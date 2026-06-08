package com.kirkouski.gtwake.companion.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.sync.IncomingMessageHandler
import com.kirkouski.gtwake.companion.di.IoDispatcher
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import com.kirkouski.gtwake.companion.widget.WidgetRefresher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var wearBridge: WearBridgeService
    @Inject lateinit var incomingHandler: IncomingMessageHandler
    @Inject lateinit var widgetRefresher: WidgetRefresher
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "boot/time event: $action")
        val pending = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                when (action) {
                    Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                        // Pre-unlock: Room locked, rearm from BFU cache.
                        repository.rescheduleFromBfu()
                    }
                    Intent.ACTION_BOOT_COMPLETED -> {
                        repository.rescheduleAllOnBoot()
                        // Idempotent — covers the case where the user
                        // dismisses morning alarm pre-MainActivity.
                        wearBridge.setIncomingHandler(incomingHandler)
                    }
                    Intent.ACTION_LOCALE_CHANGED -> {
                        // Locale-sensitive strings need re-render; alarm
                        // schedule itself is locale-free.
                        widgetRefresher.refresh()
                    }
                    else -> {
                        // TIMEZONE_CHANGED / TIME_SET / MY_PACKAGE_REPLACED.
                        repository.rescheduleAll()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
