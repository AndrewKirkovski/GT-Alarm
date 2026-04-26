package com.kirkouski.gtalarm.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.domain.DaysOfWeek
import java.util.Calendar

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

@Composable
fun rememberLocaleOrderedDayBits(): List<Int> = remember {
    val firstCalDay = Calendar.getInstance().firstDayOfWeek
    DaysOfWeek.rotated(DaysOfWeek.fromCalendarDay(firstCalDay))
}
