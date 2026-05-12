import reminderAgentManager from '@ohos.reminderAgentManager';
import notificationManager from '@ohos.notificationManager';
import common from '@ohos.app.ability.common';
import { AlarmItem, toReminderDays } from '../model/AlarmItem';
import { Logger } from '../util/Logger';

// Bundle/ability identity for the wantAgent. Mirrored from app.json5 +
// module.json5. If those change, update both sides — there is no compile-
// time enforcement that they match. EntryAbility verifies at startup via
// bundleManager but only logs a warning on mismatch.
const BUNDLE_NAME = 'com.kirkouski.gtalarm.watch';
const ABILITY_NAME = 'EntryAbility';

/**
 * Parameter keys passed via `wantAgent.parameters` and read back from
 * `want.parameters` in EntryAbility.handleIncomingWant. Supported since
 * HarmonyOS API 12; we target API 18 so always available.
 */
export const ALARM_ID_PARAM = 'gtalarm.alarmId';
export const ALARM_SOURCE_PARAM = 'gtalarm.source';
export const ALARM_SOURCE_REMINDER = 'reminder';

/**
 * Wrapper over @ohos.reminderAgentManager. All alarm firing on the watch goes
 * through the system Reminder Agent (NOT a background timer) — this is what
 * gives us reboot-survival and DoNotDisturb-respecting behavior.
 *
 * daysOfWeek: internal bitmask (Sun=1..Sat=64) is converted to the
 * HarmonyOS-required ISO format (Mon=1..Sun=7 as a number[]) at the boundary.
 * Empty array means "one-shot" — we MUST omit the field entirely in that case,
 * otherwise HarmonyOS reads it as "repeat on no days" and never fires.
 */
export class ReminderService {

  /**
   * Build the ReminderRequestAlarm payload for `item`. Pure / synchronous —
   * does NOT touch reminderAgentManager or notificationManager. Extracted
   * so unit tests can exercise the request shape (hour/minute/title/
   * daysOfWeek/wantAgent.parameters/maxScreenWantAgent) without needing
   * the device's notification permission to be granted.
   */
  static buildAlarmRequest(
    item: AlarmItem,
    ctx?: common.UIAbilityContext,
  ): reminderAgentManager.ReminderRequestAlarm {
    const defaultTitle = ReminderService.resolveString(ctx, 'reminder_default_title', 'Alarm');
    const closeTitle = ReminderService.resolveString(ctx, 'reminder_action_close', 'Dismiss');
    const snoozeTitle = ReminderService.resolveString(ctx, 'reminder_action_snooze', 'Snooze');

    const days = toReminderDays(item.daysOfWeek);
    const request: reminderAgentManager.ReminderRequestAlarm = {
      reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_ALARM,
      hour: item.hour,
      minute: item.minute,
      title: item.label.length > 0 ? item.label : defaultTitle,
      ringDuration: 60,
      snoozeTimes: 3,
      timeInterval: 600,
      actionButton: [
        {
          title: closeTitle,
          type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_CLOSE,
        },
        {
          title: snoozeTitle,
          type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_SNOOZE,
        },
      ],
      wantAgent: {
        pkgName: BUNDLE_NAME,
        abilityName: ABILITY_NAME,
        // Pass the alarm id and a "source" marker so EntryAbility can
        // identify reminder-triggered launches deterministically (instead
        // of the ±2 minute time-match heuristic). Supported since API 12.
        // Keys are string literals because ArkTS forbids computed property
        // names; they MUST match the constants exported above.
        parameters: {
          'gtalarm.alarmId': item.id,
          'gtalarm.source': 'reminder',
        },
      },
      // On wearables that run independently, maxScreenWantAgent auto-launches
      // the target ability FULLSCREEN when the reminder fires — this is what
      // turns a "notification" into a proper "alarm" experience. Without it,
      // the reminder surfaces only as a notification card.
      maxScreenWantAgent: {
        pkgName: BUNDLE_NAME,
        abilityName: ABILITY_NAME,
      },
      // slotType is intentionally omitted — Reminder Agent picks the system
      // ALARM slot internally, which gives DND-bypass and max importance.
      // Setting SERVICE_INFORMATION here actually demoted the reminder on
      // some GT 6 firmware. Don't override.
    };
    // Only attach daysOfWeek if there's at least one — an empty array is
    // interpreted by the system as "repeat on no days" and the reminder
    // never fires.
    if (days.length > 0) {
      request.daysOfWeek = days;
    }
    return request;
  }

