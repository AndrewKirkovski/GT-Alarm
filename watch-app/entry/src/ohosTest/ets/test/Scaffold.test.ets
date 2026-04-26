import { describe, it, expect } from '@ohos/hypium';

/**
 * Smoke test for the Phase-0a Hypium scaffold. If this passes the runner is
 * wired correctly. Real suites land in Phase 1.
 */
export default function scaffoldTest(): void {
  describe('Scaffold', () => {
    it('hypium runner is alive', 0, () => {
      expect(1 + 1).assertEqual(2);
    });
  });
}
