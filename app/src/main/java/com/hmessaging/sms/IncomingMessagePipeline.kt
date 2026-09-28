package com.hmessaging.sms

import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.data.repo.MessageRepository
import com.hmessaging.feature.autoreply.AutoReplyEngine
import com.hmessaging.feature.bank.BankLedger
import com.hmessaging.feature.block.BlockDecision
import com.hmessaging.feature.block.BlockEngine
import com.hmessaging.feature.forward.ForwardEngine
import com.hmessaging.feature.otp.OtpDetector
import com.hmessaging.feature.otp.OtpPresenter
import com.hmessaging.feature.otp.OtpVetoes
import com.hmessaging.feature.quickreply.QuickReplyPresenter
import com.hmessaging.notify.AlertOutcome
import com.hmessaging.notify.Notifications
import com.hmessaging.system.Diagnostics
import kotlinx.coroutines.flow.first

/**
 * The single path every inbound message takes.
 *
 * Order matters: blocking runs before anything is stored or shown, OTP capture runs before the
 * notification so a code never produces two alerts, and the auto-reply and forwarding steps run
 * last so a failure there can never cost the user the message itself.
 */
class IncomingMessagePipeline(
    private val repository: MessageRepository,
    private val diagnostics: Diagnostics,
    private val blockEngine: BlockEngine,
    private val otpPresenter: OtpPresenter,
    private val otpVetoes: OtpVetoes,
    private val autoReplyEngine: AutoReplyEngine,
    private val forwardEngine: ForwardEngine,
    private val notifications: Notifications,
    private val quickReplyPresenter: QuickReplyPresenter,
    private val bankLedger: BankLedger,
    private val prefs: AppPrefs,
) {

    /** Which path got to this message first — see [com.hmessaging.data.prefs.DeliveryStats]. */
    enum class Source {
        /** An SMS_DELIVER or SMS_RECEIVED broadcast, the way Android is supposed to deliver. */
        BROADCAST,

        /** Read out of the platform SMS store, because no broadcast ever arrived. */
        PROVIDER_SCAN,
    }

    private companion object {
        const val RECENT_CAPACITY = 200
    }

    /**
     * Fingerprints handled in this process, so the SMS_DELIVER and SMS_RECEIVED copies of the same
     * message collapse into one even when the first was blocked and never stored.
     */
    private val recentlyHandled = object : LinkedHashMap<String, Unit>(RECENT_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean =
            size > RECENT_CAPACITY
    }

    suspend fun handle(
        address: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int,
        parts: Int,
        mirrorToSystem: Boolean,
        source: Source,
    ) {
        val fingerprint = "$address|$receivedAt|${body.hashCode()}"
        val seenInProcess = synchronized(recentlyHandled) {
            if (recentlyHandled.containsKey(fingerprint)) {
                true
            } else {
                recentlyHandled[fingerprint] = Unit
                false
            }
        }
        if (seenInProcess || repository.isAlreadyStored(address, body, receivedAt)) {
            diagnostics.record(Diagnostics.KIND_DUPLICATE, "second copy from $address discarded")
            return
        }

        when (val decision = blockEngine.evaluateMessage(address, body)) {
            is BlockDecision.Blocked -> {
                blockEngine.record(address, body, receivedAt, decision)
                diagnostics.record(Diagnostics.KIND_BLOCKED, "from $address — ${decision.reason}")
                return
            }

            BlockDecision.Allowed -> Unit
        }

        val settings = prefs.settings.first()
        // The reader's own corrections outrank the detector. A message they have said is not a
        // code never becomes one again, however much it looks like one from the outside.
        val otpMatch = if (settings.otpDetectionEnabled) {
            OtpDetector.detect(address, body)?.takeUnless { otpVetoes.isVetoed(address, body) }
        } else {
            null
        }

        val (threadId, messageId) = repository.insertIncoming(
            rawAddress = address,
            body = body,
            date = receivedAt,
            subscriptionId = subscriptionId,
            parts = parts,
            isOtp = otpMatch != null,
            mirrorToSystem = mirrorToSystem,
        )

        diagnostics.record(
            Diagnostics.KIND_STORED,
            "from $address, $parts part(s), thread $threadId, via ${source.name.lowercase()}" +
                if (otpMatch != null) ", OTP" else "",
        )
        prefs.recordDelivery(viaBroadcast = source == Source.BROADCAST)

        // Written down whatever happens. A message that arrives in silence has half a dozen
        // possible causes and, from the outside, they all look the same; the log is how the
        // diagnostics screen tells them apart.
        if (otpMatch != null) {
            val outcome = runCatching { otpPresenter.present(otpMatch, address, body, receivedAt) }
                .getOrDefault(AlertOutcome.FAILED)
            diagnostics.record(Diagnostics.KIND_NOTIFY, "code from $address — ${outcome.name.lowercase()}")
        } else {
            val thread = repository.threadById(threadId)
            if (thread != null) {
                val outcome = notifications.showIncomingMessage(
                    thread = thread,
                    body = body,
                    receivedAt = receivedAt,
                    showPreview = settings.notificationPreview,
                )
                diagnostics.record(Diagnostics.KIND_NOTIFY, "from $address — ${outcome.name.lowercase()}")
                // After the notification, never instead of it: the pop-up only appears when the
                // screen is in use, and dismissing it must not be how a message goes missing.
                if (!thread.muted) {
                    runCatching {
                        quickReplyPresenter.maybeShow(
                            threadId = threadId,
                            address = address,
                            contactName = thread.contactName,
                            body = body,
                            receivedAt = receivedAt,
                        )
                    }
                }
            }
        }

        // A bank notification is filed the moment it lands, so the ledger never waits on a scan.
        runCatching {
            repository.messageById(messageId)?.let { bankLedger.record(it) }
        }

        // Neither of these may take the message down with them if the radio refuses the send.
        runCatching { autoReplyEngine.maybeReply(address, body, receivedAt, subscriptionId) }
        runCatching { forwardEngine.maybeForward(address, body, receivedAt) }
    }
}
