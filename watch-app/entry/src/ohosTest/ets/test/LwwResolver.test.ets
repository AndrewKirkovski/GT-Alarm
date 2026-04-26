import { describe, it, expect } from '@ohos/hypium';
import { LwwResolver } from '../../../main/ets/service/LwwResolver';

// Pure-helper tests for LwwResolver — mirror of phone-side LwwResolverTest.kt.
// Covers the live-row LWW rule from docs/sync-architecture.md §2.
// Tombstone tie-break is covered by Tombstones.test.ets.

export default function lwwResolverTest(): void {
  describe('LwwResolver', () => {

    it('shouldApply true when no local row exists', 0, () => {
      expect(LwwResolver.shouldApply(1_000, undefined)).assertTrue();
    });

    it('shouldApply true when incoming is newer than local', 0, () => {
      expect(LwwResolver.shouldApply(1_001, 1_000)).assertTrue();
    });

    it('shouldApply true on tie (sync-architecture incoming-wins tie-break)', 0, () => {
      expect(LwwResolver.shouldApply(1_000, 1_000)).assertTrue();
    });

    it('shouldApply false when incoming is older than local', 0, () => {
      expect(LwwResolver.shouldApply(999, 1_000)).assertFalse();
    });

    it('shouldApply true when both stamps are zero (legitimate fresh write)', 0, () => {
      expect(LwwResolver.shouldApply(0, 0)).assertTrue();
    });
  });
}
