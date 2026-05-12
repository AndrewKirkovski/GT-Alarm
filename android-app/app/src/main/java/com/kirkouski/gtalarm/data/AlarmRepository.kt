package com.kirkouski.gtalarm.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kirkouski.gtalarm.data.db.AlarmDao
import com.kirkouski.gtalarm.data.db.toDomain
import com.kirkouski.gtalarm.data.db.toEntity
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.ring.AlarmNotifications
import com.kirkouski.gtalarm.scheduler.AlarmScheduler
import com.kirkouski.gtalarm.wear.WearBridgeService
import com.kirkouski.gtalarm.widget.WidgetRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @param:ApplicationContext private val appContext: Context,
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
        Log.i(
            TAG,
            "save kind=${if (isNew) "added" else "updated"} id=${saved.id} " +
                "enabled=${saved.enabled} dow=${saved.daysOfWeek} stamp=$now",
        )
        return saved.id
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val now = System.currentTimeMillis()
        dao.setEnabledStamped(id, enabled, now)
        val alarm = dao.getById(id)?.toDomain() ?: return
        if (enabled) scheduler.schedule(alarm) else scheduler.cancel(id)
        wearBridge.sendAlarmToggled(alarm)
        refreshWidgets()
        Log.i(TAG, "setEnabled id=$id enabled=$enabled stamp=$now")
    }

    suspend fun setEnabledLocalOnly(id: Long, enabled: Boolean) {
        val now = System.currentTimeMillis()
        dao.setEnabledStamped(id, enabled, now)
        val alarm = dao.getById(id)?.toDomain() ?: return
        if (enabled) scheduler.schedule(alarm) else scheduler.cancel(id)
        refreshWidgets()
        Log.i(TAG, "setEnabledLocalOnly id=$id enabled=$enabled stamp=$now")
    }

    suspend fun delete(id: Long) {
        scheduler.cancel(id)
        val now = System.currentTimeMillis()
        dao.deleteById(id)
        tombstones.add(id, now, now)
        wearBridge.sendAlarmDeleted(id, now)
        refreshWidgets()
        Log.i(TAG, "delete id=$id stamp=$now")
    }

    // Snooze using the alarm's own configured duration. Override is for
    // tests / one-off explicit-minutes callers; production callers should
    // pass null so the per-alarm value is honored.
    suspend fun snooze(id: Long, minutesOverride: Int? = null): Long? {
        val alarm = dao.getById(id)?.toDomain() ?: return null
        val minutes = minutesOverride ?: alarm.snoozeMinutes
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        scheduler.scheduleAt(alarm, trigger)
        wearBridge.sendAlarmSnoozed(id, trigger)
        refreshWidgets()
        Log.i(TAG, "snooze id=$id +${minutes}min trigger=$trigger")
        return trigger
    }

    suspend fun snoozeAt(id: Long, triggerEpoch: Long): Long? {
        val alarm = dao.getById(id)?.toDomain() ?: return null
        scheduler.scheduleAt(alarm, triggerEpoch)
        refreshWidgets()
        Log.i(TAG, "snoozeAt id=$id trigger=$triggerEpoch (from-peer)")
        return triggerEpoch
    }

    suspend fun rescheduleAll() {
        val alarms = getAll().filter { it.enabled }
        Log.i(TAG, "rescheduleAll enabledCount=${alarms.size}")
        scheduler.rescheduleAll(alarms)
    }

    // Boot-time variant that applies the relative-alarm past-due rule
    // (memory:litewearable_storage_limits + binary-wire-format spec):
    //   - A relative alarm whose computed fire time is in the past gets
    //     classified by whether it fell during downtime or during current
    //     uptime.
    //   - Past during current uptime (target >= bootCompleteAt): schedule
    //     normally; AlarmManager will fire it immediately (or near-so).
    //   - Past during downtime (target < bootCompleteAt): post a passive
    //     "missed" notification and delete the row (tombstones + propagate
    //     to watch). The user gets one informational notification per
    //     missed reminder.
    suspend fun rescheduleAllOnBoot() {
        val now = System.currentTimeMillis()
        val bootCompleteAt = now - SystemClock.elapsedRealtime()
        val alarms = getAll().filter { it.enabled }
        val missed = mutableListOf<Alarm>()
        val keep = mutableListOf<Alarm>()
        alarms.forEach { alarm ->
            if (alarm.isRelative && alarm.computedFireEpoch() < bootCompleteAt) {
                missed += alarm
            } else {
                keep += alarm
            }
        }
        Log.i(
            TAG,
            "rescheduleAllOnBoot enabled=${alarms.size} keep=${keep.size} " +
                "missedDuringDowntime=${missed.size} bootCompleteAt=$bootCompleteAt",
        )
        scheduler.rescheduleAll(keep)
        missed.forEach { handleMissedDuringDowntime(it) }
    }

    private suspend fun handleMissedDuringDowntime(alarm: Alarm) {
        Log.i(
            TAG,
            "missedDuringDowntime id=${alarm.id} target=${alarm.computedFireEpoch()} " +
                "label='${alarm.label}'",
        )
        AlarmNotifications.postMissedNotification(appContext, alarm)
        // Self-destruct semantics: relative alarms with a downtime miss are
        // dead — they couldn't have served their purpose. Delete the row
        // (tombstones + propagate to watch) regardless of selfDestruct flag
        // value on the alarm; a missed-during-downtime row is non-recoverable.
        delete(alarm.id)
    }

    // Debug-only: returns the id of an alarm we can fire RIGHT NOW.
    // Picks the first existing enabled alarm; if none, inserts one with
    // hour/minute set to current time so the row exists for the ring
    // service to look up. Returns the alarm id.
    suspend fun ensureDebugAlarmId(): Long {
        val existing = dao.getAll().firstOrNull { it.enabled }
        if (existing != null) return existing.id
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val draft = Alarm(
            label = "Debug instant fire",
            hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            minute = cal.get(java.util.Calendar.MINUTE),
            daysOfWeek = 0,
            enabled = true,
            updatedAtEpoch = now,
        )
        val newId = dao.upsert(draft.toEntity())
        wearBridge.sendAlarmAdded(draft.copy(id = newId))
        refreshWidgets()
        Log.i(TAG, "ensureDebugAlarmId created id=$newId")
        return newId
    }

    // Debug-only: inserts a one-shot alarm and overrides the scheduler's
    // computed hour/minute trigger with an exact `now + 60_000` epoch so a
    // tester doesn't have to wait until the next minute boundary. Returns
    // the trigger epoch in ms so the caller can surface "fires at 14:33:42"
    // in a Toast. The row stays in the DB after the fire — same lifecycle
    // as a real one-shot (auto-disabled by AlarmRingService.handleDismiss).
    suspend fun scheduleTestFireInOneMinute(): Long {
        val now = System.currentTimeMillis()
        val trigger = now + ONE_MINUTE_MS
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = trigger }
        val draft = Alarm(
            label = "Debug 1-min fire",
            hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            minute = cal.get(java.util.Calendar.MINUTE),
            daysOfWeek = 0,
            enabled = true,
            updatedAtEpoch = now,
        )
        val newId = dao.upsert(draft.toEntity())
        val saved = draft.copy(id = newId)
        scheduler.scheduleAt(saved, trigger)
        wearBridge.sendAlarmAdded(saved)
        refreshWidgets()
        Log.i(TAG, "scheduleTestFireInOneMinute id=$newId trigger=$trigger")
        return trigger
    }

    private suspend fun refreshWidgets() = widgetRefresher.refresh()

    private companion object {
        const val TAG = "AlarmRepo"
        const val ONE_MINUTE_MS = 60_000L
    }
}
