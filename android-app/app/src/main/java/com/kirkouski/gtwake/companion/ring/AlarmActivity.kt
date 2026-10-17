package com.kirkouski.gtwake.companion.ring

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.os.UserManager
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.data.bfu.BfuAlarmCache
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.ui.components.PhoneRingOverlay
import com.kirkouski.gtwake.companion.ui.edit.rememberBackgroundBitmap
import com.kirkouski.gtwake.companion.util.TimeFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var bfuCache: BfuAlarmCache

    private val isUserUnlocked: Boolean
        get() = getSystemService(UserManager::class.java)?.isUserUnlocked == true

    // Flips true once the user taps Dismiss/Snooze and the service begins
    // the watch round-trip. Drives the "waiting for watch" UI. Read inside
    // the setContent composable; set on the main thread from sendAction.
    private val awaitingWatchState = mutableStateOf(false)
    private var watchWaitTimeoutArmed = false
    private var watchWaitJob: kotlinx.coroutines.Job? = null
    private val activeAlarmId = mutableLongStateOf(-1L)
    private val activeFiredAtEpoch = mutableLongStateOf(-1L)

    // Elapsed-realtime of the latest Dismiss/Snooze tap (0 = none). A replayed
    // action failure older than this is ignored.
    private var lastTapElapsedMs = 0L

    // Whether the loaded alarm shows a Snooze button; Back uses it to choose
    // between Snooze and Dismiss. Updated where the alarm row is loaded. True
    // until then: a Back pressed before the row loads must not dismiss, and the
    // service turns a snooze of a snooze-disabled alarm into its dismiss policy.
    private var snoozeAvailable = true

    // Back must not close the screen while the alarm rings: the watch's
    // Stop/Snooze reach the phone only while this Activity is alive. Back sends
    // the same action as the on-screen buttons. An object expression, so it adds
    // no function to the class (detekt TooManyFunctions is at its limit).
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val id = activeAlarmId.longValue
            val firedAt = activeFiredAtEpoch.longValue
            when (RingEndedSignal.backChoice(id >= 0L, awaitingWatchState.value, snoozeAvailable)) {
                RingEndedSignal.BackChoice.SNOOZE -> sendAction(AlarmRingService.ACTION_SNOOZE, id, firedAt)
                RingEndedSignal.BackChoice.DISMISS -> sendAction(AlarmRingService.ACTION_DISMISS, id, firedAt)
                RingEndedSignal.BackChoice.FINISH -> finishAndRemoveTask()
                RingEndedSignal.BackChoice.IGNORE -> Unit
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stamp BEFORE any work that could throw — the audit needs to know we
        // got here at all, even if setup later fails.
        lastCreatedElapsedMs.set(android.os.SystemClock.elapsedRealtime())
        configureWindow()

        val alarmId = readOccurrence(intent)
        val km = getSystemService(KeyguardManager::class.java)
        Log.i(
            TAG,
            "onCreate id=$alarmId locked=${km?.isKeyguardLocked} secure=${km?.isKeyguardSecure}",
        )

        // Close this Activity whenever the ringing service stops, regardless
        // of source (phone tap, watch peer-end, auto-stop, self-destruct
        // DELETE). The replayCache means we'd also see prior emissions —
        // Match the exact ring occurrence so a late teardown cannot close a
        // newer alarm shown by this single-instance Activity.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Reconcile with the retained current ring BEFORE any replayed
                // end event can be acted on: a stopped screen showing A must
                // switch to B first, not finish on A's replayed end.
                followCurrentRing(RingEndedSignal.current.value)
                launch { collectRingEnded() }
                launch { collectWatchActionCommitted() }
                launch { collectActionFailures() }
                launch { RingEndedSignal.current.collect { current -> followCurrentRing(current) } }
            }
        }

        onBackPressedDispatcher.addCallback(this, backCallback)

        setContent {
            var alarm by remember { mutableStateOf<Alarm?>(null) }
            var defaultBgUri by remember { mutableStateOf<String?>(null) }
            val currentAlarmId = activeAlarmId.longValue
            val currentFiredAt = activeFiredAtEpoch.longValue
            LaunchedEffect(currentAlarmId, currentFiredAt) {
                // Pre-unlock the Activity is brought up by FSI before the
                // user unlocks — Room and DataStore both live in credential-
                // encrypted storage and would throw on access. Branch on
                // UserManager.isUserUnlocked: pre-unlock read alarm fields
                // from BfuAlarmCache (the same mirror AlarmRingService uses)
                // and leave defaultBgUri null (SettingsStore is DataStore-
                // backed; same CE-storage constraint). The ring screen
                // renders the alarm time + (BFU-omitted) blank label and
                // the default black background — degraded but functional.
                if (isUserUnlocked) {
                    val loadedAlarm = readOrNull("alarm row") { repository.getById(currentAlarmId) }
                    val loadedBackground = readOrNull("default background") {
                        settingsStore.defaultPhoneBackgroundUri.first()
                    }
                    if (activeAlarmId.longValue == currentAlarmId && activeFiredAtEpoch.longValue == currentFiredAt) {
                        alarm = loadedAlarm
                        snoozeAvailable = loadedAlarm?.isSnoozeEnabled == true
                        defaultBgUri = loadedBackground
                    }
                } else {
                    val loadedAlarm = bfuCache.getAll().firstOrNull { it.id == currentAlarmId }
                    if (activeAlarmId.longValue == currentAlarmId && activeFiredAtEpoch.longValue == currentFiredAt) {
                        alarm = loadedAlarm
                        snoozeAvailable = loadedAlarm?.isSnoozeEnabled == true
                        defaultBgUri = null
                    }
                }
            }
            // Per-alarm URI wins over the user-default. Null on both means
            // "render the existing black background" — preserves legacy
            // behavior for users who haven't picked an image.
            val effectiveBgUri = alarm?.backgroundImageUri ?: defaultBgUri
            // Hide the Snooze button entirely when the alarm has snooze
            // disabled (snoozeMinutes == 0). Passing null is the explicit
            // "no button" signal so AlarmRingScreen can drop the slot
            // without secondary state.
            val snoozeAction = if (alarm?.isSnoozeEnabled == true) {
                { sendAction(AlarmRingService.ACTION_SNOOZE, currentAlarmId, currentFiredAt) }
            } else {
                null
            }
            AlarmRingScreen(
                alarm = alarm,
                backgroundImageUri = effectiveBgUri,
                awaitingWatch = awaitingWatchState.value,
                onDismiss = { sendAction(AlarmRingService.ACTION_DISMISS, currentAlarmId, currentFiredAt) },
                onSnooze = snoozeAction,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        leaveWaitingState()
        readOccurrence(intent)
        Log.i(TAG, "onNewIntent id=${activeAlarmId.longValue} firedAt=${activeFiredAtEpoch.longValue}")
    }

    private fun readOccurrence(intent: Intent): Long {
        val previous = currentOccurrence()
        activeAlarmId.longValue = intent.getLongExtra(AlarmRingService.EXTRA_ALARM_ID, -1L)
        activeFiredAtEpoch.longValue = intent.getLongExtra(AlarmRingService.EXTRA_FIRED_AT, -1L)
        shownOccurrence = currentOccurrence()
        // A different occurrence: its row is not loaded yet, so drop the previous
        // alarm's flag. True is the safe default (the service turns a snooze of a
        // snooze-disabled alarm into its dismiss policy). The same occurrence
        // keeps its loaded value (the load effect does not re-run for it).
        if (shownOccurrence != previous) snoozeAvailable = true
        return activeAlarmId.longValue
    }

    private fun currentOccurrence(): Pair<Long, Long> =
        activeAlarmId.longValue to activeFiredAtEpoch.longValue

    private fun isCurrentOccurrence(event: RingEndedSignal.Event): Boolean =
        event.alarmId == activeAlarmId.longValue && event.firedAtEpoch == activeFiredAtEpoch.longValue

    private suspend fun collectRingEnded() {
        RingEndedSignal.events.collect { event ->
            // An end event for an occurrence the service no longer rings (it
            // names a different live one) must not close this screen.
            val live = RingEndedSignal.current.value
            if (!isCurrentOccurrence(event) || (live != null && !isCurrentOccurrence(live))) return@collect
            Log.i(
                TAG,
                "ring-ended signal received id=${event.alarmId} firedAt=${event.firedAtEpoch} — finishing",
            )
            finishAndRemoveTask()
        }
    }

    // A watch dismiss/snooze was committed. The service keeps this Activity
    // up for its grace window (a duplicate request must still find an Activity
    // in the task), so show the same state as after a phone tap.
    private suspend fun collectWatchActionCommitted() {
        RingEndedSignal.watchActionCommitted.collect { event ->
            if (!isCurrentOccurrence(event)) return@collect
            Log.i(TAG, "watch action committed id=${event.alarmId} firedAt=${event.firedAtEpoch}")
            awaitingWatchState.value = true
            armWatchWaitTimeout()
        }
    }

    // The service could not save the dismiss/snooze this screen asked for and
    // the ring continues: leave "waiting for watch" so the buttons are usable.
    private suspend fun collectActionFailures() {
        RingEndedSignal.actionFailures.collect { event ->
            // Only while waiting: the flow replays its last event, and one this
            // screen already handled must not undo a newer tap.
            // A failure older than the latest tap belongs to an earlier tap.
            if (!isCurrentOccurrence(event) || !awaitingWatchState.value) return@collect
            if (RingEndedSignal.isStaleFailure(event.emittedAtElapsedMs, lastTapElapsedMs)) return@collect
            Log.w(TAG, "action failed id=${event.alarmId} firedAt=${event.firedAtEpoch} — buttons restored")
            leaveWaitingState()
        }
    }

    // The service rings a different occurrence than this (possibly stopped)
    // screen shows. It may have missed several takeovers, so follow the
    // retained current occurrence instead of replayed events: switch to it,
    // reset the waiting state, and rewrite the intent so a recreate does not
    // resurrect the old occurrence. The alarm reloads through the
    // LaunchedEffect keys.
    //
    // No current occurrence means nothing rings any more: the service sets it
    // before it posts the full-screen intent and clears it on teardown, in the
    // same process. Finish then, even if no matching end event is replayed: a
    // stopped screen showing A can miss A's end AND a later ring B (B's end
    // replaces A's in the replay cache), and would otherwise stay up on A.
    private fun followCurrentRing(current: RingEndedSignal.Event?) {
        if (current == null) {
            Log.i(TAG, "no ring is current — finishing")
            finishAndRemoveTask()
            return
        }
        if ((current.alarmId to current.firedAtEpoch) == currentOccurrence()) return
        Log.i(TAG, "following current ring id=${current.alarmId} firedAt=${current.firedAtEpoch}")
        leaveWaitingState()
        intent.putExtra(AlarmRingService.EXTRA_ALARM_ID, current.alarmId)
        intent.putExtra(AlarmRingService.EXTRA_FIRED_AT, current.firedAtEpoch)
        activeAlarmId.longValue = current.alarmId
        activeFiredAtEpoch.longValue = current.firedAtEpoch
        shownOccurrence = currentOccurrence()
        snoozeAvailable = true // see readOccurrence
    }

    private fun leaveWaitingState() {
        watchWaitJob?.cancel()
        watchWaitJob = null
        watchWaitTimeoutArmed = false
        awaitingWatchState.value = false
    }

    private fun configureWindow() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // The deprecated 4-flag quartet is REQUIRED on vendor skins (Samsung
        // One UI, Huawei EMUI) even when the modern setShowWhenLocked /
        // setTurnScreenOn APIs are also called. Documented in
        // github.com/yuriykulikov/AlarmClock issue #360 + the comment block
        // in AlarmAlertFullScreen.kt — without these, Samsung silently
        // rejects the screen wake despite the modern APIs returning true.
        // Confirmed identical pattern in FossifyOrg/Clock AlarmActivity.kt
        // L213-224 (the only Android-14-targeting reference app).
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
    }

    private fun sendAction(action: String, alarmId: Long, firedAtEpoch: Long) {
        Log.i(TAG, "user tap action=$action id=$alarmId firedAt=$firedAtEpoch")
        lastTapElapsedMs = android.os.SystemClock.elapsedRealtime()
        val intent = Intent(this, AlarmRingService::class.java).apply {
            this.action = action
            putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
            if (firedAtEpoch > 0L) putExtra(AlarmRingService.EXTRA_FIRED_AT, firedAtEpoch)
        }
        // runCatching guards the Android 12+ ForegroundServiceStartNot-
        // AllowedException edge case where the Activity has slipped from
        // foreground (auto-stop runnable + keyguard reassertion mid-tap).
        val started = runCatching { startService(intent) }
            .onFailure {
                Log.w(TAG, "startService action=$action id=$alarmId failed: ${it::class.simpleName}: ${it.message}")
            }
            .isSuccess
        if (started) {
            // Deliberately do NOT finishAndRemoveTask() here. Huawei Wear
            // Engine P2P RECEIVE only works while an Activity is in the task.
            // The service is about to send alarm_dismissed/alarm_snoozed and
            // AWAIT the watch's ack — that ack can only be delivered while
            // this Activity is alive. Stay up, show the "waiting for watch"
            // state, and let RingEndedSignal (emitted when the service ends
            // the round-trip in stopForegroundAndSelf) close us.
            // armWatchWaitTimeout is the safety net if that signal never
            // arrives. NOT finishing here is load-bearing, not an oversight.
            awaitingWatchState.value = true
            armWatchWaitTimeout()
        } else {
            // Service never started → no round-trip, no RingEndedSignal →
            // finish now so the ring UI isn't stranded.
            finishAndRemoveTask()
        }
    }

    // Safety net: if the service never emits RingEndedSignal (crash / hang),
    // finish anyway after WATCH_WAIT_TIMEOUT_MS so the ring UI can't be
    // stranded on "waiting for watch" forever. lifecycleScope cancels this
    // coroutine if RingEndedSignal finishes us first.
    private fun armWatchWaitTimeout() {
        if (watchWaitTimeoutArmed) return
        watchWaitTimeoutArmed = true
        watchWaitJob = lifecycleScope.launch {
            delay(WATCH_WAIT_TIMEOUT_MS)
            Log.w(TAG, "watch round-trip timeout — finishing without RingEndedSignal")
            finishAndRemoveTask()
        }
    }

    override fun onStart() {
        super.onStart()
        shownOccurrence = currentOccurrence()
        isVisible.set(true)
    }

    override fun onStop() {
        super.onStop()
        // A relaunch (NEW_TASK | CLEAR_TASK) creates the new instance before
        // this one stops. If the new instance already published its
        // occurrence, this stop must not clear it or the visible flag.
        if (shownOccurrence == currentOccurrence()) {
            shownOccurrence = null
            isVisible.set(false)
        }
    }

    companion object {
        // Tracks whether an AlarmActivity instance is currently in the
        // foreground. With [shownOccurrence] it lets
        // AlarmRingService.startRingingAudioAndUi skip the backup PI.send()
        // when FSI already put THIS occurrence on screen. A relaunch is
        // NEW_TASK | CLEAR_TASK on a singleInstance Activity: the old instance
        // is destroyed and recreated (onNewIntent does not run), so a needless
        // relaunch shows as a second screen sliding in. A visible Activity
        // that shows a different occurrence still needs the relaunch.
        val isVisible: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * (alarmId, firedAtEpoch) of the occurrence the most recent
         * AlarmActivity instance shows, or null when none is showing. Set when
         * an intent is read and on start; cleared on stop only by the instance
         * that owns it. Read by the service on the main thread, like every
         * lifecycle callback here.
         */
        @Volatile
        var shownOccurrence: Pair<Long, Long>? = null

        /**
         * `SystemClock.elapsedRealtime()` of the most recent [onCreate], or 0
         * if this Activity has never been created in this process.
         *
         * Exists because [isVisible] cannot tell "the ring UI never appeared"
         * apart from "it appeared and was then covered" — both read false. That
         * distinction is the whole diagnosis when GT Wake loses the screen to a
         * competing full-screen intent, and until now we had no signal for it
         * at all: `PendingIntent.send()` does NOT throw on `BAL_BLOCK`, so the
         * `runCatching` around it reports success for a launch that never
         * happened, and SystemUI declines a full-screen intent silently
         * (`NO_FSI_EXPECTED_TO_HUN`) whenever the device is already interactive.
         *
         * Compared against the fire timestamp by
         * `AlarmRingService.scheduleRingUiAudit`. Monotonic, so it is immune to
         * wall-clock changes; process-scoped, which is all the audit needs.
         */
        val lastCreatedElapsedMs: java.util.concurrent.atomic.AtomicLong =
            java.util.concurrent.atomic.AtomicLong(0L)

        // Hard cap on the post-tap "waiting for watch" state. Must exceed
        // the service's BROADCAST_AWAIT_MS (12 s) plus margin so the normal
        // RingEndedSignal path wins; this only fires if the service hangs.
        private const val WATCH_WAIT_TIMEOUT_MS = 15_000L

        private const val TAG = "AlarmActivity"
    }
}

