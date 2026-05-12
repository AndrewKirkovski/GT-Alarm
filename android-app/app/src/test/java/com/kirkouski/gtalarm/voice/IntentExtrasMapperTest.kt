package com.kirkouski.gtalarm.voice

import android.provider.AlarmClock
import com.kirkouski.gtalarm.domain.DaysOfWeek
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentExtrasMapperTest {

    @Test
    fun `null hour returns null (caller routes to manual UI)`() {
        val result = IntentExtrasMapper.build(
            IntentExtras(
                hour = null, minutes = 0, message = null, days = null,
                vibrate = false, ringtone = null, skipUi = true,
            ),
        )
        assertNull(result)
    }

    @Test
    fun `out-of-range hour returns null`() {
        assertNull(buildSimple(hour = -1))
        assertNull(buildSimple(hour = 24))
        assertNull(buildSimple(hour = 100))
    }

    @Test
    fun `valid hour with no minutes defaults to 0`() {
        val result = buildSimple(hour = 7, minutes = null)
        assertNotNull(result)
        assertEquals(7, result!!.alarm.hour)
        assertEquals(0, result.alarm.minute)
    }

    @Test
    fun `out-of-range minutes coerce to 0-59`() {
        assertEquals(59, buildSimple(hour = 7, minutes = 999)!!.alarm.minute)
        assertEquals(0, buildSimple(hour = 7, minutes = -10)!!.alarm.minute)
    }

    @Test
    fun `null message becomes empty label`() {
        val result = buildSimple(hour = 7, message = null)!!
        assertEquals("", result.alarm.label)
    }

    @Test
    fun `non-null message becomes label as-is`() {
        val result = buildSimple(hour = 7, message = "Wake up")!!
        assertEquals("Wake up", result.alarm.label)
    }

    @Test
    fun `null days list yields one-shot alarm (mask 0)`() {
        val result = buildSimple(hour = 7, days = null)!!
        assertEquals(0, result.alarm.daysOfWeek)
    }

    @Test
    fun `single weekday converts via fromCalendarDay`() {
        val result = buildSimple(hour = 7, days = listOf(Calendar.MONDAY))!!
        assertEquals(DaysOfWeek.MON, result.alarm.daysOfWeek)
    }

    @Test
    fun `weekdays list folds into WEEKDAYS bitmask`() {
        val result = buildSimple(
            hour = 7,
            days = listOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY,
            ),
        )!!
        assertEquals(DaysOfWeek.WEEKDAYS, result.alarm.daysOfWeek)
    }

    @Test
    fun `weekend list folds into WEEKENDS bitmask`() {
        val result = buildSimple(
            hour = 9,
            days = listOf(Calendar.SATURDAY, Calendar.SUNDAY),
        )!!
        assertEquals(DaysOfWeek.WEEKENDS, result.alarm.daysOfWeek)
    }

    @Test
    fun `all 7 days fold into ALL bitmask`() {
        val result = buildSimple(
            hour = 9,
            days = listOf(
                Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY,
            ),
        )!!
        assertEquals(DaysOfWeek.ALL, result.alarm.daysOfWeek)
    }

    @Test
    fun `null ringtone leaves audioUri null and respects vibrate flag`() {
        val r1 = buildSimple(hour = 7, ringtone = null, vibrate = false)!!
        assertNull(r1.alarm.audioUri)
        assertFalse(r1.alarm.isVibrationOnly)

        val r2 = buildSimple(hour = 7, ringtone = null, vibrate = true)!!
        assertNull(r2.alarm.audioUri)
        assertTrue(r2.alarm.isVibrationOnly)
    }

    @Test
    fun `VALUE_RINGTONE_SILENT forces vibration-only regardless of vibrate flag`() {
        val result = buildSimple(
            hour = 7,
            ringtone = AlarmClock.VALUE_RINGTONE_SILENT,
            vibrate = false,
        )!!
        assertNull(result.alarm.audioUri)
        assertTrue(result.alarm.isVibrationOnly)
    }

    @Test
    fun `explicit ringtone URI is passed through verbatim`() {
        val uri = "content://media/internal/audio/media/42"
        val result = buildSimple(hour = 7, ringtone = uri)!!
        assertEquals(uri, result.alarm.audioUri)
        assertNull(result.alarm.audioName)
    }

    @Test
    fun `skipUi flag is passed through unchanged`() {
        assertTrue(buildSimple(hour = 7, skipUi = true)!!.skipUi)
        assertFalse(buildSimple(hour = 7, skipUi = false)!!.skipUi)
    }

    @Test
    fun `enabled defaults to true on every parsed alarm`() {
        assertTrue(buildSimple(hour = 7)!!.alarm.enabled)
    }

    private fun buildSimple(
        hour: Int? = 7,
        minutes: Int? = 0,
        message: String? = null,
        days: List<Int>? = null,
        vibrate: Boolean = false,
        ringtone: String? = null,
        skipUi: Boolean = true,
    ) = IntentExtrasMapper.build(
        IntentExtras(
            hour = hour, minutes = minutes, message = message, days = days,
            vibrate = vibrate, ringtone = ringtone, skipUi = skipUi,
        ),
    )
}
