package com.kirkouski.gtalarm.util

import android.content.Context
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Date

object TimeFormatter {

    fun formatHourMinute(context: Context, hour: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return DateFormat.getTimeFormat(context).format(cal.time)
    }

    fun formatTime(context: Context, date: Date): String =
        DateFormat.getTimeFormat(context).format(date)

    /**
     * Reads the user's locale-aware 12h/24h preference from the system.
     * Matches what [formatHourMinute] renders, so the TimePicker on the
     * edit screen agrees with the list subtitle.
     */
    fun uses24HourFormat(context: Context): Boolean = DateFormat.is24HourFormat(context)
}
