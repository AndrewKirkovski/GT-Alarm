import { describe, it, expect, beforeAll } from '@ohos/hypium';
import reminderAgentManager from '@ohos.reminderAgentManager';
import { ReminderService } from '../../../main/ets/service/ReminderService';
import { AlarmItem } from '../../../main/ets/model/AlarmItem';
import { initI18nContext } from '../../../main/ets/util/I18n';
import { TestContext } from './TestContext';

// ReminderService.publishAlarm is gated by notificationManager: an
// emulator-only run cannot drive the system's "enable notifications?"
// dialog interactively, and a previously-denied state persists across
// runs. So we don't exercise publishReminder in unit tests at all —
// that's covered by Phase 5 hardware verification (real GT 6).
//
// What we DO test is the pure `buildAlarmRequest` helper that produces
// the `ReminderRequestAlarm` payload. Every field that affects firing
// behaviour (hour, minute, title, daysOfWeek shape, wantAgent.parameters,
// maxScreenWantAgent, actionButton labels) is asserted here. The actual
// publishReminder call is a thin pass-through over this payload.

function makeAlarm(over: Partial<AlarmItem>): AlarmItem {
  return {
    id: over.id ?? 1001,
    label: over.label ?? '',
    hour: over.hour ?? 7,
    minute: over.minute ?? 30,
    daysOfWeek: over.daysOfWeek ?? 0,
    enabled: true,
    reminderId: over.reminderId,
    updatedAtEpoch: over.updatedAtEpoch ?? Date.now(),
  };
}

export default function reminderServiceTest(): void {
  describe('ReminderService_buildAlarmRequest', () => {

    beforeAll(() => {
      initI18nContext(TestContext.require());
    });

    it('build_returns_REMINDER_TYPE_ALARM', 0, () => {
      const req = ReminderService.buildAlarmRequest(makeAlarm({}), TestContext.require());
      expect(req.reminderType).assertEqual(reminderAgentManager.ReminderType.REMINDER_TYPE_ALARM);
    });

    it('build_passes_hour_and_minute_through', 0, () => {
      const req = ReminderService.buildAlarmRequest(
        makeAlarm({ hour: 23, minute: 5 }),
        TestContext.require(),
      );
      expect(req.hour).assertEqual(23);
      expect(req.minute).assertEqual(5);
    });

    it('build_uses_label_as_title_when_present', 0, () => {
      const req = ReminderService.buildAlarmRequest(
        makeAlarm({ label: 'wakeup' }),
        TestContext.require(),
      );
      expect(req.title).assertEqual('wakeup');
    });

    it('build_falls_back_to_default_title_when_label_blank', 0, () => {
      const req = ReminderService.buildAlarmRequest(
        makeAlarm({ label: '' }),
        TestContext.require(),
      );
      expect(req.title !== undefined).assertTrue();
      // Title must come from the resource `reminder_default_title` (resolves
      // through the test context). If the resource lookup ever fails the
      // resolveString helper substitutes the literal 'Alarm' fallback —
      // either is a valid passing state, but the title must NOT be empty
      // and must NOT be whitespace.
      const title = (req.title ?? '').trim();
      expect(title.length > 0).assertTrue();
    });

    it('build_omits_daysOfWeek_for_one_shot', 0, () => {
      // daysOfWeek === 0 must NOT serialise as []. Empty array is read
      // by the system as "repeat on no days" and the reminder never fires.
      const req = ReminderService.buildAlarmRequest(
        makeAlarm({ daysOfWeek: 0 }),
        TestContext.require(),
      );
      expect(req.daysOfWeek === undefined).assertTrue();
    });

    it('build_emits_ISO_day_array_for_WEEKDAYS', 0, () => {
      const WEEKDAYS = 2 | 4 | 8 | 16 | 32;
      const req = ReminderService.buildAlarmRequest(
        makeAlarm({ daysOfWeek: WEEKDAYS }),
        TestContext.require(),
      );
      expect(req.daysOfWeek !== undefined).assertTrue();
      const days = (req.daysOfWeek ?? []).slice().sort((a, b) => a - b);
      expect(JSON.stringify(days)).assertEqual('[1,2,3,4,5]');
    });

    it('build_emits_ISO_day_array_for_WEEKENDS', 0, () => {
      const WEEKENDS = 1 | 64;
      const req = ReminderService.buildAlarmRequest(
        makeAlarm({ daysOfWeek: WEEKENDS }),
        TestContext.require(),
      );
      const days = (req.daysOfWeek ?? []).slice().sort((a, b) => a - b);
      expect(JSON.stringify(days)).assertEqual('[6,7]');
    });

    it('build_emits_seven_days_for_ALL', 0, () => {
      const ALL = 127;
      const req = ReminderService.buildAlarmRequest(
        makeAlarm({ daysOfWeek: ALL }),
        TestContext.require(),
      );
      expect((req.daysOfWeek ?? []).length).assertEqual(7);
    });

    it('build_carries_alarmId_and_source_in_wantAgent_parameters', 0, () => {
      const req = ReminderService.buildAlarmRequest(
        makeAlarm({ id: 5005 }),
        TestContext.require(),
      );
      const params = req.wantAgent?.parameters;
      expect(params !== undefined && params !== null).assertTrue();
      if (params !== undefined && params !== null) {
        expect(params['gtalarm.alarmId']).assertEqual(5005);
        expect(params['gtalarm.source']).assertEqual('reminder');
      }
    });

    it('build_includes_maxScreenWantAgent_for_fullscreen_alarm_UI', 0, () => {
      const req = ReminderService.buildAlarmRequest(makeAlarm({}), TestContext.require());
      expect(req.maxScreenWantAgent !== undefined).assertTrue();
      const m = req.maxScreenWantAgent;
      if (m !== undefined) {
        expect(m.pkgName).assertEqual('com.kirkouski.gtalarm.watch');
        expect(m.abilityName).assertEqual('EntryAbility');
      }
    });

    it('build_emits_two_action_buttons_close_then_snooze', 0, () => {
      const req = ReminderService.buildAlarmRequest(makeAlarm({}), TestContext.require());
      const buttons = req.actionButton;
      expect(buttons !== undefined).assertTrue();
      if (buttons === undefined) return;
      expect(buttons.length).assertEqual(2);
      // ArkTS strict doesn't narrow `buttons[0]` to non-undefined even after
      // we asserted length===2, so do the narrowing explicitly.
      const close = buttons[0];
      const snooze = buttons[1];
      expect(close !== undefined && snooze !== undefined).assertTrue();
      if (close === undefined || snooze === undefined) return;
      expect(close.type).assertEqual(reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_CLOSE);
      expect(snooze.type).assertEqual(reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_SNOOZE);
      expect((close.title ?? '').length > 0).assertTrue();
      expect((snooze.title ?? '').length > 0).assertTrue();
    });

    it('build_uses_pinned_60s_ringDuration_and_3_snoozes_at_600s', 0, () => {
      // These are the alarm-feel constants. If they ever drift, every
      // alarm experience changes — pin them with a unit assert so a
      // typo can't slip through.
      const req = ReminderService.buildAlarmRequest(makeAlarm({}), TestContext.require());
      expect(req.ringDuration).assertEqual(60);
      expect(req.snoozeTimes).assertEqual(3);
      expect(req.timeInterval).assertEqual(600);
    });
  });
}
