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
        Log.d(TAG, "boot/time event: ${intent.action}")
        val pending = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                repository.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
