# GT Alarm — Android

Samsung One UI target (works on any Android 10+). Kotlin + Jetpack Compose + Material3. Package `com.kirkouski.gtwake.companion`.

## Build

The Gradle wrapper (8.11.1) is already committed — no bootstrap step needed.

AGP 8.7.3 requires JDK 17. On Windows, the easiest path is Android Studio's bundled JBR:

```bash
# in Git Bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android-app
./gradlew assembleDebug
```

Or just open the project in Android Studio — it auto-selects the bundled JDK.

## Run

```bash
./gradlew :app:installDebug
adb shell am start -n com.kirkouski.gtwake.companion/com.kirkouski.gtwake.companion.MainActivity
```

## Tests

```bash
./gradlew :app:testDebugUnitTest            # unit tests (NextTriggerCalculator)
./gradlew :app:connectedDebugAndroidTest    # on-device (needs USB/Wi-Fi device)
```

## Manual verification

```bash
# Confirm an alarm is registered with the system
adb shell dumpsys alarm | grep com.kirkouski.gtwake.companion

# Force-fire the ring service without waiting for the scheduled time
adb shell am start-foreground-service \
  -a com.kirkouski.gtwake.companion.ACTION_RING \
  --el alarm_id 1 \
  -n com.kirkouski.gtwake.companion/com.kirkouski.gtwake.companion.ring.AlarmRingService

# Lockscreen test: set alarm +60s in the UI, then:
adb shell input keyevent 26   # screen off
# wait — AlarmActivity should appear over the keyguard

# Boot reschedule:
adb shell am broadcast \
  -a android.intent.action.BOOT_COMPLETED \
  -n com.kirkouski.gtwake.companion/com.kirkouski.gtwake.companion.scheduler.BootReceiver
adb shell dumpsys alarm | grep com.kirkouski.gtwake.companion

# Doze test: setAlarmClock is whitelisted and should still fire
adb shell dumpsys deviceidle force-idle
```

## Wear Engine swap point

Everything phone↔watch goes through [`WearBridgeService`](app/src/main/java/com/kirkouski/gtalarm/wear/WearBridgeService.kt). Today [`NoOpWearBridge`](app/src/main/java/com/kirkouski/gtalarm/wear/NoOpWearBridge.kt) just logs each call.

When Huawei Wear Engine vendor approval arrives:

1. Add the dependency to `app/build.gradle.kts`:
   ```kotlin
   implementation("com.huawei.hms:wearengine:<latest>")
   ```
2. Create `com/kirkouski/gtalarm/wear/HuaweiWearBridge.kt` implementing `WearBridgeService` via `HiWear P2pClient`.
3. In [`di/WearModule.kt`](app/src/main/java/com/kirkouski/gtalarm/di/WearModule.kt), change the `@Binds` target from `NoOpWearBridge` to `HuaweiWearBridge`.

Nothing else in the app needs to change.

## Wire format (phone ↔ watch)

See `docs/sync-architecture.md` §2 for the full envelope schema. Per-direction
notes:

- **phone → watch** `alarm_snoozed` carries `rescheduleEpoch` so the watch can
  display "snoozed until HH:MM".
- **watch → phone** `alarm_snoozed` carries only `alarmId`+`updatedAtEpoch` —
  the phone owns the per-alarm `snoozeMinutes` and computes the reschedule
  time locally.

## License

[PolyForm Noncommercial 1.0.0](./LICENSE). Commercial use reserved.
