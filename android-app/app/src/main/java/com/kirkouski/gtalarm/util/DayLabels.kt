package com.kirkouski.gtalarm.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.domain.DaysOfWeek
import java.util.Calendar
import java.util.Locale

@StringRes
fun shortLabelResForDayBit(bit: Int): Int = when (bit) {
    DaysOfWeek.SUN -> R.string.day_sun_short
    DaysOfWeek.MON -> R.string.day_mon_short
    DaysOfWeek.TUE -> R.string.day_tue_short
    DaysOfWeek.WED -> R.string.day_wed_short
    DaysOfWeek.THU -> R.string.day_thu_short
    DaysOfWeek.FRI -> R.string.day_fri_short
    DaysOfWeek.SAT -> R.string.day_sat_short
    else -> R.string.day_sun_short
}

/**
 * Locale-derived first day of week (Calendar.SUNDAY..Calendar.SATURDAY).
 * en_US returns SUNDAY (1); DE/PL/RU/UA/BY/CN return MONDAY (2).
 * Settings override (`SettingsStore.firstDayOfWeek`) wraps this — callers
 * should resolve via [firstCalendarDayWithOverride] below.
 */
fun localeFirstCalendarDay(): Int = Calendar.getInstance().firstDayOfWeek

/**
 * Resolve the first day of week. [overrideValue] from SettingsStore wins when
 * non-null; otherwise the locale-derived default applies. Range-clamped to
 * the Calendar SUNDAY..SATURDAY constant range.
 */
fun firstCalendarDayWithOverride(overrideValue: Int?): Int =
    overrideValue?.takeIf { it in Calendar.SUNDAY..Calendar.SATURDAY } ?: localeFirstCalendarDay()

@Composable
fun rememberLocaleOrderedDayBits(): List<Int> = rememberOrderedDayBits(overrideFirstDay = null)

/**
 * Same as [rememberLocaleOrderedDayBits] but honours an explicit
 * "first day of week" override (1..7, Calendar.SUNDAY..Calendar.SATURDAY).
 * Null = follow locale.
 */
@Composable
fun rememberOrderedDayBits(overrideFirstDay: Int?): List<Int> = remember(overrideFirstDay) {
    val firstCalDay = firstCalendarDayWithOverride(overrideFirstDay)
    DaysOfWeek.rotated(DaysOfWeek.fromCalendarDay(firstCalDay))
}

/**
 * Full localized day name for a Calendar.SUNDAY..Calendar.SATURDAY value
 * via `Calendar.getDisplayName(DAY_OF_WEEK, LONG, locale)`. Returns a
 * fallback "?" if the platform refuses to render the name (shouldn't
 * happen on real devices, defensive against test envs).
 */
fun longDayName(calendarDay: Int, locale: Locale = Locale.getDefault()): String {
    val cal = Calendar.getInstance(locale).apply {
        set(Calendar.DAY_OF_WEEK, calendarDay)
    }
    return cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, locale) ?: "?"
}
