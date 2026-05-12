package com.kirkouski.gtalarm.wear

import com.kirkouski.gtalarm.data.sync.IncomingMessageHandler
import com.kirkouski.gtalarm.domain.Alarm
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract between the phone app and a paired wearable. Wire format and LWW
 * semantics live in `docs/sync-architecture.md` §2 — keep this interface in
 * lock-step with that document.
 *
 * Every send method requires `updatedAtEpoch > 0`. Implementations may drop
 * the send when the stamp is below an NTP-sane threshold rather than crash
 * the caller.
 */
// reason: function count grew past 11 with the suspending pre-arm /
// dismiss / snooze variants (each needed separately so AlarmRingService
// can await delivery before tearing down). Splitting into a base
// interface + a "synchronized" addendum would smear the same fan-out
// across more types without changing logic.
@Suppress("TooManyFunctions")
interface WearBridgeService {
    /** UI-observable connection-state stream. */
    val statusFlow: StateFlow<WatchSyncStatus>

    /**
     * Resolved bonded device (`null` until [forceSync] or a successful send
     * has run). Surfaces name + model so the WatchSyncCard can show
     * "HUAWEI WATCH GT 6 · FRA-B19" instead of just a connected/not-connected
     * flag.
     */
    val pairedDeviceInfo: StateFlow<PairedDeviceInfo?>

    fun sendAlarmAdded(alarm: Alarm)
    fun sendAlarmUpdated(alarm: Alarm)
    fun sendAlarmToggled(alarm: Alarm)
    fun sendAlarmDeleted(alarmId: Long, updatedAtEpoch: Long)
    fun sendAlarmFired(alarmId: Long)
    fun sendAlarmDismissed(alarmId: Long)
    fun sendAlarmSnoozed(alarmId: Long, rescheduleEpoch: Long)

    /**
     * Pre-arm: send `alarm_fired` then await the watch's `alarm_ringing`
     * reply. Returns true iff the watch's ring page has actually rendered
     * within [timeoutMs] (its onShow handler sends `alarm_ringing` back).
     * Used by AlarmRingService to start phone audio in lock-step with
     * the watch — without this, the phone beats the watch by 500-1000ms
     * on cold launches because P2P send ACK happens before the watch's
     * JS engine has finished starting + mounting the ring page.
     *
     * Returns false on timeout / send failure / no paired watch.
     */
    suspend fun sendAlarmFiredAwaiting(alarmId: Long, timeoutMs: Long): Boolean

    /**
     * Suspending dismiss: waits up to [timeoutMs] for the watch to ACK
     * the P2P send (207=COMM_SUCCESS). Used by AlarmRingService so the
     * service doesn't tear down (and risk process-kill) before the watch
     * has heard about the dismiss — otherwise the watch keeps ringing.
     */
    suspend fun sendAlarmDismissedAwaiting(alarmId: Long, timeoutMs: Long): Boolean

    /** Same rationale as [sendAlarmDismissedAwaiting] but for snooze. */
    suspend fun sendAlarmSnoozedAwaiting(
        alarmId: Long,
        rescheduleEpoch: Long,
        timeoutMs: Long,
    ): Boolean

    /** Receive-side seam. Pass `null` to detach. */
    fun setIncomingHandler(handler: IncomingMessageHandler?)

    /**
     * Renders the Wear Engine auth dialog via Huawei Health. Activity context
     * required (the dialog is rendered by Huawei Health, not us). User-driven
     * — wired to the "Authorize watch access" button in HelpScreen, NOT
     * auto-fired on launch.
     */
    fun requestPermissionFromActivity(activity: android.app.Activity)

    /**
     * User-initiated "Force sync" button. Pings the peer Lite Wearable app
     * (which auto-launches it on Lite), runs an optional hash precheck
     * round-trip, then dispatches `alarm_added` envelopes for the alarms
     * returned by [freshAlarms].
     *
     * `freshAlarms` is a **suspending function**, not a snapshot list, so
     * the bridge can call it AFTER the hash precheck completes — closing
     * the TOCTOU window where the user adds an alarm during the ~2s
     * sync_check round-trip and the precheck matches a stale snapshot.
     *
     * Returns one of the [ForceSyncResult] cases for UI display.
     */
    suspend fun forceSync(freshAlarms: suspend () -> List<Alarm>): ForceSyncResult
}
