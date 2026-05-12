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
)

@HiltViewModel
class AlarmEditViewModel @Inject constructor(
    private val repository: AlarmRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AlarmEditUiState())
    val state: StateFlow<AlarmEditUiState> = _state.asStateFlow()

    fun load(id: Long?) {
        if (_state.value.loaded && _state.value.id == (id ?: 0L)) return
        viewModelScope.launch {
            val alarm = id?.let { repository.getById(it) }
            _state.value = if (alarm == null) {
                AlarmEditUiState(loaded = true)
            } else {
                AlarmEditUiState(
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
                    relativeMinutes = alarm.relativeMinutes ?: 15,
                    selfDestruct = alarm.selfDestruct,
                    selfDestructUserSet = true,
                    isExistingAlarm = true,
                    loaded = true,
                )
            }
        }
    }

    fun updateTime(hour: Int, minute: Int) = _state.update { it.copy(hour = hour, minute = minute) }
    fun updateLabel(label: String) = _state.update { it.copy(label = label) }
    fun toggleDay(day: Int) = _state.update {
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
    fun updateAudio(uri: String?, name: String?) = _state.update { it.copy(audioUri = uri, audioName = name) }
    fun toggleVibrationOnly() = _state.update { it.copy(isVibrationOnly = !it.isVibrationOnly) }
    fun updateSnoozeMinutes(minutes: Int) = _state.update {
        it.copy(snoozeMinutes = minutes.coerceIn(Alarm.MIN_SNOOZE_MINUTES, Alarm.MAX_SNOOZE_MINUTES))
    }

    fun updateMode(mode: AlarmMode) = _state.update {
        // Switching to RELATIVE forces daysOfWeek=0 (relative is always
        // one-shot). Default selfDestruct=true unless user explicitly opted out.
        val newDays = if (mode == AlarmMode.RELATIVE) 0 else it.daysOfWeek
        val newSelfDestruct = if (it.selfDestructUserSet) {
            it.selfDestruct
        } else {
            // Apply default for the new mode.
            mode == AlarmMode.RELATIVE || newDays == 0
        }
        it.copy(
            mode = mode,
            daysOfWeek = newDays,
            selfDestruct = newSelfDestruct,
        )
    }

    fun updateRelativeMinutes(minutes: Int) = _state.update {
        it.copy(
            relativeMinutes = minutes.coerceIn(
                Alarm.MIN_RELATIVE_MINUTES,
                Alarm.MAX_RELATIVE_MINUTES,
            ),
        )
    }

    fun toggleSelfDestruct() = _state.update {
        // Recurring + self-destruct is illegal. Defensive guard at the VM
        // layer in addition to the UI hiding the toggle in that mode.
        if (it.mode == AlarmMode.ABSOLUTE && it.daysOfWeek != 0) {
            it.copy(selfDestruct = false, selfDestructUserSet = true)
        } else {
            it.copy(selfDestruct = !it.selfDestruct, selfDestructUserSet = true)
        }
    }

    suspend fun save(): Long {
        val s = _state.value
        val daysOfWeek = if (s.mode == AlarmMode.RELATIVE) 0 else s.daysOfWeek
        val selfDestruct = s.selfDestruct && daysOfWeek == 0
        val relativeMinutes = if (s.mode == AlarmMode.RELATIVE) {
            s.relativeMinutes.coerceIn(Alarm.MIN_RELATIVE_MINUTES, Alarm.MAX_RELATIVE_MINUTES)
        } else {
            null
        }
        val alarm = Alarm(
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
        return repository.save(alarm)
    }

    fun delete() = viewModelScope.launch {
        val id = _state.value.id
        if (id != 0L) repository.delete(id)
    }
}
