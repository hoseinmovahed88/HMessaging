package com.hmessaging.data.prefs

import com.hmessaging.data.model.ThemeMode

/** Immutable snapshot of every user preference, read as a single stream. */
data class AppSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
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
    val liveSyncEnabled: Boolean = true,

    // One-time flags
    val systemSmsImported: Boolean = false,
    val onboardingDone: Boolean = false,
) {
    val awayModeActive: Boolean get() = awayUntil > System.currentTimeMillis()

    companion object {
        const val SUBSCRIPTION_UNSET = -1
        const val DEFAULT_CHUNK_CHARS = 300
        const val DEFAULT_OTP_POPUP_SECONDS = 30
        const val DEFAULT_FORWARD_MAX_PER_HOUR = 60
    }
}
