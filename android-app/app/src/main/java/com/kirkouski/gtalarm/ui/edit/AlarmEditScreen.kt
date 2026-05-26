// reason: AlarmEditScreen is the single Compose entry for the edit UX and
// every section (Time, Label, Mode, Days, Audio, BackgroundImage,
// WatchBackgroundImage, Snooze, SelfDestruct, Save/Delete/Discard dialogs)
// is factored into a small private composable in the same file because
// they share state-hoisting from the VM. Splitting per-section into
// separate files would push the same state-routing across more files.
@file:Suppress("TooManyFunctions")

package com.kirkouski.gtalarm.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anhaki.picktime.PickHourMinute
import com.anhaki.picktime.utils.PickTimeFocusIndicator
import com.anhaki.picktime.utils.PickTimeTextStyle
import com.anhaki.picktime.utils.TimeFormat
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import com.kirkouski.gtalarm.util.TimeFormatter
import com.kirkouski.gtalarm.util.rememberOrderedDayBits
import com.kirkouski.gtalarm.util.shortLabelResForDayBit

// reason: Compose top-level screen composables are inherently long because
// they declare a tree of layout, state hoisting, and side-effects in one
// function. Splitting AlarmEditScreen into private sub-composables is a
// Phase 3 polish item once the layout stabilises. Suppressing locally rather
// than disabling LongMethod globally.
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun AlarmEditScreen(
    alarmId: Long?,
    onDone: () -> Unit,
    vm: AlarmEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(alarmId) { vm.load(alarmId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showUnsavedConfirm by remember { mutableStateOf(false) }

    // Cancel path. Routing on dirty + alarm-source:
    //   new draft + dirty   → "Discard new alarm?" (row never existed)
    //   existing + dirty    → "Discard changes?" (row stays at pre-edit state)
    //   anything + !dirty   → silent exit
    val cancel: () -> Unit = remember(onDone, vm) {
        {
            val s = vm.state.value
            when {
                !s.dirty -> { vm.cancel(); onDone() }
                s.isExistingAlarm -> showUnsavedConfirm = true
                else -> showDiscardConfirm = true
            }
        }
    }
    val save: () -> Unit = remember(onDone, vm) {
        { vm.save { onDone() } }
    }

    BackHandler { cancel() }

    val audioPreview = rememberAudioPreview()
    val pickAudio = rememberAudioPicker { picked ->
        audioPreview.stop()
        vm.updateAudio(picked.uri, picked.name)
    }
    val pickBackgroundImage = rememberBackgroundImagePicker { uri ->
        vm.updateBackgroundImage(uri)
    }

    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            onConfirm = {
                showDeleteConfirm = false
                vm.delete()
                onDone()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    if (showDiscardConfirm) {
        DiscardConfirmDialog(
            onConfirm = {
                showDiscardConfirm = false
                vm.cancel()
                onDone()
            },
            onDismiss = { showDiscardConfirm = false },
        )
    }
    if (showUnsavedConfirm) {
        UnsavedChangesDialog(
            onConfirm = {
                showUnsavedConfirm = false
                vm.cancel()
                onDone()
            },
            onDismiss = { showUnsavedConfirm = false },
        )
    }

    val is24h = TimeFormatter.resolveUses24HourFormat(context, settings.use24Hour)

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            CancelSaveBar(
                onCancel = cancel,
                onSave = save,
                enabled = state.loaded,
                isExisting = state.isExistingAlarm,
                onDelete = { showDeleteConfirm = true },
            )
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold

        Column(
            modifier = Modifier
                .padding(padding)
                .statusBarsPadding()
                .fillMaxSize(),
        ) {
            // Time picker — top portion with transparent background so
            // the gradient behind AlarmEditScreen shows through.
            if (state.mode == AlarmMode.ABSOLUTE) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    key(state.id, is24h) {
                        PickHourMinute(
                            initialHour = state.hour,
                            onHourChange = { h -> vm.updateTime(h, state.minute) },
                            initialMinute = state.minute,
                            onMinuteChange = { m -> vm.updateTime(state.hour, m) },
                            timeFormat = if (is24h) TimeFormat.HOUR_24 else TimeFormat.HOUR_12,
                            isLooping = true,
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
                    }
                }
            } else {
                // Existing RELATIVE alarms — show duration picker in top area.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    RelativeRow(
                        minutes = state.relativeMinutes,
                        onChange = vm::updateRelativeMinutes,
                    )
                }
            }

            // Settings card — fills remaining height, scrollable.
            // Rounded top corners only; bottom sits against the CancelSaveBar.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                val divider = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // Day repeat row (ABSOLUTE mode only)
                    if (state.mode == AlarmMode.ABSOLUTE) {
                        DayRow(
                            mask = state.daysOfWeek,
                            firstDayOverride = settings.firstDayOfWeek,
                            onToggle = vm::toggleDay,
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = divider)
                    }

                    // Alarm name — underline field
                    LabelUnderlineField(
                        value = state.label,
                        onValueChange = vm::updateLabel,
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = divider)

                    // Ringtone
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.padding(end = 12.dp).size(24.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.field_audio),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = if (state.audioUri != null) {
                                    state.audioName ?: stringResource(R.string.field_audio_unknown)
                                } else {
                                    (if (state.mode == AlarmMode.RELATIVE) {
                                        settings.defaultRelativeRingtoneName
                                    } else {
                                        settings.defaultAbsoluteRingtoneName
                                    }) ?: stringResource(R.string.field_audio_default)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val previewing by audioPreview.playing
                        val previewUri = state.audioUri
                            ?: if (state.mode == AlarmMode.RELATIVE) {
                                settings.defaultRelativeRingtoneUri
                            } else {
                                settings.defaultAbsoluteRingtoneUri
                            }
                        IconButton(
                            onClick = {
                                if (previewing) audioPreview.stop() else audioPreview.play(previewUri)
                            },
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (previewing) R.drawable.ic_stop else R.drawable.ic_play,
                                ),
                                contentDescription = stringResource(
                                    if (previewing) {
                                        R.string.action_stop_preview
                                    } else {
                                        R.string.action_play_preview
                                    },
                                ),
                                tint = Color.Unspecified,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        OutlinedButton(onClick = pickAudio) {
                            Text(stringResource(R.string.field_audio_pick))
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = divider)

                    // Background image — large centered phone-frame preview
                    val previewTimeText = if (state.mode == AlarmMode.ABSOLUTE) {
                        TimeFormatter.formatHourMinute(context, state.hour, state.minute, is24h)
                    } else {
                        "--:--"
                    }
                    BackgroundImageRow(
                        uri = state.backgroundImageUri,
                        timeText = previewTimeText,
                        labelText = state.label.takeIf { it.isNotBlank() }.orEmpty(),
                        onPick = pickBackgroundImage,
                        onClear = { vm.updateBackgroundImage(null) },
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = divider)

                    // Vibration only
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_vibration),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.padding(end = 12.dp).size(24.dp),
                        )
                        Text(
                            text = stringResource(R.string.field_vibration_only),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = state.isVibrationOnly,
                            onCheckedChange = { vm.toggleVibrationOnly() },
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = divider)

                    // Snooze duration
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        SnoozeRow(value = state.snoozeMinutes, onChange = vm::updateSnoozeMinutes)
                    }
                    if (state.snoozeMinutes > Alarm.SNOOZE_DISABLED) {
                        HorizontalDivider(thickness = 0.5.dp, color = divider)
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            MaxSnoozeRow(
                                value = state.maxSnoozeCount,
                                onChange = vm::updateMaxSnoozeCount,
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = divider)

                    // Vibration pattern
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        VibrationPatternRow(
                            value = state.vibrationPattern,
                            onChange = vm::updateVibrationPattern,
                        )
                    }

                    // Volume ramp (hidden when vibration-only)
                    if (!state.isVibrationOnly) {
                        HorizontalDivider(thickness = 0.5.dp, color = divider)
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            VolumeRampRow(
                                value = state.volumeRampSeconds,
                                onChange = vm::updateVolumeRampSeconds,
                            )
                        }
                    }

                    // Self-destruct (one-shot alarms only)
                    val isOneShot = state.mode == AlarmMode.RELATIVE || state.daysOfWeek == 0
                    if (isOneShot) {
                        HorizontalDivider(thickness = 0.5.dp, color = divider)
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            SelfDestructRow(
                                checked = state.selfDestruct,
                                onToggle = vm::toggleSelfDestruct,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CancelSaveBar(
    onCancel: () -> Unit,
    onSave: () -> Unit,
    enabled: Boolean,
    isExisting: Boolean,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isExisting) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                }
                VerticalDivider(modifier = Modifier.height(28.dp))
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Text(stringResource(R.string.cancel))
            }
            VerticalDivider(modifier = Modifier.height(28.dp))
            TextButton(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Text(
                    text = stringResource(R.string.action_save),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun LabelUnderlineField(value: String, onValueChange: (String) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .drawBehind {
                drawLine(
                    color = onSurface.copy(alpha = 0.3f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.field_label_placeholder),
                        color = onSurface.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun RelativeRow(minutes: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_timer),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(end = 8.dp).size(24.dp),
            )
            Text(
                text = stringResource(R.string.field_relative_minutes),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RELATIVE_PRESETS.forEach { preset ->
                RelativeChip(
                    minutes = preset,
                    selected = preset == minutes,
                    onClick = { onChange(preset) },
                )
            }
        }
        // Custom field — accepts any value in [MIN_RELATIVE_MINUTES, MAX_RELATIVE_MINUTES].
        OutlinedTextField(
            value = minutes.toString(),
            onValueChange = { raw ->
                raw.toIntOrNull()?.takeIf {
                    it in Alarm.MIN_RELATIVE_MINUTES..Alarm.MAX_RELATIVE_MINUTES
                }?.let(onChange)
            },
            label = { Text(stringResource(R.string.field_relative_minutes_custom)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RelativeChip(minutes: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "$minutes", color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

private val RELATIVE_PRESETS = listOf(5, 15, 30, 60)

@Composable
private fun SelfDestructRow(checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_delete_forever),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.padding(end = 12.dp).size(24.dp),
        )
        Text(
            text = stringResource(R.string.field_self_destruct),
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
        )
    }
}

@Composable
private fun SnoozeRow(value: Int, onChange: (Int) -> Unit) {
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
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val label = if (minutes <= Alarm.SNOOZE_DISABLED) stringResource(R.string.field_snooze_off_chip) else "$minutes"
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

private val SNOOZE_PRESETS = listOf(
    Alarm.SNOOZE_DISABLED,
    Alarm.MIN_SNOOZE_MINUTES,
    5,
    Alarm.DEFAULT_SNOOZE_MINUTES,
    30,
)

@Composable
private fun MaxSnoozeRow(value: Int, onChange: (Int) -> Unit) {
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
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val label = if (count <= Alarm.MAX_SNOOZE_COUNT_UNLIMITED) {
        stringResource(R.string.field_max_snooze_unlimited)
    } else {
        "$count"
    }
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

private val MAX_SNOOZE_PRESETS = listOf(
    Alarm.MAX_SNOOZE_COUNT_UNLIMITED,
    1,
    3,
    5,
    10,
)

@Composable
private fun VibrationPatternRow(
    value: com.kirkouski.gtalarm.domain.VibrationPattern,
    onChange: (com.kirkouski.gtalarm.domain.VibrationPattern) -> Unit,
) {
    val current = patternLabelRes(value)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_vibration),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(end = 8.dp).size(24.dp),
            )
            Text(
                text = stringResource(R.string.field_vibration_pattern),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            com.kirkouski.gtalarm.domain.VibrationPattern.entries.forEach { pattern ->
                PatternChip(
                    label = stringResource(patternLabelRes(pattern)),
                    selected = pattern == value,
                    onClick = { onChange(pattern) },
                )
            }
        }
    }
}

private fun patternLabelRes(p: com.kirkouski.gtalarm.domain.VibrationPattern): Int = when (p) {
    com.kirkouski.gtalarm.domain.VibrationPattern.PULSE -> R.string.vibration_pattern_pulse
    com.kirkouski.gtalarm.domain.VibrationPattern.HEARTBEAT -> R.string.vibration_pattern_heartbeat
    com.kirkouski.gtalarm.domain.VibrationPattern.THREE_TAP -> R.string.vibration_pattern_three_tap
    com.kirkouski.gtalarm.domain.VibrationPattern.LONG_LONG -> R.string.vibration_pattern_long_long
    com.kirkouski.gtalarm.domain.VibrationPattern.OFF -> R.string.vibration_pattern_off
}

@Composable
private fun PatternChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun VolumeRampRow(value: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(end = 8.dp).size(24.dp),
            )
            Text(
                text = stringResource(R.string.field_volume_ramp),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (value <= 0) {
                    stringResource(R.string.field_volume_ramp_off)
                } else {
                    stringResource(R.string.field_volume_ramp_seconds, value)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VOLUME_RAMP_PRESETS.forEach { preset ->
                RampChip(
                    seconds = preset,
                    selected = preset == value,
                    onClick = { onChange(preset) },
                )
            }
        }
    }
}

@Composable
private fun RampChip(seconds: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val label = if (seconds <= 0) stringResource(R.string.field_volume_ramp_off) else "${seconds}s"
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

private val VOLUME_RAMP_PRESETS = listOf(0, 5, 15, 30, 60)

@Composable
private fun DeleteConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_alarm)) },
        text = { Text(stringResource(R.string.delete_alarm_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun DiscardConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discard_draft_title)) },
        text = { Text(stringResource(R.string.discard_draft_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.discard_draft_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_keep_editing))
            }
        },
    )
}

