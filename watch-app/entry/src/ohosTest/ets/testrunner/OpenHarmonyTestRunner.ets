import { abilityDelegatorRegistry, TestRunner } from '@kit.TestKit';
import { UIAbility, Want } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

let abilityDelegator: abilityDelegatorRegistry.AbilityDelegator;
let abilityDelegatorArguments: abilityDelegatorRegistry.AbilityDelegatorArgs;
const TAG = 'GTAlarmTest';

async function onAbilityCreateCallback(data: UIAbility): Promise<void> {
  hilog.info(0x0000, TAG, 'onAbilityCreate: %{public}s', JSON.stringify(data));
}

async function addAbilityMonitorCallback(err: BusinessError): Promise<void> {
  hilog.info(0x0000, TAG, 'addAbilityMonitor: %{public}s', JSON.stringify(err) ?? '');
}

export default class OpenHarmonyTestRunner implements TestRunner {
  onPrepare(): void {
    hilog.info(0x0000, TAG, 'OpenHarmonyTestRunner.onPrepare');
  }

  async onRun(): Promise<void> {
    hilog.info(0x0000, TAG, 'OpenHarmonyTestRunner.onRun start');
    abilityDelegatorArguments = abilityDelegatorRegistry.getArguments();
    abilityDelegator = abilityDelegatorRegistry.getAbilityDelegator();
    const moduleName = abilityDelegatorArguments.parameters['-m'];
    const bundleName = abilityDelegatorArguments.bundleName;
    const testAbilityName = 'TestAbility';
    const monitor: abilityDelegatorRegistry.AbilityMonitor = {
      abilityName: testAbilityName,
      onAbilityCreate: onAbilityCreateCallback,
      moduleName,
    };
    abilityDelegator.addAbilityMonitor(monitor, addAbilityMonitorCallback);
    const want: Want = {
      bundleName,
      abilityName: testAbilityName,
      moduleName,
    };
    abilityDelegator.startAbility(want, (err: BusinessError) => {
      hilog.info(0x0000, TAG, 'startAbility err: %{public}s', JSON.stringify(err) ?? '');
    });
    hilog.info(0x0000, TAG, 'OpenHarmonyTestRunner.onRun end');
  }
}
