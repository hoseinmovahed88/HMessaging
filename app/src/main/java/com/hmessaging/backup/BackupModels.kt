package com.hmessaging.backup

import kotlinx.serialization.Serializable

/**
 * The on-disk backup format.
 *
 * Rules, templates and settings are always included; the message history is opt-in because it
 * dominates the file size and is already mirrored in the platform SMS provider.
 */
@Serializable
data class BackupFile(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val settings: BackupSettings? = null,
    val blockRules: List<BackupBlockRule> = emptyList(),
    val autoReplyRules: List<BackupAutoReplyRule> = emptyList(),
    val forwardRules: List<BackupForwardRule> = emptyList(),
    val scheduled: List<BackupScheduled> = emptyList(),
    val templates: List<BackupTemplate> = emptyList(),
    val messages: List<BackupMessage> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class BackupSettings(
    val themeMode: String,
    val dynamicColor: Boolean,
    val deliveryReports: Boolean,
    val numberLongMessages: Boolean,
    val chunkChars: Int,
    val signature: String,
    val signatureEnabled: Boolean,
    val otpDetectionEnabled: Boolean,
    val otpPopupEnabled: Boolean,
    val otpAutoCopy: Boolean,
    val otpAutoDeleteDays: Int,
    val otpPopupSeconds: Int,
    val blockPrivateNumbers: Boolean,
    val blockNonContacts: Boolean,
    val screenCalls: Boolean,
    val keepBlockedMessages: Boolean,
    val autoReplyEnabled: Boolean,
    val forwardingEnabled: Boolean,
    val forwardMaxPerHour: Int,
    val notificationPreview: Boolean,
    val appLockEnabled: Boolean,
)

@Serializable
data class BackupBlockRule(
    val pattern: String,
    val matchType: String,
    val target: String,
    val enabled: Boolean,
    val note: String? = null,
)

@Serializable
data class BackupAutoReplyRule(
    val name: String,
    val enabled: Boolean,
    val matchType: String,
    val pattern: String,
    val replyText: String,
    val daysMask: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val cooldownMinutes: Int,
    val maxPerDay: Int,
    val priority: Int,
)

@Serializable
data class BackupForwardRule(
    val name: String,
    val enabled: Boolean,
    val matchType: String,
    val pattern: String,
    val targets: String,
    val includeSender: Boolean,
    val includeTimestamp: Boolean,
    val subscriptionId: Int,
    val template: String? = null,
)

@Serializable
data class BackupScheduled(
    val recipients: String,
    val body: String,
    val scheduledAt: Long,
    val repeat: String,
    val repeatUntil: Long? = null,
    val subscriptionId: Int,
    val status: String,
)

@Serializable
data class BackupTemplate(
    val title: String,
    val body: String,
    val sortOrder: Int,
)

@Serializable
data class BackupMessage(
    val address: String,
    val body: String,
    val date: Long,
    val type: String,
    val read: Boolean,
    val isOtp: Boolean,
    val subscriptionId: Int = -1,
)
