package com.hmessaging.sms

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import androidx.core.net.toUri
import com.hmessaging.util.AppRoles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mirrors messages into the platform SMS provider.
 *
 * Only the default SMS app may write there, and Android expects it to: the provider is where every
 * other app on the phone looks for messages — the dialer showing a contact's last text, a watch,
 * the system backup, whatever messaging app is installed next. No other app is allowed to write
 * it, so anything this app fails to write is simply not there for any of them.
 *
 * Every row carries a thread id. That is not decoration: the conversation view other apps read,
 * `content://mms-sms/conversations`, is built by grouping on `thread_id`, and a row written
 * without one is invisible to all of them however complete the rest of its columns are. The id
 * comes from [Telephony.Threads.getOrCreateThreadId], which is the only way to get the id the
 * platform itself would use for that address.
 *
 * Writes are attempted rather than pre-authorised. The platform decides whether they land, through
 * an app-operation granted only to the default SMS app, and it decides silently: a refused insert
 * returns a URI that points at no row. Asking [Telephony.Sms.getDefaultSmsPackage] first looked
 * like the safer order, but that call is answered wrongly on some vendor ROMs — HyperOS among them
 * — and an app that believes it is not the default writes nothing at all while messages are being
 * delivered to it. So every insert is made and then read back, which is the only answer that does
 * not depend on the ROM telling the truth.
 */
class SystemSmsWriter(private val context: Context) {

    suspend fun writeInbox(address: String, body: String, date: Long, subscriptionId: Int, read: Boolean): Long? =
        insert(
            uri = Telephony.Sms.Inbox.CONTENT_URI.toString(),
            address = address,
            body = body,
            date = date,
            subscriptionId = subscriptionId,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = read,
        )

    suspend fun writeSent(address: String, body: String, date: Long, subscriptionId: Int): Long? =
        insert(
            uri = Telephony.Sms.Sent.CONTENT_URI.toString(),
            address = address,
            body = body,
            date = date,
            subscriptionId = subscriptionId,
            type = Telephony.Sms.MESSAGE_TYPE_SENT,
            read = true,
        )

    /**
     * A message handed to the radio but not yet acknowledged.
     *
     * Written before the send rather than after it so the message exists for every other app while
     * it is in flight, which is what the provider's outbox is for. [moveToSent] and [moveToFailed]
     * finish it.
     */
    suspend fun writeOutbox(address: String, body: String, date: Long, subscriptionId: Int): Long? =
        insert(
            uri = Telephony.Sms.Outbox.CONTENT_URI.toString(),
            address = address,
            body = body,
            date = date,
            subscriptionId = subscriptionId,
            type = Telephony.Sms.MESSAGE_TYPE_OUTBOX,
            read = true,
        )

    suspend fun moveToSent(systemId: Long): Boolean = setType(systemId, Telephony.Sms.MESSAGE_TYPE_SENT)

    suspend fun moveToFailed(systemId: Long): Boolean = setType(systemId, Telephony.Sms.MESSAGE_TYPE_FAILED)

