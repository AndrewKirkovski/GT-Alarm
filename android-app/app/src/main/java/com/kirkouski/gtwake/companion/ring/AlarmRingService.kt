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
import androidx.core.net.toUri
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.SettingsState
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.data.bfu.BfuAlarmCache
import com.kirkouski.gtwake.companion.di.IoDispatcher
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.scheduler.AlarmScheduler
import com.kirkouski.gtwake.companion.wear.AlarmRingHints
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One dismiss/snooze request as it reaches the service. [firedAtEpoch] and
 * [requestStamp] are present only for occurrence-aware watch requests (and the
 * `firedAt` of a phone-local tap from the Activity or the notification).
 */
internal data class RingActionRequest(
    val alarmId: Long,
    val fromPeer: Boolean,
    val firedAtEpoch: Long? = null,
    val requestStamp: Long? = null,
    val rescheduleEpoch: Long = -1L,
    val autoStop: Boolean = false,
) {
    /** Only these watch requests get a reply; everything else is legacy. */
    val isOccurrenceAware: Boolean
        get() = firedAtEpoch != null && requestStamp != null
}

// reason: TooManyFunctions and LargeClass share one cause. One handler per
// action (ring, dismiss, snooze) × pre-unlock variants, plus lifecycle hooks,
// audio helpers, and notification utilities — each maps to a distinct OS event
// and all of them share the main-thread-only ring state (one occurrence, one
// player, one set of timers); splitting would scatter that state across more
// files and lose the single-owner rule.
@Suppress("TooManyFunctions", "LargeClass")
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
    // A field (not a getter on the system service) only so unit tests, which
    // have no Context, can pin the unlock state.
    private val unlockedProbe: () -> Boolean = {
        getSystemService(UserManager::class.java)?.isUserUnlocked == true
    }
    private val isUserUnlocked: Boolean
        get() = unlockedProbe()

    // Unit-test seams for the two steps that need a real Context
    // (notification building, MediaPlayer, PendingIntent). Production uses the
    // defaults, which call the private implementations below.
    private val foregroundStarter: (Alarm, Long) -> Unit = { alarm, firedAt -> startForegroundOnly(alarm, firedAt) }
    private val ringPresenter: (Alarm, String?, Long) -> Unit = { alarm, uri, firedAt ->
        startRingingAudioAndUi(alarm, uri, firedAt)
    }

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }

    // Runs the finalization of a superseded ring (its dismiss policy and its
    // watch end message). onDestroy does NOT cancel this scope: a second alarm
    // that ends quickly (a legacy watch dismiss tears the service down at
    // once, or a fast phone dismiss) must not cancel the first alarm's
    // one-shot disable or self-destruct delete half way. Every job here is
    // bounded by its own timeout, and once [destroyed] is set it touches no
    // ring state (see onMainWhileAlive).
    private val finalizerScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Set first thing in onDestroy. Read by the finalizer jobs, which outlive
    // the service; everything else is main-thread state of a live service.
    @Volatile private var destroyed: Boolean = false

    // Serializes the commit of dismiss/snooze and the takeover's finalization
    // of a superseded ring, PER ALARM ID. The race the lock exists for is
    // ACTION_DISMISS arriving while ACTION_SNOOZE is mid-flight (or the
    // reverse) on ONE row: without serialization the row could end up with
    // enabled=false AND snoozedUntilEpoch set, a contradictory state. A single
    // global lock would let a stalled save of alarm A block every action on
    // alarm B, which then rings forever. Ids are few (the user's alarms), so
    // the map is never trimmed.
    private val alarmMutexes = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    private fun mutexFor(alarmId: Long): Mutex = alarmMutexes.getOrPut(alarmId) { Mutex() }

    // ---- Ring state: MAIN THREAD ONLY. -------------------------------------
    // IO coroutines persist data and talk to the bridge. They return to the
    // main thread through onMain {} and re-check the occurrence there before
    // they change anything below, stop the foreground state, or stop the service.
    private var player: AlarmAudioPlayer? = null
    private var currentAlarmId: Long = -1L
    private var currentFiredAtEpoch: Long = -1L
    private var ringJob: Job? = null
    private var autoStopRunnable: Runnable? = null
    private var autoStopRetries: Int = 0

    // True while the armed auto-stop timer is the give-up deadline, i.e. the
    // auto-stop attempt now in progress is the last one. The retry counter
    // alone cannot say this: the watchdog counts the NEXT attempt when it arms.
    private var autoStopFinalAttempt: Boolean = false
    private var graceRunnable: Runnable? = null
    private var lastStartId: Int = 0

    /** Ring-UI audit (see [scheduleRingUiAudit]). */
    private var ringUiAuditRunnable: Runnable? = null

    /**
     * `elapsedRealtime()` captured just before the FSI-carrying notification is
     * posted. The audit compares `AlarmActivity.lastCreatedElapsedMs` against
     * THIS, not against audio start: a successful FSI launches the Activity at
     * notification-post time, which can be up to `PREARM_TIMEOUT_MS` (10 s)
     * before `startRingingAudioAndUi` runs, so comparing against audio start
     * would misreport every healthy ring as NEVER_LAUNCHED.
     */
    private var ringStartedElapsedMs: Long = 0L

    // Keeps re-sending alarm_fired after the pre-arm window runs out (see
    // startWatchCatchUp). Cancelled as soon as the ring ends.
    private var watchCatchUpJob: Job? = null

    // True once the current occurrence is over (committed action, takeover,
    // teardown). A dismiss can land while the pre-arm is still awaiting (the
    // FSI notification with its Dismiss action is already up), before the
    // catch-up job exists, so every background step re-checks this.
    private var ringEnded: Boolean = false

    // True while a dismiss/snooze for the current occurrence is being saved.
    private var ringActionStarted: Boolean = false

    // A phone-local tap or the auto-stop silenced this ring. If the save then
    // fails, the audio stays off (HEAD behaviour), and a pre-arm that is still
    // running must not start it afterwards.
    private var localStopRequested: Boolean = false

    // In-flight actions, request results and occurrence outcomes. In memory
    // for this service instance only (decided: no persistent receipts).
    private val peerState = PeerActionState()

    // Occurrences whose alarm_fired (pre-arm or catch-up) is being delivered to
    // the watch right now, MAIN THREAD ONLY. The 207 delivery record only
    // appears when a send returns, so a phone Stop while the send is in flight
    // would otherwise decide "the watch is not ringing" and send no end
    // message, and the fire then lands and the watch rings. The entry is
    // removed when the delivery effort returns on its own; the record decides
    // from then on. An effort cut short by the ring's end and pre-unlock
    // fire-and-forget sends keep their entry (their landing time is unknown);
    // the set is per service and tiny.
    private val fireSendsInFlight = mutableSetOf<OccurrenceKey>()

    // The ring that replaced an older one. [settled] completes once its
    // alarm_fired attempt is over (pre-arm returned) or the ring itself ended;
    // [confirmed] says the watch answered alarm_ringing.
    private class RingFire(val alarmId: Long, val startedElapsedMs: Long) {
        val settled = CompletableDeferred<Unit>()

        @Volatile var confirmed: Boolean = false
    }

    private data class SupersededRing(
        val key: OccurrenceKey,
        val watchMayBeRinging: Boolean,
        // The old occurrence already had a committed outcome when the new ring
        // took over; kept so a destroyed service need not be read again.
        val endedAtTakeover: Boolean,
        val replacedBy: RingFire? = null,
    )

    // The fire state of the CURRENT ring (null before the first ring).
    private var ringFire: RingFire? = null

    private data class ActionClaim(
        val occurrence: OccurrenceKey,
        val ringStartedMs: Long,
        val requestKey: PeerRequestKey?,
    )

    private sealed interface ActionResult {
        data class Committed(val outcome: RingOutcome, val triggerEpoch: Long?) : ActionResult
        data object Superseded : ActionResult
        data object Failed : ActionResult
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlarmNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // stopSelfResult(lastStartId) stops the service only if no newer start
        // is pending, so a teardown can never kill a ring that was just started.
        lastStartId = startId
        val action = intent?.action
        val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L
        val request = RingActionRequest(
            alarmId = alarmId,
            fromPeer = intent?.getBooleanExtra(EXTRA_FROM_PEER, false) ?: false,
            firedAtEpoch = intent?.getLongExtra(EXTRA_FIRED_AT, -1L)?.takeIf { it > 0L },
            requestStamp = intent?.getLongExtra(EXTRA_REQUEST_STAMP, -1L)?.takeIf { it > 0L },
            rescheduleEpoch = intent?.getLongExtra(EXTRA_RESCHEDULE_EPOCH, -1L) ?: -1L,
        )
        Log.d(TAG, "onStartCommand action=$action id=$alarmId fromPeer=${request.fromPeer}")

        when (action) {
            ACTION_RING -> handleRing(alarmId)
            ACTION_DISMISS -> handleAction(RingActionKind.DISMISS, request)
            ACTION_SNOOZE -> handleAction(RingActionKind.SNOOZE, request)
            else -> {
                Log.w(TAG, "unknown action: $action")
                stopWhenIdle()
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------
    // Ring start
    // ---------------------------------------------------------------------

    private fun handleRing(alarmId: Long) {
        if (alarmId < 0L) {
            bailWithPlaceholderIfIdle()
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
            bailWithPlaceholderIfIdle()
            return
        }
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
            bailWithPlaceholderIfIdle()
            return
        }
        // Recurrence is armed HERE, before anything can end this ring. Earlier
        // builds armed it at the very end of the ring job, so a dismiss, a
        // snooze or a takeover during the pre-arm window skipped it and the
        // alarm never fired on later days. A snooze or disable that follows
        // reuses the same request code and replaces this entry.
        armRecurrence(alarm)
        val superseded = takeOverService(alarm)
        val firedAtEpoch = currentFiredAtEpoch
        // Finalizing the previous ring is NOT part of the new ring's job:
        // ringJob.cancel() on the next takeover would otherwise cancel a
        // finalization that has not run yet, and the oldest of three quick
        // alarms would lose its dismiss policy. It still runs under
        // its alarm's mutex and the new ring never waits for it. It runs in
        // finalizerScope, which a stopping service does not cancel.
        superseded?.let { old ->
            launchDetached("finish superseded ring id=${old.key.alarmId}", finalizerScope) {
                finishSupersededRing(old)
            }
        }
        ringJob = serviceScope.launch { runRing(alarm, firedAtEpoch) }
    }

    // Even on early-exit we MUST call startForeground within 5 s of
    // startForegroundService — Android 12+ throws
    // ForegroundServiceDidNotStartInTimeException otherwise. Use a placeholder
    // notification and immediately stop. A ring that is already running keeps
    // the service foreground, so only an idle service needs the placeholder.
    private fun bailWithPlaceholderIfIdle() {
        if (currentAlarmId < 0L) {
            startForegroundPlaceholder()
            stopForegroundAndSelf()
        }
    }

    private fun armRecurrence(alarm: Alarm) {
        if (alarm.daysOfWeek == 0) return
        // The snooze trigger that just fired is consumed (consumeFireState
        // clears it later). If it still read a hair in the future, the
        // calculator would re-arm that same instant instead of the next day.
        // Side effect, debug-only: "Fire alarm now" on a recurring alarm that
        // has a FUTURE snooze pending clears that snooze for this arm and so
        // re-arms the normal recurrence instead of the snooze. The row's own
        // snoozedUntilEpoch is cleared by consumeFireState right after anyway.
        val toArm = if (alarm.snoozedUntilEpoch != null) alarm.copy(snoozedUntilEpoch = null) else alarm
        runCatching { scheduler.schedule(toArm) }
            .onFailure { Log.e(TAG, "recurrence scheduling failed id=${alarm.id}", it) }
    }

    /**
     * This service owns one ring at a time. A different alarm takes over: the
     * previous occurrence is finalized (dismiss policy + a detached watch
     * notification) by [finishSupersededRing], launched detached from
     * [handleRing] so the new ring never waits on it. The SAME
     * alarm firing again (for example "Fire alarm now" tapped twice) is not a
     * supersession: no dismiss policy is applied to it.
     */
    private fun takeOverService(alarm: Alarm): SupersededRing? {
        val previous = if (currentAlarmId >= 0L) OccurrenceKey(currentAlarmId, currentFiredAtEpoch) else null
        // The previous occurrence's ring start, captured BEFORE it is reset:
        // the "could the watch be ringing it" check must not use the new ring's.
        val superseded = previous?.takeIf { it.alarmId != alarm.id }?.let {
            SupersededRing(
                key = it,
                watchMayBeRinging = watchMayBeRinging(it, ringStartedElapsedMs),
                endedAtTakeover = peerState.outcomeOf(it) != null,
            )
        }
        ringJob?.cancel()
        ringJob = null
        stopCurrentRingResources()
        ringStartedElapsedMs = android.os.SystemClock.elapsedRealtime()
        // The old ring's end message waits on THIS ring's alarm_fired outcome
        // (see sendEndedToWatch): a released watch ends whatever ring is on
        // screen, so it must not follow a fire that already reached the watch.
        val fire = RingFire(alarm.id, ringStartedElapsedMs)
        ringFire = fire
        // Phone wall time of this ring, sent as `firedAt` so the watch can
        // match re-sent copies. Strictly increasing within this service.
        val firedAtEpoch = maxOf(System.currentTimeMillis(), currentFiredAtEpoch + 1L)
        // A stopped AlarmActivity (screen off, backup launch blocked) may still
        // show an older occurrence; it follows this retained current one.
        RingEndedSignal.setCurrent(alarm.id, firedAtEpoch)
        currentAlarmId = alarm.id
        currentFiredAtEpoch = firedAtEpoch
        ringEnded = false
        ringActionStarted = false
        localStopRequested = false
        autoStopRetries = 0
        autoStopFinalAttempt = false
        foregroundStarter(alarm, firedAtEpoch)
        // Armed at ring start (not at audio start): a failed dismiss during the
        // pre-arm, before any audio exists, must still end up auto-stopped.
        scheduleAutoStop(alarm.id, firedAtEpoch, AUTO_STOP_MS)
        return superseded?.copy(replacedBy = fire)
    }

    // Everything after the synchronous start. Runs on the IO dispatcher;
    // every ring-state read goes through onMain.
    // reason: the body chains NotificationCompat builders, AlarmAudioPlayer,
    // startActivity, scheduler — anything throwing here (RemoteException,
    // SecurityException, OOM, NotificationManagerCompat IllegalStateException)
    // would zombie the FG notification we already showed. Catch broadly +
    // tear down so the user can fire again.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runRing(alarm: Alarm, firedAtEpoch: Long) {
        val alarmId = alarm.id
        try {
            val active = prepareActiveAlarm(alarmId, firedAtEpoch)
            if (active != null) presentRing(active, firedAtEpoch)
        } catch (e: CancellationException) {
            // Arrives via the normal teardown path (serviceScope.cancel from
            // onDestroy, or ringJob.cancel on takeover); swallowing it would
            // break cancellation propagation to any nested suspend.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ring body threw — tearing down FG to avoid zombie", e)
            onMain { stopForegroundAndSelf(alarmId, firedAtEpoch) }
        }
    }

    private suspend fun prepareActiveAlarm(alarmId: Long, firedAtEpoch: Long): Alarm? {
        if (!isCurrent(alarmId, firedAtEpoch)) return null
        val active = fetchAlarmForRing(alarmId)
        if (active == null || !active.enabled) {
            Log.i(TAG, "ring superseded by committed action id=$alarmId firedAt=$firedAtEpoch")
            onMain { stopForegroundAndSelf(alarmId, firedAtEpoch) }
            return null
        }
        consumeFireState(active, firedAtEpoch)
        return if (isCurrent(alarmId, firedAtEpoch)) active else null
    }

    // Snooze consumed: the trigger that just fired was either the alarm's
    // normal clock-time or its snooze-deferred trigger. Either way the
    // snoozedUntilEpoch override no longer reflects reality — the alarm is
    // RINGING right now, not deferred. Clear it before the UI re-renders so the
    // list shows correct state.
    //
    // Skip-next consumed: if a skip was active for an earlier instant,
    // NextTriggerCalculator already advanced past it and THIS fire is the
    // post-skip occurrence. Clear the now-stale skipNextEpoch so the row
    // doesn't leak ambiguous state into sync hash + UI.
    //
    // Pre-unlock branch: Room is locked, so the cleared fields are written
    // straight to the BFU mirror; the post-unlock reconcile copies Room INTO
    // the BFU cache (and applies the pending BFU intents), not the reverse.
    // Without this, a pre-unlock fire on a skipped alarm crashed the launch
    // block before audio could play.
    // Both clears are housekeeping: a failure is logged and the alarm rings
    // anyway (a silent alarm is far worse than a stale list row).
    private suspend fun consumeFireState(active: Alarm, firedAtEpoch: Long) {
        val alarmId = active.id
        val unlocked = isUserUnlocked
        if (active.snoozedUntilEpoch != null) {
            bestEffort("clear consumed snooze id=$alarmId firedAt=$firedAtEpoch") {
                if (unlocked) {
                    repository.clearSnoozedUntil(alarmId)
                } else if (!bfuCache.setSnoozedUntil(alarmId, null)) {
                    Log.e(TAG, "failed to clear consumed BFU snooze id=$alarmId firedAt=$firedAtEpoch")
                }
            }
        }
        if (active.skipNextEpoch != null) {
            bestEffort("clear consumed skip id=$alarmId firedAt=$firedAtEpoch") {
                if (unlocked) {
                    repository.consumePastSkip(alarmId)
                } else if (bfuCache.consumePastSkip(alarmId, System.currentTimeMillis())) {
                    Log.i(TAG, "skip-next pre-unlock-cleared id=$alarmId (BFU only)")
                } else {
                    Log.e(TAG, "failed to clear consumed BFU skip id=$alarmId firedAt=$firedAtEpoch")
                }
            }
        }
    }

    // reason: housekeeping steps surface Room, DataStore and file errors with
    // no common type; none of them may stop the ring. Cancellation is rethrown.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun bestEffort(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$label failed — ringing anyway", e)
        }
    }

    private suspend fun presentRing(active: Alarm, firedAtEpoch: Long) {
        val alarmId = active.id
        // STEP 2: pre-arm — if watch is paired+connected, await alarm_fired
        // delivery up to PREARM_TIMEOUT_MS. Returns immediately false if no
        // watch is reachable.
        preArmWatchTimed(alarmId, active, firedAtEpoch)
        if (!isCurrent(alarmId, firedAtEpoch)) return
        // STEP 3: start audio + show AlarmActivity. By now phone + watch are
        // both presenting (or we timed out waiting). No sync-on-fire —
        // forceSync's sync_check/sync_replace would yank the watch off its
        // ring screen.
        val audioUri = resolveAudioUri(active)
        onMain {
            if (isCurrentRing(alarmId, firedAtEpoch)) ringPresenter(active, audioUri, firedAtEpoch)
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

    // ---------------------------------------------------------------------
    // Watch pre-arm and catch-up
    // ---------------------------------------------------------------------

    // Pre-arm with timing log; if the watch did not confirm in time, keep
    // delivering in the background (startWatchCatchUp).
    private suspend fun preArmWatchTimed(alarmId: Long, alarm: Alarm, firedAtEpoch: Long) {
        val prearmStartMs = System.currentTimeMillis()
        val ringHints = buildRingHints(alarm, firedAtEpoch)
        val watchReady = preArmWatch(OccurrenceKey(alarmId, firedAtEpoch), ringHints)
        val prearmTookMs = System.currentTimeMillis() - prearmStartMs
        Log.i(TAG, "preArmWatch id=$alarmId ready=$watchReady took=${prearmTookMs}ms")
        onMain {
            if (matchesCurrentOccurrence(alarmId, firedAtEpoch)) {
                // A ring that ended first was settled by cancelRingTimers.
                ringFire?.let {
                    it.confirmed = watchReady
                    it.settled.complete(Unit)
                }
            }
            if (!watchReady) startWatchCatchUp(alarmId, firedAtEpoch, ringHints)
        }
    }

    // Thin-client envelope hints — see AlarmRingHints KDoc. Computed once per
    // ring and passed to every send (pre-arm and catch-up) so the watch rings
    // with the right pattern + Snooze gating WITHOUT a prior sync.
    // supportsActionAck tells the watch to wait for a committed reply; it is
    // true only when this phone can actually receive the watch's request now.
    // In a cold process the receiver registration started in
    // Application.onCreate is usually unfinished, so wait briefly for it
    // (awaitReceiveReady never wakes the watch). It also fills
    // pairedDeviceInfo, which preArmWatch reads AFTER this.
    private suspend fun buildRingHints(alarm: Alarm, firedAtEpoch: Long): AlarmRingHints =
        AlarmRingHints.from(alarm, readSettingsOrNull()).copy(
            firedAtEpoch = firedAtEpoch,
            supportsActionAck = awaitReceiveReadyIfUnlocked(),
        )

    // reason: the bridge call touches Wear Engine; a failure must only mean
    // "not ready" (no supportsActionAck), never cancel the ring.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun awaitReceiveReadyIfUnlocked(): Boolean {
        if (!isUserUnlocked) return false
        return try {
            wearBridge.awaitReceiveReady(RECEIVE_READY_WAIT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "awaitReceiveReady failed — not advertising supportsActionAck", e)
            false
        }
    }

    // Settings live in credential-encrypted storage: read them only once the
    // user has unlocked. A failing read (corrupt DataStore, migration write
    // error) must never cancel ring startup, so it degrades to "unknown".
    // reason: DataStore surfaces IOException, CorruptionException and
    // migration failures with no common type; the ring must start regardless.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun readSettingsOrNull(): SettingsState? {
        if (!isUserUnlocked) return null
        return try {
            settingsStore.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "settings snapshot failed — ringing with default settings", e)
            null
        }
    }

    // Try to wake the watch so it shows its own ring page in time with the
    // phone. Returns true if a 207 (COMM_SUCCESS) came back within budget;
    // false if no watch is paired/connected OR we timed out waiting.
    private suspend fun preArmWatch(occurrence: OccurrenceKey, ringHints: AlarmRingHints): Boolean {
        val alarmId = occurrence.alarmId
        val info = wearBridge.pairedDeviceInfo.value
        if (info == null || !info.connected) {
            Log.d(TAG, "preArmWatch: no connected watch (info=$info) — skipping wait")
            // Still fire the message so when the watch DOES come back online
            // it gets the historical event (cheap, fire-and-forget).
            wearBridge.sendAlarmFired(alarmId, ringHints)
            return false
        }
        // A send to a connected watch starts now: until it comes back, a phone
        // Stop must still reach the watch (see fireSendsInFlight).
        onMain { fireSendsInFlight += occurrence }
        if (!isUserUnlocked) {
            // Pre-unlock: no incoming receiver, so await would hang. Fire-and-forget.
            // The mark stays: when this send lands is unknown.
            Log.i(TAG, "preArmWatch: pre-unlock — sending fire-and-forget, no await")
            wearBridge.sendAlarmFired(alarmId, ringHints)
            return false
        }
        val ready = wearBridge.sendAlarmFiredAwaiting(alarmId, PREARM_TIMEOUT_MS, ringHints)
        // Returned normally: the delivery record is authoritative from here.
        // A cancelled send keeps its mark (the ring ended mid-delivery).
        mainHandler.post { fireSendsInFlight -= occurrence }
        return ready
    }

    // The pre-arm gives the watch PREARM_TIMEOUT_MS before the phone starts
    // its own audio. That budget also CANCELS the send: on a slow cold start
    // (the FIT watches in review) the wake + send did not finish in 10 s and
    // nothing ever re-sent, so the watch never rang. Keep delivering in the
    // background, each attempt with a fresh wake budget, until the watch
    // confirms (alarm_ringing) or WATCH_CATCHUP_WINDOW_MS has passed. A late
    // duplicate is safe: the watch only re-acks a ring that is already up.
    // Awaiting alarm_ringing is allowed here because AlarmActivity is alive
    // for the whole ring (Wear Engine delivers only with an Activity).
    // The loop is bound to ONE occurrence: a superseded ring stops re-sending.
    private fun startWatchCatchUp(alarmId: Long, firedAtEpoch: Long, hints: AlarmRingHints) {
        val canCatchUp = isCurrentRing(alarmId, firedAtEpoch) && isUserUnlocked &&
            wearBridge.pairedDeviceInfo.value?.connected == true
        if (!canCatchUp) return
        watchCatchUpJob?.cancel()
        // elapsedRealtime: immune to wall-clock jumps during the window.
        val deadline = ringStartedElapsedMs + WATCH_CATCHUP_WINDOW_MS
        // The whole catch-up window counts as "delivery in progress", gaps
        // between attempts included: an earlier attempt may land at any time.
        val occurrence = OccurrenceKey(alarmId, firedAtEpoch)
        fireSendsInFlight += occurrence
        watchCatchUpJob = launchDetached("watch catch-up id=$alarmId") {
            runWatchCatchUp(alarmId, firedAtEpoch, hints, deadline)
            // Ran to its end (delivered, or the window is over): the delivery
            // record decides from here. A job cancelled by the ring's end
            // keeps its mark, so the end message of that ring is still sent.
            mainHandler.post { fireSendsInFlight -= occurrence }
        }
    }

    private suspend fun runWatchCatchUp(alarmId: Long, firedAtEpoch: Long, hints: AlarmRingHints, deadline: Long) {
        var attempt = 0
        while (isCurrent(alarmId, firedAtEpoch) && android.os.SystemClock.elapsedRealtime() < deadline) {
            attempt++
            val budget = minOf(WATCH_CATCHUP_ATTEMPT_MS, deadline - android.os.SystemClock.elapsedRealtime())
            val ready = wearBridge.sendAlarmFiredAwaiting(alarmId, budget, hints)
            Log.i(TAG, "watch catch-up id=$alarmId attempt=$attempt ready=$ready")
            if (ready) return
            delay(WATCH_CATCHUP_GAP_MS)
        }
        Log.w(TAG, "watch catch-up stopped id=$alarmId after $attempt attempt(s)")
    }

    // Only tell the watch to stop a ring it could have received. Sending a
    // dismiss/snooze to a watch that never got alarm_fired launches the watch
    // app just to close it again. [ringStartedMs] is the start of the
    // occurrence being ended, NOT whichever ring is newest.
    // When that start is unknown (the process died mid-ring and the dismiss
    // came from the surviving notification, so both this service and the
    // bridge's delivery record are fresh), send anyway: a watch left ringing
    // is worse than one extra wake. The same rule covers an alarm_fired that
    // is still being delivered (fireSendsInFlight): the 207 record does not
    // exist yet, but the watch may ring a moment later.
    // Main thread (fireSendsInFlight).
    private fun watchMayBeRinging(occurrence: OccurrenceKey, ringStartedMs: Long): Boolean {
        if (ringStartedMs == 0L || occurrence in fireSendsInFlight) return true
        val may = wearBridge.wasAlarmFiredDelivered(occurrence.alarmId, ringStartedMs)
        if (!may) Log.i(TAG, "watch never received alarm_fired id=${occurrence.alarmId} — not sending end")
        return may
    }

    // The end message of ring [occurrence] is wrong when a NEWER ring's
    // alarm_fired already reached the watch: released watch apps (1.0.7 in the
    // store, 1.0.11) do not compare alarm ids and would end the new ring. The
    // new alarm_fired already replaced the old ring on every watch version.
    // Main thread (reads the current ring).
    private fun newerFireReachedWatch(occurrence: OccurrenceKey): Boolean {
        if (currentAlarmId < 0L || matchesCurrentOccurrence(occurrence.alarmId, occurrence.firedAtEpoch)) return false
        return wearBridge.wasAlarmFiredDelivered(currentAlarmId, ringStartedElapsedMs)
    }

    // ---------------------------------------------------------------------
    // Foreground notification, audio and UI
    // ---------------------------------------------------------------------

    private fun startForegroundOnly(alarm: Alarm, firedAtEpoch: Long) {
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
            fullScreenIntent = AlarmNotifications.fullScreenPendingIntent(this, alarm.id, firedAtEpoch),
            dismissIntent = AlarmNotifications.dismissPendingIntent(this, alarm.id, firedAtEpoch),
            snoozeIntent = if (alarm.isSnoozeEnabled) {
                AlarmNotifications.snoozePendingIntent(this, alarm.id, firedAtEpoch)
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
    // A settings failure falls back to the alarm's own URI (or null = system default).
    private suspend fun resolveAudioUri(alarm: Alarm): String? {
        if (alarm.audioUri != null) return alarm.audioUri
        val settings = readSettingsOrNull() ?: return null
        return if (alarm.isRelative) settings.defaultRelativeRingtoneUri else settings.defaultAbsoluteRingtoneUri
    }

    // Main thread. Audio first, then the Activity: the Activity must be in
    // the task for the whole ring (Wear Engine receive), the audio may be
    // suppressed if a phone-local tap already silenced this occurrence.
    private fun startRingingAudioAndUi(alarm: Alarm, effectiveAudioUri: String?, firedAtEpoch: Long) {
        if (!isCurrentRing(alarm.id, firedAtEpoch)) return
        startPlayback(alarm, effectiveAudioUri)
        // Scheduled here so it covers BOTH launch paths — including the
        // already-showing early-return in launchRingActivity, where FSI
        // already brought the Activity up and we skip PI.send.
        scheduleRingUiAudit(alarm.id, ringStartedElapsedMs)
        launchRingActivity(alarm.id, firedAtEpoch)
    }

    private fun startPlayback(alarm: Alarm, effectiveAudioUri: String?) {
        if (localStopRequested) {
            Log.i(TAG, "audio not started id=${alarm.id} — a dismiss/snooze already silenced this ring")
            return
        }
        // A ring takes ownership of one player. Stop any previous instance
        // before replacing it, including when a newer occurrence supersedes.
        // Assign `player` BEFORE start() so that if MediaPlayer init throws
        // mid-call (audio focus denied, codec failure, broken URI), the
        // partially-initialized instance is still reachable from
        // stopCurrentRingResources for a stop() call.
        stopPlayback()
        val p = AlarmAudioPlayer(applicationContext)
        player = p
        // Pre-unlock: SAF/system-default URIs return null; use bundled tone.
        p.start(
            uri = effectiveAudioUri,
            vibrateOnly = alarm.isVibrationOnly,
            pattern = alarm.vibrationPattern,
            volumeRampSeconds = alarm.volumeRampSeconds,
            forceBundledFallback = !isUserUnlocked,
            phoneVibrationEnabled = alarm.phoneVibrationEnabled,
        )
    }

    private fun stopPlayback() {
        player?.stop()
        player = null
    }

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
    //      survives Android 14+ BAL on pre-16 devices.
    //
    // PI.send() is skipped only when the Activity is visible AND already shows
    // THIS occurrence (needsBackupLaunch). The intent is NEW_TASK | CLEAR_TASK
    // on a singleInstance Activity, so a send while it is showing would
    // destroy and recreate it (onNewIntent never runs on this path): the user
    // sees a second screen slide in. A visible Activity that shows a different
    // occurrence does need the relaunch. FSI already satisfies the Wear Engine
    // requirement for the skipped case.
    private fun launchRingActivity(alarmId: Long, firedAtEpoch: Long) {
        val visible = AlarmActivity.isVisible.get()
        if (!needsBackupLaunch(visible, AlarmActivity.shownOccurrence, alarmId, firedAtEpoch)) {
            Log.d(TAG, "skip PI.send — AlarmActivity already shows id=$alarmId firedAt=$firedAtEpoch")
            return
        }
        val launchIntent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_FIRED_AT, firedAtEpoch)
            data = "gtwake://alarm/$alarmId/activity/$firedAtEpoch".toUri()
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
            alarmId.toInt() or DIRECT_LAUNCH_REQUEST_CODE_BIT,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options,
        )
        runCatching { pi.send() }
            .onFailure { Log.w(TAG, "AlarmActivity PI.send() failed: ${it.message}") }
    }

    /**
     * Report, once per ring, whether the ring UI actually reached the user.
     *
     * Neither launch path tells us this on its own. SystemUI declines a
     * full-screen intent *silently* when the device is already interactive
     * (`NO_FSI_EXPECTED_TO_HUN` — a competing alarm/calendar FSI calls
     * `wakeUpForFullScreenIntent()` first, which creates exactly that
     * condition), and `PendingIntent.send()` does not throw on `BAL_BLOCK`, so
     * the `runCatching` above logs success for a launch that never happened.
     * Measured on an API 36 emulator: with another app's alarm Activity in the
     * foreground we log `FSI-fire … canFsi=true` and then produce zero
     * AlarmActivity records, with no error anywhere.
     *
     * That silence matters beyond the missing UI: with no Activity in the task
     * the Wear Engine receive path has no anchor, so the watch cannot deliver
     * dismiss/snooze back and the pre-arm await burns its full timeout on a
     * reply that structurally cannot arrive. See docs/acceptance-criteria.md
     * → "Losing the foreground to a competing full-screen intent".
     *
     * Audit only — it changes no behaviour. Retrying the FSI would not help:
     * the decline is driven by the device being interactive, which does not
     * stop being true while a competing alarm is up.
     */
    private fun scheduleRingUiAudit(alarmId: Long, firedAtElapsedMs: Long) {
        ringUiAuditRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            val createdAt = AlarmActivity.lastCreatedElapsedMs.get()
            val launched = createdAt >= firedAtElapsedMs
            val visible = AlarmActivity.isVisible.get()
            when {
                launched && visible ->
                    Log.i(TAG, "ringUiAudit id=$alarmId outcome=FOREGROUND")
                launched ->
                    Log.w(TAG, "ringUiAudit id=$alarmId outcome=LAUNCHED_THEN_COVERED")
                else -> Log.w(
                    TAG,
                    "ringUiAudit id=$alarmId outcome=NEVER_LAUNCHED — the ring UI " +
                        "never appeared. Audio is still playing via the FGS and the " +
                        "notification remains actionable, but there is NO Activity in " +
                        "the task, so watch dismiss/snooze cannot be received. Usual " +
                        "cause: another app's full-screen intent already made the " +
                        "device interactive, so SystemUI declined ours. Also check " +
                        "USE_FULL_SCREEN_INTENT (appop may be MODE_DEFAULT) and " +
                        "POST_NOTIFICATIONS.",
                )
            }
        }
        ringUiAuditRunnable = r
        mainHandler.postDelayed(r, RING_UI_AUDIT_DELAY_MS)
    }

    // ---------------------------------------------------------------------
    // Auto-stop, grace window, idle stop (main thread)
    // ---------------------------------------------------------------------

    private fun scheduleAutoStop(alarmId: Long, firedAtEpoch: Long, delayMs: Long) {
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopFinalAttempt = false
        val r = Runnable {
            autoStopRunnable = null
            Log.d(TAG, "auto-stop id=$alarmId firedAt=$firedAtEpoch")
            handleAction(
                RingActionKind.DISMISS,
                RingActionRequest(alarmId, fromPeer = false, firedAtEpoch = firedAtEpoch, autoStop = true),
            )
        }
        autoStopRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    // After a watch request committed: audio is already off and the occurrence
    // is marked ended, but the foreground service and AlarmActivity stay for
    // GRACE_MS. A duplicate request inside the window is answered ok:true from
    // memory, and the Activity is still in the task to receive it. The
    // runnable is occurrence-checked, so a ring that took over meanwhile is
    // never touched; stopCurrentRingResources also removes it.
    private fun scheduleGraceTeardown(occurrence: OccurrenceKey) {
        graceRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            graceRunnable = null
            stopForegroundAndSelf(occurrence.alarmId, occurrence.firedAtEpoch)
        }
        graceRunnable = r
        mainHandler.postDelayed(r, GRACE_MS)
    }

    // An idle service (no ring) that only answered a request has nothing left
    // to do. The short delay lets the bridge's bounded reply leave the process.
    private fun stopWhenIdle() {
        if (currentAlarmId >= 0L) return
        mainHandler.postDelayed(
            { if (currentAlarmId < 0L) stopSelfResult(lastStartId) },
            REPLY_FLUSH_MS,
        )
    }

    // ---------------------------------------------------------------------
    // Occurrence state helpers (main thread)
    // ---------------------------------------------------------------------

    private fun matchesCurrentOccurrence(alarmId: Long, firedAtEpoch: Long): Boolean =
        currentAlarmId == alarmId && currentFiredAtEpoch == firedAtEpoch

    private fun isCurrentRing(alarmId: Long, firedAtEpoch: Long): Boolean =
        matchesCurrentOccurrence(alarmId, firedAtEpoch) && !ringEnded

    private fun currentRing(): CurrentRing = CurrentRing(currentAlarmId, currentFiredAtEpoch, ringEnded)

    /** Suspend helper for IO code: reads ring state on the main thread. */
    private suspend fun isCurrent(alarmId: Long, firedAtEpoch: Long): Boolean =
        onMain { isCurrentRing(alarmId, firedAtEpoch) }

    /**
     * Runs [block] on the main thread and returns its result. The only way IO
     * coroutines touch ring state. If the scope is cancelled while waiting, the
     * block is not run.
     */
    // reason: any Throwable from the block must reach the awaiting coroutine,
    // or it would hang forever; it is re-thrown there, not swallowed.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> onMain(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val posted = mainHandler.post {
            if (continuation.isActive) {
                try {
                    continuation.resume(block())
                } catch (e: Throwable) {
                    continuation.resumeWithException(e)
                }
            }
        }
        if (!posted) continuation.resumeWithException(IllegalStateException("main looper is not accepting work"))
    }

    /**
     * Like [onMain], for jobs that outlive the service (finalizerScope): once
     * the service is destroyed the block is skipped and null is returned, so
     * such a job never touches the ring state of a dead instance.
     *
     * onDestroy clears [mainHandler]'s queue, which drops a block posted just
     * before it; each wait is therefore bounded, and the loop re-checks
     * [destroyed] instead of hanging until the caller's timeout (which would
     * cancel the very commit that must survive the stop). A wait that timed
     * out cancels its continuation, so its block can never run late.
     */
    private suspend fun <T : Any> onMainWhileAlive(block: () -> T): T? {
        while (!destroyed) {
            val result = withTimeoutOrNull(MAIN_WHILE_ALIVE_WAIT_MS) {
                onMain { if (destroyed) null else block() }
            }
            if (result != null) return result
        }
        return null
    }

    // reason: a detached coroutine has no caller to report to; an uncaught
    // exception in serviceScope would crash the process mid-alarm.
    @Suppress("TooGenericExceptionCaught")
    private fun launchDetached(
        label: String,
        scope: CoroutineScope = serviceScope,
        block: suspend () -> Unit,
    ): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$label failed", e)
        }
    }

    // Called by every path that ends the current ring (takeover, committed
    // action, give-up, teardown, destroy). A ring that ends before its pre-arm
    // returned will never deliver its fire from here, so the end message of an
    // older ring that waits on it (sendEndedToWatch) stops waiting.
    private fun cancelRingTimers() {
        ringFire?.settled?.complete(Unit)
        watchCatchUpJob?.cancel()
        watchCatchUpJob = null
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null
        autoStopFinalAttempt = false
        ringUiAuditRunnable?.let { mainHandler.removeCallbacks(it) }
        ringUiAuditRunnable = null
    }

    private fun stopCurrentRingResources() {
        ringEnded = true
        cancelRingTimers()
        graceRunnable?.let { mainHandler.removeCallbacks(it) }
        graceRunnable = null
        stopPlayback()
    }

    // ---------------------------------------------------------------------
    // Takeover finalization
    // ---------------------------------------------------------------------

    // The previous (different) alarm never got a dismiss/snooze. Apply its
    // dismiss policy under the mutex, unless an action for it already
    // committed. The watch is told DETACHED: the new ring must not wait for a
    // send that has no inner timeout. This runs in finalizerScope (not
    // cancelled when the service stops) and every step is bounded.
    private suspend fun finishSupersededRing(superseded: SupersededRing) {
        val key = superseded.key
        try {
            val applied = withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { applySupersededDismiss(superseded) }
            if (applied == null) {
                Log.e(TAG, "finalizing superseded ring timed out id=${key.alarmId} firedAt=${key.firedAtEpoch}")
            }
            if (applied == true && superseded.watchMayBeRinging) sendEndedToWatch(key, superseded.replacedBy)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught")
            // reason: finalizing the old ring is best-effort; whatever the
            // repository throws must not stop the NEW ring from starting.
            e: Exception,
        ) {
            Log.e(TAG, "failed to finalize superseded ring id=${key.alarmId} firedAt=${key.firedAtEpoch}", e)
        }
    }

    // True when the dismiss policy was applied by this call, false when an
    // action had already ended the occurrence. The in-memory outcome is only
    // recorded and read while the service is alive; a destroyed service takes
    // its peerState with it, so the takeover-time snapshot stands in.
    private suspend fun applySupersededDismiss(superseded: SupersededRing): Boolean {
        val key = superseded.key
        return mutexFor(key.alarmId).withLock {
            val alreadyEnded = superseded.endedAtTakeover ||
                onMainWhileAlive { peerState.outcomeOf(key) != null } == true
            if (!alreadyEnded) {
                commitDismiss(key.alarmId, fromPeer = true)
                onMainWhileAlive { peerState.recordSuccess(key, RingOutcome.DISMISSED, null) }
            }
            !alreadyEnded
        }
    }

    // Tells the watch that ring [key] is over. Released watch apps end WHATEVER
    // ring is on screen, so when the ring that replaced it ([replacedBy]) has
    // already had its alarm_fired delivered, the end is not sent: that fire
    // replaced the old ring on every watch version, and the end would stop the
    // new one. The wait for the new ring's outcome and the send are bounded.
    private suspend fun sendEndedToWatch(key: OccurrenceKey, replacedBy: RingFire?) {
        if (replacedBy != null) {
            withTimeoutOrNull(SUPERSEDED_FIRE_WAIT_MS) { replacedBy.settled.await() }
            val reached = replacedBy.confirmed ||
                wearBridge.wasAlarmFiredDelivered(replacedBy.alarmId, replacedBy.startedElapsedMs)
            if (reached) {
                Log.i(TAG, "new ring id=${replacedBy.alarmId} reached the watch — not ending id=${key.alarmId}")
                return
            }
        }
        withTimeoutOrNull(SUPERSEDED_SEND_TIMEOUT_MS) {
            wearBridge.sendAlarmDismissed(key.alarmId, firedAt = key.firedAtEpoch)
        }
    }

    // ---------------------------------------------------------------------
    // Dismiss / snooze
    // ---------------------------------------------------------------------

    // Admission runs on the main thread: decide, claim, silence. The commit
    // runs on IO under the mutex and settles back on main.
    //
    // ACTIVITY RULE: for a phone-local action this awaits the watch's
    // alarm_dismissed_ack/alarm_snoozed_ack (a Wear Engine P2P receive). That
    // ack is only delivered while AlarmActivity is alive in the task — so
    // AlarmActivity deliberately does NOT finishAndRemoveTask() on the tap; it
    // stays up showing "waiting for watch" until stopForegroundAndSelf()
    // emits RingEndedSignal. For a watch action the Activity stays for the
    // grace window. Do not "optimise" the Activity away.
    private fun handleAction(kind: RingActionKind, request: RingActionRequest) {
        Log.d(
            TAG,
            "${kind.wire} id=${request.alarmId} fromPeer=${request.fromPeer} " +
                "firedAt=${request.firedAtEpoch} stamp=${request.requestStamp} autoStop=${request.autoStop}",
        )
        val claim = admitAction(kind, request) ?: return
        launchDetached("${kind.wire} id=${request.alarmId}") { performAction(kind, request, claim) }
    }

    private fun admitAction(kind: RingActionKind, request: RingActionRequest): ActionClaim? {
        val occurrence = if (request.fromPeer) admitPeerAction(kind, request) else admitLocalAction(request)
        if (occurrence == null || !claimOccurrence(occurrence)) {
            stopWhenIdle()
            return null
        }
        if (!request.fromPeer) {
            // Phone-local: silence the phone NOW, independent of persistence
            // (HEAD behaviour). If the save fails the ring stays current but
            // quiet; the buttons come back and auto-stop is re-armed.
            localStopRequested = true
            stopPlayback()
            // The auto-stop's OWN save can stall too. Arm the watchdog before
            // the commit starts: it runs on the retry schedule independently of
            // persistence, and a commit (or failure) handler disarms/reuses it.
            if (request.autoStop) armAutoStopWatchdog(occurrence)
        }
        val requestKey = if (request.fromPeer) {
            PeerRequest(request.alarmId, kind.wire, request.firedAtEpoch, request.requestStamp).requestKeyOrNull()
        } else {
            null
        }
        return ActionClaim(occurrence, ringStartedElapsedMs, requestKey)
    }

    // Reply rule of docs/sync-architecture.md ("Phone reply rule"); the decision
    // itself is the pure PeerActionPolicy. Returns the occurrence to act on, or null (replied or
    // ignored here).
    private fun admitPeerAction(kind: RingActionKind, request: RingActionRequest): OccurrenceKey? {
        val peer = PeerRequest(request.alarmId, kind.wire, request.firedAtEpoch, request.requestStamp)
        val decision = PeerActionPolicy.decide(peerState, peer, currentRing())
        when (decision) {
            PeerDecision.Apply -> return peer.occurrenceOrNull()
            PeerDecision.ApplyLegacy -> return OccurrenceKey(currentAlarmId, currentFiredAtEpoch)
            PeerDecision.ReplayOk -> replyPeerAction(kind, request, ok = true)
            PeerDecision.Ignore ->
                Log.i(TAG, "${kind.wire} ignored id=${request.alarmId} firedAt=${request.firedAtEpoch} (no reply)")
            is PeerDecision.PeerEnded ->
                wearBridge.sendPeerEnded(request.alarmId, decision.type, decision.firedAtEpoch)
        }
        return null
    }

    private fun admitLocalAction(request: RingActionRequest): OccurrenceKey? {
        val occurrence = OccurrenceKey(request.alarmId, request.firedAtEpoch ?: currentFiredAtEpoch)
        val open = currentRing().isOpen(occurrence)
        if (open && !ringActionStarted) return occurrence
        if (open && request.autoStop) {
            autoStopWhileActionInFlight(occurrence)
            return null
        }
        Log.i(TAG, "stale local action ignored id=${request.alarmId} firedAt=${request.firedAtEpoch}")
        return null
    }

    // The auto-stop fired while a dismiss/snooze for this occurrence is still
    // being saved (possibly stalled forever). The ring must still end: silence
    // the phone NOW, retry on the usual backoff, and when the retries run out
    // with the action still unresolved end the ring without persistence.
    private fun autoStopWhileActionInFlight(occurrence: OccurrenceKey) {
        Log.w(TAG, "auto-stop while an action is in flight id=${occurrence.alarmId}; silencing and retrying")
        localStopRequested = true
        stopPlayback()
        retryAutoStop(occurrence)
    }

    private fun claimOccurrence(occurrence: OccurrenceKey): Boolean {
        if (!peerState.tryClaim(occurrence)) return false
        if (matchesCurrentOccurrence(occurrence.alarmId, occurrence.firedAtEpoch)) ringActionStarted = true
        return true
    }

    private fun replyPeerAction(kind: RingActionKind, request: RingActionRequest, ok: Boolean) {
        val stamp = request.requestStamp ?: return
        val firedAt = request.firedAtEpoch ?: return
        wearBridge.sendPeerActionAck(request.alarmId, kind.wire, stamp, firedAt, ok)
    }

    // The occurrence this watch request referred to is over: tell the watch how.
    private fun replyPeerEnded(request: RingActionRequest, occurrence: OccurrenceKey) {
        if (!request.isOccurrenceAware) return
        val type = PeerActionPolicy.endedType(peerState.outcomeOf(occurrence))
        wearBridge.sendPeerEnded(occurrence.alarmId, type, occurrence.firedAtEpoch)
    }

    private suspend fun performAction(kind: RingActionKind, request: RingActionRequest, claim: ActionClaim) {
        when (val result = commitAction(kind, request, claim)) {
            is ActionResult.Committed -> if (!request.fromPeer) finishLocalAction(claim, result)
            ActionResult.Superseded -> onMain { settleSuperseded(request, claim) }
            ActionResult.Failed -> onMain { settleFailed(kind, request, claim) }
        }
    }

    // Commit local truth FIRST, then tell the watch. If the OS kills us between
    // the two, the watch is simply not told (list sync is off by default, so
    // nothing repairs it later); the phone DB can't recover its own
    // inconsistent state, so it gets priority.
    // The occurrence check happens INSIDE the mutex: a takeover that finalized
    // this occurrence first wins, and this action is abandoned untouched.
    // reason: the commit touches Room, DataStore-backed repository code, the
    // BFU file cache and AlarmManager; any failure must become a reportable
    // Failed result (ok:false / ActionFailed), never an uncaught exception.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun commitAction(
        kind: RingActionKind,
        request: RingActionRequest,
        claim: ActionClaim,
    ): ActionResult = try {
        mutexFor(claim.occurrence.alarmId).withLock {
            if (!onMain { currentRing().isOpen(claim.occurrence) }) {
                ActionResult.Superseded
            } else {
                val committed = commitRow(kind, request)
                onMain { markCommitted(kind, request, claim, committed) }
                committed
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "${kind.wire} failed id=${request.alarmId} firedAt=${claim.occurrence.firedAtEpoch}", e)
        ActionResult.Failed
    }

    private suspend fun commitRow(kind: RingActionKind, request: RingActionRequest): ActionResult.Committed =
        when (kind) {
            RingActionKind.DISMISS -> {
                commitDismiss(request.alarmId, request.fromPeer)
                ActionResult.Committed(RingOutcome.DISMISSED, null)
            }
            RingActionKind.SNOOZE -> commitSnooze(request)
        }

    private suspend fun commitDismiss(alarmId: Long, fromPeer: Boolean) {
        if (!isUserUnlocked) {
            check(bfuCache.markDismissed(alarmId)) {
                "failed to persist pre-unlock dismissal id=$alarmId"
            }
            return
        }
        val alarm = repository.getById(alarmId)
        // The mutation that IS the commit comes first and alone, so a later
        // failure can never leave a change behind an ok:false.
        when (dismissAction(alarm)) {
            DismissAction.KEEP -> if (alarm != null) repository.resetSnoozeCounter(alarmId)
            DismissAction.DISABLE -> {
                if (fromPeer) repository.setEnabledLocalOnly(alarmId, false)
                else repository.setEnabled(alarmId, false)
                bestEffort("reset snooze counter id=$alarmId") { repository.resetSnoozeCounter(alarmId) }
            }
            DismissAction.DELETE -> repository.delete(alarmId)
        }
    }

    private suspend fun commitSnooze(request: RingActionRequest): ActionResult.Committed {
        val alarmId = request.alarmId
        val row = if (isUserUnlocked) {
            repository.getById(alarmId)
        } else {
            bfuCache.getAll().firstOrNull { it.id == alarmId }
        }
        val alarm = row ?: error("alarm row missing during snooze")

        if (!alarm.isSnoozeEnabled) {
            if (request.fromPeer) error("watch requested snooze for a snooze-disabled alarm")
            Log.i(TAG, "snooze id=$alarmId — snooze disabled, applying dismiss policy")
            commitDismiss(alarmId, fromPeer = false)
            return ActionResult.Committed(RingOutcome.DISMISSED, null)
        }
        // AlarmSchedulerImpl swallows the SecurityException of setAlarmClock, so
        // a snooze that cannot be armed would otherwise look committed (ok:true)
        // and never ring again. Refuse BEFORE any mutation; the ring continues.
        check(scheduler.canScheduleExact()) { "exact alarms not permitted; snooze id=$alarmId cannot be armed" }
        val trigger = if (request.fromPeer && request.rescheduleEpoch > 0L) {
            request.rescheduleEpoch
        } else {
            System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
        }
        if (isUserUnlocked) {
            repository.snoozeAt(alarmId, trigger, fromPeer = request.fromPeer)
                ?: error("snooze transaction rejected for id=$alarmId")
        } else {
            commitPreUnlockSnooze(alarm, trigger)
        }
        return ActionResult.Committed(RingOutcome.SNOOZED, trigger)
    }

    // Pre-unlock: Room is locked. The BFU cache records the snooze trigger and
    // the pending counter bump in ONE write; only after that succeeds is the
    // alarm armed, so a failed save never leaves an armed alarm with no record.
    // If arming fails the record is rolled back.
    private suspend fun commitPreUnlockSnooze(alarm: Alarm, trigger: Long) {
        check(bfuCache.recordPreUnlockSnooze(alarm.id, trigger)) {
            "failed to persist BFU snooze id=${alarm.id} trigger=$trigger"
        }
        // scheduleAtChecked reports false when AlarmManager refused the
        // alarm (SecurityException), which scheduleAt swallows.
        val armed = runCatching { scheduler.scheduleAtChecked(alarm, trigger) }
        if (armed.getOrDefault(false)) return
        Log.e(TAG, "pre-unlock snooze arming failed id=${alarm.id}; rolling back", armed.exceptionOrNull())
        // Restores the previous trigger and removes exactly the one bump this
        // snooze added; earlier successful snoozes keep their counts.
        bfuCache.undoPreUnlockSnooze(alarm.id, alarm.snoozedUntilEpoch)
        error("pre-unlock snooze could not be armed id=${alarm.id}")
    }

    // Main thread, inside the mutex, right after the row was written. Nothing
    // here may throw: a committed action must always reply ok:true, so a
    // failure after this point can only be logged.
    //
    // A takeover can run on the main thread between commitAction's open check
    // and this hop. The commit is then still recorded (outcome, replay) and
    // still answered ok:true, but it must not touch the ring state, timers or
    // player: those now belong to the NEWER occurrence.
    // reason: see above — a throw would turn a committed action into ok:false.
    @Suppress("TooGenericExceptionCaught")
    private fun markCommitted(
        kind: RingActionKind,
        request: RingActionRequest,
        claim: ActionClaim,
        committed: ActionResult.Committed,
    ) {
        val occurrence = claim.occurrence
        peerState.recordSuccess(occurrence, committed.outcome, claim.requestKey)
        val stillCurrent = matchesCurrentOccurrence(occurrence.alarmId, occurrence.firedAtEpoch)
        try {
            if (stillCurrent) {
                ringEnded = true
                cancelRingTimers()
                stopPlayback()
            }
            if (request.fromPeer) finishPeerCommit(kind, request, occurrence, stillCurrent)
        } catch (e: Exception) {
            Log.e(TAG, "post-commit step failed id=${occurrence.alarmId} (action stays committed)", e)
        }
    }

    // Watch-originated commit. Occurrence-aware: audio is already off, keep the
    // service + Activity for the grace window (only if this occurrence is still
    // the current one), reply ok:true either way. Legacy: HEAD behaviour, tear
    // down at once (current ring only) and never reply.
    private fun finishPeerCommit(
        kind: RingActionKind,
        request: RingActionRequest,
        occurrence: OccurrenceKey,
        stillCurrent: Boolean,
    ) {
        if (!request.isOccurrenceAware) {
            if (stillCurrent) stopForegroundAndSelf(occurrence.alarmId, occurrence.firedAtEpoch)
            return
        }
        if (stillCurrent) {
            RingEndedSignal.emitWatchActionCommitted(occurrence.alarmId, occurrence.firedAtEpoch)
            scheduleGraceTeardown(occurrence)
        }
        replyPeerAction(kind, request, ok = true)
    }

    // The takeover finalized this occurrence before the commit could run.
    private fun settleSuperseded(request: RingActionRequest, claim: ActionClaim) {
        peerState.release(claim.occurrence)
        Log.i(TAG, "action abandoned id=${request.alarmId} firedAt=${claim.occurrence.firedAtEpoch} — superseded")
        if (request.fromPeer) replyPeerEnded(request, claim.occurrence)
    }

    // Nothing was committed. While the occurrence is still current the ring
    // carries on (watch: ok:false; phone: ActionFailed so the buttons return
    // and auto-stop is guaranteed). If it ended meanwhile, answer as ended.
    private fun settleFailed(kind: RingActionKind, request: RingActionRequest, claim: ActionClaim) {
        val occurrence = claim.occurrence
        peerState.release(occurrence)
        val stillOpen = currentRing().isOpen(occurrence)
        if (stillOpen) ringActionStarted = false
        when {
            request.fromPeer && stillOpen -> {
                replyPeerAction(kind, request, ok = false)
                onActionFailedRingContinues(request, occurrence)
            }
            request.fromPeer -> replyPeerEnded(request, occurrence)
            stillOpen -> onActionFailedRingContinues(request, occurrence)
        }
    }

    // The ring carries on after a failed action. Tell the Activity (a phone tap
    // that was ignored while a WATCH action was in flight leaves it on
    // "waiting for watch"). No timer needs arming here: an open ring always has
    // an auto-stop armed. takeOverService arms the 5-minute one; when it fires
    // it re-arms through armAutoStopWatchdog (claimed) or retryAutoStop (an
    // action was in flight), or gives up and ends the ring.
    private fun onActionFailedRingContinues(request: RingActionRequest, occurrence: OccurrenceKey) {
        RingEndedSignal.emitActionFailed(occurrence.alarmId, occurrence.firedAtEpoch)
        // The auto-stop's own save failed fast and its watchdog is already
        // armed for the next attempt; only the last attempt ends here.
        if (request.autoStop && autoStopFinalAttempt) giveUpAutoStop(occurrence)
    }

    // Armed when an auto-stop claims its occurrence, BEFORE the save. Next
    // attempt on the 30/60/120 s backoff; for the last attempt a give-up
    // deadline instead. A committed action cancels it through
    // cancelRingTimers (it is the autoStopRunnable).
    private fun armAutoStopWatchdog(occurrence: OccurrenceKey) {
        if (autoStopRetries < AUTO_STOP_MAX_RETRIES) {
            autoStopRetries++
            val delayMs = AUTO_STOP_RETRY_MS shl (autoStopRetries - 1)
            scheduleAutoStop(occurrence.alarmId, occurrence.firedAtEpoch, delayMs)
            return
        }
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        val giveUp = Runnable {
            autoStopRunnable = null
            if (isCurrentRing(occurrence.alarmId, occurrence.firedAtEpoch)) giveUpAutoStop(occurrence)
        }
        autoStopRunnable = giveUp
        autoStopFinalAttempt = true
        mainHandler.postDelayed(giveUp, AUTO_STOP_RETRY_MS shl (AUTO_STOP_MAX_RETRIES - 1))
    }

    private fun retryAutoStop(occurrence: OccurrenceKey) {
        if (autoStopRetries >= AUTO_STOP_MAX_RETRIES) {
            giveUpAutoStop(occurrence)
            return
        }
        autoStopRetries++
        val delayMs = AUTO_STOP_RETRY_MS shl (autoStopRetries - 1)
        Log.w(TAG, "auto-stop could not be saved id=${occurrence.alarmId}; retry $autoStopRetries in ${delayMs}ms")
        scheduleAutoStop(occurrence.alarmId, occurrence.firedAtEpoch, delayMs)
    }

    // The save keeps failing. A ring that can never stop is worse than an
    // unsaved dismiss, so end it WITHOUT persistence and say so loudly.
    private fun giveUpAutoStop(occurrence: OccurrenceKey) {
        Log.e(
            TAG,
            "auto-stop gave up id=${occurrence.alarmId} firedAt=${occurrence.firedAtEpoch}: " +
                "dismiss could NOT be saved after $AUTO_STOP_MAX_RETRIES retries; ending ring WITHOUT saving",
        )
        peerState.recordOutcome(occurrence, RingOutcome.DISMISSED)
        // A stalled save may still hold the claim; release it so a watch retry
        // in the grace window gets the peer-ended reply, not silence.
        peerState.release(occurrence)
        ringEnded = true
        cancelRingTimers()
        stopPlayback()
        if (watchMayBeRinging(occurrence, ringStartedElapsedMs)) {
            launchDetached("auto-stop dismiss send id=${occurrence.alarmId}") { sendEndedToWatch(occurrence, null) }
        }
        scheduleGraceTeardown(occurrence)
    }

    // Phone-local action committed: tell the watch and wait for its ack while
    // AlarmActivity stays up ("waiting for watch"), then tear down. Audio and
    // vibration were silenced at the tap.
    private suspend fun finishLocalAction(claim: ActionClaim, committed: ActionResult.Committed) {
        val occurrence = claim.occurrence
        try {
            // A takeover can have happened since the tap: then a newer ring's
            // delivered alarm_fired replaces this one on the watch and an end
            // message here would stop the new ring on a released watch app.
            // The bridge's own re-send rounds inside the broadcast are not
            // gated here, only the decision to start it.
            val tellWatch = onMain {
                watchMayBeRinging(occurrence, claim.ringStartedMs) && !newerFireReachedWatch(occurrence)
            }
            if (tellWatch) broadcastToWatch(occurrence, committed)
        } finally {
            onMain { stopForegroundAndSelf(occurrence.alarmId, occurrence.firedAtEpoch) }
        }
    }

    private suspend fun broadcastToWatch(occurrence: OccurrenceKey, committed: ActionResult.Committed) {
        val id = occurrence.alarmId
        val firedAt = occurrence.firedAtEpoch
        val unlocked = isUserUnlocked
        val trigger = committed.triggerEpoch
        if (committed.outcome == RingOutcome.SNOOZED && trigger != null) {
            if (unlocked) {
                val acked = wearBridge.sendAlarmSnoozedAwaiting(id, trigger, BROADCAST_AWAIT_MS, firedAt = firedAt)
                Log.i(TAG, "snooze broadcast id=$id acked=$acked trigger=$trigger")
            } else {
                val delivered = withTimeoutOrNull(PRE_UNLOCK_SEND_TIMEOUT_MS) {
                    wearBridge.sendAlarmSnoozed(id, trigger, firedAt = firedAt)
                } ?: false
                Log.i(TAG, "snooze id=$id pre-unlock delivered=$delivered trigger=$trigger")
            }
        } else if (unlocked) {
            val acked = wearBridge.sendAlarmDismissedAwaiting(id, BROADCAST_AWAIT_MS, firedAt = firedAt)
            Log.i(TAG, "dismiss broadcast id=$id acked=$acked")
        } else {
            val delivered = withTimeoutOrNull(PRE_UNLOCK_SEND_TIMEOUT_MS) {
                wearBridge.sendAlarmDismissed(id, firedAt = firedAt)
            } ?: false
            Log.i(TAG, "dismiss id=$id pre-unlock send delivered=$delivered")
        }
    }

    // ---------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------

    // Main thread only. Occurrence-checked: a teardown that belongs to an
    // earlier ring is ignored, so it can never stop a newer alarm's service.
    private fun stopForegroundAndSelf(
        alarmId: Long = currentAlarmId,
        firedAtEpoch: Long = currentFiredAtEpoch,
    ) {
        if (alarmId >= 0L && !matchesCurrentOccurrence(alarmId, firedAtEpoch)) {
            Log.i(TAG, "stale teardown ignored id=$alarmId firedAt=$firedAtEpoch")
            return
        }
        stopCurrentRingResources()
        // Signal the AlarmActivity to finish itself. Critical for the
        // peer-driven path (watch dismiss/snooze): without this, the
        // service stops but the full-screen Activity stays on the phone
        // until the user manually dismisses it. Emit BEFORE stopForeground
        // so the Activity has the signal in flight as it tears down.
        RingEndedSignal.emitRingEnded(alarmId, firedAtEpoch)
        RingEndedSignal.clearCurrent(alarmId, firedAtEpoch)
        currentAlarmId = -1L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    // onDestroy: `serviceScope.cancel()` is non-blocking. If the OS reaps the
    // service while a handler is mid-DB-write (Room transactions usually <10ms
    // but tombstone+broadcast chains can take longer), the write may be
    // truncated. Trade-off accepted: blocking onDestroy violates the Service
    // lifecycle contract; non-blocking is the correct call. Critical writes
    // are committed BEFORE the broadcast (see commitAction ordering) so the
    // phone DB stays consistent even on mid-flight kill.
    // The finalization of a superseded ring is the exception: it runs in
    // finalizerScope, which is NOT cancelled here, so an older alarm's
    // one-shot disable / self-destruct delete completes even when the newer
    // ring ends at once. `destroyed` keeps those jobs away from ring state.
    override fun onDestroy() {
        destroyed = true
        serviceScope.cancel()
        ringJob?.cancel()
        ringJob = null
        mainHandler.removeCallbacksAndMessages(null)
        stopCurrentRingResources()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "com.kirkouski.gtwake.companion.ACTION_RING"
        const val ACTION_DISMISS = "com.kirkouski.gtwake.companion.ACTION_DISMISS"
        const val ACTION_SNOOZE = "com.kirkouski.gtwake.companion.ACTION_SNOOZE"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_FROM_PEER = "from_peer"
        const val EXTRA_RESCHEDULE_EPOCH = "reschedule_epoch"
        const val EXTRA_FIRED_AT = "fired_at"
        const val EXTRA_REQUEST_STAMP = "request_stamp"
        private const val AUTO_STOP_MS = 5L * 60_000L

        // When the auto-stop's own dismiss cannot be saved: retry after 30 s,
        // then 60 s, then 120 s; after that end the ring without saving.
        private const val AUTO_STOP_RETRY_MS = 30_000L
        private const val AUTO_STOP_MAX_RETRIES = 3

        // How long the foreground service and AlarmActivity outlive a committed
        // watch action. Long enough for the watch's duplicate request (about
        // 2.5 s after the first) and one retry to be answered ok:true.
        private const val GRACE_MS = 6_000L

        // An idle service that only answered a request waits this long before
        // stopping, so the bridge's bounded (about 4 s) reply can leave.
        private const val REPLY_FLUSH_MS = 4_500L

        // The watch is told about a superseded ring in the background; give up
        // after this long.
        private const val SUPERSEDED_SEND_TIMEOUT_MS = 5_000L

        // The old ring's dismiss-policy commit runs outside serviceScope; this
        // bound keeps a stalled save from holding that alarm's mutex forever.
        private const val FINALIZE_TIMEOUT_MS = 30_000L

        // One bounded wait of onMainWhileAlive for its block on the main thread.
        private const val MAIN_WHILE_ALIVE_WAIT_MS = 1_000L

        // Grace before auditing whether the ring UI appeared. Must outlast the
        // window-transition + Activity onCreate that a successful FSI still has
        // to finish after audio starts, or a healthy ring reports a false
        // NEVER_LAUNCHED. 2 s is comfortably past the ~200 ms observed on an
        // API 36 emulator while staying well inside the 5 min auto-stop.
        private const val RING_UI_AUDIT_DELAY_MS = 2_000L
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

        // Cold process: how long ring start waits for the P2P receiver
        // registration before deciding whether to advertise supportsActionAck.
        private const val RECEIVE_READY_WAIT_MS = 1_500L

        // How long the old ring's end message waits to learn whether the NEW
        // ring's alarm_fired reached the watch: the pre-arm budget, the cold
        // process receiver wait, and a margin for the settings read.
        private const val SUPERSEDED_FIRE_WAIT_MS = PREARM_TIMEOUT_MS + RECEIVE_READY_WAIT_MS + 2_000L

        // Background re-send of alarm_fired after the pre-arm times out (see
        // startWatchCatchUp). The window covers slow cold starts without
        // making the watch start buzzing minutes late; each attempt gets the
        // same wake budget as a manual sync.
        private const val WATCH_CATCHUP_WINDOW_MS = 90_000L
        private const val WATCH_CATCHUP_ATTEMPT_MS = 25_000L
        private const val WATCH_CATCHUP_GAP_MS = 2_000L

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
        // bypasses the FGS BAL block (see launchRingActivity). Distinct
        // from AlarmNotifications.FULL_SCREEN_BIT (0x08000000) so the two
        // PIs don't collide on FLAG_UPDATE_CURRENT.
        private const val DIRECT_LAUNCH_REQUEST_CODE_BIT = 0x04000000

        /**
         * Pure helper (unit-tested): whether the service must send the backup
         * launch of AlarmActivity. Only an Activity that is visible AND shows
         * this exact occurrence makes the launch redundant; relaunching it
         * would destroy and recreate it (second screen sliding in).
         */
        internal fun needsBackupLaunch(
            visible: Boolean,
            shown: Pair<Long, Long>?,
            alarmId: Long,
            firedAtEpoch: Long,
        ): Boolean = !(visible && shown == Pair(alarmId, firedAtEpoch))

        // Pure helper extracted for unit testing. Decides what to do to the
        // alarm row when the user dismisses (or the ring cycle auto-stops).
        // Three outcomes:
        //   KEEP    — recurring alarm, stays armed; the system will fire
        //             again on the next day-of-week match.
        //   DISABLE — legacy one-shot behavior: clear `enabled` so the row
        //             stays in the list as a re-arm-able template.
        //   DELETE  — self-destruct: row vanishes (tombstone + propagation
        //             to watch via AlarmRepository.delete). Used for alarms
        //             with `selfDestruct` set — since 1.0.8 that is the
        //             default for relative (timers) only; one-shot absolute
        //             defaults to KEEPING the row, i.e. DISABLE.
        //
        // Shared by all three dismiss routes: the live ring path, the BFU
        // pre-unlock drain (AlarmRepository.applyPendingDismissals) and
        // missed-during-downtime (AlarmRepository.handleMissedDuringDowntime).
        // Keep it the single decision point — the missed path used to branch
        // separately and silently ignored the user's per-alarm setting.
        fun dismissAction(alarm: Alarm?): DismissAction = when {
            alarm == null || !alarm.enabled -> DismissAction.KEEP
            alarm.selfDestruct -> DismissAction.DELETE
            alarm.daysOfWeek == 0 -> DismissAction.DISABLE
            else -> DismissAction.KEEP
        }
    }

    enum class DismissAction { KEEP, DISABLE, DELETE }
}
