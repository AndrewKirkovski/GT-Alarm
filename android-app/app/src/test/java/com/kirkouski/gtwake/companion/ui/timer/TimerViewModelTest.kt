package com.kirkouski.gtwake.companion.ui.timer

import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.SettingsState
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.data.SnoozeDefaults
import com.kirkouski.gtwake.companion.domain.Alarm
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModelTest {

    @Before
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start uses the timer snooze defaults`() {
        val repository = mockk<AlarmRepository>(relaxed = true)
        val settings = mockk<SettingsStore>(relaxed = true)
        every { repository.observeAlarms() } returns flowOf(emptyList())
        every { settings.state } returns flowOf(SettingsState())
        coEvery { settings.newTimerSnooze() } returns SnoozeDefaults(minutes = 5, maxCount = 3)

        TimerViewModel(repository, settings).start(15)

        coVerify(exactly = 1) {
            repository.save(
                match { it.relativeMinutes == 15 && it.snoozeMinutes == 5 && it.maxSnoozeCount == 3 },
            )
        }
    }

    @Test
    fun `the first start is allowed`() {
        assertTrue(timerStartAllowed(lastStartMs = null, nowMs = 100L))
    }

    @Test
    fun `a double tap is ignored`() {
        assertFalse(timerStartAllowed(lastStartMs = 1_000L, nowMs = 1_250L))
    }

    @Test
    fun `a start after the gate is allowed`() {
        assertTrue(timerStartAllowed(lastStartMs = 1_000L, nowMs = 1_900L))
    }

    @Test
    fun `countdown shows minutes and seconds`() {
        assertEquals("09:42", formatCountdown(582_000L))
    }

    @Test
    fun `countdown rounds up so a new timer shows its full length`() {
        assertEquals("10:00", formatCountdown(599_400L))
    }

    @Test
    fun `countdown shows hours from one hour`() {
        assertEquals("1:09:42", formatCountdown(4_182_000L))
    }

    @Test
    fun `countdown stops at zero`() {
        assertEquals("00:00", formatCountdown(-5_000L))
    }

    @Test
    fun `a new timer deletes itself after ringing and takes the given snooze`() {
        val timer = Alarm.newTimer(minutes = 10, snoozeMinutes = 0, maxSnoozeCount = 0)

        assertEquals(10, timer.relativeMinutes)
        assertTrue(timer.selfDestruct)
        assertEquals(Alarm.SNOOZE_DISABLED, timer.snoozeMinutes)
        assertTrue(timer.enabled)
    }
}
