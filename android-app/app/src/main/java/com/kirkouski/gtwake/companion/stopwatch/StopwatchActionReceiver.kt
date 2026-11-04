package com.kirkouski.gtwake.companion.stopwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kirkouski.gtwake.companion.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pause, Lap and Resume from the notification. Not direct-boot aware. */
@AndroidEntryPoint
class StopwatchActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: StopwatchRepository
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                when (intent.action) {
                    StopwatchNotifier.ACTION_PAUSE -> repository.update { s, clock -> s.pause(clock) }
                    StopwatchNotifier.ACTION_LAP -> repository.update { s, clock -> s.lap(clock) }
                    StopwatchNotifier.ACTION_RESUME -> repository.update { s, clock -> s.start(clock) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
