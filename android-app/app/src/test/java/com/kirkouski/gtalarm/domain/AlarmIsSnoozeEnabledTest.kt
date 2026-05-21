package com.kirkouski.gtalarm.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for [Alarm.isSnoozeEnabled]. This boolean collapses two
 * independent disablers — `snoozeMinutes == SNOOZE_DISABLED` and
 * `consecutiveSnoozeCount >= maxSnoozeCount` — into one signal the
 * watch consumes verbatim via `AlarmRingHints.snoozeAllowed`. Drift
 * here = wrong Snooze button visibility on the wrist.
 */
class AlarmIsSnoozeEnabledTest {

    private fun alarm(
        snoozeMinutes: Int,
        maxSnoozeCount: Int,
        consecutiveSnoozeCount: Int,
    ): Alarm = Alarm(
        id = 1L,
        label = "",
        hour = 0,
        minute = 0,
        daysOfWeek = DaysOfWeek.WEEKDAYS,
        enabled = true,
        snoozeMinutes = snoozeMinutes,
        maxSnoozeCount = maxSnoozeCount,
        consecutiveSnoozeCount = consecutiveSnoozeCount,
        updatedAtEpoch = 1L,
    )

    @Test fun `unlimited cap + nonzero minutes is enabled`() {
        assertTrue(alarm(snoozeMinutes = 5, maxSnoozeCount = 0, consecutiveSnoozeCount = 99).isSnoozeEnabled)
    }

    @Test fun `zero minutes is disabled regardless of cap`() {
        assertFalse(alarm(snoozeMinutes = 0, maxSnoozeCount = 0, consecutiveSnoozeCount = 0).isSnoozeEnabled)
        assertFalse(alarm(snoozeMinutes = 0, maxSnoozeCount = 5, consecutiveSnoozeCount = 0).isSnoozeEnabled)
    }

    @Test fun `count below cap is enabled`() {
        assertTrue(alarm(snoozeMinutes = 5, maxSnoozeCount = 3, consecutiveSnoozeCount = 2).isSnoozeEnabled)
    }

    @Test fun `count equal to cap is disabled`() {
        assertFalse(alarm(snoozeMinutes = 5, maxSnoozeCount = 3, consecutiveSnoozeCount = 3).isSnoozeEnabled)
    }

    @Test fun `count over cap is disabled`() {
        assertFalse(alarm(snoozeMinutes = 5, maxSnoozeCount = 3, consecutiveSnoozeCount = 4).isSnoozeEnabled)
    }
}
