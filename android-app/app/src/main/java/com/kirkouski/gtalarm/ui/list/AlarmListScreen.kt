package com.kirkouski.gtalarm.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WatchOff
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import com.kirkouski.gtalarm.domain.NextTriggerCalculator
import com.kirkouski.gtalarm.util.RelativeTime
import com.kirkouski.gtalarm.util.TimeFormatter
import com.kirkouski.gtalarm.util.rememberOrderedDayBits
import com.kirkouski.gtalarm.util.shortLabelResForDayBit
import com.kirkouski.gtalarm.wear.ForceSyncResult
import com.kirkouski.gtalarm.wear.PairedDeviceInfo
import com.kirkouski.gtalarm.wear.WatchSyncStatus
import android.widget.Toast

// reason: Compose top-level screen composables are inherently long because
// they declare a tree of layout, state hoisting, and side-effects in one
// function. Splitting AlarmListScreen further (into separate header/body
// composables) trades ~3 lines of slack for indirection + state-routing
// boilerplate, which we'd just have to revisit when the next AC item lands.
// Local @Suppress is preferred over disabling LongMethod globally —
// matches the same pattern used in AlarmEditScreen.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun AlarmListScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenBatteryOptSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: AlarmListViewModel = hiltViewModel(),
) {
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val canExact = vm.canScheduleExact()
    val showBatteryOptCard by vm.showBatteryOptCard.collectAsStateWithLifecycle()
    val watchStatus by vm.watchStatus.collectAsStateWithLifecycle()
    val pairedDevice by vm.pairedDeviceInfo.collectAsStateWithLifecycle()
    val forceSyncRunning by vm.forceSyncRunning.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Banner visible whenever any required permission is denied. The audit
    // is a handful of cheap system-service queries (no I/O), so re-running
    // on each list recomposition is fine; remember + the canExact/battery
    // keys mostly keep it from churning.
    val showSetupBanner = remember(alarms.size, showBatteryOptCard, canExact) {
        com.kirkouski.gtalarm.ui.help.hasUnresolvedSetup(context)
    }
    LaunchedEffect(Unit) {
        vm.forceSyncEvents.collect { result ->
            val msg = forceSyncToast(context, result)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_list_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.action_open_settings),
                        )
                    }
                    IconButton(onClick = onOpenHelp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = stringResource(R.string.action_open_help),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_alarm))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Always-on watch sync status card. Reads via the WearBridgeService
            // interface so it animates through real states as HuaweiWearBridge
            // emits status updates (CONNECTING → CONNECTED → ERROR, etc.).
            WatchSyncCard(
                status = watchStatus,
                pairedDevice = pairedDevice,
                forceSyncRunning = forceSyncRunning,
                onForceSync = { vm.onForceSync() },
            )
            if (showSetupBanner) {
                SetupNeededBanner(onOpenSetup = onOpenHelp)
            }
            if (showBatteryOptCard) {
                BatteryOptRationaleCard(
                    onOpenSettings = {
                        onOpenBatteryOptSettings()
                        vm.refreshBatteryOptCard()
                    },
                    onDismiss = { vm.dismissBatteryOptCard() },
                )
            }
            if (!canExact) {
                ElevatedCard(
                    modifier = Modifier
                        .padding(16.dp)
                        .clickable { onOpenExactAlarmSettings() },
                ) {
                    Text(
                        text = stringResource(R.string.permission_exact_alarm_rationale),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            if (alarms.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.AlarmOff,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.no_alarms))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(alarms, key = { it.id }) { alarm ->
                        SwipeToDeleteRow(
                            alarm = alarm,
                            settings = settings,
                            onToggle = { enabled -> vm.onToggle(alarm.id, enabled) },
                            onClick = { onEdit(alarm.id) },
                            onDelete = { vm.onDelete(alarm.id) },
                        )
                    }
                }
            }
        }
    }
}

// reason: single linear Card + Column + Row tree with no reorderable
// sections. Splitting into header/body/button composables would add a
// state-routing layer for ~3 lines saved.
@Suppress("LongMethod")
@Composable
private fun WatchSyncCard(
    status: WatchSyncStatus,
    pairedDevice: PairedDeviceInfo?,
    forceSyncRunning: Boolean,
    onForceSync: () -> Unit,
) {
    val bodyRes = when (status) {
        WatchSyncStatus.NOT_CONNECTED -> R.string.watch_status_card_not_connected
        WatchSyncStatus.CONNECTING -> R.string.watch_status_card_connecting
        WatchSyncStatus.CONNECTED -> R.string.watch_status_card_connected
        WatchSyncStatus.ERROR -> R.string.watch_status_card_error
    }
    val icon = if (status == WatchSyncStatus.CONNECTED) Icons.Default.Watch else Icons.Default.WatchOff
    ElevatedCard(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.watch_status_card_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(bodyRes),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    pairedDevice?.let { info ->
                        val devText = listOf(info.name, info.model)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                            .ifBlank { stringResource(R.string.watch_status_card_device_unknown) }
                        Text(
                            text = devText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onForceSync,
                enabled = !forceSyncRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.watch_status_card_force_sync))
            }
        }
    }
}

