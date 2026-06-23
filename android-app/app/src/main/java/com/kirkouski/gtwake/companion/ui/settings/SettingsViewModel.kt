package com.kirkouski.gtwake.companion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.SettingsState
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.wear.WatchSyncStatus
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Minimal Hilt VM for the Settings screen — exposes the persisted
 * [SettingsState] as a hot StateFlow and routes user input back to
 * [SettingsStore]. No local state; the screen always reflects the
 * persisted value, so a back/forward navigation pair restores cleanly.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val alarmRepository: AlarmRepository,
    wearBridge: WearBridgeService,
) : ViewModel() {

    val state: StateFlow<SettingsState> = store.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS),
        SettingsState(),
    )

    /**
     * Watch connection status — read DIRECTLY from the bridge's single, global,
     * monitor-maintained StateFlow. NOT re-wrapped in stateIn (that reset to a
     * NOT_CONNECTED initial on every open and made the watch-bg block flicker
     * hidden→shown). Collecting a StateFlow reads its CURRENT value instantly, so
     * the block renders against the already-known status with no flicker, and we
     * make no per-screen status request — the app-wide monitor just keeps it
     * current. The watch-bg block is rendered conditionally on this being
     * [WatchSyncStatus.CONNECTED].
     */
    val watchStatus: StateFlow<WatchSyncStatus> = wearBridge.statusFlow

    fun setUse24Hour(value: Boolean?) = viewModelScope.launch {
        store.setUse24Hour(value)
        alarmRepository.scheduleWatchSync()
    }

    fun setFirstDayOfWeek(value: Int?) = viewModelScope.launch {
        store.setFirstDayOfWeek(value)
        alarmRepository.scheduleWatchSync()
    }

    fun setDefaultAbsoluteRingtone(uri: String?, name: String?) = viewModelScope.launch {
        store.setDefaultAbsoluteRingtone(uri, name)
    }

    fun setDefaultRelativeRingtone(uri: String?, name: String?) = viewModelScope.launch {
        store.setDefaultRelativeRingtone(uri, name)
    }

    fun setDefaultPhoneBackground(uri: String?) = viewModelScope.launch {
        store.setDefaultPhoneBackgroundUri(uri)
    }

    fun setDefaultWatchBackground(uri: String?) = viewModelScope.launch {
        val action = if (uri == null) "clear" else "upload"
        android.util.Log.i("SettingsVM", "setDefaultWatchBackground uri=$uri -> $action")
        store.setDefaultWatchBackgroundUri(uri)
        // Settings owns the single shared watch bg (no per-alarm field).
        // Clear is an explicit branch — it deletes the cached crop + derived
        // artifacts and sends the clearance envelope. Inferring "cleared" from
        // a missing cache file silently re-uploaded the stale crop instead.
        if (uri == null) {
            alarmRepository.clearDefaultWatchBackground()
        } else {
            alarmRepository.uploadDefaultWatchBackground()
        }
    }

    private companion object {
        // Matches the cadence used by [AlarmListViewModel.alarms] —
        // 5-second tail keeps the flow alive across short config changes.
        const val STATE_TIMEOUT_MS = 5_000L
    }
}
