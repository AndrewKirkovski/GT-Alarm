# GT Alarm

A custom two-part alarm system: Android phone app + HarmonyOS NEXT watch app.

## Components

- [`android-app/`](./android-app) — Android phone app (Samsung One UI target, Kotlin + Jetpack Compose + Material3). Package `com.kirkouski.gtalarm`.
- [`watch-app/`](./watch-app) — HarmonyOS NEXT watch app (Huawei Watch GT 6, ArkTS + ArkUI). Bundle `com.kirkouski.gtalarm.watch`.

Both apps build and run independently today. They will communicate over Huawei Wear Engine P2P once vendor approval lands; the integration point is behind a `WearBridge` interface on each side, so swapping in real P2P requires no other code changes. See each sub-project's README for details.

## Architecture at a Glance

```
  Android (phone)                          Watch (HarmonyOS)
  ─────────────────                        ───────────────────
  AlarmManager.setAlarmClock()             reminderAgentManager.publishReminder()
        │                                        │
  AlarmRingService (FG)                     ReminderRequestAlarm fires
        │                                        │
  AlarmActivity (showWhenLocked)            EntryAbility.onNewWant("alarm_fired")
        │                                        │
  WearBridgeService ◀────── P2P ──────▶  WearBridgeStub
  (NoOp today)             (Wear Engine             (hilog today)
                            post-approval)
```

## License

[PolyForm Noncommercial 1.0.0](./LICENSE). Source-available for noncommercial use. Commercial use reserved to the copyright holder — dual licensing available on request.

See [`NOTICE`](./NOTICE) for the required attribution notice.

## Repo Layout

```
GT-Alarm/
├── LICENSE           # PolyForm-NC 1.0.0
├── NOTICE
├── README.md         # this file
├── .gitignore
├── android-app/      # Android Studio Gradle project
└── watch-app/        # DevEco Studio HarmonyOS project
```

## Implementation Plan

See [`docs/execution-plan.md`](./docs/execution-plan.md) for the phased roadmap, [`docs/acceptance-criteria.md`](./docs/acceptance-criteria.md) for the per-feature status tracker, and [`docs/sync-architecture.md`](./docs/sync-architecture.md) for the cross-device sync design-of-record.