private fun forceSyncToast(
    context: android.content.Context,
    result: ForceSyncResult,
): String = when (result) {
    is ForceSyncResult.Ok -> context.getString(R.string.force_sync_ok, result.alarmCount)
    is ForceSyncResult.AlreadyInSync ->
        context.getString(R.string.force_sync_already, result.alarmCount)
    ForceSyncResult.NoDevice -> context.getString(R.string.force_sync_no_device)
    ForceSyncResult.NotAuthorized -> context.getString(R.string.force_sync_not_authorized)
    ForceSyncResult.Disconnected -> context.getString(R.string.force_sync_disconnected)
    is ForceSyncResult.PeerAppMissing ->
        context.getString(R.string.force_sync_peer_app_missing, result.pingCode)
    is ForceSyncResult.Error -> context.getString(R.string.force_sync_error, result.message)
}

@Composable
private fun SetupNeededBanner(onOpenSetup: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clickable { onOpenSetup() },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.setup_banner_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    text = stringResource(R.string.setup_banner_body),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            OutlinedButton(onClick = onOpenSetup) {
                Text(stringResource(R.string.setup_banner_action))
            }
        }
    }
}

@Composable
private fun BatteryOptRationaleCard(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.battery_opt_card_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.battery_opt_card_body),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.battery_opt_card_dismiss))
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.battery_opt_card_action))
                }
            }
        }
    }
}

// reason: row composable grew past 60 lines because the swipe-to-delete
// confirm dialog and the auto-reset-on-cancel LaunchedEffect both belong
// in the same composable as the SwipeToDismissBox they coordinate with
// (extracting them would require threading the dismissState through more
// composable boundaries for no readability gain).
@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    alarm: Alarm,
    settings: com.kirkouski.gtalarm.data.SettingsState,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    var showConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart ||
            dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd
        ) {
            // Open confirm dialog instead of deleting immediately. The row
            // stays visually swiped while the dialog is up; cancel snaps it
            // back, confirm tombstones the alarm.
            showConfirm = true
        }
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = {
                showConfirm = false
            },
            title = { Text(stringResource(R.string.delete_alarm)) },
            text = {
                val body = if (alarm.label.isNotBlank()) {
                    stringResource(R.string.swipe_delete_confirm_body, alarm.label)
                } else {
                    stringResource(R.string.swipe_delete_confirm_body_unlabeled)
                }
                Text(body)
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onDelete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConfirm = false
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    // When the dialog closes without a confirm (showConfirm flipped back to
    // false while currentValue is still settled-to-EndToStart), snap the
    // row back to its resting position. .reset() is suspend so wrap in a
    // LaunchedEffect keyed on the dialog-shown flag.
    LaunchedEffect(showConfirm, dismissState.currentValue) {
        if (!showConfirm &&
            (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart ||
                dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd)
        ) {
            dismissState.reset()
        }
    }
    // reason: pad the SwipeToDismissBox externally so the errorContainer
    // backgroundContent paints in the same bounds as the AlarmRow Card. If the
    // padding lives on the inner Card, the bg leaks 8dp on each side at rest.
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium),
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_alarm),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        AlarmRow(alarm = alarm, settings = settings, onToggle = onToggle, onClick = onClick)
    }
}

