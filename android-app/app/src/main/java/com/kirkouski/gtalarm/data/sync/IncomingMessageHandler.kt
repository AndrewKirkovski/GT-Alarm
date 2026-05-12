package com.kirkouski.gtalarm.data.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kirkouski.gtalarm.data.Tombstones
import com.kirkouski.gtalarm.data.db.AlarmDao
import com.kirkouski.gtalarm.data.db.toDomain
import com.kirkouski.gtalarm.data.db.toEntity
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.ring.AlarmRingService
import com.kirkouski.gtalarm.scheduler.AlarmScheduler
import com.kirkouski.gtalarm.widget.WidgetRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receive-side handler for peer messages on the phone. Applies the LWW +
 * tombstone rules from `docs/sync-architecture.md` §2 and §5 and writes
 * resolved state directly to DAO/Scheduler/Tombstones/Widget — never via
 * AlarmRepository, which would re-broadcast and create a peer loop.
 *
 * For dismiss and snooze during a live ring, the handler dispatches an
 * intent to [AlarmRingService] with [AlarmRingService.EXTRA_FROM_PEER] set
 * so the service skips the outbound broadcast that would echo the action
 * back to the originating peer.
 */
@Singleton
class IncomingMessageHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: AlarmDao,
    private val tombstones: Tombstones,
    private val scheduler: AlarmScheduler,
    private val widgetRefresher: WidgetRefresher,
) {

    // reason: branch count is dominated by the IncomingMessage sealed
    // hierarchy (7 cases) plus the WatchLog early-return + level switch.
    // Each branch maps to a distinct wire-format type that has to be
    // dispatched; splitting into helpers per-type adds files without
    // changing logic.
    @Suppress("CyclomaticComplexMethod")
    suspend fun handle(msg: IncomingMessage) {
        // WatchLog is the dev-only log relay — surface to logcat under its
        // own tag and exit. It legitimately carries `updatedAtEpoch` = ts
        // not the LWW stamp, so it bypasses the regular epoch validation.
        if (msg is IncomingMessage.WatchLog) {
            when (msg.level) {
                "E" -> Log.e(WATCH_LOG_TAG, msg.msg)
                "W" -> Log.w(WATCH_LOG_TAG, msg.msg)
                else -> Log.i(WATCH_LOG_TAG, msg.msg)
            }
            return
        }
        // SyncHash is intercepted by HuaweiWearBridge for the pending
        // hash-request continuation. If it reaches the handler the
        // request had already timed out — silently drop instead of
        // letting it fail the updatedAtEpoch=0 validation below.
        if (msg is IncomingMessage.SyncHash) {
            Log.d(TAG, "received SyncHash after timeout (hash=${msg.hash}) — dropping")
            return
        }
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
            is IncomingMessage.AlarmDismissed -> dispatchDismissFromPeer(msg.alarmId)
            is IncomingMessage.AlarmSnoozed -> applySnooze(msg.alarmId, msg.updatedAtEpoch)
            is IncomingMessage.WatchLog -> Unit // handled above; exhaustiveness only
            is IncomingMessage.SyncHash -> Unit // handled above; exhaustiveness only
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

    private suspend fun applySnooze(alarmId: Long, incomingEpoch: Long) {
        val local = dao.getById(alarmId)?.toDomain()
        if (local == null) {
            Log.d(TAG, "ignore snooze id=$alarmId — unknown row")
            return
        }
        // Phone owns the snooze duration — peer carries the action only.
        val trigger = System.currentTimeMillis() + local.snoozeMinutes * 60_000L
        Log.d(TAG, "peer snooze id=$alarmId +${local.snoozeMinutes}min trigger=$trigger stamp=$incomingEpoch")
        dispatchSnoozeFromPeer(alarmId, trigger)
    }

    private fun dispatchDismissFromPeer(alarmId: Long) {
        dispatchToRingService(buildDismissFromPeerIntent(context, alarmId), "peer dismiss id=$alarmId")
    }

    private fun dispatchSnoozeFromPeer(alarmId: Long, rescheduleEpoch: Long) {
        dispatchToRingService(
            buildSnoozeFromPeerIntent(context, alarmId, rescheduleEpoch),
            "peer snooze id=$alarmId",
        )
    }

    /**
     * Best-effort fire of a service intent. On Android 12+ (API 31),
     * starting a FOREGROUND service from the background throws
     * ForegroundServiceStartNotAllowedException — which would happen here
     * if the peer's dismiss/snooze arrives while the phone app is fully
     * in the background AND the ring service is no longer running. We
     * catch and log; the watch's local UI already handled the action so
     * the user sees no regression, just no phone-side propagation.
     */
    private fun dispatchToRingService(intent: Intent, label: String) {
        @Suppress("TooGenericExceptionCaught")
        try {
            context.startService(intent)
        } catch (e: RuntimeException) {
            // RuntimeException is the documented superclass of
            // ForegroundServiceStartNotAllowedException (API 31+). On
            // older Androids the same intent could throw IllegalStateException
            // for similar background-launch restrictions.
            Log.w(TAG, "startService for $label failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "IncomingMsg"
        // Separate tag so adb logcat filtering can pin just the watch's
        // mirrored log lines (e.g. `adb logcat WatchLog:I *:S`).
        private const val WATCH_LOG_TAG = "WatchLog"

        fun buildDismissFromPeerIntent(context: Context, alarmId: Long): Intent =
            Intent(context, AlarmRingService::class.java).apply {
                action = AlarmRingService.ACTION_DISMISS
                putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmRingService.EXTRA_FROM_PEER, true)
            }

        fun buildSnoozeFromPeerIntent(
            context: Context,
            alarmId: Long,
            rescheduleEpoch: Long,
        ): Intent = Intent(context, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_SNOOZE
            putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmRingService.EXTRA_FROM_PEER, true)
            putExtra(AlarmRingService.EXTRA_RESCHEDULE_EPOCH, rescheduleEpoch)
        }
    }
}
