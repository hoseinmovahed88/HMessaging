package com.hmessaging.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hmessaging.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hm_settings")

/**
 * How incoming messages actually reached the app.
 *
 * The background watcher exists for ROMs that never deliver the SMS broadcasts. Whether this phone
 * is one of them is a fact, not a guess — so count it, and let the diagnostics screen say whether
 * the watcher is still earning the notification it has to show.
 *
 * Only a manifest receiver counts as [viaBroadcast]; the watcher's own runtime-registered receiver
 * reaches the message by re-reading the SMS store, so it lands in [missedByBroadcast]. When both
 * fire, whichever stores the message first takes the credit, and the store read is the slower of
 * the two — so the count errs towards "still needed", which is the safe direction to err in.
 */
data class DeliveryStats(val viaBroadcast: Long = 0, val missedByBroadcast: Long = 0) {
    val total: Long get() = viaBroadcast + missedByBroadcast
}

class AppPrefs(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    val deliveryStats: Flow<DeliveryStats> = store.data.map {
        DeliveryStats(
            viaBroadcast = it[Keys.DELIVERED_BY_BROADCAST] ?: 0L,
            missedByBroadcast = it[Keys.MISSED_BY_BROADCAST] ?: 0L,
        )
    }

    /** Called once per message actually stored, from whichever path got there first. */
    suspend fun recordDelivery(viaBroadcast: Boolean) {
        val key = if (viaBroadcast) Keys.DELIVERED_BY_BROADCAST else Keys.MISSED_BY_BROADCAST
        store.edit { it[key] = (it[key] ?: 0L) + 1 }
    }

    suspend fun setThemeMode(value: ThemeMode) = put(Keys.THEME, value.name)
    suspend fun setDynamicColor(value: Boolean) = put(Keys.DYNAMIC_COLOR, value)

    suspend fun setDeliveryReports(value: Boolean) = put(Keys.DELIVERY_REPORTS, value)
    suspend fun setNumberLongMessages(value: Boolean) = put(Keys.NUMBER_LONG, value)
    suspend fun setChunkChars(value: Int) = put(Keys.CHUNK_CHARS, value.coerceIn(MIN_CHUNK, MAX_CHUNK))
    suspend fun setSignature(value: String) = put(Keys.SIGNATURE, value)
    suspend fun setSignatureEnabled(value: Boolean) = put(Keys.SIGNATURE_ON, value)
    suspend fun setDefaultSubscriptionId(value: Int) = put(Keys.DEFAULT_SUB, value)

    suspend fun setOtpDetectionEnabled(value: Boolean) = put(Keys.OTP_DETECT, value)
    suspend fun setOtpPopupEnabled(value: Boolean) = put(Keys.OTP_POPUP, value)
    suspend fun setOtpAutoCopy(value: Boolean) = put(Keys.OTP_AUTO_COPY, value)
    suspend fun setOtpAutoDeleteDays(value: Int) = put(Keys.OTP_AUTO_DELETE, value.coerceIn(0, MAX_OTP_RETENTION_DAYS))
    suspend fun setOtpPopupSeconds(value: Int) = put(Keys.OTP_POPUP_SECONDS, value.coerceIn(5, 120))

    suspend fun setBlockPrivateNumbers(value: Boolean) = put(Keys.BLOCK_PRIVATE, value)
    suspend fun setBlockNonContacts(value: Boolean) = put(Keys.BLOCK_NON_CONTACTS, value)
    suspend fun setScreenCalls(value: Boolean) = put(Keys.SCREEN_CALLS, value)
    suspend fun setKeepBlockedMessages(value: Boolean) = put(Keys.KEEP_BLOCKED, value)

    suspend fun setAutoReplyEnabled(value: Boolean) = put(Keys.AUTO_REPLY, value)
    suspend fun setAwayUntil(value: Long) = put(Keys.AWAY_UNTIL, value)

    suspend fun setForwardingEnabled(value: Boolean) = put(Keys.FORWARDING, value)
    suspend fun setForwardMaxPerHour(value: Int) = put(Keys.FORWARD_MAX, value.coerceIn(1, MAX_FORWARD_PER_HOUR))

    suspend fun setNotificationPreview(value: Boolean) = put(Keys.NOTIF_PREVIEW, value)
    suspend fun setAppLockEnabled(value: Boolean) = put(Keys.APP_LOCK, value)
    suspend fun setLiveSyncEnabled(value: Boolean) = put(Keys.LIVE_SYNC, value)
    suspend fun setSystemSmsImported(value: Boolean) = put(Keys.SMS_IMPORTED, value)
    suspend fun setOnboardingDone(value: Boolean) = put(Keys.ONBOARDING, value)

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<Int>, value: Int) {
        store.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<Long>, value: Long) {
        store.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<String>, value: String) {
        store.edit { it[key] = value }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = this[Keys.THEME]?.let { name ->
                runCatching { ThemeMode.valueOf(name) }.getOrNull()
            } ?: defaults.themeMode,
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            deliveryReports = this[Keys.DELIVERY_REPORTS] ?: defaults.deliveryReports,
            numberLongMessages = this[Keys.NUMBER_LONG] ?: defaults.numberLongMessages,
            chunkChars = this[Keys.CHUNK_CHARS] ?: defaults.chunkChars,
            signature = this[Keys.SIGNATURE] ?: defaults.signature,
            signatureEnabled = this[Keys.SIGNATURE_ON] ?: defaults.signatureEnabled,
            defaultSubscriptionId = this[Keys.DEFAULT_SUB] ?: defaults.defaultSubscriptionId,
            otpDetectionEnabled = this[Keys.OTP_DETECT] ?: defaults.otpDetectionEnabled,
            otpPopupEnabled = this[Keys.OTP_POPUP] ?: defaults.otpPopupEnabled,
            otpAutoCopy = this[Keys.OTP_AUTO_COPY] ?: defaults.otpAutoCopy,
            otpAutoDeleteDays = this[Keys.OTP_AUTO_DELETE] ?: defaults.otpAutoDeleteDays,
            otpPopupSeconds = this[Keys.OTP_POPUP_SECONDS] ?: defaults.otpPopupSeconds,
            blockPrivateNumbers = this[Keys.BLOCK_PRIVATE] ?: defaults.blockPrivateNumbers,
            blockNonContacts = this[Keys.BLOCK_NON_CONTACTS] ?: defaults.blockNonContacts,
            screenCalls = this[Keys.SCREEN_CALLS] ?: defaults.screenCalls,
            keepBlockedMessages = this[Keys.KEEP_BLOCKED] ?: defaults.keepBlockedMessages,
            autoReplyEnabled = this[Keys.AUTO_REPLY] ?: defaults.autoReplyEnabled,
            awayUntil = this[Keys.AWAY_UNTIL] ?: defaults.awayUntil,
            forwardingEnabled = this[Keys.FORWARDING] ?: defaults.forwardingEnabled,
            forwardMaxPerHour = this[Keys.FORWARD_MAX] ?: defaults.forwardMaxPerHour,
            notificationPreview = this[Keys.NOTIF_PREVIEW] ?: defaults.notificationPreview,
            appLockEnabled = this[Keys.APP_LOCK] ?: defaults.appLockEnabled,
            liveSyncEnabled = this[Keys.LIVE_SYNC] ?: defaults.liveSyncEnabled,
            systemSmsImported = this[Keys.SMS_IMPORTED] ?: defaults.systemSmsImported,
            onboardingDone = this[Keys.ONBOARDING] ?: defaults.onboardingDone,
        )
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DELIVERY_REPORTS = booleanPreferencesKey("delivery_reports")
        val NUMBER_LONG = booleanPreferencesKey("number_long_messages")
        val CHUNK_CHARS = intPreferencesKey("chunk_chars")
        val SIGNATURE = stringPreferencesKey("signature")
        val SIGNATURE_ON = booleanPreferencesKey("signature_enabled")
        val DEFAULT_SUB = intPreferencesKey("default_subscription")
        val OTP_DETECT = booleanPreferencesKey("otp_detection")
        val OTP_POPUP = booleanPreferencesKey("otp_popup")
        val OTP_AUTO_COPY = booleanPreferencesKey("otp_auto_copy")
        val OTP_AUTO_DELETE = intPreferencesKey("otp_auto_delete_days")
        val OTP_POPUP_SECONDS = intPreferencesKey("otp_popup_seconds")
        val BLOCK_PRIVATE = booleanPreferencesKey("block_private")
        val BLOCK_NON_CONTACTS = booleanPreferencesKey("block_non_contacts")
        val SCREEN_CALLS = booleanPreferencesKey("screen_calls")
        val KEEP_BLOCKED = booleanPreferencesKey("keep_blocked_messages")
        val AUTO_REPLY = booleanPreferencesKey("auto_reply_enabled")
        val AWAY_UNTIL = longPreferencesKey("away_until")
        val FORWARDING = booleanPreferencesKey("forwarding_enabled")
        val FORWARD_MAX = intPreferencesKey("forward_max_per_hour")
        val NOTIF_PREVIEW = booleanPreferencesKey("notification_preview")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val LIVE_SYNC = booleanPreferencesKey("live_sync")
        val SMS_IMPORTED = booleanPreferencesKey("system_sms_imported")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val DELIVERED_BY_BROADCAST = longPreferencesKey("delivered_by_broadcast")
        val MISSED_BY_BROADCAST = longPreferencesKey("missed_by_broadcast")
    }

    private companion object {
        const val MIN_CHUNK = 60
        const val MAX_CHUNK = 1000
        const val MAX_OTP_RETENTION_DAYS = 365
        const val MAX_FORWARD_PER_HOUR = 500
    }
}
