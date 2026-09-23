import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';

const DOMAIN = 0x0000;

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, 'PadNote', 'EntryAbility created');
  }

  onDestroy(): void {
    hilog.info(DOMAIN, 'PadNote', 'EntryAbility destroyed');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (error: BusinessError) => {
      if (error.code) {
        hilog.error(DOMAIN, 'PadNote', 'Failed to load main page: %{public}s', JSON.stringify(error));
        return;
      }
      hilog.info(DOMAIN, 'PadNote', 'Main page loaded');
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(DOMAIN, 'PadNote', 'Window stage destroyed');
  }

  onForeground(): void {
    hilog.info(DOMAIN, 'PadNote', 'Ability foregrounded');
  }

  onBackground(): void {
    hilog.info(DOMAIN, 'PadNote', 'Ability backgrounded');
  }
}
