package com.hmessaging.feature.bank

import com.hmessaging.data.db.entity.BankRuleEntity
import com.hmessaging.data.model.BankTxKind
import com.hmessaging.util.PhoneNumbers

/** A transaction read out of a bank SMS by a rule the user taught. */
data class BankTx(
    val kind: BankTxKind,
    val amount: Long,
    val currency: String,
    /** The account or card as the bank wrote it, masking and all. */
    val accountLabel: String?,
    val balance: Long?,
    val bank: String?,
)

/** A number found in a message, with where it sits and what is written just before it. */
data class NumberSpan(
    val text: String,
    val value: Long?,
    val start: Int,
    val end: Int,
    /** The non-digit text immediately before it, trimmed — the rule's anchor. */
    val anchor: String,
)

/**
 * Turns one message the user has explained into a rule, and applies rules to later messages.
 *
 * The whole point is that nothing here guesses. [numbersIn] only lists what is in the text; which
 * of those is the amount, which is the balance and which way the money went are answers that come
 * from the person reading it. What the code contributes is noticing that "برداشت:" is what sits in
 * front of the number they picked, so the next message with "برداشت:" in it can be read the same
 * way without asking again.
 */
object BankRules {

    /** How much text before a number to keep as its anchor. Long enough to be distinctive. */
    private const val ANCHOR_LENGTH = 14

    /**
     * A run of digits, keeping the grouping and masking characters written inside it.
     *
     * `-` is deliberately not one of them: it joins the two halves of a date into a single
     * meaningless number, and the card masks that need joining use `*`.
     */
    private val NUMBER = Regex("[0-9][0-9,،٬.*]{0,30}[0-9]|[0-9]")

    /**
     * Every number in a message, in order, each with the text written in front of it.
     *
     * Persian digits are mapped to ASCII first, and that mapping is one character to one character,
     * so the offsets returned also index the original text — which is what lets the message be
     * shown as written while the numbers inside it are addressed by position.
     */
    fun numbersIn(body: String): List<NumberSpan> {
        val text = PhoneNumbers.toAsciiDigits(body)
        return NUMBER.findAll(text)
            .filter { it.value.any(Char::isDigit) }
            .map { match ->
                val start = match.range.first
                NumberSpan(
                    text = body.substring(start, match.range.last + 1),
                    value = toAmount(match.value),
                    start = start,
                    end = match.range.last + 1,
                    anchor = anchorBefore(text, start),
                )
            }
            .toList()
    }

    /**
     * The label written immediately before [index] — `برداشت:`, `مانده:`, `از كارت:`.
     *
     * It stops at the previous digit and at the previous line break. Stopping at the digit is what
     * keeps the balance's anchor in "برداشت:1,000 مانده:5,000" from swallowing the amount, which
     * would make it match only messages whose amount has exactly as many characters. Stopping at
     * the line break keeps a label from dragging in the line above it, which is often a heading the
     * bank varies between messages.
     */
    private fun anchorBefore(text: String, index: Int): String {
        var start = index
        while (start > 0 && !text[start - 1].isDigit() && text[start - 1] != '\n') start--
        return text.substring(start, index).trim().takeLast(ANCHOR_LENGTH).trim()
    }

    /** Builds the rule described by the user's picks. */
    fun ruleFrom(
        senderAddress: String,
        senderLabel: String,
        name: String,
        kind: BankTxKind,
        currency: String,
        body: String,
        amount: NumberSpan,
        balance: NumberSpan?,
        account: NumberSpan?,
        accountLiteral: String?,
    ): BankRuleEntity = BankRuleEntity(
        senderKey = PhoneNumbers.threadKey(senderAddress),
        senderLabel = senderLabel,
        name = name,
        kind = kind,
        amountAnchor = amount.anchor,
        balanceAnchor = balance?.anchor,
        accountAnchor = account?.anchor,
        accountLiteral = accountLiteral?.takeIf { it.isNotBlank() },
        currency = currency,
        sampleBody = body,
    )

    /**
     * Reads a message with a rule, or returns null when this is not that kind of message.
     *
     * An anchor that is not in the text is the answer "no": a bank's deposit rule must not fire on
     * its withdrawal messages, and the wording is the only thing that separates them.
     */
    fun apply(rule: BankRuleEntity, body: String): BankTx? {
        if (!rule.enabled) return null
        val numbers = numbersIn(body)
        val amount = numbers.firstOrNull { it.anchor == rule.amountAnchor }?.value ?: return null
        if (amount <= 0) return null

        return BankTx(
            kind = rule.kind,
            amount = amount,
            currency = rule.currency,
            accountLabel = rule.accountLiteral
                ?: rule.accountAnchor?.let { anchor -> numbers.firstOrNull { it.anchor == anchor }?.text },
            balance = rule.balanceAnchor?.let { anchor ->
                numbers.firstOrNull { it.anchor == anchor }?.value
            },
            bank = rule.name,
        )
    }

    /**
     * What the teach screen offers as a starting point, so the common case is confirming rather
     * than hunting. Only a suggestion: the user's tap always wins, and nothing is saved from this.
     */
    fun suggestAmount(numbers: List<NumberSpan>): NumberSpan? =
        numbers.firstOrNull { span ->
            span.value != null && span.text.any { it == ',' || it == '،' || it == '٬' }
        } ?: numbers.firstOrNull { it.value != null && it.value >= MIN_LIKELY_AMOUNT }

    fun suggestCurrency(body: String): String = when {
        body.contains("تومان") || body.contains("تومن") || body.contains("toman", true) -> "IRT"
        else -> "IRR"
    }

    private fun toAmount(raw: String): Long? =
        raw.filter(Char::isDigit).takeIf { it.isNotEmpty() && it.length <= MAX_AMOUNT_DIGITS }?.toLongOrNull()

    /** Beyond this a run of digits is an account or a reference, never a sum of money. */
    private const val MAX_AMOUNT_DIGITS = 15
    private const val MIN_LIKELY_AMOUNT = 1_000L
}
