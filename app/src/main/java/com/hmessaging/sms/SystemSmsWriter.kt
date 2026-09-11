package com.hmessaging.sms

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import androidx.core.net.toUri
import com.hmessaging.util.AppRoles

/**
 * Mirrors messages into the platform SMS provider.
 *
 * Only the default SMS app may write there, and Android expects it to: other apps, backup tools
 * and the next messaging app the user installs all read from this provider. Every call is a no-op
 * when we do not hold the role.
 */
class SystemSmsWriter(private val context: Context) {

    fun writeInbox(address: String, body: String, date: Long, subscriptionId: Int, read: Boolean): Long? =
        insert(
            uri = Telephony.Sms.Inbox.CONTENT_URI.toString(),
            address = address,
            body = body,
            date = date,
            subscriptionId = subscriptionId,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = read,
        )

    fun writeSent(address: String, body: String, date: Long, subscriptionId: Int): Long? =
        insert(
            uri = Telephony.Sms.Sent.CONTENT_URI.toString(),
            address = address,
            body = body,
            date = date,
            subscriptionId = subscriptionId,
            type = Telephony.Sms.MESSAGE_TYPE_SENT,
            read = true,
        )

    fun delete(systemId: Long): Boolean {
        if (!AppRoles.isPlatformDefaultSmsApp(context)) return false
        val uri = "${Telephony.Sms.CONTENT_URI}/$systemId".toUri()
        return runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    private fun insert(
        uri: String,
        address: String,
        body: String,
        date: Long,
        subscriptionId: Int,
        type: Int,
        read: Boolean,
    ): Long? {
        if (!AppRoles.isPlatformDefaultSmsApp(context)) return null
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, if (read) 1 else 0)
            put(Telephony.Sms.SEEN, if (read) 1 else 0)
            put(Telephony.Sms.TYPE, type)
            if (subscriptionId >= 0) {
                put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            }
        }
        return runCatching {
            context.contentResolver.insert(uri.toUri(), values)?.lastPathSegment?.toLongOrNull()
        }.getOrNull()
    }
}
