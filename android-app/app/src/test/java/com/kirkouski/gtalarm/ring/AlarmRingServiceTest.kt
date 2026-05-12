package com.kirkouski.gtalarm.ring

import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import com.kirkouski.gtalarm.ring.AlarmRingService.Companion.dismissAction
import com.kirkouski.gtalarm.ring.AlarmRingService.DismissAction
import org.junit.Assert.assertEquals
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
    fun `legacy one-shot (selfDestruct=false) gets DISABLE on dismiss`() {
        assertEquals(DismissAction.DISABLE, dismissAction(baseAlarm))
    }

    @Test
    fun `one-shot with selfDestruct=true gets DELETE on dismiss`() {
        val sd = baseAlarm.copy(selfDestruct = true)
        assertEquals(DismissAction.DELETE, dismissAction(sd))
    }

    @Test
    fun `relative alarm (always one-shot, default selfDestruct) gets DELETE`() {
        val relative = baseAlarm.copy(
            daysOfWeek = DaysOfWeek.NONE,
            relativeMinutes = 15,
            selfDestruct = true,
        )
        assertEquals(DismissAction.DELETE, dismissAction(relative))
    }

    @Test
    fun `relative alarm with selfDestruct=false (user opt-out) gets DISABLE`() {
        val relative = baseAlarm.copy(
            daysOfWeek = DaysOfWeek.NONE,
            relativeMinutes = 15,
            selfDestruct = false,
        )
        assertEquals(DismissAction.DISABLE, dismissAction(relative))
    }

    @Test
    fun `repeating alarm (Mon-Fri) stays armed — KEEP`() {
        val repeating = baseAlarm.copy(daysOfWeek = DaysOfWeek.WEEKDAYS)
        assertEquals(DismissAction.KEEP, dismissAction(repeating))
    }

    @Test
    fun `repeating alarm (single day) stays armed — KEEP`() {
        val repeating = baseAlarm.copy(daysOfWeek = DaysOfWeek.MON)
        assertEquals(DismissAction.KEEP, dismissAction(repeating))
    }

    @Test
    fun `disabled alarm is a no-op — KEEP`() {
        val disabled = baseAlarm.copy(enabled = false)
        assertEquals(DismissAction.KEEP, dismissAction(disabled))
    }

    @Test
    fun `null alarm (deleted before dismiss) is a no-op — KEEP`() {
        assertEquals(DismissAction.KEEP, dismissAction(null))
    }

    @Test
    fun `every-day repeating alarm stays armed — KEEP`() {
        val everyDay = baseAlarm.copy(daysOfWeek = DaysOfWeek.ALL)
        assertEquals(DismissAction.KEEP, dismissAction(everyDay))
    }
}
