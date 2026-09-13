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
     * Whether dates are written in the Solar Hijri calendar.
     *
     * A plain flag rather than something read from preferences at each call site, because dates are
     * formatted from list rows, notifications and background workers alike — most of which have no
     * business awaiting a preference. The Application sets it at startup and whenever it changes.
     */
    @Volatile
    var persianCalendar: Boolean = false

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
                if (persianCalendar) {
                    PersianCalendar.weekdayName(local.toLocalDate())
                } else {
                    local.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                }

            persianCalendar -> persianNumeric(local.toLocalDate())
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

    fun full(epochMillis: Long): String {
        val local = toLocal(epochMillis)
        if (!persianCalendar) return dateAndTime.format(local)
        return persianDate(local.toLocalDate()) + "، " + timeOnly.format(local)
    }

    fun dayHeader(epochMillis: Long): String {
        val local = toLocal(epochMillis)
        if (!persianCalendar) return dateOnly.format(local)
        return PersianCalendar.weekdayName(local.toLocalDate()) + " " + persianDate(local.toLocalDate())
    }

    /** `۲۲ شهریور ۱۴۰۵` — the form a date is spoken in, not a slash-separated one. */
    fun persianDate(date: LocalDate): String {
        val persian = PersianCalendar.toPersian(date)
        return "${persian.day} ${persian.monthName} ${persian.year}"
    }

    /** `۱۴۰۵/۰۶/۲۲`, for the places a date has to line up in a column. */
    fun persianNumeric(date: LocalDate): String {
        val persian = PersianCalendar.toPersian(date)
        return String.format(Locale.getDefault(), "%04d/%02d/%02d", persian.year, persian.month, persian.day)
    }

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
