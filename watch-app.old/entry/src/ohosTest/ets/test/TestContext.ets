// Holder for the TestAbility's UIAbilityContext. TestAbility populates this
// in onCreate (before Hypium runs the suites) so individual test files can
// obtain the context via `TestContext.require()` instead of trying to
// resolve it through the AbilityDelegator (which only exposes Context, not
// UIAbilityContext, and breaks the AlarmStore.init signature).
//
// Single-process, single-test-ability — no concurrent writes to worry about.

import common from '@ohos.app.ability.common';

export class TestContext {
  private static cached: common.UIAbilityContext | null = null;

  static set(ctx: common.UIAbilityContext): void {
    TestContext.cached = ctx;
  }

  static require(): common.UIAbilityContext {
    if (TestContext.cached === null) {
      throw new Error('TestContext not initialised — TestAbility.onCreate did not run?');
    }
    return TestContext.cached;
  }

  static clear(): void {
    TestContext.cached = null;
  }
}
