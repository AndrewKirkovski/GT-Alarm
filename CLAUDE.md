# GT Alarm — Repo-level Claude Instructions

## MANDATORY: Read UI design rules before any UI change

**Before touching any screen layout, card, gradient, fade, or pill — read [`docs/ui-design-rules.md`](docs/ui-design-rules.md) end-to-end.**

Key rules that have been violated repeatedly:

- **Pills hover OVER content — content scrolls UNDER the pill. No gap between card and pill.**
- **Whole page scrolls as one unit. Cards have NO internal scroll.**
- **The card ends where it ends.** You CAN scroll it so the card's bottom edge clears the pill — that's by design. The bottom spacer inside the card enables this.
- **Fade on the outer viewport Box** (not on the card). Fade starts at mid-pill. The fade is the visibility safeguard for content sliding under fixed headers/footers.
- Do NOT apply `calculateBottomPadding()` to the outer Column — it creates a gap above the pill.

---

## MANDATORY: Read acceptance criteria before any tech decision

**Before any of the following actions, read [`docs/acceptance-criteria.md`](docs/acceptance-criteria.md) end-to-end:**

- Pinning or bumping any SDK / API version (Android `minSdk` / `targetSdk` / `compileSdk`, HarmonyOS `minAPIVersion` / `targetAPIVersion` / `apiReleaseType`)
- Adding, removing, or upgrading any library / dependency
- Adding or removing any manifest permission (Android) or `module.json5` permission (HarmonyOS)
- Choosing a system API to call for: alarm scheduling, notification rendering, foreground services, full-screen intents, wake locks, vibration, audio routing, persistence, navigation, edge-to-edge, predictive back, locale handling, RTL mirroring, time formatting, plurals, day-of-week ordering
- Adding any user-visible string (must route through resources, never hardcode)
- Touching `wantAgent`, `reminderAgentManager`, `Navigation/NavPathStack`, `EntryAbility` lifecycle on the watch side
- Touching `AlarmManager`, `AlarmRingService`, `AlarmActivity`, `BootReceiver`, Glance widget on the Android side
- Anything that would change the wire format between Android and watch (`WearBridgeService` ↔ `WearBridgeStub`)
- Anything in the cross-device sync flow (alarm fires, dismiss propagation, snooze re-fire chain) — **also re-read [`docs/sync-architecture.md`](docs/sync-architecture.md)** for the design-of-record
- Phase / sequencing decisions — **also re-read [`docs/execution-plan.md`](docs/execution-plan.md)** to know what's in flight, what's done, and what comes next

The acceptance-criteria document is the single source of truth. If it conflicts with an old comment, README, or memory note, the acceptance-criteria document wins. If you find a real conflict, **update the acceptance-criteria document in the same turn** — do not leave it stale.

## Honest status discipline

The status legend in `docs/acceptance-criteria.md` is non-negotiable:

- ✅ **DONE** — code exists AND verified working on real device or emulator
- 🟡 **CODED** — code exists, **not yet verified** end-to-end (assume nothing about correctness)
- 🟠 **PARTIAL** — code exists with a known gap or caveat (caveat must be listed)
- ❌ **NOT DONE** — missing or known broken
- 📋 **N/A** — out of scope for this build

Never flip 🟡 → ✅ on the strength of a successful build. Building is necessary, not sufficient. ✅ requires actual end-to-end verification (device or emulator firing path, locale switch, lockscreen takeover, reboot survival, etc.).

## Untestable-claim rule

A criterion phrased as a vibe ("smoothly", "fast", "feels native") is not a criterion. Replace it with a measurable rule (frame budget, wall-clock time, count, threshold) before adding it to the spec. Drop subjective adjectives.

## Release / versioning procedure

When the user asks for a "new version" / release, follow this EXACTLY:

0. **Decide the scope: both apps, or one?** The two apps ship independently. If only one app changed, release only that one — re-uploading an unchanged build to a store buys nothing and costs a review cycle. A **single-app release** bumps only that app's `versionName` + code and leaves the other app's numbers alone; the release-notes table records the untouched app's code unchanged. Only when **both** apps ship in the same release does `versionName` have to match across them. (1.0.8 was phone-only: watch stayed at `1.0.7` / `1000007`.)
1. **Ask the user for the new `versionName`** (e.g. `1.0.1`) — never invent it.
2. **Apply `versionName` to each app being released:**
   - phone — `versionName` in `android-app/app/build.gradle.kts`
   - watch — `version.name` in `watch-app/entry/src/main/config.json`
