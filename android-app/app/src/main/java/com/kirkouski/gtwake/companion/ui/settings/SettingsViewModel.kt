package com.kirkouski.gtwake.companion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.SettingsState
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.wear.ForceSyncResult
import com.kirkouski.gtwake.companion.wear.WatchSyncStatus
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
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
    private val wearBridge: WearBridgeService,
) : ViewModel() {

    // One-shot results of a list-sync switch change, for a toast. The Boolean
    // is the requested state, so the screen can word "removed" vs "sent".
    private val _watchListSyncResults =
        MutableSharedFlow<Pair<Boolean, ForceSyncResult>>(extraBufferCapacity = 1)
    val watchListSyncResults: SharedFlow<Pair<Boolean, ForceSyncResult>> =
        _watchListSyncResults.asSharedFlow()

    private val _watchSyncUnlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val watchSyncUnlocked: SharedFlow<Unit> = _watchSyncUnlocked.asSharedFlow()

    fun unlockWatchListSync() = viewModelScope.launch {
        if (store.unlockWatchListSync()) _watchSyncUnlocked.emit(Unit)
    }

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

    /**
     * Consent was already given in the UI for turning it on. The push runs in
     * the repository's own scope, so leaving Settings mid-sync does not cancel
     * it; this only waits for the result to show a toast.
     */
    fun setWatchListSync(enabled: Boolean) = viewModelScope.launch {
        val result = alarmRepository.changeWatchListSync(enabled).await()
        _watchListSyncResults.tryEmit(enabled to result)
    }

    /**
     * After picture consent: make sure the watch's screen size is known (list
     * sync off means no sync ever fetched it), then open the cropper. The
     * cropper still works without it, just without the watch-shaped frame.
     */
    fun prepareWatchPicturePicker(onReady: () -> Unit) = viewModelScope.launch {
        wearBridge.refreshWatchScreen()
        onReady()
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
