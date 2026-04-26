# GT Alarm — Watch (HarmonyOS NEXT)

Huawei Watch GT 6 target (HarmonyOS 6 / API 17+). ArkTS + ArkUI, Stage model. Bundle `com.kirkouski.gtalarm.watch`.

## Prerequisites

- DevEco Studio 5.x
- HarmonyOS NEXT SDK ≥ API 17 (`compatibleSdkVersion: "5.0.5(17)"`)
- A signing profile for the `wearable` device type created via AppGallery Connect

## Open in DevEco Studio

```
File → Open → C:\Projects\GT-Alarm\watch-app
```

DevEco will sync via hvigor. Sign the app using your `.p12` + profile before the first deploy.

## Pair the watch over Wi-Fi

1. On the GT 6: Settings → System → About → tap build number 7× to enable Developer Mode
2. Enable "Wireless debugging" and note the IP + port
3. On the PC:
   ```bash
   hdc tconn <watch-ip>:5555
   hdc list targets
   ```

## Install

From DevEco: Run → select the watch target. Or CLI:

```bash
hdc install entry/build/default/outputs/default/entry-default-signed.hap
```

## Manual verification

1. Open GT Alarm on the watch.
2. Grant **Reminder** permission when prompted (required for `PUBLISH_AGENT_REMINDER`).
3. Tap **+**, pick a time ~1 minute in the future, tap **Save**.
4. Lock the watch, wait — the reminder should ring with Dismiss/Snooze buttons.
5. Inspect logs:
   ```bash
   hdc hilog | grep GTAlarm
   ```
   Tapping the reminder should produce `ALARM FIRED id=<n>` in the log (that's the Wear Engine stub firing).
6. **Reboot test:** add an alarm, reboot the watch, confirm it still fires. Reminders are persisted by the system reminder agent — that's the whole reason we don't use a background timer.
7. **Persistence test:** force-stop the app, relaunch, confirm alarms rehydrate.

## Wear Engine swap point

Everything watch→phone goes through [`WearBridgeStub`](entry/src/main/ets/util/WearBridgeStub.ets). All three methods log today.

When Huawei Wear Engine vendor approval arrives, replace each `Logger.i(...)` line with `wearEngine.getP2pClient().send(peerDevice, JSON.stringify({ type, alarmId }))`. The wire format is the same as the Android side:

```json
{ "type": "alarm_fired" | "dismiss" | "snooze",
  "alarmId": <number>,
  "rescheduleEpoch"?: <number> }
```

Nothing else in the watch codebase needs to change.

## API verification notes

A few HarmonyOS NEXT API shapes used in this project should be sanity-checked in the current DevEco Studio docs before heavy iteration — confirm via `/wippy-kb`, `/context7`, or the official docs:

- `reminderAgentManager.ReminderRequestAlarm` — field names used in [`ReminderService.ets`](entry/src/main/ets/service/ReminderService.ets): `reminderType`, `hour`, `minute`, `daysOfWeek`, `title`, `ringDuration`, `snoozeTimes`, `timeInterval`, `actionButton[].type`, `actionButton[].title`, `wantAgent.pkgName`, `wantAgent.abilityName`, `wantAgent.parameters`.
- `ActionButtonType.ACTION_BUTTON_TYPE_CLOSE` / `ACTION_BUTTON_TYPE_SNOOZE` enum values.
- `publishReminder(request)` — returns `Promise<number>`.
- `daysOfWeek` is a `number[]` with **Mon=1..Sun=7** (ISO-like), not a bitmask. This is handled in [`AlarmItem.ets`](entry/src/main/ets/model/AlarmItem.ets) via `toReminderDays()`.
- `deviceTypes: ["wearable"]` — correct for the GT 6.

## License

[PolyForm Noncommercial 1.0.0](./LICENSE). Commercial use reserved.
