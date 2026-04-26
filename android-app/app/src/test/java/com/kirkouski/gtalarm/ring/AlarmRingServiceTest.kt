package com.kirkouski.gtalarm.ring

import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import com.kirkouski.gtalarm.ring.AlarmRingService.Companion.shouldAutoDisableOnDismiss
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRingServiceTest {

    private val baseAlarm = Alarm(
        id = 1L,
        label = "Wake",
        hour = 7,
        minute = 0,
        daysOfWeek = DaysOfWeek.NONE,
        enabled = true,
        updatedAtEpoch = 1_700_000_000_000L,
    )

    @Test
    fun `dismiss flips enabled=false for one-shot enabled alarm`() {
        assertTrue(shouldAutoDisableOnDismiss(baseAlarm))
    }

    @Test
    fun `dismiss leaves repeating alarm armed (Mon-Fri)`() {
        val repeating = baseAlarm.copy(daysOfWeek = DaysOfWeek.WEEKDAYS)
        assertFalse(shouldAutoDisableOnDismiss(repeating))
    }

    @Test
    fun `dismiss leaves repeating alarm armed (single day)`() {
        val repeating = baseAlarm.copy(daysOfWeek = DaysOfWeek.MON)
        assertFalse(shouldAutoDisableOnDismiss(repeating))
    }

    @Test
    fun `dismiss is a no-op when alarm is already disabled`() {
        val disabled = baseAlarm.copy(enabled = false)
        assertFalse(shouldAutoDisableOnDismiss(disabled))
    }

    @Test
    fun `dismiss is a no-op when alarm is gone (deleted before dismiss)`() {
        assertFalse(shouldAutoDisableOnDismiss(null))
    }

    @Test
    fun `dismiss handles edge case ALL daysOfWeek (still repeating)`() {
        val everyDay = baseAlarm.copy(daysOfWeek = DaysOfWeek.ALL)
        assertFalse(shouldAutoDisableOnDismiss(everyDay))
    }
}
