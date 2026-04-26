import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { abilityDelegatorRegistry } from '@kit.TestKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { Hypium } from '@ohos/hypium';
import testsuite from '../test/List.test';
import { TestContext } from '../test/TestContext';

const TAG = 'GTAlarmTest';
const ON_DESTROY_ERROR = -2;

export default class TestAbility extends UIAbility {
  abilityDelegator: abilityDelegatorRegistry.AbilityDelegator =
    abilityDelegatorRegistry.getAbilityDelegator();

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(0x0000, TAG, 'TestAbility.onCreate launchParam=%{public}s', JSON.stringify(launchParam) ?? '');
    // Stash the UIAbilityContext so test suites that need preferences /
    // resourceManager can pick it up via TestContext.require().
    TestContext.set(this.context);
    const args = abilityDelegatorRegistry.getArguments();
    Hypium.hypiumTest(this.abilityDelegator, args, testsuite);
  }

  onDestroy(): void {
    hilog.info(0x0000, TAG, 'TestAbility.onDestroy');
    this.abilityDelegator.finishTest('TestAbility onDestroy unexpectedly!', ON_DESTROY_ERROR, () => {});
  }

  onWindowStageCreate(stage: window.WindowStage): void {
    stage.loadContent('testability/pages/Index', (err) => {
      if (err.code) {
        hilog.error(0x0000, TAG, 'loadContent failed %{public}s', JSON.stringify(err) ?? '');
        return;
      }
      hilog.info(0x0000, TAG, 'loadContent ok');
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(0x0000, TAG, 'TestAbility.onWindowStageDestroy');
  }
}
