package com.kirkouski.gtalarm.ui.list

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kirkouski.gtalarm.data.AlarmRepository
import com.kirkouski.gtalarm.data.OnboardingState
import com.kirkouski.gtalarm.data.SettingsState
import com.kirkouski.gtalarm.data.SettingsStore
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.scheduler.AlarmScheduler
import com.kirkouski.gtalarm.wear.ForceSyncResult
import com.kirkouski.gtalarm.wear.PairedDeviceInfo
import com.kirkouski.gtalarm.wear.WatchSyncStatus
import com.kirkouski.gtalarm.wear.WearBridgeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlarmListUiState(
    val alarms: List<Alarm> = emptyList(),
    val canScheduleExact: Boolean = true,
)

@HiltViewModel
class AlarmListViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val onboarding: OnboardingState,
    private val wearBridge: WearBridgeService,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val alarms: StateFlow<List<Alarm>> = repository.observeAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Effective user preferences (24h flag + first-day-of-week). Drives the
     * list subtitle's time format and the week-day rendering order. Null
     * fields = follow system locale.
     */
    val settings: StateFlow<SettingsState> = settingsStore.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState(),
    )

    /**
     * Re-exposes the bridge's connection state to the list screen. The Hilt-
     * bound [HuaweiWearBridge] drives this — the card auto-updates as the
     * Wear Engine peer transitions through CONNECTING / CONNECTED / ERROR.
     */
    val watchStatus: StateFlow<WatchSyncStatus> = wearBridge.statusFlow

    /** Bonded device's name/model — shown in the WatchSyncCard subtitle. */
    val pairedDeviceInfo: StateFlow<PairedDeviceInfo?> = wearBridge.pairedDeviceInfo

    private val _forceSyncEvents = MutableSharedFlow<ForceSyncResult>(extraBufferCapacity = 1)
    /** One-shot events for showing the Force-sync result as a Snackbar/Toast. */
    val forceSyncEvents: SharedFlow<ForceSyncResult> = _forceSyncEvents.asSharedFlow()

    // Backed by a Mutex so consecutive taps of "Force sync" serialize
    // instead of racing N parallel coroutines through the bridge. The
    // Mutex is non-reentrant; isLocked check on the UI side would also
    // disable the button, but serialization here is the source of truth.
    private val forceSyncMutex = kotlinx.coroutines.sync.Mutex()
    private val _forceSyncRunning = MutableStateFlow(false)
    val forceSyncRunning: StateFlow<Boolean> = _forceSyncRunning.asStateFlow()

    // Wear Engine authorization gate. When the app lacks DEVICE_MANAGER
    // permission the WatchSyncCard swaps its force-sync button for an
    // authorize button. hasWatchPermission() is tri-state; only an explicit
    // `false` flips this on, so devices with no Huawei Health (null) keep the
    // default sync button rather than being mislabelled.
    private val _needsWatchAuthorization = MutableStateFlow(false)
    val needsWatchAuthorization: StateFlow<Boolean> = _needsWatchAuthorization.asStateFlow()
    private val lastSkipToggleAtById = mutableMapOf<Long, Long>()

    init {
        refreshWatchAuthorization()
    }

    /**
     * Re-check Wear Engine permission. Called on screen resume and after the
     * user returns from the Huawei Health authorize dialog.
     */
    fun refreshWatchAuthorization() = viewModelScope.launch {
        _needsWatchAuthorization.value = wearBridge.hasWatchPermission() == false
    }

    fun onForceSync() = viewModelScope.launch {
        if (!forceSyncMutex.tryLock()) {
            // Another sync is already in flight — skip silently so a fast
            // double-tap doesn't queue two pushes back-to-back.
            return@launch
        }
        try {
            _forceSyncRunning.value = true
            // syncWatchNow re-pushes display prefs (12/24h + week-start)
            // then runs the bridge's hash-prechecked forceSync. It's
            // mutex-serialized against the debounced auto-sync so a manual
            // tap and an in-flight auto-sync can't race the bridge.
            val result = repository.syncWatchNow()
            // forceSync checks permission before anything else, so any result
            // other than NotAuthorized proves the app is authorized.
            _needsWatchAuthorization.value = result is ForceSyncResult.NotAuthorized
            _forceSyncEvents.tryEmit(result)
        } finally {
            _forceSyncRunning.value = false
            forceSyncMutex.unlock()
        }
    }

    private val _showBatteryOptCard = MutableStateFlow(computeShowBatteryOptCard())
    val showBatteryOptCard: StateFlow<Boolean> = _showBatteryOptCard.asStateFlow()

    fun canScheduleExact(): Boolean = scheduler.canScheduleExact()

    fun refreshBatteryOptCard() {
        _showBatteryOptCard.value = computeShowBatteryOptCard()
    }

    fun dismissBatteryOptCard() {
        onboarding.markBatteryOptCardDismissed()
        _showBatteryOptCard.value = false
    }

    fun onToggle(id: Long, enabled: Boolean) = viewModelScope.launch {
        repository.setEnabled(id, enabled)
    }

    fun onDelete(id: Long) = viewModelScope.launch {
        repository.delete(id)
    }

    fun onToggleSkipNext(id: Long) = viewModelScope.launch {
        val now = SystemClock.elapsedRealtime()
        val lastToggleAt = lastSkipToggleAtById[id] ?: 0L
        if (now - lastToggleAt < SKIP_TOGGLE_GUARD_MS) return@launch
        lastSkipToggleAtById[id] = now
        repository.toggleSkipNext(id)
    }

    private fun computeShowBatteryOptCard(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return OnboardingState.shouldShowBatteryOptCard(
            dismissed = onboarding.batteryOptCardDismissed(),
            isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    private companion object {
        const val SKIP_TOGGLE_GUARD_MS = 900L
    }
}
