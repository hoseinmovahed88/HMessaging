package com.hmessaging.util

import java.time.LocalDate

/** A date in the Solar Hijri (Jalali) calendar. */
data class PersianDate(val year: Int, val month: Int, val day: Int) {
    /** 1 = فروردین … 12 = اسفند. */
    val monthName: String get() = PersianCalendar.MONTH_NAMES[month - 1]
}

/**
 * Converts between the Gregorian calendar the system keeps time in and the Solar Hijri calendar
 * this app's readers actually use.
 *
 * Done as arithmetic rather than through a calendar library because nothing dependable ships with
 * one: `java.time` has no Persian chronology, and the ICU calendar that does is a platform class
 * that cannot be checked without a device. This is a handful of integer operations, which means it
 * can be tested against known dates on any machine — and it is, because a date conversion that is
 * quietly a day out is worse than no conversion at all.
 *
 * The algorithm is the standard one: count days from a fixed epoch, then walk the 33-year leap
 * cycle. Solar Hijri leap years are not a simple modulus — the cycle is 33 years with leap years at
 * known offsets — so the remainder is compared against that pattern rather than divided.
 */
object PersianCalendar {

    val MONTH_NAMES = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    /** Persian weekday names, indexed by [java.time.DayOfWeek.getValue] minus one (Monday = 0). */
    private val WEEKDAYS_FROM_MONDAY = listOf(
        "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه", "شنبه", "یکشنبه",
    )

    fun weekdayName(date: LocalDate): String = WEEKDAYS_FROM_MONDAY[date.dayOfWeek.value - 1]

    fun toPersian(date: LocalDate): PersianDate {
        val jdn = gregorianToJdn(date.year, date.monthValue, date.dayOfMonth)
        return jdnToPersian(jdn)
    }

    fun toGregorian(date: PersianDate): LocalDate {
        val jdn = persianToJdn(date.year, date.month, date.day)
        val (y, m, d) = jdnToGregorian(jdn)
        return LocalDate.of(y, m, d)
    }

    /** Whether a Solar Hijri year has 366 days, by its position in the 33-year cycle. */
    fun isLeapYear(year: Int): Boolean {
        val remainder = ((year - LEAP_CYCLE_ANCHOR) % LEAP_CYCLE + LEAP_CYCLE) % LEAP_CYCLE
        return remainder in LEAP_REMAINDERS
    }

    fun daysInMonth(year: Int, month: Int): Int = when {
        month <= FIRST_HALF_MONTHS -> LONG_MONTH_DAYS
        month < MONTHS_IN_YEAR -> SHORT_MONTH_DAYS
        isLeapYear(year) -> SHORT_MONTH_DAYS
        else -> LAST_MONTH_COMMON_DAYS
    }

    // ---- Julian day number, the common ground between the two calendars --------------------

    private fun gregorianToJdn(year: Int, month: Int, day: Int): Long {
        val a = ((MONTHS_IN_YEAR + 2 - month) / MONTHS_IN_YEAR).toLong()
        val y = year + YEAR_OFFSET - a
        val m = month + MONTHS_IN_YEAR * a - 3
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - GREGORIAN_EPOCH_SHIFT
    }

    private fun jdnToGregorian(jdn: Long): Triple<Int, Int, Int> {
        var j = jdn + GREGORIAN_INVERSE_SHIFT
        val b = (4 * j + 3) / 146097
        j -= 146097 * b / 4
        val c = (4 * j + 3) / 1461
        j -= 1461 * c / 4
        val m = (5 * j + 2) / 153
        val day = (j - (153 * m + 2) / 5 + 1).toInt()
        val month = (m + 3 - MONTHS_IN_YEAR * (m / 10)).toInt()
        val year = (100 * b + c - YEAR_OFFSET + m / 10).toInt()
        return Triple(year, month, day)
    }

    private fun persianToJdn(year: Int, month: Int, day: Int): Long {
        val epochBase = year - 1
        val cycles = Math.floorDiv(epochBase, LEAP_CYCLE.toLong().toInt()).toLong()
        val inCycle = epochBase - cycles * LEAP_CYCLE
        var days = cycles * DAYS_PER_LEAP_CYCLE
        for (y in 0 until inCycle) {
            days += if (isLeapYear((y + 1 + cycles * LEAP_CYCLE).toInt())) 366 else 365
        }
        for (m in 1 until month) days += daysInMonth(year, m)
        return PERSIAN_EPOCH_JDN + days + day - 1
    }

    private fun jdnToPersian(jdn: Long): PersianDate {
        var remaining = jdn - PERSIAN_EPOCH_JDN
        // Jump whole cycles first, so a date two thousand years out is still a few dozen steps.
        var year = 1 + (Math.floorDiv(remaining, DAYS_PER_LEAP_CYCLE) * LEAP_CYCLE).toInt()
        remaining -= Math.floorDiv(remaining, DAYS_PER_LEAP_CYCLE) * DAYS_PER_LEAP_CYCLE

        while (true) {
            val yearLength = if (isLeapYear(year)) 366 else 365
            if (remaining < yearLength) break
            remaining -= yearLength
            year++
        }

        var month = 1
        while (month <= MONTHS_IN_YEAR) {
            val monthLength = daysInMonth(year, month)
            if (remaining < monthLength) break
            remaining -= monthLength
            month++
        }
        return PersianDate(year, month, (remaining + 1).toInt())
    }

    /** 1 Farvardin 1 is Julian day 1948320. */
    private const val PERSIAN_EPOCH_JDN = 1_948_320L
    private const val GREGORIAN_EPOCH_SHIFT = 32045L

    /**
     * One less than the forward shift, and not a typo.
     *
     * The two directions of this conversion are built around different anchors — the forward one
     * subtracts 32045, its inverse adds 32044 — and using the forward constant in both put every
     * converted date exactly one day ahead.
     */
    private const val GREGORIAN_INVERSE_SHIFT = 32044L
    private const val YEAR_OFFSET = 4800

    private const val LEAP_CYCLE = 33
    private const val DAYS_PER_LEAP_CYCLE = 12053L
    private const val LEAP_CYCLE_ANCHOR = 474

    /** Positions within the 33-year cycle that carry the extra day. */
    private val LEAP_REMAINDERS = setOf(1, 5, 9, 13, 17, 22, 26, 30)

    private const val MONTHS_IN_YEAR = 12
    private const val FIRST_HALF_MONTHS = 6
    private const val LONG_MONTH_DAYS = 31
    private const val SHORT_MONTH_DAYS = 30
    private const val LAST_MONTH_COMMON_DAYS = 29
}
