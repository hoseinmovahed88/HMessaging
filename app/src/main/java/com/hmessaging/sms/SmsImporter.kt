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

    data class Progress(val imported: Int, val skipped: Int)

    suspend fun importAll(limit: Int = DEFAULT_LIMIT): Progress = withContext(Dispatchers.IO) {
        if (!Permissions.has(context, Manifest.permission.READ_SMS)) return@withContext Progress(0, 0)

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

        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT $limit",
            )
        }.getOrNull()?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val readColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val subColumn = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

            while (cursor.moveToNext()) {
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

        if (imported > 0) refreshThreadSummaries()
        Progress(imported, skipped)
    }

    /** Rebuilds each thread's snippet, timestamp and unread count from the imported rows. */
    private suspend fun refreshThreadSummaries() {
        val messages = messageDao.all().groupBy { it.threadId }
        messages.forEach { (threadId, rows) ->
            val thread = threadDao.byId(threadId) ?: return@forEach
            val latest = rows.maxByOrNull { it.date } ?: return@forEach
            threadDao.update(
                thread.copy(
                    snippet = latest.body.replace('\n', ' ').take(SNIPPET_LENGTH),
                    lastMessageAt = latest.date,
                    unreadCount = rows.count { it.type.isIncoming && !it.read },
                ),
            )
        }
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
        const val SNIPPET_LENGTH = 160
    }
}
