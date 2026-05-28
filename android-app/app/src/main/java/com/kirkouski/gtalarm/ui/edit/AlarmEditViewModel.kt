package com.kirkouski.gtalarm.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kirkouski.gtalarm.data.AlarmRepository
import com.kirkouski.gtalarm.data.SettingsState
import com.kirkouski.gtalarm.data.SettingsStore
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import com.kirkouski.gtalarm.ring.EditingAlarmRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class AlarmMode { ABSOLUTE, RELATIVE }

data class AlarmEditUiState(
    val id: Long = 0L,
    val label: String = "",
    val hour: Int = 7,
    val minute: Int = 0,
    val daysOfWeek: Int = DaysOfWeek.NONE,
    val enabled: Boolean = true,
    val audioUri: String? = null,
    val audioName: String? = null,
    val isVibrationOnly: Boolean = false,
    val backgroundImageUri: String? = null,
    val snoozeMinutes: Int = Alarm.DEFAULT_SNOOZE_MINUTES,
    val mode: AlarmMode = AlarmMode.ABSOLUTE,
    val relativeMinutes: Int = 15,
    val selfDestruct: Boolean = true,
    val selfDestructUserSet: Boolean = false,
    val vibrationPattern: com.kirkouski.gtalarm.domain.VibrationPattern =
        com.kirkouski.gtalarm.domain.VibrationPattern.DEFAULT,
    val volumeRampSeconds: Int = 0,
    val maxSnoozeCount: Int = Alarm.MAX_SNOOZE_COUNT_UNLIMITED,
    val isExistingAlarm: Boolean = false,
    val loaded: Boolean = false,
    val dirty: Boolean = false,
)

/**
 * Explicit save/cancel edit model:
 *
 *   1. On open, captures the loaded alarm into [_state]. New drafts start
 *      with default values (no DB row exists yet).
 *   2. Every field mutation updates [_state] only. Nothing is written to
 *      Room and nothing is pushed to the watch until [save] is called.
 *   3. [save] writes the alarm once (insert for new, update for existing),
 *      pushes to the watch, clears the editing-registry flag, and
 *      reschedules.
 *   4. [cancel] discards in-memory state. New-draft alarms vanish (no DB
 *      row was ever created); existing alarms remain at their pre-edit
 *      state in Room.
 *   5. [delete] is an explicit destructive action — bypasses save/cancel
 *      and removes the row immediately.
 *
 * Watch-background image is owned solely by Settings (one shared default
 * for all alarms). The alarm edit screen does NOT pick a per-alarm watch
 * background — Settings → Default watch background is the single entry
 * point. See [com.kirkouski.gtalarm.ui.settings.SettingsViewModel.setDefaultWatchBackground].
 */
