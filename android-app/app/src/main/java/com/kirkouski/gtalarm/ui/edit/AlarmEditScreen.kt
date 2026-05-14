// reason: AlarmEditScreen is the single Compose entry for the edit UX and
// every section (Time, Label, Mode, Days, Audio, BackgroundImage,
// WatchBackgroundImage, Snooze, SelfDestruct, Save/Delete/Discard dialogs)
// is factored into a small private composable in the same file because
// they share state-hoisting from the VM. Splitting per-section into
// separate files would push the same state-routing across more files.
@file:Suppress("TooManyFunctions")

package com.kirkouski.gtalarm.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Image as FoundationImage
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.anhaki.picktime.PickHourMinute
import com.anhaki.picktime.utils.PickTimeFocusIndicator
import com.anhaki.picktime.utils.PickTimeTextStyle
import com.anhaki.picktime.utils.TimeFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import com.kirkouski.gtalarm.util.TimeFormatter
import com.kirkouski.gtalarm.util.rememberOrderedDayBits
import com.kirkouski.gtalarm.util.shortLabelResForDayBit

// reason: Compose top-level screen composables are inherently long because
// they declare a tree of layout, state hoisting, and side-effects in one
// function. Splitting AlarmEditScreen into private sub-composables is a
// Phase 3 polish item (docs/execution-plan.md §3a/§3b) once we settle on
// the final layout (predictive back, edge-to-edge, MaterialExpressiveTheme
// migration may all touch this file). Suppressing locally rather than
// disabling LongMethod globally — we still want it firing on non-UI code.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// reason: complexity rose past 10 because the reverse-save UX added two
// confirm-dialog states (delete + discard-new-draft) plus a Revert action
// that's only shown when state.isExistingAlarm && state.dirty, and the
// title resource picks between new/existing. Each branch maps to a distinct
// user-facing affordance (X / Revert / Delete / Discard); collapsing them
// would just smear the conditional into the @Composable body where it'd
// be harder to read at a glance.
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
    // The VM's onCleared() is a backstop that clears the editing-registry
    // flag if the screen is destroyed without an explicit Save/Cancel.
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
        // Stop any in-flight preview before swapping the audio — keeps the
        // user from hearing the old tone after picking a new one.
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

    val isNewDraft = !state.isExistingAlarm

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = if (isNewDraft) {
                        R.string.screen_edit_title_new
                    } else {
                        R.string.screen_edit_title_edit
                    }
                    Text(stringResource(title))
                },
                navigationIcon = {
                    IconButton(onClick = cancel) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    // Delete (existing alarms only): confirms then deletes
                    // the row + writes a tombstone. New drafts cancel via
                    // the X close icon — no DB row exists yet to delete.
                    if (state.isExistingAlarm) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.delete),
                                tint = Color.Unspecified,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    // Save: commits the in-memory edits to Room, pushes to
                    // watch, and exits. Disabled until loaded (state.id is
                    // still 0 and label is empty so a tap would write garbage).
                    IconButton(onClick = save, enabled = state.loaded) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.action_save),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Mode toggle [At time] / [In…] — only on new alarms. Existing
            // alarms keep their mode locked (per spec: switching types is a
            // create+delete operation, not an in-place edit).
            if (!state.isExistingAlarm) {
                ModeToggle(
                    mode = state.mode,
                    onChange = vm::updateMode,
                )
            }

            if (state.mode == AlarmMode.ABSOLUTE) {
                // Wheel/odometer time picker (PickTime-Compose, Apache-2.0).
                // 24h vs 12h follows the Settings override; the picker rolls
                // its own AM/PM column in 12h mode. We re-mount on state.id
                // change so the picker's initial values track an alarm load
                // (the lib's `initial*` params are remembered internally).
                val is24h = TimeFormatter.resolveUses24HourFormat(context, settings.use24Hour)
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    key(state.id, is24h) {
                        PickHourMinute(
                            initialHour = state.hour,
                            onHourChange = { h -> vm.updateTime(h, state.minute) },
                            initialMinute = state.minute,
                            onMinuteChange = { m -> vm.updateTime(state.hour, m) },
                            timeFormat = if (is24h) TimeFormat.HOUR_24 else TimeFormat.HOUR_12,
                            isLooping = true,
                            extraRow = 2,
                            containerColor = MaterialTheme.colorScheme.surface,
                            selectedTextStyle = PickTimeTextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 28.sp,
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Bold,
                            ),
                            unselectedTextStyle = PickTimeTextStyle(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 18.sp,
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                            ),
                            focusIndicator = PickTimeFocusIndicator(
                                enabled = true,
                                widthFull = false,
                                background = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                            ),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_repeat),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.padding(end = 8.dp).size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.field_repeat),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                DayRow(
                    mask = state.daysOfWeek,
                    firstDayOverride = settings.firstDayOfWeek,
                    onToggle = vm::toggleDay,
                )
            } else {
                RelativeRow(
                    minutes = state.relativeMinutes,
                    onChange = vm::updateRelativeMinutes,
                )
            }

            OutlinedTextField(
                value = state.label,
                onValueChange = vm::updateLabel,
                label = { Text(stringResource(R.string.field_label)) },
                placeholder = { Text(stringResource(R.string.field_label_placeholder)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_label),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.padding(end = 12.dp).size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.field_audio))
                    Text(
                        text = state.audioName ?: stringResource(R.string.field_audio_default),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val previewing by audioPreview.playing
                IconButton(
                    onClick = {
                        if (previewing) audioPreview.stop() else audioPreview.play(state.audioUri)
                    },
                ) {
                    Icon(
                        imageVector = if (previewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (previewing) R.string.action_stop_preview else R.string.action_play_preview,
                        ),
                    )
                }
                OutlinedButton(onClick = pickAudio) {
                    Text(stringResource(R.string.field_audio_pick))
                }
            }

            BackgroundImageRow(
                uri = state.backgroundImageUri,
                onPick = pickBackgroundImage,
                onClear = { vm.updateBackgroundImage(null) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
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

            SnoozeRow(
                value = state.snoozeMinutes,
                onChange = vm::updateSnoozeMinutes,
            )

            // Self-destruct: only visible when the alarm is one-shot. Hidden
            // entirely (not greyed out) for recurring per the spec — recurring
            // + self-destruct is illegal and there's no UX for it.
            val isOneShot = state.mode == AlarmMode.RELATIVE || state.daysOfWeek == 0
            if (isOneShot) {
                SelfDestructRow(
                    checked = state.selfDestruct,
                    onToggle = vm::toggleSelfDestruct,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeToggle(mode: AlarmMode, onChange: (AlarmMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        AlarmMode.entries.forEachIndexed { idx, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(idx, AlarmMode.entries.size),
            ) {
                val labelRes = when (m) {
                    AlarmMode.ABSOLUTE -> R.string.alarm_mode_absolute
                    AlarmMode.RELATIVE -> R.string.alarm_mode_relative
                }
                Text(stringResource(labelRes))
            }
        }
    }
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
        // Updates state only when the input is a valid integer in range; otherwise
        // the prior value persists.
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_snooze),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.padding(end = 12.dp).size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.field_snooze_minutes))
            Text(
                text = if (value <= Alarm.SNOOZE_DISABLED) {
                    stringResource(R.string.field_snooze_off_value)
                } else {
                    stringResource(R.string.field_snooze_minutes_value, value)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Picker is a row of preset chips. Range-aware: presets within
        // [MIN, MAX] are shown; the live `value` highlights whichever
        // preset matches. Custom non-preset values (set via wire format /
        // migration) just unhighlight all chips but stay valid.
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

// Compact preset list: 0 ("Off"), 1 (debug-friendly), 5, 10 (legacy default), 30.
// Values outside this list still flow through the wire format / migration —
// SnoozeRow's "$value min" text always reflects the actual state.
private val SNOOZE_PRESETS = listOf(
    Alarm.SNOOZE_DISABLED,
    Alarm.MIN_SNOOZE_MINUTES,
    5,
    Alarm.DEFAULT_SNOOZE_MINUTES,
    30,
)

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

@Composable
private fun DayRow(mask: Int, firstDayOverride: Int?, onToggle: (Int) -> Unit) {
    val orderedBits = rememberOrderedDayBits(firstDayOverride)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        orderedBits.forEach { bit ->
            val on = DaysOfWeek.contains(mask, bit)
            val bg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onToggle(bit) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = stringResource(shortLabelResForDayBit(bit)), color = fg)
            }
        }
    }
}

@Composable
private fun BackgroundImageRow(
    uri: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.field_background_image))
            // Show a small thumbnail preview when a URI is bound. The
            // bitmap loader is shared with the AlarmActivity full-screen
            // background, so the decode cost is paid once per edit-session
            // recomposition and stays cheap (single small inputstream).
            val bitmapState = rememberBackgroundBitmap(uri)
            val bm = bitmapState.value
            if (uri != null && bm != null) {
                FoundationImage(
                    bitmap = bm,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            } else {
                Text(
                    text = stringResource(R.string.field_background_image_none),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (uri != null) {
            // Clear restores "use default" (null in the alarm row → fall
            // back to SettingsStore.defaultPhoneBackgroundUri).
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.field_background_image_clear),
                )
            }
        }
        OutlinedButton(onClick = onPick) {
            Text(stringResource(R.string.field_background_image_pick))
        }
    }
}
