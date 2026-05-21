package com.kirkouski.gtalarm.data.db

import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.VibrationPattern

fun AlarmEntity.toDomain(): Alarm = Alarm(
    id = id,
    label = label,
    hour = hour,
    minute = minute,
    daysOfWeek = daysOfWeek,
    enabled = enabled,
    audioUri = audioUri,
    audioName = audioName,
    isVibrationOnly = isVibrationOnly,
    snoozeMinutes = snoozeMinutes,
    updatedAtEpoch = updatedAtEpoch,
    relativeMinutes = relativeMinutes,
    selfDestruct = selfDestruct,
    snoozedUntilEpoch = snoozedUntilEpoch,
    backgroundImageUri = backgroundImageUri,
    vibrationPattern = VibrationPattern.fromWireName(vibrationPatternName),
    volumeRampSeconds = volumeRampSeconds,
    maxSnoozeCount = maxSnoozeCount,
    consecutiveSnoozeCount = consecutiveSnoozeCount,
    skipNextEpoch = skipNextEpoch,
)

fun Alarm.toEntity(): AlarmEntity = AlarmEntity(
    id = id,
    label = label,
    hour = hour,
    minute = minute,
    daysOfWeek = daysOfWeek,
    enabled = enabled,
    audioUri = audioUri,
    audioName = audioName,
    isVibrationOnly = isVibrationOnly,
    updatedAtEpoch = updatedAtEpoch,
    snoozeMinutes = snoozeMinutes,
    relativeMinutes = relativeMinutes,
    selfDestruct = selfDestruct,
    snoozedUntilEpoch = snoozedUntilEpoch,
    backgroundImageUri = backgroundImageUri,
    vibrationPatternName = vibrationPattern.name,
    volumeRampSeconds = volumeRampSeconds,
    maxSnoozeCount = maxSnoozeCount,
    consecutiveSnoozeCount = consecutiveSnoozeCount,
    skipNextEpoch = skipNextEpoch,
)
