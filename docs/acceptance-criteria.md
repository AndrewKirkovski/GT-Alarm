# GT Alarm — Acceptance Criteria (canonical)

**Status:** Phase 0b architecture pivot · **Updated:** 2026-04-27 · **Owner:** Andrei Kirkouski

This is the **authoritative spec** for GT Alarm. Every feature must map to a criterion here. Every tech decision (SDK pin, permission, API choice, library upgrade) must be re-checked against this document **before** code changes land. See `CLAUDE.md` at the repo root for the mandatory pre-decision protocol.

> **2026-04-27 architecture pivot — read first:** Empirical install testing on the GT 6 confirmed the watch runs **HarmonyOS LiteWearable, NOT full HarmonyOS NEXT**. The legacy NEXT-targeted watch app has been moved to `watch-app.old/` and a fresh LiteWearable JS project is being built in `watch-app/` (Phase 0b). All "WATCH APP" criteria below describe the legacy NEXT app and are kept here as historical record + porting reference; new criteria for the LiteWearable rewrite live in [§ WATCH APP — LiteWearable rewrite (Phase 0b)](#watch-app--litewearable-rewrite-phase-0b) below. The phone-side Android app is unaffected by this pivot. The cross-device design has also been revised: the phone is now the sole scheduler — see [`sync-architecture.md`](sync-architecture.md) §3.

---

## Conventions

### Status legend (honest)
- ✅ **DONE** — code exists AND verified working on real device or emulator
- 🟡 **CODED** — code exists, not yet verified end-to-end (assume nothing)
- 🟠 **PARTIAL** — code exists with a known gap or caveat (gap explicitly listed)
- ❌ **NOT DONE** — missing or known broken
- 📋 **N/A** — out of scope for this build (post-Wear-Engine work)

### Untestable-claim rule
A criterion phrased as a subjective adjective ("smoothly", "fast", "feels native") is not a criterion. Replace it with a measurable rule:
- "smoothly" → frame budget (e.g. ≤ 16 ms / 60 fps over a 5 s scroll)
- "fast cold start" → wall time (e.g. ≤ 1.5 s on GT 6)
- "no flicker" → "no recomposition / re-render between two consecutive routes within 250 ms"

Anything we cannot measure on the bench gets dropped from the spec, not converted to a vibe check.

### Doc-verification protocol
Every API pin in this doc cites the source consulted. If a developer is unsure about a fact ("does `setExactAndAllowWhileIdle` survive Doze?"), the order of escalation is:
1. Local SDK `.d.ts` / Javadoc / source
2. `context7` MCP (`mcp__plugin_context7_context7__query-docs`)
3. `wippy-kb` MCP (only for Wippy projects — N/A here)
4. Official vendor docs (`developer.huawei.com`, `developer.android.com`)
5. WebFetch / WebSearch as last resort

Do not implement against memory of how an API used to work. APIs change.

---

## SDK / API pinning (2026-04-25)

### Android
| Setting | Value | Rationale |
|---|---|---|
| `minSdk` | **31** (Android 12) | Removes legacy `SCHEDULE_EXACT_ALARM` runtime checks for the public alarm-clock path. `setAlarmClock` works since API 21 but `USE_EXACT_ALARM` (auto-granted, non-revocable) only exists from 33; on 31–32 we still rely on `SCHEDULE_EXACT_ALARM` so we keep that fallback. ~98% device coverage on One UI 7. |
| `targetSdk` | **35** (Android 15) | Required by Play Store from Aug 2025; `enableEdgeToEdge()` is the standard pattern. |
| `compileSdk` | **36** | Latest available; required for predictive-back AndroidX APIs and Material3 Expressive components. |
| AGP | **9.1.1** | Stable as of 2026-04. Requires Gradle ≥ 9.3.1 (wrapper pinned to 9.3.1). |
| Kotlin | **2.3.21** | K2 default; matches Compose compiler 2.3.x. Requires `android.builtInKotlin=false` + `android.newDsl=false` in `gradle.properties` for Detekt 2.0 compat (per `changelog-2.0.0.md`). |
| KSP | **2.3.7** | Detached from Kotlin-prefix versioning since KSP 2.3.0; the standalone scheme is now plain `2.3.x`. KSP1 does NOT support Kotlin 2.3+, so `ksp.useKSP2=true` is mandatory. |
| Compose BOM | **2026.04.01** | Aligned with `compileSdk 36` + Material3 1.4.x. |
| Room | **2.8.4** | KSP2 compatible. `fallbackToDestructiveMigration()` parameterless overload is deprecated in 2.8 — use `fallbackToDestructiveMigration(dropAllTables = true)` until Phase 4 lands a real `Migration(1, 2)`. |
| Hilt | **2.59.2** | Bumped from 2.57.1 because Kotlin 2.3 + KSP 2.3.7 needs Hilt ≥ 2.58 to avoid the "different class loader" error. `androidx.hilt.navigation.compose.hiltViewModel` is moved to `androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel`. |
| Coroutines | **1.10.2** | |
| Glance | **1.2.0-rc01** | Glance 1.2.0 stable not yet published (only `-alpha01`, `-beta01`, `-rc01` available as of 2026-04). RC1 is API-stable; downgrade to 1.1.1 reverts widget-side improvements. Bump to 1.2.0 stable when Google publishes it. |
| Detekt | **2.0.0-alpha.3** | Detekt 1.23.x doesn't support Kotlin 2.3. 2.0 is alpha but explicitly built against Kotlin 2.3 / Gradle 9.3 / AGP 9.0. Plugin namespace renamed from `io.gitlab.arturbosch.detekt` to `dev.detekt`. Several rule names renamed (e.g. `UnnecessaryAbstractClass` → `AbstractClassCanBeConcreteClass`/`AbstractClassCanBeInterface`). |

### HarmonyOS LiteWearable (current target — Phase 0b)
| Setting | Value | Rationale |
|---|---|---|
| `apiType` | **`"faMode"`** | LiteWearable apps use the FA (Feature Ability) model, not Stage. |
| `srcLanguage` | **`"js"`** | LiteWearable does not support TypeScript. Pure JS + HML + CSS only. |
| `deviceTypes` | **`["liteWearable"]`** | Empirically confirmed on GT 6 (full-NEXT `["wearable"]` HAP failed install with "failed to decompress" 2026-04-27). |
| `minAPIVersion` | **6** (HarmonyOS 2.2.0) | LiteWearable runtime is API 6/7-era. GT 6 supports it forward-compatibly. |
| `targetAPIVersion` | **7** (HarmonyOS 3.0.0) | Latest LiteWearable-compatible target. Reference: espinr/litewearable + Sabrina Cara Medium walkthrough. |
| Build tool | DevEco Studio (Hvigor for Lite target) | Same DevEco Studio + Hvigor build wrapper as NEXT, but with LiteWearable project template. |
| Sources | [espinr/litewearable](https://github.com/espinr/litewearable) (cloned at `.local/reference/litewearable/`); [Sabrina Cara — Lite Wearable integration](https://medium.com/huawei-developers/harmony-os-prepare-your-lite-wearable-project-for-integration-b4daaa9df67e); HMS-Core/Explore-In-HMOS-Wearable Wear Engine demos. |

### HarmonyOS NEXT — LEGACY (now in `watch-app.old/`, kept for porting reference only)
| Setting | Value | Rationale |
|---|---|---|
| `minAPIVersion` | **18** (HarmonyOS 5.1.0) | All Arc UI components (`ArcList`, `ArcButton`, `ArcSwiper`) are `@since 18`. **N/A for current build** — GT 6 doesn't run full NEXT, so these APIs are unreachable on the target hardware. |
| `targetAPIVersion` | **21** (HarmonyOS 6.0.1) | Was the highest stable in DevEco. **N/A for current build.** |
| `apiReleaseType` | **`"Release1"`** | DevEco toolchain quirk. **N/A for current build.** |
| Build tool | DevEco Studio NEXT (5.x bundled in 2026 release train) | |

**Why the legacy section is preserved:** the algorithm-bearing files in `watch-app.old/entry/src/main/ets/service/` (LwwResolver, IncomingMessageHandler, Tombstones, AlarmStore design, BridgeMessage shape) are the porting source for Phase 0b. Don't delete `watch-app.old/` until Phase 0b lands.

**Sources consulted for API pinning:**
- HarmonyOS local `.d.ts` — `@since` annotations on Arc UI components, `wantAgent.parameters`, `reminderAgentManager.getValidReminders`
- `developer.huawei.com/consumer/en/doc/harmonyos-references-V5/` (API 18 reminder agent)
- Android: `developer.android.com/about/versions/15/behavior-changes-15`, `developer.android.com/develop/background-work/services/alarms/schedule`

---

## ANDROID APP — `com.kirkouski.gtalarm`

### Alarm management
- 🟡 Create alarm: time, label, days-of-week, audio URI, vibration-only flag, snooze duration — `AlarmEditScreen`
- 🟡 Edit any field — `AlarmEditViewModel.load`
- 🟡 Edit-screen reverse-save model: every field mutation writes to DB immediately via `AlarmRepository.saveLocalOnly` (no watch broadcast); top-bar Revert restores the open-time snapshot; exit triggers a single batched `pushAlarmToWatch` for all touched rows. **Why:** keeps the watch's P2P feed quiet during a multi-second edit instead of N redundant pushes per keystroke. Phone stays the source of truth — a process kill mid-edit just leaves the watch one sync behind; the force-sync hash precheck catches it. (Phase 5b post-review rework, 2026-05-12.)
- 🟡 Confirmation dialog on destructive actions: edit-screen Delete (existing alarm), edit-screen Discard (new draft), list swipe-to-delete. Swipe cancel resets `SwipeToDismissBoxState` so the row snaps back. (Phase 5b post-review rework.)
- 🟡 Swipe-to-delete on list row — `SwipeToDismissBox` wired in `AlarmListScreen.SwipeToDeleteRow` (Phase 3b.3, 2026-04-25)
- 🟡 Toggle enable / disable — `Switch` in `AlarmListScreen`
- 🟡 Persist across process kill + reboot — Room v2 (Phase 4 LWW migration) + `BootReceiver`
- 🟡 List shows next firing time as relative + absolute — `subtitleLine(alarm)` in `AlarmListScreen.kt` renders `<days_label> · <relative_hint>` for enabled alarms (e.g. "Mon Wed Fri · in 14 hr"). `util/RelativeTime.formatUntil` delegates to `DateUtils.getRelativeTimeSpanString` with `FORMAT_ABBREV_RELATIVE` for one-component output that fits the row width. Trigger time recomputed on alarm change via `remember(alarm)`. (Phase 3 #4, 2026-04-26.)
- 🟡 One-off alarm flips `enabled = false` after firing — `AlarmRingService.handleDismiss` flips via `repository.setEnabled(id, false)` when `daysOfWeek == 0`. Verified by `AlarmRingServiceTest.shouldAutoDisableOnDismiss` (Phase 3b.1, 2026-04-25).

### Relative ("in N min") alarms — Phase 5b MVP
- 🟡 New alarm-type toggle on `AlarmEditScreen` (At time / In…): RELATIVE mode is one-shot only — `daysOfWeek` forced to `0`. Mode toggle hidden when editing an existing alarm (switching type is a create+delete operation per spec).
- 🟡 `Alarm.relativeMinutes`: nullable Int, range `[Alarm.MIN_RELATIVE_MINUTES, Alarm.MAX_RELATIVE_MINUTES]` = `[1, 1440]` (1 min to 24 h). Domain invariant: non-null `relativeMinutes` requires `daysOfWeek == 0`; enforced by `Alarm.kt` init block, defensively coerced in the edit-screen save path, and **rejected** (not coerced) by `WearJsonCodec.parseAlarm` on receive.
- 🟡 Computed fire time: `Alarm.computedFireEpoch() = updatedAtEpoch + relativeMinutes * 60_000L`. Re-toggling the alarm off→on bumps `updatedAtEpoch` which re-anchors the timer (countdown restarts).
- 🟡 Live countdown on list row: `rememberRelativeCountdownText` ticks every 30 s when remaining > 60 s, every 1 s when ≤ 60 s, stops 5 s past fire OR immediately when the alarm is toggled off.
- 🟡 Boot recovery: `AlarmRepository.rescheduleAllOnBoot` distinguishes "missed during current uptime" (re-arm normally — AlarmManager fires immediately) from "missed during downtime" (post a passive notification + delete the row). Clock-rollback guard: `bootCompleteAt = (now - SystemClock.elapsedRealtime()).coerceAtMost(now)` so a wall-clock-backwards correction can't classify still-future alarms as missed.

### Self-destruct (Delete after firing) — Phase 5b MVP
- 🟡 `Alarm.selfDestruct`: boolean. Default `true` for one-shots (RELATIVE always; ABSOLUTE with `daysOfWeek == 0`) unless the user explicitly toggles. Default `false` for recurring (UI hides the toggle). Domain invariant: `selfDestruct == true` requires `daysOfWeek == 0`; enforced in `Alarm.kt`, edit screen save path, and **rejected** by `WearJsonCodec.parseAlarm`.
- 🟡 Dismiss-action mapping in `AlarmRingService.dismissAction`: `selfDestruct == true` → `DELETE` (row removed via `repository.delete` + tombstone propagated); `selfDestruct == false` + one-shot → `DISABLE` (existing behavior); recurring → `KEEP`. Dismiss coroutine awaits the DELETE before `stopForegroundAndSelf()` to prevent service-reap from leaving stale rows.
- 🟡 Migration v3→v4 adds `relativeMinutes INTEGER` (nullable) + `selfDestruct INTEGER NOT NULL DEFAULT 0` to the `alarms` table. `MigrationTest` asserts PRAGMA column TYPE (not just nullability) and round-trips a non-null integer value to catch accidental TEXT affinity.

### Scheduling
- ✅ Fires at exact wall-clock time, ignoring Doze — `AlarmManager.setAlarmClock(AlarmClockInfo, PI)`. Verified on Pixel 3 API 33 AVD 2026-04-30: `dumpsys alarm` shows the saved alarm registered as `RTC_WAKEUP` with `exactAllowReason=policy_permission` and listed under "Next wake from idle" (Doze whitelist confirmed).
- 🟡 Recurring auto-reschedules — `AlarmRingService.handleRing` reschedules when `daysOfWeek != 0`
- 🟡 Survives reboot — `BootReceiver` triggered by **`BOOT_COMPLETED`** + **`LOCKED_BOOT_COMPLETED`** + **`MY_PACKAGE_REPLACED`** + **`TIMEZONE_CHANGED`** + **`TIME_SET`** (5 actions). On `LOCKED_BOOT_COMPLETED` (direct-boot path) we do NOT touch Room — we wake up on the user-unlocked broadcast and reschedule then. (Source: `developer.android.com/training/articles/direct-boot`.)
- 🟡 Disable cancels exact PendingIntent — `AlarmManager.cancel(PI)` reusing the same `requestCode = alarm.id`

### Ringing
- 🟡 Full-screen alarm UI launches over the lockscreen — `AlarmActivity` with `setShowWhenLocked(true)` + `setTurnScreenOn(true)` + `FLAG_KEEP_SCREEN_ON`. The notification carries `setFullScreenIntent(PI, true)` so the lockscreen takeover happens whether the activity is visible or not. (Source: `developer.android.com/develop/ui/views/notifications/time-sensitive`.)
- 🟡 Audio plays on ALARM stream — `AudioAttributes.Builder().setUsage(USAGE_ALARM)` on **both** `MediaPlayer` and the `NotificationChannel` sound attributes
- 🟡 Vibration uses `VibrationAttributes.USAGE_ALARM`
- 🟡 Vibration-only mode — `AlarmAudioPlayer.start(uri, vibrateOnly = true)` skips audio, keeps vibration loop
- 🟡 System default fallback — `RingtoneManager.getDefaultUri(TYPE_ALARM)` when user URI absent or unresolvable
- 🟡 Auto-dismiss after 5 min — `Handler.postDelayed` cancelled in `stopForegroundAndSelf`
- 🟡 Bypass DND — `NotificationChannel.setBypassDnd(true)`. **Caveat:** if the user has blocked the channel from settings or set a DND mode that explicitly excludes alarms (Android 14+ permits this), the alarm is silenced. We do not override user settings — this is the OS contract.

### Dismiss & snooze
- 🟡 Dismiss from full-screen activity (primary button) and from notification action
- 🟡 Snooze duration is configurable **per alarm** (`Alarm.snoozeMinutes`, range 1–60, default 10). The full-screen activity and notification action both call `AlarmRingService.handleSnooze`, which calls `AlarmRepository.snooze(id)` with no minutes override, causing the repo to read the alarm's own `snoozeMinutes` value. The edit screen exposes a preset chip row (1 / 5 / 10 / 30 min) and persists the choice via Room migration v3. Watch ring page reads the same field from its local `AlarmStore` copy of the alarm and uses it for the `rescheduleEpoch` it sends back to the phone. Wire format `alarm` payload carries `snoozeMinutes` on add/update/toggle.
- 🟡 Snooze reuses `alarm.id` as `PendingIntent` `requestCode` so a second snooze cancels the first

### Audio picker
- 🟡 SAF-based picker filtered to `audio/*` — `ActivityResultContracts.OpenDocument`
- 🟡 URI persisted via `ContentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` immediately after callback
- 🟡 Display name resolved through `OpenableColumns.DISPLAY_NAME` and shown in list
- 🟡 New pick replaces previous URI

### Glance widget
- 🟡 Renders next upcoming alarm as `Sat 07:00` + relative hint
- 🟡 "No alarms" empty state
- 🟡 Tap "Add alarm" → deep link `gtalarm://add` → `MainActivity` with `screen=add` extra
- 🟡 Widget refresh within 1 minute of any mutation — `AlarmRepository.refreshWidgets()` (via `WidgetRefresher` interface; `GlanceWidgetRefresher` impl) iterates `GlanceAppWidgetManager(ctx).getGlanceIds(NextAlarmGlanceWidget::class.java)` and calls `widget.update(ctx, glanceId)` per id (NOT `updateAll`). Wired into save/setEnabled/delete/snooze. Verified via `AlarmRepositoryTest` mock-asserts `widgetRefresher.refresh()` called on every mutation. (Phase 3b.2, 2026-04-25.)

### Permissions (manifest + runtime)
**Permissions in manifest:**
- `USE_EXACT_ALARM` — alarm-clock-app exemption, **auto-granted on API 33+**, non-revocable. Use this and only this for alarm scheduling on 33+. (Source: `developer.android.com/about/versions/14/changes/schedule-exact-alarms`.)
- `SCHEDULE_EXACT_ALARM` — kept ONLY for API 31–32 fallback (user-revocable). Drop on min-sdk bump to 33.
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — declared because `AlarmRingService` plays alarm audio. (Android 14+: services started while in background must declare the matching subtype.)
- `RECEIVE_BOOT_COMPLETED`
- `USE_FULL_SCREEN_INTENT` — auto-granted for `category = "alarm"` apps but is user-revocable on 14+
- `POST_NOTIFICATIONS` (runtime, 13+)
- `VIBRATE`
- `WAKE_LOCK`

**Runtime checks on cold start (`MainActivity.onCreate`):**
- 🟡 `NotificationManager.canUseFullScreenIntent()` (API 34+); if false, surface a non-blocking card linking to `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`
- 🟡 `POST_NOTIFICATIONS` runtime request (API 33+)
- 🟡 Samsung-specific battery-optimisation rationale — `BatteryOptRationaleCard` in `AlarmListScreen` shows when `PowerManager.isIgnoringBatteryOptimizations(packageName)` is false AND user hasn't dismissed it (`OnboardingState`-backed). Tap opens `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` directly (falls back to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` list). Manifest declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (alarm-clock category exemption). Lint suppression in `lint.xml` documents the policy basis. (Phase 3b.4, 2026-04-26.)

### Activity / system-bars
- ✅ `enableEdgeToEdge()` — called in `MainActivity.onCreate`. Verified on AVD 2026-04-30 (status bar drawn through, no decor-fits inset). (Phase 3a, 2026-04-25.)
- ❌ Material3 Expressive theme — **BLOCKED**: `MaterialExpressiveTheme` and `ExperimentalMaterial3ExpressiveApi` are declared `internal` in `androidx.compose.material3:material3` 1.4.0 (Compose BOM 2026.04.01) AND in 1.5.0-alpha18 (latest published on Google Maven as of 2026-04-26). No `material3-expressive` artifact is published. Re-check on each Compose BOM bump; flip when the symbols go public. Theme stays on `MaterialTheme` with documented comment in `ui/theme/Theme.kt`.
- 🟠 Predictive back gesture — manifest carries `android:enableOnBackInvokedCallback="true"` (Phase 3a, 2026-04-25). `BackHandler { onDone() }` wired in `AlarmEditScreen` (2026-04-30). Other screens still rely on `navController.popBackStack()` which routes through the system handler — adequate but not yet fully predictive-back-aware on every surface.

### Dynamic receivers
- ❌ Any `Context.registerReceiver` call must pass the explicit `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED` flag (required since API 34). We do not currently register any dynamic receivers; if we add one for media-button or screen-state, the flag is mandatory.

### Internationalization (Android)
- 🟡 All user-facing strings in `res/values/strings.xml` (English baseline). No hardcoded literals in Compose `Text(...)` — audit pass clean as of 2026-04-25 (Phase 3c).
- ✅ Locale resource overlays — 6 dirs landed: `values-en-rUS`, `values-zh-rCN`, `values-ru-rRU`, `values-pl-rPL`, `values-uk-rUA`, `values-be-rBY`. Translations cribbed from watch app's HarmonyOS resources. Verified on AVD 2026-04-30 via `cmd locale set-app-locales com.kirkouski.gtalarm.debug --locales pl-PL`: title "Alarms" → "Alarmy", days "Once" → "Raz", relative time "In 16 hr." → "Za 15 godz." on next launch. (Phase 3c, 2026-04-25.)
- ✅ `LocalConfiguration.current.locales` honored — `NextAlarmGlanceWidget.computeNext` reads `context.resources.configuration.locales[0]`; `TimeFormatter` delegates to `DateFormat` which itself respects the configuration locale. Zero `Locale.getDefault()` / `Locale.US` / `Locale.ENGLISH` / `Locale.ROOT` calls in production code (verified via Grep 2026-04-26). Verified end-to-end on AVD 2026-04-30 via per-app locale switch — list strings reflow on next process launch. Audit gap: code paths added in future may regress this — consider a custom Detekt rule when patterns stabilise.
- ✅ Time format follows system 12h/24h — `util/TimeFormatter.kt` uses `DateFormat.is24HourFormat(context)` + `DateFormat.getTimeFormat(context)`. Used by AlarmListScreen, AlarmActivity, AlarmNotifications, NextAlarmGlanceWidget. Verified on AVD 2026-04-30: en-US locale renders "7:00 AM" while pl-PL renders "07:00" — Polish 24h system pref honored after locale switch. (Phase 3c, 2026-04-25.)
- 🟡 Day-letter labels in chip row pulled from `strings.xml` — `util/DayLabels.kt:shortLabelResForDayBit(bit)` maps each `DaysOfWeek` bit to its `R.string.day_*_short`. 2-letter glyphs (`Mo`/`Tu`/...`Su`) used in base + 6 locale dirs. (Phase 3c, 2026-04-25.)
- ✅ `Configuration.uiMode` (light/dark) — `Theme.kt` reads `isSystemInDarkTheme()` and selects `dynamicDarkColorScheme(context)` / `dynamicLightColorScheme(context)` (Material3 dynamic color). Manual fallback colors defined for non-dynamic devices. Verified on AVD 2026-04-30 via `cmd uimode night yes/no` — Material You dynamic palette recomposes cleanly between light and dark.
- ✅ Per-app language picker support — `res/xml/locales_config.xml` lists all 6 locales; manifest declares `android:localeConfig="@xml/locales_config"`. Verified on AVD 2026-04-30 via `cmd locale set-app-locales com.kirkouski.gtalarm.debug --locales pl-PL` (the per-app locale API path) — strings reflow and time format flips on next launch. (Phase 3c, 2026-04-25.)
- 📋 Plurals via `<plurals>` — UI does NOT yet surface relative time ("in 5 minutes"). When the relative-time list label lands (still ❌ above), wire through `<plurals>`.
- 🟠 Right-to-left layout mirroring — `android:supportsRtl="true"` is default in our manifest. `paddingStart`/`paddingEnd` audit not done — Compose modifiers like `Modifier.padding(horizontal = Xdp)` are direction-aware; explicit `start`/`end` only needed for asymmetric paddings. None of our locales (en/zh/ru/pl/uk/be) are RTL, so this is forward-compatibility prep.
- 🟡 Accessibility-localized text — content descriptions resolved via `stringResource(...)` everywhere (verified via Grep `contentDescription = "[A-Z]`).
- 🟠 First-day-of-week rotation — `DaysOfWeek.rotated(firstDayBit)` rotates the day list to match `Calendar.getInstance().firstDayOfWeek`. en_US shows Sun-first; zh-CN/ru-RU/pl-PL/uk-UA/be-BY show Mon-first. Wired into `AlarmEditScreen.DayRow` and `AlarmListScreen.daysLabel`. (Phase 3c.5, 2026-04-26.)

### Wear bridge stub (Android side)
- ✅ Hilt `@Binds WearBridgeService → NoOpWearBridge` — single-line swap point for `HuaweiWearBridge` post-approval
- 🟡 `AlarmRingService.DISMISS` calls `wearBridge.sendAlarmDismissed(id)` before stop (Phase 4 vocabulary refactor, 2026-04-25)
- 🟡 `AlarmRingService.SNOOZE` calls `wearBridge.sendAlarmSnoozed(id, newTriggerEpochMs)` after reschedule (via repository.snooze; double-broadcast removed in Phase 4 review)
- 🟡 `AlarmRingService.RING` calls `wearBridge.sendAlarmFired(id)` so the watch can mirror the firing event (Phase 4 review, 2026-04-25)
- 🟡 `AlarmRepository.save/setEnabled/delete/snooze` dispatch through 7-message vocabulary: `sendAlarmAdded`, `sendAlarmUpdated`, `sendAlarmToggled`, `sendAlarmDeleted`, `sendAlarmSnoozed`. New rows emit `_added`; existing rows emit `_updated`; toggles emit `_toggled`. Verified by 9 unit tests in `AlarmRepositoryTest`.
- 🟡 LWW envelope: every send method `require(updatedAtEpoch > 0)` to prevent unstamped data leaking to peer; `NoOpWearBridge` serialises full `Alarm` JSON envelope for dev verification.
- 🟡 No-Op never crashes when offline — only ever calls `Log.d`
- 🟡 Watch sync status card on alarm list — `WatchSyncCard` in `AlarmListScreen` reads `watchStatus: StateFlow<WatchSyncStatus>` exposed via `AlarmListViewModel` from `WearBridgeService.statusFlow`. `NoOpWearBridge` pins `NOT_CONNECTED` for the lifetime of the process; `HuaweiWearBridge` post-approval will mutate its own `MutableStateFlow` from Wear-Engine connection callbacks (no UI churn). Card shows "Watch sync — Watch: not connected" today; renders above the existing battery-opt rationale card. Strings under `watch_status_card_*` (5 keys × 7 locales; 5 non-English locales carry English placeholders + `<!-- TODO i18n -->` per repo translation policy). Pinned `NOT_CONNECTED` invariant covered by `NoOpWearBridgeTest`. Verified rendering on AVD 2026-04-30. (2026-04-30.)

---

## WATCH APP — LiteWearable rewrite (Phase 0b)

**Status as of 2026-04-27:** scoping complete, code rewrite in flight in `watch-app/`. The legacy NEXT-targeted app at `watch-app.old/` does not install on GT 6 (empirically confirmed via "failed to decompress" install error). All criteria below are ❌ until the rewrite lands; they will flip to 🟡 / ✅ as features are ported.

### Project skeleton
- ❌ DevEco LiteWearable project template at `watch-app/` — `apiType: "faMode"`, `srcLanguage: "js"`, `deviceTypes: ["liteWearable"]`, FA model `app.js` + page-per-feature directory layout
- ❌ Signing config wired (debug profile already exists at `watch-app/GT Debug ProfileDebug.p7b`, watch UDID registered in profile, root CA at `watch-app/Debug GT6.cer`)
- ❌ Build green (`./hvigorw assembleHap --mode module -p product=default -p buildMode=debug --no-daemon`)
- ❌ Install green on GT 6 (the "step 0" empirical verification — install a stub Lite HAP through 应用调测助手 and confirm it launches)

### Algorithm port (from `watch-app.old/entry/src/main/ets/service/`)
- ❌ `js/default/services/lwwResolver.js` — port of `LwwResolver.kt`/`.ets`. Pure helper, JS rewrite. ~10 lines.
- ❌ `js/default/services/tombstones.js` — port of `Tombstones.kt`/`.ets`. 256-entry ring buffer, 7-day prune, sticky tie-break. Backing store: `@system.storage`.
- ❌ `js/default/services/incomingMessageHandler.js` — port of receive-side LWW dispatch. Routes 7 wire types to AlarmStore + ring page + ack-back-to-phone.
- ❌ `js/default/services/alarmStore.js` — display cache (mirrors phone's Room state). Backed by `@system.storage` for KV + `@system.file` for the JSON blob if needed. **Does not** schedule anything — phone is sole scheduler per `sync-architecture.md` §3.
- ❌ `js/default/services/wearBridge.js` — Wear Engine seam. No-op send/onMessage today; post-AGConnect-approval calls `wearengine.getP2pClient().send(...)` + registers `onMessage` callback that routes to `incomingMessageHandler.handle`.

### UI rewrite (HML/CSS/JS)
- ❌ `js/default/pages/index/index.{hml,css,js}` — alarm list. Renders `AlarmStore.getAll()`, allows toggle (sends `alarm_toggled` to phone) and long-press delete (sends `alarm_deleted`). No "add" or "edit" — those flow from the phone.
- ❌ `js/default/pages/ring/ring.{hml,css,js}` — fullscreen ring page. Mounted from `incomingMessageHandler` on `alarm_fired`. Vibrate via `vibrator.vibrate({pattern})`. Dismiss/Snooze taps send acks to phone, then `router.back()`.
- ❌ Round-display safe areas — Lite has different conventions than NEXT's `ArcList`; verify against espinr/litewearable patterns.
- ❌ Touch targets: 36×36 visual / 44×44 hit (Wear OS small-screen 40dp floor)

### Internationalization
- ❌ `js/default/i18n/{en-US,zh-CN,ru-RU,pl-PL,uk-UA,be-BY}.json` — same 6 locales as Android side. LiteWearable i18n is key/value JSON, not the resource-overlay scheme of NEXT.
- ❌ `formatTime` honors `i18n.is24HourClock()` (LiteWearable equivalent — verify exact API name during port)
- ❌ Locale-aware first day of week
- ❌ Day-letter chip labels from i18n

### Wear Engine integration
- ❌ `import wearengine from "@system.wearengine"` works on Lite — architecturally confirmed via Chinese-language research. Owner: Andrei Kirkouski. End-to-end runtime test gated on Phase 5b AGConnect Wear Engine approval.
- ❌ Pairing metadata in `config.json`: `metaData.customizeData.supportLists = "<phone-pkg>:<sha256-no-colons>"` — required for Wear Engine on Lite to accept the phone-side counterpart
- ❌ End-to-end smoke test: phone sends `alarm_fired`, watch ring page renders within 500 ms

### Force-sync hash precheck (Phase 5b)
- 🟡 `AlarmHash.kt` + `alarmHash.js` produce byte-equivalent 8-char hex hashes from a canonical pipe-delimited render of the alarm list, sorted by id. Empty list → `"00000000"`. Java `String.hashCode()` (Kotlin) mirrored as `((h<<5)-h+c)|0` (JS). Test coverage in `AlarmHashTest.kt` pins canonical strings + reference vectors.
- 🟡 `WearBridgeService.forceSync(suspend () -> List<Alarm>)` rounds-trips `sync_check` → `sync_hash` before pushing. Phone re-snapshots its own alarm list **after** receiving the watch's response so a TOCTOU race (user adds an alarm during the ~2s window) doesn't accidentally match a stale local hash.
- 🟡 `ForceSyncResult.AlreadyInSync(n)` returned when remote hash matches; UI shows a distinct toast string. On `sync_check` timeout / send failure, falls through to a full push (never user-blocking).
- 🟡 `WearJsonCodec.parseIncoming` validates `sync_hash` payload against `^[0-9a-f]{8}$` — corrupt/spoofed hashes dropped.
- 🟡 `HuaweiWearBridge.pendingHashRequest` is an `AtomicReference<CompletableDeferred<String>?>`; receiver claims via `getAndSet(null)` for atomic timeout/response races.
- 🟡 Force-sync UI in-flight protection: `AlarmListViewModel.forceSyncMutex.tryLock` drops mashed taps; `forceSyncRunning` StateFlow disables the WatchSyncCard button while a sync is active.

### Known scope deltas vs the legacy NEXT app
- **Watch no longer schedules.** No `reminderAgentManager` (doesn't exist on Lite). Phone is sole scheduler. Watch reacts to `alarm_fired` pings.
- **No on-device unit tests.** Hypium is NEXT-only. Pure-logic modules (LwwResolver, Tombstones, IncomingMessageHandler) get extracted as plain JS and tested in Node/jest under `.local/jest-lite/`. UI / page lifecycle is manual-only.
- **No TypeScript.** All `.ts` becomes `.js`, types stripped.
- **Reboot survival is asymmetric.** Phone is fully self-recovering; watch has nothing to recover for scheduling. AlarmStore (display cache) restores from `@system.storage` on cold start.

---

## WATCH APP — LEGACY (now in `watch-app.old/`, HarmonyOS NEXT, will not install on GT 6)

**Kept here as historical record + porting reference for Phase 0b.** The criteria below describe `watch-app.old/`. Do not implement any new criteria here — new work happens against the LiteWearable section above.

### Alarm management
- 🟡 Create alarm with `TextPicker` hour/minute + 7 day chips + `TextInput` label — `EditPage`. `TextInput.maxLength(80)`, width `'80%'`, placeholder color WCAG-AA compliant.
- 🟡 Edit existing — round-3 onSave is a 4-step transactional flow: load existing → cancel old reminder → publish new → write store. Rolls back + surfaces error banner without popping if any step fails.
- ✅ Delete from EditPage button OR long-press list row — both go through `components/DeleteConfirmDialog.ets`. Dialog `alarmLabel` is `@Prop` so updates between long-presses re-render.
- 🟡 Toggle enable/disable on list — `Toggle` widget; cancel-failure does not orphan the `reminderId`
- 🟡 Persist across process kill — `AlarmStore` wraps `@ohos.data.preferences`, JSON under key `"alarms_v1"`, calls `flush()` on every mutation. Reboot survival not yet verified on real GT 6.
- 🟡 Round-display readable — `ArcList` + `SAFE_HORIZONTAL_VP = 20vp` + dynamic top/bottom safe-area insets

### Scheduling
- 🟡 Fires at exact time via `reminderAgentManager.publishReminder` even if app process killed (system service handles firing — that's the whole point of the Reminder Agent vs a background timer)
- 🟠 One-off auto-disables only on user-tapped Dismiss; if the system 60-second `ringDuration` lapses without a tap, `enabled` stays `true` until the next cold start runs `reconcileReminders`
- 🟡 Recurring days correct — `toReminderDays(bitmask)` converts internal `Sun=1..Sat=64` to HarmonyOS `Mon=1..Sun=7`
- 🟡 Disable cancels reminder — `IndexContent.onToggle` calls `cancelAlarm(reminderId)` with rollback on failure

### Ringing UI
- 🟡 Vibrate + fullscreen alarm at scheduled time — `maxScreenWantAgent` auto-launches `EntryAbility` fullscreen
- 🟡 Label rendered at top, time below, Snooze inline button + Dismiss `ArcButton` along bottom edge
- 🟡 CLOSE action button dismisses + cancels (one-off path)
- 🟡 SNOOZE action button engages system snooze chain — `snoozeTimes: 3, timeInterval: 600` configured at publish. **Caveat:** in-app Snooze button only `pop()`s today; system handles re-fire through configured snooze count. We do not call `reminderAgentManager.snooze` explicitly because that API does not exist — the system manages it through the action button.
- 🟡 Auto-clears at `ringDuration: 60`

### wantAgent / lifecycle
- 🟡 `wantAgent.parameters` (since API 12) carries `gtalarm.alarmId` deterministically — `EntryAbility.handleIncomingWant` reads it without any ±2-min time heuristic
- 🟡 Time/day-of-week heuristic kept ONLY as fallback for direct user launches that happen to coincide with a fire time. Fallback also checks "yesterday's bit" for midnight-wraparound.
- ✅ `module.json5: "launchType": "singleton"` — no duplicate ability instances
- 🟡 Cold-start fire path — measured target: ability up + AlarmRingPage interactive within 1.5 s on GT 6. **Untested.**
- 🟡 Reboot reconciliation — `reconcileReminders()` runs on cold start: `getAllValidReminders()` (since API 12, returns `ReminderInfo[]` with `reminderId` — distinct from older `getValidReminders()` which returns `ReminderRequest[]` without ids) + republish missing enabled alarms + cancel orphan reminderIds

### UI / UX
- 🟡 Round-display safe areas — `SAFE_TOP_PX`, `SAFE_BOTTOM_PX`, `SAFE_HORIZONTAL_VP`
- 🟡 ArcList scrolls (target: 20+ items at 60 fps; **not benchmarked**)
- 🟡 Time picker operable — `TextPicker` 64×90px per wheel, gated behind `loaded` flag for correct edit-state hydration
- 🟡 Day-of-week chips — 36×36vp visual + 44×44vp `responseRegion` (round-3 fix; meets Wear OS 40dp small-screen floor)
- 🟡 Two consecutive route navigations show no recomposition or re-render gap > 250 ms — `@StorageLink` refresh, `Navigation/NavPathStack`. **Not yet timed.**
- 🟡 Multi-fire — second alarm firing while ringing swaps in via `firedAlarmTick` `@StorageLink` + load-token race protection

### Permissions (HarmonyOS)
- ✅ `ohos.permission.PUBLISH_AGENT_REMINDER` declared + requested via `abilityAccessCtrl.createAtManager().requestPermissionsFromUser` with `checkAccessToken` to avoid re-prompt
- ❌ `KEEP_BACKGROUND_RUNNING` intentionally NOT declared (Reminder Agent runs in system service; declaring would only attract AppGallery review pushback)
- ✅ `ohos.permission.VIBRATE` declared
- 🟡 Permission rationale — accepted **either pre-** or **post-system-dialog**. Currently the post-dialog path is implemented as the orange "Notifications disabled / Tap to grant" banner in `Index.checkNotifications` (visible whenever `notificationManager.isNotificationEnabled()` returns false). A pre-dialog explanation card was considered but is not required as long as the post-dialog path remains discoverable on every cold start.

### Internationalization (HarmonyOS) — i18n run completed 2026-04-25
- 🟡 Reminder action labels routed through `resourceManager.getStringByNameSync` — base + 6 locale overlays
- 🟡 Locale overlays present: **`en_US`, `zh_CN`, `ru_RU`, `pl_PL`, `be_BY`, `uk_UA`** in `entry/src/main/resources/<locale>/element/string.json` (each carries every key from base). Built and packaged into HAP — runtime locale selection not yet verified on device.
- 🟡 Day-letter chip labels — `EditPage.DAY_BITS` rebuilt from `util/I18n.orderedDayBits()` with `dayKeys(bit)` lookups; `Text(s(bit.shortKey, ''))` reads the resource. `Index.daysLabel` rewrote to consume `s('day_*')` per bit.
- 🟡 `formatTime` honors `i18n.System.is24HourClock()` (`util/I18n.formatTime`); 12h path emits localized `ampm_am` / `ampm_pm` markers. Re-export from `model/AlarmItem.ets` so existing call sites compile unchanged.
- 🟡 Locale-aware first-day-of-week — `EditPage.buildDayBits()` rotates the 7-bit array via `util/I18n.firstDayBit()` (reads `i18n.System.getFirstDayOfWeek()` @since API 18). en_US starts on Sun; others (default Mon) match.
- ❌ Plurals — `i18n.PluralRules` for "in %d minute" / "in %d minutes" (no plural strings in current UI; defer until "next firing in N min" lands)
- 🟡 RTL — `direction(Direction.Auto)` set on root containers of `Index`, `EditPage`, `AlarmRingPage`, `DeleteConfirmDialog`. No hardcoded `paddingLeft/Right` in any of those builds (uses start/end / horizontal). Untested on RTL locale.
- 🟡 Accessibility text localized — `a11y_*` keys in base + 6 overlays. `Index.rowAccessibility`, `EditPage.DayToggle`, FAB, dialog buttons, Snooze button all consume resources via `s()` / `sf()` / `$r()`.
- 🟠 App label `$string:app_name` — root key still in base only (no per-locale `app_name` override yet). Low priority — most users see the app icon and never the literal app name.
- 🟠 Digit shaping — `formatTime` uses Western 0–9 numerals only. Acceptable for our 6 declared locales (all use Western digits in modern use); revisit if Arabic/Persian/Bengali locales are added.

### Touch targets (Wear OS / WCAG aligned)
- 🟠 Day-of-week chips 36×36vp visual + 44×44vp `responseRegion` (round-3) — Wear OS small-screen 40dp floor met
- ✅ FAB 48×48vp — Wear OS 48dp recommendation
- 🟠 Alarm row toggle 36×20vp visual + 60×48vp `responseRegion` (round-3)
- ✅ Snooze button 120×48vp (round-3 from 96×34)
- ✅ DeleteConfirmDialog buttons 86×44vp (round-3)
- ✅ Text contrast ≥ 4.5:1 (WCAG AA) — placeholder `#999999` on `#000000` = 4.84:1

### Accessibility (HarmonyOS)
- 🟡 `accessibilityText` on FAB, day toggles, row switch, Snooze, dialog buttons
- 🟡 Dialog labels reactive via `@Prop alarmLabel` (round-3)
- 🟡 All `accessibilityText` strings sourced from `resourceManager` via `s()` / `sf()` (`util/I18n.ets`) — i18n run 2026-04-25

### WearBridge mutation hooks (watch side)
- ✅ `notifyAdded`, `notifyUpdated`, `notifyDeleted`, `notifyToggled`, `notifyFired`, `notifyDismissed`, `notifySnoozed` — all log JSON via `WearBridgeStub.send`

### Service-layer hardening
- ✅ AlarmStore: serialised write queue, duplicate-id rejection, hour/minute/days/label validation, JSON parse-failure resets corrupted blob
- ✅ ReminderService: `cancelAlarm` rethrows; action labels + default title localized via resourceManager; `slotType` not overridden (system picks ALARM internally)
- ✅ Logger: `describeError` extracts BusinessError code + message

---

## CROSS-DEVICE (post AGConnect Wear Engine approval, ~2 weeks out)

📋 All flow items N/A for current build. Stubs in place on Android: `WearBridgeService` Hilt-bound to `NoOpWearBridge`, `IncomingMessageHandler` + `LwwResolver` + `Tombstones` (Phase 5a). Watch-side seams in flight under Phase 0b. See [`sync-architecture.md`](sync-architecture.md) for the design-of-record (component layers, sequence diagrams §4.1–4.4, customization findings §6, local-testing strategy §8, three-phase rollout plan).

**Design pivot 2026-04-27 — phone is sole scheduler.** LiteWearable on GT 6 has no scheduling primitive that survives device sleep, so all scheduling moves to the phone. Watch is online-armed thin client: receives `alarm_fired` pings, renders the ring page, sends back dismiss/snooze acks. See `sync-architecture.md` §3 and §5.3.

Wire format both sides agree on (locked 2026-04-25):
```json
{
  "type": "alarm_fired" | "alarm_dismissed" | "alarm_snoozed" | "alarm_added" | "alarm_updated" | "alarm_deleted" | "alarm_toggled",
  "alarmId": <number>,
  "updatedAtEpoch": <number>,
  "rescheduleEpoch": <number?>,
  "enabled": <boolean?>,
  "alarm": <Alarm?>
}
```

**Conflict resolution: last-write-wins (LWW).** Every mutation stamps `updatedAtEpoch = Date.now()` before broadcasting. Receiver applies if newer than local; otherwise re-broadcasts its newer state. Tie → incoming wins. Deletion tombstones retained ≥ 7 days so stale updates can't resurrect. Code-side gaps tracked under "Known gaps" #21–#25 below.

---

## INTERNATIONALIZATION (cross-platform contract)

i18n is not "wrap a few strings"; it's an end-to-end contract. Both apps must enforce the same rules so a Polish user with a 12h clock preference sees consistent behavior on phone and watch.

1. **No string literals in UI code.** All visible text resolves through `resourceManager` (HarmonyOS) / `stringResource` (Compose). Lint rule must enforce.
2. **Time format follows system locale + 12h/24h preference.** Never hardcode `HH:mm`. Android: `DateFormat.is24HourFormat(context)`. HarmonyOS: `i18n.is24HourClock()`.
3. **First day of week is locale-derived.** `Calendar.getInstance(locale).getFirstDayOfWeek()` (Android) / `i18n.Calendar.getFirstDayOfWeek()` (HarmonyOS). Day-chip ordering must reorder.
4. **Plurals via plurals/PluralRules,** never `if n == 1 ... else ...`.
5. **RTL mirroring works without code changes.** No hardcoded `paddingLeft/Right`; only `Start/End`.
6. **Numeric digits localized** for cultures using non-Western numerals.
7. **Locale change at runtime** does not require app restart — both Compose's `LocalConfiguration` and HarmonyOS's `i18n` kit propagate.
8. **Per-app language picker** supported on Android (API 33+) and HarmonyOS (system Settings → app → Language).
9. **Locale resource bundles ship for:** `en` baseline (`base/`) + `en_US`, `zh_CN`, `ru_RU`, `pl_PL`, `be_BY`, `uk_UA` overlays. Belarusian + Ukrainian + Polish added 2026-04-25.
10. **Test matrix** includes a long-string locale (German `Wecker konfigurieren`) and an RTL locale (Arabic `الإعدادات`) to catch truncation and mirroring bugs.

---

## ARCHITECTURE GUARDRAILS (must hold across rounds)

- 🟡 `WearBridgeService` interface defined before any dismiss / snooze logic exists
- ✅ Hilt `@Binds` for `WearBridgeService` is the single swap-point for the real `HuaweiWearBridge` (Android)
- ✅ `WearBridgeStub` is the single swap-point on the watch — internals get replaced with `wearEngine.getP2pClient().send(...)`
- 🟡 Room migration v1→v2 — `MIGRATION_1_2` in `data/db/Migrations.kt` adds `updatedAtEpoch INTEGER NOT NULL DEFAULT 0`. `DatabaseModule` registered via `addMigrations(MIGRATION_1_2)`; `fallbackToDestructiveMigration` removed. Verified by `androidTest/MigrationTest`. (Phase 4b, 2026-04-25.)
- ✅ Bundle / package names consistent across `app.json5`, `module.json5`, AndroidManifest, all wantAgent fields
- ✅ HarmonyOS reminder lifecycle: `publishReminder` returns the reminderId, persisted on `AlarmItem`; `cancelReminder` uses reminderId, never alarmId
- ✅ `wantAgent.parameters` carries `gtalarm.alarmId` (since API 12) — no time-based heuristics in production code paths

---

## TEST MATRIX (must run before releasing each version)

### Android (instrumented)
- [ ] `./gradlew :app:testDebugUnitTest` — `NextTriggerCalculator` DST + midnight-wrap + one-shot-past + empty-daysOfWeek
- [ ] `./gradlew :app:connectedDebugAndroidTest` — Room DAO Flow emits within 200 ms of insert
- [ ] `adb shell dumpsys alarm | grep com.kirkouski.gtalarm` shows `setAlarmClock` registered
- [ ] Lockscreen test: alarm +60 s, screen off, wait → `AlarmActivity` appears over keyguard, screen on
- [ ] Boot reschedule: `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.kirkouski.gtalarm/.scheduler.BootReceiver` then re-check `dumpsys alarm`
- [ ] Doze: `adb shell dumpsys deviceidle force-idle`; `setAlarmClock` is whitelisted and still fires
- [ ] Force fire without waiting: `adb shell am start-foreground-service -a com.kirkouski.gtalarm.ACTION_RING --el alarm_id 1 -n com.kirkouski.gtalarm/.ring.AlarmRingService`
- [ ] Locale switch: change device language in Settings → app strings reflow without restart

### Watch (manual on GT 6 via 应用调测助手 / App Debug Assistant — Phase 0b LiteWearable target)

**Note:** GT 6 has no WLAN, no USB. `hdc tconn` and `hdc install` paths from the legacy NEXT plan are not viable. Install path is: PC build → copy HAP to phone `/sdcard/haps/` → App Debug Assistant pushes to watch via Bluetooth bridge through Huawei Health.

- [ ] Pair: confirm Huawei Health pairing on Samsung phone shows GT 6 connected (already verified 2026-04-27)
- [ ] Install: build LiteWearable HAP via Hvigor → copy to phone → App Debug Assistant push → confirm install green
- [ ] Pure-logic tests in Node/jest: `.local/jest-lite/` covers LwwResolver, Tombstones, IncomingMessageHandler core
- [ ] Manual UI: launch app on watch, scroll alarm list, toggle a row, long-press delete confirmation, all surfaces render
- [ ] Manual ring: simulate `alarm_fired` (post-Wear-Engine approval) → ring page mounts within 500 ms, vibration starts, Dismiss/Snooze taps work
- [ ] Persistence: add alarm via phone, observe sync to watch list; reboot watch; confirm `AlarmStore` restored from `@system.storage`
- [ ] Locale switch: change watch language → list time format and day labels reflow

### Watch — LEGACY NEXT (now in `watch-app.old/`, will not install on GT 6 — kept for historical reference)
- [ ] Pair: dev-mode on, `hdc tconn <ip>:5555`, `hdc list targets` shows watch — N/A on GT 6 (no WLAN)
- [ ] Install: DevEco Run → watch target (or `hdc install entry-default-signed.hap`) — N/A
- [ ] Fire test, HiLog, persistence, reboot — N/A on Lite

---

## KNOWN GAPS TO CLOSE NEXT

### Android
1. 🟡 One-off auto-disable after firing — `AlarmRingService.handleDismiss` flips when `daysOfWeek == 0` (Phase 3b.1, 2026-04-25)
2. 🟡 Swipe-to-delete in `AlarmListScreen` — `SwipeToDismissBox` wired (Phase 3b.3, 2026-04-25)
3. 🟡 Widget `update(ctx, glanceId)` from each `AlarmRepository` mutation — via `WidgetRefresher` interface (Phase 3b.2, 2026-04-25)
4. 🟡 "Next firing time" relative hint in list row — `subtitleLine` (Phase 3 #4, 2026-04-26)
5. 🟡 Samsung One UI battery-optimisation rationale card — `BatteryOptRationaleCard` deep-links to `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Phase 3b.4, 2026-04-26)
6. ✅ `enableEdgeToEdge()` migration — done; AVD-verified 2026-04-30 (Phase 3a, 2026-04-25)
7. ❌ Material3 Expressive theme migration — **BLOCKED**: `MaterialExpressiveTheme` is still `internal` in compose-material3 1.5.0-alpha18 (latest as of 2026-04-26); no `material3-expressive` artifact published. Re-check on each Compose BOM bump.
8. 🟠 Predictive back gesture — manifest flag `enableOnBackInvokedCallback="true"` set (Phase 3a). `BackHandler { onDone() }` wired in `AlarmEditScreen` (2026-04-30); other screens still rely on `popBackStack()` through the system handler — adequate but not yet fully predictive on every surface.
9. ✅ Locale resource overlays + `LocalesConfig` XML — 6 locales (en-rUS, zh-rCN, ru-rRU, pl-rPL, uk-rUA, be-rBY) + `locales_config.xml` manifest declaration. AVD-verified 2026-04-30 via `cmd locale set-app-locales`. (Phase 3c, 2026-04-25)
10. ✅ `DateFormat.is24HourFormat(context)` everywhere — `util/TimeFormatter.kt` is the single time-formatting authority. AVD-verified 2026-04-30: en-US "7:00 AM" vs pl-PL "07:00". (Phase 3c, 2026-04-25)

### Watch
11. 🟡 Verify cold-start fire on real GT 6 hardware (currently only emulator-confirmed)
12. 🟠 One-shot timeout regression — system 60s lapse without Dismiss leaves `enabled = true` until next cold-start reconcile
13. ✅ Permission rationale (post-system-dialog form via `Index.checkNotifications` banner) — accepted per user 2026-04-25
14. 🟡 Locale overlays `en_US`, `zh_CN`, `ru_RU`, `pl_PL`, `be_BY`, `uk_UA` shipped — each `<locale>/element/string.json` carries every key from base (i18n run 2026-04-25)
15. 🟡 Day-letter chip + `daysLabel` consume `$r('app.string.day_*')` via `util/I18n.dayKeys()`
16. 🟡 `formatTime` reads `i18n.System.is24HourClock()` and emits localized AM/PM markers (i18n run 2026-04-25)
17. 🟡 Locale-aware first-day-of-week chip ordering via `i18n.System.getFirstDayOfWeek()` (`util/I18n.orderedDayBits()`, i18n run 2026-04-25)
18. ❌ In-app Snooze: confirm system snooze chain re-fires (untested end-to-end)
19. 📋 Signing config for AppGallery release — deferred until Huawei AppGallery dev account is approved
20. ❌ App icon resize — currently 1024×1024, recommended 216×216 for wearable

### Cross-device LWW prep (locked 2026-04-25; send-side prep landed 2026-04-25/26 across Phase 4)
21. 🟡 Phone: `updatedAtEpoch: Long` added to `domain/Alarm.kt` + `data/db/AlarmEntity.kt`. Room migration v1→v2 in `data/db/Migrations.kt` (`MIGRATION_1_2` adds NOT NULL DEFAULT 0). DI swapped from `fallbackToDestructiveMigration` to `addMigrations(MIGRATION_1_2)`. Verified by `androidTest/MigrationTest` (compiles green; runs on AVD).
22. 🟡 Watch: `updatedAtEpoch: number` (required) added to `model/AlarmItem.ets`. `AlarmStore.validate` requires non-negative finite value; `AlarmStore.getAll` backfills missing values to `Date.now()` on first read.
23. 🟡 Stamp on every mutation. Phone: `AlarmRepository.save/setEnabled/delete/snooze` stamps `System.currentTimeMillis()`; new DAO `setEnabledStamped(id, enabled, stamp)` ensures atomic toggle+stamp. Watch: every page mutation site (`EditPage.onSave`, `Index.onToggle`, `AlarmRingPage` one-shot disable, delete sites) stamps `Date.now()`; `AlarmStore.add/update` backfills if caller passed 0; `AlarmStore.setEnabled` always re-stamps. Verified by 9 `AlarmRepositoryTest` assertions and 5 `Tombstones.test.ets` cases.
24. 🟡 Wire format extended on both sides. Phone `WearBridgeService` split into 7 typed methods (`sendAlarmAdded/Updated/Toggled/Deleted/Fired/Dismissed/Snoozed`) matching watch vocabulary. `NoOpWearBridge` serialises full `Alarm` JSON envelope (id/label/hour/minute/daysOfWeek/enabled/audioUri/audioName/isVibrationOnly/updatedAtEpoch). Watch `WearBridgeStub.BridgeMessage` carries `updatedAtEpoch` always + `alarm: AlarmItem` on add/update/toggle. Every send method `require(updatedAtEpoch > 0)`. (Phase 4 + wire-format reviewer fixes, 2026-04-25.)
25. 🟡 Deletion tombstones — `data/Tombstones.kt` (Android) + `service/Tombstones.ets` (watch). Both back a 256-entry ring buffer with 7-day retention, identical pure helpers `prune` / `pruneAndAdd` / `isTombstoned*`. Both `add` defensively `require(stamp > 0)` to prevent stamp=0 entries from auto-pruning at next read. 8 unit tests on each side. Doc §5 amended to clarify tombstone-tie semantics: `tombstone.epoch >= incoming.epoch ⇒ suppress` (delete is sticky).
26. 🟡 Receive-side LWW scaffold (Phase 5a, 2026-04-26). Phone: `data/sync/LwwResolver.kt` (live-row LWW, ties to incoming) + `data/sync/IncomingMessage.kt` (sealed class for the 7 wire types) + `data/sync/IncomingMessageHandler.kt` (DAO + Tombstones + Scheduler + WidgetRefresher direct, deliberately bypassing `AlarmRepository` to avoid the broadcast loop). Watch: `service/LwwResolver.ets` + `service/IncomingMessageHandler.ets` (AlarmStore + Tombstones direct). Both bridges (`WearBridgeService.setIncomingHandler` / `WearBridgeStub.setIncomingHandler`) expose a typed listener seam wired to the handler at app startup; `NoOpWearBridge` and the watch stub never invoke it today. 5 pure `LwwResolverTest` cases + 15 `IncomingMessageHandlerTest` cases on Android (80 unit tests total); 5 + 14 mirroring suites on watch (run on wearable emulator, manual). Phase 5b will swap in `HuaweiWearBridge` (the only file that actually calls `handler.handle(msg)`).

**Receiver-side LWW transport** (the real Wear-Engine P2P read path that delivers messages into the seam above) is gated on Huawei AppGallery dev account approval (~2 weeks per project plan).

Once each gap closes, flip 🟡 / 🟠 / ❌ → ✅ and re-test end-to-end.
