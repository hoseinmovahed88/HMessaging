package com.hmessaging.data.repo

import com.hmessaging.data.db.dao.MessageDao
import com.hmessaging.data.db.dao.ThreadDao
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.db.entity.ThreadEntity
import com.hmessaging.data.model.DeliveryStatus
import com.hmessaging.data.model.MessageType
import com.hmessaging.sms.SystemSmsWriter
import com.hmessaging.util.ContactsLookup
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat
import kotlinx.coroutines.flow.Flow

/**
 * The app's own message store. Room is the source of truth for the UI; the platform SMS provider
 * is kept in sync through [SystemSmsWriter] so the rest of the system sees the same history.
 */
class MessageRepository(
    private val threadDao: ThreadDao,
    private val messageDao: MessageDao,
    private val contacts: ContactsLookup,
    private val systemWriter: SystemSmsWriter,
) {

    fun observeThreads(): Flow<List<ThreadEntity>> = threadDao.observeActive()

    fun observeArchivedThreads(): Flow<List<ThreadEntity>> = threadDao.observeArchived()

    fun observeThread(threadId: Long): Flow<ThreadEntity?> = threadDao.observeById(threadId)

    fun observeMessages(threadId: Long): Flow<List<MessageEntity>> = messageDao.observeForThread(threadId)

    fun observeTotalUnread(): Flow<Int> = threadDao.observeTotalUnread()

    fun search(query: String): Flow<List<MessageEntity>> = messageDao.search(query)

    suspend fun threadById(threadId: Long): ThreadEntity? = threadDao.byId(threadId)

    /**
     * Re-resolves the contact name of every thread.
     *
     * The name is stored on the thread row, written once when the thread is first created. If
     * contacts were unreadable at that moment — which is exactly the case during a bulk import
     * that runs before the permission is granted — the row keeps a null forever, and the list
     * shows a bare phone number even after the user grants access. Nothing re-reads it otherwise,
     * because a thread is only rewritten when a new message arrives for it.
     */
    suspend fun refreshContactNames(onlyMissing: Boolean = false): Int {
        if (!contacts.hasPermission()) return 0
        var updated = 0
        // Each unresolved address costs a contacts-provider query, and on a cold start the
        // in-memory cache is empty. Sweeping only the threads that lack a name keeps the resume
        // path from competing with the queries that populate the list.
        val candidates = if (onlyMissing) threadDao.withoutContactName() else threadDao.all()
        candidates.forEach { thread ->
            val name = contacts.nameFor(thread.address)
            if (name != thread.contactName) {
                threadDao.setContactName(thread.id, name)
                updated++
            }
        }
        return updated
    }

    /** True when this exact message is already stored, whichever broadcast delivered it. */
    suspend fun isAlreadyStored(rawAddress: String, body: String, date: Long): Boolean =
        messageDao.exists(PhoneNumbers.normalize(rawAddress), date, body)

    /** Finds or creates the thread for [rawAddress], refreshing its contact name on the way. */
    suspend fun threadIdFor(rawAddress: String): Long {
        val address = PhoneNumbers.normalize(rawAddress)
        val existing = threadDao.byAddress(address)
        if (existing != null) {
            val name = contacts.nameFor(address)
            if (name != existing.contactName) threadDao.setContactName(existing.id, name)
            return existing.id
        }
        val created = ThreadEntity(address = address, contactName = contacts.nameFor(address))
        val id = threadDao.insert(created)
        // A concurrent insert may have won the unique index; fall back to a lookup.
        return if (id > 0) id else threadDao.byAddress(address)?.id ?: 0L
    }

    suspend fun insertIncoming(
        rawAddress: String,
        body: String,
        date: Long,
        subscriptionId: Int,
        parts: Int,
        isOtp: Boolean,
        mirrorToSystem: Boolean,
    ): Pair<Long, Long> {
        val address = PhoneNumbers.normalize(rawAddress)
        val threadId = threadIdFor(address)
        val systemId = if (mirrorToSystem) {
            systemWriter.writeInbox(address, body, date, subscriptionId, read = false)
        } else {
            null
        }
        val messageId = messageDao.insert(
            MessageEntity(
                threadId = threadId,
                address = address,
                body = body,
                date = date,
                type = MessageType.INBOX,
                read = false,
                status = DeliveryStatus.NONE,
                subscriptionId = subscriptionId,
                parts = parts,
                systemId = systemId,
                isOtp = isOtp,
            ),
        )
        threadDao.touch(threadId, body.snippet(), date, unreadDelta = 1)
        return threadId to messageId
    }

    /** Records an outgoing message before it hits the radio, so the UI can show it immediately. */
    suspend fun insertOutgoing(
        rawAddress: String,
        body: String,
        date: Long,
        subscriptionId: Int,
        parts: Int,
    ): Pair<Long, Long> {
        val address = PhoneNumbers.normalize(rawAddress)
        val threadId = threadIdFor(address)
        val messageId = messageDao.insert(
            MessageEntity(
                threadId = threadId,
                address = address,
                body = body,
                date = date,
                type = MessageType.OUTBOX,
                read = true,
                status = DeliveryStatus.PENDING,
                subscriptionId = subscriptionId,
                parts = parts,
            ),
        )
        threadDao.touch(threadId, body.snippet(), date, unreadDelta = 0)
        return threadId to messageId
    }

    suspend fun markSent(messageId: Long, mirrorToSystem: Boolean) {
        val message = messageDao.byId(messageId) ?: return
        if (message.status == DeliveryStatus.FAILED) return
        messageDao.setType(messageId, MessageType.SENT)
        messageDao.setStatus(messageId, DeliveryStatus.SENT, null)
        if (mirrorToSystem && message.systemId == null) {
            val systemId = systemWriter.writeSent(
                message.address,
                message.body,
                message.date,
                message.subscriptionId,
            )
            if (systemId != null) messageDao.setSystemId(messageId, systemId)
        }
    }

    suspend fun markDelivered(messageId: Long) {
        messageDao.setStatus(messageId, DeliveryStatus.DELIVERED, null)
    }

    suspend fun markFailed(messageId: Long, error: String) {
        messageDao.setType(messageId, MessageType.FAILED)
        messageDao.setStatus(messageId, DeliveryStatus.FAILED, error)
    }

    /** Anything still in OUTBOX long after the process died can never be resolved; fail it. */
    suspend fun failStaleOutbox(olderThanMillis: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        messageDao.staleOutbox(cutoff).forEach { markFailed(it.id, STALE_OUTBOX_ERROR) }
    }

    suspend fun markThreadRead(threadId: Long) {
        messageDao.markThreadRead(threadId)
        threadDao.clearUnread(threadId)
    }

    suspend fun setPinned(threadId: Long, pinned: Boolean) = threadDao.setPinned(threadId, pinned)
    suspend fun setArchived(threadId: Long, archived: Boolean) = threadDao.setArchived(threadId, archived)
    suspend fun setMuted(threadId: Long, muted: Boolean) = threadDao.setMuted(threadId, muted)
    suspend fun setDraft(threadId: Long, draft: String?) = threadDao.setDraft(threadId, draft?.ifBlank { null })

    suspend fun deleteMessage(messageId: Long) {
        val message = messageDao.byId(messageId) ?: return
        message.systemId?.let(systemWriter::delete)
        messageDao.deleteById(messageId)
    }

    suspend fun deleteThread(threadId: Long) {
        messageDao.deleteForThread(threadId)
        threadDao.deleteById(threadId)
    }

    suspend fun purgeOldOtpMessages(retentionDays: Int): Int {
        if (retentionDays <= 0) return 0
        val cutoff = System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY
        return messageDao.deleteOtpOlderThan(cutoff).also { threadDao.deleteEmpty() }
    }

    /** Plain-text rendering of a whole conversation, for the "export" action. */
    suspend fun exportThread(threadId: Long): String {
        val thread = threadDao.byId(threadId) ?: return ""
        val messages = messageDao.listForThread(threadId)
        val title = thread.contactName ?: PhoneNumbers.format(thread.address)
        return buildString {
            appendLine(title)
            appendLine("-".repeat(title.length.coerceAtLeast(MIN_RULE_LENGTH)))
            messages.forEach { message ->
                val who = if (message.type.isIncoming) title else OUTGOING_LABEL
                appendLine("[${TimeFormat.full(message.date)}] $who: ${message.body}")
            }
        }
    }

    private fun String.snippet(): String =
        replace('\n', ' ').trim().take(SNIPPET_LENGTH)

    private companion object {
        const val SNIPPET_LENGTH = 160
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val STALE_OUTBOX_ERROR = "No send result was received"
        const val OUTGOING_LABEL = "Me"
        const val MIN_RULE_LENGTH = 8
    }
}
