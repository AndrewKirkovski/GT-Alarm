// reason: AlarmEditScreen is the single Compose entry for the edit UX and
// every section (Time, Label, Mode, Days, Audio, BackgroundImage,
// WatchBackgroundImage, Snooze, SelfDestruct, Save/Delete/Discard dialogs)
// is factored into a small private composable in the same file because
// they share state-hoisting from the VM. Splitting per-section into
// separate files would push the same state-routing across more files.
@file:Suppress("TooManyFunctions")

package com.kirkouski.gtwake.companion.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anhaki.picktime.utils.PickTimeFocusIndicator
import com.anhaki.picktime.utils.PickTimeTextStyle
import com.anhaki.picktime.utils.TimeFormat
import com.kirkouski.gtwake.companion.ui.components.GtAccentButton
import com.kirkouski.gtwake.companion.ui.picktime.GtPickHourMinute
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import com.kirkouski.gtwake.companion.util.TimeFormatter
import com.kirkouski.gtwake.companion.util.rememberOrderedDayBits
import com.kirkouski.gtwake.companion.util.shortLabelResForDayBit

// reason: Compose top-level screen composables are inherently long because
// they declare a tree of layout, state hoisting, and side-effects in one
// function. Splitting AlarmEditScreen into private sub-composables is a
// Phase 3 polish item once the layout stabilises. Suppressing locally rather
// than disabling LongMethod globally.
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun AlarmEditScreen(
    alarmId: Long?,
    initialMode: AlarmMode = AlarmMode.ABSOLUTE,
    onDone: () -> Unit,
    vm: AlarmEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(alarmId) { vm.load(alarmId, initialMode) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showUnsavedConfirm by remember { mutableStateOf(false) }
    var showPhoneBackgroundPicker by remember { mutableStateOf(false) }

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
    val is24h = TimeFormatter.resolveUses24HourFormat(context, settings.use24Hour)
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
    if (showPhoneBackgroundPicker) {
        val previewTimeText = if (state.mode == AlarmMode.ABSOLUTE) {
            TimeFormatter.formatHourMinute(context, state.hour, state.minute, is24h)
        } else {
            "--:--"
        }
        PhoneBackgroundPickerDialog(
            cacheKey = if (state.id > 0L) "alarm_${state.id}" else "draft",
            initialUri = state.backgroundImageUri ?: settings.defaultPhoneBackgroundUri,
            timeText = previewTimeText,
            labelText = state.label.takeIf { it.isNotBlank() }.orEmpty(),
            onDismiss = { showPhoneBackgroundPicker = false },
            onSaved = { uri ->
                showPhoneBackgroundPicker = false
                vm.updateBackgroundImage(uri)
            },
        )
    }

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

        val navBarPaddingDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val density = LocalDensity.current
        // Initialise to 280 dp so content is positioned correctly before the first layout pass.
        var pickerHeightPx by remember { mutableIntStateOf(with(density) { 280.dp.roundToPx() }) }

        // Outer Box: layout anchor. Time picker overlays as a fixed header at the top.
        // MainActivity's NavHost already pads by calculateTopPadding() — no statusBarsPadding() here.
        Box(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxSize(),
        ) {
            // Inner Box: top+bottom fade mask on the scrollable content.
            // Top fade hides content scrolling under the time picker.
            // Bottom fade hides content scrolling under the pill.
            // See docs/ui-design-rules.md.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val topFadeEnd = pickerHeightPx.toFloat()
                        val topFadeStart = (topFadeEnd - 40.dp.toPx()).coerceAtLeast(0f)
                        val bottomFadeStart = size.height - navBarPaddingDp.toPx() - 40.dp.toPx()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                (topFadeStart / size.height).coerceIn(0f, 1f) to Color.Transparent,
                                (topFadeEnd / size.height).coerceIn(0f, 1f) to Color.Black,
                                (bottomFadeStart / size.height).coerceIn(0f, 1f) to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
            // Space reserved for the fixed time picker above.
            // The picker itself is placed after this Box (higher z-order).
            Spacer(Modifier.height(with(density) { pickerHeightPx.toDp() }))

            // Settings card — wraps content height; whole screen scrolls. See docs/ui-design-rules.md.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                val divider = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                Column {
                    ModeTabRow(
                        selectedMode = state.mode,
                        onSelected = vm::updateMode,
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = divider)

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

                    // Vibration-only gates audio; keep the two controls adjacent.
                    VibrationOnlyRow(
                        checked = state.isVibrationOnly,
                        onToggle = vm::toggleVibrationOnly,
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = divider)
                    if (!state.isVibrationOnly) {
                        val previewing by audioPreview.playing
                        AudioRow(
                            audioName = if (state.audioUri != null) {
                                state.audioName ?: stringResource(R.string.field_audio_unknown)
                            } else {
                                (if (state.mode == AlarmMode.RELATIVE) {
                                    settings.defaultRelativeRingtoneName
                                } else {
                                    settings.defaultAbsoluteRingtoneName
                                }) ?: stringResource(R.string.field_audio_app_default)
                            },
                            previewing = previewing,
                            onPreviewClick = {
                                val previewUri = state.audioUri
                                    ?: if (state.mode == AlarmMode.RELATIVE) {
                                        settings.defaultRelativeRingtoneUri
                                    } else {
                                        settings.defaultAbsoluteRingtoneUri
                                    }
                                if (previewing) audioPreview.stop() else audioPreview.play(previewUri)
                            },
                            onPick = pickAudio,
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = divider)
                    }

                    // Background image — large centered phone-frame preview
                    val previewTimeText = if (state.mode == AlarmMode.ABSOLUTE) {
                        TimeFormatter.formatHourMinute(context, state.hour, state.minute, is24h)
                    } else {
                        "--:--"
                    }
                    BackgroundImageRow(
                        uri = state.backgroundImageUri,
                        appDefaultUri = settings.defaultPhoneBackgroundUri,
                        timeText = previewTimeText,
                        labelText = state.label.takeIf { it.isNotBlank() }.orEmpty(),
                        onPick = { showPhoneBackgroundPicker = true },
                        onClear = { vm.updateBackgroundImage(null) },
                    )
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

                }
            }
            // Outer spacer (outside card) gives extra scroll space so card bottom
            // rounds above the pill. See docs/ui-design-rules.md.
            Spacer(Modifier.height(navBarPaddingDp + 80.dp))
            } // end scrollable Column
            } // end inner faded Box

            // Fixed time picker — stays at top while content scrolls beneath.
            // onSizeChanged drives the top-fade boundary and the spacer in the column above.
            if (state.mode == AlarmMode.ABSOLUTE) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .onSizeChanged { pickerHeightPx = it.height },
                    contentAlignment = Alignment.Center,
                ) {
                    key(state.id, is24h) {
                        GtPickHourMinute(
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .onSizeChanged { pickerHeightPx = it.height },
                    contentAlignment = Alignment.Center,
                ) {
                    RelativeRow(
                        minutes = state.relativeMinutes,
                        onChange = vm::updateRelativeMinutes,
                    )
                }
            }
        } // end outer Box
    }
}

