import { describe, it, expect, beforeAll, beforeEach } from '@ohos/hypium';
import preferences from '@ohos.data.preferences';
import { AlarmItem } from '../../../main/ets/model/AlarmItem';
import { AlarmStore } from '../../../main/ets/service/AlarmStore';
import { Tombstones } from '../../../main/ets/service/Tombstones';
import { IncomingMessageHandler } from '../../../main/ets/service/IncomingMessageHandler';
import { BridgeMessage } from '../../../main/ets/util/WearBridgeStub';
import { TestContext } from './TestContext';

// Integration tests for IncomingMessageHandler. AlarmStore + Tombstones
// are backed by @ohos.data.preferences and run against the real test-
// ability storage. The store is cleared before each test so suite
// ordering doesn't leak. Mirrors the phone-side IncomingMessageHandlerTest
// case set so divergence between the two receivers is caught early.

const ALARM_STORE = 'gt_alarm_store';
const ALARM_KEY = 'alarms_v1';
const TOMBSTONE_STORE = 'gt_alarm_tombstones';
const TOMBSTONE_KEY = 'tombstones_v1';

async function clearStore(name: string, key: string): Promise<void> {
  const ctx = TestContext.require();
  const s = await preferences.getPreferences(ctx, name);
  await s.put(key, '[]');
  await s.flush();
}

// Use Date.now()-anchored timestamps so the real preferences-backed
// Tombstones doesn't auto-prune our test entries via its 7-day retention
// (cutoff = Date.now() - RETENTION). Small constants like 100/200 fall
// well outside the window and would silently drop on first read.
const NOW = Date.now();
const OLD = NOW - 5_000;     // 5 s old, well within retention
const OLDER = NOW - 10_000;  // 10 s old, still fresh
const NEW = NOW;
const NEWER = NOW + 5_000;

function makeAlarm(over: Partial<AlarmItem>): AlarmItem {
  return {
    id: over.id ?? 0,
    label: over.label ?? '',
    hour: over.hour ?? 7,
    minute: over.minute ?? 30,
    daysOfWeek: over.daysOfWeek ?? 0,
    enabled: over.enabled ?? true,
    reminderId: over.reminderId,
    updatedAtEpoch: over.updatedAtEpoch ?? NOW,
  };
}

function addedMsg(alarm: AlarmItem, epoch: number): BridgeMessage {
  return { type: 'alarm_added', alarmId: alarm.id, updatedAtEpoch: epoch, alarm };
}

function updatedMsg(alarm: AlarmItem, epoch: number): BridgeMessage {
  return { type: 'alarm_updated', alarmId: alarm.id, updatedAtEpoch: epoch, alarm };
}

function deletedMsg(id: number, epoch: number): BridgeMessage {
  return { type: 'alarm_deleted', alarmId: id, updatedAtEpoch: epoch };
}

function toggledMsg(id: number, enabled: boolean, epoch: number): BridgeMessage {
  return { type: 'alarm_toggled', alarmId: id, updatedAtEpoch: epoch, enabled };
}

