package com.hmessaging.feature.otp

import com.hmessaging.util.PhoneNumbers

data class OtpMatch(
    val code: String,
    val serviceName: String?,
)

/**
 * Finds one-time passwords in incoming messages.
 *
 * Deliberately conservative: a bare number is only treated as a code when the message carries a
 * verification keyword, or when it comes from an alphanumeric sender ID and contains exactly one
 * candidate. That keeps balance alerts, invoice totals and delivery tracking numbers out of the
 * OTP pop-up.
 */
object OtpDetector {

    private const val MIN_CODE_LENGTH = 4
    private const val MAX_CODE_LENGTH = 8
    private const val MAX_GAP = 24

    /** English and Persian words that reliably accompany a verification code. */
    private val KEYWORDS = listOf(
        "otp", "one-time", "one time", "verification", "verify", "code", "passcode", "pin",
        "password", "auth", "authenticate", "confirm", "security code", "login",
        "رمز", "کد", "گذرواژه", "پسورد", "تایید", "تأیید", "فعالسازی", "فعال سازی",
        "یکبار مصرف", "یک بار مصرف", "پویا", "احراز", "ورود", "اعتبارسنجی",
    )

    /** Words that mark a number as money or a reference, never a code. */
    private val NEGATIVE_KEYWORDS = listOf(
        "ریال", "تومان", "مبلغ", "موجودی", "بدهی", "قبض", "شماره پیگیری", "کارت",
        "balance", "amount", "invoice", "account no", "order no", "tracking",
    )

    private val CODE = "([0-9]{$MIN_CODE_LENGTH,$MAX_CODE_LENGTH})"
    private val SEPARATOR = "[^0-9\\n]{0,$MAX_GAP}"

    /** Keyword first: "your verification code is 123456", "کد تایید: ۱۲۳۴۵۶". */
    private val KEYWORD_THEN_CODE = Regex(
        "(?:${KEYWORDS.joinToString("|") { Regex.escape(it) }})$SEPARATOR(?<![0-9])$CODE(?![0-9])",
        setOf(RegexOption.IGNORE_CASE),
    )

    /** Code first: "123456 is your login code". */
    private val CODE_THEN_KEYWORD = Regex(
        "(?<![0-9])$CODE(?![0-9])$SEPARATOR(?:${KEYWORDS.joinToString("|") { Regex.escape(it) }})",
        setOf(RegexOption.IGNORE_CASE),
    )

    private val STANDALONE_CODE = Regex("(?<![0-9])$CODE(?![0-9])")

    fun detect(sender: String, body: String): OtpMatch? {
        if (body.isBlank()) return null
        val text = PhoneNumbers.toAsciiDigits(body)
        val lower = text.lowercase()

        if (NEGATIVE_KEYWORDS.any { lower.contains(it) } && KEYWORDS.none { lower.contains(it) }) {
            return null
        }

        val hasKeyword = KEYWORDS.any { lower.contains(it) }
        val code = when {
            hasKeyword ->
                KEYWORD_THEN_CODE.find(text)?.groupValues?.get(1)
                    ?: CODE_THEN_KEYWORD.find(text)?.groupValues?.get(1)
                    ?: STANDALONE_CODE.find(text)?.groupValues?.get(1)

            // No keyword: only trust a short code sender that sent exactly one candidate.
            isSenderId(sender) -> STANDALONE_CODE.findAll(text)
                .map { it.groupValues[1] }
                .toList()
                .singleOrNull()

            else -> null
        } ?: return null

        return OtpMatch(code = code, serviceName = guessService(sender, text))
    }

    /** Alphanumeric originating addresses are service short codes, never people. */
    private fun isSenderId(sender: String): Boolean =
        sender.any { it.isLetter() } || sender.filter { it.isDigit() }.length in 1..SHORT_CODE_MAX_DIGITS

    private fun guessService(sender: String, body: String): String? {
        if (sender.any { it.isLetter() }) return sender
        // Fall back to the first capitalised word of the message, which is usually the brand.
        return body.split(' ', '\n', '،', ',', '.', ':')
            .map { it.trim() }
            .firstOrNull { word -> word.length in BRAND_MIN_LENGTH..BRAND_MAX_LENGTH && word.all { it.isLetter() } }
    }

    private const val SHORT_CODE_MAX_DIGITS = 6
    private const val BRAND_MIN_LENGTH = 3
    private const val BRAND_MAX_LENGTH = 20
}
