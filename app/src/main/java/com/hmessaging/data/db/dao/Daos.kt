package com.hmessaging.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.hmessaging.data.db.entity.AutoReplyLogEntity
import com.hmessaging.data.db.entity.AutoReplyRuleEntity
import com.hmessaging.data.db.entity.BlockRuleEntity
import com.hmessaging.data.db.entity.BlockedMessageEntity
import com.hmessaging.data.db.entity.DiagEventEntity
import com.hmessaging.data.db.entity.ForwardLogEntity
import com.hmessaging.data.db.entity.ForwardRuleEntity
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.db.entity.OtpEntity
import com.hmessaging.data.db.entity.ScheduledMessageEntity
import com.hmessaging.data.db.entity.TemplateEntity
import com.hmessaging.data.db.entity.ThreadEntity
import com.hmessaging.data.model.DeliveryStatus
import com.hmessaging.data.model.MessageType
import com.hmessaging.data.model.ScheduleStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadDao {

    @Query("SELECT * FROM threads WHERE archived = 0 ORDER BY pinned DESC, lastMessageAt DESC")
    fun observeActive(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE archived = 1 ORDER BY lastMessageAt DESC")
    fun observeArchived(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE id = :id")
    fun observeById(id: Long): Flow<ThreadEntity?>

    @Query("SELECT * FROM threads WHERE id = :id")
    suspend fun byId(id: Long): ThreadEntity?

    @Query("SELECT * FROM threads WHERE address = :address LIMIT 1")
    suspend fun byAddress(address: String): ThreadEntity?

    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM threads WHERE archived = 0")
    fun observeTotalUnread(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(thread: ThreadEntity): Long

    @Update
    suspend fun update(thread: ThreadEntity)

    @Query(
        """
        UPDATE threads
           SET snippet = :snippet,
               lastMessageAt = :date,
               unreadCount = unreadCount + :unreadDelta
         WHERE id = :threadId
        """,
    )
    suspend fun touch(threadId: Long, snippet: String, date: Long, unreadDelta: Int)

    @Query("UPDATE threads SET unreadCount = 0 WHERE id = :threadId")
    suspend fun clearUnread(threadId: Long)

    @Query("UPDATE threads SET contactName = :name WHERE id = :threadId")
    suspend fun setContactName(threadId: Long, name: String?)

    @Query("UPDATE threads SET pinned = :pinned WHERE id = :threadId")
    suspend fun setPinned(threadId: Long, pinned: Boolean)

    @Query("UPDATE threads SET archived = :archived WHERE id = :threadId")
    suspend fun setArchived(threadId: Long, archived: Boolean)

    @Query("UPDATE threads SET muted = :muted WHERE id = :threadId")
    suspend fun setMuted(threadId: Long, muted: Boolean)

    @Query("UPDATE threads SET draft = :draft WHERE id = :threadId")
    suspend fun setDraft(threadId: Long, draft: String?)

    @Query("DELETE FROM threads WHERE id = :threadId")
    suspend fun deleteById(threadId: Long)

    /** Drops threads that no longer hold any message. */
    @Query("DELETE FROM threads WHERE id NOT IN (SELECT DISTINCT threadId FROM messages)")
    suspend fun deleteEmpty()
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY date ASC, id ASC")
    fun observeForThread(threadId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    @Query(
        """
        SELECT * FROM messages
         WHERE body LIKE '%' || :query || '%' OR address LIKE '%' || :query || '%'
         ORDER BY date DESC
         LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int = 200): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET read = 1 WHERE threadId = :threadId AND read = 0")
    suspend fun markThreadRead(threadId: Long)

    @Query("UPDATE messages SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: DeliveryStatus, error: String?)

    @Query("UPDATE messages SET type = :type WHERE id = :id")
    suspend fun setType(id: Long, type: MessageType)

    @Query("UPDATE messages SET systemId = :systemId WHERE id = :id")
    suspend fun setSystemId(id: Long, systemId: Long?)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: Long)

    @Query("DELETE FROM messages WHERE isOtp = 1 AND date < :before")
    suspend fun deleteOtpOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE type = :type")
    fun countByType(type: MessageType): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE date >= :since")
    fun countSince(since: Long): Flow<Int>

    @Query("SELECT * FROM messages ORDER BY date DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages")
    suspend fun all(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY date ASC, id ASC")
    suspend fun listForThread(threadId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE type = 'OUTBOX' AND date < :cutoff")
    suspend fun staleOutbox(cutoff: Long): List<MessageEntity>

    @Query("SELECT address || '|' || date FROM messages")
    suspend fun fingerprints(): List<String>

    @Query(
        """
        SELECT address FROM messages
         WHERE type = :type
         GROUP BY address
         ORDER BY COUNT(*) DESC
         LIMIT :limit
        """,
    )
    suspend fun topAddresses(type: MessageType, limit: Int): List<String>
}

@Dao
interface BlockDao {

    @Query("SELECT * FROM block_rules ORDER BY createdAt DESC")
    fun observeRules(): Flow<List<BlockRuleEntity>>

    @Query("SELECT * FROM block_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<BlockRuleEntity>

    @Query("SELECT * FROM block_rules")
    suspend fun allRules(): List<BlockRuleEntity>

    @Upsert
    suspend fun upsertRule(rule: BlockRuleEntity): Long

    @Delete
    suspend fun deleteRule(rule: BlockRuleEntity)

    @Query("DELETE FROM block_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)

    @Query("UPDATE block_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE block_rules SET hitCount = hitCount + 1 WHERE id = :id")
    suspend fun incrementHit(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM block_rules WHERE pattern = :pattern AND target = 'SENDER')")
    suspend fun hasExactSenderRule(pattern: String): Boolean

    @Query("SELECT * FROM blocked_messages ORDER BY date DESC LIMIT :limit")
    fun observeBlockedMessages(limit: Int = 500): Flow<List<BlockedMessageEntity>>

    @Insert
    suspend fun insertBlockedMessage(message: BlockedMessageEntity): Long

    @Query("DELETE FROM blocked_messages WHERE id = :id")
    suspend fun deleteBlockedMessage(id: Long)

    @Query("DELETE FROM blocked_messages")
    suspend fun clearBlockedMessages()

    @Query("DELETE FROM blocked_messages WHERE date < :before")
    suspend fun pruneBlockedMessages(before: Long): Int

    @Query("SELECT COUNT(*) FROM blocked_messages")
    fun observeBlockedCount(): Flow<Int>
}

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM scheduled_messages ORDER BY scheduledAt ASC")
    fun observeAll(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE status = :status ORDER BY scheduledAt ASC")
    fun observeByStatus(status: ScheduleStatus): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun byId(id: Long): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages WHERE status = 'PENDING' ORDER BY scheduledAt ASC")
    suspend fun pending(): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE status = 'PENDING' AND scheduledAt <= :now")
    suspend fun due(now: Long): List<ScheduledMessageEntity>

    @Upsert
    suspend fun upsert(message: ScheduledMessageEntity): Long

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM scheduled_messages WHERE status IN ('SENT', 'CANCELLED')")
    suspend fun clearFinished()
}

@Dao
interface AutoReplyDao {

    @Query("SELECT * FROM auto_reply_rules ORDER BY priority ASC, id ASC")
    fun observeRules(): Flow<List<AutoReplyRuleEntity>>

    @Query("SELECT * FROM auto_reply_rules WHERE enabled = 1 ORDER BY priority ASC, id ASC")
    suspend fun enabledRules(): List<AutoReplyRuleEntity>

    @Query("SELECT * FROM auto_reply_rules")
    suspend fun allRules(): List<AutoReplyRuleEntity>

    @Upsert
    suspend fun upsert(rule: AutoReplyRuleEntity): Long

    @Query("DELETE FROM auto_reply_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE auto_reply_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Insert
    suspend fun log(entry: AutoReplyLogEntity)

    @Query("SELECT MAX(sentAt) FROM auto_reply_log WHERE address = :address")
    suspend fun lastReplyTo(address: String): Long?

    @Query("SELECT COUNT(*) FROM auto_reply_log WHERE address = :address AND sentAt >= :since")
    suspend fun replyCountSince(address: String, since: Long): Int

    @Query("DELETE FROM auto_reply_log WHERE sentAt < :before")
    suspend fun pruneLog(before: Long)

    @Query("SELECT COUNT(*) FROM auto_reply_log WHERE sentAt >= :since")
    fun observeReplyCountSince(since: Long): Flow<Int>
}

@Dao
interface ForwardDao {

    @Query("SELECT * FROM forward_rules ORDER BY id ASC")
    fun observeRules(): Flow<List<ForwardRuleEntity>>

    @Query("SELECT * FROM forward_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<ForwardRuleEntity>

    @Query("SELECT * FROM forward_rules")
    suspend fun allRules(): List<ForwardRuleEntity>

    @Upsert
    suspend fun upsert(rule: ForwardRuleEntity): Long

    @Query("DELETE FROM forward_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE forward_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Insert
    suspend fun log(entry: ForwardLogEntity)

    @Query("SELECT * FROM forward_log ORDER BY sentAt DESC LIMIT :limit")
    fun observeLog(limit: Int = 200): Flow<List<ForwardLogEntity>>

    @Query("SELECT COUNT(*) FROM forward_log WHERE sentAt >= :since")
    suspend fun countSince(since: Long): Int

    @Query("DELETE FROM forward_log WHERE sentAt < :before")
    suspend fun pruneLog(before: Long)
}

@Dao
interface OtpDao {

    @Query("SELECT * FROM otp_codes ORDER BY receivedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<OtpEntity>>

    @Insert
    suspend fun insert(entity: OtpEntity): Long

    @Query("UPDATE otp_codes SET copied = 1 WHERE id = :id")
    suspend fun markCopied(id: Long)

    @Query("DELETE FROM otp_codes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM otp_codes")
    suspend fun clear()

    @Query("DELETE FROM otp_codes WHERE receivedAt < :before")
    suspend fun pruneOlderThan(before: Long): Int
}

@Dao
interface TemplateDao {

    @Query("SELECT * FROM templates ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates")
    suspend fun all(): List<TemplateEntity>

    @Query("SELECT COUNT(*) FROM templates")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(template: TemplateEntity): Long

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE templates SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun incrementUsage(id: Long)
}

@Dao
interface DiagDao {

    @Query("SELECT * FROM diag_events ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<DiagEventEntity>>

    @Query("SELECT * FROM diag_events ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<DiagEventEntity>

    @Insert
    suspend fun insert(event: DiagEventEntity)

    @Query("DELETE FROM diag_events")
    suspend fun clear()

    /** Keeps the log bounded without needing a scheduled sweep. */
    @Query("DELETE FROM diag_events WHERE id NOT IN (SELECT id FROM diag_events ORDER BY at DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
