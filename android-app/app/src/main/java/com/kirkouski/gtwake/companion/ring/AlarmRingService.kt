package com.kirkouski.gtwake.companion.ring

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import android.util.Log
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.data.bfu.BfuAlarmCache
import com.kirkouski.gtwake.companion.di.IoDispatcher
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.scheduler.AlarmScheduler
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

// reason: one handler per action (ring, dismiss, snooze) × pre-unlock variants,
// plus lifecycle hooks, audio helpers, and notification utilities — each maps to
// a distinct OS event; splitting would scatter the same state across more files.
@Suppress("TooManyFunctions")
@AndroidEntryPoint
class AlarmRingService : Service() {

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var wearBridge: WearBridgeService
    @Inject lateinit var bfuCache: BfuAlarmCache
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    // Default-locked: a null UserManager (some OEM ROMs in the early-boot
    // window) routes to BFU; a wrong-unlocked read would crash the FGS.
    private val isUserUnlocked: Boolean
        get() = getSystemService(UserManager::class.java)?.isUserUnlocked == true

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Serializes handleDismiss and handleSnooze. Both can race on
    // ACTION_DISMISS arriving while ACTION_SNOOZE is mid-flight (or the
    // reverse): user taps Snooze on phone notification, watch's dismiss
    // envelope races in via peer; without serialization the row could end
    // up with enabled=false AND snoozedUntilEpoch set, a contradictory
    // state. Mutex blocks the second handler until the first commits.
    private val handlerMutex = Mutex()

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

        // STEP 1: SYNCHRONOUS Room read + startForeground with the REAL
        // FSI notification. Earlier versions used a placeholder→upgrade
        // pattern (post placeholder fast, async-load alarm, re-post with
        // FSI). That triggered Samsung One UI's NotifAttentionHelper
        // "Muting recently noisy" rate-limiter on the second post — the
        // FSI Activity launch was silently suppressed on every other fire
        // (alternating-fire bug, verified on S24 Ultra 2026-05-14).
        //
        // runBlocking on the main thread is deliberate: the suspend
        // `getById` dispatches the actual SQLite hit to Room's query
        // executor (a background thread), so Room's assertNotMainThread
        // check passes — a non-suspend DAO query would crash here because
        // Room asserts even for those. The main thread blocks ~10-50 ms
        // waiting, well inside the 5 s startForeground deadline. This
        // matches FossifyOrg/Clock's synchronous-load-in-onStartCommand
        // pattern (they use a raw SQLiteOpenHelper that has no assertion;
        // we use Room so runBlocking is the equivalent).
        //
        // If the row is missing (deleted in a narrow window between
        // schedule and fire) we still post the placeholder so the FG
        // contract holds, then tear down.
        val alarm = runBlocking { fetchAlarmForRing(alarmId) }
        if (alarm == null) {
            Log.w(TAG, "no alarm found for id=$alarmId — placeholder + tear down")
            startForegroundPlaceholder()
            stopForegroundAndSelf()
            return
        }
        startForegroundOnly(alarm)

