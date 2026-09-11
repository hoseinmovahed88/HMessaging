package com.hmessaging.util

import android.telephony.SmsMessage

/**
 * Result of measuring a message against the GSM 03.38 / UCS-2 limits.
 *
 * SMS has no "long message" concept on the wire: anything past one segment is sent as a
 * concatenated multipart message. This tells the composer how many segments a body will cost.
 */
data class SmsLength(
    val parts: Int,
    val usedInCurrentPart: Int,
    val remainingInCurrentPart: Int,
    val isUnicode: Boolean,
) {
    val limitForCurrentPart: Int get() = usedInCurrentPart + remainingInCurrentPart
}

object SmsText {

    private const val ENCODING_16BIT = 3

    private const val GSM_SINGLE = 160
    private const val GSM_MULTI = 153
    private const val UCS2_SINGLE = 70
    private const val UCS2_MULTI = 67

    /**
     * Measures [text] using the platform's own segmentation maths so the counter always agrees
     * with what [android.telephony.SmsManager.divideMessage] will actually do.
     */
    fun measure(text: String): SmsLength {
        if (text.isEmpty()) {
            return SmsLength(parts = 0, usedInCurrentPart = 0, remainingInCurrentPart = GSM_SINGLE, isUnicode = false)
        }
        return runCatching {
            // [0] segment count, [1] code units used, [2] code units remaining, [3] encoding
            val info = SmsMessage.calculateLength(text, false)
            SmsLength(
                parts = info[0],
                usedInCurrentPart = info[1],
                remainingInCurrentPart = info[2],
                isUnicode = info[3] == ENCODING_16BIT,
            )
        }.getOrElse { measureFallback(text) }
    }

    /** Used when the telephony stack is unavailable (for example in unit tests). */
    private fun measureFallback(text: String): SmsLength {
        val unicode = text.any { it.code > 0x7F }
        val single = if (unicode) UCS2_SINGLE else GSM_SINGLE
        val multi = if (unicode) UCS2_MULTI else GSM_MULTI
        val length = text.length
        return if (length <= single) {
            SmsLength(1, length, single - length, unicode)
        } else {
            val parts = (length + multi - 1) / multi
            val used = length - (parts - 1) * multi
            SmsLength(parts, used, multi - used, unicode)
        }
    }

    /**
     * Splits a very long body into numbered chunks.
     *
     * Multipart SMS already reassembles transparently on any modern handset, so this is opt-in:
     * it exists for gateways and old devices that drop concatenation headers, and for bodies past
     * the 255-segment ceiling of a single concatenated message.
     */
    fun splitNumbered(text: String, maxCharsPerChunk: Int): List<String> {
        require(maxCharsPerChunk > MAX_PREFIX_LENGTH) { "chunk size must leave room for the counter" }
        if (text.length <= maxCharsPerChunk) return listOf(text)

        // The prefix costs characters too, so budget for it before deciding the chunk count.
        val budget = maxCharsPerChunk - MAX_PREFIX_LENGTH
        val total = (text.length + budget - 1) / budget
        return (0 until total).map { index ->
            val start = index * budget
            val end = minOf(start + budget, text.length)
            "${index + 1}/$total " + text.substring(start, end)
        }
    }

    /** Largest body that still fits in [segments] concatenated parts. */
    fun maxCharsFor(segments: Int, unicode: Boolean): Int =
        if (segments <= 1) {
            if (unicode) UCS2_SINGLE else GSM_SINGLE
        } else {
            segments * if (unicode) UCS2_MULTI else GSM_MULTI
        }

    private const val MAX_PREFIX_LENGTH = 8 // "999/999 "
}
