# GT Alarm

A custom two-part alarm system: Android phone app + Huawei Lite Wearable watch app.

## Components

- [`android-app/`](./android-app) — Android phone app (Samsung One UI target, Kotlin + Jetpack Compose + Material3). Package `com.kirkouski.gtalarm`.
- [`watch-app/`](./watch-app) — Lite Wearable FA-model watch app (Huawei Watch GT 6, JS + HML + CSS). Bundle `com.kirkouski.gtalarm.watch`. The phone is the sole scheduler; the watch is a thin online-armed display.

Both apps build and run independently. They communicate over Huawei Wear Engine P2P (vendored `wearengine.js` 5.0.2.306 on the watch side, `HuaweiWearBridge` on the phone side). The phone owns scheduling + audio + the snooze cycle; the watch renders the alarm list, the ring page, and dismiss/snooze taps.

## Architecture at a Glance

```
  Android (phone)                          Watch (Huawei Lite Wearable)
  ─────────────────                        ──────────────────────────────
  AlarmManager.setAlarmClock()             (no native alarm scheduling —
        │                                   phone is sole scheduler)
  AlarmBroadcastReceiver                          │
        │                                         │
  AlarmRingService (FG: mediaPlayback)            │
        │     │                                   │
        │     └── pre-arm: send alarm_fired ─────▶ ring.js opens
        │              ◀──── alarm_ringing ──────         │
        │                                         │
  AlarmActivity (showWhenLocked,                 onTouch → dismiss/snooze
   turnScreenOn, full-screen intent)              │
        │                ◀──── alarm_dismissed ──┘
        │                ◀──── alarm_snoozed ─────
  HuaweiWearBridge ◀───── Wear Engine P2P ─────▶ wearengine.js wrapper
                                                  (vendored 5.0.2.306)
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
