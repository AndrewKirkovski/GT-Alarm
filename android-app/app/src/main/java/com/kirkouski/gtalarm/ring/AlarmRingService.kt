package com.kirkouski.gtalarm.ring

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.kirkouski.gtalarm.data.AlarmRepository
import com.kirkouski.gtalarm.di.IoDispatcher
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.scheduler.AlarmScheduler
import com.kirkouski.gtalarm.wear.WearBridgeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlarmRingService : Service() {

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var wearBridge: WearBridgeService
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: AlarmAudioPlayer? = null
    private var currentAlarmId: Long = -1L
    private var autoStopRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlarmNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L
        val fromPeer = intent?.getBooleanExtra(EXTRA_FROM_PEER, false) ?: false
        val rescheduleEpoch = intent?.getLongExtra(EXTRA_RESCHEDULE_EPOCH, -1L) ?: -1L
        Log.d(TAG, "onStartCommand action=$action id=$alarmId fromPeer=$fromPeer")

        when (action) {
            ACTION_RING -> handleRing(alarmId)
            ACTION_DISMISS -> handleDismiss(alarmId, fromPeer)
            ACTION_SNOOZE -> handleSnooze(alarmId, fromPeer, rescheduleEpoch)
            else -> {
                Log.w(TAG, "unknown action: $action")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleRing(alarmId: Long) {
        if (alarmId < 0) {
            // Even on early-exit we MUST call startForeground within 5 s of
            // startForegroundService — Android 12+ throws
            // ForegroundServiceDidNotStartInTimeException otherwise. Use a
            // placeholder notification and immediately stop.
            startForegroundPlaceholder()
            stopForegroundAndSelf()
            return
        }
        currentAlarmId = alarmId

        // STEP 1 (SYNCHRONOUS): bind FG IMMEDIATELY. Has to happen on the
        // service's main thread, before the suspend launch below, so we
        // satisfy the 5 s startForeground deadline even if the coroutine
        // dispatcher is slow to pick up. We use a placeholder notification
        // and upgrade it inside the launch once the alarm row is loaded.
        startForegroundPlaceholder()

        serviceScope.launch {
            val alarm = repository.getById(alarmId) ?: run {
                Log.w(TAG, "no alarm found for id=$alarmId — was already FG, tearing down")
                stopForegroundAndSelf()
                return@launch
            }
            // Upgrade the placeholder to the real ringing notification now
            // that we have the alarm details. If anything in the upgrade or
            // ring-audio path throws, we MUST tear down the service — the
            // FG placeholder we already showed would otherwise stick around
            // forever, blocking the user from re-firing the alarm.
            try {
                startForegroundOnly(alarm)
                // STEP 2: pre-arm — if watch is paired+connected, await
                // alarm_fired delivery up to PREARM_TIMEOUT_MS. Returns
                // immediately false if no watch is reachable.
                val prearmStartMs = System.currentTimeMillis()
                val watchReady = preArmWatch(alarmId)
                val prearmTookMs = System.currentTimeMillis() - prearmStartMs
                Log.i(TAG, "preArmWatch id=$alarmId ready=$watchReady took=${prearmTookMs}ms")
                // STEP 3: start audio + show AlarmActivity. By now phone +
                // watch are both presenting (or we timed out waiting).
                startRingingAudioAndUi(alarm)
                // STEP 4: sync-on-fire (fire-and-forget). Watch is awake,
                // push the rest of the alarm list.
                opportunisticFullSync(originatingAlarmId = alarmId)
                scheduleAutoStop()
                if (alarm.daysOfWeek != 0) {
                    scheduler.schedule(alarm)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Preserve structured-concurrency cancellation. Arrives via
                // the normal teardown path (serviceScope.cancel from
                // onDestroy); swallowing it would mis-log normal shutdown
                // as an error AND break cancellation propagation to any
                // nested suspend. Actual FG teardown is handled by
                // stopForegroundAndSelf in onDestroy → serviceScope.cancel,
                // so rethrowing is safe.
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught")
                // reason: the body chains NotificationCompat builders, AlarmAudioPlayer,
                // startActivity, scheduler — anything throwing here (RemoteException,
                // SecurityException, OOM, NotificationManagerCompat IllegalStateException)
                // would zombie the FG placeholder we already showed. Catch broadly +
                // tear down so the user can fire again.
                e: Exception,
            ) {
                Log.e(TAG, "handleRing body threw — tearing down FG to avoid zombie", e)
                stopForegroundAndSelf()
            }
        }
    }

    private fun startForegroundPlaceholder() {
        // Minimal notification so we satisfy Android's startForeground
        // deadline. Upgraded by startForegroundOnly() once we have the
        // alarm row. If we never upgrade (null alarm path), this
        // notification is torn down by stopForegroundAndSelf shortly.
        val notif = AlarmNotifications.buildPlaceholderNotification(this)
        startForeground(
            AlarmNotifications.NOTIFICATION_ID,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    // Try to wake the watch so it shows its own ring page in time with the
    // phone. Returns true if a 207 (COMM_SUCCESS) came back within budget;
    // false if no watch is paired/connected OR we timed out waiting.
    private suspend fun preArmWatch(alarmId: Long): Boolean {
        val info = wearBridge.pairedDeviceInfo.value
        if (info == null || !info.connected) {
            Log.d(TAG, "preArmWatch: no connected watch (info=$info) — skipping wait")
            // Still fire the message so when the watch DOES come back online
            // it gets the historical event (cheap, fire-and-forget).
            wearBridge.sendAlarmFired(alarmId)
            return false
        }
        return wearBridge.sendAlarmFiredAwaiting(alarmId, PREARM_TIMEOUT_MS)
    }

    private suspend fun opportunisticFullSync(originatingAlarmId: Long) {
        val all = repository.getAll()
        Log.d(TAG, "sync-on-fire pushing ${all.size} alarm(s) to watch (origin id=$originatingAlarmId)")
        for (alarm in all) {
            wearBridge.sendAlarmAdded(alarm)
        }
    }

    private fun startForegroundOnly(alarm: Alarm) {
        val notif = AlarmNotifications.buildRingingNotification(
            context = this,
            alarm = alarm,
            fullScreenIntent = AlarmNotifications.fullScreenPendingIntent(this, alarm.id),
            dismissIntent = AlarmNotifications.dismissPendingIntent(this, alarm.id),
            snoozeIntent = AlarmNotifications.snoozePendingIntent(this, alarm.id),
        )
        startForeground(
            AlarmNotifications.NOTIFICATION_ID,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun startRingingAudioAndUi(alarm: Alarm) {
        // Assign `player` BEFORE start() so that if MediaPlayer init throws
        // mid-call (audio focus denied, codec failure, broken URI), the
        // partially-initialized instance is still reachable from
        // stopForegroundAndSelf for a release() call. Otherwise the
        // exception bubbles up through handleRing's catch and the
        // MediaPlayer leaks until GC.
        val p = AlarmAudioPlayer(applicationContext)
        player = p
        p.start(alarm.audioUri, alarm.isVibrationOnly)

        val launch = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        runCatching { startActivity(launch) }
    }

    private fun scheduleAutoStop() {
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            Log.d(TAG, "auto-stop after ${AUTO_STOP_MS}ms")
            handleDismiss(currentAlarmId, fromPeer = false)
        }
        autoStopRunnable = r
        mainHandler.postDelayed(r, AUTO_STOP_MS)
    }

    private fun handleDismiss(alarmId: Long, fromPeer: Boolean) {
        Log.d(TAG, "dismiss id=$alarmId fromPeer=$fromPeer")
        if (!fromPeer) wearBridge.sendAlarmDismissed(alarmId)
        if (alarmId >= 0) {
            serviceScope.launch {
                val alarm = repository.getById(alarmId)
                when (dismissAction(alarm)) {
                    DismissAction.KEEP -> Unit
                    DismissAction.DISABLE -> {
                        if (fromPeer) {
                            repository.setEnabledLocalOnly(alarmId, false)
                        } else {
                            repository.setEnabled(alarmId, false)
                        }
                    }
                    DismissAction.DELETE -> {
                        // Tombstone + propagation via repository.delete().
                        // Idempotent on the peer if it also originated this
                        // dismiss (the alarm_dismissed envelope already told
                        // the watch it's done; the alarm_deleted that delete()
                        // sends back is a redundant-but-harmless cleanup).
                        repository.delete(alarmId)
                    }
                }
            }
        }
        stopForegroundAndSelf()
    }

    private fun handleSnooze(alarmId: Long, fromPeer: Boolean, rescheduleEpochFromPeer: Long) {
        Log.d(TAG, "snooze id=$alarmId fromPeer=$fromPeer reschedule=$rescheduleEpochFromPeer")
        serviceScope.launch {
            if (fromPeer) {
                if (rescheduleEpochFromPeer > 0L) {
                    repository.snoozeAt(alarmId, rescheduleEpochFromPeer)
                }
            } else {
                // Each alarm carries its own snooze duration (see Alarm.snoozeMinutes).
                // repository.snooze reads it when minutes is null.
                repository.snooze(alarmId)
            }
            stopForegroundAndSelf()
        }
    }

    private fun stopForegroundAndSelf() {
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null
        player?.stop()
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        player?.stop()
        player = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "com.kirkouski.gtalarm.ACTION_RING"
        const val ACTION_DISMISS = "com.kirkouski.gtalarm.ACTION_DISMISS"
        const val ACTION_SNOOZE = "com.kirkouski.gtalarm.ACTION_SNOOZE"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_FROM_PEER = "from_peer"
        const val EXTRA_RESCHEDULE_EPOCH = "reschedule_epoch"
        private const val AUTO_STOP_MS = 5L * 60_000L
        // Max time to wait for the watch to confirm alarm_fired delivery
        // before starting phone audio. Trades up to PREARM_TIMEOUT_MS of
        // silence for both devices ringing in sync.
        //
        // 3 s = generous for the observed cold-launch (~1 s) + JS receiver
        // bind (~1 s) + BT round-trip (a few hundred ms), with margin.
        // Stayed under the previous 4.5 s setting so the worst-case path
        // (placeholder FG → upgrade → preArm → audio) finishes inside ~4 s
        // total, far under the 10 s ANR budget for service startup.
        private const val PREARM_TIMEOUT_MS = 3_000L
        private const val TAG = "AlarmRing"

        // Pure helper extracted for unit testing. Returns true iff the
        // dismiss action should flip enabled=false on the given alarm:
        // Decides what to do to the alarm row when the user dismisses (or
        // the ring cycle auto-stops). Three outcomes:
        //   KEEP    — recurring alarm, stays armed; the system will fire
        //             again on the next day-of-week match.
        //   DISABLE — legacy one-shot behavior: clear `enabled` so the row
        //             stays in the list as a re-arm-able template.
        //   DELETE  — self-destruct: row vanishes (tombstone + propagation
        //             to watch via AlarmRepository.delete). Used for alarms
        //             with `flags.SELF_DESTRUCT` (default for relative +
        //             new-style one-shot absolute).
        fun dismissAction(alarm: Alarm?): DismissAction = when {
            alarm == null || !alarm.enabled -> DismissAction.KEEP
            alarm.selfDestruct -> DismissAction.DELETE
            alarm.daysOfWeek == 0 -> DismissAction.DISABLE
            else -> DismissAction.KEEP
        }
    }

    enum class DismissAction { KEEP, DISABLE, DELETE }
}
