import common from '@ohos.app.ability.common';
import { ArcList, ArcListItem, ArcListAttribute, ArcListItemAttribute, LengthMetrics } from '@kit.ArkUI';
import { AlarmItem, DOW, formatTime } from '../model/AlarmItem';
import { AlarmStore } from '../service/AlarmStore';
import { ReminderService } from '../service/ReminderService';
import { Tombstones } from '../service/Tombstones';
import { WearBridgeStub } from '../util/WearBridgeStub';
import { Logger } from '../util/Logger';
import { s, sf, dayKeys } from '../util/I18n';
import { EditPage } from './EditPage';
import { AlarmRingPage } from './AlarmRingPage';
import { DeleteConfirmDialog } from '../components/DeleteConfirmDialog';
import {
  ALARM_FIRED_TICK_KEY,
  ALARM_FIRED_ID_KEY,
  REFRESH_KEY,
  bumpRefresh,
  consumeFiredAlarmId,
  SAFE_HORIZONTAL_VP,
} from '../util/AppState';

@Entry
@Component
struct IndexApp {
  @Provide('pathStack') pathStack: NavPathStack = new NavPathStack();

  aboutToAppear(): void {
    // Seed AppStorage keys once so @StorageLink bindings have stable types.
    if (AppStorage.get<number>(REFRESH_KEY) === undefined) {
      AppStorage.setOrCreate<number>(REFRESH_KEY, 0);
    }
    if (AppStorage.get<number>(ALARM_FIRED_TICK_KEY) === undefined) {
      AppStorage.setOrCreate<number>(ALARM_FIRED_TICK_KEY, 0);
    }
    if (AppStorage.get<number>(ALARM_FIRED_ID_KEY) === undefined) {
      AppStorage.setOrCreate<number>(ALARM_FIRED_ID_KEY, 0);
    }
  }

  @Builder
  pageMap(name: string) {
    if (name === 'edit') {
      EditPage();
    } else if (name === 'ring') {
      AlarmRingPage();
    }
  }

  build() {
    Navigation(this.pathStack) {
      IndexContent()
    }
    .navDestination(this.pageMap)
    .hideTitleBar(true)
    .mode(NavigationMode.Stack)
  }
}

@Component
struct IndexContent {
  @Consume('pathStack') pathStack: NavPathStack;
  @StorageLink('gtalarm.refresh') @Watch('onRefresh') refresh: number = 0;
  // Tick is the trigger: it always increments on each fire, so @Watch fires
  // even when the same alarm id repeats. We read the actual id from the
  // companion ALARM_FIRED_ID_KEY at handle time.
  @StorageLink('gtalarm.firedAlarmTick') @Watch('onAlarmFired') firedAlarmTick: number = 0;
  @StorageProp('gtalarm.safeTopPx') safeTopPx: number = 0;
  @StorageProp('gtalarm.safeBottomPx') safeBottomPx: number = 0;
  @State alarms: AlarmItem[] = [];
  @State loading: boolean = true;
  // Default to false (pessimistic) so we don't flicker the warning bar
  // visible-then-hidden as the async check resolves.
  @State notifEnabled: boolean = false;
  @State pendingDeleteId: number = 0;
  @State pendingDeleteLabel: string = '';

  private safeTopVp(): number {
    return this.getUIContext().px2vp(this.safeTopPx);
  }

  private safeBottomVp(): number {
    return this.getUIContext().px2vp(this.safeBottomPx);
  }

  // Single controller — `pendingDeleteLabel` is @State, so the dialog
  // re-reads it on each open. Reassigning the controller per long-press
  // (the previous pattern) leaks the prior controller.
  private deleteDialog: CustomDialogController = new CustomDialogController({
    builder: DeleteConfirmDialog({
      alarmLabel: this.pendingDeleteLabel,
      onConfirm: () => this.performPendingDelete(),
    }),
    autoCancel: true,
    alignment: DialogAlignment.Center,
    customStyle: true,
  });

