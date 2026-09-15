package com.hmessaging.feature.otp

import com.hmessaging.util.PhoneNumbers

/**
 * What makes two messages "the same kind of message".
 *
 * A service writes from a template and fills in the numbers, so two messages from one template
 * differ in nothing but their digits. Taking the digits out leaves the template itself, which is
 * the thing a reader means when they say this sort of message is never a verification code.
 *
 * This is deliberately exact rather than clever. It does not try to work out what a message is
 * about — that is the guessing that put a consignment number on screen as a code in the first
 * place. It only answers whether two messages were written from the same words, which is a
 * question with a right answer.
 *
 * It fails in the safe direction: a template whose wording also varies produces a different shape
 * and simply is not matched, so a rule that stops applying costs one more tap. A rule that applied
 * too widely would cost a real verification code, which is not recoverable.
 */
object OtpShape {

    /** Everything that is not a digit, in one spelling, with the spacing flattened. */
    fun of(body: String): String = PhoneNumbers.canonical(body)
        // Separators inside a number belong to the number, not to the template. Left in, a bank's
        // "برداشت پایا1,730,000,000" and its "برداشت پایا2,000,000" come out with different
        // numbers of commas and read as two different kinds of message — which they are not.
        .replace(GROUPING_INSIDE_NUMBER, "")
        .filterNot(Char::isDigit)
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_LENGTH)

    /** The stored key: one sender's one template. */
    fun keyFor(sender: String, body: String): String = "${PhoneNumbers.threadKey(sender)}|${of(body)}"

    /**
     * A readable line for the list of what has been ruled out, since the shape itself is the
     * message with holes where its numbers were and reads badly on its own.
     */
    fun sampleOf(body: String): String = body.replace(WHITESPACE, " ").trim().take(SAMPLE_LENGTH)

    private val WHITESPACE = Regex("\\s+")

    /** A separator with a digit on each side: thousands grouping, or a decimal point. */
    private val GROUPING_INSIDE_NUMBER = Regex("(?<=[0-9])[,،٬.](?=[0-9])")

    /** Long enough to tell two templates apart, short enough not to store a novel. */
    private const val MAX_LENGTH = 400
    private const val SAMPLE_LENGTH = 160
}
