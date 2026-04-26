// Persistent ring buffer of recently deleted alarm IDs with their LWW
// timestamps. Used to suppress resurrection when a stale `alarm_added` /
// `alarm_updated` arrives from the peer device after a local delete.
//
// Capacity: MAX_ENTRIES; retention: RETENTION_MS from updatedAtEpoch. Older
// entries are pruned on every read/write. Mirrors the phone-side
// Tombstones.kt — same wire semantics so a tombstone written on either
// device suppresses a stale peer resurrection of the same id.

import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import { Logger } from '../util/Logger';

const STORE_NAME = 'gt_alarm_tombstones';
const KEY = 'tombstones_v1';
const MAX_ENTRIES = 256;
const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

export interface TombstoneEntry {
  id: number;
  updatedAtEpoch: number;
}

interface RawTombstoneEntry {
  id?: number;
  updatedAtEpoch?: number;
}

export class Tombstones {
  private static store: preferences.Preferences | null = null;
  private static initPromise: Promise<void> | null = null;
  private static writeQueue: Promise<void> = Promise.resolve();

  static init(ctx: common.UIAbilityContext): Promise<void> {
    if (Tombstones.initPromise !== null) return Tombstones.initPromise;
    Tombstones.initPromise = (async (): Promise<void> => {
      try {
        Tombstones.store = await preferences.getPreferences(ctx, STORE_NAME);
        Logger.i('Tombstones initialised');
      } catch (e) {
        Logger.err('Tombstones.init failed', e);
        Tombstones.initPromise = null;
        throw e as Error;
      }
    })();
    return Tombstones.initPromise;
  }

  static add(id: number, updatedAtEpoch: number, now: number = Date.now()): Promise<void> {
    // Defensive: a stamp <= 0 would auto-prune on next read (cutoff is
    // now - 7d, so 0 < cutoff for any wall-clock now > epoch+7d), silently
    // losing the tombstone and allowing peer resurrection. Reject loudly so
    // any future receiver path that forwards a 0-stamp delete from the
    // phone fails fast at this boundary.
    if (!Number.isFinite(updatedAtEpoch) || updatedAtEpoch <= 0) {
      throw new Error('Tombstones.add: updatedAtEpoch must be > 0 (got ' + updatedAtEpoch + ')');
    }
    return Tombstones.enqueue(async () => {
      const existing = await Tombstones.readPruned(now);
      const next = Tombstones.pruneAndAdd(existing, { id, updatedAtEpoch }, now);
      await Tombstones.write(next);
    });
  }

  static async all(now: number = Date.now()): Promise<TombstoneEntry[]> {
    return await Tombstones.readPruned(now);
  }

  static async isTombstoned(id: number, incomingEpoch: number, now: number = Date.now()): Promise<boolean> {
    const entries = await Tombstones.readPruned(now);
    return Tombstones.isTombstonedPure(entries, id, incomingEpoch);
  }

  // Pure helpers — extracted for unit testing.
  static prune(entries: TombstoneEntry[], now: number): TombstoneEntry[] {
    const cutoff = now - RETENTION_MS;
    return entries.filter(e => e.updatedAtEpoch >= cutoff);
  }

  static pruneAndAdd(existing: TombstoneEntry[], incoming: TombstoneEntry, now: number): TombstoneEntry[] {
    const pruned = Tombstones.prune(existing, now).filter(e => e.id !== incoming.id);
    pruned.push(incoming);
    if (pruned.length > MAX_ENTRIES) {
      return pruned.slice(pruned.length - MAX_ENTRIES);
    }
    return pruned;
  }

  static isTombstonedPure(entries: TombstoneEntry[], id: number, incomingEpoch: number): boolean {
    const e = entries.find(x => x.id === id);
    if (e === undefined) return false;
    // Tombstone wins iff its stamp is >= incoming (LWW with ties to local).
    return e.updatedAtEpoch >= incomingEpoch;
  }

  private static enqueue<T>(op: () => Promise<T>): Promise<T> {
    const next: Promise<T> = Tombstones.writeQueue.then(op);
    Tombstones.writeQueue = next.then(() => undefined, () => undefined);
    return next;
  }

  private static async readPruned(now: number): Promise<TombstoneEntry[]> {
    return Tombstones.prune(await Tombstones.readRaw(), now);
  }

  private static async readRaw(): Promise<TombstoneEntry[]> {
    const s = Tombstones.require();
    let raw: string;
    try {
      raw = await s.get(KEY, '[]') as string;
    } catch (e) {
      Logger.warn('Tombstones.readRaw read', e);
      return [];
    }
    try {
      const parsed = JSON.parse(raw) as RawTombstoneEntry[];
      const out: TombstoneEntry[] = [];
      for (const it of parsed) {
        if (typeof it.id === 'number' && typeof it.updatedAtEpoch === 'number') {
          out.push({ id: it.id, updatedAtEpoch: it.updatedAtEpoch });
        }
      }
      return out;
    } catch (e) {
      Logger.warn('Tombstones.readRaw parse — resetting blob', e);
      return [];
    }
  }

  private static async write(entries: TombstoneEntry[]): Promise<void> {
    const s = Tombstones.require();
    try {
      await s.put(KEY, JSON.stringify(entries));
      await s.flush();
    } catch (e) {
      Logger.err('Tombstones.write failed', e);
      throw e as Error;
    }
  }

  private static require(): preferences.Preferences {
    if (Tombstones.store === null) {
      throw new Error('Tombstones not initialised — call init(context) first');
    }
    return Tombstones.store;
  }

  static get MAX(): number { return MAX_ENTRIES; }
  static get RETENTION(): number { return RETENTION_MS; }
}
