import { describe, it, expect } from '@ohos/hypium';
import { Tombstones, TombstoneEntry } from '../../../main/ets/service/Tombstones';

// Pure-helper tests for Tombstones (prune / pruneAndAdd / isTombstonedPure).
// The persistent path needs preferences so it lives in the Android-mirrored
// integration suite, not here. The pure-logic tests cover the LWW semantics
// that determine whether a peer message is suppressed or applied.

const NOW = 10_000_000;

export default function tombstonesTest(): void {
  describe('Tombstones', () => {

    it('prune drops entries older than retention window', 0, () => {
      const entries: TombstoneEntry[] = [
        { id: 1, updatedAtEpoch: NOW - Tombstones.RETENTION - 1 },  // expired
        { id: 2, updatedAtEpoch: NOW - Tombstones.RETENTION },       // edge: keep
        { id: 3, updatedAtEpoch: NOW },                              // fresh
      ];
      const pruned = Tombstones.prune(entries, NOW);
      expect(pruned.length).assertEqual(2);
      expect(pruned[0].id).assertEqual(2);
      expect(pruned[1].id).assertEqual(3);
    });

    it('pruneAndAdd inserts new entry', 0, () => {
      const result = Tombstones.pruneAndAdd([], { id: 5, updatedAtEpoch: NOW }, NOW);
      expect(result.length).assertEqual(1);
      expect(result[0].id).assertEqual(5);
      expect(result[0].updatedAtEpoch).assertEqual(NOW);
    });

    it('pruneAndAdd dedupes by id, keeping the new stamp', 0, () => {
      const existing: TombstoneEntry[] = [{ id: 7, updatedAtEpoch: NOW - 1000 }];
      const result = Tombstones.pruneAndAdd(existing, { id: 7, updatedAtEpoch: NOW }, NOW);
      expect(result.length).assertEqual(1);
      expect(result[0].updatedAtEpoch).assertEqual(NOW);
    });

    it('pruneAndAdd caps at MAX, evicting oldest insertion (head of list)', 0, () => {
      const full: TombstoneEntry[] = [];
      for (let i = 1; i <= Tombstones.MAX; i++) {
        full.push({ id: i, updatedAtEpoch: NOW - i });
      }
      const result = Tombstones.pruneAndAdd(full, { id: 9999, updatedAtEpoch: NOW }, NOW);
      expect(result.length).assertEqual(Tombstones.MAX);
      expect(result[result.length - 1].id).assertEqual(9999);
      // First inserted entry (id=1) is the head and gets evicted.
      expect(result.find(e => e.id === 1) === undefined).assertTrue();
      expect(result[0].id).assertEqual(2);
    });

    it('isTombstonedPure returns false for unknown id', 0, () => {
      expect(Tombstones.isTombstonedPure([], 1, NOW)).assertFalse();
    });

    it('isTombstonedPure blocks resurrection when stamp equals incoming', 0, () => {
      const entries: TombstoneEntry[] = [{ id: 5, updatedAtEpoch: NOW }];
      expect(Tombstones.isTombstonedPure(entries, 5, NOW)).assertTrue();
    });

    it('isTombstonedPure blocks resurrection when stamp newer than incoming', 0, () => {
      const entries: TombstoneEntry[] = [{ id: 5, updatedAtEpoch: NOW }];
      expect(Tombstones.isTombstonedPure(entries, 5, NOW - 1)).assertTrue();
    });

    it('isTombstonedPure allows fresh peer write when its stamp is newer', 0, () => {
      const entries: TombstoneEntry[] = [{ id: 5, updatedAtEpoch: NOW - 1 }];
      expect(Tombstones.isTombstonedPure(entries, 5, NOW)).assertFalse();
    });
  });
}