@Composable
private fun AlarmRow(
    alarm: Alarm,
    settings: com.kirkouski.gtalarm.data.SettingsState,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Per-alarm background thumbnail at the leading edge. Only renders
            // when the alarm has its own URI set (we don't load the user's
            // default for every row — that's a recompose-per-row I/O storm on
            // a long list). 24dp circular crop matches the row's vertical
            // rhythm and keeps the thumbnail out of the way of the time text.
            val bgUri = alarm.backgroundImageUri
            if (bgUri != null) {
                val bitmapState = com.kirkouski.gtalarm.ui.edit.rememberBackgroundBitmap(bgUri)
                val bm = bitmapState.value
                if (bm != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bm,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .padding(end = 0.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                }
            }
            val ctx = LocalContext.current
            Column(modifier = Modifier.weight(1f)) {
                if (alarm.isRelative) {
                    // Live countdown is the prominent text for relative alarms.
                    // Different color (tertiary) so user spots it at a glance and
                    // remembers it resets on toggle off→on. Matches the watch
                    // list's color-cue convention.
                    val countdownText = rememberRelativeCountdownText(alarm)
                    Text(
                        text = countdownText,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    Text(
                        text = TimeFormatter.formatHourMinute(
                            ctx,
                            alarm.hour,
                            alarm.minute,
                            overrideUse24Hour = settings.use24Hour,
                        ),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
                if (alarm.label.isNotBlank()) {
                    Text(alarm.label, fontSize = 14.sp)
                }
                Text(
                    text = subtitleLine(alarm, settings.firstDayOfWeek),
                    fontSize = 12.sp,
                )
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun subtitleLine(alarm: Alarm, firstDayOverride: Int?): String {
    val days = daysLabel(alarm.daysOfWeek, firstDayOverride)
    if (!alarm.enabled) return days
    // For relative alarms the prominent text already shows the live countdown;
    // subtitle reduces to a hint that the timer resets on toggle. For absolute
    // alarms we keep the legacy "in N min" approximate text (doesn't tick).
    if (alarm.isRelative) return ""
    val nextTrigger = remember(alarm) { NextTriggerCalculator.nextTriggerEpochMillis(alarm) }
    val relative = RelativeTime.formatUntil(nextTrigger)
    return if (relative.isEmpty()) days else "$days  ·  $relative"
}

// Ticks the countdown for a relative alarm. Cadence:
//   > 60 s remaining: refresh every 30 s
//   ≤ 60 s remaining: refresh every 1 s (so user sees seconds counting down
//                     on the final approach)
// Stops ticking 5 s past fire time, OR immediately if the alarm is disabled
// (toggle off should freeze the displayed countdown — we use the last
// computed value without further ticking).
@Composable
private fun rememberRelativeCountdownText(alarm: Alarm): String {
    val target = alarm.computedFireEpoch()
    val enabled = alarm.enabled
    // mutableLongStateOf keyed on target+enabled so we restart the tick if
    // the user edits the duration (which bumps updatedAtEpoch → new target)
    // or toggles the enabled state. When enabled flips false, the new
    // LaunchedEffect body short-circuits the loop and the displayed value
    // freezes at the last tick.
    var now by remember(alarm.id, target, enabled) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(alarm.id, target, enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            val remaining = target - System.currentTimeMillis()
            if (remaining < -COUNTDOWN_STOP_GRACE_MS) break
            val sleep = if (remaining > 60_000L) COUNTDOWN_SLOW_TICK_MS else COUNTDOWN_FAST_TICK_MS
            delay(sleep)
            now = System.currentTimeMillis()
        }
    }
    val remaining = target - now
    return when {
        remaining <= 0L -> stringResource(R.string.list_relative_fired)
        remaining < 60_000L -> stringResource(
            R.string.list_relative_in_sec,
            (remaining / 1000L).toInt(),
        )
        remaining < 3_600_000L -> stringResource(
            R.string.list_relative_in_min,
            (remaining / 60_000L).toInt(),
        )
        else -> {
            val totalMin = (remaining / 60_000L).toInt()
            val hours = totalMin / 60
            val mins = totalMin % 60
            stringResource(R.string.list_relative_in_hr_min, hours, mins)
        }
    }
}

private const val COUNTDOWN_SLOW_TICK_MS = 30_000L
private const val COUNTDOWN_FAST_TICK_MS = 1_000L
private const val COUNTDOWN_STOP_GRACE_MS = 5_000L

@Composable
private fun daysLabel(mask: Int, firstDayOverride: Int?): String = when (mask) {
    DaysOfWeek.NONE -> stringResource(R.string.repeats_once)
    DaysOfWeek.ALL -> stringResource(R.string.repeats_every_day)
    DaysOfWeek.WEEKDAYS -> stringResource(R.string.repeats_weekdays)
    DaysOfWeek.WEEKENDS -> stringResource(R.string.repeats_weekends)
    else -> rememberOrderedDayBits(firstDayOverride)
        .mapNotNull { bit ->
            if (DaysOfWeek.contains(mask, bit)) stringResource(shortLabelResForDayBit(bit)) else null
        }
        .joinToString(" ")
}
