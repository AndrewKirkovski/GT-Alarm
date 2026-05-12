package com.kirkouski.gtalarm.voice

import android.content.Intent
import android.provider.AlarmClock
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek

data class ParsedSetAlarm(val alarm: Alarm, val skipUi: Boolean)

/** Raw values extracted from a SET_ALARM Intent before they are validated and mapped to an Alarm. */
data class IntentExtras(
    val hour: Int?,
    val minutes: Int?,
    val message: String?,
    val days: List<Int>?,
    val vibrate: Boolean,
    val ringtone: String?,
    val skipUi: Boolean,
)

object IntentExtrasMapper {

    fun fromSetAlarmIntent(intent: Intent): ParsedSetAlarm? {
        if (intent.action != AlarmClock.ACTION_SET_ALARM) return null
        val extras = IntentExtras(
            hour = if (intent.hasExtra(AlarmClock.EXTRA_HOUR)) {
                intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
            } else {
                null
            },
            minutes = if (intent.hasExtra(AlarmClock.EXTRA_MINUTES)) {
                intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)
            } else {
                null
            },
            message = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE),
            days = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.toList(),
            vibrate = intent.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, false),
            ringtone = intent.getStringExtra(AlarmClock.EXTRA_RINGTONE),
            skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false),
        )
        return build(extras)
    }

    internal fun build(extras: IntentExtras): ParsedSetAlarm? {
        val hour = extras.hour ?: return null
        if (hour !in 0..23) return null
        val safeMinute = (extras.minutes ?: 0).coerceIn(0, 59)
        val daysMask = extras.days?.fold(0) { acc, day -> acc or DaysOfWeek.fromCalendarDay(day) } ?: 0
        val audioUri: String?
        val isVibrationOnly: Boolean
        when (extras.ringtone) {
            null -> {
                audioUri = null
                isVibrationOnly = extras.vibrate
            }
            AlarmClock.VALUE_RINGTONE_SILENT -> {
                audioUri = null
                isVibrationOnly = true
            }
            else -> {
                audioUri = extras.ringtone
                isVibrationOnly = extras.vibrate
            }
        }
        val alarm = Alarm(
            label = extras.message.orEmpty(),
            hour = hour,
            minute = safeMinute,
            daysOfWeek = daysMask,
            enabled = true,
            audioUri = audioUri,
            audioName = null,
            isVibrationOnly = isVibrationOnly,
        )
        return ParsedSetAlarm(alarm, extras.skipUi)
    }
}
