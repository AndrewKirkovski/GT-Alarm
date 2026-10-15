package com.kirkouski.gtwake.companion.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.sync.IncomingMessageHandler
import com.kirkouski.gtwake.companion.di.IoDispatcher
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import com.kirkouski.gtwake.companion.widget.WidgetRefresher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
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
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        val route = routeFor(action, unlocked)
        val pending = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                execute(route)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // reason: a receiver must never crash the process (it would also kill a
                // pre-unlock ring); the failure is logged. The next app start, unlock or
                // boot/time event re-arms, and the boot branch still registers the watch
                // handler when its re-arm fails (see execute).
                Log.e(TAG, "action=$action unlocked=$unlocked route=$route failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun execute(route: BootRoute) {
        when (route) {
            // Pre-unlock: Room is locked, re-arm from the BFU cache.
            BootRoute.RESCHEDULE_FROM_BFU -> repository.rescheduleFromBfu()
            BootRoute.RESCHEDULE_ON_BOOT -> {
                try {
                    repository.rescheduleAllOnBoot()
                } finally {
                    // Idempotent — covers the case where the user dismisses the morning
                    // alarm pre-MainActivity. Registered even when the re-arm throws: the
                    // caught failure no longer restarts the process, so without this the
                    // watch Stop/Snooze would never reach the phone until the app opens.
                    wearBridge.setIncomingHandler(incomingHandler)
                }
            }
            // Locale-sensitive strings need re-render; alarm
            // schedule itself is locale-free.
            BootRoute.REFRESH_WIDGETS -> widgetRefresher.refresh()
            BootRoute.RESCHEDULE_ALL -> repository.rescheduleAll()
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}

/** What [BootReceiver] runs for a broadcast. */
internal enum class BootRoute { RESCHEDULE_FROM_BFU, RESCHEDULE_ON_BOOT, REFRESH_WIDGETS, RESCHEDULE_ALL }

/**
 * Pure routing decision. Before the first unlock Room (credential-encrypted) throws,
 * so TIME_SET / TIMEZONE_CHANGED / MY_PACKAGE_REPLACED re-arm from the BFU cache
 * instead; BOOT_COMPLETED re-arms everything from Room after unlock anyway.
 */
internal fun routeFor(action: String?, unlocked: Boolean): BootRoute = when (action) {
    Intent.ACTION_LOCKED_BOOT_COMPLETED -> BootRoute.RESCHEDULE_FROM_BFU
    Intent.ACTION_BOOT_COMPLETED -> BootRoute.RESCHEDULE_ON_BOOT
    Intent.ACTION_LOCALE_CHANGED -> BootRoute.REFRESH_WIDGETS
    else -> if (unlocked) BootRoute.RESCHEDULE_ALL else BootRoute.RESCHEDULE_FROM_BFU
}
