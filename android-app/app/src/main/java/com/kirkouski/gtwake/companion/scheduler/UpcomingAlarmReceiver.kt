package com.kirkouski.gtwake.companion.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shows the upcoming notice and handles Skip and Stop from the two status
 * notices. Separate from AlarmBroadcastReceiver, so nothing here shares a
 * PendingIntent with a ring. Not direct-boot aware. These actions send no end
 * message to the watch: off the ring screen, store watch 1.0.7 can throw on one.
 */
@AndroidEntryPoint
class UpcomingAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var statusNotifier: AlarmStatusNotifier
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmSchedulerImpl.EXTRA_ALARM_ID, -1L)
        if (id < 0L) return
        val pending = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                handle(intent.action, id)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // reason: a crash here would also kill a ring in the same process.
                Log.e(TAG, "action=${intent.action} id=$id failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(action: String?, id: Long) {
        when (action) {
            AlarmStatusNotifier.ACTION_SHOW -> {
                val alarm = repository.getById(id)?.takeIf { it.enabled && it.snoozedUntilEpoch == null }
                if (alarm != null) statusNotifier.showUpcoming(alarm, scheduler.nextTriggerMillis(alarm))
            }
            AlarmStatusNotifier.ACTION_SKIP -> repository.skipUpcoming(id)
            AlarmStatusNotifier.ACTION_STOP_SNOOZE -> repository.stopSnooze(id)
        }
    }

    private companion object {
        const val TAG = "UpcomingAlarmReceiver"
    }
}
