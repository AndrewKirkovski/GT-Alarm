import common from '@ohos.app.ability.common';
import { ArcButton, ArcButtonOptions, ArcButtonStyleMode, ArcButtonPosition } from '@kit.ArkUI';
import { AlarmItem, DOW } from '../model/AlarmItem';
import { AlarmStore } from '../service/AlarmStore';
import { ReminderService } from '../service/ReminderService';
import { Tombstones } from '../service/Tombstones';
import { WearBridgeStub } from '../util/WearBridgeStub';
import { Logger } from '../util/Logger';
import { s, sf, dayKeys, orderedDayBits } from '../util/I18n';
import { safeResourceColor } from '../util/ColorUtil';
import { bumpRefresh, SAFE_HORIZONTAL_VP } from '../util/AppState';
import { DeleteConfirmDialog } from '../components/DeleteConfirmDialog';

interface DayBit {
  shortKey: string;
  fullKey: string;
  bit: number;
}

// Build the chip order once from `i18n.System.getFirstDayOfWeek()`. Pure
// function so it can run at module load — ArkTS struct fields can't have
// non-trivial initializers that reference other class members.
function buildDayBits(): DayBit[] {
  const out: DayBit[] = [];
  const order = orderedDayBits();
  for (const bit of order) {
    const keys = dayKeys(bit);
    out.push({ shortKey: keys.short, fullKey: keys.full, bit });
  }
  return out;
}

function buildTwoDigits(count: number): string[] {
  const out: string[] = [];
  for (let i = 0; i < count; i++) {
    out.push(i < 10 ? '0' + i : '' + i);
  }
  return out;
}

const HOURS: string[] = buildTwoDigits(24);
const MINUTES: string[] = buildTwoDigits(60);

@Component
export struct EditPage {
  @Consume('pathStack') pathStack: NavPathStack;
  @StorageProp('gtalarm.safeTopPx') safeTopPx: number = 0;
  @StorageProp('gtalarm.safeBottomPx') safeBottomPx: number = 0;

  @State editingId: number = 0;
  @State hour: number = 7;
  @State minute: number = 0;
  @State days: number = DOW.NONE;
  @State label: string = '';
  @State isEnabled: boolean = true;
  @State existingReminderId: number | undefined = undefined;
  @State saving: boolean = false;
  // Pickers read `selected` only on construction. Defer their build until
  // initial state is loaded so editing an existing alarm shows correct time.
  @State loaded: boolean = false;
  @State errorBanner: string = '';

  private deleteDialog: CustomDialogController = new CustomDialogController({
    builder: DeleteConfirmDialog({
      onConfirm: () => this.performDelete(),
    }),
    autoCancel: true,
    alignment: DialogAlignment.Center,
    customStyle: true,
  });

  // Locale-rotated chip order: en_US starts on Sun, most others on Mon.
  // Read once at construction (orderedDayBits is locale-stable per session).
  private readonly DAY_BITS: DayBit[] = buildDayBits();

  async aboutToAppear(): Promise<void> {
    const topIdx: number = this.pathStack.size() - 1;
    if (topIdx < 0) {
      this.loaded = true;
      return;
    }
    const rawParam = this.pathStack.getParamByIndex(topIdx) as Object | undefined;
    if (typeof rawParam !== 'number' || rawParam === 0) {
      this.loaded = true;
      return;
    }
    const id: number = rawParam as number;

    try {
      const items = await AlarmStore.getAll();
      const found = items.find(a => a.id === id);
      if (found) {
        this.editingId = found.id;
        this.hour = found.hour;
        this.minute = found.minute;
        this.days = found.daysOfWeek;
        this.label = found.label;
        this.isEnabled = found.enabled;
        this.existingReminderId = found.reminderId;
        Logger.i('EditPage loaded id=' + id);
      } else {
        Logger.w('EditPage: alarm id=' + id + ' not found');
      }
    } catch (e) {
      Logger.warn('EditPage.aboutToAppear', e);
    }
    this.loaded = true;
  }

