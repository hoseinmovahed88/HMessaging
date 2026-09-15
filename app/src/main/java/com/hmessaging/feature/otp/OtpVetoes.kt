package com.hmessaging.feature.otp

import com.hmessaging.data.db.dao.MessageDao
import com.hmessaging.data.db.dao.OtpDao
import com.hmessaging.data.db.entity.OtpVetoEntity
import com.hmessaging.util.PhoneNumbers
import java.util.concurrent.atomic.AtomicReference

/**
 * The kinds of message the reader has said are never verification codes.
 *
 * [OtpDetector] guesses, carefully, from wording it was taught in advance; this is the correction
 * for the messages it still gets wrong. One tap on a message that is not a code rules out that
 * sender's whole template, and the word lists never have to grow another entry for it.
 *
 * Keys are held in memory because every incoming message asks this question, and the answer
 * changes only when the reader taps the button.
 */
class OtpVetoes(
    private val otpDao: OtpDao,
    private val messageDao: MessageDao,
) {

    private val cached = AtomicReference<Set<String>?>(null)

    /** True when this sender's messages of this shape have been ruled out. */
    suspend fun isVetoed(sender: String, body: String): Boolean =
        keys().contains(OtpShape.keyFor(sender, body))

    /**
     * Rules out every message written from this one's template, and undoes what was already done
     * about the ones already stored.
     *
     * Clearing the flag on messages already filed matters beyond appearances: a message marked as
     * a code is deleted by the code-retention sweep, so a bank notice mistaken for a code would
     * quietly disappear a week later.
     */
    suspend fun veto(sender: String, body: String) {
        otpDao.insertVeto(
            OtpVetoEntity(
                senderKey = PhoneNumbers.threadKey(sender),
                senderLabel = PhoneNumbers.format(sender),
                shape = OtpShape.of(body),
                sample = OtpShape.sampleOf(body),
            ),
        )
        cached.set(null)

        otpDao.deleteBySenderAndBody(sender, body)
        unflagStoredMessages()
    }

    suspend fun remove(id: Long) {
        otpDao.deleteVeto(id)
        cached.set(null)
    }

    /**
     * Takes the code flag off stored messages that the rules now say are not codes.
     *
     * Walks only the messages already flagged, which is a handful even on a phone with years of
     * history, so this can simply run after every change rather than tracking what to revisit.
     */
    private suspend fun unflagStoredMessages() {
        val keys = keys()
        if (keys.isEmpty()) return
        messageDao.flaggedAsOtp()
            .filter { keys.contains(OtpShape.keyFor(it.address, it.body)) }
            .forEach { messageDao.clearOtpFlag(it.id) }
    }

    private suspend fun keys(): Set<String> {
        cached.get()?.let { return it }
        val loaded = otpDao.vetoKeys().toSet()
        cached.set(loaded)
        return loaded
    }

    fun invalidate() = cached.set(null)
}
