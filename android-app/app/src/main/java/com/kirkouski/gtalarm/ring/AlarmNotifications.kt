package com.kirkouski.gtalarm.ring

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.util.TimeFormatter

object AlarmNotifications {
    private const val TAG = "AlarmNotifications"
    // Channel id v2 (2026-05-12). NotificationChannel state is sticky on
    // device — once "alarm_ringing" was registered with any importance, the
    // existing `ensureChannel` early-return guard meant we never re-applied
    // the IMPORTANCE_HIGH + setBypassDnd + sound-attrs config to subsequent
    // installs. If any earlier APK iteration registered the channel at a
    // lower importance, the FSI path silently degrades on that device.
    // Bumping the id forces a fresh registration with the current config,
    // and we delete the legacy id below in ensureChannel.
    const val CHANNEL_ID = "alarm_ringing_v2"
    private const val LEGACY_CHANNEL_ID = "alarm_ringing"
    const val NOTIFICATION_ID = 42

    // Separate low-priority channel for "missed during reboot" notifications.
    // Distinct from the ringing channel so users can mute it independently
    // without losing actual alarm rings.
    const val MISSED_CHANNEL_ID = "alarm_missed"
    private const val MISSED_NOTIFICATION_BASE = 0x40_00_00_00

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Drop the legacy channel (one-time, idempotent — no-op once gone).
        // Required because NotificationChannel importance is immutable after
        // creation; without removing the legacy id, ringing notifications
        // from the v2 channel would still inherit the legacy importance on
        // some devices where the legacy id was registered.
        runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
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

    fun ensureMissedChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(MISSED_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            MISSED_CHANNEL_ID,
            context.getString(R.string.notif_channel_missed_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_missed_desc)
            enableLights(false)
            enableVibration(false)
            setBypassDnd(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(channel)
    }

    // Posts a passive notification for a relative alarm whose fire time fell
    // during device downtime (target < bootCompleteAt). The alarm row itself
    // has already been deleted by the boot-recovery flow — this is purely
    // an FYI for the user. The notification auto-cancels on tap.
    fun postMissedNotification(context: Context, alarm: Alarm) {
        ensureMissedChannel(context)
        val title = context.getString(R.string.missed_notification_title)
        val text = if (alarm.label.isBlank()) {
            context.getString(R.string.missed_notification_text_unlabeled)
        } else {
            context.getString(R.string.missed_notification_text_labeled, alarm.label)
        }
        val builder = NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
        val nm = context.getSystemService(NotificationManager::class.java)
        // Distinct ID per missed alarm so multiple stack rather than collapse.
        val notifId = MISSED_NOTIFICATION_BASE or (alarm.id.toInt() and 0x00_FF_FF_FF)
        nm.notify(notifId, builder.build())
    }

    // Minimal placeholder notification used to satisfy Android's 5 s
    // startForeground deadline. AlarmRingService starts FG with this
    // BEFORE doing any I/O (DAO read, pre-arm), then upgrades to the
    // real buildRingingNotification once the alarm row is loaded.
    //
    // Visibility/category/priority match the real ringing notification so
    // Android FG_TYPE_MEDIA_PLAYBACK handling + lockscreen display behave
    // identically for the brief placeholder window — avoids a visible
    // "ranking flicker" when the upgrade swaps the notif body.
    fun buildPlaceholderNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()
    }

    fun buildRingingNotification(
        context: Context,
        alarm: Alarm,
        fullScreenIntent: PendingIntent,
        dismissIntent: PendingIntent,
        snoozeIntent: PendingIntent,
    ): Notification {
        // Android 14+ (API 34) requires USE_FULL_SCREEN_INTENT to actually
        // wake the screen via the full-screen intent path. Alarm apps with
        // the SET_ALARM intent filter typically get it auto-granted, but
        // user revocation or device customizations (Samsung One UI ROM
        // policies) can leave us without it. We still call setFullScreenIntent
        // — the system silently degrades to a heads-up which is the
        // documented fallback. The AlarmActivity start in AlarmRingService
        // is the primary screen-wake path either way; this log just makes
        // the degradation visible in logcat.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && !nm.canUseFullScreenIntent()) {
                Log.w(
                    TAG,
                    "USE_FULL_SCREEN_INTENT not granted — alarm notification will " +
                        "render as heads-up only (AlarmActivity is still the primary " +
                        "lockscreen-wake path)",
                )
            }
        }
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

    // reason: MODE_BACKGROUND_ACTIVITY_START_ALLOWED is deprecated in API 36
    // (in favor of the granular ALLOW_ALWAYS / DENIED constants added in 35),
    // but compileSdk=36 still treats it as valid at runtime AND it's the
    // value that works back to API 34 where our minSdk-effective target
    // (the FSI gate) lives. The granular replacements would let us tighten
    // later; for now we want the simple "allowed" behavior on Android 14
    // because that's the broken-screen-wake case we're fixing.
    @Suppress("DEPRECATION")
    fun fullScreenPendingIntent(context: Context, alarmId: Long): PendingIntent {
        val intent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
        }
        // Android 14 BAL opt-in for the PI creator. Without this, when
        // NotificationManagerService fires the FSI it carries no
        // background-activity-start token of its own and the AlarmActivity
        // launch is silently blocked (heads-up notification still posts but
        // the screen does not wake). Setting CREATOR mode = ALLOWED grants
        // the PI the right to start an Activity in background when fired by
        // anyone, including the system.
        //
        // UPSIDE_DOWN_CAKE = API 34 = Android 14, where this API + the
        // requirement appeared. minSdk is 31 so we runtime-guard.
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                .toBundle()
        } else {
            null
        }
        return PendingIntent.getActivity(
            context,
            (alarmId.toInt() or FULL_SCREEN_BIT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options,
        )
    }

    private const val ACTION_DISMISS_BIT = 0x10000000
    private const val ACTION_SNOOZE_BIT = 0x20000000
    private const val FULL_SCREEN_BIT = 0x08000000
}