    private suspend fun setType(systemId: Long, type: Int): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(Telephony.Sms.TYPE, type) }
        runCatching {
            context.contentResolver.update(rowUri(systemId), values, null, null) > 0
        }.getOrDefault(false)
    }

    suspend fun delete(systemId: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(rowUri(systemId), null, null) > 0 }
            .getOrDefault(false)
    }

    /**
     * Whether it is worth attempting a write at all.
     *
     * Deliberately the loose question — either the platform names this package or the role manager
     * says the role is held — because the two disagree on vendor ROMs and the strict answer was
     * stopping every write on a phone that was in fact the default. Nothing depends on this being
     * right: it only decides whether a batch is worth starting, and each write is verified.
     */
    fun canWrite(): Boolean = AppRoles.isDefaultSmsApp(context)

    private fun rowUri(systemId: Long) = "${Telephony.Sms.CONTENT_URI}/$systemId".toUri()

    /**
     * The platform's own id for the conversation with [address], created if there is not one yet.
     *
     * Returns null rather than throwing: this call needs the default-SMS role and fails without
     * it, and a message must never be lost because the provider would not answer.
     */
    private fun threadIdFor(address: String): Long? = runCatching {
        Telephony.Threads.getOrCreateThreadId(context, address)
    }.getOrNull()

    private suspend fun insert(
        uri: String,
        address: String,
        body: String,
        date: Long,
        subscriptionId: Int,
        type: Int,
        read: Boolean,
    ): Long? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, if (read) 1 else 0)
            put(Telephony.Sms.SEEN, if (read) 1 else 0)
            put(Telephony.Sms.TYPE, type)
            threadIdFor(address)?.let { put(Telephony.Sms.THREAD_ID, it) }
            if (subscriptionId >= 0) {
                put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            }
        }
        val id = runCatching {
            context.contentResolver.insert(uri.toUri(), values)?.lastPathSegment?.toLongOrNull()
        }.getOrNull()
        // A refused write still hands back a URI, and taking its id on trust is what stamped
        // messages as "already in the platform store" while the store stayed empty. Those stamps
        // then hid the same messages from the backfill, permanently. Read the row back instead.
        if (id != null && id > 0 && rowExists(id)) id else null
    }

    /** True when the provider really holds a row under this id. */
    private fun rowExists(systemId: Long): Boolean = runCatching {
        context.contentResolver.query(
            rowUri(systemId),
            arrayOf(Telephony.Sms._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    /**
     * True when a message with this address, timestamp and body exists in the platform store.
     *
     * Android refuses provider writes from an app it does not consider the default SMS app, and
     * the refusal is silent — the insert returns a URI that points at no row. Reading back is the
     * only way to know whether anything landed.
     */
    suspend fun exists(address: String, date: Long, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.DATE} = ? AND ${Telephony.Sms.BODY} = ?",
            arrayOf(date.toString(), body),
            null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    /**
     * Every row id the platform store currently holds, or null when it could not be read.
     *
     * The distinction matters more than the ids do. This app records the provider's id against
     * each message it writes, and treats a recorded id as proof the message is there — so if the
     * store is ever emptied underneath it, by a factory tool, another messaging app, or the ROM
     * itself, every one of those messages becomes invisible to the rest of the phone and the
     * backfill skips it forever. Comparing against the real set is the repair. A store that could
     * not be read must not be mistaken for an empty one, hence null rather than an empty set.
     */
    suspend fun allRowIds(): Set<Long>? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null,
                null,
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            }
        }.getOrNull()
    }

    /**
     * Gives a thread id to rows that were written without one.
     *
     * A row with `thread_id` unset is in the store and invisible all the same: the conversation
     * view every other app reads is grouped by that column, so a row outside a thread belongs to
     * no conversation. Rows written by earlier versions of this app are the ones that need it.
     *
     * Returns how many were repaired.
     */
    suspend fun repairThreadIds(limit: Int = THREAD_REPAIR_LIMIT): Int = withContext(Dispatchers.IO) {
        val broken = runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS),
                "${Telephony.Sms.THREAD_ID} IS NULL OR ${Telephony.Sms.THREAD_ID} <= 0",
                null,
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        val address = cursor.getString(1)
                        if (!address.isNullOrBlank()) add(cursor.getLong(0) to address)
                    }
                }
            }
        }.getOrNull().orEmpty()

        var repaired = 0
        for ((id, address) in broken) {
            val threadId = threadIdFor(address) ?: continue
            if (threadId <= 0) continue
            val values = ContentValues().apply { put(Telephony.Sms.THREAD_ID, threadId) }
            val updated = runCatching {
                context.contentResolver.update(rowUri(id), values, null, null) > 0
            }.getOrDefault(false)
            if (updated) repaired++
        }
        repaired
    }

    /** One row of the platform store, for the diagnostics screen to show what is actually there. */
    data class ProviderRow(
        val id: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int,
    )

    /**
     * The newest rows in `content://sms`, read back as any other app would read them.
     *
     * This exists to be looked at: the whole point of writing to the provider is that other
     * software can see the messages, and nothing else in the app can show whether it worked.
     */
    suspend fun recentRows(limit: Int = 20): List<ProviderRow> = withContext(Dispatchers.IO) {
        runCatching {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < limit) {
                    add(
                        ProviderRow(
                            id = cursor.getLong(0),
                            threadId = cursor.getLong(1),
                            address = cursor.getString(2).orEmpty(),
                            body = cursor.getString(3).orEmpty(),
                            date = cursor.getLong(4),
                            type = cursor.getInt(5),
                        ),
                    )
                }
            }
        }
        }.getOrNull().orEmpty()
    }

    /** How many rows the platform store holds, or null when it could not be asked. */
    suspend fun rowCount(): Int? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null,
                null,
                null,
            )?.use { it.count }
        }.getOrNull()
    }

    /** How many rows the platform store holds, and how many of those carry a thread id. */
    suspend fun rowStats(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        runCatching {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            var total = 0
            var threaded = 0
            while (cursor.moveToNext()) {
                total++
                if (cursor.getLong(0) > 0) threaded++
            }
                total to threaded
            }
        }.getOrNull() ?: (0 to 0)
    }

    private companion object {
        /** Enough to repair a long history in one pass without holding an app open for minutes. */
        const val THREAD_REPAIR_LIMIT = 5_000
    }
}
