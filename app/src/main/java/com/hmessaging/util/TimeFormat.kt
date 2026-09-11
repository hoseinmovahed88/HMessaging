package com.hmessaging.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object TimeFormat {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * Localized formatters are expensive to build — each one resolves a locale and parses a
     * pattern — and every row of every list asks for one. They are built once per locale and
     * rebuilt only if the device locale changes under us.
     */
    private class Formatters(val locale: Locale) {
        val timeOnly: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val dateOnly: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val dateAndTime: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale)
    }

    @Volatile
    private var cached: Formatters = Formatters(Locale.getDefault())

    private val formatters: Formatters
        get() {
            val current = cached
            val locale = Locale.getDefault()
            if (current.locale == locale) return current
            return Formatters(locale).also { cached = it }
        }

    private val timeOnly: DateTimeFormatter get() = formatters.timeOnly
    private val dateOnly: DateTimeFormatter get() = formatters.dateOnly
    private val dateAndTime: DateTimeFormatter get() = formatters.dateAndTime

    fun toLocal(epochMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    fun toEpochMillis(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    /** Time for today, weekday-free short date otherwise — the usual messenger list format. */
    fun listStamp(epochMillis: Long): String {
        val local = toLocal(epochMillis)
        val today = today()
        return when {
            local.toLocalDate() == today -> timeOnly.format(local)
            local.toLocalDate().isAfter(today.minusDays(DAYS_IN_WEEK)) ->
                local.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            else -> dateOnly.format(local)
        }
    }

    /** Today's date, recomputed at most once a minute rather than once per rendered row. */
    private var todayValue: LocalDate = LocalDate.now(zone)
    private var todayComputedAt: Long = System.currentTimeMillis()

    @Synchronized
    private fun today(): LocalDate {
        val now = System.currentTimeMillis()
        if (now - todayComputedAt > TODAY_TTL_MS) {
            todayValue = LocalDate.now(zone)
            todayComputedAt = now
        }
        return todayValue
    }

    fun clock(epochMillis: Long): String = timeOnly.format(toLocal(epochMillis))

    fun full(epochMillis: Long): String = dateAndTime.format(toLocal(epochMillis))

    fun dayHeader(epochMillis: Long): String = dateOnly.format(toLocal(epochMillis))

    fun minuteOfDay(epochMillis: Long): Int {
        val local = toLocal(epochMillis)
        return local.hour * MINUTES_PER_HOUR + local.minute
    }

    fun formatMinuteOfDay(minuteOfDay: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)

    /** ISO Monday = 1 … Sunday = 7, mapped to the 0-based bit index used by rule day masks. */
    fun dayBitIndex(epochMillis: Long): Int = toLocal(epochMillis).dayOfWeek.value - 1

    fun startOfDay(epochMillis: Long): Long =
        toLocal(epochMillis).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    private const val MINUTES_PER_HOUR = 60
    private const val DAYS_IN_WEEK = 7L
    private const val TODAY_TTL_MS = 60_000L
}
