package com.kirkouski.gtalarm.data.db

import com.kirkouski.gtalarm.domain.Alarm

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
    watchBackgroundImageUri = watchBackgroundImageUri,
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
    watchBackgroundImageUri = watchBackgroundImageUri,
)
