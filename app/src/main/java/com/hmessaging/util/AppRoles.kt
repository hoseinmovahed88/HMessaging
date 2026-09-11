package com.hmessaging.util

import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import androidx.core.net.toUri

/**
 * Helpers around the privileged roles this app needs.
 *
 * Android only delivers `SMS_DELIVER` — and only allows writing to the SMS provider — to the
 * current default SMS app, so nearly every feature here depends on holding that role.
 */
object AppRoles {

    /**
     * True when this app holds the SMS role.
     *
     * Two sources are consulted because they disagree on some vendor ROMs: [RoleManager] is the
     * modern authority, while [Telephony.Sms.getDefaultSmsPackage] is what older code — and some
     * OEM settings screens — actually update. Trusting either alone leaves the app insisting it is
     * not the default while messages are being delivered to it, or the reverse.
     */
    fun isDefaultSmsApp(context: Context): Boolean {
        val byPackage = runCatching {
            context.packageName == Telephony.Sms.getDefaultSmsPackage(context)
        }.getOrDefault(false)
        return byPackage || isSmsRoleHeld(context)
    }

    fun isSmsRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching {
            roleManager.isRoleAvailable(RoleManager.ROLE_SMS) && roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        }.getOrDefault(false)
    }

    fun isSmsRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { roleManager.isRoleAvailable(RoleManager.ROLE_SMS) }.getOrDefault(false)
    }

    /** Where the user can change the default SMS app by hand when the role prompt does not stick. */
    fun defaultAppsSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }

    fun appDetailsSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        )

    /** Intent that asks the user to promote this app to default SMS handler. */
    fun defaultSmsRequestIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.createRequestRoleIntent(RoleManager.ROLE_SMS)
                ?: legacyDefaultSmsIntent(context)
        } else {
            legacyDefaultSmsIntent(context)
        }

    private fun legacyDefaultSmsIntent(context: Context): Intent =
        Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)

    fun canRequestCallScreeningRole(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isCallScreeningApp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    fun callScreeningRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return null
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }

    /** "Display over other apps" — needed to raise the OTP pop-up from the background. */
    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun overlayPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri(),
        )

    fun canScheduleExactAlarms(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else {
            true
        }

    fun exactAlarmSettingsIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                "package:${context.packageName}".toUri(),
            )
        } else {
            null
        }
}