  async aboutToAppear(): Promise<void> {
    // If the ability was cold-started by an alarm fire, push the ring page
    // BEFORE any other init so the list never paints underneath it. Skip
    // the notification permission prompt in that case — it would overlay
    // the ring page with a system dialog.
    const firedId = consumeFiredAlarmId();
    if (firedId !== 0) {
      this.pushRingPage(firedId);
    } else {
      await this.checkNotifications();
    }
    await this.reload();
  }

  async onRefresh(): Promise<void> {
    await this.reload();
  }

  onAlarmFired(): void {
    // If a ring page is already on top, leave the consume to it — that
    // page subscribes to the same tick and will swap its alarm context.
    let onRing = false;
    try {
      const names = this.pathStack.getAllPathName();
      onRing = names.length > 0 && names[names.length - 1] === 'ring';
    } catch (e) {
      Logger.warn('Index.onAlarmFired: getAllPathName', e);
    }
    if (onRing) {
      Logger.i('Index.onAlarmFired: ring on top, deferring to ring page');
      return;
    }
    const firedId = consumeFiredAlarmId();
    if (firedId !== 0) {
      this.pushRingPage(firedId);
    }
  }

  private pushRingPage(alarmId: number): void {
    this.pathStack.pushPath({ name: 'ring', param: alarmId });
  }

  private async checkNotifications(): Promise<void> {
    const ctx: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
    const ok = await ReminderService.ensureNotificationAuthorized(ctx);
    this.notifEnabled = ok;
    if (!ok) {
      Logger.w('Index: notifications disabled — alarms will not fire');
    }
  }

  private async retryNotifications(): Promise<void> {
    await this.checkNotifications();
  }

  private async reload(): Promise<void> {
    this.loading = true;
    try {
      this.alarms = await AlarmStore.getAll();
    } catch (e) {
      Logger.err('Index.reload failed', e);
      this.alarms = [];
    }
    this.loading = false;
  }

  private async onToggle(item: AlarmItem, value: boolean): Promise<void> {
    item.enabled = value;
    item.updatedAtEpoch = Date.now();
    try {
      await AlarmStore.update(item);
    } catch (e) {
      Logger.err('onToggle: store update failed', e);
      return;
    }
    if (value) {
      try {
        const ctx: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
        const reminderId = await ReminderService.publishAlarm(item, ctx);
        item.reminderId = reminderId;
        await AlarmStore.update(item);
      } catch (e) {
        Logger.err('onToggle publish failed', e);
        // Roll back: if publish failed, the alarm shouldn't say it's enabled.
        item.enabled = false;
        item.reminderId = undefined;
        try {
          await AlarmStore.update(item);
        } catch (rollbackErr) {
          Logger.err('onToggle: rollback store update failed', rollbackErr);
        }
      }
    } else if (item.reminderId !== undefined) {
      // If cancel fails, KEEP the reminderId in local state so a retry
      // (e.g. user toggles back on then off) has something to cancel.
      // Reboot reconciliation also covers the orphan case.
      let cancelled = false;
      try {
        await ReminderService.cancelAlarm(item.reminderId);
        cancelled = true;
      } catch (e) {
        Logger.warn('onToggle cancel', e);
      }
      if (cancelled) {
        item.reminderId = undefined;
        try {
          await AlarmStore.update(item);
        } catch (e) {
          Logger.err('onToggle: second store update failed', e);
        }
      }
    }
    try {
      WearBridgeStub.notifyToggled(item);
    } catch (e) {
      Logger.warn('WearBridge notify on toggle', e);
    }
    bumpRefresh();
  }

  private onEdit(item: AlarmItem): void {
    this.pathStack.pushPath({ name: 'edit', param: item.id });
  }

  private onAdd(): void {
    this.pathStack.pushPath({ name: 'edit' });
  }

  private onLongPress(item: AlarmItem): void {
    this.pendingDeleteId = item.id;
    this.pendingDeleteLabel = item.label.length > 0
      ? item.label
      : formatTime(item.hour, item.minute);
    this.deleteDialog.open();
  }

