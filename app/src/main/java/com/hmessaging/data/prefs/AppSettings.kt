package com.hmessaging.data.prefs

import com.hmessaging.data.model.ThemeMode

/** Immutable snapshot of every user preference, read as a single stream. */
data class AppSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Write dates in the Solar Hijri calendar. On by default; this app is read in Iran. */
    val persianCalendar: Boolean = true,
    // Off by default so the app's own HyperOS-style palette is what ships.
    val dynamicColor: Boolean = false,
    // Sending
    val deliveryReports: Boolean = false,
    val numberLongMessages: Boolean = false,
    val chunkChars: Int = DEFAULT_CHUNK_CHARS,
    val signature: String = "",
    val signatureEnabled: Boolean = false,
    val defaultSubscriptionId: Int = SUBSCRIPTION_UNSET,
    // OTP
    val otpDetectionEnabled: Boolean = true,
    val otpPopupEnabled: Boolean = true,
    val otpAutoCopy: Boolean = true,
    val otpAutoDeleteDays: Int = 0,
    val otpPopupSeconds: Int = DEFAULT_OTP_POPUP_SECONDS,
    // Blocking
    val blockPrivateNumbers: Boolean = false,
    val blockNonContacts: Boolean = false,
    val screenCalls: Boolean = false,
    val keepBlockedMessages: Boolean = true,
    // Answering machine
    val autoReplyEnabled: Boolean = false,
    val awayUntil: Long = 0L,
    // Forwarding
    val forwardingEnabled: Boolean = false,
    val forwardMaxPerHour: Int = DEFAULT_FORWARD_MAX_PER_HOUR,
    // Notifications
    val notificationPreview: Boolean = true,
    /** Floating reply window over whatever is on screen, for messages that arrive while in use. */
    val quickReplyPopupEnabled: Boolean = true,
    // Updates
    /** Where to look for a published release. Blank means the address built into the app. */
    val updateManifestUrl: String = "",
    // Security
    val appLockEnabled: Boolean = false,
    // Delivery
    // Off unless asked for: it costs a permanent notification, and on a phone where this app
    // is the default SMS app it cannot find anything anyway (see WatcherNeed.shouldRun).
    val liveSyncEnabled: Boolean = false,

    // One-time flags
    val systemSmsImported: Boolean = false,
    /** One-shot: whether the sweep for duplicated messages has already run. */
    val duplicatesCleaned: Boolean = false,
    val onboardingDone: Boolean = false,
    // Backup
    /** A daily copy of every message in the public Download folder, which outlives the app. */
    val autoBackupEnabled: Boolean = true,
    val lastBackupAt: Long = 0L,
    val lastBackupCount: Int = 0,
    /** The SMS-store row count at which the last full walk for missing messages ran. */
    val gapCheckedAtRows: Long = -1L,
) {
    val awayModeActive: Boolean get() = awayUntil > System.currentTimeMillis()

    companion object {
        const val SUBSCRIPTION_UNSET = -1
        const val DEFAULT_CHUNK_CHARS = 300
        const val DEFAULT_OTP_POPUP_SECONDS = 30
        const val DEFAULT_FORWARD_MAX_PER_HOUR = 60
    }
}
