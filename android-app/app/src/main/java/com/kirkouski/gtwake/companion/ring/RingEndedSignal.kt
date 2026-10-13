package com.kirkouski.gtwake.companion.ring

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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

    data class Event(
        val alarmId: Long,
        val firedAtEpoch: Long,
    )

    private val _events = MutableSharedFlow<Event>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun emitRingEnded(alarmId: Long, firedAtEpoch: Long) {
        _events.tryEmit(Event(alarmId, firedAtEpoch))
    }

    // A dismiss/snooze for this occurrence could not be saved, so the ring
    // continues. AlarmActivity leaves its "waiting for watch" state and shows
    // its buttons again. replay = 1 so a failure that happens while the screen
    // is off (Activity stopped, not collecting) is still seen on restart; the
    // Activity acts on it only when the event matches its occurrence AND it is
    // in the waiting state, which filters stale replays of a failure it has
    // already handled.
    private val _actionFailures = MutableSharedFlow<Event>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val actionFailures: SharedFlow<Event> = _actionFailures.asSharedFlow()

    fun emitActionFailed(alarmId: Long, firedAtEpoch: Long) {
        _actionFailures.tryEmit(Event(alarmId, firedAtEpoch))
    }

    // A watch dismiss/snooze for this occurrence was committed. The service
    // keeps the Activity alive for a short grace window (so a duplicate
    // request can still be answered), and AlarmActivity shows its "waiting
    // for watch" state meanwhile. replay = 1 so an Activity that was stopped
    // (screen off) when the commit happened still sees it on restart; the
    // collector filters by occurrence, so a stale event is harmless.
    private val _watchActionCommitted = MutableSharedFlow<Event>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val watchActionCommitted: SharedFlow<Event> = _watchActionCommitted.asSharedFlow()

    fun emitWatchActionCommitted(alarmId: Long, firedAtEpoch: Long) {
        _watchActionCommitted.tryEmit(Event(alarmId, firedAtEpoch))
    }

    // The occurrence the service is ringing RIGHT NOW (null when none). A
    // retained state, not an event: a stopped AlarmActivity (screen off, the
    // backup launch blocked on Android 16) may have missed several takeovers
    // and still shows the first alarm; on collect it compares this with what
    // it shows and switches to the current one. Set on takeover, cleared on
    // teardown by the occurrence that is being torn down.
    private val _current = MutableStateFlow<Event?>(null)

    val current: StateFlow<Event?> = _current.asStateFlow()

    fun setCurrent(alarmId: Long, firedAtEpoch: Long) {
        _current.value = Event(alarmId, firedAtEpoch)
    }

    fun clearCurrent(alarmId: Long, firedAtEpoch: Long) {
        _current.compareAndSet(Event(alarmId, firedAtEpoch), null)
    }

    /** Test hook: the object is a process singleton, so replayed events leak between tests. */
    // reason: resetReplayCache is the only way to clear a SharedFlow replay
    // cache; it is called from unit tests only, never on a production path.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun resetForTest() {
        _events.resetReplayCache()
        _actionFailures.resetReplayCache()
        _watchActionCommitted.resetReplayCache()
        _current.value = null
    }
}
