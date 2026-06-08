package com.kirkouski.gtwake.companion.domain

import java.time.DayOfWeek
import java.util.Calendar

object DaysOfWeek {
    const val SUN = 1
    const val MON = 2
    const val TUE = 4
    const val WED = 8
    const val THU = 16
    const val FRI = 32
    const val SAT = 64
    const val NONE = 0
    const val ALL = 127
    const val WEEKDAYS = MON or TUE or WED or THU or FRI
    const val WEEKENDS = SAT or SUN

    val ORDERED: List<Int> = listOf(SUN, MON, TUE, WED, THU, FRI, SAT)

    fun contains(mask: Int, day: Int): Boolean = (mask and day) == day

    fun toggle(mask: Int, day: Int): Int = mask xor day

    fun fromJavaDayOfWeek(dow: DayOfWeek): Int = when (dow) {
        DayOfWeek.SUNDAY -> SUN
        DayOfWeek.MONDAY -> MON
        DayOfWeek.TUESDAY -> TUE
        DayOfWeek.WEDNESDAY -> WED
        DayOfWeek.THURSDAY -> THU
        DayOfWeek.FRIDAY -> FRI
        DayOfWeek.SATURDAY -> SAT
    }

    // Calendar.SUNDAY=1..Calendar.SATURDAY=7 → our bitmask
    fun fromCalendarDay(calendarDay: Int): Int = when (calendarDay) {
        Calendar.SUNDAY -> SUN
        Calendar.MONDAY -> MON
        Calendar.TUESDAY -> TUE
        Calendar.WEDNESDAY -> WED
        Calendar.THURSDAY -> THU
        Calendar.FRIDAY -> FRI
        Calendar.SATURDAY -> SAT
        else -> SUN
    }

    // Rotates ORDERED so the locale's first-day-of-week comes first.
    // Locale en_US wants Sun-first; DE/PL/RU/UA/BY/CN want Mon-first.
    fun rotated(firstDayBit: Int): List<Int> {
        val idx = ORDERED.indexOf(firstDayBit)
        if (idx <= 0) return ORDERED
        return ORDERED.drop(idx) + ORDERED.take(idx)
    }
}
