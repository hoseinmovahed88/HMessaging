package com.hmessaging.util

/**
 * Matching a typed query against a person: their name, or their number.
 *
 * Done in Kotlin rather than SQL because neither side of the comparison is in a normal form. The
 * stored text is exactly what arrived — Arabic and Persian spellings of the same letter, Persian
 * and ASCII digits — and the number is stored as the operator wrote it, so `+989121234567` and
 * `09121234567` are the same person spelled two ways. SQL `LIKE` compares bytes and finds neither.
 */
object SearchMatch {

    /** True when [query] appears in any of [fields], in any of the spellings either side may use. */
    fun matchesText(query: String, vararg fields: String?): Boolean {
        val needles = PhoneNumbers.spellingVariants(query).map { it.lowercase() }
        if (needles.isEmpty()) return false
        return fields.any { field ->
            if (field.isNullOrBlank()) {
                false
            } else {
                val haystacks = PhoneNumbers.spellingVariants(field).map { it.lowercase() }
                needles.any { needle -> haystacks.any { it.contains(needle) } }
            }
        }
    }

    /** True when [query] reads as part of [address], whichever way each is written. */
    fun matchesNumber(query: String, address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val typed = PhoneNumbers.digitVariants(query)
        if (typed.isEmpty()) return false
        // Only a number is matched as a number: "1" typed while hunting for a name should not
        // return every contact whose number contains a one.
        if (PhoneNumbers.toAsciiDigits(query).any { it.isLetter() }) return false
        val stored = PhoneNumbers.toAsciiDigits(address).filter(Char::isDigit)
        if (stored.isEmpty()) return false
        return typed.any { stored.contains(it) }
    }

    /** Either kind of match, for one row that carries both a name and a number. */
    fun matches(query: String, name: String?, address: String?): Boolean =
        matchesText(query, name, address) || matchesNumber(query, address)
}
