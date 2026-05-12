package com.kirkouski.gtalarm.data.db

import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {

    @Test
    fun `domain to entity preserves all fields including LWW stamp`() {
        val alarm = Alarm(
            id = 7L,
            label = "Wake",
            hour = 7,
            minute = 30,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true,
            audioUri = "content://media/audio/42",
            audioName = "Birds",
            isVibrationOnly = false,
            snoozeMinutes = 15,
            updatedAtEpoch = 1_700_000_000_000L,
        )
        val entity = alarm.toEntity()
        assertEquals(alarm.id, entity.id)
        assertEquals(alarm.label, entity.label)
        assertEquals(alarm.hour, entity.hour)
        assertEquals(alarm.minute, entity.minute)
        assertEquals(alarm.daysOfWeek, entity.daysOfWeek)
        assertEquals(alarm.enabled, entity.enabled)
        assertEquals(alarm.audioUri, entity.audioUri)
        assertEquals(alarm.audioName, entity.audioName)
        assertEquals(alarm.isVibrationOnly, entity.isVibrationOnly)
        assertEquals(alarm.snoozeMinutes, entity.snoozeMinutes)
        assertEquals(alarm.updatedAtEpoch, entity.updatedAtEpoch)
    }

    @Test
    fun `entity to domain preserves all fields including LWW stamp`() {
        val entity = AlarmEntity(
            id = 11L,
            label = "Vibe",
            hour = 22,
            minute = 0,
            daysOfWeek = DaysOfWeek.WEEKENDS,
            enabled = false,
            audioUri = null,
            audioName = null,
            isVibrationOnly = true,
            updatedAtEpoch = 1_750_000_000_000L,
            snoozeMinutes = 5,
        )
        val alarm = entity.toDomain()
        assertEquals(entity.id, alarm.id)
        assertEquals(entity.label, alarm.label)
        assertEquals(entity.hour, alarm.hour)
        assertEquals(entity.minute, alarm.minute)
        assertEquals(entity.daysOfWeek, alarm.daysOfWeek)
        assertEquals(entity.enabled, alarm.enabled)
        assertEquals(entity.audioUri, alarm.audioUri)
        assertEquals(entity.audioName, alarm.audioName)
        assertEquals(entity.isVibrationOnly, alarm.isVibrationOnly)
        assertEquals(entity.snoozeMinutes, alarm.snoozeMinutes)
        assertEquals(entity.updatedAtEpoch, alarm.updatedAtEpoch)
    }

    @Test
    fun `round trip is idempotent`() {
        val original = Alarm(
            id = 3L,
            label = "Round",
            hour = 6,
            minute = 5,
            daysOfWeek = DaysOfWeek.MON or DaysOfWeek.WED or DaysOfWeek.FRI,
            enabled = true,
            audioUri = null,
            audioName = "Default",
            isVibrationOnly = false,
            updatedAtEpoch = 42L,
        )
        val roundTripped = original.toEntity().toDomain().toEntity().toDomain()
        assertEquals(original, roundTripped)
    }

    @Test
    fun `default updatedAtEpoch is zero (LWW sentinel for legacy rows)`() {
        val alarm = Alarm()
        assertEquals(0L, alarm.updatedAtEpoch)
    }

    @Test
    fun `default snoozeMinutes is the canonical default`() {
        val alarm = Alarm()
        assertEquals(Alarm.DEFAULT_SNOOZE_MINUTES, alarm.snoozeMinutes)
    }
}
