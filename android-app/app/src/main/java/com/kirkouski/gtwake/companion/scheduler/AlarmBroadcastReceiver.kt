package com.kirkouski.gtwake.companion.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kirkouski.gtwake.companion.ring.AlarmRingService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AlarmBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(AlarmSchedulerImpl.EXTRA_ALARM_ID, -1L)
        if (alarmId < 0) {
            Log.w(TAG, "fired with no alarm_id extra")
            return
        }
        Log.d(TAG, "fire received id=$alarmId")

        val ringIntent = Intent(context, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_RING
            putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
        }
        context.startForegroundService(ringIntent)
    }

    private companion object {
        const val TAG = "AlarmFire"
    }
}
