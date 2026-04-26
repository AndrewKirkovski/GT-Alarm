package com.kirkouski.gtalarm.data

import com.kirkouski.gtalarm.data.db.AlarmDao
import com.kirkouski.gtalarm.data.db.toDomain
import com.kirkouski.gtalarm.data.db.toEntity
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.scheduler.AlarmScheduler
import com.kirkouski.gtalarm.wear.WearBridgeService
import com.kirkouski.gtalarm.widget.WidgetRefresher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmRepository @Inject constructor(
    private val dao: AlarmDao,
    private val scheduler: AlarmScheduler,
    private val wearBridge: WearBridgeService,
    private val tombstones: Tombstones,
    private val widgetRefresher: WidgetRefresher,
) {
    fun observeAlarms(): Flow<List<Alarm>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: Long): Alarm? = dao.getById(id)?.toDomain()

    suspend fun getAll(): List<Alarm> = dao.getAll().map { it.toDomain() }

    suspend fun save(alarm: Alarm): Long {
        val now = System.currentTimeMillis()
        val isNew = alarm.id == 0L
        val stamped = alarm.copy(updatedAtEpoch = now)
        val newId = dao.upsert(stamped.toEntity())
        val saved = stamped.copy(id = if (isNew) newId else stamped.id)
        if (saved.enabled) {
            scheduler.schedule(saved)
        } else {
            scheduler.cancel(saved.id)
        }
        if (isNew) wearBridge.sendAlarmAdded(saved) else wearBridge.sendAlarmUpdated(saved)
        refreshWidgets()
        return saved.id
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val now = System.currentTimeMillis()
        dao.setEnabledStamped(id, enabled, now)
        val alarm = dao.getById(id)?.toDomain() ?: return
        if (enabled) scheduler.schedule(alarm) else scheduler.cancel(id)
        wearBridge.sendAlarmToggled(alarm)
        refreshWidgets()
    }

    suspend fun delete(id: Long) {
        scheduler.cancel(id)
        val now = System.currentTimeMillis()
        dao.deleteById(id)
        tombstones.add(id, now, now)
        wearBridge.sendAlarmDeleted(id, now)
        refreshWidgets()
    }

    suspend fun snooze(id: Long, minutes: Int = 10): Long? {
        val alarm = dao.getById(id)?.toDomain() ?: return null
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        scheduler.scheduleAt(alarm, trigger)
        wearBridge.sendAlarmSnoozed(id, trigger)
        refreshWidgets()
        return trigger
    }

    suspend fun rescheduleAll() {
        val alarms = getAll().filter { it.enabled }
        scheduler.rescheduleAll(alarms)
    }

    private suspend fun refreshWidgets() = widgetRefresher.refresh()
}
