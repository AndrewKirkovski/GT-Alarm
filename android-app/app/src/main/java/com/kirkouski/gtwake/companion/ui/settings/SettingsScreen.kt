// reason: Settings screen + several private subcomposables (DisplaySection,
// RingtoneSection, RingtoneRow, FirstDayDropdown, TwentyFourHourSelector)
// live in one file because they share the SettingsViewModel state surface
// and have no reuse outside Settings. Splitting per-component would push
// VM-state routing across more files for zero readability gain.
@file:Suppress("TooManyFunctions")

package com.kirkouski.gtwake.companion.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.data.SettingsState
import com.kirkouski.gtwake.companion.ui.components.GtFloatingButton
import com.kirkouski.gtwake.companion.ui.components.GtSegmentOption
import com.kirkouski.gtwake.companion.ui.components.GtSegmentedControl
import com.kirkouski.gtwake.companion.ui.components.GtSnackbarHost
import com.kirkouski.gtwake.companion.ui.components.MediaChoiceRow
import com.kirkouski.gtwake.companion.ui.components.SoundPreviewButton
import com.kirkouski.gtwake.companion.ui.components.defaultPictureChoice
import com.kirkouski.gtwake.companion.ui.components.defaultSoundChoice
import com.kirkouski.gtwake.companion.ui.components.pictureChoiceText
import com.kirkouski.gtwake.companion.ui.components.removable
import com.kirkouski.gtwake.companion.ui.components.showUndo
import com.kirkouski.gtwake.companion.ui.components.soundChoiceText
import com.kirkouski.gtwake.companion.ui.edit.PhoneBackgroundPickerDialog
import com.kirkouski.gtwake.companion.ui.edit.RingScreenPreview
import com.kirkouski.gtwake.companion.ui.edit.WatchBackgroundPickerDialog
import com.kirkouski.gtwake.companion.wear.WatchCropProfile
import com.kirkouski.gtwake.companion.wear.WatchSupport
import com.kirkouski.gtwake.companion.wear.WatchSupportState
import com.kirkouski.gtwake.companion.wear.WatchSyncStatus
import com.kirkouski.gtwake.companion.wear.WatchScreenProfiles
import com.kirkouski.gtwake.companion.ui.edit.rememberAudioPicker
import com.kirkouski.gtwake.companion.ui.edit.rememberAudioPreview
import com.kirkouski.gtwake.companion.ui.edit.rememberBackgroundBitmap
import com.kirkouski.gtwake.companion.util.firstCalendarDayWithOverride
import com.kirkouski.gtwake.companion.util.longDayName
import kotlinx.coroutines.launch
import java.util.Calendar

private typealias UndoSnackbar = (message: String, undo: () -> Unit) -> Unit

