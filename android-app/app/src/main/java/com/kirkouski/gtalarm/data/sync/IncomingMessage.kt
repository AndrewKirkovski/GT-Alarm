package com.kirkouski.gtalarm.data.sync

import com.kirkouski.gtalarm.domain.Alarm

/**
 * Typed representation of a peer-originated wear-bridge message. Mirrors
 * the JSON wire format defined in `docs/sync-architecture.md` §2.
 *
 * Carries the LWW key (`updatedAtEpoch`) on every variant so the receive
 * path can short-circuit before any I/O when the incoming write is older
 * than the local row or suppressed by a tombstone.
 *
 * Construction is the responsibility of the bridge implementation
 * (`HuaweiWearBridge` post-vendor-approval). The handler [IncomingMessageHandler]
 * is bridge-agnostic — it only sees these typed values.
 */
sealed class IncomingMessage {
    abstract val alarmId: Long
    abstract val updatedAtEpoch: Long

    /** `type: alarm_added` — full record, no local row expected. */
    data class AlarmAdded(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
        val alarm: Alarm,
    ) : IncomingMessage()

    /** `type: alarm_updated` — full record, may overwrite a local row. */
    data class AlarmUpdated(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
        val alarm: Alarm,
    ) : IncomingMessage()

    /** `type: alarm_toggled` — flips `enabled`; payload does NOT carry the full record. */
    data class AlarmToggled(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
        val enabled: Boolean,
    ) : IncomingMessage()

    /** `type: alarm_deleted` — writes a tombstone; clears the local row if present. */
    data class AlarmDeleted(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
    ) : IncomingMessage()

    /** `type: alarm_fired` — peer began ringing. Informational, no local mutation. */
    data class AlarmFired(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
    ) : IncomingMessage()

    /** `type: alarm_dismissed` — peer dismissed the ring. Stops local ring if active. */
    data class AlarmDismissed(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
    ) : IncomingMessage()

    /** `type: alarm_snoozed` — peer snoozed; local AlarmManager re-arms at rescheduleEpoch. */
    data class AlarmSnoozed(
        override val alarmId: Long,
        override val updatedAtEpoch: Long,
        val rescheduleEpoch: Long,
    ) : IncomingMessage()
}
