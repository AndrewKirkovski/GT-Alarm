package com.kirkouski.gtwake.companion.wear

import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import com.kirkouski.gtwake.companion.domain.VibrationPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the thin-client `alarm_fired` envelope hint pair. The watch
 * relies on these fields to render the right pattern + Snooze gating
 * WITHOUT a prior `sync_replace` — if the field collapse below is wrong,
 * a cold-paired watch shows the wrong affordance.
 */
class AlarmRingHintsTest {

    private fun alarm(
        snoozeMinutes: Int = Alarm.DEFAULT_SNOOZE_MINUTES,
        maxSnoozeCount: Int = Alarm.MAX_SNOOZE_COUNT_UNLIMITED,
        consecutiveSnoozeCount: Int = 0,
        vibrationPattern: VibrationPattern = VibrationPattern.PULSE,
    ): Alarm = Alarm(
        id = 1L,
        label = "x",
        hour = 7,
        minute = 0,
        daysOfWeek = DaysOfWeek.WEEKDAYS,
        enabled = true,
        snoozeMinutes = snoozeMinutes,
        maxSnoozeCount = maxSnoozeCount,
        consecutiveSnoozeCount = consecutiveSnoozeCount,
        vibrationPattern = vibrationPattern,
        updatedAtEpoch = 1L,
    )

    @Test
    fun `pattern flows through as enum name`() {
        listOf(
            VibrationPattern.PULSE,
            VibrationPattern.HEARTBEAT,
            VibrationPattern.THREE_TAP,
            VibrationPattern.LONG_LONG,
            VibrationPattern.OFF,
        ).forEach { p ->
            val hints = AlarmRingHints.from(alarm(vibrationPattern = p))
            assertEquals(p.name, hints.vibrationPatternName)
        }
    }

    @Test
    fun `snoozeAllowed true when snoozeMinutes greater than 0 and cap not reached`() {
        val hints = AlarmRingHints.from(alarm(snoozeMinutes = 5, maxSnoozeCount = 3, consecutiveSnoozeCount = 1))
        assertTrue(hints.snoozeAllowed)
    }

    @Test
    fun `snoozeAllowed false when snoozeMinutes is 0`() {
        val hints = AlarmRingHints.from(alarm(snoozeMinutes = Alarm.SNOOZE_DISABLED))
        assertFalse(hints.snoozeAllowed)
    }

    @Test
    fun `snoozeAllowed false when counter has reached the cap`() {
        val hints = AlarmRingHints.from(alarm(snoozeMinutes = 5, maxSnoozeCount = 3, consecutiveSnoozeCount = 3))
        assertFalse(hints.snoozeAllowed)
    }

    @Test
    fun `snoozeAllowed true when cap is unlimited (0)`() {
        // Even with 999 consecutive snoozes, unlimited cap stays allowed.
        val hints = AlarmRingHints.from(
            alarm(
                snoozeMinutes = 5,
                maxSnoozeCount = Alarm.MAX_SNOOZE_COUNT_UNLIMITED,
                consecutiveSnoozeCount = 999,
            ),
        )
        assertTrue(hints.snoozeAllowed)
    }

    @Test
    fun `snoozeAllowed false stacks both disablers (cap=0 AND minutes=0)`() {
        val hints = AlarmRingHints.from(
            alarm(
                snoozeMinutes = Alarm.SNOOZE_DISABLED,
                maxSnoozeCount = 1,
                consecutiveSnoozeCount = 0,
            ),
        )
        // snoozeMinutes==0 alone is enough.
        assertFalse(hints.snoozeAllowed)
    }
}
