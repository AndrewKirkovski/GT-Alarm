import vibrator from '@ohos.vibrator';
import { ArcButton, ArcButtonOptions, ArcButtonStyleMode, ArcButtonPosition } from '@kit.ArkUI';
import { AlarmItem, formatTime } from '../model/AlarmItem';
import { AlarmStore } from '../service/AlarmStore';
import { ReminderService } from '../service/ReminderService';
import { WearBridgeStub } from '../util/WearBridgeStub';
import { Logger } from '../util/Logger';
import { s } from '../util/I18n';
import { safeResourceColor } from '../util/ColorUtil';
import { bumpRefresh, consumeFiredAlarmId, SAFE_HORIZONTAL_VP } from '../util/AppState';

/**
 * Fullscreen alarm ringing UI. Routed here when `maxScreenWantAgent` fires
 * from a published reminder — EntryAbility reads `want.parameters.alarmId`
 * and pushes this page onto the Navigation stack.
 *
 * The system Reminder Agent itself plays ring/vibration for `ringDuration`
 * seconds (60 in our config). This page drives the additional continuous
 * vibration pattern + the big Dismiss/Snooze buttons.
 */
@Component
export struct AlarmRingPage {
  @Consume('pathStack') pathStack: NavPathStack;
  @StorageProp('gtalarm.safeTopPx') safeTopPx: number = 0;
  @StorageProp('gtalarm.safeBottomPx') safeBottomPx: number = 0;
  // Subscribe to fire-tick so a second alarm firing while we're already
  // ringing swaps in the new alarm's data instead of being dropped.
  @StorageLink('gtalarm.firedAlarmTick') @Watch('onAlarmFired') firedAlarmTick: number = 0;

  @State alarm: AlarmItem | null = null;
  @State currentTime: string = '';
  private timer: number = -1;
  private vibrationActive: boolean = false;
  // Token discriminates concurrent loadAlarm calls so a slow first load
  // can't overwrite a fast second load when a 2nd alarm fires while the
  // page is still settling.
  private loadToken: number = 0;

  async aboutToAppear(): Promise<void> {
    const topIdx: number = this.pathStack.size() - 1;
    if (topIdx < 0) return;
    const rawParam = this.pathStack.getParamByIndex(topIdx) as Object | undefined;
    if (typeof rawParam !== 'number') return;
    const alarmId: number = rawParam as number;
    await this.loadAlarm(alarmId);

    this.updateClock();
    // 30s tick — minute precision is enough for the small clock and saves
    // battery vs the 1Hz tick a watch would otherwise burn.
    this.timer = setInterval(() => this.updateClock(), 30000);

    this.startVibration();
  }

  private async loadAlarm(alarmId: number): Promise<void> {
    const myToken = ++this.loadToken;
    try {
      const items = await AlarmStore.getAll();
      // Discard stale loads: a newer load may have started while we were
      // awaiting storage. Without this, a slow first load would overwrite
      // the result of a faster second load (e.g. when alarm B fires
      // before alarm A's data has finished loading).
      if (myToken !== this.loadToken) {
        Logger.i('AlarmRingPage: discarding stale load for id=' + alarmId);
        return;
      }
      const found = items.find(a => a.id === alarmId);
      if (found) {
        this.alarm = found;
        Logger.i('AlarmRingPage: showing alarm id=' + alarmId);
      } else {
        // Alarm was deleted between fire and consumption. Blank out so
        // dismiss/snooze handlers don't operate on stale state.
        this.alarm = null;
        Logger.w('AlarmRingPage: alarm id=' + alarmId + ' not found');
      }
    } catch (e) {
      Logger.warn('AlarmRingPage load', e);
    }
  }

  private async onAlarmFired(): Promise<void> {
    const newId = consumeFiredAlarmId();
    if (newId === 0) return;
    if (this.alarm !== null && this.alarm.id === newId) return;
    Logger.i('AlarmRingPage: superseded by alarm id=' + newId);
    await this.loadAlarm(newId);
  }

  aboutToDisappear(): void {
    if (this.timer !== -1) {
      clearInterval(this.timer);
      this.timer = -1;
    }
    this.stopVibration();
  }

  private updateClock(): void {
    const now = new Date();
    this.currentTime = formatTime(now.getHours(), now.getMinutes());
  }

  private startVibration(): void {
    this.vibrationActive = true;
    this.vibrateLoop();
  }

  private sleepHandle: number = -1;
  private sleepResolver: (() => void) | null = null;

  private async vibrateLoop(): Promise<void> {
    while (this.vibrationActive) {
      try {
        await vibrator.startVibration(
          { type: 'time', duration: 800 },
          { usage: 'alarm' },
        );
      } catch (e) {
        Logger.warn('vibrate', e);
        this.vibrationActive = false;
        return;
      }
      // Cancellable sleep — stopVibration calls the stored resolver so we
      // exit the loop immediately rather than hanging up to 1.2s.
      await new Promise<void>(resolve => {
        this.sleepResolver = resolve;
        this.sleepHandle = setTimeout(() => {
          this.sleepHandle = -1;
          this.sleepResolver = null;
          resolve();
        }, 1200);
      });
    }
  }

