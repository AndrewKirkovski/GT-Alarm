package com.kirkouski.gtalarm.ring

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.data.AlarmRepository
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.util.TimeFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {

    @Inject lateinit var repository: AlarmRepository

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
            LaunchedEffect(alarmId) {
                alarm = repository.getById(alarmId)
            }
            AlarmRingScreen(
                alarm = alarm,
                onDismiss = { sendAction(AlarmRingService.ACTION_DISMISS, alarmId) },
                onSnooze = { sendAction(AlarmRingService.ACTION_SNOOZE, alarmId) },
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun sendAction(action: String, alarmId: Long) {
        Log.i(TAG, "user tap action=$action id=$alarmId")
        val intent = Intent(this, AlarmRingService::class.java).apply {
            this.action = action
            putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
        }
        lifecycleScope.launch { startService(intent) }
        finishAndRemoveTask()
    }

    private companion object {
        const val TAG = "AlarmActivity"
    }
}

@Composable
private fun AlarmRingScreen(
    alarm: Alarm?,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val ctx = LocalContext.current
            val time = alarm?.let { TimeFormatter.formatHourMinute(ctx, it.hour, it.minute) } ?: "--:--"
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
