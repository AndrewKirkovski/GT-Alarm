package com.kirkouski.gtwake.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.domain.Alarm

@Composable
fun SnoozeRow(value: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_snooze),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(end = 8.dp).size(24.dp),
            )
            Text(
                text = stringResource(R.string.field_snooze_minutes),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (value <= Alarm.SNOOZE_DISABLED) {
                    stringResource(R.string.field_snooze_off_value)
                } else {
                    stringResource(R.string.field_snooze_minutes_value, value)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SNOOZE_PRESETS.forEach { preset ->
                SnoozeChip(
                    minutes = preset,
                    selected = preset == value,
                    onClick = { onChange(preset) },
                )
            }
        }
    }
}

@Composable
private fun SnoozeChip(minutes: Int, selected: Boolean, onClick: () -> Unit) {
    val label = if (minutes <= Alarm.SNOOZE_DISABLED) stringResource(R.string.field_snooze_off_chip) else "$minutes"
    OptionChip(label = label, selected = selected, onClick = onClick)
}

private val SNOOZE_PRESETS = listOf(
    Alarm.SNOOZE_DISABLED,
    Alarm.MIN_SNOOZE_MINUTES,
    5,
    Alarm.DEFAULT_SNOOZE_MINUTES,
    30,
)

@Composable
fun MaxSnoozeRow(value: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_snooze),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(end = 8.dp).size(24.dp),
            )
            Text(
                text = stringResource(R.string.field_max_snooze),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (value <= Alarm.MAX_SNOOZE_COUNT_UNLIMITED) {
                    stringResource(R.string.field_max_snooze_unlimited)
                } else {
                    stringResource(R.string.field_max_snooze_value, value)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            MAX_SNOOZE_PRESETS.forEach { preset ->
                MaxSnoozeChip(
                    count = preset,
                    selected = preset == value,
                    onClick = { onChange(preset) },
                )
            }
        }
    }
}

@Composable
private fun MaxSnoozeChip(count: Int, selected: Boolean, onClick: () -> Unit) {
    val label = if (count <= Alarm.MAX_SNOOZE_COUNT_UNLIMITED) {
        stringResource(R.string.field_max_snooze_unlimited)
    } else {
        "$count"
    }
    OptionChip(label = label, selected = selected, onClick = onClick)
}

private val MAX_SNOOZE_PRESETS = listOf(
    Alarm.MAX_SNOOZE_COUNT_UNLIMITED,
    1,
    3,
    5,
    10,
)

@Composable
fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(36.dp)
            .defaultMinSize(minWidth = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}