  private toggleDay(bit: number): void {
    this.days = DOW.toggle(this.days, bit);
  }

  private async onSave(): Promise<void> {
    if (this.saving) return;
    this.saving = true;
    // Use a local for errorBanner and commit ONCE in the finally block.
    // Codelinter @performance/hp-arkui-use-local-var-to-replace-state-var
    // flags repeated @State writes; each write triggers a re-render of the
    // banner Text. One commit per call is enough — the user only ever
    // sees the final value anyway.
    let errorBanner: string = '';
    try {
      const isNew = this.editingId === 0;
      // Preserve the existing reminderId on edit so step 1's store write
      // doesn't orphan-track the live reminder. If the process dies
      // between step 1 and step 2, reboot reconciliation can still find
      // the old reminder via the persisted reminderId.
      const item: AlarmItem = {
        id: this.editingId,
        label: this.label,
        hour: this.hour,
        minute: this.minute,
        daysOfWeek: this.days,
        enabled: this.isEnabled,
        reminderId: isNew ? undefined : this.existingReminderId,
        updatedAtEpoch: Date.now(),
      };

      // STEP 1 — persist new fields. For new alarms this assigns an id we
      // need before publishing. For edits this writes the new time/days
      // while keeping the old reminderId until we successfully cancel +
      // republish in step 4.
      try {
        if (isNew) {
          await AlarmStore.add(item);
          Logger.i('EditPage: inserted new alarm id=' + item.id);
        } else {
          await AlarmStore.update(item);
          Logger.i('EditPage: updated alarm id=' + item.id);
        }
      } catch (e) {
        Logger.err('EditPage.onSave: store write failed', e);
        errorBanner = s('err_save_failed', 'Could not save alarm');
        return;
      }

      // STEP 2 — cancel the existing reminder (only when editing). If
      // cancel fails we abort to avoid double-trigger from old + new.
      // cancelAlarm now rethrows so this catch is reachable.
      if (!isNew && this.existingReminderId !== undefined) {
        try {
          await ReminderService.cancelAlarm(this.existingReminderId);
        } catch (e) {
          Logger.err('EditPage.onSave: cancel failed, aborting publish', e);
          errorBanner = s('err_cancel_previous', 'Could not cancel previous alarm — try again');
          return;
        }
      }

      // STEP 3 — publish the new reminder. On failure, leave isEnabled
      // alone so the user can correct the cause (e.g. notifications) and
      // tap Save again. We do NOT auto-flip enabled=false because there's
      // no UI control on this page to flip it back.
      let publishFailed = false;
      if (item.enabled) {
        try {
          const ctx: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
          item.reminderId = await ReminderService.publishAlarm(item, ctx);
        } catch (e) {
          Logger.err('EditPage.onSave: publish failed', e);
          item.reminderId = undefined;
          publishFailed = true;
          errorBanner = s('err_arm_alarm', 'Could not arm alarm — check notification permission and retry');
        }
      } else {
        // User saved with isEnabled=false — clear any existing reminderId.
        item.reminderId = undefined;
      }

      // STEP 4 — persist the (possibly updated) reminderId / enabled flag.
      try {
        await AlarmStore.update(item);
      } catch (e) {
        Logger.err('EditPage.onSave: final store update failed', e);
        // Roll back the orphan reminder if we just published one.
        if (item.reminderId !== undefined) {
          try {
            await ReminderService.cancelAlarm(item.reminderId);
          } catch (rollbackErr) {
            Logger.err('EditPage.onSave: rollback failed', rollbackErr);
          }
        }
        errorBanner = s('err_save_failed', 'Could not save alarm');
        return;
      }

      try {
        if (isNew) {
          WearBridgeStub.notifyAdded(item);
        } else {
          WearBridgeStub.notifyUpdated(item);
        }
      } catch (e) {
        Logger.warn('WearBridge notify on save', e);
      }

      bumpRefresh();
      if (!publishFailed) {
        this.pathStack.pop();
      } else {
        // Stay on the page so the user sees the banner. Clear the
        // existingReminderId so the next retry's cancel-step skips
        // (we already cancelled in step 2 if applicable). Reboot
        // reconciliation will republish the now-orphan-enabled alarm
        // even if the user gives up and pops back to the list.
        this.existingReminderId = undefined;
      }
    } finally {
      this.errorBanner = errorBanner;
      this.saving = false;
    }
  }

