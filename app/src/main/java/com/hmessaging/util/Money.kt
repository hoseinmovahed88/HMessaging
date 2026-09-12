package com.hmessaging.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Formats the sums read out of bank messages.
 *
 * Grouped with the locale's own separator and its own digits, so a Persian phone sees ۱٬۲۳۴٬۵۶۷ and
 * an English one 1,234,567 — the same number as the bank wrote it, rather than a reformatting the
 * reader has to translate back before they can compare it against the SMS.
 */
object Money {

    @Volatile
    private var cached: Pair<Locale, DecimalFormat>? = null

    fun format(amount: Long): String = formatter().format(amount)

    /** What to call a currency in the UI. Unknown codes are shown as they came. */
    fun currencyName(code: String): String = when (code.uppercase()) {
        "IRR" -> "ریال"
        "IRT" -> "تومان"
        else -> code
    }

    private fun formatter(): DecimalFormat {
        val locale = Locale.getDefault()
        cached?.let { (cachedLocale, format) -> if (cachedLocale == locale) return format }
        val format = DecimalFormat("#,##0", DecimalFormatSymbols(locale))
        cached = locale to format
        return format
    }
}
