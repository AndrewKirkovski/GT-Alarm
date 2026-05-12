// Receive-side handler for peer messages on the watch. Mirrors the
// phone-side IncomingMessageHandler.kt — applies the LWW + tombstone
// rules from docs/sync-architecture.md §2/§5 and writes resolved state
// directly through AlarmStore, deliberately bypassing the broadcast
// path (Index/EditPage call sites that wrap mutations with
// WearBridgeStub.notifyXxx) so an inbound peer message never triggers
// a re-broadcast loop.
//
// Phase 5a scope: state mutations on AlarmStore only.
// ReminderAgent re-publish on add/update is deferred to Phase 5b — the
// existing reconcileReminders() pass at EntryAbility.onCreate covers the
// reminder catch-up at next launch. Live-ring coordination (dismissed)
// is log-only today; HuaweiWearBridge will wire a real intent later.

import { AlarmItem } from '../model/AlarmItem';
import { AlarmStore } from './AlarmStore';
import { LwwResolver } from './LwwResolver';
import { Tombstones } from './Tombstones';
import { Logger } from '../util/Logger';
import { BridgeMessage } from '../util/WearBridgeStub';

const TYPE_ADDED = 'alarm_added';
const TYPE_UPDATED = 'alarm_updated';
const TYPE_TOGGLED = 'alarm_toggled';
const TYPE_DELETED = 'alarm_deleted';
const TYPE_FIRED = 'alarm_fired';
const TYPE_DISMISSED = 'alarm_dismissed';
const TYPE_SNOOZED = 'alarm_snoozed';

export class IncomingMessageHandler {

  static async handle(msg: BridgeMessage): Promise<void> {
    if (!Number.isFinite(msg.updatedAtEpoch) || msg.updatedAtEpoch < 0) {
      Logger.w('IncomingMsg: rejecting bad updatedAtEpoch=' + msg.updatedAtEpoch + ' type=' + msg.type);
      return;
    }
    switch (msg.type) {
      case TYPE_ADDED:
      case TYPE_UPDATED:
        if (msg.alarm === undefined) {
          Logger.w('IncomingMsg: missing alarm payload on ' + msg.type);
          return;
        }
        await IncomingMessageHandler.applyAddOrUpdate(msg.alarm, msg.updatedAtEpoch);
        return;
      case TYPE_TOGGLED:
        if (msg.enabled === undefined) {
          Logger.w('IncomingMsg: missing enabled on alarm_toggled');
          return;
        }
        await IncomingMessageHandler.applyToggle(msg.alarmId, msg.enabled, msg.updatedAtEpoch);
        return;
      case TYPE_DELETED:
        await IncomingMessageHandler.applyDelete(msg.alarmId, msg.updatedAtEpoch);
        return;
      case TYPE_FIRED:
        Logger.i('IncomingMsg: peer fired alarm id=' + msg.alarmId);
        return;
      case TYPE_DISMISSED:
        Logger.i('IncomingMsg: peer dismissed alarm id=' + msg.alarmId);
        return;
      case TYPE_SNOOZED:
        if (msg.rescheduleEpoch === undefined) {
          Logger.w('IncomingMsg: missing rescheduleEpoch on alarm_snoozed');
          return;
        }
        Logger.i('IncomingMsg: peer snoozed id=' + msg.alarmId
          + ' until=' + msg.rescheduleEpoch + ' (Phase 5b will re-arm reminderAgent)');
        return;
      default:
        Logger.w('IncomingMsg: unknown type=' + msg.type);
    }
  }

  private static async applyAddOrUpdate(incoming: AlarmItem, incomingEpoch: number): Promise<void> {
    const id = incoming.id;
    if (await Tombstones.isTombstoned(id, incomingEpoch)) {
      Logger.i('IncomingMsg: suppress add/update id=' + id + ' — tombstoned');
      return;
    }
    const items = await AlarmStore.getAll();
    const local = items.find(a => a.id === id);
    if (!LwwResolver.shouldApply(incomingEpoch, local === undefined ? undefined : local.updatedAtEpoch)) {
      Logger.i('IncomingMsg: ignore add/update id=' + id + ' — older than local');
      return;
    }
    const stamped: AlarmItem = {
      id: incoming.id,
      label: incoming.label,
      hour: incoming.hour,
      minute: incoming.minute,
      daysOfWeek: incoming.daysOfWeek,
      enabled: incoming.enabled,
      reminderId: local === undefined ? undefined : local.reminderId,
      updatedAtEpoch: incomingEpoch,
    };
    // Use add/update based on the snapshot, but fall back if the snapshot
    // raced with another mutation. AlarmStore.add throws on duplicate id,
    // AlarmStore.update throws on missing id — we recover both ways so a
    // burst of peer messages can't deadlock on a transient mismatch.
    if (local === undefined) {
      try {
        await AlarmStore.add(stamped);
      } catch (e) {
        if (IncomingMessageHandler.isMissingOrDuplicate(e)) {
          await AlarmStore.update(stamped);
        } else {
          throw e as Error;
        }
      }
    } else {
      try {
        await AlarmStore.update(stamped);
      } catch (e) {
        if (IncomingMessageHandler.isMissingOrDuplicate(e)) {
          await AlarmStore.add(stamped);
        } else {
          throw e as Error;
        }
      }
    }
  }

  private static isMissingOrDuplicate(e: Object): boolean {
    if (!(e instanceof Error)) return false;
    const msg = e.message;
    return msg.indexOf('not found') >= 0 || msg.indexOf('duplicate id') >= 0;
  }

  private static async applyToggle(alarmId: number, enabled: boolean, incomingEpoch: number): Promise<void> {
    if (await Tombstones.isTombstoned(alarmId, incomingEpoch)) {
      Logger.i('IncomingMsg: suppress toggle id=' + alarmId + ' — tombstoned');
      return;
    }
    const items = await AlarmStore.getAll();
    const local = items.find(a => a.id === alarmId);
    if (local === undefined) {
      Logger.i('IncomingMsg: ignore toggle id=' + alarmId + ' — unknown row');
      return;
    }
    if (!LwwResolver.shouldApply(incomingEpoch, local.updatedAtEpoch)) {
      Logger.i('IncomingMsg: ignore toggle id=' + alarmId + ' — older than local');
      return;
    }
    const updated: AlarmItem = {
      id: local.id,
      label: local.label,
      hour: local.hour,
      minute: local.minute,
      daysOfWeek: local.daysOfWeek,
      enabled,
      reminderId: local.reminderId,
      updatedAtEpoch: incomingEpoch,
    };
    await AlarmStore.update(updated);
  }

  private static async applyDelete(alarmId: number, incomingEpoch: number): Promise<void> {
    // Tombstone is sticky — write unconditionally so a stale add/update
    // arriving later is suppressed by the §2 tombstone tie-break.
    if (incomingEpoch > 0) {
      try {
        await Tombstones.add(alarmId, incomingEpoch);
      } catch (e) {
        Logger.warn('IncomingMsg: tombstone add failed', e);
      }
    }
    const items = await AlarmStore.getAll();
    const local = items.find(a => a.id === alarmId);
    if (local === undefined) {
      Logger.i('IncomingMsg: delete id=' + alarmId + ' — tombstone only');
      return;
    }
    if (!LwwResolver.shouldApply(incomingEpoch, local.updatedAtEpoch)) {
      Logger.i('IncomingMsg: suppress delete id=' + alarmId + ' — older than local');
      return;
    }
    await AlarmStore.delete(alarmId);
  }
}
