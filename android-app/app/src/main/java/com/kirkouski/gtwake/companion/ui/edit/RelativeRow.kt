package com.kirkouski.gtwake.companion.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anhaki.picktime.utils.PickTimeFocusIndicator
import com.anhaki.picktime.utils.PickTimeTextStyle
import com.anhaki.picktime.utils.TimeFormat
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.ui.picktime.GtPickHourMinute

@Composable
fun RelativeRow(minutes: Int, onChange: (Int) -> Unit) {
    val hours = (minutes / 60).coerceAtMost(23)
    val mins = minutes % 60
    // forceReset bumps when the proposed total would be clamped (e.g. both drums at 0:00
    // clamps to MIN_RELATIVE_MINUTES). Incrementing the key tears down and recreates the
    // picker with the correct initialHour/initialMinute so the display matches the stored value.
    var forceReset by remember { mutableIntStateOf(0) }
    key(forceReset) {
    GtPickHourMinute(
        initialHour = hours,
        onHourChange = { h ->
            val raw = h * 60 + mins
            val clamped = raw.coerceIn(Alarm.MIN_RELATIVE_MINUTES, Alarm.MAX_RELATIVE_MINUTES)
            if (clamped != raw) forceReset++
            onChange(clamped)
        },
        initialMinute = mins,
        onMinuteChange = { m ->
            val raw = hours * 60 + m
            val clamped = raw.coerceIn(Alarm.MIN_RELATIVE_MINUTES, Alarm.MAX_RELATIVE_MINUTES)
            if (clamped != raw) forceReset++
            onChange(clamped)
        },
        timeFormat = TimeFormat.HOUR_24,
        isLooping = false,
        extraRow = 2,
        containerColor = Color.Transparent,
        selectedTextStyle = PickTimeTextStyle(
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 64.sp,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
        ),
        unselectedTextStyle = PickTimeTextStyle(
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
            fontSize = 32.sp,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
        ),
        focusIndicator = PickTimeFocusIndicator(
            enabled = false,
            widthFull = false,
            background = Color.Transparent,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(0.dp, Color.Transparent),
        ),
    )
    } // end key(forceReset)
}
