package com.hmessaging.feature.bank

import com.hmessaging.data.model.BankTxKind
import com.hmessaging.util.PhoneNumbers

/**
 * Sorting one bank's conversation into accounts and into money in versus money out.
 *
 * This is for the tabs above a conversation, and it is worth being clear about how much it is
 * allowed to decide. It never reads an amount — that is what the taught formats are for, and a
 * parser that guessed at amounts is what filled this app's ledger with six thousand wrong rows
 * once already. It only answers two questions that the message states in words: which account
 * number is printed in it, and whether the bank called this money in or money out.
 *
 * Getting either wrong costs a message filed under the wrong tab, which is visible and reversible.
 * No total is computed from any of it.
 */
object BankAccounts {

    /** Below this a pure-digit key matches dates, amounts and reference numbers by chance. */
    private const val MIN_DIGIT_KEY = 5

    /** A line that is nothing but a long number is how some banks print the account. */
    private const val MIN_BARE_ACCOUNT_DIGITS = 8

    // ---- which account -----------------------------------------------------------------

    /**
     * The known account whose identifier appears in [body], longest first.
     *
     * Longest first because identifiers nest: a bank writing both `1038301315305` and a masked
     * `1305` would otherwise file the full number's messages under the mask.
     */
    fun accountFor(body: String, keys: Collection<String>): String? {
        if (keys.isEmpty()) return null
        val text = normalize(body)
        return keys.asSequence()
            .filter { it.isNotBlank() && (it.any { ch -> !ch.isDigit() } || it.length >= MIN_DIGIT_KEY) }
            .sortedByDescending { it.length }
            .firstOrNull { text.contains(normalize(it)) }
    }

    /**
     * Account identifiers this message prints, found without having been told about them.
     *
     * Three ways, and the first two are the bank's own labelling rather than anything inferred:
     *
     *  - after a label the reader already taught, so one lesson on "حساب:" finds every account
     *    that bank ever names that way, not only the account in the message that was taught;
     *  - on a line that holds a number and nothing else, which is how Keshavarzi prints it;
     *  - on a line naming a card, which is how the same bank prints a card instead of an account.
     *
     * Only asked of senders the reader has already called a bank. On any other conversation this
     * is never run, so nothing anywhere else can be renamed an account by accident.
     */
    fun accountsIn(body: String, taughtAnchors: Collection<String>): List<String> {
        val found = LinkedHashSet<String>()

        if (taughtAnchors.isNotEmpty()) {
            val wanted = taughtAnchors.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
            BankRules.numbersIn(body)
                .filter { normalize(it.anchor) in wanted }
                .forEach { span -> span.text.filter(Char::isDigit).takeIf { it.isNotEmpty() }?.let(found::add) }
        }

        PhoneNumbers.stripInvisible(PhoneNumbers.toAsciiDigits(body))
            .split('\n')
            .map { it.trim() }
            .forEach { line ->
                when {
                    line.length >= MIN_BARE_ACCOUNT_DIGITS && line.all(Char::isDigit) -> found.add(line)
                    CARD_LINE.matches(line) -> found.add(line)
                }
            }

        return found.toList()
    }

    /**
     * Groups a bank's messages by account, keeping each account's messages in the order given.
     *
     * Anything it cannot place is grouped under null rather than dropped: a message this cannot
     * attribute is still a message about money, and hiding it would be the one outcome worse than
     * filing it loosely.
     */
    fun <T> groupByAccount(
        items: List<T>,
        keys: Collection<String>,
        body: (T) -> String,
    ): Map<String?, List<T>> = items.groupBy { accountFor(body(it), keys) }

    /** A line that names a card by its last digits: `کارت9310*`, `کارت *8344`. */
    private val CARD_LINE = Regex("^(?:کارت|كارت|card)\\s*\\*?\\s*[0-9]{4,}\\s*\\*?$", RegexOption.IGNORE_CASE)

    // ---- which way the money went -------------------------------------------------------

    /**
     * Whether the bank called this money in or money out, or null when it said neither.
     *
     * Two conditions, both of them things the message says rather than things worked out from it.
     * It has to state a balance, which is what separates a transaction from the notices that
     * surround it — a login alert, a verification code for a purchase not yet made, an invoice
     * added to a queue. And it has to use one of the words below, which Iranian banks use to mean
     * exactly one direction each.
     *
     * A reversal is read as the reversal and not as the withdrawal it undoes: "برگشت برداشت پل"
     * contains both words and is money coming back.
     */
    fun directionOf(body: String): BankTxKind? {
        val text = PhoneNumbers.canonical(body).lowercase()
        if (BALANCE_WORDS.none { text.contains(it) }) return null
        if (REVERSAL_WORDS.any { text.contains(it) }) return BankTxKind.DEPOSIT
        if (DEPOSIT_WORDS.any { text.contains(it) }) return BankTxKind.DEPOSIT
        if (WITHDRAWAL_WORDS.any { text.contains(it) }) return BankTxKind.WITHDRAWAL
        return null
    }

    /**
     * A stated balance is what makes a message a transaction.
     *
     * Every transaction notice from both banks on this phone prints one, and none of the notices
     * that are not transactions do: the mobile-bank login, the purchase code, the item added to a
     * signing queue. It is a far better test than the vocabulary alone, which a purchase code also
     * matches because it too says "خرید".
     */
    private val BALANCE_WORDS = listOf("مانده", "موجودی")

    private val REVERSAL_WORDS = listOf("برگشت", "بازگشت", "عودت")
    private val DEPOSIT_WORDS = listOf("واریز", "وصول")
    private val WITHDRAWAL_WORDS = listOf("برداشت", "خرید", "کارمزد", "پرداخت", "انتقال", "حواله")

    /** Comparable form: one spelling of every letter and digit, no invisible marks, no spaces. */
    private fun normalize(text: String): String =
        PhoneNumbers.stripInvisible(PhoneNumbers.canonical(text)).filterNot { it.isWhitespace() }
}
