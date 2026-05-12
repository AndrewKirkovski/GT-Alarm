import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import { AlarmItem } from '../model/AlarmItem';
import { Logger } from '../util/Logger';

const STORE_NAME = 'gt_alarm_store';
const KEY = 'alarms_v1';

/**
 * Persistent storage for alarms, backed by @ohos.data.preferences. Alarms are
 * serialised as a single JSON array under one key to keep writes atomic.
 *
 * All mutators serialize through `writeQueue` so concurrent calls (e.g. two
 * rapid toggles) cannot interleave their read-modify-write cycles and lose
 * an update.
 */
export class AlarmStore {
  private static store: preferences.Preferences | null = null;
  private static initPromise: Promise<void> | null = null;
  // Tail of the write queue. Every mutator chains onto this so they execute
  // strictly serially. The queue swallows errors per-link so one failure
  // doesn't break subsequent operations.
  private static writeQueue: Promise<void> = Promise.resolve();

  static init(ctx: common.UIAbilityContext): Promise<void> {
    if (AlarmStore.initPromise !== null) return AlarmStore.initPromise;
    AlarmStore.initPromise = (async (): Promise<void> => {
      try {
        AlarmStore.store = await preferences.getPreferences(ctx, STORE_NAME);
        Logger.i('AlarmStore initialised');
      } catch (e) {
        Logger.err('AlarmStore.init failed', e);
        AlarmStore.initPromise = null;
        throw e as Error;
      }
    })();
    return AlarmStore.initPromise;
  }

  static async getAll(): Promise<AlarmItem[]> {
    const s = AlarmStore.require();
    let raw: string;
    try {
      raw = await s.get(KEY, '[]') as string;
    } catch (e) {
      Logger.warn('AlarmStore.getAll read', e);
      return [];
    }
    try {
      const parsed = JSON.parse(raw) as AlarmItem[];
      // Backfill: rows persisted before LWW (Phase 4) lack updatedAtEpoch.
      // Default to Date.now() on first read so subsequent compares work.
      const now = Date.now();
      for (const it of parsed) {
        if (typeof it.updatedAtEpoch !== 'number' || !Number.isFinite(it.updatedAtEpoch)) {
          it.updatedAtEpoch = now;
        }
      }
      return parsed;
    } catch (e) {
      Logger.warn('AlarmStore.getAll parse — resetting blob', e);
      // Actually reset the corrupted blob so we don't parse-fail forever.
      try {
        await s.put(KEY, '[]');
        await s.flush();
      } catch (resetErr) {
        Logger.err('AlarmStore.getAll: reset failed', resetErr);
      }
      return [];
    }
  }

  static add(item: AlarmItem): Promise<void> {
    if (item.updatedAtEpoch === 0) item.updatedAtEpoch = Date.now();
    AlarmStore.validate(item);
    return AlarmStore.enqueue(async () => {
      const items = await AlarmStore.getAll();
      if (item.id === 0) {
        item.id = AlarmStore.nextId(items);
      } else if (items.find(a => a.id === item.id) !== undefined) {
        throw new Error('AlarmStore.add: duplicate id ' + item.id);
      }
      items.push(item);
      await AlarmStore.writeAll(items);
    });
  }

  static update(item: AlarmItem): Promise<void> {
    if (item.updatedAtEpoch === 0) item.updatedAtEpoch = Date.now();
    AlarmStore.validate(item);
    return AlarmStore.enqueue(async () => {
      const items = await AlarmStore.getAll();
      const idx = items.findIndex(a => a.id === item.id);
      if (idx < 0) {
        throw new Error('AlarmStore.update: id ' + item.id + ' not found');
      }
      items[idx] = item;
      await AlarmStore.writeAll(items);
    });
  }

  static delete(id: number): Promise<void> {
    return AlarmStore.enqueue(async () => {
      const items = await AlarmStore.getAll();
      if (items.find(a => a.id === id) === undefined) {
        Logger.w('AlarmStore.delete: id ' + id + ' not present, no-op');
        return;
      }
      const filtered = items.filter(a => a.id !== id);
      await AlarmStore.writeAll(filtered);
    });
  }

  static setEnabled(id: number, enabled: boolean): Promise<void> {
    return AlarmStore.enqueue(async () => {
      const items = await AlarmStore.getAll();
      const idx = items.findIndex(a => a.id === id);
      if (idx < 0) {
        Logger.w('AlarmStore.setEnabled: id ' + id + ' not found');
        return;
      }
      items[idx].enabled = enabled;
      items[idx].updatedAtEpoch = Date.now();
      await AlarmStore.writeAll(items);
    });
  }

  /**
   * Chain `op` onto the write queue so it runs strictly after every
   * previously enqueued mutation. The queue tail (`writeQueue`) is a
   * never-rejecting Promise so one op's failure can't stall subsequent
   * ops or hang their callers. The caller receives the original op
   * promise, so success/failure semantics are preserved.
   */
  private static enqueue<T>(op: () => Promise<T>): Promise<T> {
    const next: Promise<T> = AlarmStore.writeQueue.then(op);
    AlarmStore.writeQueue = next.then(() => undefined, () => undefined);
    return next;
  }

  /**
   * Validate hour/minute/daysOfWeek/label ranges before persisting. Throws
   * on out-of-range so a UI bug doesn't quietly persist garbage that
   * publishReminder would later reject with an opaque code.
   */
  private static validate(item: AlarmItem): void {
    if (!Number.isInteger(item.hour) || item.hour < 0 || item.hour > 23) {
      throw new Error('AlarmStore.validate: bad hour ' + item.hour);
    }
    if (!Number.isInteger(item.minute) || item.minute < 0 || item.minute > 59) {
      throw new Error('AlarmStore.validate: bad minute ' + item.minute);
    }
    if (!Number.isInteger(item.daysOfWeek) || item.daysOfWeek < 0 || item.daysOfWeek > 127) {
      throw new Error('AlarmStore.validate: bad daysOfWeek ' + item.daysOfWeek);
    }
    if (item.label.length > 80) {
      throw new Error('AlarmStore.validate: label too long (' + item.label.length + ')');
    }
    if (!Number.isFinite(item.updatedAtEpoch) || item.updatedAtEpoch < 0) {
      throw new Error('AlarmStore.validate: bad updatedAtEpoch ' + item.updatedAtEpoch);
    }
  }

  private static async writeAll(items: AlarmItem[]): Promise<void> {
    const s = AlarmStore.require();
    try {
      await s.put(KEY, JSON.stringify(items));
    } catch (e) {
      Logger.err('AlarmStore.writeAll put', e);
      throw e as Error;
    }
    try {
      await s.flush();
    } catch (e) {
      Logger.err('AlarmStore.writeAll flush', e);
      // Flush failure means the value is in memory only — surface it.
      throw e as Error;
    }
  }

  private static nextId(items: AlarmItem[]): number {
    let max = 0;
    for (const it of items) {
      if (it.id > max) max = it.id;
    }
    return max + 1;
  }

  private static require(): preferences.Preferences {
    if (AlarmStore.store === null) {
      throw new Error('AlarmStore not initialised — call init(context) first');
    }
    return AlarmStore.store;
  }
}
