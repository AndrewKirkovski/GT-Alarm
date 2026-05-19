# GT Alarm — Multi-phase execution plan

**Status:** in-progress · **Created:** 2026-04-25 · **Reconciled:** 2026-05-19

Breakdown of every gap found by the 2026-04-25 anti-lazy scan, organised into phases that each end with a code-review pass. Each phase is verifiable before the next begins.

> Companion to `acceptance-criteria.md` and `sync-architecture.md`. Re-read both before starting any phase.
> **The phase numbering below is historical** — the watch app was rewritten mid-plan (see the Reconcile section). `acceptance-criteria.md` → "KNOWN GAPS TO CLOSE NEXT" is the live task list.

---

## Reconcile — current status (2026-05-19)

This plan was written against the **HarmonyOS NEXT** watch app. On 2026-04-27 the watch was **rewritten as a HarmonyOS LiteWearable app** (ACELite FA-model JS) — the GT 6 will not run a NEXT / Stage-Model app. The legacy app is parked in `watch-app.old/`. This supersedes the watch-side phases:

- **Phase 0a (watch codelinter / ohosTest / `safeResourceColor`)** — SUPERSEDED. The LiteWearable app has no ArkTS, no `ColorMetrics`, no Hypium/ohosTest. Watch linting is `codelinter.sh`; there is no watch unit-test suite.
- **Phase 1 (watch Hypium unit tests)** — SUPERSEDED for the same reason. Watch logic is plain ES5 JS, verified on-device.
- **Phases 0b / 2 / 3 / 4 (Android)** — substantially LANDED. Android is on the 2026 SDK stack; the three-layer linter (kotlinc `-Werror` + Android lint + detekt) is enforced; edge-to-edge, locale overlays, swipe-to-delete, widget refresh, the battery card all shipped. Per-criterion status lives in `acceptance-criteria.md`.
- **Migrations:** Phase 4b added a Room v1→v2 migration, but the project policy since 2026-05-13 is **no migration code until the first public release** — pre-release schema bumps are handled by wipe-and-reinstall (`memory/no_migration_until_release.md`). `DatabaseModule` uses `fallbackToDestructiveMigration` (debug-gated).

**Shipped since (DONE sections at the bottom):** Phase 5a, 5a+, the LiteWearable watch rewrite, and the post-5a+ icon-system + UI/UX + watch-input pass.

