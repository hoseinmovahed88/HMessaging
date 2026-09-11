package com.hmessaging.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import com.hmessaging.di.AppGraph
import com.hmessaging.system.Diagnostics
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
        val graph = AppGraph.from(context)
        val incoming = IncomingSms.fromIntent(intent)
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                if (incoming == null) {
                    graph.diagnostics.record(
                        Diagnostics.KIND_SMS_DELIVER,
                        "broadcast arrived but carried no readable message",
                    )
                    return@launch
                }
                graph.diagnostics.record(
                    Diagnostics.KIND_SMS_DELIVER,
                    "from ${incoming.address}, ${incoming.parts} part(s)",
                )
                graph.incomingPipeline.handle(
                    address = incoming.address,
                    body = incoming.body,
                    receivedAt = incoming.receivedAt,
                    subscriptionId = incoming.subscriptionId,
                    parts = incoming.parts,
                    // Only write to the provider when the platform names us as default; if it
                    // does not, it is still writing these messages itself and we would duplicate.
                    mirrorToSystem = AppRoles.isPlatformDefaultSmsApp(context),
                    source = IncomingMessagePipeline.Source.BROADCAST,
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
 * Kept live alongside SMS_DELIVER rather than as a fallback: the two broadcasts are deduplicated
 * downstream, so a device that delivers only one of them still works.
 */
class SmsReceivedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val graph = AppGraph.from(context)
        val incoming = IncomingSms.fromIntent(intent)
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                if (incoming == null) {
                    graph.diagnostics.record(
                        Diagnostics.KIND_SMS_RECEIVED,
                        "broadcast arrived but carried no readable message",
                    )
                    return@launch
                }
                graph.diagnostics.record(
                    Diagnostics.KIND_SMS_RECEIVED,
                    "from ${incoming.address}, ${incoming.parts} part(s)",
                )
                // This used to bail out whenever the app held the role, on the assumption that
                // SMS_DELIVER would cover the message. On a device where RoleManager reports the
                // role held but the platform reports no default SMS app, SMS_DELIVER may never
                // fire — and that assumption dropped every message. Both broadcasts now feed the
                // pipeline, which discards the duplicate when they both arrive.
                graph.incomingPipeline.handle(
                    address = incoming.address,
                    body = incoming.body,
                    receivedAt = incoming.receivedAt,
                    subscriptionId = incoming.subscriptionId,
                    parts = incoming.parts,
                    // As above: the platform's own answer decides who owns the provider.
                    mirrorToSystem = AppRoles.isPlatformDefaultSmsApp(context),
                    source = IncomingMessagePipeline.Source.BROADCAST,
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
