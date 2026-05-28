// reason: Settings screen + several private subcomposables (DisplaySection,
// RingtoneSection, RingtoneRow, FirstDayDropdown, TwentyFourHourSelector)
// live in one file because they share the SettingsViewModel state surface
// and have no reuse outside Settings. Splitting per-component would push
// VM-state routing across more files for zero readability gain.
@file:Suppress("TooManyFunctions")

package com.kirkouski.gtalarm.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.data.SettingsState
import com.kirkouski.gtalarm.ui.edit.RingScreenPreview
import com.kirkouski.gtalarm.ui.edit.WatchBackgroundPickerDialog
import com.kirkouski.gtalarm.ui.edit.rememberAudioPicker
import com.kirkouski.gtalarm.ui.edit.rememberAudioPreview
import com.kirkouski.gtalarm.ui.edit.rememberBackgroundBitmap
import com.kirkouski.gtalarm.ui.edit.rememberBackgroundImagePicker
import com.kirkouski.gtalarm.util.firstCalendarDayWithOverride
import com.kirkouski.gtalarm.util.longDayName
import java.util.Calendar

// reason: Scaffold + Box fade wrapper + three card sections = inherently long
// for a settings screen; splitting adds indirection with no reuse benefit.
@Suppress("LongMethod")
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(stringResource(R.string.settings_section_default_ringtones))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        RingtoneSection(state = state, vm = vm)
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
                        BackgroundSection(state = state, vm = vm)
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
private fun BackgroundSection(state: SettingsState, vm: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.settings_default_backgrounds_intro),
            style = MaterialTheme.typography.bodySmall,
        )
        BackgroundRow(
            titleRes = R.string.settings_default_phone_background,
            currentUri = state.defaultPhoneBackgroundUri,
            onPicked = { uri -> vm.setDefaultPhoneBackground(uri) },
            onClear = { vm.setDefaultPhoneBackground(null) },
        )
        WatchBackgroundRow(
            currentUri = state.defaultWatchBackgroundUri,
            onPicked = { uri -> vm.setDefaultWatchBackground(uri) },
            onClear = { vm.setDefaultWatchBackground(null) },
        )
    }
}

/**
 * Watch-default variant of [BackgroundRow]. Opens the same circular-crop
 * [WatchBackgroundPickerDialog] used for per-alarm edits so the user gets
 * the watch-UI overlay preview here too, not just a plain SAF picker. The
 * picker keys its cached PNG/.bin by alarm id; we pass [DEFAULT_WATCH_BG_ID]
 * (a sentinel below Room's positive id range) so the default's cache file
 * (`watch_bg_-1.png`) can't collide with a real alarm's crop.
 */
