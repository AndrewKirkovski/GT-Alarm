package com.kirkouski.gtalarm.util

import android.text.format.DateUtils

object RelativeTime {

    /**
     * Localized "in 14 hr" / "in 23 min" / "in 5 days" string for a future
     * timestamp. Uses Android's built-in `DateUtils.getRelativeTimeSpanString`
     * with abbreviated relative format so we get one-component output that fits
     * a tight list row (vs the `RelativeDateTimeFormatter` two-component form).
     *
     * Returns an empty string when the trigger has already passed (caller is
     * expected to suppress the row in that case).
     *
     * Note: `DateUtils` reads from `Locale.getDefault()` so the per-app locale
     * override (locales_config.xml) is honored only via the system propagating
     * its choice to the default locale on resume. For our 6 declared locales
     * this is correct in practice.
     */
    fun formatUntil(
        triggerEpochMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        if (triggerEpochMillis <= nowMillis) return ""
        return DateUtils.getRelativeTimeSpanString(
            triggerEpochMillis,
            nowMillis,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
}