  private onCancel(): void {
    this.pathStack.pop();
  }

  private onDeletePressed(): void {
    if (this.editingId === 0) return;
    this.deleteDialog.open();
  }

  private async performDelete(): Promise<void> {
    if (this.editingId === 0) return;
    const deletedId = this.editingId;
    if (this.existingReminderId !== undefined) {
      try {
        await ReminderService.cancelAlarm(this.existingReminderId);
      } catch (e) {
        Logger.warn('EditPage.performDelete: cancel', e);
      }
    }
    try {
      await AlarmStore.delete(this.editingId);
      Logger.i('EditPage: deleted alarm id=' + this.editingId);
    } catch (e) {
      Logger.err('EditPage.performDelete: store delete failed', e);
    }
    const tombstoneEpoch = Date.now();
    try {
      await Tombstones.add(deletedId, tombstoneEpoch, tombstoneEpoch);
    } catch (e) {
      Logger.warn('Tombstones.add failed', e);
    }
    try {
      WearBridgeStub.notifyDeleted(deletedId, tombstoneEpoch);
    } catch (e) {
      Logger.warn('WearBridge notify on delete', e);
    }
    bumpRefresh();
    this.pathStack.pop();
  }

  @Builder
  DayToggle(bit: DayBit) {
    // 36×36vp visual + the surrounding Row spacing of 4 yields a 40vp
    // horizontal step — fits 7 toggles inside a 280vp band on the round
    // 466 display. The full 44×44 wearable HMI minimum doesn't fit 7
    // across with two-letter labels. responseRegion expands the tap
    // hitbox to 44×44 without changing the visual.
    Text(s(bit.shortKey, ''))
      .width(36)
      .height(36)
      .fontSize(12)
      .textAlign(TextAlign.Center)
      .borderRadius(18)
      .backgroundColor(DOW.contains(this.days, bit.bit) ? '#BBA6FF' : '#2A2A2A')
      .fontColor(DOW.contains(this.days, bit.bit) ? '#000000' : '#FFFFFF')
      .responseRegion({ x: -4, y: -4, width: 44, height: 44 })
      .accessibilityText(sf('a11y_day_state',
        s(bit.fullKey, '') + ', ' + (DOW.contains(this.days, bit.bit) ? 'on' : 'off'),
        s(bit.fullKey, ''),
        DOW.contains(this.days, bit.bit) ? s('state_on', 'on') : s('state_off', 'off')))
      .onClick(() => this.toggleDay(bit.bit))
  }

