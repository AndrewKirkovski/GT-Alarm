package com.kirkouski.gtwake.companion.stopwatch

/** One reading of the device clocks. Passed in, so the logic has plain unit tests. */
data class StopwatchClock(val elapsedMs: Long, val wallMs: Long, val bootCount: Int)

/** One lap, for display. */
data class StopwatchLap(val number: Int, val lapMs: Long, val totalMs: Long)

/**
 * Stopwatch state, kept as timestamps, not a ticking counter, so the time
 * survives the app closing, a swiped notification and a force-stop.
 *
 * On the same boot it counts elapsed time, which a clock change can't move.
 * Elapsed time restarts at a reboot, so after one it counts wall time instead.
 * The boot count decides, not a broadcast: Android 15+ re-sends boot broadcasts
 * after a force-stop.
 */
data class Stopwatch(
    val phase: Phase = Phase.Idle,
    val accumulatedMs: Long = 0L,
    val elapsedBaseMs: Long = 0L,
    val wallBaseMs: Long = 0L,
    val bootCount: Int = 0,
    val lapTotalsMs: List<Long> = emptyList(),
) {
    enum class Phase { Idle, Running, Paused }

    fun totalAt(clock: StopwatchClock): Long =
        if (phase == Phase.Running) accumulatedMs + sinceStart(clock) else accumulatedMs

    private fun sinceStart(clock: StopwatchClock): Long {
        val delta = if (clock.bootCount == bootCount) {
            clock.elapsedMs - elapsedBaseMs
        } else {
            clock.wallMs - wallBaseMs
        }
        return delta.coerceAtLeast(0L)
    }

    fun start(clock: StopwatchClock): Stopwatch =
        if (phase == Phase.Running) {
            this
        } else {
            copy(
                phase = Phase.Running,
                elapsedBaseMs = clock.elapsedMs,
                wallBaseMs = clock.wallMs,
                bootCount = clock.bootCount,
            )
        }

    fun pause(clock: StopwatchClock): Stopwatch =
        if (phase == Phase.Running) copy(phase = Phase.Paused, accumulatedMs = totalAt(clock)) else this

    fun lap(clock: StopwatchClock): Stopwatch =
        if (phase == Phase.Running) copy(lapTotalsMs = lapTotalsMs + totalAt(clock)) else this

    /** Newest first. */
    fun laps(): List<StopwatchLap> =
        lapTotalsMs.mapIndexed { index, total ->
            StopwatchLap(
                number = index + 1,
                lapMs = total - (lapTotalsMs.getOrNull(index - 1) ?: 0L),
                totalMs = total,
            )
        }.asReversed()
}
