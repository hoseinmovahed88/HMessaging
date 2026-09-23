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

    /**
     * Message search, asked in each spelling the text might be stored in and each form the number
     * might be written in. Unused slots are passed as empty and the query ignores them.
     */
    fun search(query: String): Flow<List<MessageEntity>> {
        val spellings = PhoneNumbers.spellingVariants(query)
        // The longest digit form, so "0912…" narrows rather than matching every number containing
        // a nine. The tail forms are what contact lookup needs; a body search wants the opposite.
        val digits = PhoneNumbers.digitVariants(query).maxByOrNull { it.length }.orEmpty()
        return messageDao.search(
            query = spellings.getOrElse(0) { "" },
            alternate = spellings.getOrElse(1) { "" },
            localized = spellings.getOrElse(2) { "" },
            digits = digits,
        )
    }

    /**
     * Removes messages that are a second copy of one already held.
     *
     * Both directions come from the same place — the platform SMS store — and from the same
     * cause: the two copies of one message are stamped with two different clocks. A message that
     * arrived by broadcast is stored under the time the network centre accepted it, while the
     * store's row is stamped when the phone received it; a message this app sent is mirrored into
     * the store, which may replace the timestamp on insert. Either way the next scan reads a row
     * whose timestamp matches nothing here and files it as new.
     *
     * The import guard stops new ones. This clears what is already stored, keeping the original
     * of each pair — the row carrying the part count and the delivery status — and leaving the
     * platform's own row alone, since that one is not a copy of anything.
     */
    suspend fun removeDuplicateMessages(): Int {
        val ids = messageDao.duplicateIds(DUPLICATE_WINDOW_MS)
        ids.forEach { messageDao.deleteById(it) }
        if (ids.isNotEmpty()) threadDao.rebuildSummaries()
        return ids.size
    }

    /** What one [backfillSystemProvider] pass managed. */
    data class ProviderBackfill(val written: Int, val alreadyThere: Int, val refused: Int)

    /**
     * Writes the messages this app already holds into the platform SMS provider.
     *
     * Needed because the provider is not a mirror that catches up on its own: this app is the only
     * one allowed to write it, and anything it did not write at the time is missing from every
     * other app on the phone, permanently. Messages stored before this app wrote thread ids — or
     * during any spell when the platform did not consider it the default — are exactly that.
     *
     * Each row keeps its own original timestamp rather than the time of the backfill, so the
     * history stays in the order it happened. Existing rows are matched on (address, date, body)
     * before writing, so running this twice cannot duplicate anything, and the id the provider
     * gives back is stored so later scans recognise the row as one of ours.
     *
     * Returns without writing anything when the app does not hold the role, which is not a
     * failure: the provider refuses those writes, and the answer is to try again later.
     */
    suspend fun backfillSystemProvider(limit: Int = PROVIDER_BACKFILL_LIMIT): ProviderBackfill {
        if (!systemWriter.canWrite()) return ProviderBackfill(0, 0, refused = 1)

        var written = 0
        var alreadyThere = 0
        var refused = 0

        // Oldest first, so a run that hits the limit leaves the newest messages for the next one
        // and the provider fills in the order a reader would expect.
        for (message in messageDao.oldestWithoutSystemId(limit)) {
            if (message.body.isBlank() || message.address == PhoneNumbers.UNKNOWN_ADDRESS) continue
            if (systemWriter.exists(message.address, message.date, message.body)) {
                alreadyThere++
                continue
            }
            val systemId = when {
                message.type.isIncoming -> systemWriter.writeInbox(
                    address = message.address,
                    body = message.body,
                    date = message.date,
                    subscriptionId = message.subscriptionId,
                    read = message.read,
                )

                message.type == MessageType.SENT -> systemWriter.writeSent(
                    address = message.address,
                    body = message.body,
                    date = message.date,
                    subscriptionId = message.subscriptionId,
                )

                // Drafts, failures and anything still in the outbox are this app's business until
                // they are actually sent; the provider is for messages that happened.
                else -> null
            }
            if (systemId != null) {
                messageDao.setSystemId(message.id, systemId)
                written++
            } else if (message.type.isIncoming || message.type == MessageType.SENT) {
                refused++
            }
        }
        return ProviderBackfill(written, alreadyThere, refused)
    }

    /**
     * Drops the recorded platform-store id of every message whose row is no longer there.
     *
     * The ids are recorded as proof that a message is in the store, and the backfill trusts that
     * proof absolutely — a message with an id is never written again. That is fine while the store
     * only grows, and wrong the moment it does not: the history imported from the phone arrives
     * already carrying the ids of the rows it was read from, so if those rows are later cleared,
     * by another messaging app, a cleanup tool or the ROM itself, every imported message is at once
     * missing from the phone and invisible to the repair meant to fix exactly that. Which is how a
     * dialer ends up saying there are no messages while the app holds years of them.
     *
     * Reading the store must succeed first. A store that would not answer is not an empty one, and
     * treating it as empty would throw away every id for nothing.
     *
     * Returns how many messages were put back in the queue.
     */
    suspend fun reconcileSystemProvider(): Int {
        val present = systemWriter.allRowIds() ?: return 0
        val stale = messageDao.knownSystemIds().filterNot { it in present }
        if (stale.isEmpty()) return 0
        // SQLite refuses a statement with more bound parameters than it allows, and this list is
        // as long as the message history.
        stale.chunked(SQL_PARAMETER_CHUNK).forEach { messageDao.clearSystemIds(it) }
        return stale.size
    }

    /** Gives a thread id to platform-store rows written without one. See [SystemSmsWriter]. */
    suspend fun repairProviderThreadIds(): Int = systemWriter.repairThreadIds()

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

    /**
     * True when this message is already stored, whichever route delivered it.
     *
     * Not an exact timestamp match. The same message reaches this app under two clocks — the
     * network centre's when it arrives as a broadcast, the phone's when it is read back out of
     * the platform store — so comparing them exactly answers "no" for every copy.
     */
    suspend fun isAlreadyStored(
        rawAddress: String,
        body: String,
        date: Long,
        incoming: Boolean = true,
    ): Boolean {
        val address = PhoneNumbers.normalize(rawAddress)
        if (messageDao.exists(address, date, body)) return true
        return messageDao.existsNear(
            address = address,
            body = body,
            from = date - DUPLICATE_WINDOW_MS,
            to = date + DUPLICATE_WINDOW_MS,
            incoming = incoming,
        )
    }

    /**
     * Finds or creates the thread for [rawAddress], refreshing its contact name on the way.
     *
     * Matched on [PhoneNumbers.threadKey] rather than the address itself, so the same person
     * reaching you as `+989121234567` on one message and `09121234567` on the next stays in one
     * conversation instead of two.
     */
    suspend fun threadIdFor(rawAddress: String): Long {
        val address = PhoneNumbers.normalize(rawAddress)
        val key = PhoneNumbers.threadKey(address)

        val existing = threadDao.byMatchKey(key) ?: threadDao.byAddress(address)
        if (existing != null) {
            if (existing.matchKey != key) threadDao.setMatchKey(existing.id, key)
            val name = contacts.nameFor(address)
            if (name != existing.contactName) threadDao.setContactName(existing.id, name)
            return existing.id
        }

        val created = ThreadEntity(
            address = address,
            matchKey = key,
            contactName = contacts.nameFor(address),
        )
        val id = threadDao.insert(created)
        // A concurrent insert may have won the unique index; fall back to a lookup.
        return if (id > 0) id else threadDao.byMatchKey(key)?.id ?: threadDao.byAddress(address)?.id ?: 0L
    }

    /**
     * Collapses conversations that are the same person written two ways.
     *
     * Needed once, for histories built before threads were keyed on the significant digits, and
     * cheap enough afterwards that it can simply run whenever any thread is still unkeyed.
     */
    suspend fun mergeDuplicateThreads(): Int {
        if (threadDao.countWithoutMatchKey() == 0) return 0

        var merged = 0
        threadDao.all()
            .groupBy { PhoneNumbers.threadKey(it.address) }
            .forEach { (key, group) ->
                // Keep the oldest row so its id, and anything already pointing at it, survives.
                val survivor = group.minBy { it.id }
                if (survivor.matchKey != key) threadDao.setMatchKey(survivor.id, key)

                group.filter { it.id != survivor.id }.forEach { duplicate ->
                    messageDao.moveToThread(source = duplicate.id, target = survivor.id)
                    threadDao.deleteById(duplicate.id)
                    merged++
                }
            }

        if (merged > 0) threadDao.rebuildSummaries()
        return merged
    }

    suspend fun messageById(id: Long): MessageEntity? = messageDao.byId(id)

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
        threadDao.touch(threadId, body.snippet(), date, subscriptionId, unreadDelta = 1)
        return threadId to messageId
    }

    /**
     * Records an outgoing message before it hits the radio, so the UI can show it immediately.
     *
     * It goes into the platform store's outbox at the same moment, and moves to sent or failed
     * when the radio answers. Writing it only on success would leave every other app on the phone
     * blind to a message for as long as it takes to send — which on a poor signal is minutes.
     */
    suspend fun insertOutgoing(
        rawAddress: String,
        body: String,
        date: Long,
        subscriptionId: Int,
        parts: Int,
    ): Pair<Long, Long> {
        val address = PhoneNumbers.normalize(rawAddress)
        val threadId = threadIdFor(address)
        val systemId = systemWriter.writeOutbox(address, body, date, subscriptionId)
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
                systemId = systemId,
            ),
        )
        threadDao.touch(threadId, body.snippet(), date, subscriptionId, unreadDelta = 0)
        return threadId to messageId
    }

    /**
     * [mirrorToSystem] is advisory for sent messages: the write is always attempted.
     *
     * No other app writes the messages this one sends, so there is nothing to duplicate, and a
     * phone that refuses the write simply ignores it. Skipping it outright is the only option
     * that guarantees other apps — a dialer showing a contact's last message, for instance —
     * never see anything sent from here.
     *
     * The row is normally already in the provider's outbox from [insertOutgoing] and only needs
     * moving; it is written here only when that first attempt was refused, which happens when the
     * app was not yet the default at the moment of sending.
     */
    suspend fun markSent(messageId: Long, mirrorToSystem: Boolean) {
        val message = messageDao.byId(messageId) ?: return
        if (message.status == DeliveryStatus.FAILED) return
        messageDao.setType(messageId, MessageType.SENT)
        messageDao.setStatus(messageId, DeliveryStatus.SENT, null)

        val systemId = message.systemId
        if (systemId != null) {
            systemWriter.moveToSent(systemId)
            return
        }
        systemWriter.writeSent(
            message.address,
            message.body,
            message.date,
            message.subscriptionId,
        )?.let { messageDao.setSystemId(messageId, it) }
    }

    suspend fun markDelivered(messageId: Long) {
        messageDao.setStatus(messageId, DeliveryStatus.DELIVERED, null)
    }

    suspend fun markFailed(messageId: Long, error: String) {
        messageDao.setType(messageId, MessageType.FAILED)
        messageDao.setStatus(messageId, DeliveryStatus.FAILED, error)
        // The provider keeps its own outbox; a message that never went must not sit in it forever.
        messageDao.byId(messageId)?.systemId?.let { systemWriter.moveToFailed(it) }
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
        message.systemId?.let { systemWriter.delete(it) }
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

        /**
         * How far apart two identical messages may be and still be one message.
         *
         * Sized by what actually separates the copies: the time a message spends in the network
         * between being accepted and being delivered, and the gap between sending a long message
         * and the store scan that follows it. Ten minutes covers a badly delayed message and is
         * still far short of any interval at which anyone repeats themselves word for word.
         */
        const val DUPLICATE_WINDOW_MS = 10L * 60 * 1000

        /**
         * How many messages one backfill pass writes.
         *
         * Each one is a provider insert plus a read-back to check for a duplicate, so a hundred
         * thousand of them is not something to do inside one resume. The pass repeats on later
         * opens until there is nothing left without a provider row.
         */
        const val PROVIDER_BACKFILL_LIMIT = 2_000

        /** Comfortably under SQLite's limit on bound parameters in one statement. */
        private const val SQL_PARAMETER_CHUNK = 400
    }
}
