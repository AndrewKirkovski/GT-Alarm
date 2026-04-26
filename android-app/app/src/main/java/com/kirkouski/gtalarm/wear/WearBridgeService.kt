package com.kirkouski.gtalarm.wear

import com.kirkouski.gtalarm.data.sync.IncomingMessageHandler
import com.kirkouski.gtalarm.domain.Alarm

/**
 * Contract between the phone app and a paired wearable. Today this is a no-op
 * stub (see [NoOpWearBridge]); once Huawei Wear Engine vendor approval lands,
 * a `HuaweiWearBridge` will bind here and use HiWear P2P to talk to the watch.
 *
 * Wire format (agreed with the watch side, see docs/sync-architecture.md §2).
 * Message-type vocabulary matches the watch's WearBridgeStub exactly:
 *   { "type": "alarm_added" | "alarm_updated" | "alarm_toggled"
 *           | "alarm_deleted" | "alarm_fired" | "alarm_dismissed"
 *           | "alarm_snoozed",
 *     "alarmId": <number>,
 *     "updatedAtEpoch": <number>,                 // LWW key, always present
 *     "alarm"?: <full alarm object on add/update/toggle>,
 *     "rescheduleEpoch": <number> on snooze,
 *     "enabled"?: <bool> on toggle }
 *
 * The `updatedAtEpoch` is the LWW key: receivers compare against their local
 * value and apply only if the incoming stamp is strictly newer. Ties on live
 * rows favour the incoming message (deterministic convergence to the leader);
 * ties involving a tombstone favour the local tombstone (delete is sticky —
 * see docs/sync-architecture.md §5.2).
 *
 * IMPORTANT: every send method must receive an `updatedAtEpoch > 0`. The
 * `0L` sentinel on `Alarm.updatedAtEpoch` only exists to keep Room's column
 * default sane for migrated v1 rows — by the time an alarm reaches this
 * bridge the AlarmRepository has already stamped a real timestamp. Impls
 * (`NoOpWearBridge`, future `HuaweiWearBridge`) precondition-check this.
 */
interface WearBridgeService {
    fun sendAlarmAdded(alarm: Alarm)
    fun sendAlarmUpdated(alarm: Alarm)
    fun sendAlarmToggled(alarm: Alarm)
    fun sendAlarmDeleted(alarmId: Long, updatedAtEpoch: Long)
    fun sendAlarmFired(alarmId: Long)
    fun sendAlarmDismissed(alarmId: Long)
    fun sendAlarmSnoozed(alarmId: Long, rescheduleEpoch: Long)

    /**
     * Receive-side seam. The bridge implementation calls `handler?.handle(msg)`
     * for every peer message it decodes. `NoOpWearBridge` stores the handler
     * but never invokes it (no peer); `HuaweiWearBridge` will once Wear-Engine
     * P2P is wired up. Pass `null` to detach.
     *
     * **Threading contract:** `IncomingMessageHandler.handle` is `suspend`. The
     * bridge implementation is responsible for invoking it on a coroutine
     * scope it owns (typically `Dispatchers.IO` or a `ServiceCoroutineScope`),
     * NOT directly from a binder/system callback thread. The interface deliberately
     * accepts the handler instance rather than a lambda so each implementation
     * can pick its own dispatching strategy.
     */
    fun setIncomingHandler(handler: IncomingMessageHandler?)
}
