package com.kirkouski.gtwake.companion.data.sync

import com.kirkouski.gtwake.companion.domain.Alarm

/**
 * Typed representation of a peer-originated wear-bridge message. Mirrors
 * the JSON wire format defined in `docs/sync-architecture.md` §2.
 */
sealed class IncomingMessage {
    abstract val alarmId: Long
    abstract val updatedAtEpoch: Long

    data class AlarmAdded(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
        val alarm: Alarm,
    ) : IncomingMessage()

    data class AlarmUpdated(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
        val alarm: Alarm,
    ) : IncomingMessage()

    data class AlarmToggled(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
        val enabled: Boolean,
    ) : IncomingMessage()

    data class AlarmDeleted(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
    ) : IncomingMessage()

    data class AlarmFired(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
    ) : IncomingMessage()

    data class AlarmDismissed(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
    ) : IncomingMessage()

    // Snooze duration is owned by the phone (per-alarm `alarm.snoozeMinutes`),
    // so the wire payload carries only the action — no reschedule time.
    data class AlarmSnoozed(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
    ) : IncomingMessage()

    // Dev-only log relay from the watch — surfaces watch-side Logger lines
    // in phone adb logcat (HiLog access from DevEco is unreliable on
    // Windows). `alarmId`/`updatedAtEpoch` aren't meaningful here; the
    // handler logs `level`+`msg` under tag "WatchLog" and exits.
    data class WatchLog(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
        val level: String,
        val msg: String,
    ) : IncomingMessage()

    // Response to a `sync_check` request the phone sent before initiating a
    // full force-sync. Carries the watch's current AlarmHash so the phone
    // can skip the push when both sides already match. Doesn't traverse
    // IncomingMessageHandler's LWW path — HuaweiWearBridge intercepts it
    // directly to complete the pending hash-request continuation.
    // `alarmId`/`updatedAtEpoch` aren't meaningful here (the envelope
    // didn't carry them); we set both to 0 to satisfy the sealed-class
    // contract without implying any LWW semantics.
    data class SyncHash(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
        val hash: String,
    ) : IncomingMessage()

    // Watch's reply to a phone-originated `alarm_fired`. Sent from the
    // ring page's onShow handler — confirms the watch's ring UI is
    // visible and vibrating. HuaweiWearBridge intercepts this for the
    // pre-arm coordinator so the phone can wait for the watch to be
    // actually ringing before starting its own audio (otherwise phone
    // rings ~500-1000ms before watch on a cold-launch).
    data class AlarmRinging(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
    ) : IncomingMessage()

    // Watch's reply to a phone-originated `alarm_dismissed`. Sent from
    // incomingHandler.js after onPeerEndedRing(alarmId) runs — confirms
    // the watch's ring page has closed (or that it was never up to
    // begin with). HuaweiWearBridge intercepts this for the dismiss
    // coordinator so the phone knows the watch actually processed the
    // dismiss, not just the transport layer's 207. Without this loop
    // a 207 ACK can succeed while the watch's JS receiver was in a
    // transient state and silently dropped the envelope — the user
    // would then have to manually dismiss on the watch too.
    data class AlarmDismissedAck(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
    ) : IncomingMessage()

    // Symmetric to AlarmDismissedAck — for the snooze action.
    data class AlarmSnoozedAck(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
    ) : IncomingMessage()
}