// reason: Scaffold + Box fade wrapper + three card sections = inherently long
// for a settings screen; splitting adds indirection with no reuse benefit.
@Suppress("LongMethod")
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Global, monitor-maintained status read instantly from the bridge StateFlow
    // (no per-screen request, no NOT_CONNECTED-initial flicker). Treat a watch as
    // "present" unless it's definitively NOT_CONNECTED — the transient CONNECTING
    // (resolving the device) and ERROR (a 206 send hiccup) states must NOT hide
    // the block, or a background send during a crop would yank it.
    val watchStatus by vm.watchStatus.collectAsStateWithLifecycle()
    val watchConnected = watchStatus != WatchSyncStatus.NOT_CONNECTED
    val context = LocalContext.current
    // The list-sync switch only means something where Wear Engine works at
    // all; on an unsupported phone the list screen already explains that.
    val watchSupported = remember { WatchSupport.state(context) == WatchSupportState.Supported }
    LaunchedEffect(Unit) {
        vm.watchListSyncResults.collect { (enabled, result) ->
            Toast.makeText(context, watchListSyncToast(context, enabled, result), Toast.LENGTH_LONG).show()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoText = stringResource(R.string.action_undo)
    val showUndo: UndoSnackbar = { message, undo ->
        scope.launch { snackbarHostState.showUndo(message, undoText, undo) }
    }
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Lifted above the bottom pill, which floats over this screen.
            GtSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 76.dp,
                ),
            )
        },
    ) { padding ->
        val navBarPaddingDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val density = LocalDensity.current
        val fadeEdgePx = remember(navBarPaddingDp) { with(density) { (navBarPaddingDp + 38.dp).toPx() } }
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startY = size.height - fadeEdgePx,
                            endY = size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(stringResource(R.string.settings_section_display))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DisplaySection(state = state, vm = vm)
                    }
                }
            }
            if (watchSupported && state.watchListSyncUnlocked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader(stringResource(R.string.settings_section_watch))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            WatchListSyncSection(
                                listSyncOn = state.effectiveWatchListSync,
                                onChange = vm::setWatchListSync,
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(stringResource(R.string.settings_section_default_ringtones))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        RingtoneSection(state = state, vm = vm, showUndo = showUndo)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(stringResource(R.string.settings_section_default_backgrounds))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        BackgroundSection(
                            state = state,
                            vm = vm,
                            watchConnected = watchConnected,
                            showUndo = showUndo,
                            showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                        )
                    }
                }
            }
            Spacer(
                Modifier.height(
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp,
                ),
            )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BackgroundSection(
    state: SettingsState,
    vm: SettingsViewModel,
    watchConnected: Boolean,
    showUndo: UndoSnackbar,
    showMessage: (String) -> Unit,
) {
    var pendingPictureAction by remember { mutableStateOf<WatchPictureAction?>(null) }
    var watchReloadToken by remember { mutableIntStateOf(0) }
    val removing by vm.watchPictureRemoving.collectAsStateWithLifecycle()
    val savedMessage = stringResource(R.string.settings_watch_background_saved)
    WatchPictureRemovalMessages(vm, showMessage)
    val watchProfile = WatchScreenProfiles.resolve(
        state.watchScreenWidth,
        state.watchScreenHeight,
        state.watchScreenShape,
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.settings_default_backgrounds_intro),
            style = MaterialTheme.typography.bodySmall,
        )
        PhonePictureRow(
            currentUri = state.defaultPhoneBackgroundUri,
            vm = vm,
            showUndo = showUndo,
        )
        // Shown while a watch is connected, and also while a picture is set,
        // so a set picture never looks gone. The status is read from the global
        // monitor-maintained StateFlow, so it no longer flickers hidden→shown.
        if (watchConnected || state.defaultWatchBackgroundUri != null) {
            WatchBackgroundRow(
                currentUri = state.defaultWatchBackgroundUri,
                watchConnected = watchConnected,
                enabled = watchConnected && !removing,
                onChange = { pendingPictureAction = WatchPictureAction.CHANGE },
                onClear = { pendingPictureAction = WatchPictureAction.CLEAR },
            ) {
                WatchFacePreview(
                    backgroundUri = state.defaultWatchBackgroundUri,
                    profile = watchProfile,
                    reloadKey = watchReloadToken,
                    onPickEmpty = {
                        if (watchConnected && !removing) pendingPictureAction = WatchPictureAction.CHANGE
                    },
                )
            }
        }
    }
    WatchPictureDialogs(
        currentUri = state.defaultWatchBackgroundUri,
        vm = vm,
        pending = pendingPictureAction,
        onPendingDone = { pendingPictureAction = null },
        watchProfile = watchProfile,
        onSaved = {
            watchReloadToken++
            showMessage(savedMessage)
        },
    )
}

