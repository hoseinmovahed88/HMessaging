package com.hmessaging.feature.bank

import com.hmessaging.util.PhoneNumbers

/**
 * Which of a bank's accounts a message is about.
 *
 * The bank prints the account number in the message. Once one message has been explained and the
 * app knows that "1038301315305" is an account at this bank, every other message from that bank
 * containing those digits is about that account — and that is a string comparison, not a guess.
 * It works across the bank's other formats without any of them being taught: a deposit notice, a
 * card purchase and a transfer all name the same account the same way.
 *
 * What it will not do is decide that some number it has never been told about is an account.
 * A message naming an account nobody has explained stays unattributed, which is the honest answer
 * and the one that leaves the reader in charge.
 */
object BankAccounts {

    /**
     * Below this a "match" is not evidence. Four digits of a masked card appear by chance in
     * amounts, dates and reference numbers often enough to put a message under the wrong account.
     */
    private const val MIN_KEY_DIGITS = 5

    /**
     * The known account whose digits appear in [body], longest first.
     *
     * Longest first because account numbers nest: a bank that writes both `1038301315305` and the
     * masked `1305` would otherwise file the full number's messages under the mask.
     */
    fun accountFor(body: String, keys: Collection<String>): String? {
        val digits = PhoneNumbers.toAsciiDigits(body).filter { it.isDigit() || it == '\n' }
        val text = PhoneNumbers.toAsciiDigits(body)
        return keys.asSequence()
            .filter { it.length >= MIN_KEY_DIGITS && it.all(Char::isDigit) }
            .sortedByDescending { it.length }
            .firstOrNull { key -> text.contains(key) || digits.contains(key) }
    }

    /**
     * Groups a bank's messages by account, keeping each account's messages in the order given.
     *
     * The key of the group for anything unattributed is null, so a caller can show those together
     * rather than hiding them — a message this cannot place is still a message about money.
     */
    fun <T> groupByAccount(
        items: List<T>,
        keys: Collection<String>,
        body: (T) -> String,
    ): Map<String?, List<T>> = items.groupBy { accountFor(body(it), keys) }
}
