package com.kirkouski.gtwake.companion.ui.timer

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.SettingsState
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.domain.Alarm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

private const val STATE_TIMEOUT_MS = 5_000L
private const val START_GATE_MS = 900L
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** A second start this soon is a double tap: it would make two rows and two rings. */
internal fun timerStartAllowed(lastStartMs: Long?, nowMs: Long): Boolean =
    lastStartMs == null || nowMs - lastStartMs >= START_GATE_MS

/** `09:42`, or `1:09:42` from one hour. Rounds up, so a new 10 minute timer shows 10:00. */
internal fun formatCountdown(remainingMs: Long): String {
    val total = (remainingMs.coerceAtLeast(0L) + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
    val hours = total / SECONDS_PER_HOUR
    val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}

/**
 * The Timer tab. A timer is an ordinary relative [Alarm], so saving one covers
 * Room, the pre-unlock cache, the scheduler, widgets and the watch ring.
 */
@HiltViewModel
class TimerViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val settings: StateFlow<SettingsState> = settingsStore.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS), SettingsState(),
    )

    val running: StateFlow<List<Alarm>> = repository.observeAlarms()
        .map { alarms ->
            alarms.filter { it.isRelative && it.enabled }
                .sortedBy { it.snoozedUntilEpoch ?: it.computedFireEpoch() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS), emptyList())

    private var lastStartMs: Long? = null

    private val _startFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One-shot: a timer could not be saved, so it is not running. */
    val startFailed: SharedFlow<Unit> = _startFailed.asSharedFlow()

    fun start(minutes: Int) {
        val now = SystemClock.elapsedRealtime()
        if (!timerStartAllowed(lastStartMs, now)) return
        lastStartMs = now
        viewModelScope.launch {
            try {
                // Same reason as AlarmEditViewModel.save: leaving the tab must not
                // stop a save halfway.
                withContext(NonCancellable) {
                    val snooze = settingsStore.newTimerSnooze()
                    repository.save(Alarm.newTimer(minutes, snooze.minutes, snooze.maxCount))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // reason: Room and scheduler failures share no type, and a full disk must not crash the app.
                Log.w(TAG, "timer start failed: ${e.message}")
                _startFailed.tryEmit(Unit)
            }
        }
    }

    fun cancel(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    private companion object {
        const val TAG = "TimerViewModel"
    }
}
