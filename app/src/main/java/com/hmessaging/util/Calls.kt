package com.hmessaging.util

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Placing a call from a conversation.
 *
 * `ACTION_DIAL` rather than `ACTION_CALL`: it opens the dialer with the number filled in and lets
 * the person press the green button themselves. That needs no permission and, more to the point,
 * means a mistaken tap in a messaging app can never start ringing someone.
 */
object Calls {

    /** True for an address a dialer could do something with: digits, and no letters. */
    fun isDialable(address: String?): Boolean {
        if (address.isNullOrBlank() || address == PhoneNumbers.UNKNOWN_ADDRESS) return false
        val normalized = PhoneNumbers.normalize(address)
        if (normalized.any { it.isLetter() }) return false
        return normalized.count { it.isDigit() } >= MIN_DIALABLE_DIGITS
    }

    fun dial(context: Context, address: String?) {
        if (!isDialable(address)) return
        val intent = Intent(Intent.ACTION_DIAL, "tel:${PhoneNumbers.normalize(address!!)}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Short codes below this are services, not people; a dialer cannot reach them. */
    private const val MIN_DIALABLE_DIGITS = 5
}