// reason: TooManyFunctions — VM owns one updater per editable alarm field
// (label, time, days, audio, vibration, snooze, mode, relative, selfDestruct,
// backgroundImage) + lifecycle hooks (save, cancel, delete) + state-derivation
// helpers (loadFromAlarm, buildAlarm). Each maps to one UI control or one
// lifecycle event; splitting would smear the same _state mutation channel
// across more files.
@Suppress("TooManyFunctions")
@HiltViewModel
class AlarmEditViewModel @Inject constructor(
    private val repository: AlarmRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AlarmEditUiState())
    val state: StateFlow<AlarmEditUiState> = _state.asStateFlow()

    val settings: StateFlow<SettingsState> = settingsStore.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS), SettingsState(),
    )

    fun load(id: Long?, initialMode: AlarmMode = AlarmMode.ABSOLUTE) {
        if (_state.value.loaded && _state.value.id == (id ?: 0L)) return
        viewModelScope.launch {
            val alarm = id?.let { repository.getById(it) }
            if (alarm == null) {
                _state.value = AlarmEditUiState(loaded = true, isExistingAlarm = false, mode = initialMode)
            } else {
                _state.value = stateFromAlarm(alarm).copy(loaded = true)
                // Mark this alarm as "being edited" so AlarmRingService bails
                // if its fire trigger arrives while we're on this screen.
                // Cleared by save() / delete() / cancel().
                EditingAlarmRegistry.setEditing(alarm.id)
            }
        }
    }

    fun updateTime(hour: Int, minute: Int) = mutate { it.copy(hour = hour, minute = minute) }
    fun updateLabel(label: String) = mutate {
        // Truncate at input so the Alarm.init MAX_LABEL_LENGTH require()
        // can't crash save() inside the NonCancellable block (which would
        // be a silent failure — no toast, no log, no navigation). 256 is
        // already way past any reasonable label.
        it.copy(label = label.take(Alarm.MAX_LABEL_LENGTH))
    }

    fun toggleDay(day: Int) = mutate {
        val newDays = DaysOfWeek.toggle(it.daysOfWeek, day)
        val newSelfDestruct = if (newDays != 0) false else it.selfDestruct
        val newUserSet = if (newDays != 0) false else it.selfDestructUserSet
        it.copy(
            daysOfWeek = newDays,
            selfDestruct = newSelfDestruct,
            selfDestructUserSet = newUserSet,
        )
    }

    fun updateAudio(uri: String?, name: String?) = mutate {
        it.copy(audioUri = uri, audioName = name)
    }

    fun updateBackgroundImage(uri: String?) = mutate {
        it.copy(backgroundImageUri = uri)
    }

    fun toggleVibrationOnly() = mutate { it.copy(isVibrationOnly = !it.isVibrationOnly) }

    fun updateSnoozeMinutes(minutes: Int) = mutate {
        // SNOOZE_DISABLED (0) is a sentinel meaning "snooze is off"; anything
        // else clamps into the enabled range so a stray slider tick can't
        // produce an invalid duration.
        val clamped = if (minutes <= Alarm.SNOOZE_DISABLED) {
            Alarm.SNOOZE_DISABLED
        } else {
            minutes.coerceIn(Alarm.MIN_SNOOZE_MINUTES, Alarm.MAX_SNOOZE_MINUTES)
        }
        it.copy(snoozeMinutes = clamped)
    }

    fun updateMode(mode: AlarmMode) = mutate {
        val newDays = if (mode == AlarmMode.RELATIVE) 0 else it.daysOfWeek
        val newSelfDestruct = if (it.selfDestructUserSet) {
            it.selfDestruct
        } else {
            mode == AlarmMode.RELATIVE || newDays == 0
        }
        val newRelativeMinutes = it.relativeMinutes
        it.copy(
            mode = mode,
            daysOfWeek = newDays,
            selfDestruct = newSelfDestruct,
            relativeMinutes = newRelativeMinutes,
        )
    }

    fun updateRelativeMinutes(minutes: Int) = mutate {
        it.copy(
            relativeMinutes = minutes.coerceIn(
                Alarm.MIN_RELATIVE_MINUTES,
                Alarm.MAX_RELATIVE_MINUTES,
            ),
        )
    }

    fun toggleSelfDestruct() = mutate {
        if (it.mode == AlarmMode.ABSOLUTE && it.daysOfWeek != 0) {
            it.copy(selfDestruct = false, selfDestructUserSet = true)
        } else {
            it.copy(selfDestruct = !it.selfDestruct, selfDestructUserSet = true)
        }
    }

    fun updateVibrationPattern(pattern: com.kirkouski.gtalarm.domain.VibrationPattern) = mutate {
        it.copy(vibrationPattern = pattern)
    }

    fun updateVolumeRampSeconds(seconds: Int) = mutate {
        it.copy(volumeRampSeconds = seconds.coerceIn(0, Alarm.MAX_VOLUME_RAMP_SECONDS))
    }

    fun updateMaxSnoozeCount(count: Int) = mutate {
        val clamped = when {
            count <= Alarm.MAX_SNOOZE_COUNT_UNLIMITED -> Alarm.MAX_SNOOZE_COUNT_UNLIMITED
            else -> count.coerceAtMost(Alarm.MAX_SNOOZE_COUNT_CAP)
        }
        it.copy(maxSnoozeCount = clamped)
    }

    /**
     * In-memory state mutator. Sets `dirty = true` only if the transform
     * actually changes state — otherwise the TimePicker's initial
     * snapshotFlow emit (which arrives after load with the loaded hour /
     * minute) would falsely flip dirty on a freshly-opened pristine alarm.
     *
     * **Bug #92 loaded gate** still applies as defense-in-depth: pre-load
     * emits get dropped entirely, never reaching the comparison.
     */
    private fun mutate(transform: (AlarmEditUiState) -> AlarmEditUiState) {
        if (!_state.value.loaded) return
        _state.update {
            val next = transform(it)
            if (next == it) it else next.copy(dirty = true)
        }
    }

    /**
     * Commit the current in-memory state to Room, push to watch,
     * reschedule, and clear the editing flag. Caller is responsible for
     * invoking only when state.loaded is true.
     */
    fun save(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            val snapshot = _state.value
            val alarm = buildAlarm(snapshot)
            // NonCancellable guards against the caller popping the back-
            // stack inside onComplete: navigating away clears the VM,
            // which cancels viewModelScope. Without this, saveLocalOnly /
            // rescheduleAlarm could abort partway through and leave Room +
            // scheduler in disagreement. scheduleWatchSync only arms a
            // debounced job on the repo's appScope, so it survives anyway.
            withContext(NonCancellable) {
                val savedId = repository.saveLocalOnly(alarm)
                EditingAlarmRegistry.clearEditing(savedId)
                repository.rescheduleAlarm(savedId)
                repository.scheduleWatchSync()
            }
            onComplete()
        }
    }

    /**
     * Discard in-memory edits and clear the editing flag. New drafts vanish
     * (no DB row was created). Existing alarms remain at their pre-edit Room
     * state. Safe to call multiple times.
     */
    fun cancel() {
        val id = _state.value.id
        if (id > 0L) EditingAlarmRegistry.clearEditing(id)
    }

    /**
     * Delete the alarm (existing-only). Confirms via caller dialog. Pushes
     * the deletion to the watch via the repository's regular sync path.
     */
    fun delete() = viewModelScope.launch {
        val id = _state.value.id
        if (id != 0L) {
            repository.delete(id)
            EditingAlarmRegistry.clearEditing(id)
        }
    }

    override fun onCleared() {
        // Defense-in-depth: clear the editing-registry flag in case the VM
        // is destroyed without an explicit save/cancel/delete (process death,
        // task removal). No DB writes here — the explicit save/cancel model
        // means undismissed VMs lose their in-memory edits, which is the
        // intended contract.
        val id = _state.value.id
        if (id > 0L) EditingAlarmRegistry.clearEditing(id)
        super.onCleared()
    }

    private fun buildAlarm(s: AlarmEditUiState): Alarm {
        val daysOfWeek = if (s.mode == AlarmMode.RELATIVE) 0 else s.daysOfWeek
        val selfDestruct = s.selfDestruct && daysOfWeek == 0
        val relativeMinutes = if (s.mode == AlarmMode.RELATIVE) {
            s.relativeMinutes.coerceIn(Alarm.MIN_RELATIVE_MINUTES, Alarm.MAX_RELATIVE_MINUTES)
        } else {
            null
        }
        return Alarm(
            id = s.id,
            label = s.label,
            hour = s.hour,
            minute = s.minute,
            daysOfWeek = daysOfWeek,
            enabled = s.enabled,
            audioUri = s.audioUri,
            audioName = s.audioName,
            isVibrationOnly = s.isVibrationOnly,
            snoozeMinutes = if (s.snoozeMinutes <= Alarm.SNOOZE_DISABLED) {
                Alarm.SNOOZE_DISABLED
            } else {
                s.snoozeMinutes.coerceIn(Alarm.MIN_SNOOZE_MINUTES, Alarm.MAX_SNOOZE_MINUTES)
            },
            relativeMinutes = relativeMinutes,
            selfDestruct = selfDestruct,
            backgroundImageUri = s.backgroundImageUri,
            vibrationPattern = s.vibrationPattern,
            volumeRampSeconds = s.volumeRampSeconds.coerceIn(0, Alarm.MAX_VOLUME_RAMP_SECONDS),
            maxSnoozeCount = when {
                s.maxSnoozeCount <= Alarm.MAX_SNOOZE_COUNT_UNLIMITED -> Alarm.MAX_SNOOZE_COUNT_UNLIMITED
                else -> s.maxSnoozeCount.coerceAtMost(Alarm.MAX_SNOOZE_COUNT_CAP)
            },
        )
    }

    private fun stateFromAlarm(alarm: Alarm): AlarmEditUiState = AlarmEditUiState(
        id = alarm.id,
        label = alarm.label,
        hour = alarm.hour,
        minute = alarm.minute,
        daysOfWeek = alarm.daysOfWeek,
        enabled = alarm.enabled,
        audioUri = alarm.audioUri,
        audioName = alarm.audioName,
        isVibrationOnly = alarm.isVibrationOnly,
        backgroundImageUri = alarm.backgroundImageUri,
        snoozeMinutes = alarm.snoozeMinutes,
        mode = if (alarm.isRelative) AlarmMode.RELATIVE else AlarmMode.ABSOLUTE,
        relativeMinutes = alarm.relativeMinutes ?: DEFAULT_RELATIVE_MINUTES,
        selfDestruct = alarm.selfDestruct,
        selfDestructUserSet = false,
        vibrationPattern = alarm.vibrationPattern,
        volumeRampSeconds = alarm.volumeRampSeconds,
        maxSnoozeCount = alarm.maxSnoozeCount,
        isExistingAlarm = true,
        loaded = true,
    )

    private companion object {
        const val DEFAULT_RELATIVE_MINUTES = 15
        const val STATE_TIMEOUT_MS = 5_000L
    }
}
