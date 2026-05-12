package com.kirkouski.gtalarm.ring

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-singleton "ring ended" signal. Emitted by [AlarmRingService]
 * whenever a ring stops — for ANY reason (user dismiss/snooze on phone OR
 * watch, auto-stop timeout, self-destruct DELETE). [AlarmActivity]
 * observes and `finish()`es so the lockscreen takeover doesn't outlive
 * the audio.
 *
 * Why this exists: dismiss/snooze originating on the watch flows
 * Wear Engine receiver → IncomingMessageHandler → AlarmRingService
 * → stop(). The AlarmActivity is a separate Activity (started by the
 * notification's full-screen intent) and has no direct lifecycle tie
 * to the service — without this signal, the watch tap stops audio but
 * leaves the phone's full-screen UI orphaned until the user manually
 * dismisses it.
 *
 * Why a process-singleton object (not Hilt @Singleton): the signal has
 * zero collaborators — it's just a SharedFlow with a single emit/collect
 * pair. Adding it to the Hilt graph would require injecting it into
 * both the Service AND the Activity for no behavioral benefit.
 * MutableSharedFlow itself is thread-safe.
 *
 * Buffer size 1 + DROP_OLDEST so a fast "dismiss → re-fire → dismiss"
 * cycle never loses the second emit; the Activity observes
 * `replayCache`-aware to avoid acting on a stale value from a prior
 * cycle (see [AlarmActivity] collector).
 */
object RingEndedSignal {

    /**
     * Wall-clock epoch of the most recent ring-ended event. Activity uses
     * this to filter out emissions that predate its own onCreate (so a
     * stale signal from a previous ring doesn't immediately close a
     * newly-started Activity).
     */
    private val _events = MutableSharedFlow<Long>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<Long> = _events.asSharedFlow()

    fun emitRingEnded() {
        _events.tryEmit(System.currentTimeMillis())
    }
}
