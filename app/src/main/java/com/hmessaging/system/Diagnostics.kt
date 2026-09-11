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
import com.hmessaging.data.db.dao.ThreadDao
import com.hmessaging.data.db.entity.DiagEventEntity
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.sms.SmsSyncService
import com.hmessaging.sms.SystemSmsWriter
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
    private val threadDao: ThreadDao,
    private val prefs: AppPrefs,
    private val systemWriter: SystemSmsWriter,
) {

    private fun contactsPermissionGranted(): Boolean =
        Permissions.has(context, Manifest.permission.READ_CONTACTS)

    /**
     * Not every deviation stops messages arriving.
     *
     * Treating them alike made the screen shout "something is blocking incoming messages" over a
     * platform quirk that costs nothing, which is worse than saying nothing: it trains the reader
     * to ignore the one line that will eventually matter.
     */
    enum class Severity { OK, WARNING, PROBLEM }

    data class Check(val label: String, val severity: Severity, val detail: String) {
        val ok: Boolean get() = severity == Severity.OK
    }

    data class Report(
        val checks: List<Check>,
        val events: List<DiagEventEntity>,
    ) {
        val problems: List<Check> get() = checks.filter { it.severity == Severity.PROBLEM }
        val warnings: List<Check> get() = checks.filter { it.severity == Severity.WARNING }
        val allOk: Boolean get() = problems.isEmpty() && warnings.isEmpty()

        /** The single most useful fact on the screen: has anything actually been delivered? */
        val lastDelivery: DiagEventEntity? get() = events.firstOrNull {
            it.kind == KIND_SMS_DELIVER || it.kind == KIND_SMS_RECEIVED || it.kind == KIND_SYNC
        }

        fun asText(): String = buildString {
            appendLine("HMessaging diagnostics")
            appendLine("generated ${TimeFormat.full(System.currentTimeMillis())}")
            appendLine()
            checks.forEach {
                val tag = when (it.severity) {
                    Severity.OK -> "OK  "
                    Severity.WARNING -> "WARN"
                    Severity.PROBLEM -> "FAIL"
                }
                appendLine("$tag  ${it.label}: ${it.detail}")
            }
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
                severity = when {
                    defaultPackage == context.packageName -> Severity.OK
                    // RoleManager is the authority from Android 10 on. Some vendor builds never
                    // update the legacy getDefaultSmsPackage, and messages still arrive.
                    roleHeld -> Severity.WARNING
                    else -> Severity.PROBLEM
                },
                detail = when {
                    defaultPackage == context.packageName -> "this app"
                    roleHeld ->
                        "the SMS role is held, which is what counts; this phone's older " +
                            "getDefaultSmsPackage reports " + (defaultPackage ?: "none") +
                            ", which is a known vendor quirk and harmless"
                    defaultPackage == null -> "the platform reports no default SMS app"
                    else -> "currently $defaultPackage"
                },
            ),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(
                Check(
                    label = "SMS role held",
                    severity = if (roleHeld) Severity.OK else Severity.PROBLEM,
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
            add(
                Check(
                    label = label,
                    severity = if (granted) Severity.OK else Severity.PROBLEM,
                    detail = if (granted) "granted" else "DENIED — grant it in app settings",
                ),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = Permissions.has(context, Manifest.permission.POST_NOTIFICATIONS)
            add(
                Check(
                    label = "Notifications",
                    // Messages still arrive without it; they just do so silently.
                    severity = if (granted) Severity.OK else Severity.WARNING,
                    detail = if (granted) "granted" else "denied — messages still arrive, silently",
                ),
            )
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
                // The foreground watcher covers this; it only matters if that is switched off.
                severity = if (isIgnoringBatteryOptimizations()) Severity.OK else Severity.WARNING,
                detail = if (isIgnoringBatteryOptimizations()) {
                    "unrestricted"
                } else {
                    "restricted — some ROMs stop background receivers; set battery to 'no restrictions'"
                },
            ),
        )

        // getRunningServices walks every running service, so ask once.
        val watcherRunning = SmsSyncService.isRunning(context)
        val stats = prefs.deliveryStats.first()
        add(
            Check(
                label = "Background watcher",
                severity = if (watcherRunning) Severity.OK else Severity.WARNING,
                detail = buildString {
                    append(
                        if (watcherRunning) {
                            "running — the SMS store is being watched directly"
                        } else {
                            "not running — delivery depends on the system waking the app"
                        },
                    )
                    // Whether this phone needs the watcher is measurable, so measure it rather
                    // than leaving the reader to wonder what the notification is buying them.
                    append(
                        when {
                            stats.total == 0L ->
                                "; no message has arrived yet, so there is nothing to judge it by"

                            stats.missedByBroadcast == 0L ->
                                "; all ${stats.total} messages so far arrived by system broadcast, " +
                                    "so this phone does not appear to need it — you can switch it " +
                                    "off under Settings › Reliable delivery"

                            else ->
                                "; ${stats.missedByBroadcast} of ${stats.total} messages never " +
                                    "produced a system broadcast and were only found by reading " +
                                    "the SMS store, so switching it off would risk losing those"
                        },
                    )
                },
            ),
        )

        val threads = threadDao.all()
        val named = threads.count { it.contactName != null }
        add(
            Check(
                label = "Contact names",
                severity = if (threads.isEmpty() || named > 0 || !contactsPermissionGranted()) {
                    Severity.OK
                } else {
                    Severity.WARNING
                },
                detail = "$named of ${threads.size} conversations resolved to a contact",
            ),
        )

        // The question another app asking "what was the last message?" actually depends on.
        val recentSent = messageDao.recentSent(SENT_SAMPLE)
        val mirrored = recentSent.count { systemWriter.exists(it.address, it.date, it.body) }
        add(
            Check(
                label = "Sent messages visible to other apps",
                severity = when {
                    recentSent.isEmpty() -> Severity.OK
                    mirrored == recentSent.size -> Severity.OK
                    else -> Severity.WARNING
                },
                detail = when {
                    recentSent.isEmpty() -> "nothing sent from this app yet"
                    mirrored == recentSent.size ->
                        "the last $mirrored are in the phone's SMS store"
                    systemWriter.canWrite() ->
                        "only $mirrored of the last ${recentSent.size} reached the phone's SMS store"
                    else ->
                        "$mirrored of the last ${recentSent.size} reached the phone's SMS store — " +
                            "Android refuses these writes unless it names this app as default, so " +
                            "other apps cannot see messages sent from here"
                },
            ),
        )

        val stored = messageDao.count()
        add(
            Check(
                label = "Messages stored",
                // A symptom, never a cause — an empty database on a fresh install is normal.
                severity = if (stored > 0) Severity.OK else Severity.WARNING,
                detail = "$stored in the app database",
            ),
        )

        val alreadyImported = prefs.settings.first().systemSmsImported
        add(
            Check(
                label = "History import",
                severity = if (!alreadyImported || stored > 0) Severity.OK else Severity.WARNING,
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
                severity = if (inProvider >= 0) Severity.OK else Severity.PROBLEM,
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
            severity = if (mine) Severity.OK else Severity.PROBLEM,
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
        private const val SENT_SAMPLE = 10
    }
}
