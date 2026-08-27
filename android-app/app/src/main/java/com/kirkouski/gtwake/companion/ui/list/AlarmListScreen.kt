// reason: AlarmListScreen is the list screen plus its inline cards (watch-sync
// card + its split-out action button, setup banner, battery-opt rationale,
// swipe-to-delete row, alarm row) and the countdown / repeat-label / day-label
// helpers — each referenced exactly once from this screen. Splitting would
// scatter list-state routing across files for zero reuse.
@file:Suppress("TooManyFunctions")

package com.kirkouski.gtwake.companion.ui.list

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import com.kirkouski.gtwake.companion.domain.NextTriggerCalculator
import com.kirkouski.gtwake.companion.ui.components.GtAccentButton
import com.kirkouski.gtwake.companion.ui.edit.AlarmMode
import com.kirkouski.gtwake.companion.util.RelativeTime
import com.kirkouski.gtwake.companion.util.TimeFormatter
import com.kirkouski.gtwake.companion.util.rememberOrderedDayBits
import com.kirkouski.gtwake.companion.util.shortLabelResForDayBit
import com.kirkouski.gtwake.companion.wear.ForceSyncResult
import com.kirkouski.gtwake.companion.wear.PairedDeviceInfo
import com.kirkouski.gtwake.companion.wear.WatchSyncStatus

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
    // Takes the mode so the empty-state card can open a new draft straight on
    // the Alarm or the Timer tab; the top-right + always means ABSOLUTE.
    onAdd: (AlarmMode) -> Unit,
    onEdit: (Long) -> Unit,
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
        com.kirkouski.gtwake.companion.ui.help.hasUnresolvedSetup(context)
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
                // AT MOST ONE remediation prompt at a time, most-severe first, so
                // the user resolves them one by one instead of facing a stack of
                // competing "fix this" cards. Clearing the top one reveals the
                // next on the following recomposition.
                //
                // The old standalone exact-alarm card is gone rather than merely
                // deprioritised: EXACT_ALARM is `isRequiredForRing()`, so
                // `hasUnresolvedSetup()` is already true whenever `!canExact` —
                // that card could never appear except underneath the Setup
                // banner, and the Setup screen it links to itemises the same
                // permission with a working deep-link.
                when {
                    showSetupBanner -> item(key = "setup-banner") {
                        SetupNeededBanner(onOpenSetup = onOpenHelp)
                    }
                    showBatteryOptCard -> item(key = "battery-opt") {
                        BatteryOptRationaleCard(
                            onOpenSettings = {
                                onOpenBatteryOptSettings()
                                vm.refreshBatteryOptCard()
                            },
                            onDismiss = { vm.dismissBatteryOptCard() },
                        )
                    }
                }
                item(key = "alarm-list") {
                    if (alarms.isEmpty()) {
                        EmptyAlarmsCard(onAdd = onAdd)
                    } else {
                        AlarmListCard(
                            alarms = alarms,
                            settings = settings,
                            onToggle = { id, en -> vm.onToggle(id, en) },
                            onClick = { id -> onEdit(id) },
                            onDelete = { id -> vm.onDelete(id) },
                            onToggleSkipNext = { id -> vm.onToggleSkipNext(id) },
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
                        IconButton(onClick = { onAdd(AlarmMode.ABSOLUTE) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.add_alarm),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                } else {
                    IconButton(onClick = { onAdd(AlarmMode.ABSOLUTE) }) {
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
// Ticks every second when under an hour out (so the seconds visibly tick) and
// every 30 s otherwise; if no upcoming alarm exists, distinguishes an empty
// alarm list from a list whose alarms are all disabled.
@Suppress("LongMethod")
@Composable
private fun HeroSection(
    alarms: List<Alarm>,
    settings: com.kirkouski.gtwake.companion.data.SettingsState,
) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Keep `now` live so the hero countdown updates. Under an hour out the
    // secondary tier is SECONDS (the countdown has no unit labels — the ticking
    // is what tells minutes:seconds apart from hours:minutes), so refresh every
    // second; above an hour the minutes tier only needs a 30 s cadence.
    LaunchedEffect(alarms) {
        while (true) {
            now = System.currentTimeMillis()
            delay(nextHeroTickMs(alarms, now))
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
            .padding(top = 56.dp, bottom = 20.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (nextTrigger != null && nextTrigger > now) {
            val remaining = nextTrigger - now
            // Two-tier countdown "Alarm in <big>N <sup>MM</sup>" with no unit
            // labels (>=1h → h:m; <1h → m:s — the ticking seconds disambiguate).
            // Built in a helper to keep this composable under the complexity cap.
            val template = stringResource(R.string.screen_list_hero_countdown)
            val heroText = heroCountdownAnnotated(heroCountdownTiers(remaining), template)
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
            // Empty / all-off states mirror the populated hero: a bold 28.sp
            // header + a small subtle subtext (same styling as the "Alarm in X"
            // header and its date line above), kept consistent across both states.
            val isEmpty = alarms.isEmpty()
            Text(
                text = stringResource(if (isEmpty) R.string.no_alarms else R.string.no_enabled_alarms),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(if (isEmpty) R.string.no_alarms_hint else R.string.no_enabled_alarms_hint),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// All alarms in one white card with thin dividers between rows.
@Composable
private fun AlarmListCard(
    alarms: List<Alarm>,
    settings: com.kirkouski.gtwake.companion.data.SettingsState,
    onToggle: (Long, Boolean) -> Unit,
    onClick: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onToggleSkipNext: (Long) -> Unit,
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
                    onToggleSkipNext = { onToggleSkipNext(alarm.id) },
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

// Empty-list slot. Deliberately a Card with the SAME shape/margins as
// AlarmListCard so the empty↔populated swap doesn't shift the layout, and so
// the empty screen reads as three consistent cards instead of a bare icon
// floating between two of them. No title here — HeroSection already prints
// "No alarms" directly above.
@Composable
private fun EmptyAlarmsCard(onAdd: (AlarmMode) -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_alarm_off),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = Color.Unspecified,
            )
            Text(
                text = stringResource(R.string.empty_alarms_body),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GtAccentButton(onClick = { onAdd(AlarmMode.ABSOLUTE) }) {
                    Text(stringResource(R.string.alarm_notification_title))
                }
                GtAccentButton(onClick = { onAdd(AlarmMode.RELATIVE) }) {
                    Text(stringResource(R.string.screen_timer_title))
                }
            }
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
            GtAccentButton(onClick = onOpenSetup) {
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
                GtAccentButton(
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
    settings: com.kirkouski.gtwake.companion.data.SettingsState,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleSkipNext: () -> Unit = {},
) {
    var showConfirm by remember { mutableStateOf(false) }
    val canSkip = alarm.daysOfWeek != 0
    val skipActive = alarm.skipNextEpoch != null &&
        alarm.skipNextEpoch > System.currentTimeMillis()
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    if (canSkip) {
                        onToggleSkipNext()
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
                if (skipActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
            val iconRes = if (isSkipGesture) R.drawable.ic_timer else R.drawable.ic_delete
            val cd = if (isSkipGesture) {
                stringResource(
                    if (skipActive) {
                        R.string.list_unskip_next_hint
                    } else {
                        R.string.list_skip_next_hint
                    },
                )
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
    settings: com.kirkouski.gtwake.companion.data.SettingsState,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val editInteraction = remember { MutableInteractionSource() }
    val toggleInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ctx = LocalContext.current
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = editInteraction,
                    indication = null,
                    onClick = onClick,
                ),
        ) {
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
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = 120.dp)
                .clickable(
                    interactionSource = toggleInteraction,
                    indication = null,
                ) {
                    onToggle(!alarm.enabled)
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
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
    // No days and not relative ⇒ a one-shot dated alarm, whether or not it
    // self-destructs. This used to be keyed off `selfDestruct`, which was
    // survivable only while that defaulted ON for one-shots: since 1.0.8 it
    // defaults OFF, so every newly created dated alarm fell through to "" and
    // rendered with NO subtitle at all once it fired and disabled itself.
    // Repetition and deletion are independent — label the former.
    else -> stringResource(R.string.repeats_once)
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
        return singleUnitCountdownText(
            remainingMillis = rel * MILLIS_PER_MINUTE,
            prefix = CountdownPrefix.NONE,
        )
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
    return if (remaining <= 0L) {
        stringResource(R.string.list_relative_fired)
    } else {
        singleUnitCountdownText(remaining, prefix = CountdownPrefix.IN)
    }
}

@Composable
private fun singleUnitCountdownText(
    remainingMillis: Long,
    prefix: CountdownPrefix,
): String {
    val unit = singleCountdownUnit(remainingMillis)
    return when (unit) {
        is CountdownUnit.Seconds -> secondsCountdownText(unit.value, prefix)
        is CountdownUnit.Minutes -> minutesCountdownText(unit.value, prefix)
        is CountdownUnit.Hours -> hoursCountdownText(unit.value, prefix)
    }
}

private fun singleCountdownUnit(remainingMillis: Long): CountdownUnit = when {
    remainingMillis < QUARTER_MINUTE_MILLIS -> CountdownUnit.Seconds(
        ceilDiv(remainingMillis, MILLIS_PER_SECOND).coerceAtLeast(1L).toInt(),
    )
    remainingMillis < MILLIS_PER_HOUR -> CountdownUnit.Minutes(
        ((remainingMillis + HALF_MINUTE_MILLIS) / MILLIS_PER_MINUTE)
            .coerceAtLeast(1L)
            .toInt(),
    )
    else -> CountdownUnit.Hours(roundedQuarterHourLabel(remainingMillis))
}

// Two-tier hero countdown as (primary, secondary): >=1h → (hours, minutes);
// otherwise (minutes, seconds). Always exactly two tiers, never a third — the
// caller renders `secondary` as a half-size superscript and (in the m:s tier)
// the ticking second is what tells it apart from h:m.
private fun heroCountdownTiers(remainingMillis: Long): Pair<Int, Int> =
    if (remainingMillis >= MILLIS_PER_HOUR) {
        val hours = (remainingMillis / MILLIS_PER_HOUR).toInt()
        val minutes = ((remainingMillis % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE).toInt()
        hours to minutes
    } else {
        val minutes = (remainingMillis / MILLIS_PER_MINUTE).toInt()
        val seconds = ((remainingMillis % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND).toInt()
        minutes to seconds
    }

// Tick cadence for the hero countdown: 1 s when the next alarm is under an hour
// out (so the seconds tier visibly ticks), else 30 s. Pulled out of HeroSection
// to keep that composable under the cyclomatic-complexity budget.
private fun nextHeroTickMs(alarms: List<Alarm>, nowMs: Long): Long {
    val nextMs = alarms.asSequence()
        .filter { it.enabled }
        .map { NextTriggerCalculator.nextTriggerEpochMillis(it) }
        .minOrNull()
    val rem = if (nextMs != null) nextMs - nowMs else Long.MAX_VALUE
    return if (rem in 1 until MILLIS_PER_HOUR) COUNTDOWN_FAST_TICK_MS else COUNTDOWN_SLOW_TICK_MS
}

// Render the two countdown tiers as "Alarm in <primary> <superscript secondary>"
// by splicing the styled numbers into the localized template at its %1$s slot
// (so word order localizes). Secondary is a half-size raised superscript.
private fun heroCountdownAnnotated(tiers: Pair<Int, Int>, template: String): AnnotatedString {
    val numbers = buildAnnotatedString {
        append(tiers.first.toString())
        // No separator between the tiers — the superscript baseline shift sets
        // the secondary apart on its own (e.g. "19" + raised "35" → 19³⁵).
        withStyle(SpanStyle(fontSize = 14.sp, baselineShift = BaselineShift.Superscript)) {
            append(tiers.second.toString().padStart(2, '0'))
        }
    }
    val slot = template.indexOf(COUNTDOWN_SLOT)
    return buildAnnotatedString {
        if (slot >= 0) {
            append(template.substring(0, slot))
            append(numbers)
            append(template.substring(slot + COUNTDOWN_SLOT.length))
        } else {
            append(numbers)
        }
    }
}

@Composable
private fun secondsCountdownText(value: Int, prefix: CountdownPrefix): String = when (prefix) {
    CountdownPrefix.ALARM_IN -> stringResource(R.string.screen_list_hero_alarm_in_sec, value)
    CountdownPrefix.IN -> stringResource(R.string.list_relative_in_sec, value)
    CountdownPrefix.NONE -> stringResource(R.string.list_relative_off_min, 1)
}

@Composable
private fun minutesCountdownText(value: Int, prefix: CountdownPrefix): String = when (prefix) {
    CountdownPrefix.ALARM_IN -> stringResource(R.string.screen_list_hero_alarm_in_min, value)
    CountdownPrefix.IN -> stringResource(R.string.list_relative_in_min, value)
    CountdownPrefix.NONE -> stringResource(R.string.list_relative_off_min, value)
}

@Composable
private fun hoursCountdownText(value: String, prefix: CountdownPrefix): String = when (prefix) {
    CountdownPrefix.ALARM_IN -> stringResource(R.string.screen_list_hero_alarm_in_hr, value)
    CountdownPrefix.IN -> stringResource(R.string.list_relative_in_hr, value)
    CountdownPrefix.NONE -> stringResource(R.string.list_relative_off_hr, value)
}

private fun roundedQuarterHourLabel(remainingMillis: Long): String {
    val quarters = ((remainingMillis + HALF_QUARTER_HOUR_MILLIS) / QUARTER_HOUR_MILLIS)
        .coerceAtLeast(1L)
    val wholeHours = quarters / QUARTERS_PER_HOUR
    return when (quarters % QUARTERS_PER_HOUR) {
        0L -> "$wholeHours"
        1L -> "$wholeHours¼"
        2L -> "$wholeHours½"
        else -> "$wholeHours¾"
    }
}

private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1L) / divisor

private sealed interface CountdownUnit {
    data class Seconds(val value: Int) : CountdownUnit
    data class Minutes(val value: Int) : CountdownUnit
    data class Hours(val value: String) : CountdownUnit
}

private enum class CountdownPrefix { NONE, IN, ALARM_IN }

private const val COUNTDOWN_SLOW_TICK_MS = 30_000L
private const val COUNTDOWN_FAST_TICK_MS = 1_000L
private const val COUNTDOWN_STOP_GRACE_MS = 5_000L
// Placeholder in the localized "Alarm in %1$s" hero template; the numbers
// (big primary + superscript secondary) are spliced in at this slot.
private const val COUNTDOWN_SLOT = "%1\$s"
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val HALF_MINUTE_MILLIS = 30_000L
private const val QUARTER_MINUTE_MILLIS = 15_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val QUARTER_HOUR_MILLIS = 15 * MILLIS_PER_MINUTE
private const val HALF_QUARTER_HOUR_MILLIS = QUARTER_HOUR_MILLIS / 2
private const val QUARTERS_PER_HOUR = 4

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