  build() {
    NavDestination() {
      // NavDestination is a single-child container — wrap Scroll + ArcButton
      // in an outer Column or the ArcButton is silently dropped.
      Column() {
        // Scroll ensures the content fits a 466px-tall round display even with
        // time picker + 7 day toggles + label + 3 buttons. Top/bottom padding
        // comes from the system avoid area; horizontal inset is the round-
        // display design constant from SAFE_HORIZONTAL_VP.
        Scroll() {
          Column() {
          Text(this.editingId === 0 ? $r('app.string.screen_edit_new') : $r('app.string.screen_edit_edit'))
            .fontSize(13)
            .fontWeight(FontWeight.Bold)
            .fontColor('#FFFFFF')
            .margin({ top: 8, bottom: 8 })

          if (this.errorBanner.length > 0) {
            Text(this.errorBanner)
              .fontSize(10)
              .fontColor('#FFBB4D')
              .textAlign(TextAlign.Center)
              .padding({ left: 12, right: 12, top: 4, bottom: 4 })
              .backgroundColor('#332200')
              .borderRadius(8)
              .margin({ bottom: 6 })
          }

          if (this.loaded) {
            Row() {
              TextPicker({ range: HOURS, selected: this.hour })
                .onChange((_: string | string[], idx: number | number[]) => {
                  const i = Array.isArray(idx) ? idx[0] : idx;
                  this.hour = i;
                })
                .width(64)
                .height(90)
              Text(':')
                .fontSize(22)
                .fontColor('#FFFFFF')
                .padding({ left: 4, right: 4 })
              TextPicker({ range: MINUTES, selected: this.minute })
                .onChange((_: string | string[], idx: number | number[]) => {
                  const i = Array.isArray(idx) ? idx[0] : idx;
                  this.minute = i;
                })
                .width(64)
                .height(90)
            }
            .justifyContent(FlexAlign.Center)
          } else {
            Row().height(90)
          }

          Row({ space: 4 }) {
            ForEach(this.DAY_BITS, (b: DayBit) => {
              this.DayToggle(b)
            }, (b: DayBit) => b.bit.toString())
          }
          .width('100%')
          .justifyContent(FlexAlign.Center)
          .margin({ top: 12, bottom: 10 })

          TextInput({ text: this.label, placeholder: $r('app.string.field_label') })
            .onChange((v: string) => this.label = v)
            .maxLength(80)
            .width('80%')
            .height(36)
            .fontSize(12)
            .placeholderFont({ size: 12 })
            .backgroundColor('#1F1F1F')
            // #999999 on #1F1F1F = 4.84:1 (passes WCAG AA for placeholder).
            // Was #666666 = 2.84:1 — failed even the 3:1 non-text floor.
            .placeholderColor('#999999')
            .fontColor('#FFFFFF')
            .borderRadius(18)
            .padding({ left: 12, right: 12 })

          // Cancel = small secondary button inline (discard changes)
          Button($r('app.string.action_cancel'))
            .fontSize(11)
            .width(96)
            .height(32)
            .backgroundColor('#333333')
            .fontColor('#FFFFFF')
            .margin({ top: 16 })
            .onClick(() => this.onCancel())

          if (this.editingId !== 0) {
            Button($r('app.string.action_delete_alarm'))
              .fontSize(11)
              .width(140)
              .height(32)
              .backgroundColor('#3A1212')
              .fontColor('#FF8888')
              .margin({ top: 12 })
              .onClick(() => this.onDeletePressed())
          }
        }
        .width('100%')
        .alignItems(HorizontalAlign.Center)
        .padding({
          left: SAFE_HORIZONTAL_VP,
          right: SAFE_HORIZONTAL_VP,
          top: this.getUIContext().px2vp(this.safeTopPx),
          // ArcButton occupies the bottom arc — reserve space so the last
          // inline button is reachable above it.
          bottom: this.getUIContext().px2vp(this.safeBottomPx) + 72,
        })
        }
        .width('100%')
        .layoutWeight(1)
        .scrollBar(BarState.Auto)
        .edgeEffect(EdgeEffect.Spring)

        // Save = bottom-edge ArcButton (primary action, curves along bezel).
        // ArcButton.label is a plain string (not Resource), so resolve via
        // the I18n helper. Uses @State `saving` so the label flips reactively.
        ArcButton({
          options: new ArcButtonOptions({
            label: this.saving ? s('action_saving', 'Saving…') : s('action_save', 'Save'),
            position: ArcButtonPosition.BOTTOM_EDGE,
            styleMode: ArcButtonStyleMode.CUSTOM,
            backgroundColor: safeResourceColor('#BBA6FF'),
            fontColor: safeResourceColor('#000000'),
            onClick: () => {
              if (!this.saving) this.onSave();
            },
          }),
        })
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#000000')
      .direction(Direction.Auto)
    }
    .hideTitleBar(true)
  }
}
