package com.kirkouski.gtalarm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

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
)
