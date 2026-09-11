package com.hmessaging.sms

import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.data.repo.MessageRepository
import com.hmessaging.feature.autoreply.AutoReplyEngine
import com.hmessaging.feature.block.BlockDecision
import com.hmessaging.feature.block.BlockEngine
import com.hmessaging.feature.forward.ForwardEngine
import com.hmessaging.feature.otp.OtpDetector
import com.hmessaging.feature.otp.OtpPresenter
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
    private val autoReplyEngine: AutoReplyEngine,
    private val forwardEngine: ForwardEngine,
    private val notifications: Notifications,
    private val prefs: AppPrefs,
) {

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
        val otpMatch = if (settings.otpDetectionEnabled) OtpDetector.detect(address, body) else null

        val (threadId, _) = repository.insertIncoming(
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
            "from $address, $parts part(s), thread $threadId" + if (otpMatch != null) ", OTP" else "",
        )

        if (otpMatch != null) {
            runCatching { otpPresenter.present(otpMatch, address, body, receivedAt) }
        } else {
            val thread = repository.threadById(threadId)
            if (thread != null) {
                notifications.showIncomingMessage(
                    thread = thread,
                    body = body,
                    receivedAt = receivedAt,
                    showPreview = settings.notificationPreview,
                )
            }
        }

        // Neither of these may take the message down with them if the radio refuses the send.
        runCatching { autoReplyEngine.maybeReply(address, body, receivedAt, subscriptionId) }
        runCatching { forwardEngine.maybeForward(address, body, receivedAt) }
    }
}