3. **Bump the released app's integer release code by +1.** The two sequences are **INDEPENDENT** — phone `versionCode` is a small int (…2, 3, 4…); watch `version.code` is a HarmonyOS code (…1000001, 1000002…). Stores reject re-uploading the same code (Play: "version code N already used"; AppGallery: "version code already used"), so every app you actually upload MUST have a fresh code:
   - phone — `versionCode` in `android-app/app/build.gradle.kts`
   - watch — `version.code` in `watch-app/entry/src/main/config.json`
4. **Add a release-notes entry to [`docs/release-notes.md`](docs/release-notes.md):** a new row in the version table + a `## <version> — <YYYY-MM-DD>` section (fixes + release-engineering notes; absolute dates).
5. **Commit** `Release v<version>: <summary>`, then create an **annotated tag** on that commit: `git tag -a v<version> -m "v<version>"`.
6. **Push the commit AND the tag** (`git push origin main --follow-tags`).
7. **Build signed store artifacts for upload** — for the apps being released: `bash scripts/android.sh release` (phone APK+AAB) and/or `bash scripts/build-watch-release.sh` (watch `.app`). If the signing cert changed since the last release, also re-run `scripts/pack-signing-uploads.sh` and re-upload the store-signing packages.
8. **Collect into `dist/`:** `bash scripts/collect-release.sh` stamps the signed artifacts into `dist/gtwake-{phone,watch}-<that app's version name>-<code>.{apk,aab,app}` — the single, gitignored, upload-ready location. Each app is stamped from **its own** manifest, so a single-app release cannot mislabel the app it didn't touch. Scope it with `--phone` or `--watch`; without a scope flag it expects both apps' artifacts and exits non-zero if any is missing. **Upload from `dist/`, not the deep `build/outputs/` paths.** (`--build` does steps 7+8 in one, honouring the same scope: e.g. `bash scripts/collect-release.sh --build --phone`.)
9. **Publish the direct download:** `cd meta/site && npm run deploy`. This re-syncs the APK out of `dist/`, regenerates the published SHA-256, rebuilds, and deploys to Cloudflare Pages. It **hard-fails unless the APK's signing cert is `95F6…`** — that cert parity is what lets users move between the Play build and the site build without uninstalling, and what satisfies the watch's Wear Engine allow-list. Never bypass it with `GTWAKE_SKIP_CERT_CHECK=1` for a real release.

Keep `versionName` monotonic; never reuse a release code. The site is a **third distribution channel** for the phone app — a phone release is not fully shipped until step 9 runs, or `gtwake.kirkouski.com/download` will advertise the previous version. (A watch-only release skips step 9; the site carries no watch artifact.)

## Doc-verification protocol

Order of escalation when an API contract is unclear:
1. Local SDK `.d.ts` / Javadoc / source
2. `context7` MCP (`mcp__plugin_context7_context7__query-docs`)
3. Official vendor docs (`developer.huawei.com`, `developer.android.com`)
4. WebFetch / WebSearch (last resort)

**Never implement against memory of how an API used to work.** APIs change.

## Layout

```
GT-Alarm/
  docs/
    acceptance-criteria.md     ← MANDATORY pre-decision read
  android-app/                 ← Android Studio Gradle project
  watch-app/                   ← DevEco Studio HarmonyOS project
  LICENSE                      PolyForm Noncommercial 1.0.0
  NOTICE                       Author + commercial-licensing contact
```

## Build / verify quick reference

**Always build via the repo scripts — never raw `gradlew` / `hvigorw`.** The scripts wire up `JAVA_HOME`, `DEVECO_SDK_HOME`, memory flags, and `--no-daemon` so there is one command pattern to approve:

- **Android:** `bash scripts/android.sh <build|lint|detekt|test|check|all>` — `all` = assemble + lint + detekt + test.
- **Watch:** `bash scripts/build-watch.sh debug` — builds the HAP AND pushes it to `/sdcard/haps/` on the phone. Lint separately via `./scripts/codelinter.sh`.

Each app builds standalone — there is no cross-build dependency.

### Agent pre-flight checklist (recurring trip-ups — check EVERY time)

