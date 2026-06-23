package com.kirkouski.gtwake.companion.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.VibrationPattern

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Int,
    val enabled: Boolean,
    val audioUri: String?,
    val audioName: String?,
    val isVibrationOnly: Boolean,
    // Last-write-wins timestamp for cross-device LWW conflict resolution.
    // Stamped by AlarmRepository on every mutation.
    val updatedAtEpoch: Long = 0L,
    // Per-alarm snooze duration in minutes. Schema v3.
    val snoozeMinutes: Int = Alarm.DEFAULT_SNOOZE_MINUTES,
    // Schema v4: relative "in N min" alarms (null for absolute) and
    // self-destruct flag (delete row after final dismiss). Invariants
    // enforced by the Alarm domain model: relativeMinutes != null implies
    // daysOfWeek == 0; selfDestruct == true illegal with daysOfWeek != 0.
    val relativeMinutes: Int? = null,
    val selfDestruct: Boolean = false,
    // Schema v5: snoozed-until epoch. Non-null while snoozed; cleared on
    // fire/edit/disable so the list always reflects the actual next ring.
    val snoozedUntilEpoch: Long? = null,
    // Schema v6: per-alarm background image URI (content://...). null means
    // "fall back to the default from SettingsStore". Pre-release schema
    // bump uses destructive migration (no installs to preserve).
    val backgroundImageUri: String? = null,
    // Schema v9: vibration pattern (enum name), audio crescendo seconds,
    // consecutive-snooze cap + counter, and skip-next-occurrence epoch.
    val vibrationPatternName: String = VibrationPattern.DEFAULT.name,
    // Schema v10: per-device vibration enables (1.0.6).
    val phoneVibrationEnabled: Boolean = true,
    val watchVibrationEnabled: Boolean = true,
    val volumeRampSeconds: Int = 0,
    val maxSnoozeCount: Int = Alarm.MAX_SNOOZE_COUNT_UNLIMITED,
    val consecutiveSnoozeCount: Int = 0,
    val skipNextEpoch: Long? = null,
)
