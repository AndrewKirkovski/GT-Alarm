package com.kirkouski.gtalarm.ui.picktime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anhaki.picktime.utils.PickTimeFocusIndicator
import com.anhaki.picktime.utils.PickTimeTextStyle
import com.anhaki.picktime.utils.TimeFormat

/**
 * Fixed version of PickHourMinute that uses the same wheel geometry for the
 * separator that the hour and minute wheels use for the selected row.
 */
// reason: ported from anhaki/PickTime-Compose and inherits the same complexity + length.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun GtPickHourMinute(
    initialHour: Int,
    onHourChange: (Int) -> Unit,
    initialMinute: Int,
    onMinuteChange: (Int) -> Unit,
    selectedTextStyle: PickTimeTextStyle = PickTimeTextStyle(
        color = Color(0xFF404040),
        fontSize = 24.sp,
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
    ),
    unselectedTextStyle: PickTimeTextStyle = PickTimeTextStyle(
        color = Color(0xFF9F9F9F),
        fontSize = 18.sp,
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
    ),
    timeFormat: TimeFormat = TimeFormat.HOUR_24,
    verticalSpace: Dp = 10.dp,
    horizontalSpace: Dp = 10.dp,
    containerColor: Color = Color(0xFFFFFFFF),
    isLooping: Boolean = false,
    extraRow: Int = 2,
    focusIndicator: PickTimeFocusIndicator = PickTimeFocusIndicator(
        enabled = true,
        widthFull = false,
        background = Color(0xFFFFFFFF),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(4.dp, Color(0xFFEE4720)),
    ),
) {
    val hourRange = when (timeFormat) {
        TimeFormat.HOUR_24 -> 0..23
        TimeFormat.HOUR_12 -> 1..12
    }
    val minuteRange = 0..59

    val displayedHour = when (timeFormat) {
        TimeFormat.HOUR_24 -> initialHour.coerceIn(0, 23)
        TimeFormat.HOUR_12 -> when (initialHour % 12) { 0 -> 12 else -> initialHour % 12 }
    }
    val displayedMinute = initialMinute.coerceIn(0, 59)
    val initialAmPm = if (initialHour in 0..11) 1 else 2

    val row = extraRow.coerceIn(1, 5)

    val adjustedSelectedTextStyle = if (selectedTextStyle.fontSize < unselectedTextStyle.fontSize) {
        selectedTextStyle.copy(fontSize = unselectedTextStyle.fontSize)
    } else {
        selectedTextStyle
    }

    // Match the Wheel's full height model so the separator lands on the same
    // selected-item Y position even when the parent header constrains height.
    val density = LocalDensity.current
    val selectedItemHeightPx = measureTextHeight(adjustedSelectedTextStyle)
    val unselectedItemHeightPx = measureTextHeight(unselectedTextStyle)
    val wheelHeightDp = with(density) {
        val spacePx = verticalSpace.toPx()
        ((unselectedItemHeightPx * (row * 2)) +
            (spacePx * (row * 2 + 2)) +
            selectedItemHeightPx).toDp()
    }
    val selectedRowTopDp = with(density) {
        ((unselectedItemHeightPx * row) + (verticalSpace.toPx() * (row + 1))).toDp()
    }
    val selectedItemHeightDp = with(density) { selectedItemHeightPx.toDp() }
    val colonAlignmentOffsets = measureColonAlignmentOffsets(
        textStyle = adjustedSelectedTextStyle,
        selectedItemHeightPx = selectedItemHeightPx,
    )

    GenericPickTime(
        selectedTextStyle = adjustedSelectedTextStyle,
        verticalSpace = verticalSpace,
        containerColor = containerColor,
        focusIndicator = focusIndicator,
    ) {
        NumberWheel(
            items = hourRange.toList(),
            selectedItem = displayedHour,
            onItemSelected = { selectedHour ->
                val newHour = when (timeFormat) {
                    TimeFormat.HOUR_24 -> selectedHour
                    TimeFormat.HOUR_12 -> {
                        if (initialAmPm == 1) { // AM
                            if (selectedHour == 12) 0 else selectedHour
                        } else { // PM
                            if (selectedHour == 12) 12 else selectedHour + 12
                        }
                    }
                }
                onHourChange(newHour)
            },
            space = verticalSpace,
            selectedTextStyle = adjustedSelectedTextStyle,
            unselectedTextStyle = unselectedTextStyle,
            extraRow = row,
            isLooping = isLooping,
            overlayColor = containerColor,
        )
        Spacer(modifier = Modifier.width(horizontalSpace))
        ColonSeparator(
            wheelHeight = wheelHeightDp,
            selectedRowTop = selectedRowTopDp,
            selectedItemHeight = selectedItemHeightDp,
            rowOffset = colonAlignmentOffsets.rowOffset,
            textOffset = colonAlignmentOffsets.textOffset,
            textStyle = adjustedSelectedTextStyle,
        )
        Spacer(modifier = Modifier.width(horizontalSpace))
        NumberWheel(
            items = minuteRange.toList(),
            selectedItem = displayedMinute,
            onItemSelected = onMinuteChange,
            space = verticalSpace,
            selectedTextStyle = adjustedSelectedTextStyle,
            unselectedTextStyle = unselectedTextStyle,
            extraRow = row,
            isLooping = isLooping,
            overlayColor = containerColor,
        )
        if (timeFormat == TimeFormat.HOUR_12) {
            Spacer(modifier = Modifier.width(horizontalSpace))
            StringWheel(
                items = listOf("AM", "PM"),
                selectedItem = initialAmPm,
                onItemSelected = { amPm ->
                    val adjustedHour = when (amPm) {
                        1 -> if (initialHour in 12..23) initialHour - 12 else initialHour
                        2 -> if (initialHour in 0..11) {
                            if (initialHour == 0) 12 else initialHour + 12
                        } else initialHour
                        else -> initialHour
                    }
                    onHourChange(adjustedHour)
                },
                space = verticalSpace,
                selectedTextStyle = adjustedSelectedTextStyle,
                unselectedTextStyle = unselectedTextStyle,
                extraRow = row,
                isLooping = false,
                overlayColor = containerColor,
            )
        }
    }
}

@Composable
private fun ColonSeparator(
    wheelHeight: Dp,
    selectedRowTop: Dp,
    selectedItemHeight: Dp,
    rowOffset: Dp,
    textOffset: Dp,
    textStyle: PickTimeTextStyle,
) {
    Box(
        modifier = Modifier
            .height(wheelHeight),
    ) {
        Box(
            modifier = Modifier
                .offset(y = selectedRowTop + rowOffset)
                .height(selectedItemHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                modifier = Modifier.offset(y = textOffset),
                text = ":",
                style = textStyle.toTextStyle(),
            )
        }
    }
}
