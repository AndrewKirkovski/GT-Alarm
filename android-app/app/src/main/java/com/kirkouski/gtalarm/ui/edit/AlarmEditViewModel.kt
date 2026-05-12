package com.kirkouski.gtalarm.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kirkouski.gtalarm.data.AlarmRepository
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val snoozeMinutes: Int = Alarm.DEFAULT_SNOOZE_MINUTES,
    val mode: AlarmMode = AlarmMode.ABSOLUTE,
    val relativeMinutes: Int = 15,
    // Default self-destruct ON for new one-shot absolute + all relative; OFF
    // for recurring. Recomputed on mode/daysOfWeek transitions unless the
    // user has explicitly toggled (tracked by `selfDestructUserSet`).
    val selfDestruct: Boolean = true,
    val selfDestructUserSet: Boolean = false,
    // True when the loaded alarm is an existing row (editing). Mode toggle
    // is hidden in that case — to switch types, user deletes + recreates.
    val isExistingAlarm: Boolean = false,
    val loaded: Boolean = false,
    // True if at least one field has been changed since the screen opened.
    // Drives Revert button visibility (revert is meaningful only when dirty
    // and the screen is editing an existing alarm with a snapshot).
    val dirty: Boolean = false,
)

/**
 * Reverse-save edit model:
 *
 *   1. On open, capture an immutable [openSnapshot] of the alarm (or null
 *      for a brand-new draft).
 *   2. Every field mutation writes to local DB immediately via
 *      [AlarmRepository.saveLocalOnly]. Watch is NOT notified per-keystroke.
 *   3. On exit ([flushPendingToWatch]), the latest state is broadcast once
 *      to the watch.
 *   4. [revert] restores the snapshot (re-writes original state to DB);
 *      [discardNewDraft] removes the in-progress new-alarm row.
 *
 * Why: keeps the watch's notification feed silent during a multi-second
 * edit and avoids N redundant P2P pushes per alarm. Phone is the source
 * of truth either way, so a process kill mid-edit just leaves the watch
 * one sync behind — the next force-sync hash compare will catch it.
 */
