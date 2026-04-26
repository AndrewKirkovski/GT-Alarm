# GT Alarm — Repo-level Claude Instructions

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

- **Watch:** `cd watch-app && ./hvigorw assembleHap --mode module -p product=default -p buildMode=debug --no-daemon` (DevEco SDK env var: `DEVECO_SDK_HOME="C:/Program Files/Huawei/DevEco Studio/sdk"`). Lint via `./scripts/codelinter.sh`.
- **Android:** `cd android-app && ./gradlew :app:assembleDebug` (also `:app:lintDebug`, `:app:testDebugUnitTest`, `:app:detektDebug`).

Each app builds standalone — there is no cross-build dependency.

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
