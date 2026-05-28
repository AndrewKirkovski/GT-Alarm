// reason: AlarmListScreen is the list screen plus its inline cards (watch-sync
// card + its split-out action button, setup banner, battery-opt rationale,
// swipe-to-delete row, alarm row) and the countdown / repeat-label / day-label
// helpers — each referenced exactly once from this screen. Splitting would
// scatter list-state routing across files for zero reuse.
@file:Suppress("TooManyFunctions")

package com.kirkouski.gtalarm.ui.list

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
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

// reason: Compose top-level screen composables are inherently long because
// they declare a tree of layout, state hoisting, and side-effects in one
// function. Splitting AlarmListScreen further (into separate header/body
// composables) trades ~3 lines of slack for indirection + state-routing
// boilerplate, which we'd just have to revisit when the next AC item lands.
// Local @Suppress is preferred over disabling LongMethod globally —
// matches the same pattern used in AlarmEditScreen.
@Composable
@Suppress("LongMethod")
fun AlarmListScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenBatteryOptSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onAuthorizeWatch: () -> Unit,
    vm: AlarmListViewModel = hiltViewModel(),
) {
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val canExact = vm.canScheduleExact()
    val showBatteryOptCard by vm.showBatteryOptCard.collectAsStateWithLifecycle()
    val watchStatus by vm.watchStatus.collectAsStateWithLifecycle()
    val pairedDevice by vm.pairedDeviceInfo.collectAsStateWithLifecycle()
    val forceSyncRunning by vm.forceSyncRunning.collectAsStateWithLifecycle()
    val needsWatchAuth by vm.needsWatchAuthorization.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshWatchAuthorization()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val showSetupBanner = remember(alarms.size, showBatteryOptCard, canExact) {
        com.kirkouski.gtalarm.ui.help.hasUnresolvedSetup(context)
    }
    LaunchedEffect(Unit) {
        vm.forceSyncEvents.collect { result ->
            val msg = forceSyncToast(context, result)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    val listState = rememberLazyListState()
    // derivedStateOf prevents recomposition on every scroll pixel — only
    // recomposes when the boolean result actually flips.
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val navBarPaddingDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val density = LocalDensity.current
            val fadeEdgePx = remember(navBarPaddingDp) { with(density) { (navBarPaddingDp + 38.dp).toPx() } }
            LazyColumn(
                state = listState,
                modifier = Modifier
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
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp,
                ),
            ) {
                item(key = "hero") {
                    HeroSection(alarms = alarms, settings = settings)
                }
                if (showSetupBanner) {
                    item(key = "setup-banner") {
                        SetupNeededBanner(onOpenSetup = onOpenHelp)
                    }
                }
                if (showBatteryOptCard) {
                    item(key = "battery-opt") {
                        BatteryOptRationaleCard(
                            onOpenSettings = {
                                onOpenBatteryOptSettings()
                                vm.refreshBatteryOptCard()
                            },
                            onDismiss = { vm.dismissBatteryOptCard() },
                        )
                    }
                }
                if (!canExact) {
                    item(key = "exact-alarm") {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .fillMaxWidth()
                                .clickable { onOpenExactAlarmSettings() },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.permission_exact_alarm_rationale),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                item(key = "alarm-list") {
                    if (alarms.isEmpty()) {
                        EmptyAlarmsPlaceholder()
                    } else {
                        AlarmListCard(
                            alarms = alarms,
                            settings = settings,
                            onToggle = { id, en -> vm.onToggle(id, en) },
                            onClick = { id -> onEdit(id) },
                            onDelete = { id -> vm.onDelete(id) },
                            onSkipNext = { id -> vm.onSkipNext(id) },
                        )
                    }
                }
                item(key = "watch-sync") {
                    WatchSyncCard(
                        status = watchStatus,
                        pairedDevice = pairedDevice,
                        forceSyncRunning = forceSyncRunning,
                        needsAuthorization = needsWatchAuth,
                        onForceSync = { vm.onForceSync() },
                        onAuthorize = onAuthorizeWatch,
                    )
                }
            }

            // Add button — plain icon when at top, pill when scrolled.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 16.dp),
            ) {
                if (isScrolled) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp,
                    ) {
                        IconButton(onClick = onAdd) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.add_alarm),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                } else {
                    IconButton(onClick = onAdd) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.add_alarm),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}

