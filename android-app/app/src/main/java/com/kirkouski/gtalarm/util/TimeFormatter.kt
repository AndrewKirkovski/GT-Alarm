package com.kirkouski.gtalarm.util

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeFormatter {

    fun formatHourMinute(context: Context, hour: Int, minute: Int): String =
        formatHourMinute(context, hour, minute, overrideUse24Hour = null)

    /**
     * Format [hour]:[minute] respecting an optional explicit 12h/24h override.
     * When [overrideUse24Hour] is null the system locale preference (via
     * [DateFormat.is24HourFormat]) is used — matching legacy behaviour.
     * Otherwise we render with an explicit pattern (`HH:mm` for 24h,
     * `h:mm a` for 12h) so the choice is locale-stable.
     */
    fun formatHourMinute(
        context: Context,
        hour: Int,
        minute: Int,
        overrideUse24Hour: Boolean?,
    ): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return if (overrideUse24Hour == null) {
            DateFormat.getTimeFormat(context).format(cal.time)
        } else {
            val pattern = if (overrideUse24Hour) PATTERN_24H else PATTERN_12H
            SimpleDateFormat(pattern, Locale.getDefault()).format(cal.time)
        }
    }

    fun formatTime(context: Context, date: Date): String =
        DateFormat.getTimeFormat(context).format(date)

    /**
     * Reads the user's locale-aware 12h/24h preference from the system.
     * Matches what [formatHourMinute] renders, so the TimePicker on the
     * edit screen agrees with the list subtitle.
     */
    fun uses24HourFormat(context: Context): Boolean = DateFormat.is24HourFormat(context)

    /**
     * Resolve the effective 12h/24h flag. [override] from SettingsStore wins
     * when non-null; otherwise the system locale preference applies.
     */
    fun resolveUses24HourFormat(context: Context, override: Boolean?): Boolean =
        override ?: DateFormat.is24HourFormat(context)

    private const val PATTERN_24H = "HH:mm"
    private const val PATTERN_12H = "h:mm a"
}
