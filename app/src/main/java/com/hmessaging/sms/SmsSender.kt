package com.hmessaging.sms

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.data.repo.MessageRepository
import com.hmessaging.util.AppRoles
import com.hmessaging.util.Permissions
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.SmsText
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

data class SendOutcome(
    val sent: Int = 0,
    val failed: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = failed == 0 && sent > 0

    operator fun plus(other: SendOutcome) = SendOutcome(
        sent = sent + other.sent,
        failed = failed + other.failed,
        errors = errors + other.errors,
    )
}

/**
 * Sends SMS, including bodies far past the 160-character limit.
 *
 * Length is never a hard stop: [SmsManager.divideMessage] splits a body into GSM-03.38 segments
 * that the receiving handset reassembles transparently. Only when the user asks for numbered
 * chunks (for gateways that drop concatenation headers) is the body cut into separate messages.
 */
class SmsSender(
    private val context: Context,
    private val repository: MessageRepository,
    private val prefs: AppPrefs,
    private val simManager: SimManager,
) {

    suspend fun send(
        recipients: List<String>,
        body: String,
        subscriptionId: Int = AppSettings.SUBSCRIPTION_UNSET,
        appendSignature: Boolean = true,
        allowChunking: Boolean = true,
    ): SendOutcome {
        if (body.isBlank()) return SendOutcome(failed = 1, errors = listOf(ERROR_EMPTY_BODY))
        if (recipients.isEmpty()) return SendOutcome(failed = 1, errors = listOf(ERROR_NO_RECIPIENT))
        if (!Permissions.canSendSms(context)) {
            return SendOutcome(failed = recipients.size, errors = listOf(ERROR_NO_PERMISSION))
        }

        val settings = prefs.settings.first()
        val resolvedSubscription = simManager.resolveSubscriptionId(subscriptionId, settings)
        val finalBody = applySignature(body, settings, appendSignature)
        val chunks = if (allowChunking && settings.numberLongMessages) {
            SmsText.splitNumbered(finalBody, settings.chunkChars)
        } else {
            listOf(finalBody)
        }

        var outcome = SendOutcome()
        for (recipient in recipients) {
            for (chunk in chunks) {
                outcome += sendOne(recipient, chunk, resolvedSubscription, settings)
            }
        }
        return outcome
    }

    // SEND_SMS is verified in send() before any recipient is dispatched.
    @SuppressLint("MissingPermission")
    private suspend fun sendOne(
        recipient: String,
        body: String,
        subscriptionId: Int,
        settings: AppSettings,
    ): SendOutcome {
        val address = PhoneNumbers.normalize(recipient)
        val manager = smsManagerFor(subscriptionId)
            ?: return SendOutcome(failed = 1, errors = listOf(ERROR_NO_SMS_MANAGER))

        val parts = runCatching { manager.divideMessage(body) }.getOrNull()
            ?: return SendOutcome(failed = 1, errors = listOf(ERROR_DIVIDE_FAILED))
        if (parts.isEmpty()) return SendOutcome(failed = 1, errors = listOf(ERROR_EMPTY_BODY))

        val (_, messageId) = repository.insertOutgoing(
            rawAddress = address,
            body = body,
            date = System.currentTimeMillis(),
            subscriptionId = subscriptionId,
            parts = parts.size,
        )

        val sentIntents = ArrayList<PendingIntent>(parts.size)
        val deliveryIntents = if (settings.deliveryReports) ArrayList<PendingIntent>(parts.size) else null
        for (index in parts.indices) {
            sentIntents += statusIntent(SmsStatusReceiver.KIND_SENT, messageId, index, parts.size)
            deliveryIntents?.add(
                statusIntent(SmsStatusReceiver.KIND_DELIVERED, messageId, index, parts.size),
            )
        }

        return try {
            manager.sendMultipartTextMessage(address, null, parts, sentIntents, deliveryIntents)
            SendOutcome(sent = 1)
        } catch (error: Exception) {
            val reason = error.message ?: error::class.java.simpleName
            repository.markFailed(messageId, reason)
            SendOutcome(failed = 1, errors = listOf(reason))
        }
    }

    private fun applySignature(body: String, settings: AppSettings, append: Boolean): String {
        if (!append || !settings.signatureEnabled || settings.signature.isBlank()) return body
        return "$body\n${settings.signature}"
    }

    private fun statusIntent(kind: String, messageId: Long, partIndex: Int, partCount: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java).apply {
            action = kind
            // The action alone is not unique enough for PendingIntent equality, so the data URI
            // carries the identity of this exact part.
            data = Uri.parse("hmessaging://send/" + messageId + "/" + partIndex)
            putExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(SmsStatusReceiver.EXTRA_PART_INDEX, partIndex)
            putExtra(SmsStatusReceiver.EXTRA_PART_COUNT, partCount)
        }
        return PendingIntent.getBroadcast(context, requestCodes.incrementAndGet(), intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Mutable on purpose: the telephony stack writes the delivery PDU into this intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun smsManagerFor(subscriptionId: Int): SmsManager? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subscriptionId >= 0) base?.createForSubscriptionId(subscriptionId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subscriptionId >= 0) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }
    }.getOrNull()

    /** True when we may mirror sent messages into the platform provider. */
    fun canMirrorToSystem(): Boolean = AppRoles.isDefaultSmsApp(context)

    private companion object {
        val requestCodes = AtomicInteger(1000)

        const val ERROR_EMPTY_BODY = "Message body is empty"
        const val ERROR_NO_RECIPIENT = "No recipient"
        const val ERROR_NO_PERMISSION = "SEND_SMS permission is not granted"
        const val ERROR_NO_SMS_MANAGER = "Telephony is unavailable"
        const val ERROR_DIVIDE_FAILED = "Could not split the message into SMS parts"
    }
}
