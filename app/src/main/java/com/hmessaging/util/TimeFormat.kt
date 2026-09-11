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

    private val timeOnly: DateTimeFormatter
        get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

    private val dateOnly: DateTimeFormatter
        get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

    private val dateAndTime: DateTimeFormatter
        get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())

    fun toLocal(epochMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    fun toEpochMillis(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    /** Time for today, weekday-free short date otherwise — the usual messenger list format. */
    fun listStamp(epochMillis: Long): String {
        val local = toLocal(epochMillis)
        val today = LocalDate.now(zone)
        return when {
            local.toLocalDate() == today -> timeOnly.format(local)
            local.toLocalDate().isAfter(today.minusDays(DAYS_IN_WEEK)) ->
                local.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            else -> dateOnly.format(local)
        }
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
}
