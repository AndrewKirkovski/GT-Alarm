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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    val settings by vm.settings.collectAsStateWithLifecycle()
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
    var showWatchBgPicker by remember { mutableStateOf(false) }

    BackHandler { exit() }

    val timeState = rememberTimePickerState(
        initialHour = state.hour,
        initialMinute = state.minute,
        // 24h vs 12h: explicit Settings override wins; otherwise follows the
        // device locale (matches TimeFormatter on the list screen so the
        // user doesn't see one format here and a different one in the
        // subtitle).
        is24Hour = TimeFormatter.resolveUses24HourFormat(context, settings.use24Hour),
    )

    LaunchedEffect(timeState) {
        snapshotFlow { timeState.hour to timeState.minute }
            .distinctUntilChanged()
            .collect { (h, m) -> vm.updateTime(h, m) }
    }

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

    if (showWatchBgPicker) {
        // The picker writes a 466 × 466 cropped PNG to cacheDir keyed by
        // alarm id. We feed that file:// URI back into the alarm row via
        // [AlarmEditViewModel.updateWatchBackgroundImage]; the parallel
        // BGRA `.bin` (also written by the picker) is uploaded to the
        // watch on screen exit via flushPendingToWatch.
        WatchBackgroundPickerDialog(
            alarmId = state.id,
            initialUri = state.watchBackgroundImageUri,
            onDismiss = { showWatchBgPicker = false },
            onSaved = { uri ->
                showWatchBgPicker = false
                vm.updateWatchBackgroundImage(uri)
            },
        )
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

            // Per-alarm watch-side background image. Distinct from the
            // phone bg because it must be cropped to a circular 466 × 466
            // region with a watch-UI preview overlay (the watch's <image>
            // element renders a BGRA blob — see WatchBackgroundEncoder).
            // The picker writes both the PNG (for re-display) + .bin (for
            // upload) into cacheDir; we only persist the PNG URI here.
            WatchBackgroundImageRow(
                uri = state.watchBackgroundImageUri,
                onEdit = { showWatchBgPicker = true },
                onClear = { vm.updateWatchBackgroundImage(null) },
            )

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
private fun WatchBackgroundImageRow(
    uri: String?,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Watch,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.field_watch_background_image))
            val bitmapState = rememberBackgroundBitmap(uri)
            val bm = bitmapState.value
            if (uri != null && bm != null) {
                // Circular thumbnail — matches how the bg ends up
                // rendered on the watch's circular display.
                FoundationImage(
                    bitmap = bm,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(48.dp)
                        .clip(CircleShape),
                )
            } else {
                Text(
                    text = stringResource(R.string.field_watch_background_image_none),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (uri != null) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.field_watch_background_image_clear),
                )
            }
        }
        OutlinedButton(onClick = onEdit) {
            Text(stringResource(R.string.field_watch_background_image_edit))
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
            imageVector = Icons.Default.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
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
