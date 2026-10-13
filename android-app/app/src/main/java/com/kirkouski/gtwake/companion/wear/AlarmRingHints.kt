package com.kirkouski.gtwake.companion.wear

import com.kirkouski.gtwake.companion.data.SettingsState
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
    // 1.0.6: whether the WATCH should vibrate for this alarm. Inlined so a
    // cold-paired watch honors "silent on watch" at fire time without sync.
    val watchVibrationEnabled: Boolean,
    // "Show alarm list on watch" at fire time. false tells the watch to drop
    // any list it still holds (cleared after the ring ends). null = unknown
    // (phone not unlocked yet, settings unreadable) → field omitted, watch
    // changes nothing.
    val listSync: Boolean? = null,
    // 12/24 h for the ring clock. With list sync off the watch never gets a
    // settings_changed, so the fire carries it. null = follow the watch's
    // locale, or unknown pre-unlock → field omitted.
    val use24Hour: Boolean? = null,
    // Wall time this ring started on the phone. Constant across the pre-arm
    // and every catch-up re-send, so the watch can tell a late copy of a ring
    // it already ended (ignore) from a new ring of the same alarm, such as a
    // snooze re-fire (ring). null = omitted.
    val firedAtEpoch: Long? = null,
    // Whether the phone can receive the watch's dismiss/snooze right now
    // (user unlocked AND the P2P receiver registered). Only then does the
    // envelope carry `supportsActionAck: true`, which makes the watch wait
    // for a committed reply. Otherwise the watch completes on transport 207,
    // as released builds always did.
    val supportsActionAck: Boolean = false,
) {
    companion object {
        /**
         * [settings] is null before the first unlock: DataStore lives in
         * credential-encrypted storage, so the ring path must not read it
         * there (an exception would tear down the ring).
         */
        fun from(alarm: Alarm, settings: SettingsState? = null): AlarmRingHints = AlarmRingHints(
            vibrationPatternName = alarm.vibrationPattern.name,
            snoozeAllowed = alarm.isSnoozeEnabled,
            watchVibrationEnabled = alarm.watchVibrationEnabled,
            listSync = settings?.effectiveWatchListSync,
            use24Hour = settings?.use24Hour,
        )
    }
}