  private stopVibration(): void {
    this.vibrationActive = false;
    if (this.sleepHandle !== -1) {
      clearTimeout(this.sleepHandle);
      this.sleepHandle = -1;
    }
    if (this.sleepResolver !== null) {
      this.sleepResolver();
      this.sleepResolver = null;
    }
    try {
      vibrator.stopVibration();
    } catch (e) {
      Logger.warn('stopVibration', e);
    }
  }

  private async onDismiss(): Promise<void> {
    if (this.alarm !== null) {
      try {
        WearBridgeStub.notifyDismissed(this.alarm.id);
      } catch (e) {
        Logger.warn('WearBridge notify dismiss', e);
      }
      // One-shot alarms auto-disable after firing so they don't appear
      // armed in the list. Repeating alarms stay armed (the system
      // re-publishes internally for us on daysOfWeek).
      if (this.alarm.daysOfWeek === 0) {
        if (this.alarm.reminderId !== undefined) {
          try {
            await ReminderService.cancelAlarm(this.alarm.reminderId);
          } catch (e) {
            Logger.warn('onDismiss cancel', e);
          }
        }
        const updated: AlarmItem = {
          id: this.alarm.id,
          label: this.alarm.label,
          hour: this.alarm.hour,
          minute: this.alarm.minute,
          daysOfWeek: this.alarm.daysOfWeek,
          enabled: false,
          reminderId: undefined,
          updatedAtEpoch: Date.now(),
        };
        try {
          await AlarmStore.update(updated);
        } catch (e) {
          Logger.err('onDismiss update', e);
        }
        bumpRefresh();
      }
    }
    this.pathStack.pop();
  }

  private onSnooze(): void {
    if (this.alarm !== null) {
      try {
        // OS reminder agent re-fires after 10 min (snoozeTimes=3, timeInterval=600
        // configured at publish time); communicate the same hint to the phone.
        const SNOOZE_MS = 10 * 60 * 1000;
        WearBridgeStub.notifySnoozed(this.alarm.id, Date.now() + SNOOZE_MS);
      } catch (e) {
        Logger.warn('WearBridge notify snooze', e);
      }
    }
    // The Reminder Agent manages snoozing itself (snoozeTimes=3, interval=10min
    // configured at publish time). Tapping Snooze here just dismisses the
    // current ring; the system re-fires after 10 min up to 3 times.
    this.pathStack.pop();
  }

  build() {
    NavDestination() {
      // NavDestination is a single-child container — wrap content + ArcButton
      // in an outer Column or the ArcButton is silently dropped.
      Column() {
        Column() {
          Text(this.currentTime)
            .fontSize(14)
            .fontColor('#888888')

          Blank().layoutWeight(1)

          Text(this.alarm !== null ? formatTime(this.alarm.hour, this.alarm.minute) : '--:--')
            .fontSize(48)
            .fontWeight(FontWeight.Bold)
            .fontColor('#FFFFFF')

          if (this.alarm !== null && this.alarm.label.length > 0) {
            Text(this.alarm.label)
              .fontSize(13)
              .fontColor('#CCCCCC')
              .margin({ top: 6 })
              .maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }

          Blank().layoutWeight(1)

          // Snooze = inline Button (secondary action). 48vp height meets
          // the Wear OS small-screen 40dp floor with margin to spare —
          // critical here because this is the user's most-stressful tap.
          Button($r('app.string.reminder_action_snooze'))
            .fontSize(13)
            .width(120)
            .height(48)
            .backgroundColor('#333333')
            .fontColor('#FFFFFF')
            .margin({ bottom: 6 })
            .accessibilityText($r('app.string.a11y_snooze_alarm'))
            .onClick(() => this.onSnooze())
        }
        .width('100%')
        .layoutWeight(1)
        .padding({
          left: SAFE_HORIZONTAL_VP,
          right: SAFE_HORIZONTAL_VP,
          top: this.getUIContext().px2vp(this.safeTopPx) + 8,
          bottom: this.getUIContext().px2vp(this.safeBottomPx) + 8,
        })

        // Dismiss = bottom-edge ArcButton (primary action, curves along bezel)
        ArcButton({
          options: new ArcButtonOptions({
            label: s('reminder_action_close', 'Dismiss'),
            position: ArcButtonPosition.BOTTOM_EDGE,
            styleMode: ArcButtonStyleMode.CUSTOM,
            backgroundColor: safeResourceColor('#BBA6FF'),
            fontColor: safeResourceColor('#000000'),
            onClick: () => this.onDismiss(),
          }),
        })
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#000000')
      .direction(Direction.Auto)
    }
    .hideTitleBar(true)
    .onBackPressed(() => {
      // Swallow hardware back — user must explicitly Dismiss or Snooze.
      return true;
    })
  }
}