// Hero section: countdown to next enabled alarm + its date/time.
// Ticks every 30 s; if no upcoming alarm shows the empty-state label.
@Suppress("LongMethod")
@Composable
private fun HeroSection(
    alarms: List<Alarm>,
    settings: com.kirkouski.gtalarm.data.SettingsState,
) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    val nextAlarm = remember(alarms, now) {
        alarms.filter { it.enabled }
            .minByOrNull { NextTriggerCalculator.nextTriggerEpochMillis(it) }
    }
    val nextTrigger = nextAlarm?.let { NextTriggerCalculator.nextTriggerEpochMillis(it) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, bottom = 20.dp, start = 24.dp, end = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (nextTrigger != null && nextTrigger > now) {
            val remaining = nextTrigger - now
            val hours = (remaining / 3_600_000L).toInt()
            val mins = ((remaining % 3_600_000L) / 60_000L).toInt()
            val heroText = if (hours > 0) {
                stringResource(R.string.screen_list_hero_alarm_in_hr_min, hours, mins)
            } else {
                stringResource(R.string.screen_list_hero_alarm_in_min, mins)
            }
            Text(
                text = heroText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val is24h = TimeFormatter.resolveUses24HourFormat(ctx, settings.use24Hour)
            val timeStr = TimeFormatter.formatHourMinute(
                ctx, nextAlarm.hour, nextAlarm.minute, is24h,
            )
            val cal = java.util.Calendar.getInstance().also { it.timeInMillis = nextTrigger }
            val dayName = android.text.format.DateFormat.format("EEE", cal).toString()
            val dayNum = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val monthName = android.text.format.DateFormat.format("MMM", cal).toString()
            Text(
                text = "$dayName $dayNum $monthName, $timeStr",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.no_alarms),
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            )
        }
    }
}

// All alarms in one white card with thin dividers between rows.
@Composable
private fun AlarmListCard(
    alarms: List<Alarm>,
    settings: com.kirkouski.gtalarm.data.SettingsState,
    onToggle: (Long, Boolean) -> Unit,
    onClick: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSkipNext: (Long) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            alarms.forEachIndexed { i, alarm ->
                SwipeToDeleteRow(
                    alarm = alarm,
                    settings = settings,
                    onToggle = { enabled -> onToggle(alarm.id, enabled) },
                    onClick = { onClick(alarm.id) },
                    onDelete = { onDelete(alarm.id) },
                    onSkipNext = { onSkipNext(alarm.id) },
                )
                if (i < alarms.size - 1) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAlarmsPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_alarm_off),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = Color.Unspecified,
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.no_alarms))
        }
    }
}

// reason: LongMethod — one linear Row tree; the status/device strings and
// the spin transition are derived inline so the layout reads top-to-bottom.
@Suppress("LongMethod")
@Composable
private fun WatchSyncCard(
    status: WatchSyncStatus,
    pairedDevice: PairedDeviceInfo?,
    forceSyncRunning: Boolean,
    needsAuthorization: Boolean,
    onForceSync: () -> Unit,
    onAuthorize: () -> Unit,
) {
    val iconRes = if (status == WatchSyncStatus.CONNECTED) R.drawable.ic_watch else R.drawable.ic_watch_off
    val deviceText = pairedDevice?.displayName?.takeIf { it.isNotBlank() }
    val subtitle = when {
        needsAuthorization -> stringResource(R.string.watch_status_card_needs_auth)
        deviceText != null -> deviceText
        else -> null
    }
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color.Unspecified,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.watch_status_card_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            WatchSyncActionButton(
                needsAuthorization = needsAuthorization,
                forceSyncRunning = forceSyncRunning,
                onForceSync = onForceSync,
                onAuthorize = onAuthorize,
            )
        }
    }
}

@Composable
private fun WatchSyncActionButton(
    needsAuthorization: Boolean,
    forceSyncRunning: Boolean,
    onForceSync: () -> Unit,
    onAuthorize: () -> Unit,
) {
    if (needsAuthorization) {
        Image(
            painter = painterResource(R.drawable.ic_watch_authorize),
            contentDescription = stringResource(R.string.watch_status_card_authorize),
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onAuthorize),
        )
        return
    }
    val spin by rememberInfiniteTransition(label = "watchSync").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "watchSyncAngle",
    )
    Image(
        painter = painterResource(R.drawable.ic_sync),
        contentDescription = stringResource(R.string.watch_status_card_force_sync),
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = !forceSyncRunning, onClick = onForceSync)
            .rotate(if (forceSyncRunning) spin else 0f),
    )
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
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clickable { onOpenSetup() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = Color.Unspecified,
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
            FilledTonalButton(onClick = onOpenSetup) {
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
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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
                FilledTonalButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.battery_opt_card_action))
                }
            }
        }
    }
}

// reason: the confirm-dialog declaration and the SwipeToDismissBox it
// coordinates with both live in this composable so the showConfirm state
// doesn't have to be threaded across composable boundaries. Complexity is
// >10 because the backgroundContent now branches on (skip vs delete) ×
// (start vs end vs settled) for icon + bg color + alignment — each branch
// maps to a distinct user-visible affordance.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    alarm: Alarm,
    settings: com.kirkouski.gtalarm.data.SettingsState,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSkipNext: () -> Unit = {},
) {
    var showConfirm by remember { mutableStateOf(false) }
    val canSkip = alarm.daysOfWeek != 0
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    if (canSkip) {
                        onSkipNext()
                        false
                    } else {
                        showConfirm = true
                        false
                    }
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    showConfirm = true
                    false
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
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
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.clip(MaterialTheme.shapes.medium),
        backgroundContent = {
            val isSkipGesture = canSkip &&
                dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val bg = if (isSkipGesture) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
            val iconRes = if (isSkipGesture) R.drawable.ic_timer else R.drawable.ic_delete
            val cd = if (isSkipGesture) {
                stringResource(R.string.list_skip_next_hint)
            } else {
                stringResource(R.string.delete_alarm)
            }
            val alignment = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = cd,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(28.dp),
                )
            }
        },
    ) {
        AlarmRow(alarm = alarm, settings = settings, onToggle = onToggle, onClick = onClick)
    }
}

