package com.kirkouski.gtalarm.ring

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
        if (EditingAlarmRegistry.isEditing(alarmId)) {
            // User is actively editing this alarm on AlarmEditScreen. Don't
            // fire — would interrupt the edit with a full-screen alarm UI.
            // Still satisfy startForeground budget, then bail. The edit-
            // screen save hook (AlarmEditViewModel.save → rescheduleAlarm)
            // will re-arm; if computedFireEpoch has already passed by then,
            // the scheduler fires immediately and we re-enter here cleanly.
            Log.i(TAG, "handleRing id=$alarmId — alarm is being edited, bailing")
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
            // Snooze consumed: the trigger that just fired was either the
            // alarm's normal clock-time or its snooze-deferred trigger. Either
            // way the snoozedUntilEpoch override no longer reflects reality —
            // the alarm is RINGING right now, not deferred. Clear it before
            // the UI re-renders so the list shows correct state.
            if (alarm.snoozedUntilEpoch != null) {
                repository.clearSnoozedUntil(alarmId)
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
        startForeground(AlarmNotifications.NOTIFICATION_ID, notif, foregroundServiceTypeForApi())
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
        // Lockscreen-penetration diagnostics. The user has hit intermittent
        // "screen didn't wake" failures (e.g. 2nd alarm in a row sometimes
        // misses). Capture the FSI-relevant device state at fire moment so
        // logcat post-mortem can pin down whether the keyguard, the
        // CAN_USE_FULL_SCREEN_INTENT appop, or the BAL gate is the blocker.
        val km = runCatching { getSystemService(android.app.KeyguardManager::class.java) }.getOrNull()
        val nm = runCatching { getSystemService(android.app.NotificationManager::class.java) }.getOrNull()
        val canFsi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { nm?.canUseFullScreenIntent() ?: false }.getOrElse { false }
        } else {
            true // pre-34: FSI permission was implicit
        }
        Log.i(
            TAG,
            "FSI-fire id=${alarm.id} locked=${km?.isKeyguardLocked} " +
                "keyguardSecure=${km?.isKeyguardSecure} canFsi=$canFsi " +
                "sdk=${Build.VERSION.SDK_INT}",
        )
        val notif = AlarmNotifications.buildRingingNotification(
            context = this,
            alarm = alarm,
            fullScreenIntent = AlarmNotifications.fullScreenPendingIntent(this, alarm.id),
            dismissIntent = AlarmNotifications.dismissPendingIntent(this, alarm.id),
            snoozeIntent = if (alarm.isSnoozeEnabled) {
                AlarmNotifications.snoozePendingIntent(this, alarm.id)
            } else {
                null
            },
        )
        startForeground(AlarmNotifications.NOTIFICATION_ID, notif, foregroundServiceTypeForApi())
    }

    // FOREGROUND_SERVICE_TYPE_SPECIAL_USE was added in API 34; on 31-33 we
    // fall back to MEDIA_PLAYBACK. The manifest declares both types via
    // foregroundServiceType="specialUse|mediaPlayback" so either int is
    // valid at startForeground time.
    private fun foregroundServiceTypeForApi(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
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

        // Primary path: AlarmActivity is launched by NotificationManager when
        // it fires the setFullScreenIntent PendingIntent (see startForegroundOnly).
        // Per AOSP docs, FSI launches an Activity when the screen is locked /
        // off / AOD; it degrades to a heads-up notification when the device is
        // already in use.
        //
        // Backup path: an extra PI.send() for the "device in use" case. We
        // need AlarmActivity in the task stack regardless of FSI suppression
        // because Huawei Wear Engine REQUIRES an Activity component for the
        // P2P receive path — see memory/wear_engine_requires_activity.md.
        // A direct startActivity() from the FGS is BAL-blocked on Android 14
        // ("an app running a foreground service is considered to be in the
        // background" per developer.android.com/guide/components/activities/
        // background-starts), but a PendingIntent with creator-side BAL opt-in
        // goes through a different BAL path and is honored. singleInstance
        // launchMode + FLAG_ACTIVITY_NEW_TASK make a duplicate launch harmless
        // (re-enters onNewIntent on the existing instance).
        val launchIntent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                .toBundle()
        } else {
            null
        }
        val pi = PendingIntent.getActivity(
            this,
            alarm.id.toInt() or DIRECT_LAUNCH_REQUEST_CODE_BIT,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options,
        )
        runCatching { pi.send() }
            .onFailure { Log.w(TAG, "AlarmActivity PI.send() failed: ${it.message}") }
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
        if (alarmId < 0) {
            stopForegroundAndSelf()
            return
        }
        // Stop audio + vibration NOW for instant user feedback. We still
        // need to keep the service alive a bit longer to:
        //  (1) AWAIT the watch broadcast so the watch stops ringing too
        //      (fire-and-forget was vulnerable to mid-flight process kill)
        //  (2) AWAIT the dismiss action coroutine so a DELETE
        //      (self-destruct) row + tombstone actually commits before
        //      the OS reaps us.
        // The notification stays up for those ~2s in the worst case but
        // there's no audible cost.
        player?.stop()
        player = null
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null

        serviceScope.launch {
            if (!fromPeer) {
                val acked = wearBridge.sendAlarmDismissedAwaiting(alarmId, BROADCAST_AWAIT_MS)
                Log.i(TAG, "dismiss broadcast id=$alarmId acked=$acked")
            }
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
            stopForegroundAndSelf()
        }
    }

    private fun handleSnooze(alarmId: Long, fromPeer: Boolean, rescheduleEpochFromPeer: Long) {
        Log.d(TAG, "snooze id=$alarmId fromPeer=$fromPeer reschedule=$rescheduleEpochFromPeer")
        // Stop audio + vibration NOW (same rationale as handleDismiss) —
        // no user wants a snooze tap that keeps the alarm playing for
        // another 2s while the watch ACK arrives.
        player?.stop()
        player = null
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null

        serviceScope.launch {
            if (fromPeer) {
                if (rescheduleEpochFromPeer > 0L) {
                    repository.snoozeAt(alarmId, rescheduleEpochFromPeer)
                }
            } else {
                // Each alarm carries its own snooze duration. Compute the
                // trigger ourselves so we can broadcast to the watch BEFORE
                // tearing down the service (repository.snooze would also
                // broadcast but as fire-and-forget — at risk of being
                // killed mid-flight if the OS reaps the process).
                val alarm = repository.getById(alarmId)
                if (alarm == null) {
                    Log.w(TAG, "snooze id=$alarmId — row missing, skipping")
                    stopForegroundAndSelf()
                    return@launch
                }
                if (!alarm.isSnoozeEnabled) {
                    // Snooze was disabled on this alarm — reaches here only via
                    // a stale notification action posted before the user turned
                    // it off. Collapse to a real dismiss so the watch stops
                    // ringing AND self-destruct / disable / delete actions run;
                    // otherwise the watch keeps ringing and the row stays in
                    // an inconsistent state. handleDismiss owns its own
                    // stopForegroundAndSelf().
                    Log.i(TAG, "snooze id=$alarmId — snooze disabled, collapsing to dismiss")
                    handleDismiss(alarmId, fromPeer = false)
                    return@launch
                }
                val trigger = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
                val acked = wearBridge.sendAlarmSnoozedAwaiting(
                    alarmId,
                    trigger,
                    BROADCAST_AWAIT_MS,
                )
                Log.i(TAG, "snooze broadcast id=$alarmId acked=$acked trigger=$trigger")
                repository.snoozeAt(alarmId, trigger)
            }
            stopForegroundAndSelf()
        }
    }

    private fun stopForegroundAndSelf() {
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null
        player?.stop()
        player = null
        // Signal the AlarmActivity to finish itself. Critical for the
        // peer-driven path (watch dismiss/snooze): without this, the
        // service stops but the full-screen Activity stays on the phone
        // until the user manually dismisses it. Emit BEFORE stopForeground
        // so the Activity has the signal in flight as it tears down.
        RingEndedSignal.emitRingEnded()
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
        // 10 s — user-reported 2026-05-12: the watch occasionally takes 5–8 s
        // to cold-launch (JS engine restart + page mount) when the app was
        // killed several minutes ago. The 3 s budget caused the phone to ring
        // alone in those cases. The placeholder FG was already up when the
        // service started so the 10 s service-start ANR budget doesn't apply
        // to this wait (it's inside `serviceScope.launch`).
        //
        // Pre-arm returns EARLY as soon as the watch's `alarm_ringing` reply
        // arrives, so this is a worst-case cap, not a fixed delay.
        private const val PREARM_TIMEOUT_MS = 10_000L

        // Max time to await the watch acknowledging a user-driven dismiss
        // or snooze broadcast before tearing down the service. If we don't
        // wait, the process may be reaped before the bridge's fire-and-
        // forget send actually delivers, leaving the watch ringing.
        //
        // 12 s, bumped 2026-05-13 (was 2 s — user-reported "dismiss/snooze
        // on Android doesn't stop watch ring"). 2 s only covered ONE
        // round-trip; if the watch's P2P receiver was unbound (page just
        // started or paged out) the first send returned 206 COMM_FAIL and
        // the bridge's retry path needed up to PING_WAKE_TIMEOUT_MS=10 s
        // + RETRY_BACKOFF_MS=1 s of inner waits. Audio is already stopped
        // before the await begins so the user experience is identical;
        // the service just stays in the foreground a few extra seconds
        // while the deliverable settles.
        private const val BROADCAST_AWAIT_MS = 12_000L
        private const val TAG = "AlarmRing"

        // Request-code high bit for the direct-launch PendingIntent that
        // bypasses the FGS BAL block (see startRingingAudioAndUi). Distinct
        // from AlarmNotifications.FULL_SCREEN_BIT (0x08000000) so the two
        // PIs don't collide on FLAG_UPDATE_CURRENT.
        private const val DIRECT_LAUNCH_REQUEST_CODE_BIT = 0x04000000

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