@Composable
private fun UnsavedChangesDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unsaved_changes_title)) },
        text = { Text(stringResource(R.string.unsaved_changes_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.unsaved_changes_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_keep_editing))
            }
        },
    )
}

// Flat letter day selector — active days shown in primary/bold, Sunday
// shown in error tint when inactive so it visually reads as a weekend marker.
@Composable
private fun DayRow(mask: Int, firstDayOverride: Int?, onToggle: (Int) -> Unit) {
    val orderedBits = rememberOrderedDayBits(firstDayOverride)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        orderedBits.forEach { bit ->
            val on = DaysOfWeek.contains(mask, bit)
            val isSunday = bit == DaysOfWeek.SUN
            val color = when {
                on -> MaterialTheme.colorScheme.primary
                isSunday -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onToggle(bit) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(shortLabelResForDayBit(bit)),
                    color = color,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

// reason: Box wraps preview + clear overlay; column + row wraps label +
// pick button. Both together push past 60 lines but splitting would require
// passing the URI decode state across functions for no readability gain.
@Suppress("LongMethod")
@Composable
private fun BackgroundImageRow(
    uri: String?,
    timeText: String,
    labelText: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(end = 12.dp).size(24.dp),
            )
            Text(
                text = stringResource(R.string.field_background_image),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onPick) {
                Text(stringResource(R.string.field_background_image_pick))
            }
        }
        Spacer(Modifier.height(12.dp))
        // Centre the phone-frame preview; overlay the × clear button at
        // the top-right corner of the frame itself (inner Box sizes to preview).
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box {
                RingScreenPreview(
                    backgroundUri = uri,
                    timeText = timeText,
                    labelText = labelText,
                    width = 140.dp,
                    height = 248.dp,
                )
                if (uri != null) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.field_background_image_clear),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
