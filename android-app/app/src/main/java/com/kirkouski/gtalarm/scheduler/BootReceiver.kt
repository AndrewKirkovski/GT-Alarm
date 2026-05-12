package com.kirkouski.gtalarm.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kirkouski.gtalarm.data.AlarmRepository
import com.kirkouski.gtalarm.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: AlarmRepository
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "boot/time event: $action")
        val pending = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    // Only the boot path needs the "missed during downtime"
                    // rule. Time/timezone change events fire while the device
                    // is already up — no downtime to reckon with.
                    repository.rescheduleAllOnBoot()
                } else {
                    repository.rescheduleAll()
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
