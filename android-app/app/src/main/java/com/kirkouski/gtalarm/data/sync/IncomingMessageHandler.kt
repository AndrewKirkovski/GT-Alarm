package com.kirkouski.gtalarm.data.sync

import android.util.Log
import com.kirkouski.gtalarm.data.Tombstones
import com.kirkouski.gtalarm.data.db.AlarmDao
import com.kirkouski.gtalarm.data.db.toDomain
import com.kirkouski.gtalarm.data.db.toEntity
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.scheduler.AlarmScheduler
import com.kirkouski.gtalarm.widget.WidgetRefresher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receive-side handler for peer messages on the phone. Applies the LWW +
 * tombstone rules from `docs/sync-architecture.md` §2 and §5 and writes
 * the resolved state directly to DAO/Scheduler/Tombstones/Widget — it
 * does NOT route through [com.kirkouski.gtalarm.data.AlarmRepository],
 * which would re-broadcast the change and cause a feedback loop.
 *
 * Bound at app start to [com.kirkouski.gtalarm.wear.WearBridgeService]
 * via `setIncomingHandler`. Today's `NoOpWearBridge` never invokes it;
 * `HuaweiWearBridge` will, once vendor approval lands.
 */
@Singleton
class IncomingMessageHandler @Inject constructor(
    private val dao: AlarmDao,
    private val tombstones: Tombstones,
    private val scheduler: AlarmScheduler,
    private val widgetRefresher: WidgetRefresher,
) {

    suspend fun handle(msg: IncomingMessage) {
        // Boundary validation. Peer messages are an external API; reject malformed
        // stamps so they cannot poison the LWW state. Drop, don't throw — a
        // misbehaving peer should not crash the app. Mirrors the watch handler.
        if (msg.updatedAtEpoch <= 0L) {
            Log.w(TAG, "rejecting bad updatedAtEpoch=${msg.updatedAtEpoch} type=${msg::class.simpleName}")
            return
        }
        when (msg) {
            is IncomingMessage.AlarmAdded -> applyAddOrUpdate(msg.alarm, msg.updatedAtEpoch)
            is IncomingMessage.AlarmUpdated -> applyAddOrUpdate(msg.alarm, msg.updatedAtEpoch)
            is IncomingMessage.AlarmToggled -> applyToggle(msg.alarmId, msg.enabled, msg.updatedAtEpoch)
            is IncomingMessage.AlarmDeleted -> applyDelete(msg.alarmId, msg.updatedAtEpoch)
            is IncomingMessage.AlarmFired -> Log.d(TAG, "peer fired alarm id=${msg.alarmId}")
            is IncomingMessage.AlarmDismissed -> Log.d(TAG, "peer dismissed alarm id=${msg.alarmId}")
            is IncomingMessage.AlarmSnoozed -> applySnooze(msg.alarmId, msg.rescheduleEpoch, msg.updatedAtEpoch)
        }
    }

    private suspend fun applyAddOrUpdate(incoming: Alarm, incomingEpoch: Long) {
        val id = incoming.id
        if (tombstones.isTombstoned(id, incomingEpoch)) {
            Log.d(TAG, "suppress add/update id=$id — tombstoned")
            return
        }
        val local = dao.getById(id)
        if (!LwwResolver.shouldApply(incomingEpoch, local?.updatedAtEpoch)) {
            Log.d(TAG, "ignore add/update id=$id — older than local")
            return
        }
        val stamped = incoming.copy(updatedAtEpoch = incomingEpoch)
        dao.upsert(stamped.toEntity())
        if (stamped.enabled) scheduler.schedule(stamped) else scheduler.cancel(id)
        widgetRefresher.refresh()
    }

    private suspend fun applyToggle(alarmId: Long, enabled: Boolean, incomingEpoch: Long) {
        if (tombstones.isTombstoned(alarmId, incomingEpoch)) {
            Log.d(TAG, "suppress toggle id=$alarmId — tombstoned")
            return
        }
        val local = dao.getById(alarmId)
        if (local == null) {
            Log.d(TAG, "ignore toggle id=$alarmId — unknown row")
            return
        }
        if (!LwwResolver.shouldApply(incomingEpoch, local.updatedAtEpoch)) {
            Log.d(TAG, "ignore toggle id=$alarmId — older than local")
            return
        }
        dao.setEnabledStamped(alarmId, enabled, incomingEpoch)
        val updated = local.copy(enabled = enabled, updatedAtEpoch = incomingEpoch).toDomain()
        if (enabled) scheduler.schedule(updated) else scheduler.cancel(alarmId)
        widgetRefresher.refresh()
    }

    private suspend fun applyDelete(alarmId: Long, incomingEpoch: Long) {
        // Tombstone is sticky — write unconditionally so a stale add/update
        // arriving later is suppressed by the §2 tombstone tie-break.
        tombstones.add(alarmId, incomingEpoch)
        val local = dao.getById(alarmId)
        if (local == null) {
            Log.d(TAG, "delete id=$alarmId — tombstone only")
            return
        }
        if (!LwwResolver.shouldApply(incomingEpoch, local.updatedAtEpoch)) {
            Log.d(TAG, "suppress delete id=$alarmId — older than local")
            return
        }
        scheduler.cancel(alarmId)
        dao.deleteById(alarmId)
        widgetRefresher.refresh()
    }

    private suspend fun applySnooze(alarmId: Long, rescheduleEpoch: Long, incomingEpoch: Long) {
        // Live-ring coordination, not a state mutation — re-arms the local
        // AlarmManager so the phone fires at the same future moment as peer.
        val local = dao.getById(alarmId)?.toDomain()
        if (local == null) {
            Log.d(TAG, "ignore snooze id=$alarmId — unknown row")
            return
        }
        Log.d(TAG, "peer snooze id=$alarmId at $rescheduleEpoch stamp=$incomingEpoch")
        scheduler.scheduleAt(local, rescheduleEpoch)
    }

    private companion object {
        const val TAG = "IncomingMsg"
    }
}
