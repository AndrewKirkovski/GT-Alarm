package com.kirkouski.gtalarm.domain

/** Per-alarm vibration character. Phone runs the full [phoneTimings] waveform;
 *  watch is Lite Wearable + can only call `vibrator.vibrate({ mode: 'short' | 'long' })`,
 *  so it walks [watchSequence] via setTimeout.
 *
 *  Only the enum `name` crosses the watch wire; [watchSequence] is
 *  reference-only and MUST stay in sync with `watch-app/.../pages/index/
 *  index.js#VIBRATION_PATTERNS`. The watch table is authoritative — these
 *  values are mirrored here for documentation + cross-device feel parity. */
enum class VibrationPattern(
    val phoneTimings: LongArray,
    val watchSequence: List<WatchStep>,
) {
    /** Steady long pulses, classic alarm clock. */
    PULSE(
        phoneTimings = longArrayOf(0L, 600L, 900L),
        watchSequence = listOf(WatchStep("long", 1500)),
    ),

    /** Two quick beats then long pause — da-dum, da-dum. */
    HEARTBEAT(
        phoneTimings = longArrayOf(0L, 80L, 150L, 80L, 1000L),
        watchSequence = listOf(
            WatchStep("short", 150),
            WatchStep("short", 1000),
        ),
    ),

    /** Three rapid taps then a beat. */
    THREE_TAP(
        phoneTimings = longArrayOf(0L, 80L, 120L, 80L, 120L, 80L, 1200L),
        watchSequence = listOf(
            WatchStep("short", 200),
            WatchStep("short", 200),
            WatchStep("short", 1200),
        ),
    ),

    /** Two long pulses tight together, then breath. */
    LONG_LONG(
        phoneTimings = longArrayOf(0L, 500L, 500L, 500L, 1500L),
        watchSequence = listOf(
            WatchStep("long", 800),
            WatchStep("long", 1200),
        ),
    ),

    /** No vibration — audio-only ring. */
    OFF(
        phoneTimings = longArrayOf(0L),
        watchSequence = emptyList(),
    );

    data class WatchStep(val mode: String, val delayAfterMs: Int)

    companion object {
        val DEFAULT: VibrationPattern = PULSE
        fun fromWireName(name: String?): VibrationPattern =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