@Composable
private fun ModeTabRow(
    selectedMode: AlarmMode,
    onSelected: (AlarmMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        ModeTab(
            text = stringResource(R.string.alarm_notification_title),
            selected = selectedMode == AlarmMode.ABSOLUTE,
            onClick = { onSelected(AlarmMode.ABSOLUTE) },
            shape = RoundedCornerShape(topStart = 20.dp),
            modifier = Modifier.weight(1f),
        )
        ModeTab(
            text = stringResource(R.string.screen_timer_title),
            selected = selectedMode == AlarmMode.RELATIVE,
            onClick = { onSelected(AlarmMode.RELATIVE) },
            shape = RoundedCornerShape(topEnd = 20.dp),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

// reason: black pill layout — 5 params + optional delete branch + two dividers = inherently long.
@Suppress("LongMethod")
@Composable
private fun CancelSaveBar(
    onCancel: () -> Unit,
    onSave: () -> Unit,
    enabled: Boolean,
    isExisting: Boolean,
    onDelete: () -> Unit,
) {
    val spreadColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
    } else {
        lerp(Color.White, MaterialTheme.colorScheme.tertiary, 0.14f).copy(alpha = 0.86f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .spreadShadow(spread = 5.dp, radius = 28.dp, color = spreadColor),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
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
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun Modifier.spreadShadow(spread: Dp, radius: Dp, color: Color): Modifier = drawBehind {
    val spreadPx = spread.toPx()
    val radiusPx = radius.toPx() + spreadPx
    drawRoundRect(
        color = color,
        topLeft = Offset(-spreadPx, -spreadPx),
        size = Size(size.width + spreadPx * 2f, size.height + spreadPx * 2f),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
    )
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
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
private fun VibrationOnlyRow(checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            checked = checked,
            onCheckedChange = { onToggle() },
        )
    }
}

@Composable
private fun AudioRow(
    audioName: String,
    previewing: Boolean,
    onPreviewClick: () -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                text = audioName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onPreviewClick) {
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
        GtAccentButton(onClick = onPick) {
            Text(stringResource(R.string.field_audio_pick))
        }
    }
}

@Composable
private fun RelativeRow(minutes: Int, onChange: (Int) -> Unit) {
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
private fun VibrationPatternRow(
    value: com.kirkouski.gtwake.companion.domain.VibrationPattern,
    onChange: (com.kirkouski.gtwake.companion.domain.VibrationPattern) -> Unit,
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
            com.kirkouski.gtwake.companion.domain.VibrationPattern.entries.forEach { pattern ->
                PatternChip(
                    label = stringResource(patternLabelRes(pattern)),
                    selected = pattern == value,
                    onClick = { onChange(pattern) },
                )
            }
        }
    }
}

private fun patternLabelRes(p: com.kirkouski.gtwake.companion.domain.VibrationPattern): Int = when (p) {
    com.kirkouski.gtwake.companion.domain.VibrationPattern.PULSE -> R.string.vibration_pattern_pulse
    com.kirkouski.gtwake.companion.domain.VibrationPattern.HEARTBEAT -> R.string.vibration_pattern_heartbeat
    com.kirkouski.gtwake.companion.domain.VibrationPattern.THREE_TAP -> R.string.vibration_pattern_three_tap
    com.kirkouski.gtwake.companion.domain.VibrationPattern.LONG_LONG -> R.string.vibration_pattern_long_long
    com.kirkouski.gtwake.companion.domain.VibrationPattern.OFF -> R.string.vibration_pattern_off
}

@Composable
private fun PatternChip(label: String, selected: Boolean, onClick: () -> Unit) {
    OptionChip(label = label, selected = selected, onClick = onClick)
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
    val label = if (seconds <= 0) stringResource(R.string.field_volume_ramp_off) else "${seconds}s"
    OptionChip(label = label, selected = selected, onClick = onClick)
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .height(36.dp)
            .defaultMinSize(minWidth = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
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
    appDefaultUri: String?,
    timeText: String,
    labelText: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    // Effective URI for the preview: per-alarm override → app default → null (gradient placeholder).
    val previewUri = uri ?: appDefaultUri
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.field_background_image),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (uri == null) {
                    Text(
                        text = stringResource(R.string.field_background_image_default),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            GtAccentButton(onClick = onPick) {
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
                    backgroundUri = previewUri,
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
