package com.kirkouski.gtwake.companion.ui.stopwatch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.stopwatch.Stopwatch
import com.kirkouski.gtwake.companion.stopwatch.StopwatchClock
import com.kirkouski.gtwake.companion.stopwatch.StopwatchLap
import com.kirkouski.gtwake.companion.stopwatch.formatStopwatch
import com.kirkouski.gtwake.companion.stopwatch.lapsAsText
import com.kirkouski.gtwake.companion.ui.components.FitText
import com.kirkouski.gtwake.companion.ui.components.GtAccentButton
import com.kirkouski.gtwake.companion.ui.components.GtFloatingButton
import com.kirkouski.gtwake.companion.ui.components.GtSnackbarHost
import com.kirkouski.gtwake.companion.ui.components.bottomPillFade
import com.kirkouski.gtwake.companion.ui.components.showUndo
import kotlinx.coroutines.launch

@Composable
fun StopwatchScreen(vm: StopwatchViewModel = hiltViewModel()) {
    val stopwatch by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resetText = stringResource(R.string.stopwatch_reset_done)
    val undoText = stringResource(R.string.action_undo)
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val fadeEdgePx = remember(navBarPadding) { with(density) { (navBarPadding + 38.dp).toPx() } }
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Lifted above the bottom pill, which floats over this screen.
            GtSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = navBarPadding + 76.dp),
            )
        },
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StopwatchCard(
                    stopwatch = stopwatch,
                    clock = vm::clock,
                    onStart = vm::start,
                    onPause = vm::pause,
                    onLap = vm::lap,
                    onReset = {
                        val previous = stopwatch
                        vm.reset()
                        scope.launch {
                            snackbarHostState.showUndo(resetText, undoText) { vm.restore(previous) }
                        }
                    },
                )
                val laps = stopwatch.laps()
                if (laps.isNotEmpty()) LapsCard(laps)
                Spacer(Modifier.height(navBarPadding + 88.dp))
            }
        }
    }
}

@Composable
private fun StopwatchCard(
    stopwatch: Stopwatch,
    clock: () -> StopwatchClock,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onLap: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StopwatchTime(stopwatch, clock)
            // The main action is always on the right.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (stopwatch.phase) {
                    Stopwatch.Phase.Idle -> Unit
                    Stopwatch.Phase.Running -> SecondaryButton(R.string.stopwatch_lap, onLap)
                    Stopwatch.Phase.Paused -> SecondaryButton(R.string.stopwatch_reset, onReset)
                }
                val running = stopwatch.phase == Stopwatch.Phase.Running
                GtAccentButton(
                    onClick = if (running) onPause else onStart,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    FitText(stringResource(mainActionRes(stopwatch.phase)))
                }
            }
        }
    }
}

private fun mainActionRes(phase: Stopwatch.Phase): Int = when (phase) {
    Stopwatch.Phase.Idle -> R.string.action_start
    Stopwatch.Phase.Running -> R.string.stopwatch_stop
    Stopwatch.Phase.Paused -> R.string.stopwatch_resume
}

@Composable
private fun RowScope.SecondaryButton(labelRes: Int, onClick: () -> Unit) {
    GtFloatingButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        FitText(stringResource(labelRes))
    }
}

// A small leaf, so per-frame updates recompose only this text. Stops below STARTED.
@Composable
private fun StopwatchTime(stopwatch: Stopwatch, clock: () -> StopwatchClock) {
    var shown by remember { mutableLongStateOf(stopwatch.totalAt(clock())) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(stopwatch) {
        shown = stopwatch.totalAt(clock())
        if (stopwatch.phase == Stopwatch.Phase.Running) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    withFrameMillis { }
                    shown = stopwatch.totalAt(clock())
                }
            }
        }
    }
    Text(
        text = formatStopwatch(shown),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        fontSize = 52.sp,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
    )
}

@Composable
private fun LapsCard(laps: List<StopwatchLap>) {
    val context = LocalContext.current
    val divider = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val resources = LocalResources.current
    val copiedText = stringResource(R.string.stopwatch_copied)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.stopwatch_laps),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val text = lapsAsText(laps) { resources.getString(R.string.stopwatch_lap_number, it) }
                        copyLaps(context, text, copiedText)
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = stringResource(R.string.stopwatch_copy_laps),
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            laps.forEach { lap ->
                HorizontalDivider(thickness = 0.5.dp, color = divider)
                LapRow(lap)
            }
        }
    }
}

@Composable
private fun LapRow(lap: StopwatchLap) {
    val digits = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(text = lap.number.toString(), style = digits, modifier = Modifier.weight(0.4f))
        FitText(text = formatStopwatch(lap.lapMs), style = digits, modifier = Modifier.weight(1f))
        FitText(
            text = formatStopwatch(lap.totalMs),
            style = digits,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun copyLaps(context: Context, text: String, copiedText: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(copiedText, text))
    // Android 13 and later show their own clipboard message.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
    }
}
