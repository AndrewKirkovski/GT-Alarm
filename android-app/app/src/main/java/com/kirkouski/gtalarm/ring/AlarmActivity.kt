package com.kirkouski.gtalarm.ring

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.data.AlarmRepository
import com.kirkouski.gtalarm.data.SettingsStore
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.ui.edit.rememberBackgroundBitmap
import com.kirkouski.gtalarm.util.TimeFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var settingsStore: SettingsStore

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
                alarm = repository.getById(alarmId)
                // One-shot read of the SettingsStore default. The ring screen
                // is a moment-in-time view — re-collecting the Flow would
                // make the bg image flicker if the user opens settings on
                // another device mid-ring, which is the wrong UX.
                defaultBgUri = settingsStore.defaultPhoneBackgroundUri.first()
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
        // Synchronous startService — wrapping in lifecycleScope.launch raced
        // with finishAndRemoveTask: lifecycleScope cancels on Activity destroy,
        // so if the cancellation won the next-main-loop tick race the
        // startService never ran and the alarm kept ringing on the watch.
        // startService is non-suspending and safe to call from the main thread.
        startService(intent)
        finishAndRemoveTask()
    }

    private companion object {
        const val TAG = "AlarmActivity"
    }
}

// Dimming overlay alpha — 0.45 was chosen empirically so a bright lock-screen
// wallpaper still reads as "your image" while the white 96sp time stays
// legible. Lower values washed out under bright photos; higher made the
// photo unrecognizable. Declared above its usage in [AlarmRingScreen] so
// the kotlinc compile-debug path resolves it (kotlinc 2.3.21 has been
// observed dropping forward references to file-level private consts that
// live below the function under some module-cache configurations).
private const val DIM_OVERLAY_ALPHA = 0.45f

// reason: single linear Box + Column with conditional time/label/button block;
// splitting into header/buttons composables would add state-routing
// boilerplate for one-call-site widgets.
@Suppress("LongMethod")
@Composable
private fun AlarmRingScreen(
    alarm: Alarm?,
    backgroundImageUri: String?,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
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

            Text(
                text = time,
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
            )
            if (label.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 24.sp,
                )
            }
            Spacer(Modifier.height(64.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.action_dismiss),
                    fontSize = 18.sp,
                )
            }
            if (onSnooze != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.action_snooze),
                        color = Color.White,
                        fontSize = 18.sp,
                    )
                }
            }
        }
    }
}
