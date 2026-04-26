import common from '@ohos.app.ability.common';
import i18n from '@ohos.i18n';
import { Logger } from './Logger';

/**
 * Locale + resource helpers. Pages don't directly call resourceManager —
 * everything funnels through here so a missing context or a missing key
 * fails to a sane fallback rather than throwing into an ArkUI render
 * frame and tearing down the page.
 *
 * The context is cached in `EntryAbility.onCreate` (single ability, single
 * process). If a page is ever rendered before that runs (impossible today
 * but cheap to guard), every getter returns the fallback string.
 */

let cachedContext: common.UIAbilityContext | null = null;

export function initI18nContext(ctx: common.UIAbilityContext): void {
  cachedContext = ctx;
}

/**
 * Look up a localized string by resource name. The fallback is returned
 * when the context is not yet initialised OR the key is missing.
 */
export function s(name: string, fallback: string): string {
  if (cachedContext === null) return fallback;
  try {
    return cachedContext.resourceManager.getStringByNameSync(name);
  } catch (e) {
    Logger.warn('I18n.s missing key=' + name, e);
    return fallback;
  }
}

/**
 * Look up a resource string with format args (positional `%1$s`, `%2$s`,
 * ...). Falls back to the bare format string with args appended on error.
 * resourceManager natively supports format args since API 11.
 */
export function sf(name: string, fallback: string, ...args: string[]): string {
  if (cachedContext === null) return fallback;
  try {
    return cachedContext.resourceManager.getStringByNameSync(name, ...args);
  } catch (e) {
    Logger.warn('I18n.sf missing key=' + name, e);
    return fallback;
  }
}

/**
 * Returns true if the system is configured for 24-hour time. Falls back
 * to true (the global default for most non-US locales) if the i18n call
 * fails.
 */
export function is24Hour(): boolean {
  try {
    return i18n.System.is24HourClock();
  } catch (e) {
    Logger.warn('is24HourClock failed', e);
    return true;
  }
}

/**
 * Locale-aware time string. 24h: "07:30". 12h: "7:30 AM" / "12:30 PM"
 * with localized AM/PM markers from string resources.
 */
export function formatTime(hour: number, minute: number): string {
  const m = minute < 10 ? '0' + minute : '' + minute;
  if (is24Hour()) {
    const h = hour < 10 ? '0' + hour : '' + hour;
    return h + ':' + m;
  }
  let h12 = hour % 12;
  if (h12 === 0) h12 = 12;
  const marker = hour < 12 ? s('ampm_am', 'AM') : s('ampm_pm', 'PM');
  return h12 + ':' + m + ' ' + marker;
}

/**
 * DOW bit values are defined in `model/AlarmItem.ets`:
 *   SUN=1, MON=2, TUE=4, WED=8, THU=16, FRI=32, SAT=64.
 * The default chip order is Mon→Sun (matches HarmonyOS publishReminder
 * day numbering, ISO 1..7).
 */
export const DAY_BITS_MON_FIRST: number[] = [2, 4, 8, 16, 32, 64, 1];

/**
 * Translate `i18n.System.getFirstDayOfWeek()` (WeekDay: MON=1..SUN=7) to
 * our internal DOW bit. Falls back to MON when the API throws.
 */
export function firstDayBit(): number {
  try {
    const wd: number = i18n.System.getFirstDayOfWeek();
    switch (wd) {
      case 1: return 2;   // MON
      case 2: return 4;   // TUE
      case 3: return 8;   // WED
      case 4: return 16;  // THU
      case 5: return 32;  // FRI
      case 6: return 64;  // SAT
      case 7: return 1;   // SUN
      default: return 2;
    }
  } catch (e) {
    Logger.warn('getFirstDayOfWeek failed', e);
    return 2;
  }
}

/**
 * Returns the 7 DOW bits in display order, rotated so the locale's
 * first-day-of-week is at index 0. en_US starts on Sunday; most others
 * start on Monday. Caller-cached: rotation is locale-stable per session.
 */
export function orderedDayBits(): number[] {
  const first = firstDayBit();
  const idx = DAY_BITS_MON_FIRST.indexOf(first);
  if (idx < 0) return DAY_BITS_MON_FIRST.slice();
  return DAY_BITS_MON_FIRST.slice(idx).concat(DAY_BITS_MON_FIRST.slice(0, idx));
}

interface DayKeys {
  short: string;
  full: string;
}

/**
 * Resource-key lookup for a DOW bit. Both short ("Mo") and full ("Monday")
 * keys live in `base/element/string.json` plus every locale overlay.
 */
export function dayKeys(bit: number): DayKeys {
  switch (bit) {
    case 2:  return { short: 'day_mon', full: 'day_full_mon' };
    case 4:  return { short: 'day_tue', full: 'day_full_tue' };
    case 8:  return { short: 'day_wed', full: 'day_full_wed' };
    case 16: return { short: 'day_thu', full: 'day_full_thu' };
    case 32: return { short: 'day_fri', full: 'day_full_fri' };
    case 64: return { short: 'day_sat', full: 'day_full_sat' };
    case 1:  return { short: 'day_sun', full: 'day_full_sun' };
    default: return { short: '', full: '' };
  }
}
