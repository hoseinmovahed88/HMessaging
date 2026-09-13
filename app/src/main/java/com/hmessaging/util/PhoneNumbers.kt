package com.hmessaging.util

import android.telephony.PhoneNumberUtils

const val RECIPIENT_SEPARATOR = ","

/**
 * Phone-number normalisation that survives the messy reality of SMS: Persian/Arabic-Indic digits,
 * zero-width marks injected by some operators, and alphanumeric sender IDs such as "BANK".
 */
object PhoneNumbers {

    private val PERSIAN_DIGITS = '۰'..'۹'
    private val ARABIC_DIGITS = '٠'..'٩'
    private val INVISIBLE = setOf(
        '\u200B', // zero-width space
        '\u200C', // zero-width non-joiner
        '\u200D', // zero-width joiner
        '\u200E', // left-to-right mark
        '\u200F', // right-to-left mark
        '\uFEFF', // byte-order mark
    )

    /** Converts ۰۱۲… and ٠١٢… to 012…, leaving everything else untouched. */
    /**
     * Arabic letters that Persian text is routinely written with, mapped to their Persian forms.
     *
     * Iranian senders mix them freely — the same word arrives as "توزیع" and "توزيع" from the same
     * service — and a keyword list written one way silently fails to match the other. One character
     * maps to one character, so offsets into the result still index the original.
     */
    fun normalizeLetters(input: String): String = buildString(input.length) {
        for (ch in input) {
            append(
                when (ch) {
                    'ي' -> 'ی'
                    'ك' -> 'ک'
                    'ة' -> 'ه'
                    'ۀ' -> 'ه'
                    else -> ch
                },
            )
        }
    }

    /** Digits and letters both put in the one form the rest of the app matches against. */
    fun canonical(input: String): String = normalizeLetters(toAsciiDigits(input))

    fun toAsciiDigits(input: String): String = buildString(input.length) {
        for (ch in input) {
            when (ch) {
                in PERSIAN_DIGITS -> append('0' + (ch - '۰'))
                in ARABIC_DIGITS -> append('0' + (ch - '٠'))
                else -> append(ch)
            }
        }
    }

    /**
     * Canonical form used as the thread key. Digits keep an optional leading `+`; alphanumeric
     * sender IDs are upper-cased and trimmed.
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return UNKNOWN_ADDRESS
        val cleaned = toAsciiDigits(raw).filterNot { it in INVISIBLE }.trim()
        if (cleaned.isEmpty()) return UNKNOWN_ADDRESS

        val hasLetters = cleaned.any { it.isLetter() }
        if (hasLetters) return cleaned.uppercase()

        val digitsAndPlus = cleaned.filter { it.isDigit() || it == '+' }
        if (digitsAndPlus.isEmpty()) return UNKNOWN_ADDRESS

        // A '+' is only meaningful in the leading position.
        val leadingPlus = digitsAndPlus.startsWith('+')
        val digits = digitsAndPlus.filter { it.isDigit() }
        return if (leadingPlus) "+$digits" else digits
    }

    /** Loose equality: `+989121234567`, `09121234567` and `9121234567` are the same person. */
    fun sameNumber(a: String?, b: String?): Boolean {
        val left = normalize(a)
        val right = normalize(b)
        if (left == right) return true
        if (left == UNKNOWN_ADDRESS || right == UNKNOWN_ADDRESS) return false
        if (left.any { it.isLetter() } || right.any { it.isLetter() }) return false
        // Deprecated in favour of the API-31 areSamePhoneNumber, which needs a country ISO we do
        // not always have; the digit-tail check below covers what it misses.
        @Suppress("DEPRECATION")
        if (PhoneNumberUtils.compare(left, right)) return true
        val tailLeft = left.takeLast(MIN_SIGNIFICANT_DIGITS)
        val tailRight = right.takeLast(MIN_SIGNIFICANT_DIGITS)
        return tailLeft.length == MIN_SIGNIFICANT_DIGITS && tailLeft == tailRight
    }

    /**
     * The identity a conversation is keyed on.
     *
     * [normalize] preserves what the operator sent, so the same person reaches you as
     * `+989121234567` from one route and `09121234567` from another and lands in two separate
     * conversations. Keying on the significant tail collapses those, exactly as [sameNumber]
     * already does when comparing two numbers directly.
     *
     * Alphanumeric sender IDs and short codes are returned whole — they are not phone numbers and
     * two different short codes must never merge.
     */
    fun threadKey(raw: String?): String {
        val normalized = normalize(raw)
        if (normalized == UNKNOWN_ADDRESS) return UNKNOWN_ADDRESS
        if (normalized.any { it.isLetter() }) return normalized
        val digits = normalized.filter { it.isDigit() }
        if (digits.isEmpty()) return normalized
        return if (digits.length > MIN_SIGNIFICANT_DIGITS) {
            digits.takeLast(MIN_SIGNIFICANT_DIGITS)
        } else {
            digits
        }
    }

    /** Splits a user-entered recipient field into normalised numbers. */
    fun splitRecipients(value: String): List<String> =
        value.split(RECIPIENT_SEPARATOR, ";", "\n")
            .map { normalize(it) }
            .filter { it != UNKNOWN_ADDRESS }
            .distinct()

    fun joinRecipients(values: List<String>): String = values.joinToString(RECIPIENT_SEPARATOR)

    /** True for withheld caller IDs, which arrive as an empty or placeholder address. */
    fun isPrivateOrHidden(address: String?): Boolean {
        val normalized = normalize(address)
        if (normalized == UNKNOWN_ADDRESS) return true
        if (normalized in HIDDEN_PLACEHOLDERS) return true
        return normalized.none { it.isDigit() || it.isLetter() }
    }

    /**
     * Display form: keeps sender IDs as-is, groups digits for readability.
     *
     * Memoised because this is a framework call and every conversation row asks for it on every
     * recomposition.
     */
    fun format(address: String): String {
        if (address.any { it.isLetter() }) return address
        formatCache[address]?.let { return it }
        val formatted = PhoneNumberUtils.formatNumber(address, java.util.Locale.getDefault().country)
            ?: address
        if (formatCache.size > FORMAT_CACHE_LIMIT) formatCache.clear()
        formatCache[address] = formatted
        return formatted
    }

    private val formatCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    const val UNKNOWN_ADDRESS = "unknown"

    private const val MIN_SIGNIFICANT_DIGITS = 9
    private const val FORMAT_CACHE_LIMIT = 500
    private val HIDDEN_PLACEHOLDERS = setOf("UNKNOWN", "PRIVATE", "ANONYMOUS", "RESTRICTED")
}
