package com.werd.app.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Days {
    /** Logical "today": the day starts at [dayStartHour] (e.g. 3 = a werd read at 1am counts for the previous day). */
    fun today(dayStartHour: Int): Long =
        LocalDateTime.now().minusHours(dayStartHour.toLong()).toLocalDate().toEpochDay()

    fun dayOfWeekBit(epochDay: Long): Int = LocalDate.ofEpochDay(epochDay).dayOfWeek.value - 1

    fun isInMask(mask: Int, epochDay: Long): Boolean = mask and (1 shl dayOfWeekBit(epochDay)) != 0

    fun maskHas(mask: Int, dow: DayOfWeek): Boolean = mask and (1 shl (dow.value - 1)) != 0

    /** Weekdays in display order for the locale (Saturday first for Arabic, Monday otherwise). */
    fun weekOrder(locale: Locale): List<DayOfWeek> {
        val first = if (locale.language == "ar") DayOfWeek.SATURDAY else DayOfWeek.MONDAY
        return (0 until 7).map { first.plus(it.toLong()) }
    }

    fun hijri(locale: Locale, date: LocalDate = LocalDate.now()): String {
        val h = HijrahDate.from(date)
        val f = DateTimeFormatter.ofPattern("d MMMM y", locale).withChronology(HijrahChronology.INSTANCE)
        val s = f.format(h)
        return if (locale.language == "ar") "$s هـ" else "$s AH"
    }

    fun gregorian(locale: Locale, date: LocalDate = LocalDate.now()): String =
        DateTimeFormatter.ofPattern("EEEE، d MMMM", locale).let {
            if (locale.language == "ar") it.format(date)
            else DateTimeFormatter.ofPattern("EEEE, d MMMM", locale).format(date)
        }

    fun shortDate(locale: Locale, date: LocalDate): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
}
