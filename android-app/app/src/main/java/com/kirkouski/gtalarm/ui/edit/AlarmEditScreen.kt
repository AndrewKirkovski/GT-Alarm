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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import com.kirkouski.gtalarm.util.TimeFormatter
import com.kirkouski.gtalarm.util.rememberLocaleOrderedDayBits
import com.kirkouski.gtalarm.util.shortLabelResForDayBit
import kotlinx.coroutines.flow.distinctUntilChanged

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
    val context = LocalContext.current

    // Wrap onDone so every exit path (back gesture, X button, system nav)
    // flushes the pending edits to the watch ONCE before navigating. The
    // VM's onCleared() is a backstop for process-death paths.
    val exit: () -> Unit = remember(onDone, vm) {
        {
            vm.flushPendingToWatch()
            onDone()
        }
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    BackHandler { exit() }

    val timeState = rememberTimePickerState(
        initialHour = state.hour,
        initialMinute = state.minute,
        // 24h vs 12h follows the device locale — matches TimeFormatter on
        // the list screen so the user doesn't see one format here and a
        // different one in the subtitle.
        is24Hour = TimeFormatter.uses24HourFormat(context),
    )

    LaunchedEffect(timeState) {
        snapshotFlow { timeState.hour to timeState.minute }
            .distinctUntilChanged()
            .collect { (h, m) -> vm.updateTime(h, m) }
    }

    val pickAudio = rememberAudioPicker { picked ->
        vm.updateAudio(picked.uri, picked.name)
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
                vm.discardNewDraft()
                onDone()
            },
            onDismiss = { showDiscardConfirm = false },
        )
    }

    // Existing alarm has an openSnapshot in the VM -> Revert button works.
    // New draft has no snapshot -> the destructive top-bar action becomes
    // "discard" (delete the in-progress row) instead of "delete".
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
                    IconButton(onClick = exit) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    // Revert button: shown when there are unsaved changes
                    // since the screen opened. Semantics depend on source:
                    //   existing dirty → restore openSnapshot, stay on screen
                    //   new draft dirty → confirm discard, delete row + exit
                    // Both flows are non-destructive of explicitly-final
                    // state: existing edits can be redone; the new draft's
                    // only consumer is the user who's about to throw it away.
                    if (state.dirty) {
                        IconButton(onClick = {
                            if (isNewDraft) showDiscardConfirm = true else vm.revert()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = stringResource(R.string.action_revert),
                            )
                        }
                    }
                    // Delete (existing alarms only): confirms then deletes
                    // the row + writes a tombstone. New drafts use Revert
                    // (above) for the same outcome — no second destructive
                    // affordance needed.
                    if (state.isExistingAlarm) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                            )
                        }
                    }
                    // Save: visually reassuring "commit" affordance. Does
                    // exactly the same as the X button or system back — the
                    // reverse-save model has already persisted every edit;
                    // this just flushes the pending watch push and exits.
                    IconButton(onClick = exit) {
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
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timeState)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.field_repeat),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                DayRow(
                    mask = state.daysOfWeek,
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
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.field_audio))
                    Text(
                        text = state.audioName ?: stringResource(R.string.field_audio_default),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = pickAudio) {
                    Text(stringResource(R.string.field_audio_pick))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Vibration,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
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
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
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
            imageVector = Icons.Default.DeleteForever,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
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
            imageVector = Icons.Default.Snooze,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.field_snooze_minutes))
            Text(
                text = stringResource(R.string.field_snooze_minutes_value, value),
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
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "$minutes", color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

// Compact preset list: 1 (debug-friendly), 5, 10 (legacy default), 30.
// Values outside this list still flow through the wire format / migration —
// SnoozeRow's "$value min" text always reflects the actual state.
private val SNOOZE_PRESETS = listOf(
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
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun DayRow(mask: Int, onToggle: (Int) -> Unit) {
    val orderedBits = rememberLocaleOrderedDayBits()
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
