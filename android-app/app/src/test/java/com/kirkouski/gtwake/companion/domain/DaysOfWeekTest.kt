package com.kirkouski.gtwake.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.util.Calendar

class DaysOfWeekTest {

    @Test
    fun `bit constants are powers of two and unique`() {
        val bits = listOf(
            DaysOfWeek.SUN, DaysOfWeek.MON, DaysOfWeek.TUE, DaysOfWeek.WED,
            DaysOfWeek.THU, DaysOfWeek.FRI, DaysOfWeek.SAT,
        )
        // Each is a single bit.
        bits.forEach { assertEquals(1, Integer.bitCount(it)) }
        // All distinct.
        assertEquals(7, bits.toSet().size)
    }

    @Test
    fun `WEEKDAYS includes Mon through Fri`() {
        assertTrue(DaysOfWeek.contains(DaysOfWeek.WEEKDAYS, DaysOfWeek.MON))
        assertTrue(DaysOfWeek.contains(DaysOfWeek.WEEKDAYS, DaysOfWeek.TUE))
        assertTrue(DaysOfWeek.contains(DaysOfWeek.WEEKDAYS, DaysOfWeek.WED))
        assertTrue(DaysOfWeek.contains(DaysOfWeek.WEEKDAYS, DaysOfWeek.THU))
        assertTrue(DaysOfWeek.contains(DaysOfWeek.WEEKDAYS, DaysOfWeek.FRI))
        assertFalse(DaysOfWeek.contains(DaysOfWeek.WEEKDAYS, DaysOfWeek.SAT))
        assertFalse(DaysOfWeek.contains(DaysOfWeek.WEEKDAYS, DaysOfWeek.SUN))
    }

    @Test
    fun `WEEKENDS includes Sat and Sun`() {
        assertTrue(DaysOfWeek.contains(DaysOfWeek.WEEKENDS, DaysOfWeek.SAT))
        assertTrue(DaysOfWeek.contains(DaysOfWeek.WEEKENDS, DaysOfWeek.SUN))
        assertFalse(DaysOfWeek.contains(DaysOfWeek.WEEKENDS, DaysOfWeek.MON))
        assertFalse(DaysOfWeek.contains(DaysOfWeek.WEEKENDS, DaysOfWeek.FRI))
    }

    @Test
    fun `ALL contains every weekday bit`() {
        for (bit in DaysOfWeek.ORDERED) {
            assertTrue("ALL should contain bit=$bit", DaysOfWeek.contains(DaysOfWeek.ALL, bit))
        }
    }

    @Test
    fun `NONE contains no weekday bit`() {
        for (bit in DaysOfWeek.ORDERED) {
            assertFalse(DaysOfWeek.contains(DaysOfWeek.NONE, bit))
        }
    }

    @Test
    fun `toggle flips a single bit and preserves others`() {
        val start = DaysOfWeek.MON or DaysOfWeek.WED
        val after = DaysOfWeek.toggle(start, DaysOfWeek.WED)
        assertTrue(DaysOfWeek.contains(after, DaysOfWeek.MON))
        assertFalse(DaysOfWeek.contains(after, DaysOfWeek.WED))
    }

    @Test
    fun `toggle is its own inverse`() {
        val start = DaysOfWeek.MON or DaysOfWeek.FRI
        val once = DaysOfWeek.toggle(start, DaysOfWeek.MON)
        val twice = DaysOfWeek.toggle(once, DaysOfWeek.MON)
        assertEquals(start, twice)
    }

    @Test
    fun `fromJavaDayOfWeek covers every DayOfWeek enum value`() {
        assertEquals(DaysOfWeek.MON, DaysOfWeek.fromJavaDayOfWeek(DayOfWeek.MONDAY))
        assertEquals(DaysOfWeek.TUE, DaysOfWeek.fromJavaDayOfWeek(DayOfWeek.TUESDAY))
        assertEquals(DaysOfWeek.WED, DaysOfWeek.fromJavaDayOfWeek(DayOfWeek.WEDNESDAY))
        assertEquals(DaysOfWeek.THU, DaysOfWeek.fromJavaDayOfWeek(DayOfWeek.THURSDAY))
        assertEquals(DaysOfWeek.FRI, DaysOfWeek.fromJavaDayOfWeek(DayOfWeek.FRIDAY))
        assertEquals(DaysOfWeek.SAT, DaysOfWeek.fromJavaDayOfWeek(DayOfWeek.SATURDAY))
        assertEquals(DaysOfWeek.SUN, DaysOfWeek.fromJavaDayOfWeek(DayOfWeek.SUNDAY))
    }

    @Test
    fun `ORDERED has exactly seven entries in Sun-first order`() {
        assertEquals(7, DaysOfWeek.ORDERED.size)
        assertEquals(DaysOfWeek.SUN, DaysOfWeek.ORDERED.first())
        assertEquals(DaysOfWeek.SAT, DaysOfWeek.ORDERED.last())
    }

    @Test
    fun `fromCalendarDay maps Calendar constants to bitmask`() {
        assertEquals(DaysOfWeek.SUN, DaysOfWeek.fromCalendarDay(Calendar.SUNDAY))
        assertEquals(DaysOfWeek.MON, DaysOfWeek.fromCalendarDay(Calendar.MONDAY))
        assertEquals(DaysOfWeek.TUE, DaysOfWeek.fromCalendarDay(Calendar.TUESDAY))
        assertEquals(DaysOfWeek.WED, DaysOfWeek.fromCalendarDay(Calendar.WEDNESDAY))
        assertEquals(DaysOfWeek.THU, DaysOfWeek.fromCalendarDay(Calendar.THURSDAY))
        assertEquals(DaysOfWeek.FRI, DaysOfWeek.fromCalendarDay(Calendar.FRIDAY))
        assertEquals(DaysOfWeek.SAT, DaysOfWeek.fromCalendarDay(Calendar.SATURDAY))
    }

    @Test
    fun `rotated with SUN returns canonical Sun-first list`() {
        assertEquals(DaysOfWeek.ORDERED, DaysOfWeek.rotated(DaysOfWeek.SUN))
    }

    @Test
    fun `rotated with MON puts Mon first and Sun last`() {
        val mon = DaysOfWeek.rotated(DaysOfWeek.MON)
        assertEquals(7, mon.size)
        assertEquals(DaysOfWeek.MON, mon.first())
        assertEquals(DaysOfWeek.SUN, mon.last())
        assertEquals(
            listOf(DaysOfWeek.MON, DaysOfWeek.TUE, DaysOfWeek.WED, DaysOfWeek.THU,
                DaysOfWeek.FRI, DaysOfWeek.SAT, DaysOfWeek.SUN),
            mon,
        )
    }

    @Test
    fun `rotated with SAT puts Sat first and Fri last`() {
        val sat = DaysOfWeek.rotated(DaysOfWeek.SAT)
        assertEquals(DaysOfWeek.SAT, sat.first())
        assertEquals(DaysOfWeek.FRI, sat.last())
    }

    @Test
    fun `rotated with unknown bit falls back to canonical order`() {
        assertEquals(DaysOfWeek.ORDERED, DaysOfWeek.rotated(0))
        assertEquals(DaysOfWeek.ORDERED, DaysOfWeek.rotated(999))
    }
}
