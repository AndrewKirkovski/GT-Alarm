package com.kirkouski.gtalarm.data.sync

import com.kirkouski.gtalarm.domain.Alarm

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
}
