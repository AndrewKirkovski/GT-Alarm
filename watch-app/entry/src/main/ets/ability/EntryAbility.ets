import UIAbility from '@ohos.app.ability.UIAbility';
import Want from '@ohos.app.ability.Want';
import AbilityConstant from '@ohos.app.ability.AbilityConstant';
import window from '@ohos.window';
import abilityAccessCtrl, { Permissions } from '@ohos.abilityAccessCtrl';
import bundleManager from '@ohos.bundle.bundleManager';
import reminderAgentManager from '@ohos.reminderAgentManager';
import common from '@ohos.app.ability.common';
import { AlarmItem, DOW } from '../model/AlarmItem';
import { AlarmStore } from '../service/AlarmStore';
import {
  ReminderService,
  ALARM_ID_PARAM,
  ALARM_SOURCE_PARAM,
  ALARM_SOURCE_REMINDER,
} from '../service/ReminderService';
import { Tombstones } from '../service/Tombstones';
import { WearBridgeStub } from '../util/WearBridgeStub';
import { Logger } from '../util/Logger';
import { initI18nContext } from '../util/I18n';
import { SAFE_TOP_PX, SAFE_BOTTOM_PX, signalAlarmFired } from '../util/AppState';

const REQUIRED_PERMS: Array<Permissions> = [
  'ohos.permission.PUBLISH_AGENT_REMINDER',
];

export default class EntryAbility extends UIAbility {
  private mainWindow: window.Window | null = null;
  private avoidAreaListenerAttached: boolean = false;
  // Suppresses duplicate fire signals for the same alarm within the same
  // minute when we fall back to the time-based heuristic. The deterministic
  // path (want.parameters) doesn't need this but the fallback path does.
  private lastFiredKey: string = '';

