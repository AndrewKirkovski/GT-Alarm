import { describe, it, expect, beforeAll, afterAll } from '@ohos/hypium';
import { DOW, toReminderDays, formatTime } from '../../../main/ets/model/AlarmItem';
import { initI18nContext } from '../../../main/ets/util/I18n';
import { TestContext } from './TestContext';

// AlarmItem is pure ArkTS; the formatTime path delegates to I18n.formatTime
// which calls i18n.System.is24HourClock(). On the wearable emulator this
// returns the device's current setting; we test the deterministic paths
// (DOW + toReminderDays + minute formatting) here and the full formatTime
// branches in I18n.test.ets.

export default function alarmItemTest(): void {
  describe('AlarmItem', () => {

    beforeAll(() => {
      initI18nContext(TestContext.require());
    });

    afterAll(() => {
      // No state to tear down.
    });

    // ─── DOW.contains ────────────────────────────────────────────────
    it('DOW_contains_each_bit_in_ALL', 0, () => {
      expect(DOW.contains(DOW.ALL, DOW.SUN)).assertTrue();
      expect(DOW.contains(DOW.ALL, DOW.MON)).assertTrue();
      expect(DOW.contains(DOW.ALL, DOW.TUE)).assertTrue();
      expect(DOW.contains(DOW.ALL, DOW.WED)).assertTrue();
      expect(DOW.contains(DOW.ALL, DOW.THU)).assertTrue();
      expect(DOW.contains(DOW.ALL, DOW.FRI)).assertTrue();
      expect(DOW.contains(DOW.ALL, DOW.SAT)).assertTrue();
    });

    it('DOW_contains_returns_false_for_NONE', 0, () => {
      expect(DOW.contains(DOW.NONE, DOW.MON)).assertFalse();
      expect(DOW.contains(DOW.NONE, DOW.SUN)).assertFalse();
    });

    it('DOW_contains_isolates_bits_correctly', 0, () => {
      // Only MON+WED set.
      const mask = DOW.MON | DOW.WED;
      expect(DOW.contains(mask, DOW.MON)).assertTrue();
      expect(DOW.contains(mask, DOW.WED)).assertTrue();
      expect(DOW.contains(mask, DOW.TUE)).assertFalse();
      expect(DOW.contains(mask, DOW.FRI)).assertFalse();
    });

    it('DOW_WEEKDAYS_includes_MON_through_FRI_only', 0, () => {
      expect(DOW.contains(DOW.WEEKDAYS, DOW.MON)).assertTrue();
      expect(DOW.contains(DOW.WEEKDAYS, DOW.FRI)).assertTrue();
      expect(DOW.contains(DOW.WEEKDAYS, DOW.SAT)).assertFalse();
      expect(DOW.contains(DOW.WEEKDAYS, DOW.SUN)).assertFalse();
    });

    it('DOW_WEEKENDS_includes_SAT_and_SUN_only', 0, () => {
      expect(DOW.contains(DOW.WEEKENDS, DOW.SAT)).assertTrue();
      expect(DOW.contains(DOW.WEEKENDS, DOW.SUN)).assertTrue();
      expect(DOW.contains(DOW.WEEKENDS, DOW.MON)).assertFalse();
      expect(DOW.contains(DOW.WEEKENDS, DOW.FRI)).assertFalse();
    });

    // ─── DOW.toggle ──────────────────────────────────────────────────
    it('DOW_toggle_flips_bit', 0, () => {
      let m = DOW.NONE;
      m = DOW.toggle(m, DOW.MON);
      expect(DOW.contains(m, DOW.MON)).assertTrue();
      m = DOW.toggle(m, DOW.MON);
      expect(DOW.contains(m, DOW.MON)).assertFalse();
    });

    it('DOW_toggle_preserves_other_bits', 0, () => {
      let m = DOW.MON | DOW.WED;
      m = DOW.toggle(m, DOW.WED); // remove WED
      expect(DOW.contains(m, DOW.MON)).assertTrue();
      expect(DOW.contains(m, DOW.WED)).assertFalse();
    });

    // ─── DOW.todayBit ────────────────────────────────────────────────
    it('DOW_todayBit_for_each_weekday', 0, () => {
      // Date(2024-01-07) is a Sunday (getDay() === 0). Walk forward.
      const sun = new Date(2024, 0, 7);
      expect(DOW.todayBit(sun)).assertEqual(DOW.SUN);

      const mon = new Date(2024, 0, 8);
      expect(DOW.todayBit(mon)).assertEqual(DOW.MON);

      const tue = new Date(2024, 0, 9);
      expect(DOW.todayBit(tue)).assertEqual(DOW.TUE);

      const wed = new Date(2024, 0, 10);
      expect(DOW.todayBit(wed)).assertEqual(DOW.WED);

      const thu = new Date(2024, 0, 11);
      expect(DOW.todayBit(thu)).assertEqual(DOW.THU);

      const fri = new Date(2024, 0, 12);
      expect(DOW.todayBit(fri)).assertEqual(DOW.FRI);

      const sat = new Date(2024, 0, 13);
      expect(DOW.todayBit(sat)).assertEqual(DOW.SAT);
    });

    // ─── toReminderDays ──────────────────────────────────────────────
    it('toReminderDays_NONE_returns_empty_array', 0, () => {
      expect(toReminderDays(DOW.NONE).length).assertEqual(0);
    });

    it('toReminderDays_ALL_returns_seven_days_in_ISO_order', 0, () => {
      // Internal SUN=1..SAT=64 → ISO MON=1..SUN=7. ALL must produce
      // [1,2,3,4,5,6,7] in that exact order.
      const out = toReminderDays(DOW.ALL);
      expect(out.length).assertEqual(7);
      expect(out[0]).assertEqual(1); // MON
      expect(out[1]).assertEqual(2); // TUE
      expect(out[2]).assertEqual(3); // WED
      expect(out[3]).assertEqual(4); // THU
      expect(out[4]).assertEqual(5); // FRI
      expect(out[5]).assertEqual(6); // SAT
      expect(out[6]).assertEqual(7); // SUN
    });

    it('toReminderDays_WEEKDAYS_emits_1_2_3_4_5', 0, () => {
      const out = toReminderDays(DOW.WEEKDAYS);
      expect(JSON.stringify(out)).assertEqual('[1,2,3,4,5]');
    });

    it('toReminderDays_WEEKENDS_emits_6_7', 0, () => {
      const out = toReminderDays(DOW.WEEKENDS);
      expect(JSON.stringify(out)).assertEqual('[6,7]');
    });

    it('toReminderDays_single_MON', 0, () => {
      const out = toReminderDays(DOW.MON);
      expect(JSON.stringify(out)).assertEqual('[1]');
    });

    it('toReminderDays_single_SUN_emits_7_not_1', 0, () => {
      // The internal SUN=1 must land at ISO position 7.
      const out = toReminderDays(DOW.SUN);
      expect(JSON.stringify(out)).assertEqual('[7]');
    });

    it('toReminderDays_mixed_MON_FRI_SUN', 0, () => {
      const mask = DOW.MON | DOW.FRI | DOW.SUN;
      const out = toReminderDays(mask);
      // Output must be in ascending ISO order regardless of bit order.
      expect(JSON.stringify(out)).assertEqual('[1,5,7]');
    });

    // ─── formatTime: minute zero-padding ─────────────────────────────
    // formatTime delegates to I18n.formatTime → is24Hour() (system call).
    // The minute-padding logic ('07' vs '7') is independent of locale and
    // covered here. The 12h/24h branches are exercised in I18n.test.ets
    // where we control the system clock setting via mocked is24Hour
    // fallback.
    it('formatTime_pads_minute_below_ten', 0, () => {
      const out = formatTime(7, 5);
      // Either "07:05" (24h) or "7:05 AM" (12h) — both end in ":05 ..."
      // We only assert the minute formatting.
      expect(out.indexOf(':05')).assertLarger(-1);
    });

    it('formatTime_does_not_pad_minute_at_or_above_ten', 0, () => {
      const out = formatTime(7, 30);
      expect(out.indexOf(':30')).assertLarger(-1);
    });
  });
}
