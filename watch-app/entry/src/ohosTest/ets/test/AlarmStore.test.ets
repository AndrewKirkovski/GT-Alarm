import { describe, it, expect, beforeAll, beforeEach, afterAll } from '@ohos/hypium';
import preferences from '@ohos.data.preferences';
import { AlarmStore } from '../../../main/ets/service/AlarmStore';
import { AlarmItem } from '../../../main/ets/model/AlarmItem';
import { TestContext } from './TestContext';

// AlarmStore is backed by @ohos.data.preferences. We initialise it once
// against the test ability's UIAbilityContext (single static singleton —
// re-init returns the cached promise). We CLEAR the same preferences blob
// before each test so suite ordering doesn't leak state.
//
// We deliberately use the SAME store name as production ("gt_alarm_store"
// + key "alarms_v1"). Reasoning: we test the real persistence path. The
// emulator's app_data directory is wiped by `aa test` between runs, and
// we explicitly clear the blob in beforeEach. Trying to override the
// store name would require a test-only field in AlarmStore that we'd
// have to maintain forever.

const STORE_NAME = 'gt_alarm_store';
const KEY = 'alarms_v1';

async function clearStore(): Promise<void> {
  try {
    const ctx = TestContext.require();
    const s = await preferences.getPreferences(ctx, STORE_NAME);
    await s.put(KEY, '[]');
    await s.flush();
  } catch (e) {
    // Best-effort cleanup; surface so suite hooks don't silently corrupt
    // the next test's starting state.
    throw e as Error;
  }
}

async function writeRaw(raw: string): Promise<void> {
  try {
    const ctx = TestContext.require();
    const s = await preferences.getPreferences(ctx, STORE_NAME);
    await s.put(KEY, raw);
    await s.flush();
  } catch (e) {
    throw e as Error;
  }
}

function makeAlarm(over: Partial<AlarmItem>): AlarmItem {
  return {
    id: over.id ?? 0,
    label: over.label ?? '',
    hour: over.hour ?? 7,
    minute: over.minute ?? 30,
    daysOfWeek: over.daysOfWeek ?? 0,
    enabled: over.enabled ?? true,
    reminderId: over.reminderId,
    updatedAtEpoch: over.updatedAtEpoch ?? Date.now(),
  };
}

