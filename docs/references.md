# GT Alarm — Reference repositories

Consolidated index of external open-source projects we cross-referenced
while designing and debugging GT Alarm. **Permanent record** — was
previously only in memory files (`memory/android_alarm_lockscreen_pattern.md`,
`memory/litewearable_rendering_gotchas.md`, `memory/wear_engine_lite_facts.md`,
etc.) and at risk of being lost across context compactions.

Each entry: **what it is · why we read it · trust status · relevance to GT
Alarm**. Trust statuses:
- ✅ **TRUSTED-CURRENT** — actively maintained, recent commits, applicable to the modern Android/HarmonyOS stack we're on.
- ⚠️ **TRUSTED-LEGACY** — the patterns are correct historically but the repo no longer tracks current SDKs / has unfixed regressions.
- ℹ️ **INFORMATIONAL** — useful sample / docs but not a copy-from source.

---

## Android — alarm + lockscreen pattern references

### [FossifyOrg/Clock](https://github.com/FossifyOrg/Clock) — ✅ TRUSTED-CURRENT · **primary copy-from reference**
- AOSP-spirit alarm + clock app. Last commit 2026-05-05, release 1.6.0 (2026-01-30).
- **Why we read it:** the alarm path was deliberately rewritten Q1-Q2 2025 (PRs #133, #132, #145, #150, #173). Six+ closed lock-screen issues (#89, #93, #66, #235), zero open critical lock-screen bugs.
- **What we adopted from it:** the modern "specialUse FGS + 4 legacy window flags + `showOnLockScreen` + `showWhenLocked` + `turnScreenOn`" alarm-activity recipe (codified in `memory/android_alarm_lockscreen_pattern.md`).

### [BlackyHawky/Clock](https://github.com/BlackyHawky/Clock) — ✅ TRUSTED-CURRENT · cross-validation reference
- AOSP DeskClock fork, 932★, pushed 2026-05-13. Java not Kotlin.
- **Why we read it:** independent confirmation of the same recipe Fossify uses — both arrive at the identical Android-16 lockscreen approach despite different lineages.
- **Notable difference:** declares `android:directBootAware="true"` on its alarm components for **Before-First-Unlock (BFU)** alarms. We have NOT adopted this (requires Room → device-protected-storage migration) — tracked as future enhancement.

### [yuriykulikov/AlarmClock](https://github.com/yuriykulikov/AlarmClock) — ⚠️ TRUSTED-LEGACY ONLY
- `targetSdk=33`, last real code change 2024-05-15. **Open issue #774 — Android 16 FSI regression unfixed since 2025-09.**
- **Why we read it:** their `AlarmAlertFullScreen.kt` comment about the 4-flag legacy quartet — citing closed issue #360 (2021) — is the canonical written justification for *why* the four legacy window flags are still needed alongside the modern attributes.
- **Do NOT trust** as a 2026 reference for SDK choices, manifest declarations, or FSI behaviour.

---

## HarmonyOS Lite Wearable references

### [espinr/litewearable](https://github.com/espinr/litewearable) — ✅ TRUSTED-CURRENT · canonical FA-model JS reference
- Cloned locally at `.local/reference/litewearable/`.
- **Why we read it:** working Lite-Wearable FA-model app demonstrating the ES5 JS dialect, `success`/`fail` callback discipline, sensor access, and `config.json` shape we need for GT 6 Pro.
- **What we adopted:** callback patterns, page-bundle structure, and `@system.*` API usage in `watch-app/entry/src/main/js/`.

### [scriptiot/hm_lite_wearable_demos](https://github.com/scriptiot/hm_lite_wearable_demos) — ✅ TRUSTED-CURRENT · component reference
- Huawei-licensed showcase. Last activity 2020, but the Lite API has been stable.
- **Why we read it:** every Lite component documented with working code + 4 polished sample apps (alarm, music, airquality, showcase). `examples/showcase/.../component/{list, picker_view, image, swiper, stack, progress, chart, canvas, marquee, switch}` is the closest thing to an official component reference.
- **Directly relevant sub-sample:** `alarm/` — uses `<list>` + `<list-item>` + `<switch>` + corner `<image>` add-button. Mirrors the layout we ship.

### [megaacheyounes/harmonyos-dev-guide](https://github.com/megaacheyounes/harmonyos-dev-guide) — ℹ️ INFORMATIONAL
- Unofficial guide.
- **Why we read it:** documents "INSTALLATION FAILED: 10. Internal error" failure cases (e.g. destructuring syntax in Lite JS) and the no-native-Alarm caveat for Lite. Cross-validation when build/install errors surfaced (`memory/litewearable_rendering_gotchas.md`).
- Also referenced for GT-series specifics in `memory/gt6_hardware_constraints.md`.

### [gudqs7/My-HarmonyOS-First-Demo](https://github.com/gudqs7/My-HarmonyOS-First-Demo) — ℹ️ INFORMATIONAL
- **Why we read it:** working `<image>` + `<input type="button">` patterns + a working `<picker-view>` example. Useful when the official picker-view docs were ambiguous.

### [eclipse-oniro-mirrors/arkui_ace_engine_lite](https://github.com/eclipse-oniro-mirrors/arkui_ace_engine_lite) — ℹ️ INFORMATIONAL · Lite framework source-of-truth
- Open-source mirror of the ACE Lite engine the GT 6 runs.
- **Key files we used to settle "is this really supported":**
  - `frameworks/src/core/base/keys.h`
  - `frameworks/src/core/base/key_parser.cpp`
  - `frameworks/src/core/components/component_factory.h`
- **Why we read it:** the GT 6 firmware closes over a specific ACE Lite version; when the public docs were silent or wrong (e.g. `<input>` event names, list scroll events, key handling), the engine source is the only authoritative answer. Used heavily in the input-research spike (`memory/litewearable_input_capabilities.md`).

---

## Wear Engine — phone ↔ watch P2P references

### [HMS-Core/hms-wear-engine-demo](https://github.com/HMS-Core/hms-wear-engine-demo) — ℹ️ INFORMATIONAL · official Huawei demo
- Watch side: `Lite wearable devices/WearEngineLiteWearableDemo`.
- Phone side: `Android phones/WearEnginePhoneDemo`.
- **Why we read it:** the official demo is the only place where the exact `Builder.setDescription(string)` vs `Builder.setPayload(ArrayBuffer)` convention is shown in working code. Settled the wire-format silent-drop bug (see `memory/wear_engine_lite_facts.md` "SOLVED 2026-05-12").
- **Gotcha:** the watch-side demo imports `../wearEngine/wearengine.js` but the wrapper file is **not committed** — developers must drop in the file from the official SDK zip. We learned this the hard way.
- **Also referenced for:** receiver-registration site. Their phone-side demo registers `setIncomingHandler` in **`MainActivity`**, not `Application` — this is the most likely cause of our 100 % inbound-drop rate even when `MainActivity` is resumed (`memory/wear_engine_watch_to_phone_blocked.md`).

### [Explore-In-HMOS-Wearable/sportwatch-wear-engine-lite-wearable-to-mobile](https://github.com/Explore-In-HMOS-Wearable/sportwatch-wear-engine-lite-wearable-to-mobile) — ℹ️ INFORMATIONAL
- **Why we read it:** second working sample of Lite ↔ phone P2P. Cross-validates the HMS-Core demo's conventions.

---

## Other docs / writeups (not source repos)

- [Sabrina Cara — "Harmony OS: Prepare your Lite Wearable project for integration"](https://medium.com/huawei-developers/harmony-os-prepare-your-lite-wearable-project-for-integration-b4daaa9df67e) — Lite Wearable setup walkthrough (cited in `acceptance-criteria.md` Sources).
- [Android `directBootAware` documentation](https://developer.android.com/training/articles/direct-boot) — official guidance referenced in `acceptance-criteria.md:114` for the LOCKED_BOOT_COMPLETED path.

---

## How to use this list

- When debugging a new alarm / lockscreen / FSI issue: **start with FossifyOrg/Clock**.
- When debugging a Lite Wearable rendering / component issue: **start with scriptiot/hm_lite_wearable_demos** + the ACE Lite engine source.
- When debugging Wear Engine P2P: **start with HMS-Core/hms-wear-engine-demo** + the verbatim quote in `memory/wear_engine_lite_facts.md`.
- Memory files are the working notes (what we learned, gotchas, what to pin). This file is the index of where the knowledge originally came from — read both.
