package com.kirkouski.gtwake.companion.stopwatch

import java.util.Locale

private const val MS_PER_CENTISECOND = 10L
private const val CENTISECONDS_PER_SECOND = 100L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** `01:24.37`, or `1:01:24.37` from one hour. */
fun formatStopwatch(totalMs: Long): String {
    val centiseconds = totalMs.coerceAtLeast(0L) / MS_PER_CENTISECOND
    val seconds = centiseconds / CENTISECONDS_PER_SECOND
    val fraction = centiseconds % CENTISECONDS_PER_SECOND
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d.%02d".format(Locale.ROOT, hours, minutes, rest, fraction)
    } else {
        "%02d:%02d.%02d".format(Locale.ROOT, minutes, rest, fraction)
    }
}

/** Plain text for the clipboard, oldest lap first. */
fun lapsAsText(laps: List<StopwatchLap>, lapLabel: (Int) -> String): String =
    laps.sortedBy { it.number }.joinToString("\n") { lap ->
        "${lapLabel(lap.number)}\t${formatStopwatch(lap.lapMs)}\t${formatStopwatch(lap.totalMs)}"
    }