export default function incomingMessageHandlerTest(): void {
  describe('IncomingMessageHandler', () => {

    beforeAll(async () => {
      await AlarmStore.init(TestContext.require());
      await Tombstones.init(TestContext.require());
    });

    beforeEach(async () => {
      await clearStore(ALARM_STORE, ALARM_KEY);
      await clearStore(TOMBSTONE_STORE, TOMBSTONE_KEY);
    });

    // ─── add / update ────────────────────────────────────────────────
    it('AlarmAdded with no local row inserts the alarm', 0, async () => {
      const incoming = makeAlarm({ id: 5, label: 'peer', updatedAtEpoch: NEW });
      await IncomingMessageHandler.handle(addedMsg(incoming, NEW));

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(1);
      expect(all[0].id).assertEqual(5);
      expect(all[0].updatedAtEpoch).assertEqual(NEW);
    });

    it('AlarmAdded for tombstoned id is suppressed (no DAO write)', 0, async () => {
      await Tombstones.add(5, NEWER);
      const incoming = makeAlarm({ id: 5, updatedAtEpoch: NEW });
      await IncomingMessageHandler.handle(addedMsg(incoming, NEW));

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(0);
    });

    it('AlarmUpdated with newer stamp overwrites local row', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 7, label: 'old', updatedAtEpoch: OLDER }));
      const incoming = makeAlarm({ id: 7, label: 'new', updatedAtEpoch: NEW });

      await IncomingMessageHandler.handle(updatedMsg(incoming, NEW));

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(1);
      expect(all[0].label).assertEqual('new');
      expect(all[0].updatedAtEpoch).assertEqual(NEW);
    });

    it('AlarmUpdated with older stamp is ignored', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 7, label: 'keep', updatedAtEpoch: NEW }));
      const incoming = makeAlarm({ id: 7, label: 'stale', updatedAtEpoch: OLD });

      await IncomingMessageHandler.handle(updatedMsg(incoming, OLD));

      const all = await AlarmStore.getAll();
      expect(all[0].label).assertEqual('keep');
      expect(all[0].updatedAtEpoch).assertEqual(NEW);
    });

    it('AlarmUpdated with equal stamp applies (incoming-wins tie-break)', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 7, label: 'old', updatedAtEpoch: NEW }));
      const incoming = makeAlarm({ id: 7, label: 'new', updatedAtEpoch: NEW });

      await IncomingMessageHandler.handle(updatedMsg(incoming, NEW));

      const all = await AlarmStore.getAll();
      expect(all[0].label).assertEqual('new');
    });

    // ─── delete ──────────────────────────────────────────────────────
    it('AlarmDeleted for existing row writes tombstone and removes row', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 8, updatedAtEpoch: OLD }));

      await IncomingMessageHandler.handle(deletedMsg(8, NEW));

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(0);
      const stones = await Tombstones.all();
      expect(stones.length).assertEqual(1);
      expect(stones[0].id).assertEqual(8);
      expect(stones[0].updatedAtEpoch).assertEqual(NEW);
    });

    it('AlarmDeleted for unknown id records tombstone only', 0, async () => {
      await IncomingMessageHandler.handle(deletedMsg(99, NEW));

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(0);
      const stones = await Tombstones.all();
      expect(stones.find(t => t.id === 99) !== undefined).assertTrue();
    });

    it('AlarmDeleted with older stamp records tombstone but keeps the row', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 8, updatedAtEpoch: NEW }));

      await IncomingMessageHandler.handle(deletedMsg(8, OLD));

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(1);
      const stones = await Tombstones.all();
      expect(stones.find(t => t.id === 8) !== undefined).assertTrue();
    });

    // ─── toggle ──────────────────────────────────────────────────────
    it('AlarmToggled flips enabled and re-stamps with incoming epoch', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 9, enabled: false, updatedAtEpoch: OLD }));

      await IncomingMessageHandler.handle(toggledMsg(9, true, NEW));

      const all = await AlarmStore.getAll();
      expect(all[0].enabled).assertTrue();
      expect(all[0].updatedAtEpoch).assertEqual(NEW);
    });

    it('AlarmToggled with older stamp is ignored', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 9, enabled: true, updatedAtEpoch: NEW }));

      await IncomingMessageHandler.handle(toggledMsg(9, false, OLD));

      const all = await AlarmStore.getAll();
      expect(all[0].enabled).assertTrue();
      expect(all[0].updatedAtEpoch).assertEqual(NEW);
    });

    it('AlarmToggled for unknown id is a no-op', 0, async () => {
      await IncomingMessageHandler.handle(toggledMsg(99, true, NEW));

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(0);
    });

    // ─── fired / dismissed / snoozed (non-mutating in 5a) ────────────
    it('AlarmFired and AlarmDismissed and AlarmSnoozed do not mutate AlarmStore', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 4, updatedAtEpoch: OLD }));

      await IncomingMessageHandler.handle({ type: 'alarm_fired', alarmId: 4, updatedAtEpoch: NEW });
      await IncomingMessageHandler.handle({ type: 'alarm_dismissed', alarmId: 4, updatedAtEpoch: NEW });
      await IncomingMessageHandler.handle(
        { type: 'alarm_snoozed', alarmId: 4, updatedAtEpoch: NEW, rescheduleEpoch: NEW + 600_000 });

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(1);
      expect(all[0].updatedAtEpoch).assertEqual(OLD);
    });

    // ─── malformed ───────────────────────────────────────────────────
    it('rejects bad updatedAtEpoch values', 0, async () => {
      await IncomingMessageHandler.handle({ type: 'alarm_added', alarmId: 1, updatedAtEpoch: -1,
        alarm: makeAlarm({ id: 1 }) });
      await IncomingMessageHandler.handle({ type: 'alarm_added', alarmId: 1, updatedAtEpoch: NaN,
        alarm: makeAlarm({ id: 1 }) });

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(0);
    });

    it('AlarmAdded with missing alarm payload is rejected', 0, async () => {
      await IncomingMessageHandler.handle({ type: 'alarm_added', alarmId: 1, updatedAtEpoch: NEW });

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(0);
    });
  });
}