        serviceScope.launch {
            // Snooze consumed: the trigger that just fired was either the
            // alarm's normal clock-time or its snooze-deferred trigger. Either
            // way the snoozedUntilEpoch override no longer reflects reality —
            // the alarm is RINGING right now, not deferred. Clear it before
            // the UI re-renders so the list shows correct state.
            if (alarm.snoozedUntilEpoch != null) {
                repository.clearSnoozedUntil(alarmId)
            }
            // Skip-next consumed: if a skip was active for an earlier
            // instant, NextTriggerCalculator already advanced past it and
            // THIS fire is the post-skip occurrence. Clear the now-stale
            // skipNextEpoch so the row doesn't leak ambiguous state into
            // sync hash + UI.
            //
            // Pre-unlock branch: Room is locked so consumePastSkip's
            // dao.upsert would throw. Write the cleared field straight
            // to the BFU mirror; the post-unlock reconcile will replay
            // BFU's authoritative values into Room (rescheduleAllOnBoot
            // → replaceAll(keep)). Without this, a pre-unlock fire on a
            // skipped alarm previously crashed the launch block before
            // startRingingAudioAndUi could play audio.
            if (alarm.skipNextEpoch != null) {
                if (isUserUnlocked) {
                    repository.consumePastSkip(alarmId)
                } else {
                    bfuCache.upsert(
                        alarm.copy(
                            skipNextEpoch = null,
                            updatedAtEpoch = System.currentTimeMillis(),
                        ),
                    )
                    Log.i(TAG, "skip-next pre-unlock-cleared id=$alarmId (BFU only)")
                }
            }
            try {
                // STEP 2: pre-arm — if watch is paired+connected, await
                // alarm_fired delivery up to PREARM_TIMEOUT_MS. Returns
                // immediately false if no watch is reachable.
                val prearmStartMs = System.currentTimeMillis()
                val watchReady = preArmWatch(alarmId, alarm)
                val prearmTookMs = System.currentTimeMillis() - prearmStartMs
                Log.i(TAG, "preArmWatch id=$alarmId ready=$watchReady took=${prearmTookMs}ms")
                // STEP 3: start audio + show AlarmActivity. By now phone +
                // watch are both presenting (or we timed out waiting).
                startRingingAudioAndUi(alarm, resolveAudioUri(alarm))
                // No sync-on-fire — forceSync's sync_check/sync_replace
                // would yank the watch off its ring screen.
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

    // Pre-unlock: read BFU cache; audio fallback handled by
    // forceBundledFallback in AlarmAudioPlayer.start().
    private suspend fun fetchAlarmForRing(alarmId: Long): Alarm? {
        if (isUserUnlocked) {
            return repository.getById(alarmId)
        }
        Log.i(TAG, "fetchAlarmForRing pre-unlock id=$alarmId — reading BFU cache")
        return bfuCache.getAll().firstOrNull { it.id == alarmId }
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
    private suspend fun preArmWatch(alarmId: Long, alarm: Alarm): Boolean {
        // Thin-client envelope hints — see AlarmRingHints KDoc. Computed
        // once here, sent on every send/await variant below so the watch
        // can ring with the right pattern + Snooze gating WITHOUT prior
        // sync (cold-paired watch, dropped sync_replace, etc.).
        val ringHints = com.kirkouski.gtwake.companion.wear.AlarmRingHints.from(alarm)
        val info = wearBridge.pairedDeviceInfo.value
        if (info == null || !info.connected) {
            Log.d(TAG, "preArmWatch: no connected watch (info=$info) — skipping wait")
            // Still fire the message so when the watch DOES come back online
            // it gets the historical event (cheap, fire-and-forget).
            wearBridge.sendAlarmFired(alarmId, ringHints)
            return false
        }
        if (!isUserUnlocked) {
            // Pre-unlock: no incoming receiver, so await would hang. Fire-and-forget.
            Log.i(TAG, "preArmWatch: pre-unlock — sending fire-and-forget, no await")
            wearBridge.sendAlarmFired(alarmId, ringHints)
            return false
        }
        return wearBridge.sendAlarmFiredAwaiting(alarmId, PREARM_TIMEOUT_MS, ringHints)
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

    // Per-alarm URI → app Settings default → null (player falls back to system alarm).
    // DataStore is post-unlock only; returns null pre-BFU so bundled fallback plays.
    private suspend fun resolveAudioUri(alarm: Alarm): String? {
        if (alarm.audioUri != null || !isUserUnlocked) return alarm.audioUri
        val s = settingsStore.snapshot()
        return if (alarm.isRelative) s.defaultRelativeRingtoneUri else s.defaultAbsoluteRingtoneUri
    }

    private fun startRingingAudioAndUi(alarm: Alarm, effectiveAudioUri: String?) {
        // Assign `player` BEFORE start() so that if MediaPlayer init throws
        // mid-call (audio focus denied, codec failure, broken URI), the
        // partially-initialized instance is still reachable from
        // stopForegroundAndSelf for a release() call. Otherwise the
        // exception bubbles up through handleRing's catch and the
        // MediaPlayer leaks until GC.
        val p = AlarmAudioPlayer(applicationContext)
        player = p
        // Pre-unlock: SAF/system-default URIs return null; use bundled tone.
        p.start(
            uri = effectiveAudioUri,
            vibrateOnly = alarm.isVibrationOnly,
            pattern = alarm.vibrationPattern,
            volumeRampSeconds = alarm.volumeRampSeconds,
            forceBundledFallback = !isUserUnlocked,
        )

        // CANONICAL Huawei Wear Engine compliance path: ensure AlarmActivity
        // is in the task at fire time. Huawei P2P receive REQUIRES an Activity
        // component to be present — without one, the watch can't deliver
        // dismiss/snooze envelopes back to the phone. Watch sync is the core
        // product feature; this guarantee is non-negotiable. See memory/
        // wear_engine_requires_activity.md.
        //
        // Two paths bring AlarmActivity into the task:
        //   1. NotificationManager's FSI dispatch when we post the notification
        //      with setFullScreenIntent (runs as uid 10051 with the
        //      BAL_ALLOW_NON_APP_VISIBLE_WINDOW exemption — always succeeds
        //      when screen is locked + canFsi=true).
        //   2. PI.send() from this service — covers the case where FSI degraded
        //      to heads-up only (device active, FSI permission revoked, OEM
        //      policy). Uses ActivityOptions with
        //      setPendingIntentCreatorBackgroundActivityStartMode so it
        //      survives Android 14+ BAL.
        //
        // If AlarmActivity.isVisible is already true, FSI already brought it
        // up and PI.send would just re-enter the singleInstance task with a
        // fresh ActivityRecord — the user would see a "second screen sliding
        // in" ~2.6 s after FSI launch (the system animates the open
        // transition over the already-visible Activity). Skip in that case
        // as a UX-only optimization. The Wear Engine requirement is already
        // satisfied by FSI's launch. Visible flag set in AlarmActivity.onStart
        // / cleared in onStop.
        if (AlarmActivity.isVisible.get()) {
            Log.d(TAG, "skip PI.send — AlarmActivity already visible (FSI launched it)")
            return
        }
        val launchIntent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // reason: ActivityOptions.makeBasic() is the only API that
            // exposes setPendingIntentCreatorBackgroundActivityStartMode on
            // 34+; the alternative pendingIntentBackgroundActivityStartMode
            // is on PendingIntentOptions which our minSdk doesn't have.
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

    // ACTIVITY RULE: when fromPeer=false this awaits the watch's
    // alarm_dismissed_ack (a Wear Engine P2P receive). That ack is only
    // delivered while AlarmActivity is alive in the task — so AlarmActivity
    // deliberately does NOT finishAndRemoveTask() on the dismiss tap; it
    // stays up showing "waiting for watch" until stopForegroundAndSelf()
    // below emits RingEndedSignal. Do not "optimise" the Activity away.
    // reason: complexity rose past 10 because dismissAction() now branches
    // on KEEP/DISABLE/DELETE × fromPeer (LocalOnly vs broadcasting variant),
    // and there's a separate pre-unlock branch + a serviceScope.launch
    // gating on user-unlocked. Each branch maps to a distinct end state;
    // collapsing would smear the data/peer rules.
    @Suppress("CyclomaticComplexMethod")
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

        if (!isUserUnlocked) {
            // Pre-unlock: Room locked, transport-only watch send; serviceScope
            // keeps the FGS alive so a process reap can't kill the send.
            // markDismissed records the intent for post-unlock dismissAction.
            Log.i(TAG, "dismiss id=$alarmId pre-unlock — notifying watch + recording intent")
            serviceScope.launch {
                bfuCache.markDismissed(alarmId)
                val delivered = withTimeoutOrNull(PRE_UNLOCK_SEND_TIMEOUT_MS) {
                    wearBridge.sendAlarmDismissed(alarmId)
                } ?: false
                Log.i(TAG, "dismiss id=$alarmId pre-unlock send delivered=$delivered")
                stopForegroundAndSelf()
            }
            return
        }

        serviceScope.launch {
            handlerMutex.withLock {
                // Commit local truth FIRST, then broadcast. If the OS kills
                // us between the two, the watch can recover via the next
                // sync hash mismatch; the phone DB can't recover its own
                // inconsistent state, so it gets priority.
                val alarm = repository.getById(alarmId)
                // Reset the consecutive-snooze counter — a real dismiss
                // ends the snooze cycle. Done BEFORE the action so DELETE
                // (which removes the row) doesn't no-op the reset.
                if (alarm != null) repository.resetSnoozeCounter(alarmId)
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
                        // Idempotent if peer originated this dismiss.
                        repository.delete(alarmId)
                    }
                }
                if (!fromPeer) {
                    val acked = wearBridge.sendAlarmDismissedAwaiting(alarmId, BROADCAST_AWAIT_MS)
                    Log.i(TAG, "dismiss broadcast id=$alarmId acked=$acked")
                }
            }
            stopForegroundAndSelf()
        }
    }

    // ACTIVITY RULE: same as handleDismiss — when fromPeer=false this awaits
    // the watch's alarm_snoozed_ack (a P2P receive), which only arrives
    // while AlarmActivity is alive in the task. AlarmActivity stays up
    // ("waiting for watch") until stopForegroundAndSelf() emits
    // RingEndedSignal.
    // reason: 3 paths (pre-unlock, snooze-disabled→dismiss, happy path)
    // sharing player/handler teardown; splitting smears the Mutex contract.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun handleSnooze(alarmId: Long, fromPeer: Boolean, rescheduleEpochFromPeer: Long) {
        Log.d(TAG, "snooze id=$alarmId fromPeer=$fromPeer reschedule=$rescheduleEpochFromPeer")
        // Stop audio + vibration NOW (same rationale as handleDismiss) —
        // no user wants a snooze tap that keeps the alarm playing for
        // another 2s while the watch ACK arrives.
        player?.stop()
        player = null
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null

        if (!isUserUnlocked) {
            // Pre-unlock: schedule directly via AlarmManager (direct-boot
            // OK); BFU cache carries per-alarm snoozeMinutes.
            serviceScope.launch {
                val alarm = bfuCache.getAll().firstOrNull { it.id == alarmId }
                if (alarm == null) {
                    Log.w(TAG, "snooze id=$alarmId pre-unlock — not in BFU cache, dropping snooze")
                    stopForegroundAndSelf()
                    return@launch
                }
                if (!alarm.isSnoozeEnabled) {
                    Log.i(TAG, "snooze id=$alarmId pre-unlock — snooze-disabled, collapse to dismiss")
                    bfuCache.markDismissed(alarmId)
                    val delivered = withTimeoutOrNull(PRE_UNLOCK_SEND_TIMEOUT_MS) {
                        wearBridge.sendAlarmDismissed(alarmId)
                    } ?: false
                    Log.i(TAG, "snooze→dismiss id=$alarmId pre-unlock send delivered=$delivered")
                    stopForegroundAndSelf()
                    return@launch
                }
                val trigger = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
                scheduler.scheduleAt(alarm, trigger)
                // Pre-unlock: Room is locked so we can't bump consecutiveSnoozeCount
                // directly. Record the bump in BFU; post-unlock reconcile drains
                // it into Room so the cap applies uniformly across the unlock
                // boundary. Without this, lockscreen-snoozers escape the cap.
                if (!fromPeer) {
                    bfuCache.markPendingSnoozeBump(alarmId)
                }
                val delivered = withTimeoutOrNull(PRE_UNLOCK_SEND_TIMEOUT_MS) {
                    wearBridge.sendAlarmSnoozed(alarmId, trigger)
                } ?: false
                Log.i(
                    TAG,
                    "snooze id=$alarmId pre-unlock — +${alarm.snoozeMinutes}min " +
                        "trigger=$trigger delivered=$delivered fromPeer=$fromPeer " +
                        "(BFU bump=${!fromPeer})",
                )
                stopForegroundAndSelf()
            }
            return
        }

        serviceScope.launch {
            // Read the row OUTSIDE the Mutex to decide whether to collapse to
            // dismiss. `snoozeMinutes` is a config field; it doesn't race
            // against the dismiss path (which mutates `enabled` /
            // `snoozedUntilEpoch`), so the read is safe without locking.
            val alarm = repository.getById(alarmId)
            if (alarm == null) {
                Log.w(TAG, "snooze id=$alarmId — row missing, skipping")
                stopForegroundAndSelf()
                return@launch
            }
            if (!fromPeer && !alarm.isSnoozeEnabled) {
                // Snooze was disabled on this alarm — stale notification
                // action button was tapped. Treat as a dismiss: from the
                // user's perspective the alarm went silent, and we need
                // the dismiss-side state transitions (self-destruct DELETE,
                // recurring DISABLE if applicable, snoozedUntilEpoch=null)
                // to actually commit. handleDismiss owns its own launch +
                // Mutex acquisition; calling it from here just schedules the
                // dismiss coroutine and returns — Mutex serializes the two.
                Log.i(TAG, "snooze id=$alarmId — snooze disabled, collapsing to dismiss")
                handleDismiss(alarmId, fromPeer = false)
                return@launch
            }
            handlerMutex.withLock {
                // Commit local truth FIRST, then broadcast — matches
                // handleDismiss ordering so a process kill between the two
                // leaves the phone consistent; the watch recovers via the
                // next sync hash mismatch.
                val trigger = if (fromPeer && rescheduleEpochFromPeer > 0L) {
                    rescheduleEpochFromPeer
                } else {
                    System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
                }
                repository.snoozeAt(alarmId, trigger, fromPeer = fromPeer)
                if (!fromPeer) {
                    val acked = wearBridge.sendAlarmSnoozedAwaiting(
                        alarmId,
                        trigger,
                        BROADCAST_AWAIT_MS,
                    )
                    Log.i(TAG, "snooze broadcast id=$alarmId acked=$acked trigger=$trigger")
                }
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

    // onDestroy: `serviceScope.cancel()` is non-blocking. If the OS reaps the
    // service while a handler is mid-DB-write (Room transactions usually <10ms
    // but tombstone+broadcast chains can take longer), the write may be
    // truncated. Trade-off accepted: blocking onDestroy violates the Service
    // lifecycle contract; non-blocking is the correct call. Critical writes
    // are committed BEFORE the broadcast (see handleDismiss/handleSnooze
    // ordering) so the phone DB stays consistent even on mid-flight kill.
    override fun onDestroy() {
        serviceScope.cancel()
        player?.stop()
        player = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "com.kirkouski.gtwake.companion.ACTION_RING"
        const val ACTION_DISMISS = "com.kirkouski.gtwake.companion.ACTION_DISMISS"
        const val ACTION_SNOOZE = "com.kirkouski.gtwake.companion.ACTION_SNOOZE"
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
        // FGS-alive budget for a pre-unlock dismiss/snooze transport ACK.
        private const val PRE_UNLOCK_SEND_TIMEOUT_MS = 3_000L
        private const val TAG = "AlarmRing"

        // Request-code high bit for the direct-launch PendingIntent that
        // bypasses the FGS BAL block (see startRingingAudioAndUi). Distinct
        // from AlarmNotifications.FULL_SCREEN_BIT (0x08000000) so the two
        // PIs don't collide on FLAG_UPDATE_CURRENT.
        private const val DIRECT_LAUNCH_REQUEST_CODE_BIT = 0x04000000

        // Pure helper extracted for unit testing. Decides what to do to the
        // alarm row when the user dismisses (or the ring cycle auto-stops).
        // Three outcomes:
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
