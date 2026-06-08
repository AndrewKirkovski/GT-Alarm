package com.kirkouski.gtwake.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirkouski.gtwake.companion.R

/**
 * Shared phone alarm UI overlay used by the real ring screen and image
 * previews, so background positioning is judged against the same time/label
 * and dismiss/snooze geometry the user will actually see.
 */
@Composable
fun PhoneRingOverlay(
    timeText: String,
    labelText: String,
    awaitingWatch: Boolean,
    onDismiss: (() -> Unit)?,
    onSnooze: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    showSnooze: Boolean = onSnooze != null,
) {
    val timeParts = splitTimeParts(timeText)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding((32f * scale).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = timeParts.main,
                color = Color.White,
                fontSize = (96f * scale).sp,
                fontWeight = FontWeight.Light,
            )
            if (timeParts.suffix != null) {
                Text(
                    text = timeParts.suffix,
                    color = Color.White,
                    fontSize = (44f * scale).sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(start = (10f * scale).dp, bottom = (12f * scale).dp),
                )
            }
        }
        if (labelText.isNotEmpty()) {
            Spacer(Modifier.height((16f * scale).dp))
            Text(
                text = labelText,
                color = Color.White,
                fontSize = (24f * scale).sp,
            )
        }
        Spacer(Modifier.height((64f * scale).dp))
        if (awaitingWatch) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.ring_waiting_for_watch),
                color = Color.White,
                fontSize = (18f * scale).sp,
            )
        } else {
            RingActionPill(
                label = androidx.compose.ui.res.stringResource(R.string.action_dismiss),
                filled = true,
                scale = scale,
                onClick = onDismiss,
            )
            if (showSnooze) {
                Spacer(Modifier.height((12f * scale).dp))
                RingActionPill(
                    label = androidx.compose.ui.res.stringResource(R.string.action_snooze),
                    filled = false,
                    scale = scale,
                    onClick = onSnooze,
                )
            }
        }
    }
}

private data class TimeParts(
    val main: String,
    val suffix: String?,
)

private fun splitTimeParts(timeText: String): TimeParts {
    val trimmed = timeText.trim()
    val suffix = MERIDIEM_SUFFIXES.firstOrNull { trimmed.endsWith(" $it", ignoreCase = true) }
        ?: return TimeParts(main = timeText, suffix = null)
    return TimeParts(
        main = trimmed.removeSuffix(" $suffix"),
        suffix = suffix.uppercase(),
    )
}

private val MERIDIEM_SUFFIXES = listOf("AM", "PM")

@Composable
private fun RingActionPill(
    label: String,
    filled: Boolean,
    scale: Float,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape((24f * scale).dp)
    val labelFontSize = (18f * scale).sp
    val modifier = Modifier
        .fillMaxWidth()
        .height((48f * scale).dp)
        .clip(shape)
        .then(
            if (filled) {
                Modifier.background(Color.White)
            } else {
                Modifier.border((1f * scale).dp, Color.White.copy(alpha = 0.72f), shape)
            },
        )
        .clickable(enabled = onClick != null) { onClick?.invoke() }
    androidx.compose.foundation.layout.Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (filled) Color.Black else Color.White,
            fontSize = labelFontSize,
            lineHeight = labelFontSize,
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
        )
    }
}