  async onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): Promise<void> {
    Logger.i('EntryAbility.onCreate');
    // Cache the ability context for I18n lookups before any page renders.
    // Pages that fall through to fallback strings without it become
    // unreadable on a non-English device.
    initI18nContext(this.context);
    try {
      await AlarmStore.init(this.context);
    } catch (e) {
      Logger.err('AlarmStore init failed', e);
    }
    try {
      await Tombstones.init(this.context);
    } catch (e) {
      Logger.err('Tombstones init failed', e);
    }
    // Cold-start fire path: handle the want BEFORE asking for permission so
    // the ring page surfaces immediately. If a fire was detected, skip the
    // permission dialog this launch — overlaying it on the ring page is
    // jarring; we'll prompt next foreground open instead.
    const fired = await this.handleIncomingWant(want);
    if (!fired) {
      // Reconcile our local store against the OS reminder list so orphan
      // reminders (cancel-failure paths) get cleaned up at launch time.
      // Skipped on a fire so we don't slow down the ring-page surface.
      this.reconcileReminders();
      // Don't await — the result is discarded and blocking onWindowStageCreate
      // serves no purpose.
      this.ensureReminderPermission(this.context);
    }
  }

  async onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): Promise<void> {
    Logger.i('EntryAbility.onNewWant');
    await this.handleIncomingWant(want);
  }

  onWindowStageCreate(stage: window.WindowStage): void {
    stage.loadContent('pages/Index', (err) => {
      if (err.code) {
        Logger.err('loadContent failed', err);
        return;
      }
      this.wireSafeAreaFromWindow(stage);
    });
  }

  onWindowStageDestroy(): void {
    if (this.mainWindow !== null && this.avoidAreaListenerAttached) {
      try {
        this.mainWindow.off('avoidAreaChange');
      } catch (e) {
        Logger.warn('avoidAreaChange off failed', e);
      }
      this.avoidAreaListenerAttached = false;
    }
    this.mainWindow = null;
  }

  /**
   * Extract the system avoid area (top = status bar, bottom = gesture bar)
   * and write to AppStorage so pages can apply it as padding. Re-runs on
   * avoidArea change (e.g., orientation or system UI hide).
   */
  private wireSafeAreaFromWindow(stage: window.WindowStage): void {
    stage.getMainWindow().then((win) => {
      this.mainWindow = win;
      const publish = (): void => {
        try {
          const top = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
          const bottom = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR);
          AppStorage.setOrCreate<number>(SAFE_TOP_PX, top.topRect.height);
          AppStorage.setOrCreate<number>(SAFE_BOTTOM_PX, bottom.bottomRect.height);
          Logger.i('safe-area px: top=' + top.topRect.height + ' bottom=' + bottom.bottomRect.height);
        } catch (e) {
          Logger.warn('safe-area query failed', e);
        }
      };
      publish();
      try {
        win.on('avoidAreaChange', () => publish());
        this.avoidAreaListenerAttached = true;
      } catch (e) {
        Logger.warn('avoidAreaChange listener failed', e);
      }
    }).catch((e: Object) => {
      Logger.warn('getMainWindow failed', e);
    });
  }

  onDestroy(): void {
    Logger.i('EntryAbility.onDestroy');
  }

  /**
   * Called on cold start (onCreate) AND on warm fire (onNewWant). Reads
   * `want.parameters[ALARM_ID_PARAM]` set by ReminderService.publishAlarm
   * (supported since HarmonyOS API 12, and we target API 18). Falls back
   * to a time/day-of-week heuristic only if parameters are missing.
   *
   * Returns true if a fire was detected and signaled.
   */
  private async handleIncomingWant(want: Want): Promise<boolean> {
    if (want === null || want === undefined) return false;

    // Deterministic path — parameters arrive intact from the wantAgent.
    const fromParams = this.extractAlarmIdFromWant(want);
    if (fromParams !== null) {
      Logger.i('handleIncomingWant: alarmId from want.parameters=' + fromParams);
      this.fireAlarm(fromParams);
      return true;
    }

    // Fallback heuristic — only used when the want has no parameters
    // (e.g. the user manually opened the app at exactly an alarm time).
    const firedId = await this.findFiringAlarmId();
    if (firedId === null) {
      Logger.i('handleIncomingWant: no firing alarm matches current time');
      return false;
    }
    // Dedupe by (id, minute-bucket) — only relevant for the fallback path.
    const minuteBucket = Math.floor(Date.now() / 60000);
    const key = firedId + '@' + minuteBucket;
    if (this.lastFiredKey === key) {
      Logger.i('handleIncomingWant: duplicate fire suppressed key=' + key);
      return true;
    }
    this.lastFiredKey = key;
    Logger.i('handleIncomingWant: heuristic match alarmId=' + firedId);
    this.fireAlarm(firedId);
    return true;
  }

  private fireAlarm(alarmId: number): void {
    try {
      WearBridgeStub.notifyFired(alarmId);
    } catch (e) {
      Logger.warn('notifyFired failed', e);
    }
    signalAlarmFired(alarmId);
  }

  private extractAlarmIdFromWant(want: Want): number | null {
    const params = want.parameters;
    if (params === null || params === undefined) return null;
    const source = params[ALARM_SOURCE_PARAM];
    if (source !== ALARM_SOURCE_REMINDER) return null;
    const id = params[ALARM_ID_PARAM];
    if (typeof id !== 'number' || !Number.isFinite(id) || id <= 0) return null;
    return id;
  }

  /**
   * Time-and-day fallback for fires arriving without parameters. Matches an
   * enabled alarm whose hour:minute is within ±2 minutes of now AND whose
   * daysOfWeek includes today (or yesterday for the midnight-wraparound
   * arm) — or is one-shot.
   */
  private async findFiringAlarmId(): Promise<number | null> {
    try {
      const items = await AlarmStore.getAll();
      const now = new Date();
      const nowTotal = now.getHours() * 60 + now.getMinutes();
      const todayBit = DOW.todayBit(now);
      // Yesterday's bit handles the case where an alarm scheduled for
      // 23:59 fires and the heuristic runs at 00:00 the next day.
      const yesterdayBit = DOW.todayBit(new Date(now.getTime() - 86400000));

      const matches: number[] = [];
      for (const item of items) {
        if (!item.enabled) continue;
        const itemTotal = item.hour * 60 + item.minute;
        const delta = Math.abs(nowTotal - itemTotal);
        const wraparound = delta >= 1438;
        if (delta > 2 && !wraparound) continue;
        if (item.daysOfWeek !== 0) {
          const useBit = wraparound && nowTotal < itemTotal ? yesterdayBit : todayBit;
          if (!DOW.contains(item.daysOfWeek, useBit)) continue;
        }
        matches.push(item.id);
      }
      if (matches.length === 0) return null;
      if (matches.length > 1) {
        Logger.w('findFiringAlarmId: ambiguous match ids=' + matches.join(',') + ' — returning first');
      }
      return matches[0];
    } catch (e) {
      Logger.warn('findFiringAlarmId failed', e);
      return null;
    }
  }

  /**
   * Reconcile AlarmStore against `reminderAgentManager.getValidReminders()`.
   * For each enabled alarm whose `reminderId` is missing from the OS list
   * (orphan from a cancel-failure or reboot edge), republish. For OS
   * reminders that don't match any local alarm, cancel them.
   *
   * Runs fire-and-forget — don't block onCreate on storage I/O.
   */
  private async reconcileReminders(): Promise<void> {
    try {
      const liveReminders: reminderAgentManager.ReminderInfo[] =
        await reminderAgentManager.getAllValidReminders();
      const liveIds = new Set<number>();
      for (const r of liveReminders) {
        liveIds.add(r.reminderId);
      }
      const items: AlarmItem[] = await AlarmStore.getAll();
      const localReminderIds = new Set<number>();
      for (const item of items) {
        if (item.reminderId !== undefined) {
          localReminderIds.add(item.reminderId);
        }
      }
      // Cancel OS reminders that the local store doesn't know about.
      for (const r of liveReminders) {
        if (!localReminderIds.has(r.reminderId)) {
          Logger.w('reconcile: cancelling orphan reminderId=' + r.reminderId);
          try {
            await ReminderService.cancelAlarm(r.reminderId);
          } catch (e) {
            Logger.warn('reconcile: cancel orphan failed', e);
          }
        }
      }
      // Republish local alarms whose reminderId is not in the OS list.
      const ctx = this.context;
      for (const item of items) {
        if (!item.enabled) continue;
        const known = item.reminderId !== undefined && liveIds.has(item.reminderId);
        if (known) continue;
        Logger.w('reconcile: republishing alarm id=' + item.id);
        try {
          const newId = await ReminderService.publishAlarm(item, ctx);
          item.reminderId = newId;
          await AlarmStore.update(item);
        } catch (e) {
          Logger.warn('reconcile: republish failed for id=' + item.id, e);
        }
      }
      Logger.i('reconcile complete: ' + items.length + ' alarms, ' + liveReminders.length + ' OS reminders');
    } catch (e) {
      Logger.warn('reconcile failed', e);
    }
  }

  private async ensureReminderPermission(ctx: common.UIAbilityContext): Promise<void> {
    try {
      const atManager = abilityAccessCtrl.createAtManager();
      const bundleInfo = await bundleManager.getBundleInfoForSelf(
        bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION,
      );
      const tokenId = bundleInfo.appInfo.accessTokenId;
      const state = await atManager.checkAccessToken(tokenId, REQUIRED_PERMS[0]);
      if (state === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
        Logger.i('reminder permission already granted');
        return;
      }
      const result = await atManager.requestPermissionsFromUser(ctx, REQUIRED_PERMS);
      const granted = result.authResults.length > 0
        && result.authResults[0] === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
      Logger.i('reminder permission requested, granted=' + granted);
    } catch (e) {
      Logger.warn('ensureReminderPermission failed', e);
    }
  }
}
