package com.hmessaging.sms

import android.Manifest
import android.content.Context
import android.provider.Telephony
import com.hmessaging.data.db.dao.MessageDao
import com.hmessaging.data.db.dao.ThreadDao
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.model.DeliveryStatus
import com.hmessaging.data.model.MessageType
import com.hmessaging.data.repo.MessageRepository
import com.hmessaging.util.Permissions
import com.hmessaging.util.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pulls the phone's existing SMS history into the app's own store, so switching to HMessaging
 * does not look like losing every conversation.
 *
 * Existing rows are matched on (address, date, body) rather than the provider's `_id`, because a
 * message may already be present from a previous import or from live delivery.
 */
class SmsImporter(
    private val context: Context,
    private val threadDao: ThreadDao,
    private val messageDao: MessageDao,
    private val repository: MessageRepository,
    private val pipeline: () -> IncomingMessagePipeline,
) {

    /** Emitted while a long import runs, so a minute of work does not look like a freeze. */
    data class Running(val imported: Int, val scanned: Int, val total: Int)

    private val _progress = MutableStateFlow<Running?>(null)
    val progress: StateFlow<Running?> = _progress.asStateFlow()

    private val importLock = Mutex()

    data class Progress(
        val imported: Int,
        val skipped: Int,
        /** False when the provider could not be read at all, which is not the same as "nothing new". */
        val succeeded: Boolean = true,
        val error: String? = null,
    )

    /**
     * Copies the phone's SMS history into this app.
     *
     * [limit] caps how many *new* messages a single run takes; already-known rows are skipped
     * without counting against it, so repeated runs walk further back each time. Pass [NO_LIMIT]
     * to take everything in one pass — on a phone holding a hundred thousand messages that is a
     * minute of work, which is why it reports [progress] as it goes.
     */
    suspend fun importAll(limit: Int = DEFAULT_LIMIT): Progress = importLock.withLock {
        withContext(Dispatchers.IO) { runImport(limit) }
    }

    private suspend fun runImport(limit: Int): Progress {
        if (!Permissions.has(context, Manifest.permission.READ_SMS)) {
            return Progress(0, 0, succeeded = false, error = "READ_SMS is not granted")
        }

        val known = HashSet(messageDao.fingerprints())
        // Rows this app wrote into the platform store itself, whose timestamps the provider may
        // have replaced — the fingerprint above would not recognise them.
        val knownSystemIds = HashSet(messageDao.knownSystemIds())

        var imported = 0
        var skipped = 0
        val batch = ArrayList<MessageEntity>(BATCH_SIZE)
        val threadIds = HashMap<String, Long>()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.SUBSCRIPTION_ID,
        )

        // The row cap is applied while walking the cursor rather than as a `LIMIT` clause appended
        // to the sort order: several vendor SMS providers validate that string and reject the
        // query outright, which silently yielded an empty import.
        val cursorResult = runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )
        }
        val cursor = cursorResult.getOrNull()
        if (cursor == null) {
            val reason = cursorResult.exceptionOrNull()?.message ?: "the SMS provider returned no cursor"
            return Progress(0, 0, succeeded = false, error = reason)
        }

        val total = runCatching { cursor.count }.getOrDefault(0)
        _progress.value = Running(imported = 0, scanned = 0, total = total)

        try {
        cursor.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val sentColumn = cursor.getColumnIndex(Telephony.Sms.DATE_SENT)
            val typeColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val readColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val subColumn = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

            var scanned = 0
            while (cursor.moveToNext()) {
                if (imported >= limit) break
                scanned++
                if (scanned % PROGRESS_STRIDE == 0) {
                    _progress.value = Running(imported = imported, scanned = scanned, total = total)
                }
                val address = PhoneNumbers.normalize(cursor.getString(addressColumn))
                val body = cursor.getString(bodyColumn).orEmpty()
                val date = cursor.getLong(dateColumn)
                if (body.isEmpty() || address == PhoneNumbers.UNKNOWN_ADDRESS) {
                    skipped++
                    continue
                }
                // Two timestamps, because the same message carries two. A message delivered by
                // broadcast is stored under the time the network centre accepted it, which the
                // platform store keeps as `date_sent`, while its own `date` is when the phone
                // received it. Checking only one of them imports every such message a second time.
                val dateSent = if (sentColumn >= 0) cursor.getLong(sentColumn) else 0L
                val known1 = !known.add("$address|$date")
                val known2 = dateSent > 0 && !known.add("$address|$dateSent")
                if (known1 || known2) {
                    skipped++
                    continue
                }
                if (!knownSystemIds.add(cursor.getLong(idColumn))) {
                    skipped++
                    continue
                }

                val type = toMessageType(cursor.getInt(typeColumn))
                // Thread ids are resolved once per distinct address rather than once per row:
                // each resolution is a lookup plus a possible contact query.
                val threadId = threadIds.getOrPut(address) { repository.threadIdFor(address) }
                batch += MessageEntity(
                    threadId = threadId,
                    address = address,
                    body = body,
                    date = date,
                    type = type,
                    read = cursor.getInt(readColumn) != 0 || !type.isIncoming,
                    status = if (type == MessageType.SENT) DeliveryStatus.SENT else DeliveryStatus.NONE,
                    subscriptionId = if (subColumn >= 0) cursor.getInt(subColumn) else -1,
                    systemId = cursor.getLong(idColumn),
                )
                imported++

                if (batch.size >= BATCH_SIZE) {
                    messageDao.insertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) messageDao.insertAll(batch)
        }
        } finally {
            _progress.value = null
        }
        // Unconditionally: a conversation whose summary was left stale by an interrupted earlier
        // run shows an old last message however many rows this run added, and a walk of the
        // whole store is the natural moment to put every summary right.
        threadDao.rebuildSummaries()

        if (imported > 0) threadDao.rebuildSummaries()
        return Progress(imported, skipped)
    }

    /**
     * Whether this platform-store row is a message the app already has.
     *
     * The (address, date, body) test above is not enough, because one message carries two
     * timestamps and the two copies are stored under different ones. A message that arrived by
     * broadcast is stored under the time the network centre accepted it — what the platform store
     * calls `date_sent` — while the store's own `date` is when the phone received it, and the gap
     * between them is however long the message spent in transit. A message this app sent has the
     * same problem from the other end: it mirrors the message into the store, and a provider that
     * stamps its own `date` on that insert leaves a row whose timestamp matches nothing here.
     *
     * Three answers, in order of certainty: the row id may already be stored against a message;
     * the sending timestamp may match one exactly; and failing both, this app may already hold
     * these exact words to or from this number at about this time.
     */
    private suspend fun isAlreadyHeld(
        systemId: Long,
        type: MessageType,
        address: String,
        body: String,
        date: Long,
        dateSent: Long,
    ): Boolean {
        if (messageDao.existsBySystemId(systemId)) return true
        if (dateSent > 0 && messageDao.exists(address, dateSent, body)) return true
        return messageDao.existsNear(
            address = address,
            body = body,
            from = date - MIRROR_WINDOW_MS,
            to = date + MIRROR_WINDOW_MS,
            incoming = type.isIncoming,
        )
    }

    private fun toMessageType(providerType: Int): MessageType = when (providerType) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> MessageType.INBOX
        Telephony.Sms.MESSAGE_TYPE_SENT -> MessageType.SENT
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> MessageType.OUTBOX
        Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageType.FAILED
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> MessageType.DRAFT
        else -> MessageType.INBOX
    }

    /**
     * Pulls in whatever the phone's SMS store has that this app does not.
     *
     * This is the safety net for devices where the broadcast never arrives — several vendor ROMs
     * refuse to cold-start an app from a manifest-declared receiver — and it is cheap enough to
     * run on every resume, because it only looks at rows newer than the newest one already held.
     *
     * [deliverThroughPipeline] routes genuinely fresh inbox messages through blocking, OTP
     * capture, auto-reply and forwarding. It is deliberately off for bulk catch-up: replaying a
     * backlog through the answering machine would send a burst of real, billable messages.
     */
    suspend fun syncNew(deliverThroughPipeline: Boolean): Progress = importLock.withLock {
        withContext(Dispatchers.IO) { runSync(deliverThroughPipeline) }
    }

    // Behind the same lock as the full import: the two used to run side by side on the first
    // open, each deciding what was "new" from a database the other was still filling.
    private suspend fun runSync(deliverThroughPipeline: Boolean): Progress {
        if (!Permissions.has(context, Manifest.permission.READ_SMS)) {
            return Progress(0, 0, succeeded = false, error = "READ_SMS is not granted")
        }
        val since = messageDao.newestDate() ?: 0L
        val cutoff = System.currentTimeMillis() - LIVE_WINDOW_MS

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.SUBSCRIPTION_ID,
        )

        val cursorResult = runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE} > ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} ASC",
            )
        }
        val cursor = cursorResult.getOrNull()
            ?: return Progress(
                0,
                0,
                succeeded = false,
                error = cursorResult.exceptionOrNull()?.message ?: "the SMS provider returned no cursor",
            )

        var imported = 0
        var skipped = 0
        cursor.use { rows ->
            val idColumn = rows.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressColumn = rows.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = rows.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = rows.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val sentColumn = rows.getColumnIndex(Telephony.Sms.DATE_SENT)
            val typeColumn = rows.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val readColumn = rows.getColumnIndexOrThrow(Telephony.Sms.READ)
            val subColumn = rows.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

            while (rows.moveToNext()) {
                if (imported >= SYNC_LIMIT) break
                val address = PhoneNumbers.normalize(rows.getString(addressColumn))
                val body = rows.getString(bodyColumn).orEmpty()
                val date = rows.getLong(dateColumn)
                if (body.isEmpty() || address == PhoneNumbers.UNKNOWN_ADDRESS) {
                    skipped++
                    continue
                }
                val dateSent = if (sentColumn >= 0) rows.getLong(sentColumn) else 0L
                if (messageDao.exists(address, date, body)) {
                    skipped++
                    continue
                }

                val type = toMessageType(rows.getInt(typeColumn))
                val subscriptionId = if (subColumn >= 0) rows.getInt(subColumn) else -1
                val systemId = rows.getLong(idColumn)

                if (isAlreadyHeld(systemId, type, address, body, date, dateSent)) {
                    skipped++
                    continue
                }

                if (deliverThroughPipeline && type.isIncoming && date >= cutoff) {
                    pipeline().handle(
                        address = address,
                        body = body,
                        receivedAt = date,
                        subscriptionId = subscriptionId,
                        parts = 1,
                        // Already in the platform store — that is where it was just read from.
                        mirrorToSystem = false,
                        source = IncomingMessagePipeline.Source.PROVIDER_SCAN,
                    )
                } else {
                    val threadId = repository.threadIdFor(address)
                    messageDao.insert(
                        MessageEntity(
                            threadId = threadId,
                            address = address,
                            body = body,
                            date = date,
                            type = type,
                            read = rows.getInt(readColumn) != 0 || !type.isIncoming,
                            status = if (type == MessageType.SENT) DeliveryStatus.SENT else DeliveryStatus.NONE,
                            subscriptionId = subscriptionId,
                            systemId = systemId,
                        ),
                    )
                }
                imported++
            }
        }

        if (imported > 0) threadDao.rebuildSummaries()
        return Progress(imported, skipped)
    }

    companion object {
        /** Take the whole history in one pass, however large it is. */
        const val NO_LIMIT = Int.MAX_VALUE

        /** Enough for a first screen within seconds; the first open follows it with the rest. */
        const val DEFAULT_LIMIT = 5000
        private const val PROGRESS_STRIDE = 250
        private const val SYNC_LIMIT = 500
        private const val BATCH_SIZE = 200

        /** Only messages this fresh are replayed through auto-reply and forwarding. */
        private const val LIVE_WINDOW_MS = 15L * 60 * 1000

        /**
         * How far one copy of a message may sit from the other before they are two messages.
         *
         * Sized by what actually separates them: the time a message spends in the network between
         * being accepted and being delivered. Ten minutes covers a badly delayed one and is still
         * far short of any interval at which a sender repeats themselves word for word.
         */
        private const val MIRROR_WINDOW_MS = 10L * 60 * 1000
    }
}
