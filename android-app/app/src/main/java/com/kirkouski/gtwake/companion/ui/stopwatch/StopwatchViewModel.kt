package com.kirkouski.gtwake.companion.ui.stopwatch

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

@HiltViewModel
class StopwatchViewModel @Inject constructor(
    private val repository: StopwatchRepository,
) : ViewModel() {

    val state: StateFlow<Stopwatch> = repository.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS), Stopwatch(),
    )

    fun clock(): StopwatchClock = repository.clock()

    fun start() = update { stopwatch, clock -> stopwatch.start(clock) }
    fun pause() = update { stopwatch, clock -> stopwatch.pause(clock) }
    fun lap() = update { stopwatch, clock -> stopwatch.lap(clock) }
    fun reset() = update { _, _ -> Stopwatch() }

    /** Undo for Reset. */
    fun restore(previous: Stopwatch) {
        viewModelScope.launch { repository.restore(previous) }
    }

    private fun update(transform: (Stopwatch, StopwatchClock) -> Stopwatch) {
        viewModelScope.launch { repository.update(transform) }
    }
}
