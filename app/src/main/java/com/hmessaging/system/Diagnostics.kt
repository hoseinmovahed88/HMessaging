package com.hmessaging.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.TelephonyManager
import com.hmessaging.data.db.dao.DiagDao
import com.hmessaging.data.db.dao.MessageDao
import com.hmessaging.data.db.entity.DiagEventEntity
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.sms.SmsSyncService
import com.hmessaging.util.AppRoles
import com.hmessaging.util.Permissions
import com.hmessaging.util.TimeFormat
import kotlinx.coroutines.flow.first

/**
 * Answers "why is nothing arriving?" without a debugger attached.
 *
 * The four causes look identical from the UI — the role is not held, a permission is denied, the
 * broadcast never fires, or the message was blocked — so each is measured separately here.
 */
class Diagnostics(
    private val context: Context,
    private val diagDao: DiagDao,
    private val messageDao: MessageDao,
    private val prefs: AppPrefs,
) {

    data class Check(val label: String, val ok: Boolean, val detail: String)

    data class Report(
        val checks: List<Check>,
        val events: List<DiagEventEntity>,
    ) {
        val allOk: Boolean get() = checks.all { it.ok }

        fun asText(): String = buildString {
            appendLine("HMessaging diagnostics")
            appendLine("generated ${TimeFormat.full(System.currentTimeMillis())}")
            appendLine()
            checks.forEach { appendLine("${if (it.ok) "OK  " else "FAIL"}  ${it.label}: ${it.detail}") }
            appendLine()
            appendLine("Recent telephony events (${events.size}):")
            if (events.isEmpty()) appendLine("  none recorded")
            events.forEach { appendLine("  ${TimeFormat.full(it.at)}  ${it.kind}  ${it.detail}") }
        }
    }

    suspend fun collect(): Report = Report(checks = runChecks(), events = diagDao.recent())

    private suspend fun runChecks(): List<Check> = buildList {
        val defaultPackage = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        val roleHeld = AppRoles.isSmsRoleHeld(context)
        add(
            Check(
                label = "Default SMS app",
                ok = defaultPackage == context.packageName,
                detail = when {
                    defaultPackage == context.packageName -> "this app"
                    defaultPackage == null && roleHeld ->
                        "the platform reports none, though the SMS role is held — messages may " +
                            "arrive only as SMS_RECEIVED"
                    defaultPackage == null -> "the platform reports no default SMS app"
                    else -> "currently $defaultPackage"
                },
            ),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(
                Check(
                    label = "SMS role held",
                    ok = AppRoles.isSmsRoleHeld(context),
                    detail = if (AppRoles.isSmsRoleAvailable(context)) {
                        "role is offered by this device"
                    } else {
                        "this device does not offer the SMS role"
                    },
                ),
            )
        }

        listOf(
            "Receive SMS" to Manifest.permission.RECEIVE_SMS,
            "Read SMS" to Manifest.permission.READ_SMS,
            "Send SMS" to Manifest.permission.SEND_SMS,
            "Read contacts" to Manifest.permission.READ_CONTACTS,
            "Phone state" to Manifest.permission.READ_PHONE_STATE,
        ).forEach { (label, permission) ->
            val granted = Permissions.has(context, permission)
            add(Check(label, granted, if (granted) "granted" else "DENIED — grant it in app settings"))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = Permissions.has(context, Manifest.permission.POST_NOTIFICATIONS)
            add(Check("Notifications", granted, if (granted) "granted" else "denied — messages still arrive, silently"))
        }

        // A component the system cannot resolve makes the app ineligible for the role, which is
        // the failure mode that looks most like "nothing happens".
        add(resolutionCheck("SMS_DELIVER receiver", Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION), broadcast = true))
        add(
            resolutionCheck(
                "WAP_PUSH_DELIVER receiver",
                Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                    type = "application/vnd.wap.mms-message"
                },
                broadcast = true,
            ),
        )
        add(
            resolutionCheck(
                "Quick-response service",
                Intent(TelephonyManager.ACTION_RESPOND_VIA_MESSAGE, Uri.parse("smsto:")),
                broadcast = false,
                service = true,
            ),
        )
        add(
            resolutionCheck(
                "SENDTO activity",
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                broadcast = false,
            ),
        )

        add(
            Check(
                label = "Battery restrictions",
                ok = isIgnoringBatteryOptimizations(),
                detail = if (isIgnoringBatteryOptimizations()) {
                    "unrestricted"
                } else {
                    "restricted — some ROMs stop background receivers; set battery to 'no restrictions'"
                },
            ),
        )

        // getRunningServices walks every running service, so ask once.
        val watcherRunning = SmsSyncService.isRunning(context)
        add(
            Check(
                label = "Background watcher",
                ok = watcherRunning,
                detail = if (watcherRunning) {
                    "running — the SMS store is being watched directly"
                } else {
                    "not running — delivery depends on the system waking the app"
                },
            ),
        )

        val stored = messageDao.count()
        add(Check("Messages stored", stored > 0, "$stored in the app database"))

        val alreadyImported = prefs.settings.first().systemSmsImported
        add(
            Check(
                label = "History import",
                ok = !alreadyImported || stored > 0,
                detail = if (alreadyImported) {
                    "already run — use \"Import existing messages now\" to run it again"
                } else {
                    "has not run yet"
                },
            ),
        )

        val inProvider = countSystemMessages()
        add(
            Check(
                label = "Messages in the phone's SMS store",
                ok = inProvider >= 0,
                detail = if (inProvider < 0) "not readable (READ_SMS denied)" else "$inProvider readable",
            ),
        )
    }

    private fun resolutionCheck(
        label: String,
        intent: Intent,
        broadcast: Boolean,
        service: Boolean = false,
    ): Check {
        val manager = context.packageManager
        val resolved = runCatching {
            when {
                broadcast -> manager.queryBroadcastReceivers(intent, 0).map { it.activityInfo.packageName }
                service -> manager.queryIntentServices(intent, 0).map { it.serviceInfo.packageName }
                else -> manager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
            }
        }.getOrDefault(emptyList())
        val mine = context.packageName in resolved
        return Check(
            label = label,
            ok = mine,
            detail = if (mine) "registered" else "NOT registered (resolvers: ${resolved.size})",
        )
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    /** Negative means the provider could not be read at all. */
    private fun countSystemMessages(): Int {
        if (!Permissions.has(context, Manifest.permission.READ_SMS)) return -1
        return runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null,
                null,
                null,
            )?.use { it.count } ?: -1
        }.getOrDefault(-1)
    }

    suspend fun record(kind: String, detail: String) {
        runCatching {
            diagDao.insert(DiagEventEntity(at = System.currentTimeMillis(), kind = kind, detail = detail))
            diagDao.trimTo(MAX_EVENTS)
        }
    }

    suspend fun clear() = diagDao.clear()

    companion object {
        const val KIND_SMS_DELIVER = "SMS_DELIVER"
        const val KIND_SMS_RECEIVED = "SMS_RECEIVED"
        const val KIND_WAP_PUSH = "WAP_PUSH"
        const val KIND_STORED = "STORED"
        const val KIND_BLOCKED = "BLOCKED"
        const val KIND_DUPLICATE = "DUPLICATE"
        const val KIND_SYNC = "SYNC"
        const val KIND_IMPORT = "IMPORT"
        const val KIND_SEND = "SEND"
        const val KIND_ERROR = "ERROR"

        private const val MAX_EVENTS = 300
    }
}
