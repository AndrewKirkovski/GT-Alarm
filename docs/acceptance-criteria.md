# GT Alarm — Acceptance Criteria (canonical)

**Status:** in-progress · **Updated:** 2026-04-26 · **Owner:** Andrei Kirkouski

This is the **authoritative spec** for GT Alarm. Every feature must map to a criterion here. Every tech decision (SDK pin, permission, API choice, library upgrade) must be re-checked against this document **before** code changes land. See `CLAUDE.md` at the repo root for the mandatory pre-decision protocol.

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

### HarmonyOS NEXT
| Setting | Value | Rationale |
|---|---|---|
| `minAPIVersion` | **18** (HarmonyOS 5.1.0) | All Arc UI components (`ArcList`, `ArcButton`, `ArcSwiper`) are `@since 18`. Bumping higher gains nothing because every API we use is available at 18. GT 6 ships HarmonyOS 6 (API 20) firmware and runs API 18 binaries forward-compatibly. |
| `targetAPIVersion` | **21** (HarmonyOS 6.0.1) | Highest stable available in current DevEco SDK. Lets us use the most recent reminder/notification slot semantics. |
| `apiReleaseType` | **`"Release1"`** | The DevEco toolchain currently bundled with this project rejects bare `"Release"` at build time (verified 2026-04-25 — the change crashed `hvigor`). `Release1` is what the local SDK manifest actually accepts. Re-test with each DevEco upgrade; do not flip to `"Release"` without a successful build. |
| Build tool | DevEco Studio NEXT (5.x bundled in 2026 release train) | |

**Sources consulted for API pinning:**
- HarmonyOS local `.d.ts` — `@since` annotations on Arc UI components, `wantAgent.parameters`, `reminderAgentManager.getValidReminders`
- `developer.huawei.com/consumer/en/doc/harmonyos-references-V5/` (API 18 reminder agent)
- Android: `developer.android.com/about/versions/15/behavior-changes-15`, `developer.android.com/develop/background-work/services/alarms/schedule`

---

## ANDROID APP — `com.kirkouski.gtalarm`

