package com.kirkouski.gtwake.companion.stopwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class StopwatchTest {

    private val clock = StopwatchClock(elapsedMs = 1_000L, wallMs = 50_000L, bootCount = 7)

    private fun later(ms: Long) = clock.copy(elapsedMs = clock.elapsedMs + ms, wallMs = clock.wallMs + ms)

    @Test
    fun `running time counts elapsed time on the same boot`() {
        val running = Stopwatch().start(clock)

        assertEquals(5_000L, running.totalAt(later(5_000L)))
    }

    @Test
    fun `a clock change does not move the time on the same boot`() {
        val running = Stopwatch().start(clock)
        val changed = later(5_000L).copy(wallMs = 1L)

        assertEquals(5_000L, running.totalAt(changed))
    }

    @Test
    fun `after a reboot it counts wall time`() {
        val running = Stopwatch().start(clock)
        val rebooted = StopwatchClock(elapsedMs = 300L, wallMs = clock.wallMs + 60_000L, bootCount = 8)

        assertEquals(60_000L, running.totalAt(rebooted))
    }

    @Test
    fun `a negative result is clamped to zero`() {
        val running = Stopwatch().start(clock)
        val rebooted = StopwatchClock(elapsedMs = 300L, wallMs = 1L, bootCount = 8)

        assertEquals(0L, running.totalAt(rebooted))
    }

    @Test
    fun `pause holds the time and resume continues from it`() {
        val paused = Stopwatch().start(clock).pause(later(4_000L))
        assertEquals(4_000L, paused.totalAt(later(99_000L)))

        val resumed = paused.start(later(10_000L))
        assertEquals(6_000L, resumed.totalAt(later(12_000L)))
    }

    @Test
    fun `starting twice keeps the first start`() {
        val running = Stopwatch().start(clock)

        assertEquals(running, running.start(later(5_000L)))
    }

    @Test
    fun `laps come newest first with their own times`() {
        val running = Stopwatch().start(clock).lap(later(1_000L)).lap(later(4_000L))

        assertEquals(
            listOf(StopwatchLap(2, 3_000L, 4_000L), StopwatchLap(1, 1_000L, 1_000L)),
            running.laps(),
        )
    }

    @Test
    fun `a paused stopwatch takes no lap`() {
        val paused = Stopwatch().start(clock).pause(later(1_000L))

        assertEquals(paused, paused.lap(later(2_000L)))
    }

    @Test
    fun `format shows hundredths`() {
        assertEquals("01:24.37", formatStopwatch(84_379L))
    }

    @Test
    fun `format shows hours from one hour`() {
        assertEquals("1:01:24.37", formatStopwatch(3_684_370L))
    }

    @Test
    fun `lap text lists the oldest lap first`() {
        val laps = listOf(StopwatchLap(2, 3_000L, 4_000L), StopwatchLap(1, 1_000L, 1_000L))

        assertEquals(
            "Lap 1\t00:01.00\t00:01.00\nLap 2\t00:03.00\t00:04.00",
            lapsAsText(laps) { "Lap $it" },
        )
    }
}
