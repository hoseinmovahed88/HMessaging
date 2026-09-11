package com.hmessaging.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hmessaging.data.model.DeliveryStatus
import com.hmessaging.data.model.MatchTarget
import com.hmessaging.data.model.MatchType
import com.hmessaging.data.model.MessageType
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.data.model.ScheduleStatus
import com.hmessaging.data.model.SourceMatch

@Entity(
    tableName = "threads",
    indices = [
        Index(value = ["address"], unique = true),
        Index(value = ["matchKey"]),
        Index(value = ["lastMessageAt"]),
    ],
)
data class ThreadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalised phone number or alphanumeric short-code, as the operator sent it. */
    val address: String,
    /**
     * What the conversation is actually keyed on: see [com.hmessaging.util.PhoneNumbers.threadKey].
     * Two spellings of one number share this, so they share a conversation.
     */
    val matchKey: String = "",
    val contactName: String? = null,
    val snippet: String = "",
    val lastMessageAt: Long = 0L,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val draft: String? = null,
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["threadId"]),
        Index(value = ["date"]),
        Index(value = ["address"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: MessageType,
    val read: Boolean = false,
    val status: DeliveryStatus = DeliveryStatus.NONE,
    val subscriptionId: Int = -1,
    /** Number of SMS parts this message was split into on the wire. */
    val parts: Int = 1,
    val errorMessage: String? = null,
    /** `_id` of the row we mirrored into the system SMS provider, when we are the default app. */
    val systemId: Long? = null,
    val isOtp: Boolean = false,
)

@Entity(tableName = "block_rules")
data class BlockRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val matchType: MatchType = MatchType.EXACT,
    val target: MatchTarget = MatchTarget.SENDER,
    val enabled: Boolean = true,
    val note: String? = null,
    val hitCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "blocked_messages", indices = [Index(value = ["date"])])
data class BlockedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val body: String,
    val date: Long,
    /** Human-readable description of the rule that caught it. */
    val reason: String,
    val ruleId: Long? = null,
)

@Entity(
    tableName = "scheduled_messages",
    indices = [Index(value = ["scheduledAt"]), Index(value = ["status"])],
)
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** One or more recipients, separated by [com.hmessaging.util.RECIPIENT_SEPARATOR]. */
    val recipients: String,
    val body: String,
    val scheduledAt: Long,
    val repeat: RepeatMode = RepeatMode.NONE,
    val repeatUntil: Long? = null,
    val subscriptionId: Int = -1,
    val status: ScheduleStatus = ScheduleStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val sentCount: Int = 0,
)

@Entity(tableName = "auto_reply_rules")
data class AutoReplyRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val matchType: SourceMatch = SourceMatch.ALL,
    val pattern: String = "",
    val replyText: String,
    /** Bit 0 = Monday … bit 6 = Sunday. */
    val daysMask: Int = ALL_DAYS,
    val startMinuteOfDay: Int = 0,
    val endMinuteOfDay: Int = MINUTES_PER_DAY - 1,
    /** Minimum gap before the same sender may be answered again. */
    val cooldownMinutes: Int = 60,
    val maxPerDay: Int = 5,
    /** Lower runs first; the first matching rule wins. */
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val ALL_DAYS = 0b111_1111
        const val MINUTES_PER_DAY = 24 * 60
    }
}

@Entity(tableName = "auto_reply_log", indices = [Index(value = ["address", "sentAt"])])
data class AutoReplyLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val address: String,
    val sentAt: Long,
)

@Entity(tableName = "forward_rules")
data class ForwardRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val matchType: SourceMatch = SourceMatch.ALL,
    val pattern: String = "",
    /** One or more destinations, separated by [com.hmessaging.util.RECIPIENT_SEPARATOR]. */
    val targets: String,
    val includeSender: Boolean = true,
    val includeTimestamp: Boolean = false,
    val subscriptionId: Int = -1,
    /** Optional custom layout; supports {sender} {body} {time} placeholders. */
    val template: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "forward_log", indices = [Index(value = ["sentAt"])])
data class ForwardLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val fromAddress: String,
    val target: String,
    val sentAt: Long,
    val success: Boolean,
    val error: String? = null,
)

@Entity(tableName = "otp_codes", indices = [Index(value = ["receivedAt"])])
data class OtpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    /** Best-effort guess at the service that sent the code. */
    val serviceName: String? = null,
    val copied: Boolean = false,
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val sortOrder: Int = 0,
    val usageCount: Int = 0,
)

/**
 * Append-only record of what the system actually delivered to this app.
 *
 * Exists because "no messages arrive" has several very different causes — the role is not held,
 * the permission is denied, the broadcast never fires, or the message was blocked — and they are
 * indistinguishable from the outside.
 */
@Entity(tableName = "diag_events", indices = [Index(value = ["at"])])
data class DiagEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
    val detail: String,
)