  private async performPendingDelete(): Promise<void> {
    const id = this.pendingDeleteId;
    if (id === 0) return;
    const item = this.alarms.find(a => a.id === id);
    // Optimistically remove from the visible list so the row disappears
    // immediately rather than after the async cancel + delete chain.
    this.alarms = this.alarms.filter(a => a.id !== id);
    if (item !== undefined && item.reminderId !== undefined) {
      try {
        await ReminderService.cancelAlarm(item.reminderId);
      } catch (e) {
        Logger.warn('performPendingDelete cancel', e);
      }
    }
    try {
      await AlarmStore.delete(id);
    } catch (e) {
      Logger.err('performPendingDelete store failed', e);
    }
    const tombstoneEpoch = Date.now();
    try {
      await Tombstones.add(id, tombstoneEpoch, tombstoneEpoch);
    } catch (e) {
      Logger.warn('Tombstones.add failed', e);
    }
    try {
      WearBridgeStub.notifyDeleted(id, tombstoneEpoch);
    } catch (e) {
      Logger.warn('WearBridge notify on delete', e);
    }
    this.pendingDeleteId = 0;
    bumpRefresh();
  }

  private daysLabel(mask: number): string {
    if (mask === DOW.NONE) return s('repeat_once', 'Once');
    if (mask === DOW.ALL) return s('repeat_every_day', 'Every day');
    if (mask === DOW.WEEKDAYS) return s('repeat_weekdays', 'Weekdays');
    if (mask === DOW.WEEKENDS) return s('repeat_weekends', 'Weekends');
    // Two-letter labels avoid the M/T/W/T/F/S/S ambiguity that single
    // letters introduce — duplicate T (Tue/Thu) and S (Sat/Sun) is
    // unreadable at a glance on a watch.
    const parts: string[] = [];
    const bits: number[] = [DOW.MON, DOW.TUE, DOW.WED, DOW.THU, DOW.FRI, DOW.SAT, DOW.SUN];
    const fallbacks: string[] = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];
    for (let i = 0; i < bits.length; i++) {
      if (DOW.contains(mask, bits[i])) {
        parts.push(s(dayKeys(bits[i]).short, fallbacks[i]));
      }
    }
    return parts.join(' ');
  }

  private rowAccessibility(item: AlarmItem): string {
    const time = formatTime(item.hour, item.minute);
    const state = item.enabled ? s('state_on', 'on') : s('state_off', 'off');
    if (item.label.length > 0) {
      return sf('a11y_alarm_row_with_label',
        'Alarm at ' + time + ', ' + item.label + ', ' + state,
        time, item.label, state);
    }
    return sf('a11y_alarm_row_no_label',
      'Alarm at ' + time + ', ' + state,
      time, state);
  }

  @Builder
  AlarmRow(item: AlarmItem) {
    Row() {
      Column() {
        Text(formatTime(item.hour, item.minute))
          .fontSize(22)
          .fontWeight(FontWeight.Medium)
          .fontColor('#FFFFFF')
        if (item.label.length > 0) {
          Text(item.label)
            .fontSize(10)
            .fontColor('#CCCCCC')
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        Text(this.daysLabel(item.daysOfWeek))
          .fontSize(9)
          .fontColor('#888888')
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Toggle({ type: ToggleType.Switch, isOn: item.enabled })
        .onChange((v: boolean) => this.onToggle(item, v))
        .width(36)
        .height(20)
        // Expand the tap hitbox to ≥48x48 without changing visual size —
        // the visual switch alone is below the Wear OS 40dp floor.
        .responseRegion({ x: -12, y: -14, width: 60, height: 48 })
        .accessibilityText(this.rowAccessibility(item))
    }
    // Horizontal safe-area margin keeps the card off the curved bezel.
    .margin({ left: SAFE_HORIZONTAL_VP, right: SAFE_HORIZONTAL_VP })
    .height(64)
    .padding({ left: 14, right: 14 })
    .backgroundColor('#1F1F1F')
    .borderRadius(20)
    .onClick(() => this.onEdit(item))
    .gesture(
      LongPressGesture()
        .onAction(() => this.onLongPress(item))
    )
  }

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      // ArcList fills the whole circular display; items near the edges are
      // auto-arranged along the arc by the system.
      if (!this.loading && this.alarms.length > 0) {
        ArcList({ initialIndex: 0 }) {
          // Header spacer so the first alarm doesn't sit under the title
          ArcListItem() {
            Column() {
              Text($r('app.string.screen_list_title'))
                .fontSize(16)
                .fontWeight(FontWeight.Bold)
                .fontColor('#FFFFFF')
                .margin({ top: 8, bottom: 4 })
              if (!this.notifEnabled) {
                Column() {
                  Text($r('app.string.notif_disabled_title'))
                    .fontSize(10)
                    .fontColor('#FFBB4D')
                    .fontWeight(FontWeight.Bold)
                  Text($r('app.string.notif_tap_to_grant'))
                    .fontSize(9)
                    .fontColor('#AAAAAA')
                }
                .width(180)
                .padding(6)
                .backgroundColor('#332200')
                .borderRadius(8)
                .onClick(() => this.retryNotifications())
              }
            }
            .width('100%')
            .alignItems(HorizontalAlign.Center)
          }
          .height(this.notifEnabled ? 44 : 84)

          ForEach(this.alarms, (item: AlarmItem) => {
            ArcListItem() {
              this.AlarmRow(item)
            }
          }, (item: AlarmItem) => item.id.toString())

          // Bottom spacer so the last alarm isn't hidden behind the FAB
          ArcListItem() {
            Column() {}.width('100%').height(72)
          }
          .height(72)
        }
        .width('100%')
        .height('100%')
        .space(LengthMetrics.vp(8))
        .backgroundColor('#000000')
      } else {
        // Empty / loading state — plain Column, centered
        Column() {
          Text($r('app.string.screen_list_title'))
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor('#FFFFFF')
            .margin({ top: 20, bottom: 12 })
          if (!this.notifEnabled) {
            Column() {
              Text($r('app.string.notif_disabled_title'))
                .fontSize(10)
                .fontColor('#FFBB4D')
                .fontWeight(FontWeight.Bold)
              Text($r('app.string.notif_tap_to_grant'))
                .fontSize(9)
                .fontColor('#AAAAAA')
            }
            .width(180)
            .padding(6)
            .backgroundColor('#332200')
            .borderRadius(8)
            .margin({ bottom: 12 })
            .onClick(() => this.retryNotifications())
          }
          Blank().layoutWeight(1)
          if (this.loading) {
            Text($r('app.string.loading')).fontSize(18).fontColor('#666666')
          } else {
            Text($r('app.string.no_alarms')).fontSize(12).fontColor('#888888')
            Text($r('app.string.tap_plus_to_add')).fontSize(10).fontColor('#666666').margin({ top: 4 })
          }
          Blank().layoutWeight(1)
        }
        .width('100%')
        .height('100%')
        .backgroundColor('#000000')
      }

      // FAB — placed just above the system gesture bar (if any)
      Button('+', { type: ButtonType.Circle })
        .width(48)
        .height(48)
        .fontSize(22)
        .fontWeight(FontWeight.Bold)
        .backgroundColor('#BBA6FF')
        .fontColor('#000000')
        .margin({ bottom: 12 + this.safeBottomVp() })
        .accessibilityText($r('app.string.a11y_add_alarm'))
        .onClick(() => this.onAdd())
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#000000')
    .direction(Direction.Auto)
    // System-reported safe-area insets (status bar top, gesture bar bottom)
    // applied here so every page child stays clear of system chrome.
    .padding({ top: this.safeTopVp(), bottom: 0 })
  }
}