// reason: function count crossed 15 because each user-facing field has its
// own mutator (updateTime, updateLabel, toggleDay, updateAudio,
// toggleVibrationOnly, updateSnoozeMinutes, updateMode, updateRelativeMinutes,
// toggleSelfDestruct) plus the reverse-save lifecycle helpers (load, applyLocal,
// revert, delete, discardNewDraft, flushPendingToWatch, onCleared, buildAlarm,
// stateFromAlarm). Splitting into helper objects would just route the same
// state through more indirection without changing logic.
@Suppress("TooManyFunctions")
@HiltViewModel
class AlarmEditViewModel @Inject constructor(
    private val repository: AlarmRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AlarmEditUiState())
    val state: StateFlow<AlarmEditUiState> = _state.asStateFlow()

    // Snapshot of the alarm as it existed when the screen opened. null for
    // a new draft. Used by [revert] to undo all in-session edits.
    private var openSnapshot: Alarm? = null

    // Set of alarm ids whose state has been mutated locally since the last
    // flush. On exit we broadcast each one to the watch. A Set (not single
    // id) because [revert] can change the id (no, ids are stable — but the
    // set also covers the case where a future "swap alarm" UX exists).
    private val pendingWatchPushIds = mutableSetOf<Long>()

    fun load(id: Long?) {
        if (_state.value.loaded && _state.value.id == (id ?: 0L)) return
        viewModelScope.launch {
            val alarm = id?.let { repository.getById(it) }
            if (alarm == null) {
                openSnapshot = null
                _state.value = AlarmEditUiState(loaded = true, isExistingAlarm = false)
            } else {
                openSnapshot = alarm
                _state.value = stateFromAlarm(alarm).copy(loaded = true)
            }
        }
    }

    fun updateTime(hour: Int, minute: Int) = applyLocal { it.copy(hour = hour, minute = minute) }
    fun updateLabel(label: String) = applyLocal { it.copy(label = label) }

    fun toggleDay(day: Int) = applyLocal {
        val newDays = DaysOfWeek.toggle(it.daysOfWeek, day)
        // Self-destruct is illegal with recurring (per spec). When the user
        // turns the alarm recurring, force-clear selfDestruct AND reset the
        // user-set flag so a later return to one-shot re-applies the default.
        val newSelfDestruct = if (newDays != 0) false else it.selfDestruct
        val newUserSet = if (newDays != 0) false else it.selfDestructUserSet
        it.copy(
            daysOfWeek = newDays,
            selfDestruct = newSelfDestruct,
            selfDestructUserSet = newUserSet,
        )
    }

    fun updateAudio(uri: String?, name: String?) = applyLocal {
        it.copy(audioUri = uri, audioName = name)
    }

    fun toggleVibrationOnly() = applyLocal { it.copy(isVibrationOnly = !it.isVibrationOnly) }

    fun updateSnoozeMinutes(minutes: Int) = applyLocal {
        it.copy(snoozeMinutes = minutes.coerceIn(Alarm.MIN_SNOOZE_MINUTES, Alarm.MAX_SNOOZE_MINUTES))
    }

    fun updateMode(mode: AlarmMode) = applyLocal {
        // Switching to RELATIVE forces daysOfWeek=0 (relative is always
        // one-shot). Default selfDestruct=true unless user explicitly opted out.
        val newDays = if (mode == AlarmMode.RELATIVE) 0 else it.daysOfWeek
        val newSelfDestruct = if (it.selfDestructUserSet) {
            it.selfDestruct
        } else {
            mode == AlarmMode.RELATIVE || newDays == 0
        }
        // Reset relativeMinutes to its default when leaving RELATIVE mode so
        // a later return to RELATIVE doesn't pick up a stale custom value.
        val newRelativeMinutes = if (mode == AlarmMode.ABSOLUTE) DEFAULT_RELATIVE_MINUTES else it.relativeMinutes
        it.copy(
            mode = mode,
            daysOfWeek = newDays,
            selfDestruct = newSelfDestruct,
            relativeMinutes = newRelativeMinutes,
        )
    }

    fun updateRelativeMinutes(minutes: Int) = applyLocal {
        it.copy(
            relativeMinutes = minutes.coerceIn(
                Alarm.MIN_RELATIVE_MINUTES,
                Alarm.MAX_RELATIVE_MINUTES,
            ),
        )
    }

    fun toggleSelfDestruct() = applyLocal {
        // Recurring + self-destruct is illegal. Defensive guard at the VM
        // layer in addition to the UI hiding the toggle in that mode.
        if (it.mode == AlarmMode.ABSOLUTE && it.daysOfWeek != 0) {
            it.copy(selfDestruct = false, selfDestructUserSet = true)
        } else {
            it.copy(selfDestruct = !it.selfDestruct, selfDestructUserSet = true)
        }
    }

    /**
     * Apply a state mutation, persist to DB, and mark the row for watch
     * flush on exit. Common path for every field-edit handler.
     */
    private fun applyLocal(transform: (AlarmEditUiState) -> AlarmEditUiState) {
        _state.update { transform(it).copy(dirty = true) }
        viewModelScope.launch {
            val alarm = buildAlarm(_state.value)
            val savedId = repository.saveLocalOnly(alarm)
            if (_state.value.id == 0L) {
                // First mutation on a new draft assigned a row id — keep it
                // so subsequent mutations upsert in place instead of
                // creating duplicate rows.
                _state.update { it.copy(id = savedId, isExistingAlarm = true) }
            }
            pendingWatchPushIds.add(savedId)
        }
    }

    /**
     * Restore the original alarm state captured at screen open. No-op for a
     * brand-new draft (nothing to revert to — use [discardNewDraft] instead).
     */
    fun revert() {
        val snap = openSnapshot ?: return
        viewModelScope.launch {
            // saveLocalOnly will re-stamp updatedAtEpoch — that's correct,
            // because the local DB needs a fresh stamp for LWW resolution
            // when the watch eventually receives this state.
            repository.saveLocalOnly(snap)
            _state.value = stateFromAlarm(snap).copy(loaded = true)
            pendingWatchPushIds.add(snap.id)
        }
    }

    /**
     * Delete the alarm (discrete user action — pushes immediately to watch).
     * Caller is responsible for showing a confirmation dialog before invoking.
     */
    fun delete() = viewModelScope.launch {
        val id = _state.value.id
        if (id != 0L) {
            repository.delete(id)
            pendingWatchPushIds.remove(id)
        }
    }

    /**
     * Discard a brand-new draft that was committed to DB on first edit but
     * which the user no longer wants. Skips watch broadcast since the watch
     * never learned the alarm existed.
     */
    fun discardNewDraft() = viewModelScope.launch {
        val id = _state.value.id
        if (id != 0L && openSnapshot == null) {
            repository.deleteLocalOnly(id)
            pendingWatchPushIds.remove(id)
        }
    }

    /**
     * Flush any pending watch pushes for alarms touched in this session.
     * Called on user-driven exit (back, save, X, navigation) BEFORE
     * navigation completes so the bridge sees the latest state. Also
     * invoked by [onCleared] as the final safety net when the VM dies.
     */
    fun flushPendingToWatch() {
        if (pendingWatchPushIds.isEmpty()) return
        val snapshot = pendingWatchPushIds.toList()
        pendingWatchPushIds.clear()
        snapshot.forEach { repository.pushAlarmToWatch(it) }
    }

    override fun onCleared() {
        // Final safety net: if the screen is being destroyed (process death,
        // task removal) before the UI got a chance to call flushPendingToWatch,
        // dispatch via the repository's appScope so the push survives our death.
        flushPendingToWatch()
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
            snoozeMinutes = s.snoozeMinutes.coerceIn(Alarm.MIN_SNOOZE_MINUTES, Alarm.MAX_SNOOZE_MINUTES),
            relativeMinutes = relativeMinutes,
            selfDestruct = selfDestruct,
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
        snoozeMinutes = alarm.snoozeMinutes,
        mode = if (alarm.isRelative) AlarmMode.RELATIVE else AlarmMode.ABSOLUTE,
        relativeMinutes = alarm.relativeMinutes ?: DEFAULT_RELATIVE_MINUTES,
        selfDestruct = alarm.selfDestruct,
        // selfDestructUserSet=false on a fresh load so mode/days transitions
        // re-apply the default; UI sets it true on explicit user toggle.
        selfDestructUserSet = false,
        isExistingAlarm = true,
        loaded = true,
    )

    private companion object {
        const val DEFAULT_RELATIVE_MINUTES = 15
    }
}
