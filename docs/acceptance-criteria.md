# GT Alarm — Acceptance Criteria (canonical)

**Status:** Phase 0b architecture pivot · **Updated:** 2026-04-27 · **Owner:** Andrei Kirkouski

This is the **authoritative spec** for GT Alarm. Every feature must map to a criterion here. Every tech decision (SDK pin, permission, API choice, library upgrade) must be re-checked against this document **before** code changes land. See `CLAUDE.md` at the repo root for the mandatory pre-decision protocol.

> **2026-04-27 architecture pivot — read first:** Empirical install testing on the GT 6 confirmed the watch runs **HarmonyOS LiteWearable, NOT full HarmonyOS NEXT**. The legacy NEXT-targeted watch app has been moved to `watch-app.old/` and a fresh LiteWearable JS project is being built in `watch-app/` (Phase 0b). All "WATCH APP" criteria below describe the legacy NEXT app and are kept here as historical record + porting reference; new criteria for the LiteWearable rewrite live in [§ WATCH APP — LiteWearable rewrite (Phase 0b)](#watch-app--litewearable-rewrite-phase-0b) below. The phone-side Android app is unaffected by this pivot. The cross-device design has also been revised: the phone is now the sole scheduler — see [`sync-architecture.md`](sync-architecture.md) §3.

> **2026-06-24 — 1.0.6 release deltas (post review-pass):**
> - ✅ **Watch crown scrolls the alarm list** — the 2 s poll was reassigning `self.alarms` every tick, re-rendering the `<list>` and dropping `rotation({focus})`; now diff-before-reassign + deferred focus. Device-verified on GT 6 Pro (crown-to-dismiss on the ring still works).
> - ❌ **Watch audio playback — NOT SUPPORTED** (closes the F3 experiment): `@system.audio` resolves but its `src` setter is a no-op on the GT 6 — no source loads, every `play()` errors. The probe + bundled clips were removed. Watch alarm feedback is **vibration only**. See `sync-architecture.md` §6.1.
> - ✅ **>3-alarm sync** — full-state replace is sent as a Wear Engine **file transfer** above the ~1 KB text-message ceiling, with ack+retry (device-verified; see `sync-architecture.md` §2.1/§5.7).
> - ✅ **Per-device vibration** — independent phone + watch vibration switches per alarm; DB v9→v10 covered by `MigrationTest.migrate9To10_*`. (A v5–v8 destructive-migration fallback was attempted but **REVERTED** — it conflicted with `MIGRATION_4_5` and crashed startup in 1.0.6; see the 1.0.7 release notes.)
> - ✅ **Translations complete** — all 5 non-English Android locales filled to parity; the watch app now ships all 6 locales (added `ru-RU`, `uk-UA`, `be-BY` under `watch-app/entry/src/main/js/MainAbility/i18n/`). Supersedes the stale `js/default/i18n/` ❌ entries further below.

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

## ANDROID APP — `com.kirkouski.gtwake.companion`

### Alarm management
- 🟡 Create alarm: time, label, days-of-week, audio URI, vibration-only flag, snooze duration (incl. "Off" preset for snooze-disabled), background image — `AlarmEditScreen`. Time picker is `PickTime-Compose 1.1.6` wheel/odometer (from JitPack `com.github.anhaki`, Apache-2.0). Label is bounded to `Alarm.MAX_LABEL_LENGTH = 256` characters by domain `Alarm.init`. The label is **phone-only** — it is not sent to the watch, not stored there, and not part of `AlarmHash` (see `sync-architecture.md` §2.2); it appears only on the phone ring screen. (Phase 5a+, 2026-05-14; label-off-wire 2026-05-18.)
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
- 🟡 **Per-device sound/vibration switches (1.0.6)** — the alarm editor has three independent on/off switches: **Sound** (phone audio; backed by `isVibrationOnly`), **Phone vibration** (`Alarm.phoneVibrationEnabled`, gates `AlarmAudioPlayer` vibration independently of sound), and **Watch vibration** (`Alarm.watchVibrationEnabled`, synced + honored on the watch ring `startVibrate`). "Silent" = all three off. `vibrationPattern` is now pattern-only (the `OFF` choice retired; Room migration **v9→10** maps legacy `OFF` rows to both-vib-off + a real pattern). `watchVibrationEnabled` rides the wire alarm payload, the `alarm_fired` hint, and the LWW hash; `phoneVibrationEnabled` is phone-only.
- 🟡 System default fallback — `RingtoneManager.getDefaultUri(TYPE_ALARM)` when user URI absent or unresolvable
- 🟡 Auto-dismiss after 5 min — `Handler.postDelayed` cancelled in `stopForegroundAndSelf`
- 🟡 Bypass DND — `NotificationChannel.setBypassDnd(true)`. **Caveat:** if the user has blocked the channel from settings or set a DND mode that explicitly excludes alarms (Android 14+ permits this), the alarm is silenced. We do not override user settings — this is the OS contract.

### Dismiss & snooze
- 🟡 Dismiss from full-screen activity (primary button) and from notification action
- 🟡 Snooze duration is configurable **per alarm** (`Alarm.snoozeMinutes`, range 1–60, default 10). The full-screen activity and notification action both call `AlarmRingService.handleSnooze`, which calls `AlarmRepository.snooze(id)` with no minutes override, causing the repo to read the alarm's own `snoozeMinutes` value. The edit screen exposes a preset chip row (1 / 5 / 10 / 30 min) and persists the choice via Room migration v3. Watch ring page reads the same field from its local `AlarmStore` copy of the alarm and uses it for the `rescheduleEpoch` it sends back to the phone. Wire format `alarm` payload carries `snoozeMinutes` on add/update/toggle.
- 🟡 Snooze can be **disabled** per alarm: `Alarm.snoozeMinutes == Alarm.SNOOZE_DISABLED (0)` means the ring UI (`AlarmActivity` + heads-up notification) hides the Snooze button entirely. Edit screen exposes an "Off" chip as the first preset. Wire format preserves `0` end-to-end (`WearJsonCodec.parseAlarm` sentinel ladder: `<=0 → 0`, else clamp `[1,60]`). Peer-initiated snooze on a locally-disabled alarm collapses to a dismiss in `IncomingMessageHandler.applySnooze` to prevent a phantom 0-minute re-fire. `AlarmRepository.snooze` guards with an explicit `<= 0` early-return for the same reason. (Phase 5a+, 2026-05-14.)
- 🟡 Snooze reuses `alarm.id` as `PendingIntent` `requestCode` so a second snooze cancels the first
- 🟡 Concurrent `handleSnooze` / `handleDismiss` on `AlarmRingService` are serialized via `handlerMutex` so the row can't end up with `enabled=false` AND `snoozedUntilEpoch` set. Both handlers commit local truth (Room write) BEFORE the wear broadcast: a process kill between the two leaves the phone consistent and the watch recovers via the next sync hash mismatch. (Phase 5a+ review fix, 2026-05-14.)

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

### Help screen surfaces (Phase 5a+, 2026-05-14)
- 🟡 Reliability checklist + per-brand tips + voice-default banner + pair-watch card + debug card (existing).
- 🟡 **Donate card**: `ActionCard` linking to `https://ko-fi.com/ryotsuke` via `Intent.ACTION_VIEW`. Address #90.
- 🟡 **Device-unsupported card**: problem-first ordering, ABOVE the donate card. Links to `mailto:ryotsuke+gtalarm@gmail.com?subject=GT%20Alarm%20-%20Device%20support`. Address #91.
- 🟡 **Credits footer**: clickable courtesy credit "Icons by Tabler" → `https://tabler.io/icons`. The full icon set was migrated (2026-05-18) to [Tabler Icons](https://tabler.io/icons) — MIT licensed, **no attribution required** — generated by the `tools/icongen` pipeline. The prior Flaticon **LEGAL GAP is CLOSED**: no Flaticon assets ship in `res/`. The credit is a courtesy, not a license obligation.

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
- 🟡 FSI auto-deeplink on first launch when `PermissionAudit.checkFullScreenIntent` (AppOps `MODE_ALLOWED` — bypasses Samsung's `MODE_DEFAULT` silent-drop) reports DENIED. One-shot via `OnboardingState.fsiPromptShown()` (SharedPreferences, `commit=true` so the marker survives the deeplink). Subsequent launches rely on the in-app Setup banner. (Phase 5a+, 2026-05-14, addresses #73.)
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
- ✅ Locale resource overlays — 6 dirs landed: `values-en-rUS`, `values-zh-rCN`, `values-ru-rRU`, `values-pl-rPL`, `values-uk-rUA`, `values-be-rBY`. Translations cribbed from watch app's HarmonyOS resources. Verified on AVD 2026-04-30 via `cmd locale set-app-locales com.kirkouski.gtwake.companion --locales pl-PL`: title "Alarms" → "Alarmy", days "Once" → "Raz", relative time "In 16 hr." → "Za 15 godz." on next launch. (Phase 3c, 2026-04-25.)
- ✅ `LocalConfiguration.current.locales` honored — `NextAlarmGlanceWidget.computeNext` reads `context.resources.configuration.locales[0]`; `TimeFormatter` delegates to `DateFormat` which itself respects the configuration locale. Zero `Locale.getDefault()` / `Locale.US` / `Locale.ENGLISH` / `Locale.ROOT` calls in production code (verified via Grep 2026-04-26). Verified end-to-end on AVD 2026-04-30 via per-app locale switch — list strings reflow on next process launch. Audit gap: code paths added in future may regress this — consider a custom Detekt rule when patterns stabilise.
- ✅ Time format follows system 12h/24h — `util/TimeFormatter.kt` uses `DateFormat.is24HourFormat(context)` + `DateFormat.getTimeFormat(context)`. Used by AlarmListScreen, AlarmActivity, AlarmNotifications, NextAlarmGlanceWidget. Verified on AVD 2026-04-30: en-US locale renders "7:00 AM" while pl-PL renders "07:00" — Polish 24h system pref honored after locale switch. (Phase 3c, 2026-04-25.)
- 🟡 Day-letter labels in chip row pulled from `strings.xml` — `util/DayLabels.kt:shortLabelResForDayBit(bit)` maps each `DaysOfWeek` bit to its `R.string.day_*_short`. 2-letter glyphs (`Mo`/`Tu`/...`Su`) used in base + 6 locale dirs. (Phase 3c, 2026-04-25.)
- ✅ `Configuration.uiMode` (light/dark) — `Theme.kt` reads `isSystemInDarkTheme()` and selects `dynamicDarkColorScheme(context)` / `dynamicLightColorScheme(context)` (Material3 dynamic color). Manual fallback colors defined for non-dynamic devices. Verified on AVD 2026-04-30 via `cmd uimode night yes/no` — Material You dynamic palette recomposes cleanly between light and dark.
- ✅ Per-app language picker support — `res/xml/locales_config.xml` lists all 6 locales; manifest declares `android:localeConfig="@xml/locales_config"`. Verified on AVD 2026-04-30 via `cmd locale set-app-locales com.kirkouski.gtwake.companion --locales pl-PL` (the per-app locale API path) — strings reflow and time format flips on next launch. (Phase 3c, 2026-04-25.)
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

### Watch-background re-sync handshake (1.0.6)
- 🟡 The default watch background is uploaded as a Wear Engine file whose name carries a stable content hash (`bgd_<hash>.bin`); the phone persists `lastUploadedWatchBgHash` in `SettingsStore`. The watch parses the hash from the file name, validates the `.bin` still exists on launch via `@system.file` (was blindly restoring a possibly-deleted path → blank), and **reports its current bg hash inside the existing `watch_screen` reply** (no new message type).
- 🟡 On the `watch_screen` reply the phone reconciles: if it has a bg set AND the watch's reported hash differs (or is empty — the watch lost it after reset/reinstall) → re-upload the current bg (re-encoded from the cached crop for the same resolution); if the phone has no bg but the watch still reports one → send the clear. This auto-restores the background after a watch reset and guarantees the watch never shows a mismatching image. Caveat: a watch **resolution/model change** still needs a re-pick (the original SAF source isn't cached, only the per-resolution crop).
- 🟡 Settings watch-bg block gating (1.0.6, revised on device feedback): the ENTIRE watch-background block is dropped unless a watch is connected AND its shape is known (`watchConnected && state.watchScreenWidth > 0`) — no placeholder (we can't render a shape we don't know). `watchConnected` comes from the reliable connection signal below, not a stale send-state.
- 🟡 Reliable watch-connection detection (1.0.6): `WearBridgeService.statusFlow` is the single source of truth for EVERY watch-status surface (alarm-list sync card + Settings block). It is kept authoritative by (a) a one-shot LOCAL `DeviceClient.getBondedDevices().any{isConnected}` query — a query to the phone's Huawei Health pairing service, NOT a P2P round-trip, so zero watch traffic / no ping — seeded on every `MainActivity.onStart` + on Settings open, and (b) a `MonitorClient` connection-status monitor (`MonitorItem.MONITOR_ITEM_CONNECTION`) registered on `onStart`, unregistered on `onStop` — event-driven, **no background service**. The monitor's payload int mapping is undocumented (verified by decompiling `wearengine-5.0.1.300.aar`), so each change event just re-runs the authoritative local query rather than trusting the value. Replaces the prior design where status only flipped to CONNECTED during an active P2P send (read "not connected" when idle). **Monitor delivery may be subject to the same Activity-in-task constraint as P2P receive — verify on device; the seed-on-resume keeps status correct regardless.**
- 🟡 `watch_default_bg_cleared` reliability (1.0.6): the clear send now uses the force-wake + retry path (`performSend(forceWake=true, retryOnError=true)`) like the bg upload — a non-forced send at Settings time (watch asleep) hit a stale "running" cache and delivered into the void (clearing never reached the watch).

### Force-sync hash precheck (Phase 5b)
- 🟡 `AlarmHash.kt` + `alarmHash.js` produce byte-equivalent 8-char hex hashes from a canonical pipe-delimited render of the alarm list, sorted by id. Empty list → `"00000000"`. Java `String.hashCode()` (Kotlin) mirrored as `((h<<5)-h+c)|0` (JS). Test coverage in `AlarmHashTest.kt` pins canonical strings + reference vectors.
- 🟡 `WearBridgeService.forceSync(suspend () -> List<Alarm>)` rounds-trips `sync_check` → `sync_hash` before pushing. Phone re-snapshots its own alarm list **after** receiving the watch's response so a TOCTOU race (user adds an alarm during the ~2s window) doesn't accidentally match a stale local hash.
- 🟡 On hash mismatch, force-sync makes the watch's alarm set **EXACTLY equal** the phone's: phone sends ONE authoritative full-state replace (full list) and the watch replaces its `AlarmStore` wholesale + tombstones every dropped id. The earlier additive `alarm_added`-per-row push could never prune watch-only rows, so the watch monotonically accumulated stale alarms and the hash never converged. (Phase 5b, 2026-05-17.)
- ✅ **Full replace is text-or-file by size (>3-alarm sync fix, 2026-06-23).** Device-verified on GT 6 Pro: a 4-alarm/1068 B replace went over the 768 B text cap → file transfer → `incoming.sync_replace applied n=4 dropped=0 pruned=0` → `alarms_received` ack → `pushFileReplace confirmed … after 1 attempt`, all 4 alarms visible on the watch, and the previously-perpetual `sync_replace n=4` re-push loop stopped (converged). The Wear Engine P2P **text-message** ceiling is ~1 KiB on the GT 6 (measured: 3-alarm/817 B round-trips; 4-alarm/1068 B returns transport 207 but is truncated before the watch `JSON.parse`, so the watch silently kept 3 and the phone re-pushed forever). `pushFullReplace` now sends the replace as **text only when ≤ `SYNC_REPLACE_MAX_BYTES` = 768 B**; above that, `pushFileReplace` stages the identical `sync_replace` JSON to `alarms_<hash>.json` and sends it as a **Wear Engine file transfer** with app-level ack (`alarms_received`, hash echoed from the filename) + retry — the same proven pattern as the watch background image (`pendingAlarmsAck` ↔ watch `WearBridge.sendAlarmsReceived`). Watch `fileHandler` branches on the `alarms_` name → `IncomingHandler.applyReplaceFromFile` (validate + `replaceAll` + tombstone pruned, so the file path **still prunes**) → acks after the store write commits. The `alarm_added` burst is now only a last-ditch fallback if the file transfer itself fails. The old 4 KiB text cap sat ~4× above the real ceiling, which is why >3 alarms silently failed to sync. (See `sync-architecture.md` §2.1 + §5.7.)
- 🟡 Force-sync always sends a `sync_done` marker last (matched and replaced paths). The watch terminates on it immediately instead of waiting out the drain-quiescence timer — closes the race where the watch self-terminated between `sync_check` and the data push (`forceSync done sent=0 of=1`). (Phase 5b, 2026-05-17.)
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

**Acceptance criterion — watch rings on command without prior sync (2026-05-20).** The watch is a thin client: it MUST be able to render its ring page from the `alarm_fired` envelope alone, even for an alarm it has never received an `alarm_added` / `sync_replace` for. The phone never assumes the watch's local store is populated when issuing a ring command. Practical implications:
- `alarm_fired` carries the thin-client ring hints: `alarmId`, `updatedAtEpoch`, `vibrationPattern` (enum name), `watchVibrationEnabled` (1.0.6 — when `false` the watch skips its `startVibrate` loop, supporting the per-device "silent" state), `snoozeAllowed` (pre-computed `alarm.isSnoozeEnabled` — collapses both `snoozeMinutes==0` and `consecutiveSnoozeCount >= maxSnoozeCount` so the watch hides Snooze verbatim). The watch reads these BEFORE falling back to `AlarmStore`. Hour/minute/daysOfWeek/isVibrationOnly come from the synced cache (it's already there in steady-state; the thin-client criterion targets ring affordances, not the clock readout). `label` is NEVER on the wire (PII, phone-only — see `sync-architecture.md`).
- Watch ring page reads display fields from the envelope first; AlarmStore lookup is a fallback for older envelopes only, never required.
- This is the foundation for any "phone fires from a state the watch hasn't synced yet" scenario — Before-First-Unlock (BFU) alarms, cold-start fires, watch reboot-mid-sync, paired-but-never-synced first install.

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
- [ ] `adb shell dumpsys alarm | grep com.kirkouski.gtwake.companion` shows `setAlarmClock` registered
- [ ] Lockscreen test: alarm +60 s, screen off, wait → `AlarmActivity` appears over keyguard, screen on
- [ ] Boot reschedule: `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.kirkouski.gtwake.companion/.scheduler.BootReceiver` then re-check `dumpsys alarm`
- [ ] Doze: `adb shell dumpsys deviceidle force-idle`; `setAlarmClock` is whitelisted and still fires
- [ ] Force fire without waiting: `adb shell am start-foreground-service -a com.kirkouski.gtwake.companion.ACTION_RING --el alarm_id 1 -n com.kirkouski.gtwake.companion/.ring.AlarmRingService`
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

## DISTRIBUTION

Three channels for the phone app, one for the watch. **All phone channels must ship the same signing
certificate** (`gtwake.phone`, RSA 2048, SHA-256 `95F6:00:1E:…`): Google Play App Signing is configured
with our own uploaded key rather than a Google-generated one (verified 2026-08-24 in Play Console —
app-signing key SHA-256/SHA-1/MD5 all match the local keystore). That parity is load-bearing twice
over: Android refuses cross-cert updates, and the watch's Wear Engine `supportLists` is keyed on the
phone cert. A channel signed with a different key would silently lose both.

- ✅ Google Play — AAB re-signed by Play with our key; app-signing cert confirmed `95F6…`
- 🟠 Huawei AppGallery — `gtwake-appgallery-app-signing.zip` packed 2026-06-10, but **upload to AGC
  App Signing (Method 2) is unconfirmed**. Caveat: if Huawei generated its own key instead, the
  AppGallery build's cert differs and watch pairing breaks for exactly the users most likely to own
  the watch. Verify the registered cert in AGC reads `95F6…`
- ✅ Direct APK at `gtwake.kirkouski.com/download` — deployed 2026-08-24; page, staging script and
  cert guard all live. Guard proven by a negative test against a differently-signed APK
- ✅ Served file verified byte-identical to the `dist/` artifact — production `curl` returns
  `content-length: 5812620` and SHA-256 `670893e5…429d3a`, with
  `content-type: application/vnd.android.package-archive`
- 🟠 Missing/stale APK URLs return `200 text/html`, not 404 — Pages ignores a 404 status in
  `_redirects` (tested). Mitigated: `_headers` keys the exact filename so a miss isn't mislabelled as
  an APK, and the router sends `/apk/*` to `/download`. Caveat: **never verify a download by status
  code alone** — assert `content-length` or re-hash
- ❌ Sideload verified as an in-place update over a Play install (no uninstall prompt, alarms preserved)
- ❌ Watch pairing verified on a sideloaded install
- 📋 Auto-update for sideloaded installs — out of scope: requires `INTERNET`, which the published
  privacy policy states the app does not declare
- 📋 Google's sideload developer-verification regime (Sept 2026 BR/ID/SG/TH, global 2027) — revisit
  before the global phase; no effect on EU users today

## KNOWN GAPS TO CLOSE NEXT

### Android
0. **Pre-release legal/correctness backlog (Phase 5a+, 2026-05-14):**
   - ✅ **Icon attribution — RESOLVED (2026-05-18).** The whole icon set (both apps) was migrated from Flaticon to [Tabler Icons](https://tabler.io/icons) — MIT, no attribution required — generated by `tools/icongen` (Tabler SVG → gradient/circle/raw PNG). No Flaticon assets ship in `res/`. The orphaned Flaticon source PNGs under `icons/` + `icons/attribution.txt` are staged for deletion.
   - 📋 **Database migrations — deferred to the public-release cutoff (policy, 2026-05-19).** Pre-release there are no real installs to protect: any schema or storage change is handled by wipe-and-reinstall, NOT `Migration` code (see `memory/no_migration_until_release.md`). `DatabaseModule` keeps `fallbackToDestructiveMigration` (debug-gated). A real migration path — and a release-build schema-mismatch test — become required only at the first public-release build, not before. When a schema bump lands, the dev is told to wipe-reinstall.

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

---

## ROADMAP — backlog features, tiered by alignment

Recovered from prior-session conversation + memory in 2026-05-20.
**Product positioning anchor:** the watch is the main control unit — the
core promise is "you don't reach for the phone." Tiers are set against
that, NOT against generic alarm-app feature parity. "Using the alarm" =
ring → dismiss/snooze flow; "creating" stays phone-only by design.

Symbol legend: 🎯 high alignment (watch-first reliability or wrist-only UX) ·
🤝 supports watch-first indirectly · 📞 phone-side ergonomic · 🧭 orthogonal.

### Tier 1 — TODO (ship pre-1.0)
The foundation under the watch-first promise. If either of these is missing
the proposition itself wobbles.

- 🟡 🎯 **BFU / direct-boot alarms** (Phase A v1 coded 2026-05-20, awaiting on-device verification). Fire before the user unlocks the phone after reboot. Without this, an OS update / low-battery shutdown overnight = no morning alarm; the watch can't compensate. Reference: BlackyHawky/Clock (see `docs/references.md`).
  - **Components made `directBootAware="true"`:** `BootReceiver` (already), `AlarmBroadcastReceiver`, `AlarmRingService`, `AlarmActivity`. The `GtAlarmApp` Application class is NOT direct-boot-aware — the receiver path tolerates a partially-initialised process.
  - **Storage:** `data/bfu/BfuAlarmCache` — JSON file in `Context.createDeviceProtectedStorageContext().filesDir`. Schema v3. Mirror of enabled alarms (id, hour, minute, daysOfWeek, enabled, isVibrationOnly, snoozeMinutes, updatedAtEpoch, relativeMinutes, selfDestruct, vibrationPattern, volumeRampSeconds, maxSnoozeCount, consecutiveSnoozeCount, skipNextEpoch). Intentionally omits PII (label) and SAF URIs (audio / background) — both phone-only or unresolvable pre-unlock.
  - **Write-through:** every `AlarmRepository` mutation (`save`, `saveLocalOnly`, `setEnabled`, `setEnabledLocalOnly`, `delete`, `ensureDebugAlarmId`) updates the cache. `rescheduleAll` + `rescheduleAllOnBoot` rebuild the cache from Room as the post-unlock source-of-truth pass.
  - **Boot branch:** `BootReceiver` routes `LOCKED_BOOT_COMPLETED → repository.rescheduleFromBfu()` (no Room); `BOOT_COMPLETED → repository.rescheduleAllOnBoot()` (Room); other broadcasts → `rescheduleAll()`.
  - **Ring path pre-unlock:** `AlarmRingService.fetchAlarmForRing` reads Room when unlocked, otherwise the BFU cache. Audio routes through `AlarmAudioPlayer.start(forceBundledFallback = !isUserUnlocked)` which plays `R.raw.fallback_alarm` directly (ContentResolver-backed SAF + system-default URIs return null pre-unlock). Pre-unlock `handleDismiss` notifies the watch via `wearBridge.sendAlarmDismissed` (transport-only suspend) under `PRE_UNLOCK_SEND_TIMEOUT_MS = 3000ms` inside `serviceScope.launch` so the FGS stays alive during the send. `handleSnooze` reads `snoozeMinutes` from the BFU cache, calls `scheduler.scheduleAt`, then sends `wearBridge.sendAlarmSnoozed`.
  - **Phase A v1 scope cuts (documented):**
    - **Recurring alarms only re-armed pre-unlock.** `rescheduleFromBfu` filters to `daysOfWeek != 0`. One-shots scheduled to fire pre-unlock get a missed-during-downtime notification + DISABLE (non-self-destruct) or DELETE (self-destruct / relative) on unlock via `rescheduleAllOnBoot`'s `intendedFireForOneShot` helper, which computes the fire time as scheduled at last user action (not "now") so a missed 7 AM doesn't silently roll to tomorrow's 7 AM.
    - **Lock-screen dismiss persists across unlock.** `AlarmRingService.handleDismiss` pre-unlock calls `bfuCache.markDismissed(id)`, recorded in the BFU cache's `pendingDismissals` set. `AlarmRepository.rescheduleAllOnBoot` drains the set at the start of post-unlock reconcile and applies the same `dismissAction` the live ring path uses (DELETE / DISABLE / KEEP). Without this, a one-shot dismissed at 07:00 pre-unlock would re-fire at 07:00 tomorrow because its Room `enabled` flag was never updated.
    - **Watch sync best-effort pre-unlock.** Wear Engine binding may or may not work in direct-boot mode (Huawei doesn't document this); the ring envelope is self-sufficient per the thin-client criterion above, so the watch CAN ring without prior sync if Wear Engine functions pre-unlock. **TODO on-device verification required** — `GtAlarmApp.onCreate` defers `setIncomingHandler` pre-unlock on the assumption Huawei Health needs the credential keystore, but `preArmWatch` pre-unlock already calls `sendAlarmFired` through the same binder. Both can't be correct; resolve by reboot + schedule +5min + don't unlock + observe whether watch rings.
  - **Architecture choice — parallel cache vs DB-in-DPS:** BlackyHawky/Clock (and AOSP DeskClock) move the **whole Room database** to device-protected storage via `Context.moveDatabaseFrom`. That gives full pre-unlock CRUD (dismiss/snooze persists in the same DB) at the cost of putting alarm `label` strings in device-derived-key storage rather than user-credential-encrypted. We chose a **parallel cache** instead: labels stay phone-only (PII), one less migration to ship, and the watch is the primary control surface anyway. Trade-off: pre-unlock dismiss/snooze don't persist in v1, mitigated by the recurring-only re-arm + the watch's own ring-end ack flow. If the parallel-cache scope cuts prove painful on real hardware testing, we can revisit by migrating Room to DPS for Phase A v2.
- 🟡 🎯 **Custom vibration patterns per alarm** (coded 2026-05-21, awaiting wrist-feel verification on real GT 6 Pro). Distinct wrist buzz per alarm so the user knows what fired without looking (wake-up vs meds vs meeting). The only feature on this list that pays off ONLY on the wrist — squarely on-positioning. Today only `isVibrationOnly` boolean. Phone gets full `VibrationEffect.createWaveform` expressivity. Watch is constrained — Lite Wearable `@system.vibrator.vibrate()` accepts only `{mode: 'short' | 'long'}` (verified against [official docs](https://device.harmonyos.com/en/docs/apiref/lite-wearable-system-vibration-0000001222448285), no `pattern` / `duration` / `intensity` parameter). Custom patterns on the watch = orchestrated `setTimeout` sequence of `short`/`long` calls; we already do interval-driven pulsing today (`startVibrate` / `_vibrateTimer`), so extending to per-pattern timing is a small change. Realistic preset set (Lite-expressible): **Pulse** (`long`, 1500ms loop), **Heartbeat** (`short`, 150ms, `short`, 1000ms loop), **Three-tap** (`short`, 200ms, `short`, 200ms, `short`, 1200ms loop), **Long-long** (`long`, 800ms, `long`, 1200ms loop), **Off** (no vibration — audio-only). Three-tap and Long-long landed with slightly slower pulses than the original pitch because the GT 6 Pro's short-mode envelope clips below ~150ms — the shipped numbers are what the wrist actually feels. Wire format adds `vibrationPattern: String` (enum name) to `alarm_fired` so the watch can ring without prior sync (thin-client criterion above).

### Tier 2 — TODO (ship pre-1.0 if Tier 1 lands with budget left)
Ring-quality polish + small management features. Cheap-to-mid effort, real
user value.

- 🟡 🎯 **Skip next occurrence (phone-side, swipe-left on a recurring alarm)** (coded 2026-05-21, verified on emulator with mid-swipe screenshots). For a recurring alarm ("Wake up · Mon–Fri 7:00"), swipe-left on the list row marks the very next firing to be skipped — the alarm stays enabled and recurring, and the firing after that is unaffected. Use case: holiday tomorrow, sick day, one-off late start. Phone-only mutation per the Phase 0b pivot (watch is read-only display + toggle/delete — no edit gestures on the wrist). Implementation: new nullable `skipNextEpoch: Long?` on `Alarm`; `NextTriggerCalculator` advances past it when the skipped instant arrives, then clears the field; phone schedules the post-skip trigger via `setAlarmClock` as usual. Phone list row shows a "next: skipped" subtitle hint when set. Conflicts with current swipe-to-delete direction (`SwipeToDismissBox`) — coordinate gestures: swipe-left = skip-next, swipe-right (or trailing trash icon / long-press) = delete, using a leading-vs-trailing swipe split. Whether to send `skipNextEpoch` to the watch is a sub-decision — it only matters if the watch surfaces a next-firing line; defer until that UI lands.
- 🟡 🎯 **Max-snooze-count limit** (coded 2026-05-21). Cap consecutive snoozes (e.g. 3 in a row), then the Snooze button disappears on **both** the phone (AlarmActivity + notification action) AND the watch (`snoozeAllowed=false` inlined into the `alarm_fired` envelope so the watch gates without prior sync). Phone-side counter `Alarm.consecutiveSnoozeCount` increments on every snooze (local OR peer-driven), resets on real dismiss. Distinct from `snoozeMinutes=0` (which disables snooze from the start). `Alarm.isSnoozeEnabled` collapses both conditions into one boolean the watch applies verbatim.
- 🟡 🤝 **Volume ramp / crescendo / fade-in** (coded 2026-05-21). Phone-side audio change; linearly ramps the alarm MediaPlayer's per-player attenuator from near-silence (0.01) to full (1.0) over N seconds (0..60, configurable per alarm). Softer wake, no user interaction required. Passive, on-positioning by virtue of not competing with the watch flow. **Design choice:** ramp is the player-scoped float `setVolume`, NOT `AudioManager.setStreamVolume(STREAM_ALARM, …)`. Programmatically raising STREAM_ALARM would clobber the user's system volume across other alarms / future sessions and would need `MODIFY_AUDIO_SETTINGS`. The per-player ramp respects the user's stream level — if STREAM_ALARM is at 30%, ramp 0→1 means silence→30% (relative ramp). The semantically-equivalent "ramp to whatever the user has set" is the better default.

### Tier 3 — somewhere in future (de-prioritised, not pre-1.0)
Lower alignment with the watch-first positioning OR pure parity features.
Parked here so they don't keep re-surfacing as "did we forget X" — we
didn't, they're just lower priority. Each can graduate to a higher tier
if the product direction shifts (e.g. a user is reliably without a watch).

- ❌ 🤝 **Per-alarm volume override.** Phone-side per-alarm volume slider. Niche — the user is increasingly on the watch path and not hearing the phone anyway.
- ❌ 📞 **Shake to dismiss.** Accelerometer-based dismiss on the phone. Phone-in-hand assumption, contradicts the positioning. Useful only when the watch is dead / charging.
- ❌ 📞 **Volume +/− buttons as dismiss / snooze.** Phone hardware buttons configurable as alarm actions. Same phone-in-hand assumption.
- ❌ 📞 **Alarm label on the phone lockscreen ring.** Label already bounded (256 chars) but `AlarmActivity` doesn't surface it prominently. Looking at the phone defeats the positioning.
- ❌ 🧭 **Ringtone library / white-noise presets.** Bundled tones beyond the SAF picker. Generic parity feature, not watch-specific.

### To investigate (research before tiering)
- 🔬 **Watch playing alarm audio (ringtone).** **Researched 2026-06-22 (1.0.6):** the Lite Wearable JS runtime ships exactly 20 `@system.*` modules (verified in the DevEco SDK `js/api/` tree) — **none** for audio, media, or recording; Huawei's Lite Wearable docs state audio/video playback is unavailable on Lite; the audio dev-guidance that exists is for full HarmonyOS (ArkTS/Stage model) only. Strong conclusion: **watch-side audio is not feasible** on the GT 6 Lite Wearable without abandoning the Lite model. An on-device probe build (`f3-audioprobe` — resolves candidate audio/recorder modules at runtime + logs via WatchLog) is staged to confirm empirically; **verdict pending a connected device**. Watch stays vibration-only; a phone-side "default alarm tone" setting is the fallback if desired.

### Out of scope (recorded so they don't re-surface)
Searched memory + transcripts; these are aspirational alarm-app ideas that
were NEVER planned for GT Alarm. Listed for the record:

- 📋 Calendar / holiday skip, sleep tracking + smart wake via HR sensor, weather-based alarms, wake puzzles / math / captcha dismiss, cloud backup-restore, voice / assistant integration.

A Tier-3 item can be promoted to Tier 2 (or higher) when a real user need
shows up. When work starts on any backlog item: cut a phase entry in
`execution-plan.md`, move the line up to "KNOWN GAPS TO CLOSE NEXT" with
the proper status icon, and link the implementation file.
