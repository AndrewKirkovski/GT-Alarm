package com.kirkouski.gtalarm.ui.list

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kirkouski.gtalarm.data.AlarmRepository
import com.kirkouski.gtalarm.data.OnboardingState
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.scheduler.AlarmScheduler
import com.kirkouski.gtalarm.wear.WatchSyncStatus
import com.kirkouski.gtalarm.wear.WearBridgeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    wearBridge: WearBridgeService,
) : ViewModel() {

    val alarms: StateFlow<List<Alarm>> = repository.observeAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Re-exposes the bridge's connection state to the list screen. Today this
     * is permanently [WatchSyncStatus.NOT_CONNECTED] (NoOpWearBridge); once
     * HuaweiWearBridge binds at the same Hilt seam it will start emitting
     * real CONNECTING/CONNECTED/ERROR transitions and the card auto-updates.
     */
    val watchStatus: StateFlow<WatchSyncStatus> = wearBridge.statusFlow

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

    private fun computeShowBatteryOptCard(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return OnboardingState.shouldShowBatteryOptCard(
            dismissed = onboarding.batteryOptCardDismissed(),
            isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName),
        )
    }
}