// The picker's open-state lives HERE, OUTSIDE the conditional watch-bg row.
// CRITICAL: if the dialog lived inside the conditional and the status flickered
// during the multi-second crop encode, the row (and the cropper's coroutine
// scope) would be removed from composition and the save would be CANCELLED
// after writing the crop files but before onSaved fired, so the upload never
// ran. Keeping it here means an open crop survives any status change.
@Composable
private fun WatchPictureDialogs(
    currentUri: String?,
    vm: SettingsViewModel,
    pending: WatchPictureAction?,
    onPendingDone: () -> Unit,
    watchProfile: WatchCropProfile,
    onSaved: () -> Unit,
) {
    var showWatchPicker by remember { mutableStateOf(false) }
    WatchPictureConsent(
        pending = pending,
        onConfirm = { action ->
            onPendingDone()
            if (action == WatchPictureAction.CHANGE) {
                vm.prepareWatchPicturePicker { showWatchPicker = true }
            } else {
                vm.removeDefaultWatchBackground()
            }
        },
        onDismiss = onPendingDone,
    )
    if (showWatchPicker) {
        WatchBackgroundPickerDialog(
            alarmId = DEFAULT_WATCH_BG_ID,
            initialUri = currentUri,
            onDismiss = { showWatchPicker = false },
            onSaved = { uri ->
                showWatchPicker = false
                vm.setDefaultWatchBackground(uri)
                onSaved()
            },
            profile = watchProfile,
        )
    }
}

@Composable
private fun WatchPictureRemovalMessages(vm: SettingsViewModel, showMessage: (String) -> Unit) {
    val removedMessage = stringResource(R.string.picture_removed)
    val failedMessage = stringResource(R.string.settings_watch_picture_remove_failed)
    LaunchedEffect(Unit) {
        vm.watchPictureRemoveResults.collect { removed ->
            showMessage(if (removed) removedMessage else failedMessage)
        }
    }
}

/**
 * Preview for the watch-default picture, shaped to the connected watch
 * ([WatchScreenProfiles.resolve] off the reported screen — circle for round GT,
 * rounded-rect for FIT), so the user gets a shape-accurate preview. The picker
 * keys its cached PNG/.bin by alarm id; we pass [DEFAULT_WATCH_BG_ID] (a sentinel
 * below Room's positive id range) so the default's cache file (`watch_bg_-1.png`)
 * can't collide with a real alarm's crop.
 */
