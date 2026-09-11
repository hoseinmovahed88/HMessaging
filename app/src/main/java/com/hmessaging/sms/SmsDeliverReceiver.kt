package com.hmessaging.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import com.hmessaging.di.AppGraph
import com.hmessaging.util.AppRoles
import kotlinx.coroutines.launch

/**
 * Receives `SMS_DELIVER`, the broadcast Android sends only to the current default SMS app.
 *
 * Holding this role is what lets the app block a message before it is ever written anywhere, and
 * obliges it to mirror what it keeps into the platform SMS provider.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val incoming = IncomingSms.fromIntent(intent) ?: return

        val graph = AppGraph.from(context)
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                graph.incomingPipeline.handle(
                    address = incoming.address,
                    body = incoming.body,
                    receivedAt = incoming.receivedAt,
                    subscriptionId = incoming.subscriptionId,
                    parts = incoming.parts,
                    // We hold the role here, so the platform provider is ours to keep current.
                    mirrorToSystem = AppRoles.isDefaultSmsApp(context),
                )
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Receives the legacy `SMS_RECEIVED` broadcast, which every SMS-capable app gets.
 *
 * It only does anything while another app holds the default-SMS role, so the user still sees
 * their messages here before they switch — without double-handling once they do.
 */
class SmsReceivedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (AppRoles.isDefaultSmsApp(context)) return // SmsDeliverReceiver owns this message.
        val incoming = IncomingSms.fromIntent(intent) ?: return

        val graph = AppGraph.from(context)
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                graph.incomingPipeline.handle(
                    address = incoming.address,
                    body = incoming.body,
                    receivedAt = incoming.receivedAt,
                    subscriptionId = incoming.subscriptionId,
                    parts = incoming.parts,
                    // Not the default app: the provider belongs to whoever is.
                    mirrorToSystem = false,
                )
            } finally {
                pending.finish()
            }
        }
    }
}

/** A multipart SMS reassembled into one logical message. */
data class IncomingSms(
    val address: String,
    val body: String,
    val receivedAt: Long,
    val subscriptionId: Int,
    val parts: Int,
) {
    companion object {
        /** The extra the telephony stack uses to name the SIM a message arrived on. */
        private const val EXTRA_SUBSCRIPTION = "subscription"

        fun fromIntent(intent: Intent): IncomingSms? {
            val messages: Array<SmsMessage> =
                runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
                    ?: return null
            if (messages.isEmpty()) return null

            val first = messages.first()
            val address = first.displayOriginatingAddress
                ?: first.originatingAddress
                ?: return null
            val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
            if (body.isEmpty()) return null

            return IncomingSms(
                address = address,
                body = body,
                receivedAt = first.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION, -1),
                parts = messages.size,
            )
        }
    }
}
