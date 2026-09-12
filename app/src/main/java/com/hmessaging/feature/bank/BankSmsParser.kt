package com.hmessaging.feature.bank

import com.hmessaging.data.model.BankTxKind
import com.hmessaging.util.PhoneNumbers

/** A bank transaction read out of a notification SMS. */
data class BankTx(
    val kind: BankTxKind,
    val amount: Long,
    val currency: String,
    /** The account or card as the bank wrote it, masking and all: `*۱۲۳۴`, `6219****1234`. */
    val accountLabel: String?,
    val balance: Long?,
    val bank: String?,
)

/**
 * Reads Iranian bank notification SMS.
 *
 * These messages are not a format anyone standardised — every bank writes its own — but they share
 * a shape: a word saying which direction the money went, an amount, usually a masked account or
 * card, and usually the balance afterwards. That shape is what is matched here, rather than a list
 * of per-bank templates that would go stale the first time a bank reworded anything.
 *
 * A message is only accepted as a transaction when it carries both a direction word and an amount.
 * That is what keeps verification codes, balance enquiries and marketing out of the ledger: the
 * cost of being wrong here is a made-up row in the user's own accounts, which is worse than a
 * missed one.
 */
object BankSmsParser {

    private val DEPOSIT_WORDS = listOf(
        "واریز", "وارﯾﺰ", "بستانکار", "افزایش یافت", "دریافت شد", "دریافتی",
        "credit", "deposit", "received",
    )

    private val WITHDRAWAL_WORDS = listOf(
        "برداشت", "بدهکار", "خرید", "پرداخت", "انتقال", "کاهش یافت", "کسر",
        "debit", "withdraw", "purchase", "payment",
    )

    private val BALANCE_WORDS = listOf("مانده", "موجودی", "balance")

    private val ACCOUNT_WORDS = listOf("حساب", "سپرده", "کارت", "شبا", "account", "card")

    /** A written amount: digits in groups, optionally separated by commas or Persian thousands. */
    private const val NUMBER = "[0-9]{1,3}(?:[,،٬][0-9]{3})+|[0-9]{4,}"

    private val CURRENCIES = mapOf(
        "ریال" to "IRR",
        "ر.ی" to "IRR",
        "rial" to "IRR",
        "irr" to "IRR",
        "تومان" to "IRT",
        "تومن" to "IRT",
        "toman" to "IRT",
    )

    private val BALANCE = Regex(
        "(?:${BALANCE_WORDS.joinToString("|") { Regex.escape(it) }})[^0-9\\n]{0,12}($NUMBER)",
        RegexOption.IGNORE_CASE,
    )

    /** The masked account or card the bank names, kept verbatim so the user recognises it. */
    private val ACCOUNT = Regex(
        "(?:${ACCOUNT_WORDS.joinToString("|") { Regex.escape(it) }})" +
            "[:\\s]{0,3}([0-9*xX#\\u066A]{2,}(?:[-. ]?[0-9*xX#]{2,}){0,4})",
        RegexOption.IGNORE_CASE,
    )

    private val ANY_NUMBER = Regex(NUMBER)

    fun parse(body: String): BankTx? {
        if (body.isBlank()) return null
        val text = PhoneNumbers.toAsciiDigits(body)
        val lower = text.lowercase()

        val deposit = firstIndexOfAny(lower, DEPOSIT_WORDS)
        val withdrawal = firstIndexOfAny(lower, WITHDRAWAL_WORDS)
        val kind = when {
            deposit >= 0 && withdrawal >= 0 -> if (deposit < withdrawal) {
                BankTxKind.DEPOSIT
            } else {
                BankTxKind.WITHDRAWAL
            }

            deposit >= 0 -> BankTxKind.DEPOSIT
            withdrawal >= 0 -> BankTxKind.WITHDRAWAL
            else -> return null
        }

        val balance = BALANCE.find(text)?.let { toLong(it.groupValues[1]) }
        val balanceRange = BALANCE.find(text)?.groups?.get(1)?.range
        val amount = amountNear(text, if (kind == BankTxKind.DEPOSIT) deposit else withdrawal, balanceRange)
            ?: return null

        return BankTx(
            kind = kind,
            amount = amount,
            currency = currencyOf(lower),
            accountLabel = ACCOUNT.find(text)?.groupValues?.get(1)?.takeIf { it.any(Char::isDigit) },
            balance = balance,
            bank = null,
        )
    }

    /**
     * The amount is the number closest after the direction word — "واریز:۵۰۰,۰۰۰" — falling back to
     * the first number in the message. The balance is skipped either way: it is the largest number
     * in most of these messages, so anything that picked by size would report the balance as the
     * transaction every time.
     */
    private fun amountNear(text: String, keywordIndex: Int, balanceRange: IntRange?): Long? {
        val candidates = ANY_NUMBER.findAll(text)
            .filter { balanceRange == null || it.range.first != balanceRange.first }
            .toList()
        if (candidates.isEmpty()) return null
        val after = candidates.firstOrNull { it.range.first >= keywordIndex }
        return toLong((after ?: candidates.first()).value)
    }

    private fun firstIndexOfAny(haystack: String, needles: List<String>): Int =
        needles.map { haystack.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1

    private fun currencyOf(lower: String): String =
        CURRENCIES.entries.firstOrNull { lower.contains(it.key) }?.value ?: "IRR"

    private fun toLong(raw: String): Long? =
        raw.filter { it.isDigit() }.takeIf { it.isNotEmpty() && it.length <= MAX_DIGITS }?.toLongOrNull()

    /** Beyond this a "number" is an account or a reference, not a sum of money. */
    private const val MAX_DIGITS = 15
}
