// GT Alarm — watch alarm data model.
// Uses the same daysOfWeek bitmask as the Android app at the domain level,
// converted to HarmonyOS's Mon=1..Sun=7 number[] format at the ReminderRequest
// boundary (see `toReminderDays`).

import { formatTime as i18nFormatTime } from '../util/I18n';

export interface AlarmItem {
  id: number;
  label: string;
  hour: number;
  minute: number;
  daysOfWeek: number; // bitmask — see DOW below
  enabled: boolean;
  reminderId?: number; // returned by reminderAgentManager.publishReminder
  // Last-write-wins stamp (epoch millis) for cross-device LWW conflict
  // resolution per docs/sync-architecture.md §2/§5. Stamped by AlarmStore on
  // every mutation; legacy rows without the field default to Date.now() on
  // first read.
  updatedAtEpoch: number;
}

export class DOW {
  static readonly SUN: number = 1;
  static readonly MON: number = 2;
  static readonly TUE: number = 4;
  static readonly WED: number = 8;
  static readonly THU: number = 16;
  static readonly FRI: number = 32;
  static readonly SAT: number = 64;
  static readonly NONE: number = 0;
  static readonly ALL: number = 127;
  static readonly WEEKDAYS: number = 2 | 4 | 8 | 16 | 32;
  static readonly WEEKENDS: number = 1 | 64;

  static contains(mask: number, day: number): boolean {
    return (mask & day) === day;
  }

  static toggle(mask: number, day: number): number {
    return mask ^ day;
  }

  /**
   * DOW bit for today, derived from `Date.getDay()` (Sun=0..Sat=6) which
   * happens to align with our bitmask via `1 << getDay()`.
   */
  static todayBit(now: Date): number {
    return 1 << now.getDay();
  }
}

/**
 * Convert the internal bitmask (Sun=1..Sat=64) to HarmonyOS's
 * ReminderRequest.daysOfWeek format: number[] with values 1..7 where Mon=1
 * through Sun=7 (ISO-like).
 */
export function toReminderDays(mask: number): number[] {
  const out: number[] = [];
  if (DOW.contains(mask, DOW.MON)) out.push(1);
  if (DOW.contains(mask, DOW.TUE)) out.push(2);
  if (DOW.contains(mask, DOW.WED)) out.push(3);
  if (DOW.contains(mask, DOW.THU)) out.push(4);
  if (DOW.contains(mask, DOW.FRI)) out.push(5);
  if (DOW.contains(mask, DOW.SAT)) out.push(6);
  if (DOW.contains(mask, DOW.SUN)) out.push(7);
  return out;
}

// Locale-aware (12h/24h + localized AM/PM) formatter. Delegates to
// `util/I18n.formatTime` so existing imports of `formatTime` from this
// module keep working without churn.
export function formatTime(hour: number, minute: number): string {
  return i18nFormatTime(hour, minute);
}