- [ ] **Build with the scripts above**, not raw `gradlew.bat` / `hvigorw`. Raw invocations skip env setup and have failed repeatedly.
- [ ] **Two adb devices are connected.** ALWAYS set `ANDROID_SERIAL=R5CX13B9KWK` for `adb` and `build-watch.sh`, or commands fail with `more than one device/emulator`.
- [ ] **bg-manager + shell scripts:** append `2>&1` to `bash scripts/*.sh` commands so bg-manager routes them through Git Bash — a bare direct spawn yields an empty log + exit 1.
- [ ] **Run `scripts/android.sh all` before declaring an Android build done** — detekt breaks the build on new loop-jumps (≤1 `break`/`continue` per loop), >3 returns, >60-line methods, or broad `catch`. Fix at source, or add `@Suppress` + an adjacent `// reason:`.
- [ ] **Any watch HML/CSS change → update `watch-references/watch-ui-preview.html` AND Playwright-screenshot it.** The mockup is browser CSS and CANNOT reproduce native-component quirks — verify intent, not pixel-truth.
- [ ] **`<list>` is a native scroll component, not a flex box** — it needs an explicit `height`. `display:flex` with no height collapses it to ~1px on real hardware. Model it in the mockup as fixed-height + `overflow-y:auto` so the regression can't hide.
- [ ] **Root-cause before patching** — prove the cause from logs/source; no speculative fixes.

### Android linter stack

Three layers, all strict, all break the build on a new finding:

1. **kotlinc** (`allWarningsAsErrors = true`) — every Kotlin compiler warning fails the build. Each `@Suppress(...)` MUST carry an adjacent `// reason: ...` comment per repo policy.
2. **Android lint** (`abortOnError = true`, `warningsAsErrors = true`) — resource/manifest checks. Permanent overrides live in `app/lint.xml` with reason comments; transient deferred warnings live in `app/lint-baseline.xml` and MUST reference a tracked phase in `docs/execution-plan.md`.
3. **Detekt** (`ignoreFailures = false`) — Kotlin code-smell linter. Config in `app/detekt.yml`; rule disables there carry an inline reason. Per-site `@Suppress("RuleName")` annotations also need adjacent `// reason: ...` comments. New smells = build break.

There is intentionally no `detekt-baseline.xml` checked in. The first run produced zero baseline because every finding was either fixed at source or annotated with a documented per-site suppression. If a future smell can't be fixed immediately, prefer a per-site `@Suppress` with reason over re-introducing a baseline file.

### Android build environment gotcha

AGP 8/9 + Room/Hilt KSP need a **JDK 17+ that ships `jlink`**.

- **DevEco Studio's bundled JBR** at `C:/Program Files/Huawei/DevEco Studio/jbr` is a **JRE only — no jlink**. Using it as `JAVA_HOME` makes `:app:compileDebugJavaWithJavac` fail with `jlink executable ... does not exist`.
- **Android Studio's bundled JBR** at `C:/Program Files/Android/Android Studio/jbr` is a full JDK 17 with `jlink`. Use that, or any other temurin/zulu JDK ≥ 17.
- Recommended: set `JAVA_HOME` for the shell session, e.g. `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` (Git Bash) before invoking `./gradlew`.

### Memory under contention

The Android lint + test executors crash with `paging file too small` when DevEco + Android Studio + the Gradle daemon all compete for RAM. Mitigations already wired into `app/build.gradle.kts`:
- `lint { checkDependencies = false }` — transitive scan blew the lint daemon's heap; revisit in Phase 2.
- `testOptions.unitTests.all.maxHeapSize = "768m"` + `-XX:+UseSerialGC` — minimal-footprint test executor.

When invoking from CLI, set `GRADLE_OPTS="-Xmx2g -XX:MaxMetaspaceSize=512m"` and `--max-workers=1` for additional headroom on memory-pressured hosts.

## Watch (Lite Wearable) compliance — check on EVERY watch-side edit

The watch is a HarmonyOS **Lite Wearable** (FA model, ACELite engine, GT 6 Pro 466×466). It is NOT full HarmonyOS / Stage Model — disregard NEXT / ArkTS / `@ohos.*` advice.

