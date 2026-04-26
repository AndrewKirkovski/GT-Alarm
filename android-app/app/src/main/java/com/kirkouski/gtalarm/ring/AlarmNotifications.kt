package com.kirkouski.gtalarm.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.util.TimeFormatter

object AlarmNotifications {
    const val CHANNEL_ID = "alarm_ringing"
    const val NOTIFICATION_ID = 42

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_alarm_desc)
            enableLights(true)
            enableVibration(true)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            // Channel sound is a fallback for the notification; the service owns real audio.
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
        }
        nm.createNotificationChannel(channel)
    }

    fun buildRingingNotification(
        context: Context,
        alarm: Alarm,
        fullScreenIntent: PendingIntent,
        dismissIntent: PendingIntent,
        snoozeIntent: PendingIntent,
    ): Notification {
        val title = alarm.label.ifBlank { context.getString(R.string.alarm_notification_title) }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(TimeFormatter.formatHourMinute(context, alarm.hour, alarm.minute))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .addAction(0, context.getString(R.string.action_dismiss), dismissIntent)
            .addAction(0, context.getString(R.string.action_snooze), snoozeIntent)
            .build()
    }

    fun dismissPendingIntent(context: Context, alarmId: Long): PendingIntent {
        val intent = Intent(context, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_DISMISS
            putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getService(
            context,
            (alarmId.toInt() or ACTION_DISMISS_BIT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun snoozePendingIntent(context: Context, alarmId: Long): PendingIntent {
        val intent = Intent(context, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_SNOOZE
            putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getService(
            context,
            (alarmId.toInt() or ACTION_SNOOZE_BIT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun fullScreenPendingIntent(context: Context, alarmId: Long): PendingIntent {
        val intent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getActivity(
            context,
            (alarmId.toInt() or FULL_SCREEN_BIT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val ACTION_DISMISS_BIT = 0x10000000
    private const val ACTION_SNOOZE_BIT = 0x20000000
    private const val FULL_SCREEN_BIT = 0x08000000
}
