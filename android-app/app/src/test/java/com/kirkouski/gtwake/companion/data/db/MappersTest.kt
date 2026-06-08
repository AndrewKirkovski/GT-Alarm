package com.kirkouski.gtwake.companion.data.db

import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
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

    @Test
    fun `domain to entity preserves relativeMinutes and selfDestruct`() {
        val alarm = Alarm(
            id = 21L,
            label = "Timer",
            hour = 0,
            minute = 0,
            daysOfWeek = 0,
            enabled = true,
            audioUri = null,
            audioName = null,
            isVibrationOnly = false,
            snoozeMinutes = 5,
            updatedAtEpoch = 1_780_000_000_000L,
            relativeMinutes = 30,
            selfDestruct = true,
        )
        val entity = alarm.toEntity()
        assertEquals(30, entity.relativeMinutes)
        assertEquals(true, entity.selfDestruct)
    }

    @Test
    fun `entity to domain preserves relativeMinutes and selfDestruct`() {
        val entity = AlarmEntity(
            id = 22L,
            label = "Timer",
            hour = 0,
            minute = 0,
            daysOfWeek = 0,
            enabled = true,
            audioUri = null,
            audioName = null,
            isVibrationOnly = false,
            updatedAtEpoch = 1_780_000_000_000L,
            snoozeMinutes = 5,
            relativeMinutes = 45,
            selfDestruct = true,
        )
        val alarm = entity.toDomain()
        assertEquals(45, alarm.relativeMinutes)
        assertEquals(true, alarm.selfDestruct)
    }

    @Test
    fun `round trip preserves non-null relativeMinutes and selfDestruct true`() {
        val original = Alarm(
            id = 23L,
            label = "Snack",
            hour = 0,
            minute = 0,
            daysOfWeek = 0,
            enabled = true,
            audioUri = null,
            audioName = null,
            isVibrationOnly = false,
            updatedAtEpoch = 999L,
            snoozeMinutes = 10,
            relativeMinutes = 15,
            selfDestruct = true,
        )
        val roundTripped = original.toEntity().toDomain().toEntity().toDomain()
        assertEquals(original, roundTripped)
        // Belt-and-braces: explicitly check the new fields survived both legs.
        assertEquals(15, roundTripped.relativeMinutes)
        assertEquals(true, roundTripped.selfDestruct)
    }

    @Test
    fun `round trip preserves null relativeMinutes for absolute alarms`() {
        // The null case has to round-trip cleanly — absolute alarms make up
        // the bulk of the data and a mapper that mishandled null would
        // silently break every existing alarm on the next save.
        val original = Alarm(
            id = 24L,
            label = "Wake",
            hour = 8,
            minute = 0,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true,
            audioUri = "content://media/audio/1",
            audioName = "Birds",
            isVibrationOnly = false,
            updatedAtEpoch = 12345L,
            snoozeMinutes = 10,
            relativeMinutes = null,
            selfDestruct = false,
        )
        val roundTripped = original.toEntity().toDomain().toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(null, roundTripped.relativeMinutes)
        assertEquals(false, roundTripped.selfDestruct)
    }

    @Test
    fun `backgroundImageUri survives round trip when set`() {
        // Per-alarm phone background image URI must round-trip cleanly. A
        // bug that dropped it on the entity→domain or domain→entity path
        // would make the AlarmActivity render the default-bg or black-bg
        // screen even though the user explicitly picked a per-alarm image.
        val uri = "content://com.android.providers.media.documents/document/image%3A42"
        val original = Alarm(
            id = 30L,
            label = "Photo bg",
            hour = 7,
            minute = 0,
            daysOfWeek = 0,
            enabled = true,
            updatedAtEpoch = 1L,
            backgroundImageUri = uri,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(uri, roundTripped.backgroundImageUri)
    }

    @Test
    fun `null backgroundImageUri survives round trip (fall back to default)`() {
        val original = Alarm(
            id = 31L,
            label = "No bg",
            hour = 7,
            minute = 0,
            daysOfWeek = 0,
            enabled = true,
            updatedAtEpoch = 1L,
            backgroundImageUri = null,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(null, roundTripped.backgroundImageUri)
    }
}
