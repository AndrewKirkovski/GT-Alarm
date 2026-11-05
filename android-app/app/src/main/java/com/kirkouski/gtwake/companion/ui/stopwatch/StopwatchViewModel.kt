package com.kirkouski.gtwake.companion.ui.stopwatch

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kirkouski.gtwake.companion.stopwatch.Stopwatch
import com.kirkouski.gtwake.companion.stopwatch.StopwatchClock
import com.kirkouski.gtwake.companion.stopwatch.StopwatchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STATE_TIMEOUT_MS = 5_000L
private const val START_GUARD_MS = 400L

/** Stop and Lap appear under the finger that tapped Start, so a double tap must not reach them. */
internal fun stopwatchTapAllowed(lastStartMs: Long?, nowMs: Long): Boolean =
    lastStartMs == null || nowMs - lastStartMs >= START_GUARD_MS

@HiltViewModel
class StopwatchViewModel @Inject constructor(
    private val repository: StopwatchRepository,
) : ViewModel() {

    val state: StateFlow<Stopwatch> = repository.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS), Stopwatch(),
    )

    fun clock(): StopwatchClock = repository.clock()

    private var lastStartMs: Long? = null

    fun start() {
        lastStartMs = SystemClock.elapsedRealtime()
        update { stopwatch, clock -> stopwatch.start(clock) }
    }

    fun pause() {
        if (tapAllowed()) update { stopwatch, clock -> stopwatch.pause(clock) }
    }

    fun lap() {
        if (tapAllowed()) update { stopwatch, clock -> stopwatch.lap(clock) }
    }

    fun reset() = update { _, _ -> Stopwatch() }

    /** Undo for Reset. */
    fun restore(previous: Stopwatch) {
        viewModelScope.launch { repository.restore(previous) }
    }

    private fun tapAllowed() = stopwatchTapAllowed(lastStartMs, SystemClock.elapsedRealtime())

    private fun update(transform: (Stopwatch, StopwatchClock) -> Stopwatch) {
        viewModelScope.launch { repository.update(transform) }
    }
}
