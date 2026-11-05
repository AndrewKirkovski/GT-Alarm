package com.kirkouski.gtwake.companion.ui.timer

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.ui.components.FitText
import com.kirkouski.gtwake.companion.ui.components.GtAccentButton
import com.kirkouski.gtwake.companion.ui.components.GtFloatingButton
import com.kirkouski.gtwake.companion.ui.components.GtSnackbarHost
import com.kirkouski.gtwake.companion.ui.components.bottomPillFade
import com.kirkouski.gtwake.companion.ui.edit.RelativeRow
import com.kirkouski.gtwake.companion.util.TimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import java.util.Calendar

private val PRESET_MINUTES = listOf(10, 15, 30)
private const val INITIAL_MINUTES = 5
private const val SECOND_MS = 1_000L

@Composable
fun TimerScreen(
    onEdit: (Long) -> Unit,
    vm: TimerViewModel = hiltViewModel(),
) {
    val running by vm.running.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val is24h = TimeFormatter.resolveUses24HourFormat(LocalContext.current, settings.use24Hour)
    var minutes by rememberSaveable { mutableIntStateOf(INITIAL_MINUTES) }
    var pendingCancel by remember { mutableStateOf<Alarm?>(null) }
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val fadeEdgePx = remember(navBarPadding) { with(density) { (navBarPadding + 38.dp).toPx() } }

    pendingCancel?.let { timer ->
        CancelTimerDialog(
            onConfirm = {
                pendingCancel = null
                vm.cancel(timer.id)
            },
            onDismiss = { pendingCancel = null },
        )
    }
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { StartFailedSnackbar(vm.startFailed, navBarPadding) },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .bottomPillFade(fadeEdgePx),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (running.isNotEmpty()) {
                    RunningTimersCard(
                        timers = running,
                        is24h = is24h,
                        onOpen = onEdit,
                        onCancel = { pendingCancel = it },
                    )
                }
                NewTimerCard(
                    minutes = minutes,
                    onMinutesChange = { minutes = it },
                    onStart = vm::start,
                )
                Spacer(Modifier.height(navBarPadding + 88.dp))
            }
        }
    }
}

// Lifted above the bottom pill, which floats over this screen.
@Composable
private fun StartFailedSnackbar(failures: SharedFlow<Unit>, navBarPadding: Dp) {
    val hostState = remember { SnackbarHostState() }
    val text = stringResource(R.string.timer_start_failed)
    LaunchedEffect(failures) {
        failures.collect { hostState.showSnackbar(text) }
    }
    GtSnackbarHost(hostState = hostState, modifier = Modifier.padding(bottom = navBarPadding + 76.dp))
}

@Composable
private fun NewTimerCard(minutes: Int, onMinutesChange: (Int) -> Unit, onStart: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                WheelLabel(R.string.timer_hours)
                WheelLabel(R.string.timer_minutes)
            }
            RelativeRow(minutes = minutes, onChange = onMinutesChange)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESET_MINUTES.forEach { preset ->
                    GtFloatingButton(
                        onClick = { onStart(preset) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        FitText(stringResource(R.string.field_snooze_minutes_value, preset))
                    }
                }
            }
            GtAccentButton(
                onClick = { onStart(minutes) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_start))
            }
        }
    }
}

@Composable
private fun RowScope.WheelLabel(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun RunningTimersCard(
    timers: List<Alarm>,
    is24h: Boolean,
    onOpen: (Long) -> Unit,
    onCancel: (Alarm) -> Unit,
) {
    val divider = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        timers.forEachIndexed { index, timer ->
            if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = divider)
            RunningTimerRow(
                timer = timer,
                is24h = is24h,
                onOpen = { onOpen(timer.id) },
                onCancel = { onCancel(timer) },
            )
        }
    }
}

@Composable
private fun RunningTimerRow(timer: Alarm, is24h: Boolean, onOpen: () -> Unit, onCancel: () -> Unit) {
    val target = timer.snoozedUntilEpoch ?: timer.computedFireEpoch()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatCountdown(rememberRemaining(target)),
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = timerSubtitle(timer, target, is24h),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GtFloatingButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun timerSubtitle(timer: Alarm, target: Long, is24h: Boolean): String {
    val calendar = Calendar.getInstance().also { it.timeInMillis = target }
    val time = TimeFormatter.formatHourMinute(
        LocalContext.current,
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
        is24h,
    )
    if (timer.snoozedUntilEpoch != null) return stringResource(R.string.timer_snoozed_until, time)
    val name = timer.label.ifBlank {
        stringResource(R.string.field_snooze_minutes_value, timer.relativeMinutes ?: 0)
    }
    return stringResource(R.string.timer_ends_at, name, time)
}

@Composable
private fun rememberRemaining(target: Long): Long {
    var now by remember(target) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(target) {
        while (now < target) {
            delay(SECOND_MS - now % SECOND_MS)
            now = System.currentTimeMillis()
        }
    }
    return target - now
}

@Composable
private fun CancelTimerDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timer_cancel_title)) },
        text = { Text(stringResource(R.string.timer_cancel_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.timer_cancel_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.timer_keep)) }
        },
    )
}