@Composable
private fun WatchFacePreview(backgroundUri: String?, reloadKey: Any? = null) {
    val bm = rememberBackgroundBitmap(backgroundUri, reloadKey).value
    Box(
        modifier = Modifier.size(140.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bm != null) {
            Image(
                bitmap = bm,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.Black),
            )
        }
        Image(
            painter = painterResource(R.drawable.watch_overlay),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun WatchBackgroundRow(
    currentUri: String?,
    onPicked: (String) -> Unit,
    onClear: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    // Bumped on every save so the preview re-decodes: the watch-bg picker
    // rewrites a fixed path, so a re-crop changes neither the URI string
    // nor anything else the thumbnail keys on.
    var reloadToken by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val savedMessage = stringResource(R.string.settings_watch_background_saved)
    if (showPicker) {
        WatchBackgroundPickerDialog(
            alarmId = DEFAULT_WATCH_BG_ID,
            initialUri = currentUri,
            onDismiss = { showPicker = false },
            onSaved = { uri ->
                showPicker = false
                reloadToken++
                onPicked(uri)
                Toast.makeText(context, savedMessage, Toast.LENGTH_LONG).show()
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_default_watch_background),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (currentUri == null) R.string.settings_default_background_none
                        else R.string.settings_default_background_set,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (currentUri != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (currentUri != null) {
                IconButton(onClick = onClear) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.settings_default_background_clear),
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            FilledTonalButton(onClick = { showPicker = true }) {
                Text(stringResource(R.string.field_background_image_pick))
            }
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            WatchFacePreview(backgroundUri = currentUri, reloadKey = reloadToken)
        }
    }
}

@Composable
private fun BackgroundRow(
    titleRes: Int,
    currentUri: String?,
    onPicked: (String) -> Unit,
    onClear: () -> Unit,
) {
    val pick = rememberBackgroundImagePicker { uri -> onPicked(uri) }
    BackgroundRowLayout(
        titleRes = titleRes,
        currentUri = currentUri,
        onPick = pick,
        onClear = onClear,
        showRingPreview = true,
    )
}

// reason: column-then-row layout with optional ring-screen preview adds
// branches (thumbnail / icon / ring-preview) that push past 62 lines.
// Splitting the preview out would add a callsite routing layer used once.
@Suppress("LongMethod")
@Composable
private fun BackgroundRowLayout(
    titleRes: Int,
    currentUri: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    reloadKey: Any? = null,
    showRingPreview: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!showRingPreview) {
                if (currentUri != null) {
                    val bitmapState = rememberBackgroundBitmap(currentUri, reloadKey)
                    val bm = bitmapState.value
                    if (bm != null) {
                        Image(
                            bitmap = bm,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_image),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp).padding(8.dp),
                            tint = Color.Unspecified,
                        )
                    }
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_image),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).padding(8.dp),
                        tint = Color.Unspecified,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .padding(start = if (showRingPreview) 0.dp else 12.dp)
                    .weight(1f),
            ) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (currentUri == null) R.string.settings_default_background_none
                        else R.string.settings_default_background_set,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (currentUri != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (currentUri != null) {
                IconButton(onClick = onClear) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.settings_default_background_clear),
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            FilledTonalButton(onClick = onPick) {
                Text(stringResource(R.string.field_background_image_pick))
            }
        }
        if (showRingPreview) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                RingScreenPreview(
                    backgroundUri = currentUri,
                    timeText = "--:--",
                    labelText = "",
                    width = 140.dp,
                    height = 248.dp,
                )
            }
        }
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TwentyFourHourSelector(
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    val options = listOf<Triple<Boolean?, Int, String>>(
        Triple(null, R.string.settings_time_format_system, "system"),
        Triple(false, R.string.settings_time_format_12h, "12"),
        Triple(true, R.string.settings_time_format_24h, "24"),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { idx, opt ->
            SegmentedButton(
                selected = value == opt.first,
                onClick = { onChange(opt.first) },
                shape = SegmentedButtonDefaults.itemShape(idx, options.size),
            ) {
                Text(stringResource(opt.second))
            }
        }
    }
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
        FilledTonalButton(
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
private fun RingtoneSection(state: SettingsState, vm: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.settings_default_ringtones_intro),
            style = MaterialTheme.typography.bodySmall,
        )
        RingtoneRow(
            iconRes = R.drawable.ic_music_note,
            titleRes = R.string.settings_default_ringtone_absolute,
            currentName = state.defaultAbsoluteRingtoneName,
            currentUri = state.defaultAbsoluteRingtoneUri,
            onPicked = { uri, name -> vm.setDefaultAbsoluteRingtone(uri, name) },
            onClear = { vm.setDefaultAbsoluteRingtone(null, null) },
        )
        RingtoneRow(
            iconRes = R.drawable.ic_timer,
            titleRes = R.string.settings_default_ringtone_relative,
            currentName = state.defaultRelativeRingtoneName,
            currentUri = state.defaultRelativeRingtoneUri,
            onPicked = { uri, name -> vm.setDefaultRelativeRingtone(uri, name) },
            onClear = { vm.setDefaultRelativeRingtone(null, null) },
        )
    }
}

// reason: the row factors out the three controls (label + Play/Stop +
// Choose + Reset) into a column-then-row layout; complexity rose to 9
// because the audio-preview state machine has play/stop branching and
// the row exposes a Reset button only when the user has overridden the
// system default. Below the detekt threshold of 10. LongMethod (65 of 60)
// trips because each control is a labelled Composable callsite — extracting
// would just push state-routing across more files for no readability gain.
@Suppress("LongMethod")
@Composable
private fun RingtoneRow(
    iconRes: Int,
    titleRes: Int,
    currentName: String?,
    currentUri: String?,
    onPicked: (uri: String, name: String?) -> Unit,
    onClear: () -> Unit,
) {
    val audioPreview = rememberAudioPreview()
    val pickAudio = rememberAudioPicker { picked ->
        audioPreview.stop()
        onPicked(picked.uri, picked.name)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = currentName ?: stringResource(R.string.field_audio_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val previewing by audioPreview.playing
            IconButton(
                onClick = {
                    if (previewing) audioPreview.stop() else audioPreview.play(currentUri)
                },
            ) {
                Icon(
                    painter = painterResource(
                        if (previewing) R.drawable.ic_stop else R.drawable.ic_play,
                    ),
                    contentDescription = stringResource(
                        if (previewing) R.string.action_stop_preview else R.string.action_play_preview,
                    ),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = pickAudio,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.field_audio_pick))
            }
            if (currentUri != null) {
                FilledTonalButton(
                    onClick = {
                        audioPreview.stop()
                        onClear()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_default_ringtone_reset))
                }
            }
        }
    }
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
