import { describe, it, expect, beforeAll } from '@ohos/hypium';
import {
  initI18nContext,
  s,
  sf,
  is24Hour,
  firstDayBit,
  orderedDayBits,
  dayKeys,
  DAY_BITS_MON_FIRST,
} from '../../../main/ets/util/I18n';
import { TestContext } from './TestContext';

// I18n.test exercises the locale + resource-lookup helpers. Some paths
// (is24Hour, firstDayBit) wrap the system @ohos.i18n module — on the
// emulator those calls return real values, which is fine: we only
// assert *shape* (e.g., one of seven valid bits, or boolean). The
// fallback paths that fire when @ohos.i18n throws are exercised via
// the resource-name-missing path in s/sf, which we can drive
// deterministically.

export default function i18nTest(): void {
  describe('I18n', () => {

    beforeAll(() => {
      initI18nContext(TestContext.require());
    });

    // ─── s() / sf() resource lookup ──────────────────────────────────
    it('s_returns_fallback_for_missing_key', 0, () => {
      // Use a deliberately bogus key so resourceManager throws → fallback path.
      const out = s('nonexistent_key_xyz_42', 'FALLBACK');
      expect(out).assertEqual('FALLBACK');
    });

    it('sf_returns_fallback_for_missing_key', 0, () => {
      const out = sf('nonexistent_key_xyz_42', 'FALLBACK', 'arg1');
      expect(out).assertEqual('FALLBACK');
    });

    it('s_returns_real_value_for_known_key', 0, () => {
      // ampm_am exists in every locale's string.json. Real resolution
      // must NOT return the literal fallback sentinel — that would
      // indicate the resourceManager.getStringByNameSync threw and we
      // fell through.
      const out = s('ampm_am', 'FALLBACK_AM_SENTINEL');
      expect(out.length).assertLarger(0);
      expect(out === 'FALLBACK_AM_SENTINEL').assertFalse();
    });

    it('sf_returns_real_value_for_known_key_with_args', 0, () => {
      // a11y_alarm_row_no_label = "Alarm at %1$s, %2$s" — one of the
      // few keys with format placeholders. Must NOT return the fallback.
      const out = sf('a11y_alarm_row_no_label', 'FALLBACK_SF_SENTINEL', '07:30', 'Mon');
      expect(out.length).assertLarger(0);
      expect(out === 'FALLBACK_SF_SENTINEL').assertFalse();
      // Both args must appear in the rendered output.
      expect(out.indexOf('07:30')).assertLarger(-1);
      expect(out.indexOf('Mon')).assertLarger(-1);
    });

    // ─── is24Hour ────────────────────────────────────────────────────
    it('is24Hour_returns_boolean', 0, () => {
      const out = is24Hour();
      expect(typeof out === 'boolean').assertTrue();
    });

    // ─── firstDayBit ─────────────────────────────────────────────────
    it('firstDayBit_returns_a_valid_DOW_bit', 0, () => {
      const out = firstDayBit();
      // Must be one of the 7 valid DOW bits.
      expect(DAY_BITS_MON_FIRST.indexOf(out) >= 0).assertTrue();
    });

    // ─── orderedDayBits ──────────────────────────────────────────────
    it('orderedDayBits_has_seven_unique_bits', 0, () => {
      const out = orderedDayBits();
      expect(out.length).assertEqual(7);
      // All bits unique.
      const set = new Set(out);
      expect(set.size).assertEqual(7);
      // All bits are valid DOW bits.
      for (const b of out) {
        expect(DAY_BITS_MON_FIRST.indexOf(b) >= 0).assertTrue();
      }
    });

    it('orderedDayBits_starts_at_firstDayBit', 0, () => {
      const out = orderedDayBits();
      expect(out[0]).assertEqual(firstDayBit());
    });

    it('orderedDayBits_preserves_relative_order', 0, () => {
      // Whatever the rotation, MON→TUE→WED→THU→FRI→SAT→SUN sequence is
      // preserved cyclically (defined by DAY_BITS_MON_FIRST).
      const out = orderedDayBits();
      // Every consecutive pair (i, i+1) must be adjacent in the
      // canonical Mon-first cycle.
      for (let i = 0; i < out.length - 1; i++) {
        const idxA = DAY_BITS_MON_FIRST.indexOf(out[i]);
        const idxB = DAY_BITS_MON_FIRST.indexOf(out[i + 1]);
        const expected = (idxA + 1) % 7;
        expect(idxB).assertEqual(expected);
      }
    });

    // ─── dayKeys ─────────────────────────────────────────────────────
    it('dayKeys_covers_all_seven_DOW_bits', 0, () => {
      // SUN=1, MON=2, TUE=4, WED=8, THU=16, FRI=32, SAT=64.
      const sun = dayKeys(1);
      expect(sun.short).assertEqual('day_sun');
      expect(sun.full).assertEqual('day_full_sun');

      expect(dayKeys(2).short).assertEqual('day_mon');
      expect(dayKeys(2).full).assertEqual('day_full_mon');

      expect(dayKeys(4).short).assertEqual('day_tue');
      expect(dayKeys(8).short).assertEqual('day_wed');
      expect(dayKeys(16).short).assertEqual('day_thu');
      expect(dayKeys(32).short).assertEqual('day_fri');
      expect(dayKeys(64).short).assertEqual('day_sat');
    });

    it('dayKeys_returns_empty_strings_for_invalid_bit', 0, () => {
      const out = dayKeys(0);
      expect(out.short).assertEqual('');
      expect(out.full).assertEqual('');

      const out2 = dayKeys(128);
      expect(out2.short).assertEqual('');
      expect(out2.full).assertEqual('');
    });
  });
}
