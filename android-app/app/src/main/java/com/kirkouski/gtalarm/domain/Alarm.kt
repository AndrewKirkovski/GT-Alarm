package com.kirkouski.gtalarm.domain

data class Alarm(
    val id: Long = 0L,
    val label: String = "",
    val hour: Int = 7,
    val minute: Int = 0,
    val daysOfWeek: Int = 0,
    val enabled: Boolean = true,
    val audioUri: String? = null,
    val audioName: String? = null,
    val isVibrationOnly: Boolean = false,
    // Per-alarm snooze duration. Used by AlarmRingService when the user
    // taps Snooze. Range constrained by [MIN_SNOOZE_MINUTES, MAX_SNOOZE_MINUTES]
    // — the edit screen enforces, the receive-side wire parser clamps.
    val snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    val updatedAtEpoch: Long = 0L,
    // Relative "in N minutes" alarms: null for absolute (clock-time) alarms;
    // otherwise duration in minutes from `updatedAtEpoch` to fire time.
    // Range: [MIN_RELATIVE_MINUTES, MAX_RELATIVE_MINUTES]. When non-null,
    // `daysOfWeek` MUST be 0 (relative alarms are one-shot).
    val relativeMinutes: Int? = null,
    // Self-destruct: when true, the row is deleted after the final dismiss
    // (snooze cycles preserve it). Default ON for one-shot absolute + all
    // relative; OFF for recurring. Illegal combination: `selfDestruct == true`
    // with `daysOfWeek != 0` (enforced below + by the edit screen UI).
    val selfDestruct: Boolean = false,
    // Snoozed-until timestamp. When non-null and in the future, the alarm
    // is currently snoozed and `nextTriggerEpochMillis()` returns this
    // directly instead of computing from hour/minute/daysOfWeek. Cleared
    // when the alarm fires (snooze consumed), the user disables/edits the
    // alarm, or the trigger is in the past. NOT serialized over the wire
    // — local-only UI state, watch reads its own AlarmManager-equivalent
    // for its display. Not included in the LWW hash either.
    val snoozedUntilEpoch: Long? = null,
    // Per-alarm full-screen background image for the phone AlarmActivity.
    // null = use the default from SettingsStore. content:// URI obtained
    // via OpenDocument with persistable read permission taken so the URI
    // survives process death between picker selection and the eventual
    // ring (could be hours later). Cover mode only — rendered with
    // ContentScale.Crop behind a dimming overlay.
    //
    // Included in the LWW hash + wire envelope so a peer that learns
    // about an alarm also knows what background to render (the watch
    // currently ignores it, but the field is preserved for future use
    // and so two devices stay in sync on alarm equality).
    val backgroundImageUri: String? = null,
    // Per-alarm watch-side background image. Resolved at picker time:
    // the source image is cropped to a centered circular region and
    // scaled to WATCH_BG_NATIVE_PX (466 × 466 for GT 6 Pro), written to
    // the app cache as a PNG, and the local file:// URI of the cropped
    // PNG is stored here. NOT serialized on the wire — the parallel
    // BGRA `.bin` is what the watch consumes via the file-transfer
    // path. Null means "no watch background — watch uses its default
    // ring UI". Not included in AlarmHash either (the watch reconciles
    // bg presence via the file-transfer path, not via the alarm row).
    val watchBackgroundImageUri: String? = null,
) {
    init {
        if (relativeMinutes != null) {
            require(relativeMinutes in MIN_RELATIVE_MINUTES..MAX_RELATIVE_MINUTES) {
                "relativeMinutes=$relativeMinutes out of range " +
                    "[$MIN_RELATIVE_MINUTES, $MAX_RELATIVE_MINUTES]"
            }
            require(daysOfWeek == 0) {
                "relativeMinutes set but daysOfWeek=$daysOfWeek (relative alarms are one-shot)"
            }
        }
        if (selfDestruct) {
            require(daysOfWeek == 0) {
                "selfDestruct=true illegal with daysOfWeek=$daysOfWeek (recurring alarms can't self-destruct)"
            }
        }
    }

    /** True if this is a "in N minutes" relative-time alarm. */
    val isRelative: Boolean get() = relativeMinutes != null

    /** Fire time for relative alarms. Undefined for absolute — caller must check `isRelative`. */
    fun computedFireEpoch(): Long = updatedAtEpoch + (relativeMinutes ?: 0) * 60_000L

    companion object {
        const val DEFAULT_SNOOZE_MINUTES = 10
        const val MIN_SNOOZE_MINUTES = 1
        const val MAX_SNOOZE_MINUTES = 60
        const val MIN_RELATIVE_MINUTES = 1
        const val MAX_RELATIVE_MINUTES = 1440 // 24 hours
    }
}
