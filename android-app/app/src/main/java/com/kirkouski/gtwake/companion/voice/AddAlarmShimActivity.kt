package com.kirkouski.gtwake.companion.voice

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kirkouski.gtwake.companion.MainActivity
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.util.TimeFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

// reason: Invisible shim activity that handles `AlarmClock.ACTION_SET_ALARM`
// from Google Assistant ("Hey Google, set an alarm for 7am"). Routes the
// intent extras through IntentExtrasMapper, dedups against any matching
// existing alarm (re-enables instead of inserting a duplicate), saves via
// AlarmRepository, toasts the resolved time, finishes. Theme.NoDisplay
// keeps it from flashing a window.
@AndroidEntryPoint
class AddAlarmShimActivity : ComponentActivity() {

    @Inject lateinit var alarmRepository: AlarmRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "voice intent action=${intent.action} hasExtras=${intent.extras != null}")
        val parsed = IntentExtrasMapper.fromSetAlarmIntent(intent)
        if (parsed == null) {
            Log.w(TAG, "voice intent unparseable — routing to manual add")
            // No usable hour — bounce to the manual add screen so the user
            // can fill the time. Better than silently dropping the intent.
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_DEEP_LINK_SCREEN, MainActivity.SCREEN_ADD)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            finish()
            return
        }
        Log.i(TAG, "voice parsed h=${parsed.alarm.hour} m=${parsed.alarm.minute} dow=${parsed.alarm.daysOfWeek}")
        lifecycleScope.launch {
            runCatching { saveOrEnable(parsed.alarm) }
                .onSuccess { id ->
                    Log.i(TAG, "voice saved id=$id")
                    val display = TimeFormatter.formatHourMinute(
                        this@AddAlarmShimActivity,
                        parsed.alarm.hour,
                        parsed.alarm.minute,
                    )
                    Toast.makeText(
                        this@AddAlarmShimActivity,
                        getString(R.string.voice_alarm_set, display),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .onFailure { e ->
                    Log.w(TAG, "voice save failed: ${e.message}", e)
                    Toast.makeText(
                        this@AddAlarmShimActivity,
                        getString(R.string.voice_alarm_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            finish()
        }
    }

    private suspend fun saveOrEnable(alarm: Alarm): Long {
        val match = alarmRepository.getAll().firstOrNull {
            it.hour == alarm.hour && it.minute == alarm.minute && it.daysOfWeek == alarm.daysOfWeek
        }
        return if (match != null) {
            if (!match.enabled) alarmRepository.setEnabled(match.id, true)
            match.id
        } else {
            alarmRepository.save(alarm)
        }
    }

    private companion object {
        const val TAG = "VoiceShim"
    }
}