  static async publishAlarm(item: AlarmItem, ctx?: common.UIAbilityContext): Promise<number> {
    // Notifications must be enabled for the app before the Reminder Agent
    // will accept publishReminder. On first call the user sees a system
    // dialog; if they deny it, publishReminder fails with 1700001.
    if (ctx !== undefined) {
      const ok = await ReminderService.ensureNotificationAuthorized(ctx);
      if (!ok) {
        Logger.e('publishAlarm: notifications not enabled — user must grant in Settings');
        throw new Error('Notifications not enabled for this app');
      }
    }

    const request = ReminderService.buildAlarmRequest(item, ctx);
    const days = request.daysOfWeek ?? [];

    try {
      const reminderId = await reminderAgentManager.publishReminder(request);
      Logger.i('publishAlarm ok id=' + item.id + ' reminderId=' + reminderId
        + ' days=' + JSON.stringify(days) + ' at=' + item.hour + ':' + item.minute);
      return reminderId;
    } catch (e) {
      Logger.err('publishAlarm failed id=' + item.id, e);
      throw e as Error;
    }
  }

  /**
   * Cancels a previously published reminder. Rethrows on failure so callers
   * can decide whether to abort their flow (e.g., EditPage aborts publish if
   * the old reminder couldn't be cancelled — preventing double-trigger).
   * For "best-effort" sites where failure is acceptable (e.g. cancelling an
   * already-fired one-shot), wrap the call in a try/catch.
   */
  static async cancelAlarm(reminderId: number): Promise<void> {
    try {
      await reminderAgentManager.cancelReminder(reminderId);
      Logger.i('cancelAlarm ok reminderId=' + reminderId);
    } catch (e) {
      Logger.warn('cancelAlarm reminderId=' + reminderId, e);
      throw e as Error;
    }
  }

  static async cancelAll(): Promise<void> {
    try {
      await reminderAgentManager.cancelAllReminders();
      Logger.i('cancelAll ok');
    } catch (e) {
      Logger.warn('cancelAll', e);
    }
  }

  /**
   * Cancel every existing reminder and republish the enabled items. Returns
   * the items with fresh reminderIds populated.
   */
  static async syncAll(items: AlarmItem[], ctx?: common.UIAbilityContext): Promise<AlarmItem[]> {
    await ReminderService.cancelAll();
    const enabled = items.filter(i => i.enabled);
    if (enabled.length > 20) {
      Logger.w('syncAll: ' + enabled.length + ' reminders enabled — may exceed system cap');
    }
    const out: AlarmItem[] = [];
    for (const item of items) {
      if (item.enabled) {
        try {
          const newId = await ReminderService.publishAlarm(item, ctx);
          out.push({
            id: item.id,
            label: item.label,
            hour: item.hour,
            minute: item.minute,
            daysOfWeek: item.daysOfWeek,
            enabled: item.enabled,
            reminderId: newId,
            updatedAtEpoch: item.updatedAtEpoch,
          });
        } catch (e) {
          out.push(item);
        }
      } else {
        out.push({
          id: item.id,
          label: item.label,
          hour: item.hour,
          minute: item.minute,
          daysOfWeek: item.daysOfWeek,
          enabled: item.enabled,
          reminderId: undefined,
          updatedAtEpoch: item.updatedAtEpoch,
        });
      }
    }
    return out;
  }

  /**
   * Reminder publication requires the `ohos.permission.PUBLISH_AGENT_REMINDER`
   * user_grant permission AND the app's notification switch to be ON. On
   * first run the user sees a system dialog. If they dismiss it, subsequent
   * publishReminder calls fail with 1700001 "Notification not enable" until
   * they manually enable in Settings.
   */
  /**
   * Resolve a localized string from app resources. Falls back to the
   * fallback value if no context is available (rare — only when publish
   * is called without a UIAbility context).
   */
  private static resolveString(
    ctx: common.UIAbilityContext | undefined,
    name: string,
    fallback: string,
  ): string {
    if (ctx === undefined) return fallback;
    try {
      return ctx.resourceManager.getStringByNameSync(name);
    } catch (e) {
      Logger.warn('resolveString ' + name, e);
      return fallback;
    }
  }

  static async ensureNotificationAuthorized(ctx: common.UIAbilityContext): Promise<boolean> {
    try {
      const before = await notificationManager.isNotificationEnabled();
      Logger.i('ensureNotificationAuthorized: before=' + before);
      if (before) return true;

      Logger.i('ensureNotificationAuthorized: calling requestEnableNotification');
      await notificationManager.requestEnableNotification(ctx);

      const after = await notificationManager.isNotificationEnabled();
      Logger.i('ensureNotificationAuthorized: after=' + after);
      return after;
    } catch (e) {
      Logger.warn('ensureNotificationAuthorized', e);
      return false;
    }
  }
}
