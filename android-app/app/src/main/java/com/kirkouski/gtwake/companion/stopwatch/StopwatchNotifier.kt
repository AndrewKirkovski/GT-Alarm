package com.kirkouski.gtwake.companion.stopwatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kirkouski.gtwake.companion.MainActivity
import com.kirkouski.gtwake.companion.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silent ongoing notification, like Google's clock app: a chronometer needs no
 * foreground service. Paused shows fixed text. There is no Reset action, because
 * a notification can't offer Undo.
 */
@Singleton
class StopwatchNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    fun show(stopwatch: Stopwatch, clock: StopwatchClock) {
        if (stopwatch.phase == Stopwatch.Phase.Idle) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel()
        val total = stopwatch.totalAt(clock)
        val running = stopwatch.phase == Stopwatch.Phase.Running
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stopwatch)
            .setContentTitle(context.getString(R.string.screen_stopwatch_title))
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openStopwatch())
        if (running) {
            builder
                .setUsesChronometer(true)
                .setWhen(clock.wallMs - total)
                .setShowWhen(true)
                .addAction(R.drawable.ic_pause, context.getString(R.string.stopwatch_pause), action(ACTION_PAUSE))
                .addAction(R.drawable.ic_flag, context.getString(R.string.stopwatch_lap), action(ACTION_LAP))
        } else {
            builder
                .setContentText(formatStopwatch(total))
                .setShowWhen(false)
                .addAction(R.drawable.ic_stopwatch, context.getString(R.string.stopwatch_resume), action(ACTION_RESUME))
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.screen_stopwatch_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }

    private fun action(name: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        name.hashCode(),
        Intent(context, StopwatchActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openStopwatch(): PendingIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DEEP_LINK_SCREEN, MainActivity.SCREEN_STOPWATCH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_PAUSE = "com.kirkouski.gtwake.companion.stopwatch.PAUSE"
        const val ACTION_LAP = "com.kirkouski.gtwake.companion.stopwatch.LAP"
        const val ACTION_RESUME = "com.kirkouski.gtwake.companion.stopwatch.RESUME"
        private const val CHANNEL_ID = "stopwatch"

        // The ring notification is 42; missed alarms start at 0x40000000.
        private const val NOTIFICATION_ID = 43
    }
}