@Composable
private fun WatchFacePreview(
    backgroundUri: String?,
    profile: WatchCropProfile,
    reloadKey: Any? = null,
    onPickEmpty: (() -> Unit)? = null,
) {
    val bm = rememberBackgroundBitmap(backgroundUri, reloadKey).value
    // Shape + frame follow the connected watch (matching the cropper): circle
    // for round GT, rounded-rect for FIT. Keep the longest side at 140dp so a
    // portrait FIT frame is narrower + taller, not 140×140 distorted.
    val shape = if (profile.round) CircleShape else RoundedCornerShape(24.dp)
    val aspect = profile.outWidth.toFloat() / profile.outHeight.toFloat()
    val maxSide = 140.dp
    val frameWidth = if (aspect >= 1f) maxSide else maxSide * aspect
    val frameHeight = if (aspect >= 1f) maxSide / aspect else maxSide
    // When empty, the whole frame is a tap target that opens the picker
    // (matches the cropper's EmptyCropFrame affordance). "Empty" = no URI set —
    // NOT "bitmap not decoded yet": rememberBackgroundBitmap is async, so a set
    // image is bm==null during decode (and permanently if the file is broken).
    // Gating on the URI keeps a loading/broken set-image from becoming a
    // pick-me tap target while the header already shows "Set" + Clear.
    val isEmpty = backgroundUri == null
    val tappableEmpty = isEmpty && onPickEmpty != null
    Box(
        modifier = Modifier
            .size(width = frameWidth, height = frameHeight)
            .then(
                if (tappableEmpty) Modifier.clip(shape).clickable { onPickEmpty() }
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bm != null) {
            Image(
                bitmap = bm,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else if (isEmpty) {
            // No custom bg set → show ONLY the default gradient the watch shows
            // when it has no image (watch common/default-bg.png). The watch shows
            // NO icon over its background, so neither does this preview. The frame
            // is still tappable (outer Box clickable) to open the picker.
            Image(
                painter = painterResource(R.drawable.watch_default_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else {
            // URI set but the bitmap is still decoding (transient) → neutral fill.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(Color.Black),
            )
        }
        // Round watch-UI overlay only for recognized round panels — matches
        // the cropper rule (we have no rect overlay asset).
        if (profile.showOverlay) {
            Image(
                painter = painterResource(R.drawable.watch_overlay),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WatchBackgroundRow(
    currentUri: String?,
    watchConnected: Boolean,
    enabled: Boolean,
    onChange: () -> Unit,
    onClear: () -> Unit,
    preview: @Composable () -> Unit,
) {
    // NOTE: the picker dialog + open-state are owned by the CALLER
    // (BackgroundSection), OUTSIDE the conditional block, so an in-progress crop
    // save survives a connection-status flicker. This row only renders the
    // controls + preview and reports taps.
    val choice = defaultPictureChoice(currentUri)
    MediaChoiceRow(
        iconRes = R.drawable.ic_image,
        title = stringResource(R.string.settings_default_watch_background),
        value = pictureChoiceText(choice),
        removable = choice.removable,
        onChoose = onChange,
        onRemove = onClear,
        enabled = enabled,
        preview = {
            if (!watchConnected) {
                Text(
                    text = stringResource(R.string.settings_watch_picture_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                preview()
            }
        },
    )
}

@Composable
private fun PhonePictureRow(currentUri: String?, vm: SettingsViewModel, showUndo: UndoSnackbar) {
    val removedMessage = stringResource(R.string.picture_removed)
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        PhoneBackgroundPickerDialog(
            cacheKey = "default_phone",
            initialUri = currentUri,
            timeText = "--:--",
            labelText = "",
            onDismiss = { showPicker = false },
            onSaved = { uri ->
                showPicker = false
                vm.setDefaultPhoneBackground(uri)
            },
        )
    }
    val choice = defaultPictureChoice(currentUri)
    MediaChoiceRow(
        iconRes = R.drawable.ic_image,
        title = stringResource(R.string.settings_default_phone_background),
        value = pictureChoiceText(choice),
        removable = choice.removable,
        onChoose = { showPicker = true },
        onRemove = {
            vm.setDefaultPhoneBackground(null)
            showUndo(removedMessage) { vm.setDefaultPhoneBackground(currentUri) }
        },
        preview = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                RingScreenPreview(
                    backgroundUri = currentUri,
                    timeText = "--:--",
                    labelText = "",
                    modifier = Modifier.clickable { showPicker = true },
                    width = 140.dp,
                    height = 248.dp,
                )
            }
        },
    )
}

@Composable
private fun DisplaySection(state: SettingsState, vm: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.settings_time_format_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        TwentyFourHourSelector(
            value = state.use24Hour,
            onChange = vm::setUse24Hour,
        )

        Text(
            text = stringResource(R.string.settings_first_day_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        FirstDayDropdown(
            value = state.firstDayOfWeek,
            onChange = vm::setFirstDayOfWeek,
        )
    }
}

@Composable
private fun TwentyFourHourSelector(
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val options: List<GtSegmentOption<Boolean?>> = listOf(
        GtSegmentOption(null, stringResource(R.string.settings_time_format_system)),
        GtSegmentOption(false, stringResource(R.string.settings_time_format_12h)),
        GtSegmentOption(true, stringResource(R.string.settings_time_format_24h)),
    )
    GtSegmentedControl(
        options = options,
        selectedValue = value,
        onSelected = onChange,
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color.White,
        unselectedContentColor = if (isDark) MaterialTheme.colorScheme.onSurface else Color(0xFF1B1720),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirstDayDropdown(
    value: Int?,
    onChange: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val effective = firstCalendarDayWithOverride(value)
    val isFollowingLocale = value == null
    val label = if (isFollowingLocale) {
        stringResource(R.string.settings_first_day_system, longDayName(effective))
    } else {
        longDayName(effective)
    }
    Column {
        GtFloatingButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(end = 8.dp),
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            R.string.settings_first_day_system,
                            longDayName(firstCalendarDayWithOverride(null)),
                        ),
                    )
                },
                onClick = {
                    onChange(null)
                    expanded = false
                },
            )
            CALENDAR_DAYS.forEach { calendarDay ->
                DropdownMenuItem(
                    text = { Text(longDayName(calendarDay)) },
                    onClick = {
                        onChange(calendarDay)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RingtoneSection(state: SettingsState, vm: SettingsViewModel, showUndo: UndoSnackbar) {
    val removedText = stringResource(R.string.sound_removed)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.settings_default_ringtones_intro),
            style = MaterialTheme.typography.bodySmall,
        )
        DefaultSoundRow(
            iconRes = R.drawable.ic_music_note,
            title = stringResource(R.string.settings_default_ringtone_absolute),
            currentName = state.defaultAbsoluteRingtoneName,
            currentUri = state.defaultAbsoluteRingtoneUri,
            onPicked = { uri, name -> vm.setDefaultAbsoluteRingtone(uri, name) },
            onRemove = {
                val uri = state.defaultAbsoluteRingtoneUri
                val name = state.defaultAbsoluteRingtoneName
                vm.setDefaultAbsoluteRingtone(null, null)
                showUndo(removedText) { vm.setDefaultAbsoluteRingtone(uri, name) }
            },
        )
        DefaultSoundRow(
            iconRes = R.drawable.ic_timer,
            title = stringResource(R.string.settings_default_ringtone_relative),
            currentName = state.defaultRelativeRingtoneName,
            currentUri = state.defaultRelativeRingtoneUri,
            onPicked = { uri, name -> vm.setDefaultRelativeRingtone(uri, name) },
            onRemove = {
                val uri = state.defaultRelativeRingtoneUri
                val name = state.defaultRelativeRingtoneName
                vm.setDefaultRelativeRingtone(null, null)
                showUndo(removedText) { vm.setDefaultRelativeRingtone(uri, name) }
            },
        )
    }
}

@Composable
private fun DefaultSoundRow(
    iconRes: Int,
    title: String,
    currentName: String?,
    currentUri: String?,
    onPicked: (uri: String, name: String?) -> Unit,
    onRemove: () -> Unit,
) {
    val audioPreview = rememberAudioPreview()
    val pickAudio = rememberAudioPicker { picked ->
        audioPreview.stop()
        onPicked(picked.uri, picked.name)
    }
    val choice = defaultSoundChoice(currentUri, currentName)
    val previewing by audioPreview.playing
    MediaChoiceRow(
        iconRes = iconRes,
        title = title,
        value = soundChoiceText(choice),
        removable = choice.removable,
        onChoose = pickAudio,
        onRemove = {
            audioPreview.stop()
            onRemove()
        },
        trailing = {
            SoundPreviewButton(previewing) {
                if (previewing) audioPreview.stop() else audioPreview.play(currentUri)
            }
        },
    )
}

// Sentinel alarm id for the Settings default watch-bg crop. Negative so it
// can never collide with a Room-assigned id (auto-generated ids start at 1).
// Used to key `cacheDir/watch_bg_-1.png` separately from per-alarm crops.
private const val DEFAULT_WATCH_BG_ID = -1L

// Sunday-first order matches the dropdown rendering of the 7 weekdays.
// The user can pick any day; the locale order is reflected by the
// "System default" item showing the locale-derived first day.
private val CALENDAR_DAYS = listOf(
    Calendar.SUNDAY,
    Calendar.MONDAY,
    Calendar.TUESDAY,
    Calendar.WEDNESDAY,
    Calendar.THURSDAY,
    Calendar.FRIDAY,
    Calendar.SATURDAY,
)
