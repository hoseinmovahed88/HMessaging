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

    data class Progress(
        val imported: Int,
        val skipped: Int,
        /** False when the provider could not be read at all, which is not the same as "nothing new". */
        val succeeded: Boolean = true,
        val error: String? = null,
    )

    suspend fun importAll(limit: Int = DEFAULT_LIMIT): Progress = withContext(Dispatchers.IO) {
        if (!Permissions.has(context, Manifest.permission.READ_SMS)) {
            return@withContext Progress(0, 0, succeeded = false, error = "READ_SMS is not granted")
        }

        val known = HashSet(messageDao.fingerprints())

        var imported = 0
        var skipped = 0
        val batch = ArrayList<MessageEntity>(BATCH_SIZE)
        val threadIds = HashMap<String, Long>()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
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
            return@withContext Progress(0, 0, succeeded = false, error = reason)
        }

        cursor.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val readColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val subColumn = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

            while (cursor.moveToNext()) {
                if (imported >= limit) break
                val address = PhoneNumbers.normalize(cursor.getString(addressColumn))
                val body = cursor.getString(bodyColumn).orEmpty()
                val date = cursor.getLong(dateColumn)
                if (body.isEmpty() || address == PhoneNumbers.UNKNOWN_ADDRESS) {
                    skipped++
                    continue
                }
                if (!known.add("$address|$date")) {
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

        if (imported > 0) threadDao.rebuildSummaries()
        Progress(imported, skipped)
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
    suspend fun syncNew(deliverThroughPipeline: Boolean): Progress = withContext(Dispatchers.IO) {
        if (!Permissions.has(context, Manifest.permission.READ_SMS)) {
            return@withContext Progress(0, 0, succeeded = false, error = "READ_SMS is not granted")
        }
        val since = messageDao.newestDate() ?: 0L
        val cutoff = System.currentTimeMillis() - LIVE_WINDOW_MS

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
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
            ?: return@withContext Progress(
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
                if (messageDao.exists(address, date, body)) {
                    skipped++
                    continue
                }

                val type = toMessageType(rows.getInt(typeColumn))
                val subscriptionId = if (subColumn >= 0) rows.getInt(subColumn) else -1

                if (deliverThroughPipeline && type.isIncoming && date >= cutoff) {
                    pipeline().handle(
                        address = address,
                        body = body,
                        receivedAt = date,
                        subscriptionId = subscriptionId,
                        parts = 1,
                        // Already in the platform store — that is where it was just read from.
                        mirrorToSystem = false,
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
                            systemId = rows.getLong(idColumn),
                        ),
                    )
                }
                imported++
            }
        }

        if (imported > 0) threadDao.rebuildSummaries()
        Progress(imported, skipped)
    }

    private companion object {
        const val DEFAULT_LIMIT = 5000
        const val SYNC_LIMIT = 500
        const val BATCH_SIZE = 200
        /** Only messages this fresh are replayed through auto-reply and forwarding. */
        const val LIVE_WINDOW_MS = 15L * 60 * 1000
    }
}
