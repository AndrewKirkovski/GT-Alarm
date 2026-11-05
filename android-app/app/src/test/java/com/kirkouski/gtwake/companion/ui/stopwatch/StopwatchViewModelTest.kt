package com.kirkouski.gtwake.companion.ui.stopwatch

import com.kirkouski.gtwake.companion.stopwatch.Stopwatch
import com.kirkouski.gtwake.companion.stopwatch.StopwatchRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StopwatchViewModelTest {

    @Before
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Stop and Lap right after Start are ignored`() {
        val repository = mockk<StopwatchRepository>(relaxed = true)
        every { repository.state } returns flowOf(Stopwatch())
        val vm = StopwatchViewModel(repository)

        vm.start()
        vm.pause()
        vm.lap()

        coVerify(exactly = 1) { repository.update(any()) }
    }

    @Test
    fun `Stop works with no Start in this screen`() {
        val repository = mockk<StopwatchRepository>(relaxed = true)
        every { repository.state } returns flowOf(Stopwatch())

        StopwatchViewModel(repository).pause()

        coVerify(exactly = 1) { repository.update(any()) }
    }

    @Test
    fun `a tap is allowed once the guard has passed`() {
        assertFalse(stopwatchTapAllowed(lastStartMs = 1_000L, nowMs = 1_399L))
        assertTrue(stopwatchTapAllowed(lastStartMs = 1_000L, nowMs = 1_400L))
    }

    @Test
    fun `Undo writes back the state from before the reset`() {
        val repository = mockk<StopwatchRepository>(relaxed = true)
        every { repository.state } returns flowOf(Stopwatch())
        val previous = Stopwatch()

        StopwatchViewModel(repository).restore(previous)

        coVerify(exactly = 1) { repository.restore(previous) }
    }
}
