package com.kirkouski.gtalarm.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.kirkouski.gtalarm.util.rememberLocaleOrderedDayBits
import com.kirkouski.gtalarm.util.shortLabelResForDayBit

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
    vm: AlarmListViewModel = hiltViewModel(),
) {
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val canExact = vm.canScheduleExact()
    val showBatteryOptCard by vm.showBatteryOptCard.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.screen_list_title)) })
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
                    Text(stringResource(R.string.no_alarms))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    alarm: Alarm,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart ||
            dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd
        ) {
            onDelete()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
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
        AlarmRow(alarm = alarm, onToggle = onToggle, onClick = onClick)
    }
}

@Composable
private fun AlarmRow(
    alarm: Alarm,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ctx = LocalContext.current
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = TimeFormatter.formatHourMinute(ctx, alarm.hour, alarm.minute),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                )
                if (alarm.label.isNotBlank()) {
                    Text(alarm.label, fontSize = 14.sp)
                }
                Text(
                    text = subtitleLine(alarm),
                    fontSize = 12.sp,
                )
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun subtitleLine(alarm: Alarm): String {
    val days = daysLabel(alarm.daysOfWeek)
    if (!alarm.enabled) return days
    // Recompute on alarm content change so list updates after edit/toggle.
    // Doesn't tick — relative text is approximate by design (FORMAT_ABBREV_RELATIVE
    // collapses sub-minute intervals). The list refreshes on every list-screen
    // resume via observeAlarms() emission, which is sufficient for our cadence.
    val nextTrigger = remember(alarm) { NextTriggerCalculator.nextTriggerEpochMillis(alarm) }
    val relative = RelativeTime.formatUntil(nextTrigger)
    return if (relative.isEmpty()) days else "$days  ·  $relative"
}

@Composable
private fun daysLabel(mask: Int): String = when (mask) {
    DaysOfWeek.NONE -> stringResource(R.string.repeats_once)
    DaysOfWeek.ALL -> stringResource(R.string.repeats_every_day)
    DaysOfWeek.WEEKDAYS -> stringResource(R.string.repeats_weekdays)
    DaysOfWeek.WEEKENDS -> stringResource(R.string.repeats_weekends)
    else -> rememberLocaleOrderedDayBits()
        .mapNotNull { bit ->
            if (DaysOfWeek.contains(mask, bit)) stringResource(shortLabelResForDayBit(bit)) else null
        }
        .joinToString(" ")
}