- **ES5 only.** No `let`/`const`, arrow functions, destructuring, `async`/`await`, `Promise`, template literals, classes, default params. Plain `var` + `function` + callbacks.
- **Explicit px sizes everywhere.** Flex children do NOT auto-size. Every layout element needs `width`/`height` in px, and layout `<div>`s need `display: flex` or they don't render.
- **`<list>` is a native scroll component** — needs an explicit `height`, never flex-sized (see pre-flight checklist).
- **One `<list-item for=>` template per `<list>`.** Multiple templates render nothing. Switch enabled/disabled variants with inner `if=` on pre-computed booleans (HML `if=` does NOT coerce truthy).
- **Buttons are `<div onclick=>`**, not `<input>`.
- **Page bundle ≤ 48 KB.** Webpack-bundled imports count — do NOT import the Wear Engine bridge into display-only pages (it drags in the heavy `wearengine.js` wrapper). Keep `pages/*` imports minimal.
- **Per-page-bundle module isolation.** `app.js` + `common/*` share one bundle; each `pages/*` is its own isolated bundle. A module singleton imported by `app.js` is a DIFFERENT instance than the one a page imports. Cross-bundle signalling MUST go through `@system.storage` / `@system.file` — never shared module state.
- **`@system.storage` per-value cap is 128 bytes.** Anything larger → `@system.file`.
- **`<image>` cannot decode PNG/JPEG at runtime** — only the custom BGRA `.bin` format (phone encodes before sending).
- **`setTimeout` is foreground-only** — a hidden page's pending timers do not fire.

## Wear Engine: the Activity rule — DO NOT violate

Huawei Wear Engine **P2P receive requires an Activity component in the task** at receive time — it is a runtime tech constraint AND an AppGallery review requirement. Watch sync is the entire point of this app; getting to AppGallery depends on it.

- `AlarmRingService` MUST ensure `AlarmActivity` is in the task when an alarm fires (FSI is the primary launch path; `pi.send` with creator-side BAL opt-in is the **canonical compliance path** when FSI degrades to a heads-up — NOT a removable "backup").
  - ⚠️ **On Android 16 that second path no longer actually lands** — the BAL gate refuses it every time on `balDontBringExistingBackgroundTaskStackToFg`, even with the creator-side opt-in present and honoured, on `targetSdk` 35 *and* 36. Keep the code (it is the compliance story Huawei requires and it still works pre-16), but do **not** assume it provides a working fallback if FSI is ever denied. Measurements + logs: `docs/acceptance-criteria.md` → "Android 16 — the `pi.send` activity-launch backup no longer works".
- Never replace the Activity launch with an FGS-only receive path. Treat this as fact unless empirically disproven (would require disassembling the closed-source SDK).

### The whole round-trip needs the Activity — not just the fire

`registerReceiver` is process-scoped (the `HuaweiWearBridge` `@Singleton` owns it), but Wear Engine only **delivers** inbound messages while an Activity is live. So a registered receiver is necessary but **not sufficient**.

**Concrete failure mode (the "watch dismiss never propagates" bug):** `AlarmActivity` finished itself on the dismiss tap, then `AlarmRingService` (a Service) ran `sendAlarmDismissedAwaiting` and waited for the watch's `alarm_dismissed_ack`. With no Activity left, that ack was never delivered — the await timed out every single time.

Rules:
- **Any send-and-await round-trip with the watch requires an Activity alive in the task for the WHOLE round-trip.** You may *send* from anywhere; you may not *await a reply* after the Activity is gone.
- A Service may *run* the await coroutine (the fire flow does — `AlarmRingService.preArmWatch` → `sendAlarmFiredAwaiting`), but it only resolves because `AlarmActivity` coexists. The Service is not the receive anchor; the Activity is.
- When a user action tears the ring UI down (dismiss/snooze), keep `AlarmActivity` alive until the watch round-trip completes (show a "waiting for watch" state) — let `RingEndedSignal` close it, don't `finishAndRemoveTask()` eagerly.

## Cross-device messaging: retries + ack are MANDATORY

Any watch-bound message whose loss is user-visible (`alarm_fired`, `alarm_dismissed`, `alarm_snoozed`) MUST:

1. **Wake-and-send protocol** — ping-poll the peer until it returns 202 (APP_RUNNING) before sending; 201 means "launching, not ready". Force-reping (bypass the 30 s cache) on dismiss/snooze.
2. **Transport retry** — retry on 206 (COMM_FAIL) / thrown transport errors, with backoff, bounded by a total deadline.
3. **Application-level ack** — wait for an ack envelope from the watch's JS handler, NOT just the 207 transport ACK. **207 means "delivered to the watch's Wear Engine layer", NOT "the JS receiver processed it"** — the receiver can be mid-bundle-load and silently drop it. On ack timeout, re-send the whole envelope (idempotent on the watch) for another round.

Fire-and-forget is acceptable ONLY for non-critical traffic (log relay, toggle notifications the user can re-tap). When in doubt, add the ack loop. See `docs/sync-architecture.md` for the wire format of record.
