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
                    loaded = true,
                )
            }
        }
    }

    fun updateTime(hour: Int, minute: Int) = _state.update { it.copy(hour = hour, minute = minute) }
    fun updateLabel(label: String) = _state.update { it.copy(label = label) }
    fun toggleDay(day: Int) = _state.update { it.copy(daysOfWeek = DaysOfWeek.toggle(it.daysOfWeek, day)) }
    fun updateAudio(uri: String?, name: String?) = _state.update { it.copy(audioUri = uri, audioName = name) }
    fun toggleVibrationOnly() = _state.update { it.copy(isVibrationOnly = !it.isVibrationOnly) }

    suspend fun save(): Long {
        val s = _state.value
        val alarm = Alarm(
            id = s.id,
            label = s.label,
            hour = s.hour,
            minute = s.minute,
            daysOfWeek = s.daysOfWeek,
            enabled = s.enabled,
            audioUri = s.audioUri,
            audioName = s.audioName,
            isVibrationOnly = s.isVibrationOnly,
        )
        return repository.save(alarm)
    }

    fun delete() = viewModelScope.launch {
        val id = _state.value.id
        if (id != 0L) repository.delete(id)
    }
}
