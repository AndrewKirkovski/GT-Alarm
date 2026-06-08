package com.kirkouski.gtwake.companion.wear

import com.kirkouski.gtwake.companion.domain.Alarm

/**
 * Per-fire hints inlined into the `alarm_fired` envelope so the watch can
 * present the right ring affordances without a prior `sync_replace`. The
 * thin-client criterion in `docs/acceptance-criteria.md` §"Tier 1" requires
 * this — a cold-paired watch / dropped sync MUST still buzz the right
 * pattern and hide Snooze when capped.
 *
 *  - [vibrationPatternName] — enum name of the resolved pattern. The watch
 *    looks it up in its `VIBRATION_PATTERNS` table and falls back to PULSE
 *    on unknown / missing (forward-compat).
 *  - [snoozeAllowed] — pre-computed `alarm.isSnoozeEnabled`. Collapses two
 *    independent disablers (snoozeMinutes==0 OR consecutiveSnoozeCount has
 *    hit `maxSnoozeCount`) into one boolean the watch can apply verbatim.
 *    Pre-computing on the phone keeps `consecutiveSnoozeCount` off the wire
 *    (it's transient ring-cycle state; sending it would churn the sync hash
 *    on every snooze).
 */
data class AlarmRingHints(
    val vibrationPatternName: String,
    val snoozeAllowed: Boolean,
) {
    companion object {
        fun from(alarm: Alarm): AlarmRingHints = AlarmRingHints(
            vibrationPatternName = alarm.vibrationPattern.name,
            snoozeAllowed = alarm.isSnoozeEnabled,
        )
    }
}
