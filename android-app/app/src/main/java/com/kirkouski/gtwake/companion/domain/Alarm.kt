package com.kirkouski.gtwake.companion.domain

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
    // taps Snooze. SNOOZE_DISABLED (0) means "snooze is off" — the ring UI
    // hides the Snooze button entirely. Otherwise constrained to
    // [MIN_SNOOZE_MINUTES, MAX_SNOOZE_MINUTES]; the edit screen enforces,
    // the receive-side wire parser clamps.
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
    // Carried in the wire envelope so the field round-trips through sync,
    // but intentionally EXCLUDED from AlarmHash because content:// URIs
    // don't round-trip between devices (each peer's resolver mints its
    // own opaque IDs) — including it would mismatch every sync forever.
    val backgroundImageUri: String? = null,
    // Per-alarm wrist character + audio crescendo + snooze-count cap (Tier 1+2).
    val vibrationPattern: VibrationPattern = VibrationPattern.DEFAULT,
    // Per-device vibration enables (1.0.6) — independent of `vibrationPattern`
    // (now pattern-only). The phone honors phoneVibrationEnabled; the watch
    // honors watchVibrationEnabled. "Silent" on a device = sound off + that
    // device's vibration off. phoneVibrationEnabled is phone-only (off the wire
    // and off the LWW hash); watchVibrationEnabled syncs to the watch.
    val phoneVibrationEnabled: Boolean = true,
    val watchVibrationEnabled: Boolean = true,
    val volumeRampSeconds: Int = 0,
    val maxSnoozeCount: Int = MAX_SNOOZE_COUNT_UNLIMITED,
    val consecutiveSnoozeCount: Int = 0,
    /** Epoch of the NEXT firing the user marked as skipped (swipe-left).
     *  `NextTriggerCalculator` rolls past it; null = no skip pending. */
    val skipNextEpoch: Long? = null,
) {
    init {
        require(snoozeMinutes == SNOOZE_DISABLED || snoozeMinutes in MIN_SNOOZE_MINUTES..MAX_SNOOZE_MINUTES) {
            "snoozeMinutes=$snoozeMinutes invalid — must be SNOOZE_DISABLED (0) or in " +
                "[$MIN_SNOOZE_MINUTES, $MAX_SNOOZE_MINUTES]"
        }
        require(volumeRampSeconds in 0..MAX_VOLUME_RAMP_SECONDS) {
            "volumeRampSeconds=$volumeRampSeconds out of range [0, $MAX_VOLUME_RAMP_SECONDS]"
        }
        require(maxSnoozeCount in 0..MAX_SNOOZE_COUNT_CAP) {
            "maxSnoozeCount=$maxSnoozeCount out of range [0, $MAX_SNOOZE_COUNT_CAP]"
        }
        require(consecutiveSnoozeCount >= 0) {
            "consecutiveSnoozeCount=$consecutiveSnoozeCount must be >= 0"
        }
        // Label cap: defense against a compromised / buggy peer pushing a
        // multi-megabyte label that would bloat the DB row, full-screen
        // AlarmActivity title, widget, and notification subject. 256 chars
        // is well past any reasonable user-typed label (the edit field has
        // no enforced max but humans don't write paragraphs).
        require(label.length <= MAX_LABEL_LENGTH) {
            "label too long (${label.length} chars, max $MAX_LABEL_LENGTH)"
        }
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

    /** True when snooze is set up — the ring UI shows a Snooze button only in this case.
     *  Combines the static config (snoozeMinutes>0) with the runtime cap
     *  (consecutiveSnoozeCount<maxSnoozeCount), so once the cap is hit the
     *  button hides until a real dismiss resets the counter. */
    val isSnoozeEnabled: Boolean
        get() = snoozeMinutes > 0 &&
            (maxSnoozeCount == MAX_SNOOZE_COUNT_UNLIMITED || consecutiveSnoozeCount < maxSnoozeCount)

    /**
     * Fire time for relative alarms. For absolute alarms returns
     * [updatedAtEpoch] (i.e. the last-edited instant), which is NOT a
     * meaningful fire time — callers must check [isRelative] first.
     */
    fun computedFireEpoch(): Long {
        check(isRelative) { "computedFireEpoch called on absolute alarm id=$id" }
        return updatedAtEpoch + (relativeMinutes ?: 0) * 60_000L
    }

    companion object {
        const val DEFAULT_SNOOZE_MINUTES = 10
        const val SNOOZE_DISABLED = 0
        const val MIN_SNOOZE_MINUTES = 1
        const val MAX_SNOOZE_MINUTES = 60
        const val MIN_RELATIVE_MINUTES = 1
        const val MAX_RELATIVE_MINUTES = 1440 // 24 hours
        const val MAX_LABEL_LENGTH = 256
        const val MAX_VOLUME_RAMP_SECONDS = 60
        /** Sentinel: no cap on consecutive snoozes. */
        const val MAX_SNOOZE_COUNT_UNLIMITED = 0
        const val MAX_SNOOZE_COUNT_CAP = 20
    }
}
