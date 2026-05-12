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