// reason: adding DayDotRow for recurring alarms adds branches (isRecurring /
// subtitle-empty) that push the function body beyond the 60-line limit. The
// function is a single layout tree — splitting would scatter state routing.
@Suppress("LongMethod")
@Composable
private fun AlarmRow(
    alarm: Alarm,
    settings: com.kirkouski.gtalarm.data.SettingsState,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ctx = LocalContext.current
        Column(modifier = Modifier.weight(1f)) {
            if (alarm.isRelative) {
                val countdownText = rememberRelativeCountdownText(alarm)
                Text(
                    text = countdownText,
                    fontSize = 43.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                val timeColor = if (alarm.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                }
                Text(
                    text = TimeFormatter.formatHourMinute(
                        ctx,
                        alarm.hour,
                        alarm.minute,
                        overrideUse24Hour = settings.use24Hour,
                    ),
                    fontSize = 43.sp,
                    fontWeight = FontWeight.Bold,
                    color = timeColor,
                )
            }
            val isRecurring = alarm.daysOfWeek != DaysOfWeek.NONE
            if (isRecurring) {
                Spacer(Modifier.height(4.dp))
                DayDotRow(mask = alarm.daysOfWeek, firstDayOverride = settings.firstDayOfWeek)
            }
            val subtitle = subtitleLine(
                alarm,
                settings.firstDayOfWeek,
                showDays = !isRecurring,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = alarm.enabled, onCheckedChange = onToggle)
    }
}

// reason: showDays=false path (used when DayDotRow replaces text days) adds
// one extra branch that tips complexity to 11; the whole function is one
// linear derivation of a display string — splitting would add indirection.
@Suppress("CyclomaticComplexMethod")
@Composable
private fun subtitleLine(alarm: Alarm, firstDayOverride: Int?, showDays: Boolean = true): String {
    val daysBase = if (showDays) repeatLabel(alarm, firstDayOverride) else ""
    val skipActive = alarm.skipNextEpoch != null &&
        alarm.skipNextEpoch > System.currentTimeMillis()
    val skipHint = if (skipActive) stringResource(R.string.list_skip_next_hint) else ""
    val days = if (skipHint.isNotEmpty()) {
        if (daysBase.isEmpty()) skipHint else "$daysBase · $skipHint"
    } else {
        daysBase
    }
    if (!alarm.enabled) return days
    if (alarm.isRelative) return days
    val nextTrigger = remember(alarm) { NextTriggerCalculator.nextTriggerEpochMillis(alarm) }
    val relative = RelativeTime.formatUntil(nextTrigger)
    return when {
        days.isEmpty() -> relative
        relative.isEmpty() -> days
        else -> "$days  ·  $relative"
    }
}

@Composable
private fun repeatLabel(alarm: Alarm, firstDayOverride: Int?): String = when {
    alarm.daysOfWeek != DaysOfWeek.NONE -> daysLabel(alarm.daysOfWeek, firstDayOverride)
    alarm.isRelative -> stringResource(R.string.repeats_timer)
    alarm.selfDestruct -> stringResource(R.string.repeats_once)
    else -> ""
}

// reason: complexity is 12 because the function combines two distinct
// modes (disabled → static label; enabled → tick loop + 4-way countdown
// switch). Splitting into two composables would force the parent to
// branch on `alarm.enabled` for every relative row — same branch count
// just hoisted up one frame. Keeping both modes here keeps the disabled
// fix co-located with the bug it addresses.
@Suppress("CyclomaticComplexMethod")
@Composable
private fun rememberRelativeCountdownText(alarm: Alarm): String {
    if (!alarm.enabled) {
        val rel = alarm.relativeMinutes ?: 0
        return if (rel >= 60) {
            stringResource(R.string.list_relative_off_hr_min, rel / 60, rel % 60)
        } else {
            stringResource(R.string.list_relative_off_min, rel)
        }
    }
    val snoozeUntil = alarm.snoozedUntilEpoch
    val target = if (snoozeUntil != null && snoozeUntil > System.currentTimeMillis()) {
        snoozeUntil
    } else {
        alarm.computedFireEpoch()
    }
    var now by remember(alarm.id, target) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(alarm.id, target) {
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

// Day indicator row. Compact spacing so it reads as a dense 7-char row, not
// a stretched full-width bar.
@Composable
private fun DayDotRow(mask: Int, firstDayOverride: Int?) {
    val orderedBits = rememberOrderedDayBits(firstDayOverride)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        orderedBits.forEach { bit ->
            val active = DaysOfWeek.contains(mask, bit)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                        ),
                )
                Text(
                    text = stringResource(shortLabelResForDayBit(bit)),
                    fontSize = 11.sp,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
