package com.kirkouski.gtalarm.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.NextTriggerCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmSchedulerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AlarmScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(alarm: Alarm) {
        if (!alarm.enabled) {
            cancel(alarm.id)
            return
        }
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm)
        scheduleAt(alarm, trigger)
    }

    override fun scheduleAt(alarm: Alarm, triggerAtMillis: Long) {
        val firePi = firePendingIntent(alarm.id) ?: run {
            Log.w(TAG, "could not create PendingIntent for alarm id=${alarm.id}")
            return
        }
        val showPi = showPendingIntent(alarm.id)
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showPi)
        try {
            alarmManager.setAlarmClock(info, firePi)
            Log.d(TAG, "scheduled alarm id=${alarm.id} trigger=$triggerAtMillis")
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException scheduling alarm id=${alarm.id}: ${se.message}")
        }
    }

    override fun cancel(alarmId: Long) {
        val pi = firePendingIntent(alarmId, flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
            Log.d(TAG, "cancelled alarm id=$alarmId")
        }
    }

    override fun rescheduleAll(alarms: List<Alarm>) {
        alarms.forEach { schedule(it) }
    }

    override fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    override fun nextTriggerMillis(alarm: Alarm): Long =
        NextTriggerCalculator.nextTriggerEpochMillis(alarm)

    private fun firePendingIntent(
        alarmId: Long,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    ): PendingIntent? {
        val intent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            flags,
        )
    }

    private fun showPendingIntent(alarmId: Long): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, "com.kirkouski.gtalarm.MainActivity")
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getActivity(
            context,
            (alarmId.toInt() or SHOW_PI_OFFSET),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE = "com.kirkouski.gtalarm.ACTION_FIRE"
        const val EXTRA_ALARM_ID = "alarm_id"
        private const val SHOW_PI_OFFSET = 0x40000000
        private const val TAG = "AlarmScheduler"
    }
}
