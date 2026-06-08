package com.kirkouski.gtwake.companion.ring

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()

        val alarmId = intent.getLongExtra(AlarmRingService.EXTRA_ALARM_ID, -1L)
        val km = getSystemService(KeyguardManager::class.java)
        Log.i(
            TAG,
            "onCreate id=$alarmId locked=${km?.isKeyguardLocked} secure=${km?.isKeyguardSecure}",
        )

        // Close this Activity whenever the ringing service stops, regardless
        // of source (phone tap, watch peer-end, auto-stop, self-destruct
        // DELETE). The replayCache means we'd also see prior emissions —
        // filter against `createdAtEpoch` so a stale signal from an earlier
        // ring doesn't immediately finish a newly-started Activity.
        val createdAtEpoch = System.currentTimeMillis()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RingEndedSignal.events.collect { endedAt ->
                    if (endedAt < createdAtEpoch) return@collect
                    Log.i(TAG, "ring-ended signal received endedAt=$endedAt — finishing")
                    finishAndRemoveTask()
                }
            }
        }

        setContent {
            var alarm by remember { mutableStateOf<Alarm?>(null) }
            var defaultBgUri by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(alarmId) {
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
                    alarm = repository.getById(alarmId)
                    defaultBgUri = settingsStore.defaultPhoneBackgroundUri.first()
                } else {
                    alarm = bfuCache.getAll().firstOrNull { it.id == alarmId }
                    defaultBgUri = null
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
                { sendAction(AlarmRingService.ACTION_SNOOZE, alarmId) }
            } else {
                null
            }
            AlarmRingScreen(
                alarm = alarm,
                backgroundImageUri = effectiveBgUri,
                awaitingWatch = awaitingWatchState.value,
                onDismiss = { sendAction(AlarmRingService.ACTION_DISMISS, alarmId) },
                onSnooze = snoozeAction,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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

    private fun sendAction(action: String, alarmId: Long) {
        Log.i(TAG, "user tap action=$action id=$alarmId")
        val intent = Intent(this, AlarmRingService::class.java).apply {
            this.action = action
            putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
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
        lifecycleScope.launch {
            delay(WATCH_WAIT_TIMEOUT_MS)
            Log.w(TAG, "watch round-trip timeout — finishing without RingEndedSignal")
            finishAndRemoveTask()
        }
    }

    override fun onStart() {
        super.onStart()
        isVisible.set(true)
    }

    override fun onStop() {
        super.onStop()
        isVisible.set(false)
    }

    companion object {
        // Tracks whether an AlarmActivity instance is currently in the
        // foreground. Used by AlarmRingService.startRingingAudioAndUi to
        // skip the backup PI.send() when FSI already launched us — without
        // this gate, every successful FSI fire results in a second
        // Activity launch ~2.6 s later (after preArmWatch returns), which
        // animates a visible "second screen sliding in" over the first.
        val isVisible: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false)

        // Hard cap on the post-tap "waiting for watch" state. Must exceed
        // the service's BROADCAST_AWAIT_MS (12 s) plus margin so the normal
        // RingEndedSignal path wins; this only fires if the service hangs.
        private const val WATCH_WAIT_TIMEOUT_MS = 15_000L

        private const val TAG = "AlarmActivity"
    }
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
