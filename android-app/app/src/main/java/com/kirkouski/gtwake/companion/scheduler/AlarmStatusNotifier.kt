package com.kirkouski.gtwake.companion.scheduler

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.kirkouski.gtwake.companion.MainActivity
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.util.TimeFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

private const val MILLIS_PER_MINUTE = 60_000L

/** When the upcoming notice is due, or null when this alarm gets none. */
internal fun upcomingNoticeAt(alarm: Alarm, trigger: Long, leadMinutes: Int, now: Long): Long? {
    if (leadMinutes <= 0 || alarm.isRelative || alarm.snoozedUntilEpoch != null) return null
    return (trigger - leadMinutes * MILLIS_PER_MINUTE).takeIf { it > now }
}

/**
 * The two silent notices around an alarm: upcoming before it and the snooze
 * countdown after it. Both use a LOW channel, so neither can wake anyone. The
 * upcoming notice goes through its own receiver, so its PendingIntent is
 * separate from the fire intent and nothing here can touch a ring.
 */
@Singleton
class AlarmStatusNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    // Device-protected, so the scheduler can read it before the first unlock.
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var upcomingLeadMinutes: Int
        get() = prefs.getInt(KEY_LEAD_MINUTES, DEFAULT_LEAD_MINUTES)
        set(value) = prefs.edit { putInt(KEY_LEAD_MINUTES, value) }

    fun armUpcoming(alarm: Alarm, trigger: Long) {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        val at = upcomingNoticeAt(alarm, trigger, upcomingLeadMinutes, System.currentTimeMillis()) ?: return
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, broadcast(ACTION_SHOW, alarm.id))
    }

    /** Disarms the upcoming notice and removes it if it is showing. */
    fun clearUpcoming(alarmId: Long) {
        alarmManager.cancel(broadcast(ACTION_SHOW, alarmId))
        manager.cancel(upcomingId(alarmId))
    }

    fun clearSnooze(alarmId: Long) {
        manager.cancel(snoozeId(alarmId))
    }

    /** At ring start. Leaves the next repeat's armed notice alone. */
    fun clearPosted(alarmId: Long) {
        manager.cancel(upcomingId(alarmId))
        manager.cancel(snoozeId(alarmId))
    }

    fun showUpcoming(alarm: Alarm, trigger: Long) {
        val builder = notice(alarm, trigger)
            .setContentTitle(context.getString(R.string.upcoming_notice_title, timeText(trigger)))
            .addAction(0, context.getString(R.string.action_skip), broadcast(ACTION_SKIP, alarm.id))
        manager.notify(upcomingId(alarm.id), builder.build())
    }

    fun showSnooze(alarm: Alarm, trigger: Long, canStop: Boolean) {
        val builder = notice(alarm, trigger)
            .setContentTitle(context.getString(R.string.timer_snoozed_until, timeText(trigger)))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(trigger)
            .setShowWhen(true)
        // The action's receiver is not direct-boot aware.
        if (canStop) {
            val stop = broadcast(ACTION_STOP_SNOOZE, alarm.id)
            builder.addAction(0, context.getString(R.string.snooze_notice_stop), stop)
        }
        manager.notify(snoozeId(alarm.id), builder.build())
    }

    private fun notice(alarm: Alarm, trigger: Long): NotificationCompat.Builder {
        ensureChannel()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentText(alarm.label.ifBlank { null })
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSilent(true)
            .setContentIntent(open)
            .setTimeoutAfter((trigger - System.currentTimeMillis()).coerceAtLeast(1L))
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_status),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }

    private fun broadcast(action: String, alarmId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        alarmId.toInt(),
        Intent(context, UpcomingAlarmReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmSchedulerImpl.EXTRA_ALARM_ID, alarmId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun timeText(epoch: Long): String {
        val calendar = Calendar.getInstance().also { it.timeInMillis = epoch }
        return TimeFormatter.formatHourMinute(
            context,
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
    }

    private fun upcomingId(alarmId: Long): Int = UPCOMING_NOTIFICATION_BASE or alarmId.toInt()

    private fun snoozeId(alarmId: Long): Int = SNOOZE_NOTIFICATION_BASE or alarmId.toInt()

    companion object {
        const val ACTION_SHOW = "com.kirkouski.gtwake.companion.upcoming.SHOW"
        const val ACTION_SKIP = "com.kirkouski.gtwake.companion.upcoming.SKIP"
        const val ACTION_STOP_SNOOZE = "com.kirkouski.gtwake.companion.upcoming.STOP_SNOOZE"
        private const val CHANNEL_ID = "alarm_status"
        private const val PREFS_FILE = "alarm_status"
        private const val KEY_LEAD_MINUTES = "upcoming_lead_minutes"
        private const val DEFAULT_LEAD_MINUTES = 30

        // Clear of the ring (42), the stopwatch (43) and missed alarms (0x40000000).
        private const val UPCOMING_NOTIFICATION_BASE = 0x10000000
        private const val SNOOZE_NOTIFICATION_BASE = 0x20000000
    }
}
