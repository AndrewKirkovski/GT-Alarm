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
        Log.d(TAG, "onStartCommand action=$action id=$alarmId")

        when (action) {
            ACTION_RING -> handleRing(alarmId)
            ACTION_DISMISS -> handleDismiss(alarmId)
            ACTION_SNOOZE -> handleSnooze(alarmId)
            else -> {
                Log.w(TAG, "unknown action: $action")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleRing(alarmId: Long) {
        if (alarmId < 0) {
            stopSelf()
            return
        }
        currentAlarmId = alarmId

        serviceScope.launch {
            val alarm = repository.getById(alarmId) ?: run {
                Log.w(TAG, "no alarm found for id=$alarmId")
                stopForegroundAndSelf()
                return@launch
            }
            wearBridge.sendAlarmFired(alarmId)
            startRinging(alarm)
            scheduleAutoStop()
            // For repeating alarms, schedule the next occurrence so we don't miss it.
            if (alarm.daysOfWeek != 0) {
                scheduler.schedule(alarm)
            }
        }
    }

    private fun startRinging(alarm: Alarm) {
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

        val p = AlarmAudioPlayer(applicationContext)
        p.start(alarm.audioUri, alarm.isVibrationOnly)
        player = p

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
            handleDismiss(currentAlarmId)
        }
        autoStopRunnable = r
        mainHandler.postDelayed(r, AUTO_STOP_MS)
    }

    private fun handleDismiss(alarmId: Long) {
        Log.d(TAG, "dismiss id=$alarmId")
        wearBridge.sendAlarmDismissed(alarmId)
        if (alarmId >= 0) {
            // Auto-disable a one-shot alarm after it fires (mirrors watch behaviour).
            // Repeating alarms (daysOfWeek != 0) stay enabled; their next slot is
            // already scheduled in handleRing.
            serviceScope.launch {
                val alarm = repository.getById(alarmId)
                if (shouldAutoDisableOnDismiss(alarm)) {
                    repository.setEnabled(alarmId, false)
                }
            }
        }
        stopForegroundAndSelf()
    }

    private fun handleSnooze(alarmId: Long) {
        Log.d(TAG, "snooze id=$alarmId")
        serviceScope.launch {
            // Repository.snooze already broadcasts alarm_snoozed via the bridge
            // and reschedules; we just need to tear down the ring foreground.
            repository.snooze(alarmId, SNOOZE_MINUTES)
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
        const val SNOOZE_MINUTES = 10
        private const val AUTO_STOP_MS = 5L * 60_000L
        private const val TAG = "AlarmRing"

        // Pure helper extracted for unit testing. Returns true iff the
        // dismiss action should flip enabled=false on the given alarm:
        // one-shot (daysOfWeek=0) AND currently enabled. Repeating alarms
        // stay armed (the system re-publishes for daysOfWeek).
        fun shouldAutoDisableOnDismiss(alarm: Alarm?): Boolean =
            alarm != null && alarm.daysOfWeek == 0 && alarm.enabled
    }
}