export default function alarmStoreTest(): void {
  describe('AlarmStore', () => {

    beforeAll(async () => {
      await AlarmStore.init(TestContext.require());
    });

    beforeEach(async () => {
      await clearStore();
    });

    afterAll(async () => {
      await clearStore();
    });

    // ─── validate() rejection (sync throw before queue) ──────────────
    it('validate_rejects_negative_hour', 0, async () => {
      let threw = false;
      try {
        await AlarmStore.add(makeAlarm({ hour: -1 }));
      } catch (e) {
        threw = true;
      }
      expect(threw).assertTrue();
    });

    it('validate_rejects_hour_24', 0, async () => {
      let threw = false;
      try {
        await AlarmStore.add(makeAlarm({ hour: 24 }));
      } catch (e) {
        threw = true;
      }
      expect(threw).assertTrue();
    });

    it('validate_rejects_minute_60', 0, async () => {
      let threw = false;
      try {
        await AlarmStore.add(makeAlarm({ minute: 60 }));
      } catch (e) {
        threw = true;
      }
      expect(threw).assertTrue();
    });

    it('validate_rejects_negative_daysOfWeek', 0, async () => {
      let threw = false;
      try {
        await AlarmStore.add(makeAlarm({ daysOfWeek: -1 }));
      } catch (e) {
        threw = true;
      }
      expect(threw).assertTrue();
    });

    it('validate_rejects_daysOfWeek_above_127', 0, async () => {
      let threw = false;
      try {
        await AlarmStore.add(makeAlarm({ daysOfWeek: 128 }));
      } catch (e) {
        threw = true;
      }
      expect(threw).assertTrue();
    });

    it('validate_rejects_label_over_80_chars', 0, async () => {
      let threw = false;
      const longLabel = 'x'.repeat(81);
      try {
        await AlarmStore.add(makeAlarm({ label: longLabel }));
      } catch (e) {
        threw = true;
      }
      expect(threw).assertTrue();
    });

    // ─── add() ───────────────────────────────────────────────────────
    it('add_assigns_id_when_caller_passes_zero', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 0, hour: 8 }));
      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(1);
      expect(all[0].id > 0).assertTrue();
      expect(all[0].hour).assertEqual(8);
    });

    it('add_rejects_duplicate_explicit_id', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 42, hour: 6 }));
      let threw = false;
      try {
        await AlarmStore.add(makeAlarm({ id: 42, hour: 7 }));
      } catch (e) {
        threw = true;
      }
      expect(threw).assertTrue();
      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(1);
      expect(all[0].hour).assertEqual(6); // first one survived
    });

    it('add_assigns_max_plus_one_when_id_zero', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 5, hour: 6 }));
      await AlarmStore.add(makeAlarm({ id: 0, hour: 7 })); // should be 6
      const all = await AlarmStore.getAll();
      const ids = all.map(a => a.id).sort((a, b) => a - b);
      expect(ids[0]).assertEqual(5);
      expect(ids[1]).assertEqual(6);
    });

    // ─── update() ────────────────────────────────────────────────────
    it('update_throws_on_missing_id', 0, async () => {
      let threw = false;
      try {
        await AlarmStore.update(makeAlarm({ id: 999, hour: 9 }));
      } catch (e) {
        threw = true;
      }
      expect(threw).assertTrue();
    });

    it('update_rejects_invalid_field_via_validate', 0, async () => {
      // update() also runs validate() before dispatching to the queue.
      // Without this assertion, a hypothetical refactor could skip
      // validation on update and let bad values reach storage.
      await AlarmStore.add(makeAlarm({ id: 1, hour: 6 }));
      let threw = false;
      try {
        await AlarmStore.update(makeAlarm({ id: 1, hour: 25 })); // invalid hour
      } catch (e) {
        threw = true;
      }
      expect(threw).assertTrue();
      // Original alarm must be unmodified.
      const all = await AlarmStore.getAll();
      expect(all[0].hour).assertEqual(6);
    });

    it('update_replaces_in_place', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 1, hour: 6, label: 'old' }));
      await AlarmStore.update(makeAlarm({ id: 1, hour: 8, label: 'new' }));
      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(1);
      expect(all[0].hour).assertEqual(8);
      expect(all[0].label).assertEqual('new');
    });

    // ─── delete() ────────────────────────────────────────────────────
    it('delete_no_op_when_id_absent', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 1 }));
      // Should NOT throw, should NOT remove anything.
      await AlarmStore.delete(999);
      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(1);
    });

    it('delete_removes_target_only', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 1, hour: 6 }));
      await AlarmStore.add(makeAlarm({ id: 2, hour: 7 }));
      await AlarmStore.delete(1);
      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(1);
      expect(all[0].id).assertEqual(2);
    });

    // ─── setEnabled() ────────────────────────────────────────────────
    it('setEnabled_no_op_when_id_absent', 0, async () => {
      // Should NOT throw.
      await AlarmStore.setEnabled(999, false);
    });

    it('setEnabled_flips_existing', 0, async () => {
      await AlarmStore.add(makeAlarm({ id: 1, enabled: true }));
      await AlarmStore.setEnabled(1, false);
      const all = await AlarmStore.getAll();
      expect(all[0].enabled).assertFalse();
    });

    // ─── getAll() corruption recovery ────────────────────────────────
    it('getAll_resets_blob_on_JSON_parse_failure', 0, async () => {
      await writeRaw('this-is-not-json{{{');
      const out = await AlarmStore.getAll();
      expect(out.length).assertEqual(0);
      // After the recovery path, a subsequent read should also be clean.
      const out2 = await AlarmStore.getAll();
      expect(out2.length).assertEqual(0);
    });

    // ─── write queue serialisation ───────────────────────────────────
    it('write_queue_serialises_concurrent_adds', 0, async () => {
      // Fire 5 adds in parallel. Without serialisation, two would race the
      // read-modify-write cycle and one would clobber the other → fewer
      // than 5 ids would land. Both length AND id-set must hold.
      const promises: Promise<void>[] = [];
      for (let i = 1; i <= 5; i++) {
        promises.push(AlarmStore.add(makeAlarm({ id: i, hour: 5 + i })));
      }
      await Promise.all(promises);
      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(5);
      const ids = all.map(a => a.id).sort((a, b) => a - b);
      expect(JSON.stringify(ids)).assertEqual('[1,2,3,4,5]');
    });

    it('write_queue_does_not_stall_after_failed_op', 0, async () => {
      // A failed mutation must not block subsequent ops. Trigger a duplicate-id
      // failure mid-queue and verify a later add still lands.
      await AlarmStore.add(makeAlarm({ id: 1 }));

      const failPromise = AlarmStore.add(makeAlarm({ id: 1 })) // duplicate → reject
        .catch(() => undefined);
      const okPromise = AlarmStore.add(makeAlarm({ id: 2 }));
      await Promise.all([failPromise, okPromise]);

      const all = await AlarmStore.getAll();
      expect(all.length).assertEqual(2);
    });
  });
}