// The ring screen's own reads (alarm row, default background). This Activity
// shares the process with AlarmRingService, so an uncaught throw here would end
// the ring; a failed read shows the plain screen instead (time only, black
// background), and Back / the buttons still act on the current occurrence.
// reason: Room and DataStore surface IOException, CorruptionException, SQLite
// and migration failures with no common type.
@Suppress("TooGenericExceptionCaught")
private suspend fun <T> readOrNull(what: String, read: suspend () -> T?): T? = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("AlarmActivity", "ring screen: $what read failed, showing defaults", e)
    null
}

// Dimming overlay alpha — 0.45 chosen empirically so a bright lockscreen
// wallpaper reads as "your image" while the white 96sp time stays legible.
// Lower values washed out bright photos; higher made the photo
// unrecognizable.
private const val DIM_OVERLAY_ALPHA = 0.45f

// reason: single linear Box + Column with conditional time/label/button block;
// splitting into header/buttons composables would add state-routing
// boilerplate for one-call-site widgets.
@Suppress("LongMethod")
@Composable
private fun AlarmRingScreen(
    alarm: Alarm?,
    backgroundImageUri: String?,
    awaitingWatch: Boolean,
    onDismiss: () -> Unit,
    onSnooze: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // Full-screen background image with a fixed dimming overlay so the
        // big white time text stays readable on any photo. Cover mode only
        // (ContentScale.Crop) — the spec calls out no fit/contain options
        // because the ring screen is always full-bleed and aspect-mismatched
        // letterboxing would expose the black void behind the image.
        val bitmapState = rememberBackgroundBitmap(backgroundImageUri)
        val bg = bitmapState.value
        if (backgroundImageUri != null && bg != null) {
            Image(
                bitmap = bg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Dimming overlay. 0.45 alpha is the spec value — heavy enough
            // for white text on bright photos, light enough that the bg
            // is still recognizable as "your image", not just a tinted void.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = DIM_OVERLAY_ALPHA)),
            )
        }
        val ctx = LocalContext.current
        val time = alarm?.let {
            if (it.isRelative) {
                // Relative alarms store hour=7 minute=0 as defaults (the
                // fields are meaningless for them). The actual fire clock
                // time comes from computedFireEpoch (updatedAtEpoch +
                // relativeMinutes). Formatting that gives the wall-clock
                // hh:mm the user expects to see on the ring screen.
                TimeFormatter.formatTime(ctx, java.util.Date(it.computedFireEpoch()))
            } else {
                TimeFormatter.formatHourMinute(ctx, it.hour, it.minute)
            }
        } ?: "--:--"
        val label = alarm?.label?.takeIf { it.isNotBlank() }.orEmpty()
        PhoneRingOverlay(
            timeText = time,
            labelText = label,
            awaitingWatch = awaitingWatch,
            onDismiss = onDismiss,
            onSnooze = onSnooze,
        )
    }
}
