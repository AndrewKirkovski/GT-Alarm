import { AlarmItem } from '../model/AlarmItem';
import { Logger } from './Logger';

/**
 * Stub for the phone<->watch channel. All methods log today; once Huawei
 * Wear Engine vendor approval lands, replace `send` with
 * `wearEngine.getP2pClient().send(peerDevice, payload)` — every other call
 * site already constructs the JSON shape the real impl will transmit.
 *
 * Wire format (agreed with the phone side, see docs/sync-architecture.md §2):
 *   msg = { type: "alarm_fired" | "alarm_dismissed" | "alarm_snoozed"
 *                | "alarm_added" | "alarm_updated" | "alarm_deleted"
 *                | "alarm_toggled",
 *           alarmId: number,
 *           updatedAtEpoch: number,           // LWW key (always present)
 *           alarm?: AlarmItem,                // on add/update, full payload
 *           rescheduleEpoch?: number,         // on snooze
 *           enabled?: boolean }               // on toggle
 *
 * The `updatedAtEpoch` is the LWW key: receivers compare against their local
 * value and apply only if the incoming stamp is strictly newer (ties favour
 * the incoming message to converge on the leader).
 *
 * This is the ONLY file that needs to change on the watch side when real P2P
 * arrives.
 */

export interface BridgeMessage {
  type: string;
  alarmId: number;
  updatedAtEpoch: number;
  alarm?: AlarmItem;
  // rescheduleEpoch is required by the phone-side WearBridgeService.kt
  // contract on `alarm_snoozed`; both senders must always populate it.
  rescheduleEpoch?: number;
  enabled?: boolean;
}

// Receive-side seam. The bridge stores `incomingHandler` and invokes it
// for every peer message it decodes. NoOp today; HuaweiWearBridge will
// install the real P2pClient onMessage callback that routes here. The
// type is `(msg: BridgeMessage) => Promise<void>` to match the async
// shape of the IncomingMessageHandler.
//
// Threading contract: HarmonyOS ArkTS is single-threaded (event loop),
// so concurrent invocations cannot run literally in parallel. However,
// `await` points yield the loop, and AlarmStore mutations are serialised
// through its own write queue. The handler implementation must tolerate
// snapshot/write races (see IncomingMessageHandler.applyAddOrUpdate's
// add/update fallback).
export type IncomingHandler = (msg: BridgeMessage) => Promise<void>;

class WearBridge {
  private static incomingHandler: IncomingHandler | null = null;

  static setIncomingHandler(handler: IncomingHandler | null): void {
    WearBridge.incomingHandler = handler;
    Logger.i('WearBridge.setIncomingHandler ' + (handler === null ? 'null' : 'set'));
  }

  /**
   * Test-only — synthesises a peer message arrival without a real P2P
   * channel. NoOpWearBridge.test code paths exercise this; production
   * P2P-client onMessage will call the same path.
   */
  static async deliverIncoming(msg: BridgeMessage): Promise<void> {
    const h = WearBridge.incomingHandler;
    if (h === null) {
      Logger.i('deliverIncoming: no handler attached, dropping ' + msg.type);
      return;
    }
    await h(msg);
  }

  private static send(msg: BridgeMessage): void {
    // Real impl will replace the body with:
    //   wearEngine.getP2pClient().send(peerDevice, JSON.stringify(msg));
    Logger.i('WearBridge: ' + JSON.stringify(msg));
  }

  static notifyFired(alarmId: number): void {
    WearBridge.send({ type: 'alarm_fired', alarmId, updatedAtEpoch: Date.now() });
  }

  static notifyDismissed(alarmId: number): void {
    WearBridge.send({ type: 'alarm_dismissed', alarmId, updatedAtEpoch: Date.now() });
  }

  static notifySnoozed(alarmId: number, rescheduleEpoch: number): void {
    WearBridge.send({ type: 'alarm_snoozed', alarmId, updatedAtEpoch: Date.now(), rescheduleEpoch });
  }

  static notifyAdded(alarm: AlarmItem): void {
    WearBridge.send({ type: 'alarm_added', alarmId: alarm.id, updatedAtEpoch: alarm.updatedAtEpoch, alarm });
  }

  static notifyUpdated(alarm: AlarmItem): void {
    WearBridge.send({ type: 'alarm_updated', alarmId: alarm.id, updatedAtEpoch: alarm.updatedAtEpoch, alarm });
  }

  static notifyDeleted(alarmId: number, updatedAtEpoch: number): void {
    WearBridge.send({ type: 'alarm_deleted', alarmId, updatedAtEpoch });
  }

  static notifyToggled(alarm: AlarmItem): void {
    WearBridge.send({
      type: 'alarm_toggled',
      alarmId: alarm.id,
      updatedAtEpoch: alarm.updatedAtEpoch,
      enabled: alarm.enabled,
    });
  }
}

export { WearBridge as WearBridgeStub };