### Alarm management
- 🟡 Create alarm: time, label, days-of-week, audio URI, vibration-only flag — `AlarmEditScreen`
- 🟡 Edit any field — `AlarmEditViewModel.load`
- 🟡 Swipe-to-delete on list row — `SwipeToDismissBox` wired in `AlarmListScreen.SwipeToDeleteRow` (Phase 3b.3, 2026-04-25)
- 🟡 Toggle enable / disable — `Switch` in `AlarmListScreen`
- 🟡 Persist across process kill + reboot — Room v2 (Phase 4 LWW migration) + `BootReceiver`
- 🟡 List shows next firing time as relative + absolute — `subtitleLine(alarm)` in `AlarmListScreen.kt` renders `<days_label> · <relative_hint>` for enabled alarms (e.g. "Mon Wed Fri · in 14 hr"). `util/RelativeTime.formatUntil` delegates to `DateUtils.getRelativeTimeSpanString` with `FORMAT_ABBREV_RELATIVE` for one-component output that fits the row width. Trigger time recomputed on alarm change via `remember(alarm)`. (Phase 3 #4, 2026-04-26.)
- 🟡 One-off alarm flips `enabled = false` after firing — `AlarmRingService.handleDismiss` flips via `repository.setEnabled(id, false)` when `daysOfWeek == 0`. Verified by `AlarmRingServiceTest.shouldAutoDisableOnDismiss` (Phase 3b.1, 2026-04-25).

### Scheduling
- 🟡 Fires at exact wall-clock time, ignoring Doze — `AlarmManager.setAlarmClock(AlarmClockInfo, PI)`
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
- 🟡 Snooze +10 min from full-screen activity and from notification action
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
- 🟡 `enableEdgeToEdge()` — called in `MainActivity.onCreate`. (Phase 3a, 2026-04-25.)
- ❌ Material3 Expressive theme — **BLOCKED**: `MaterialExpressiveTheme` and `ExperimentalMaterial3ExpressiveApi` are declared `internal` in `androidx.compose.material3:material3` 1.4.0 (Compose BOM 2026.04.01) AND in 1.5.0-alpha18 (latest published on Google Maven as of 2026-04-26). No `material3-expressive` artifact is published. Re-check on each Compose BOM bump; flip when the symbols go public. Theme stays on `MaterialTheme` with documented comment in `ui/theme/Theme.kt`.
- 🟠 Predictive back gesture — manifest carries `android:enableOnBackInvokedCallback="true"` (Phase 3a, 2026-04-25). `BackHandler` (Compose) NOT yet wired through `OnBackPressedDispatcher` for screen-specific handling — gap noted, low impact since the only pop sites currently call `navController.popBackStack()` which routes through the system handler.

### Dynamic receivers
- ❌ Any `Context.registerReceiver` call must pass the explicit `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED` flag (required since API 34). We do not currently register any dynamic receivers; if we add one for media-button or screen-state, the flag is mandatory.

### Internationalization (Android)
- 🟡 All user-facing strings in `res/values/strings.xml` (English baseline). No hardcoded literals in Compose `Text(...)` — audit pass clean as of 2026-04-25 (Phase 3c).
- 🟡 Locale resource overlays — 6 dirs landed: `values-en-rUS`, `values-zh-rCN`, `values-ru-rRU`, `values-pl-rPL`, `values-uk-rUA`, `values-be-rBY`. Translations cribbed from watch app's HarmonyOS resources. (Phase 3c, 2026-04-25.)
- 🟡 `LocalConfiguration.current.locales` honored — `NextAlarmGlanceWidget.computeNext` reads `context.resources.configuration.locales[0]`; `TimeFormatter` delegates to `DateFormat` which itself respects the configuration locale. Zero `Locale.getDefault()` / `Locale.US` / `Locale.ENGLISH` / `Locale.ROOT` calls in production code (verified via Grep 2026-04-26). Audit gap: code paths added in future may regress this — consider a custom Detekt rule when patterns stabilise.
- 🟡 Time format follows system 12h/24h — `util/TimeFormatter.kt` uses `DateFormat.is24HourFormat(context)` + `DateFormat.getTimeFormat(context)`. Used by AlarmListScreen, AlarmActivity, AlarmNotifications, NextAlarmGlanceWidget. (Phase 3c, 2026-04-25.)
- 🟡 Day-letter labels in chip row pulled from `strings.xml` — `util/DayLabels.kt:shortLabelResForDayBit(bit)` maps each `DaysOfWeek` bit to its `R.string.day_*_short`. 2-letter glyphs (`Mo`/`Tu`/...`Su`) used in base + 6 locale dirs. (Phase 3c, 2026-04-25.)
- 🟠 `Configuration.uiMode` (light/dark) — `Theme.kt` reads `isSystemInDarkTheme()` and selects `dynamicDarkColorScheme(context)` / `dynamicLightColorScheme(context)` (Material3 dynamic color). Manual fallback colors defined for non-dynamic devices. End-to-end visual verification on AVD pending.
- 🟡 Per-app language picker support — `res/xml/locales_config.xml` lists all 6 locales; manifest declares `android:localeConfig="@xml/locales_config"`. (Phase 3c, 2026-04-25.)
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

---

## WATCH APP — `com.kirkouski.gtalarm.watch` (HarmonyOS NEXT, GT 6)

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

## CROSS-DEVICE (post Wear-Engine approval, ~2 weeks out)

📋 All flow items N/A for current build. Stubs in place: `WearBridgeService` (Android Hilt-bound) + `WearBridgeStub` (watch). See [`sync-architecture.md`](sync-architecture.md) for the design-of-record (component layers, sequence diagrams §4.1–4.4, customization findings §6, local-testing strategy §8, three-phase rollout plan).

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

### Watch (manual on GT 6 over `hdc`)
- [ ] Pair: dev-mode on, `hdc tconn <ip>:5555`, `hdc list targets` shows watch
- [ ] Install: DevEco Run → watch target (or `hdc install entry-default-signed.hap`)
- [ ] Fire test: alarm +1 min, lock watch, wait → reminder UI fires + ring + label visible
- [ ] HiLog: `hdc hilog | grep GTAlarm` shows `ALARM FIRED id=<n>` line on tap
- [ ] Persistence: add alarm, force-stop app, relaunch — list rehydrates
- [ ] Reboot: reboot watch, confirm pending reminder still fires (this is the whole point of reminderAgent vs background timers)
- [ ] Locale switch: change watch language → action button labels and chip labels reflow

---

## KNOWN GAPS TO CLOSE NEXT

### Android
1. 🟡 One-off auto-disable after firing — `AlarmRingService.handleDismiss` flips when `daysOfWeek == 0` (Phase 3b.1, 2026-04-25)
2. 🟡 Swipe-to-delete in `AlarmListScreen` — `SwipeToDismissBox` wired (Phase 3b.3, 2026-04-25)
3. 🟡 Widget `update(ctx, glanceId)` from each `AlarmRepository` mutation — via `WidgetRefresher` interface (Phase 3b.2, 2026-04-25)
4. 🟡 "Next firing time" relative hint in list row — `subtitleLine` (Phase 3 #4, 2026-04-26)
5. 🟡 Samsung One UI battery-optimisation rationale card — `BatteryOptRationaleCard` deep-links to `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Phase 3b.4, 2026-04-26)
6. 🟡 `enableEdgeToEdge()` migration — done (Phase 3a, 2026-04-25)
7. ❌ Material3 Expressive theme migration — **BLOCKED**: `MaterialExpressiveTheme` is still `internal` in compose-material3 1.5.0-alpha18 (latest as of 2026-04-26); no `material3-expressive` artifact published. Re-check on each Compose BOM bump.
8. 🟠 Predictive back gesture — manifest flag `enableOnBackInvokedCallback="true"` set (Phase 3a). Compose `BackHandler` per-screen wiring still pending; low impact since `popBackStack()` routes through the system handler.
9. 🟡 Locale resource overlays + `LocalesConfig` XML — 6 locales (en-rUS, zh-rCN, ru-rRU, pl-rPL, uk-rUA, be-rBY) + `locales_config.xml` manifest declaration (Phase 3c, 2026-04-25)
10. 🟡 `DateFormat.is24HourFormat(context)` everywhere — `util/TimeFormatter.kt` is the single time-formatting authority (Phase 3c, 2026-04-25)

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

**Receiver-side LWW handling** (apply incoming if `incoming.updatedAtEpoch > local`, suppress when `Tombstones.isTombstoned(...)`) is *send-side prep only* today and lands when `HuaweiWearBridge` / phone-side receiver arrives — gated on Wear Engine vendor approval (~2 weeks per project plan).

Once each gap closes, flip 🟡 / 🟠 / ❌ → ✅ and re-test end-to-end.
