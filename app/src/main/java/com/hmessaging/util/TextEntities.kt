package com.hmessaging.util

/** What a run of characters inside a message turned out to be. */
enum class EntityType { IBAN, CARD, PHONE, ACCOUNT, CODE, URL }

/**
 * One findable thing inside a message body.
 *
 * [start] and [end] index the original text so it can be highlighted in place; [value] is the
 * cleaned form worth putting on the clipboard — ASCII digits with the grouping taken out, because
 * a card number pasted into a banking app must not carry Persian digits or dashes.
 */
data class TextEntity(
    val type: EntityType,
    val text: String,
    val value: String,
    val start: Int,
    val end: Int,
)

/**
 * Pulls the numbers worth copying out of a message: card numbers, IBANs, account numbers, phone
 * numbers, verification codes and links.
 *
 * Every pattern runs against the ASCII-digit form of the body. [PhoneNumbers.toAsciiDigits] maps
 * one character to one character, so an index into that string is also an index into the original —
 * which is what lets a Persian-digit message be highlighted without a second, shifted set of
 * offsets.
 *
 * Order is priority: an IBAN also looks like a long account number and a card number also looks
 * like a digit run, so the more specific pattern claims its span first and later ones skip
 * anything already taken.
 */
object TextEntities {

    private const val MIN_CODE = 4
    private const val MAX_CODE = 8
    private const val MIN_ACCOUNT = 6
    private const val MAX_ACCOUNT = 20

    /** Grouping characters that appear inside a written-out card or account number. */
    private const val GROUPING = "[-‐-―_. ]"

    private val IBAN = Regex("(?<![A-Z0-9])IR$GROUPING?(?:[0-9]$GROUPING?){24}", RegexOption.IGNORE_CASE)

    private val CARD = Regex("(?<![0-9])[0-9]{4}$GROUPING?[0-9]{4}$GROUPING?[0-9]{4}$GROUPING?[0-9]{4}(?![0-9])")

    /** Iranian mobile and landline numbers, plus a generic international form. */
    private val PHONE = Regex("(?<![0-9])(?:\\+98|0098|0)(?:9[0-9]{9}|[1-8][0-9]{9})(?![0-9])")

    private val ACCOUNT_KEYWORDS = listOf("حساب", "سپرده", "شبا", "account", "iban", "acct")

    private val ACCOUNT = Regex(
        "(?:${ACCOUNT_KEYWORDS.joinToString("|") { Regex.escape(it) }})" +
            "[^0-9\\n]{0,16}((?:[0-9]$GROUPING?){$MIN_ACCOUNT,$MAX_ACCOUNT})",
        RegexOption.IGNORE_CASE,
    )

    private val CODE_KEYWORDS = listOf(
        "رمز", "کد", "گذرواژه", "پسورد", "پویا", "یکبار مصرف", "یک بار مصرف",
        "otp", "code", "pin", "password", "passcode",
    )

    private val CODE = Regex(
        "(?:${CODE_KEYWORDS.joinToString("|") { Regex.escape(it) }})" +
            "[^0-9\\n]{0,24}(?<![0-9])([0-9]{$MIN_CODE,$MAX_CODE})(?![0-9])",
        RegexOption.IGNORE_CASE,
    )

    private val URL = Regex("(?:https?://|www\\.)[^\\s‌،,]+", RegexOption.IGNORE_CASE)

    fun detect(body: String): List<TextEntity> {
        if (body.isBlank()) return emptyList()
        val text = PhoneNumbers.toAsciiDigits(body)
        val found = mutableListOf<TextEntity>()

        fun claim(type: EntityType, range: IntRange, digitsOnly: Boolean) {
            if (found.any { range.first < it.end && it.start < range.last + 1 }) return
            val raw = text.substring(range.first, range.last + 1)
            found += TextEntity(
                type = type,
                text = body.substring(range.first, range.last + 1),
                value = if (digitsOnly) raw.filter { it.isDigit() || it.isLetter() } else raw,
                start = range.first,
                end = range.last + 1,
            )
        }

        IBAN.findAll(text).forEach { claim(EntityType.IBAN, it.range, digitsOnly = true) }
        CARD.findAll(text).forEach { claim(EntityType.CARD, it.range, digitsOnly = true) }
        PHONE.findAll(text).forEach { claim(EntityType.PHONE, it.range, digitsOnly = false) }
        // Group 1, not the whole match: the keyword that identified it is not part of the number.
        ACCOUNT.findAll(text).forEach { match ->
            match.groups[1]?.range?.let { claim(EntityType.ACCOUNT, it, digitsOnly = true) }
        }
        CODE.findAll(text).forEach { match ->
            match.groups[1]?.range?.let { claim(EntityType.CODE, it, digitsOnly = true) }
        }
        URL.findAll(text).forEach { claim(EntityType.URL, it.range, digitsOnly = false) }

        return found.sortedBy { it.start }
    }
}