**Current blocker:** Huawei AppGallery / Wear Engine dev-account approval — every cross-device sync criterion (`acceptance-criteria.md` #21–26) is coded (🟡) and cannot be verified until P2P transport is live.

---

## Findings summary (from 4 parallel scans, 2026-04-25)

**Watch app:** AC items 1–20 all coded ✅/🟡 with no observed-vs-claimed discrepancies. Build clean (one expected `signingConfig` warning). 4 ArkTS warnings on `ColorMetrics.resourceColor` ("Function may throw exceptions") that we have not yet fixed — addressed in Phase 0a step 3 (extract `safeResourceColor` helper). **Zero unit tests.** No `entry/src/ohosTest/` folder. LWW prep #21–25 ❌ (expected — gated on Wear Engine).

**Android app:**
- SDK pins **all out of date** vs AC: compileSdk 35 (need 36), minSdk 29 (need 31), AGP 8.7.3 (need 9.1.1), Kotlin 2.1.0 (need 2.3.21), Compose BOM 2025.01.00 (need 2026.04.01), Room 2.6.1 (need 2.8.4), Hilt 2.53.1 (need 2.57.1), Coroutines 1.9.0 (need 1.10.2), Glance 1.1.1 (need 1.2.0).
- AC features missing: `enableEdgeToEdge()`, MaterialExpressiveTheme, predictive-back wiring, widget auto-refresh from mutations, swipe-to-delete, one-off auto-disable, Samsung battery rationale card, `Locale.US` hardcoded in `AlarmNotifications`, no locale overlays, no `locales_config.xml`, no `DateFormat.is24HourFormat` use.
- Tests: 7 NextTriggerCalculator unit tests exist; everything else untested. No instrumented tests.
- Build env: requires JDK 17+; the running shell currently has JDK 8. **Verify env before any version bump.**
- LWW prep #21–25 ❌ (same as watch, expected).

**Linter strategy (research outputs):**
- **Watch:** `codelinter` ships with DevEco. Config `code-linter.json5`. No native baseline — convention: `.strict-baseline.json` listing approved exceptions with ticket refs. The 4 `ColorMetrics.resourceColor` warnings are REAL ("may throw" is a true contract); fix by extracting to a `safeResourceColor()` wrapper, NOT by suppressing.
- **Android:** Three-layer policy — Android `lint` (`warningsAsErrors = true`, `lint-baseline.xml`), Kotlin compiler (`allWarningsAsErrors = true`), `detekt` (`detekt-baseline.xml`). Custom Gradle task validates that every `@Suppress(...)` and `tools:ignore=...` has an adjacent `// reason: ...` comment. Undocumented suppression = build fails.

**Test strategy (research outputs):**
- **Watch:** Hypium (`describe`/`it`/`expect.assertEqual`) under `entry/src/ohosTest/ets/test/`. CLI: `hdc shell aa test -b <bundle> -m entry_test -s unittest /ets/testrunner/OpenHarmonyTestRunner`. No built-in coverage. Pure-target candidates: `AlarmItem` (DOW, toReminderDays, formatTime), `util/I18n` (orderedDayBits, firstDayBit, dayKeys), `util/Logger` (describeError). Mocked candidates: `AlarmStore` (preferences), `ReminderService` (reminderAgentManager).
- **Android:** JUnit 4 (in current deps), MockK + Turbine + Room testing already wired. `./gradlew :app:testDebugUnitTest` + Jacoco. Pure: extend `NextTriggerCalculatorTest`, add `DaysOfWeekTest`, `MappersTest`. Mocked: `AlarmSchedulerImplTest` (mockk AlarmManager), `AlarmRepositoryTest` (in-memory Room). Instrumented: Room DAO Flow emission timing, `AlarmActivity` lockscreen takeover.

---

## Phase 0 — Linter & test scaffolding (foundation)

**Goal:** make warnings into hard failures so subsequent phases can't regress quality. No feature work in this phase.

### Watch (Phase 0a)
1. Add `code-linter.json5` at `watch-app/` with strict ruleset.
2. Add `entry/src/ohosTest/` test scaffold + Hypium runner config.
3. Replace `ColorMetrics.resourceColor('#XXXXXX')` call sites with a `safeResourceColor` helper in `util/ColorUtil.ets` (try/catch). This is the ONLY way to silence the 4 `may throw exceptions` warnings without lying.
4. Build must pass with **0 warnings** other than the expected `signingConfig` one (deferred per AC #19).

### Android (Phase 0b)
1. **Verify JDK 17+ is available** before any version bump (the build env had JDK 8 in the scan; cannot proceed otherwise).
2. Add `app/lint.xml` (severity overrides) + run `./gradlew :app:lintDebug -Dlint.baselines.continue=true` to seed `lint-baseline.xml`.
3. Add `kotlinOptions { allWarningsAsErrors = true }` and `lint { warningsAsErrors = true; abortOnError = true }` to `app/build.gradle.kts`.
4. Add `detekt` plugin + `detekt.yml` + seed `detekt-baseline.xml`.
5. Add a Gradle task `lintSuppressionsValidated` that fails if any `@Suppress(...)` or `tools:ignore="..."` lacks an adjacent `// reason: <ticket-or-issue>` line. Document the policy in repo `CLAUDE.md`.
6. Build must pass with **0 net new warnings** beyond the seeded baseline; baseline file is committed and reviewed.

### Phase 0 review
Spawn 2 agents in parallel:
- **Watch reviewer** — verify `safeResourceColor` correctness, codelinter config, ohosTest scaffold compiles.
- **Android reviewer** — verify lint+detekt+Kotlin compiler are all wired and build is reproducible warning-free; `lint-baseline.xml` content is justified.

**Phase 0 exit criteria:** both apps build with no unhandled warnings; CI fails on undocumented suppressions; test scaffolds compile (no tests yet, just plumbing).

---

## Phase 1 — Watch unit tests (everything device-independent)

**Goal:** cover every pure ArkTS file with Hypium tests. Aim for 80%+ logical coverage of `model/`, `util/`, and the device-mockable paths in `service/`.

### Tests to add (`entry/src/ohosTest/ets/test/`)
1. `AlarmItem.test.ets` — `DOW.contains/toggle/todayBit`, `toReminderDays(mask)` for every meaningful mask (NONE, ALL, WEEKDAYS, WEEKENDS, single-day, mixed), `formatTime` (12h vs 24h, midnight=00:00→12 AM, noon=12:00→12 PM, 23:00→11 PM).
2. `I18n.test.ets` — `orderedDayBits()` rotates correctly for `firstDayBit` MON (default) vs SUN (en_US); `dayKeys(bit)` covers all 7 bits; `s()`/`sf()` fallback paths when `cachedContext===null`.
3. `Logger.test.ets` — `describeError` with `BusinessError` shape vs plain `Error` vs `null` vs `Object` lacking `code`/`message`.
4. `AlarmStore.test.ets` — `validate()` rejects bad hour/minute/days/label; `add()` rejects duplicate id; `update()` throws on missing id; `delete()` no-ops when id absent; JSON-parse-fail resets blob. Uses a hand-rolled mock of `@ohos.data.preferences`.
5. `ReminderService.test.ets` — mock `reminderAgentManager`; verify `publishAlarm` calls with the correct `wantAgent.parameters` (deterministic alarmId), localized action labels, days conversion via `toReminderDays`.

### Phase 1 review
Spawn 2 agents:
- **Test correctness reviewer** — does each test exercise the real branch (positive AND negative paths)?
- **Test coverage reviewer** — list files in `model/`/`util/` with ZERO tests after Phase 1.

**Phase 1 exit criteria:** `hdc shell aa test ...` passes 100% locally on the wearable emulator; reviewer confirms no untested branches in the Phase 1 target files.

---

## Phase 2 — Android version bumps + linter clean-up + unit tests

**Goal:** bring Android to the AC's pinned 2026 stack AND get the same lint discipline running. Version bumps happen here because lint output depends on AGP/lint version.

### 2a — Version bumps (one at a time, build after each)
Order: AGP → Kotlin → Compose BOM → compileSdk → minSdk → targetSdk → Hilt → Room → Coroutines → Glance.
After every single bump, run `./gradlew :app:assembleDebug --warning-mode all` and review new warnings; fix them in the same commit. **Do not batch bumps** — it makes regression isolation impossible.

### 2b — Untouched Android lint hits from the scan
1. Replace hardcoded `Locale.US` in `AlarmNotifications` (string formatting must use `LocalConfiguration.current.locales` / `Locale.getDefault()` — actually the latter is also banned per AC i18n contract; use the context-derived locale).
2. Migrate any `WindowCompat.setDecorFitsSystemWindows(window, false)` to `enableEdgeToEdge()`.
3. Wire `Notification.setColor(int)` to dynamic Material You palette / drop if useless.

### 2c — Unit tests
1. Extend `NextTriggerCalculatorTest` with DST cases (spring-forward 2am→3am, fall-back 2am→1am), midnight wrap, one-shot in past, empty daysOfWeek.
2. Add `DaysOfWeekTest` — bitmask arithmetic.
3. Add `MappersTest` — entity↔domain round-trip with all fields preserved.
4. Add `AlarmSchedulerImplTest` — mockk `AlarmManager`, verify `setAlarmClock` is called with the right `AlarmClockInfo` + reused `requestCode = alarm.id`.
5. Add `AlarmRepositoryTest` — in-memory Room DB, Turbine on `observeAll()` Flow, verify scheduler interactions on `insert`/`update`/`delete`/`setEnabled`.

### 2d — Instrumented tests
1. Room DAO Flow emission within 200 ms of insert.
2. `AlarmActivity` launches over keyguard with `setShowWhenLocked(true)`.

### Phase 2 review
3 agents in parallel:
- **Version-bump reviewer** — go through git log of bumps; verify no API regressions left unaddressed (e.g. deprecated symbols still used post-bump).
- **Lint reviewer** — every entry in `lint-baseline.xml` and `detekt-baseline.xml` has a justification (either a tracking ticket or a "won't fix because X" comment).
- **Test correctness reviewer** — same as Phase 1 but for Android.

**Phase 2 exit criteria:** `./gradlew :app:assembleDebug :app:lintDebug :app:detekt :app:testDebugUnitTest` all green with `--warning-mode all`. SDK pins match AC. Untouched lint hits closed.

---

## Phase 3 — Android missing AC features

**Goal:** close the AC items that are pure code work (no cross-device, no hardware required).

### 3a — System-bar / nav modernisation
1. `enableEdgeToEdge()` in `MainActivity.onCreate` (drop legacy decor-fits-system-windows if present).
2. Predictive back: `android:enableOnBackInvokedCallback="true"` in manifest `<application>`; wire `BackHandler` in Compose where we own back behavior.
3. Migrate to `MaterialExpressiveTheme` (compose-material3 1.4+).

### 3b — Behavior gaps
1. One-off auto-disable: when `AlarmRingService.handleDismiss()` runs for an alarm with `daysOfWeek == 0`, set `enabled = false` via `AlarmRepository`. Mirror the watch's existing logic.
2. Widget auto-refresh: every `AlarmRepository.{insert,update,delete,setEnabled}` calls `GlanceAppWidgetManager(ctx).getGlanceIds(NextAlarmGlanceWidget::class.java).forEach { widget.update(ctx, it) }`. Per AC, NOT `updateAll()`.
3. Swipe-to-delete: `SwipeToDismissBox` in `AlarmListScreen`.
4. Samsung battery-optimisation rationale card on first launch (deep-link to `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

### 3c — i18n parity with watch (6 locales)
1. Move every literal in Compose UI to `stringResource(R.string.X)`.
2. Create `res/values-en-rUS/strings.xml`, `values-zh-rCN/`, `values-ru-rRU/`, `values-pl-rPL/`, `values-uk-rUA/`, `values-be-rBY/` matching the watch's translations key-for-key.
3. Add `res/xml/locales_config.xml` + `android:localeConfig="@xml/locales_config"` (per-app language picker, API 33+).
4. Replace any hardcoded `"HH:mm"` with `DateFormat.is24HourFormat(context)` + `DateFormat.getTimeFormat(context)`.
5. Replace any hardcoded weekday ordering with `Calendar.getInstance().firstDayOfWeek`.
6. Define plurals (`<plurals>` for "in N min").

### 3d — Tests for new features
- Widget refresh: instrumented test triggering a `repository.insert(...)` and asserting `GlanceAppWidgetManager.getGlanceIds(...)` was called.
- One-off auto-disable: unit test on the dismiss path (mockk repository).
- Locale switch: instrumented test changing `Configuration.uiMode`/`locales` and asserting strings reflow.

### Phase 3 review
2 agents:
- **Feature completeness reviewer** — every AC item flipped 🟡→✅ (or ❌→🟡 if the verification needs a device).
- **i18n consistency reviewer** — Android keys match the watch's key set 1:1; no drift between platforms.

**Phase 3 exit criteria:** Android AC items 1–11 all 🟡 or ✅ (the cross-device ones stay 📋); locale parity verified; all new features unit/instrumented-tested.

---

## Phase 4 — Cross-device LWW prep #21–25 (BOTH apps simultaneously)

**Goal:** lock the wire format so Wear Engine integration in Phase 5 is plug-and-play.

### 4a — Watch
1. Add `updatedAtEpoch: number` to `AlarmItem` (required, non-undefined). Existing JSON blobs get `Date.now()` on first migration.
2. `AlarmStore.validate()` requires it; `AlarmStore.add/update/delete` stamps it before write.
3. Stamp on every mutation: `EditPage.onSave`, `Index.onToggle`, `Index.performPendingDelete`, `AlarmRingPage.onDismiss` (when flipping one-shot to disabled).
4. `WearBridgeStub.BridgeMessage` extends to include `updatedAtEpoch: number` (always) and `alarm?: AlarmItem` (on add/update). Update all `notify*` call sites to pass them.
5. Tombstone ring buffer: persist `{ id, updatedAtEpoch }` for deleted items, retention 7 days, max 256 entries. Deletion path adds, lookup checks before allowing a replay-resurrection.

### 4b — Android
1. Add `updatedAtEpoch: Long` (non-null) to `domain/Alarm.kt` and `data/db/AlarmEntity.kt`.
2. Room migration v1→v2 with proper SQL (`ALTER TABLE alarms ADD COLUMN updatedAtEpoch INTEGER NOT NULL DEFAULT 0`); replace `fallbackToDestructiveMigration` with the migration list.
3. Stamp on `AlarmRepository.{insert,update,delete,setEnabled}`.
4. `WearBridgeService` interface extends to `sendAlarmUpdate(alarm, updatedAtEpoch)`, `sendDismiss(alarmId, updatedAtEpoch)`, etc. JSON shape on the wire matches the watch.
5. Tombstone ring buffer mirror.

### 4c — Tests
- Round-trip serialization on both sides: marshal → unmarshal → equals.
- Tombstone retention: insert+delete, advance synthetic clock, verify entry purged at 7-day boundary.
- LWW receiver-side test (mocked transport): incoming `updatedAtEpoch` < local → ignore + re-broadcast; > local → apply, no re-broadcast.

### Phase 4 review
2 agents:
- **Wire-format compatibility reviewer** — phone serialiser output matches watch deserialiser input byte-for-byte for every message type.
- **Migration safety reviewer** — Room migration tested with a fixture v1 DB; existing data preserved.

**Phase 4 exit criteria:** AC items #21–25 all 🟡 (coded but unverified — full ✅ requires Phase 5 hardware).

---

## Phase 5 — Hardware verification (deferred until devices in hand)

**Goal:** flip 🟡→✅ for every gated criterion.

Sub-phases (in order):
1. **GT 6 only** (no Wear Engine): cold-start fire, reboot survival, locale switch on wearable, fullscreen `maxScreenWantAgent` actually fires.
2. **Real Samsung phone only** (no watch): One UI 7 alarm-clock indicator + Sleeping/Deep-sleeping app menu + power manager interaction.
3. **Wear Engine approved** (post-vendor): drop in `HuaweiWearBridge.kt` + replace `WearBridge.send` body on watch (per `sync-architecture.md` §7), pair phone+watch via Huawei Health, run §4.1–4.4 scenarios.

This phase is intentionally out of execution scope for the current session (no hardware available). Documented here for completeness so the AC ✅ flips happen with proper verification, not via wishful thinking.

### Phase 5a — Code-only landed (DONE 2026-04-26 → 2026-05-12)

Code shipped for cross-device LWW + receive-side scaffold + relative alarms + self-destruct + force-sync hash precheck + reverse-save edit UX. End-to-end device verification still gated on Wear Engine AGConnect approval (Phase 5 sub-phase 3).

Tasks landed:
- LWW + tombstones (phone + watch ports)
- Receive-side `IncomingMessageHandler` on both sides
- Relative-alarm + self-destruct domain model, schema v3→v4 migration, edit UI, scheduling, boot recovery
- Wire format extended for `relativeMinutes` + `selfDestruct`; codec rejects (not coerces) illegal combinations
- Watch storage migrated to `@system.file` (memory: `litewearable_storage_limits.md`)
- Watch ring page + index page + alarmStore + tombstones + lwwResolver (LiteWearable rewrite, replacing `watch-app.old/`)
- Force-sync hash precheck: `AlarmHash` (Kotlin + JS), `sync_check` / `sync_hash` envelopes, TOCTOU-safe re-snapshot, atomic-claim pending request
- Edit-screen reverse-save UX: every keystroke writes to DB locally, single batched watch push on exit, Revert button restores open-time snapshot, delete + discard confirm dialogs
- Test coverage: golden hash vectors, migration TYPE assertion + integer round-trip, mapper round-trips for new fields, receive-side handler new-field paths, repository save/local-only/push/discard paths

What ✅ verifies (when hardware lands):
- All Phase 5a 🟡 entries flip to ✅ as the corresponding live-device tests pass.
- AC items in `docs/acceptance-criteria.md` under "Relative alarms", "Self-destruct", "Alarm management" → reverse-save, "Force-sync hash precheck" track that flip.

### Phase 5a+ — Polish + feature top-up (DONE 2026-05-13 → 2026-05-14)

Multi-feature landing covering queued UX + bug-fix items. Two commits:
`e762235` (feature batch) + a follow-up review-fix commit (HIGH/MED items
surfaced in two parallel-agent review passes).

Tasks landed:
- Phase 5a B3 — shared watch background image (issue #98): drop per-alarm
  `watchBackgroundImageUri`, single shared default via
  `SettingsStore.defaultWatchBackgroundUri`, db v7→v8 destructive migration
  (debug-gated as of the follow-up review-fix commit), wire rename to
  `bg_default.bin` / `watch_default_bg_cleared`. Watch-side file-receive
  handlers DEFERRED to Phase 0b watch-app rewrite (documented in
  `sync-architecture.md` §2.4).
- FSI auto-deeplink on first launch (issue #73): `MainActivity` switches
  to `PermissionAudit.checkFullScreenIntent` (AppOps `MODE_ALLOWED`) for
  Samsung S24 compatibility, one-shot gate via
  `OnboardingState.fsiPromptShown` (`commit=true` so the marker survives
  the deeplink).
- Snooze "Off" feature: `Alarm.SNOOZE_DISABLED = 0` sentinel,
  `isSnoozeEnabled` getter, edit chip + descriptor, ring UI hides
  Snooze button, notification skips snooze action,
  `IncomingMessageHandler.applySnooze` collapses peer-snooze on
  locally-off alarm to dismiss, `AlarmRepository.snooze` guard against
  phantom 0-min re-fire.
- Help screen donate + contact (issues #90 / #91): ko-fi.com/ryotsuke +
  mailto:ryotsuke+gtalarm@gmail.com. Translations across all 6 locales.
  Problem-first card ordering (device-unsupported above donate).
- Icon migration (issue #105, supersedes #102): Material → Flaticon
  "Pixel perfect" wired across the UI, with 5 Material vectors kept for
  tight slots (Add, PlayArrow, Stop, ArrowDropDown, Check/CheckCircle,
  Close, RadioButtonUnchecked) after AVD review. Every PNG-painter Icon
  site got explicit `Modifier.size(...)`. **Pending legal**: per-pack
  Flaticon attribution expansion (see AC "KNOWN GAPS → Android" entry 0).
- PickTime-Compose wheel time picker (issue #101): replaces M3 TimePicker,
  JitPack dep with tight `includeGroup` scope.
- Swipe-cancel fix (issue #103), "Keep editing" dialog label (issue #104),
  debug-alarm 1-min snooze fix (issue #71).
- Review-fix commit: `AlarmRingService` `handlerMutex` serializing
  snooze/dismiss, broadcast order swap (local truth first), stale-snooze
  collapse-to-dismiss with self-destruct semantics intact, label PII
  scrubbed from logcat, peer-label length cap (`Alarm.MAX_LABEL_LENGTH =
  256`), URI scheme-only logging, `AlarmActivity.sendAction` race fix,
  `AlarmEditViewModel.save` `NonCancellable` guard,
  `DatabaseModule.fallbackToDestructiveMigration` `BuildConfig.DEBUG` gate.

What ✅ verifies (when hardware lands):
- AC items under "Help screen surfaces", "Dismiss & snooze" (snooze-off
  + collapse rules + handlerMutex), "Permissions" (FSI auto-deeplink),
  "Alarm management" (label cap + wheel picker) flip to ✅ as the
  corresponding live-device tests pass.
- Pre-release legal/correctness backlog (AC "KNOWN GAPS → Android"
  entries 0 a/b/c) must close before public Play Store release.

---

### Post-5a+ landings (2026-05-15 → 2026-05-19)

Not part of the original 2026-04-25 plan — queued UX/polish plus a full icon-pipeline rebuild and a watch-input research spike.

Landed:
- **Icon system** — repo-tracked generator `tools/icongen` (Tabler SVG → PNG: gradient-circle / raw / gradient-stroke / filled modes). Replaced the Flaticon set on both apps; closed the Flaticon attribution legal gap (`acceptance-criteria.md` KNOWN GAPS Android #0). Phone bottom-tab navigation (`ui/nav/BottomBar.kt`).
- **Brand palette** — fixed 6-colour palette (primary cyan `#009EDA`) applied to both apps; Material You dynamic colour dropped in favour of the fixed brand scheme.
- **Compose UI restyle** — collapsible `AccordionSection` component; Help + Settings rebuilt as edge-to-edge accordion lists; Help-screen content rework (voice-section dedup, brand-tip trim to non-checklist items, single immediate Debug button, dead "Open Default apps" button removed, unreachable `help_status_none` state removed).
- **Watch-sync authorize flow** — the watch-sync card's button launches the Wear Engine permission dialog directly when permission is missing (tri-state `hasWatchPermission`).
- **Watch input research + crown-to-snooze** — established (memory `litewearable_input_capabilities.md`) that the GT 6 physical button is system-reserved (no JS event reaches a third-party app) and the crown only drives a focused `<list>`'s scroll. Shipped crown-to-snooze on the ring screen: a hidden, always-scrollable `<list>` debounces `scrollend` ticks into gestures — one rotation → snooze, two → dismiss.

Pending device verification: crown-to-snooze timing tuning on the GT 6; the palette / UX pass rendered on the watch.

---

## Phase ordering rationale

| Phase | Why now |
|---|---|
| 0 | Lint discipline must be in place before any feature work or no rule has teeth. |
| 1 | Watch is more "done" than Android per the scan — finishing tests there is the cheapest confidence gain. |
| 2 | Android needs a version-pin alignment AND lint plumbing AND tests; bundle them. |
| 3 | Now Android has the modern toolchain to support new features (Material Expressive, predictive back, locales_config). |
| 4 | LWW prep touches BOTH apps' data models — do it after each side has independent tests so regressions are caught locally before cross-device matters. |
| 5 | Hardware-gated; cannot start until devices land. |

---

## Skip list (intentionally NOT in scope of any phase)

- App icon resize 1024→216 — purely visual, can be done at any time
- AppGallery signing config — gated on Huawei dev account
- Reading "Next firing time" relative+absolute on phone list — UI polish; can be done in Phase 3 if time permits
- Plurals UI strings — defer until UI surfaces them
- Real-device wallpaper / digit-shaping for Arabic-style locales — out of scope for the 6 declared locales

---

## Tracking

Every phase opens a TaskCreate group. Phase exit criteria are checked off in `acceptance-criteria.md` (status flips). Pinned items in `memory/watch_pending_after_i18n.md` get re-evaluated at Phase 5.
