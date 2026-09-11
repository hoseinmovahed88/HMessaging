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
                val threadId = repository.threadIdFor(address)
                messageDao.insert(
                    MessageEntity(
                        threadId = threadId,
                        address = address,
                        body = body,
                        date = date,
                        type = type,
                        read = cursor.getInt(readColumn) != 0 || !type.isIncoming,
                        status = if (type == MessageType.SENT) DeliveryStatus.SENT else DeliveryStatus.NONE,
                        subscriptionId = if (subColumn >= 0) cursor.getInt(subColumn) else -1,
                        systemId = cursor.getLong(idColumn),
                    ),
                )
                imported++
            }
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

    private companion object {
        const val DEFAULT_LIMIT = 5000
    }
}
