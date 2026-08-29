# GT Wake — Release Notes

Version log for both apps. The apps ship independently: a release covering both keeps
`versionName` in step, while a single-app release bumps only that app's numbers and
leaves the other's unchanged. The integer release codes are **per-app**.

| versionName | phone code | watch code | tag | date |
|---|---|---|---|---|
| 1.0.9 | 9 | 1000008 | `v1.0.9` | 2026-08-29 |
| 1.0.8 | 8 | 1000007 (unchanged — phone-only release) | `v1.0.8` | 2026-08-27 |
| 1.0.7 | 7 | 1000007 | `v1.0.7` | 2026-06-25 |
| 1.0.6 | 6 | 1000006 | `v1.0.6` | 2026-06-24 (REJECTED — startup crash; superseded by 1.0.7) |
| 1.0.5 | 5 | 1000005 | `v1.0.5` | 2026-06-19 |
| 1.0.2 | 4 | 1000003 | `v1.0.2` | 2026-06-11 |
| 1.0.1 | 3 | 1000002 | `v1.0.1` | 2026-06-11 |

Apps:
- **Phone** — `com.kirkouski.gtwake.companion` (Android, Google Play + AppGallery + direct APK at `gtwake.kirkouski.com/download`), `versionCode` in `android-app/app/build.gradle.kts`. All three channels ship the **same signing cert** (`95F6…`), so users can move between them without uninstalling.
- **Watch** — `com.kirkouski.gtwatch.watch` (HarmonyOS Lite Wearable, AppGallery only), `version.code` in `watch-app/entry/src/main/config.json`.

---

## 1.0.9 — 2026-08-29
phone `versionCode 9` · watch `code 1000008` · tag `v1.0.9`

**Both apps ship in this release.** The watch changes are the substantive ones.

**Watch: the alarm no longer snoozes itself.** Raising your wrist to look at a ringing
alarm — or simply letting the always-on display take over — used to be read as a
side-button press and snoozed the alarm. Measured on a GT 6 Pro: the ring screen was
being hidden 3.1 seconds in, with no user action, and the alarm silently snoozed. The
watch now holds its screen on for the duration of the ring, and a hide with no explicit
tap does nothing at all. For an alarm clock the safe direction is to keep ringing — a
missed snooze gesture costs one tap, a phantom snooze can make you miss a wake-up.

**Watch: dismissing from the phone now reaches the watch reliably.** An inbound
`alarm_dismissed` arriving while the watch sat on its list screen threw a JavaScript error
inside the message handler, so the watch never acknowledged it. The phone then burned both
retry rounds and gave up, leaving the ring UI stuck in "waiting for watch" until it timed
out.

**Phone: the app can now tell you when its alarm screen never appeared.** An alarm can
ring without taking over the screen — Android shows a banner instead when another
full-screen alert already owns the display. That used to be completely invisible in logs.
Each ring now records whether the alarm screen reached the foreground, was covered, or
never appeared at all.

**Phone: new help entry** explaining that case, and that tapping the banner opens the full
alarm screen (which also restores the watch's ability to dismiss). Localized in all six
languages.

**Known limitations, documented rather than fixed.** Three defects are recorded as TODOs
in the source with their evidence and a safe procedure, deliberately not patched in this
release: alarms that overlap can lose the second alarm's full-screen UI; a second alarm
firing over a first leaves the first one's state unfinished; and the first alarm's audio
player is not stopped when that happens. All three sit in the most failure-sensitive path
in the app and none has been reproduced on real hardware yet.

---

## 1.0.8 — 2026-08-27
phone `versionCode 8` · watch unchanged at `1.0.7` / `code 1000007` · tag `v1.0.8`

**Phone-only release.** Nothing changed on the watch, so its build is not re-cut or
re-uploaded. The wire format between the two apps is untouched — 1.0.8 phones and 1.0.7
watches sync exactly as before.

**Targets Android 16 (API 36).** `targetSdk` moved 35 → 36 ahead of the Google Play
deadline of 2026-08-31, after which updates below 36 cannot be published. `compileSdk`
was already 36, so no dependency changes were needed. No target-gated Android 16 change
touches the alarm path: edge-to-edge was already enforced at 35, predictive back was
already opted in, the app schedules with `AlarmManager` rather than `JobScheduler`, and
full-screen-intent / exact-alarm / lock-screen behaviour is unchanged at 36. AppGallery
sets a minimum `targetSdkVersion` of 30 and no maximum, so the bump is safe there too.

**Empty alarm list is a card.** With no alarms the list used to render a bare icon
floating between the setup banner and the watch-sync card. That slot is now
`EmptyAlarmsCard` — same shape and margins as the populated alarm card — carrying two
buttons that open a new draft directly on the **Alarm** or the **Timer** tab. The hero
subtitle changed from the instructional "Press + to add an alarm" to "Your wake-ups will
show here" so it no longer competes with those buttons. New/reworded strings are
localized across all six locales.

**One setup prompt at a time.** The list could stack up to three "fix this" cards at once
("Setup needed", "Keep alarms reliable", and an exact-alarm card). It now shows at most one,
most-severe first, so they get resolved one by one; clearing the top one reveals the next. The
exact-alarm card was removed outright — the permission it pointed at is already covered by
"Setup needed" and by the Setup screen behind it, so that card could never appear on its own.

**"Delete after firing" defaults reworked.** The setting persists as one field but the
two modes now present it independently:

- A **dated alarm** asks "Delete after firing", defaulting to **off** — it stays in the
  list (disabled) after ringing, ready to re-arm. Previously this defaulted to on.
- A **timer** asks "Don't delete after firing", defaulting to **off** — unchanged
  behaviour (the timer still deletes itself), stated in the polarity that reads
  naturally for a timer.

Each mode keeps its own value, so switching between the Alarm and Timer tabs no longer
overwrites what you set on the other one. Selecting repeat-days hides the row and forces
the flag off (a recurring alarm can't self-destruct); clearing the days again restores
your choice instead of silently resetting it.

**Fixed: a missed timer ignored "Don't delete after firing".** If a timer's fire time
passed while the phone was off, boot recovery deleted the row unconditionally instead of
honouring the setting. All three dismiss routes — live ring, pre-unlock lock-screen
dismiss, and missed-during-downtime — now share one decision function.

---

## 1.0.7 — 2026-06-25
phone `versionCode 7` · watch `code 1000007` · tag `v1.0.7`

**Startup-crash hotfix over 1.0.6 (which the store rejected).** 1.0.6 had added a release
`fallbackToDestructiveMigrationFrom(5,6,7,8)` to the Room builder, but version 5 is the END of
`MIGRATION_4_5`, so Room threw `IllegalArgumentException: Inconsistency detected …` at DB
`build()` — which runs during Hilt init in `GtAlarmApp.onCreate`, crashing **every launch** on a
fresh install. Reverted that fallback (it also guarded an impossible case: every shipped release is
DB v9, interim v5–v8 were destructive, and a debug↔release signature change forces a fresh install,
so no v5–v8 DB can reach a release build). Verified crash-free on a Pixel emulator — fresh install
*and* upgrade-from-v9. **All 1.0.6 features below ship unchanged in 1.0.7.**

**Also in 1.0.7 — alarm-list polish**
- **Empty states restyled to match the hero** — a bold header + a small subtle subtext instead of one
  flat line. Empty list → "No alarms" / "Press + to add an alarm"; all alarms disabled → "All alarms
  are off" / "Turn one on, or press + to add an alarm" (the two new hint strings are localized).
- **Hero countdown redesigned** to a two-tier "Alarm in 3⁴⁵": a big primary number with the secondary
  as a half-size superscript and **no unit letters**. ≥ 1 h renders hours:minutes; under an hour renders
  minutes:seconds and now **ticks every second** (the ticking is what tells m:s apart from h:m — there
  are no labels). Replaces the old single-unit label with quarter-hour fractions ("3¾ hr"). The
  countdown prefix stays English-only for now, matching the existing `screen_list_hero_*` strings.

---

## 1.0.6 — 2026-06-24 (REJECTED — startup crash, superseded by 1.0.7)
phone `versionCode 6` · watch `code 1000006` · tag `v1.0.6`

Cross-device sync reliability + per-device controls, watch crown scrolling, and a full localization pass.

**Sync — fixes the ">3 alarms won't sync" bug**
- **More than 3 alarms now sync to the watch.** The full alarm list was sent as a single Wear Engine
  P2P **text message**, which has a ~1 KB ceiling on the GT 6: a 4-alarm list (~1068 B) was accepted by
  the transport but **truncated before the watch could parse it**, so the watch silently kept its old
  3-alarm list and the phone re-pushed forever. The full-state replace now goes as a **file transfer**
  (`alarms_<hash>.json`) above 768 B, with an app-level ack + retry — the same proven path as the watch
  background image. Device-verified: 4 alarms converge on the first attempt. *(See `sync-architecture.md`
  §2.1/§5.7.)*
- **Hardening (release review):** the watch re-arms its terminate timer on an inbound file so a slow /
  large transfer isn't killed mid-receive; it acks only after the store write commits; corrupt transfers
  are dropped + re-fetched.

**Watch**
- **The digital crown scrolls the alarm list again.** A 2-second poll was rebuilding and reassigning the
  list every tick, re-rendering it and discarding the crown focus; the list is now only reassigned when
  its content actually changes, and crown focus is re-asserted after render.
- Watch-side audio playback (an experiment) was **removed** — `@system.audio` exists on the GT 6 but its
  `src` setter is a no-op, so playback isn't achievable; the watch uses **vibration** for alarm feedback.

**Per-alarm controls**
- **Independent phone + watch vibration switches** per alarm (replaces the single "vibration off" pattern
  choice) — silence the wrist without silencing the phone, or vice versa. Existing "no-vibration" alarms
  migrate to both-off with a usable default pattern. DB migration v9→v10 (covered by a new test).

**Localization**
- **Completed translations** for Belarusian, Polish, Russian, Ukrainian, and Simplified Chinese (≈80–90
  previously-English strings each), and added **Russian / Ukrainian / Belarusian** locales to the watch
  app (watch now ships all 6 languages).

**Release engineering**
- ~~A DB destructive-migration fallback for the never-shipped interim v5–v8 schemas.~~ **This was the
  1.0.6 startup-crash cause — REVERTED in 1.0.7** (it conflicted with `MIGRATION_4_5`, which ends at
  version 5, and threw at DB build). v9 keeps its real, data-preserving migration regardless.
- Removed a junk `bash.exe.stackdump`; `*.stackdump` now gitignored.

## 1.0.5 — 2026-06-19
phone `versionCode 5` · watch `code 1000005` · tag `v1.0.5`

Completes the AppGallery watch resubmission: the watch now installs on the GT 6 and self-explains setup.

**Watch — install + onboarding (AppGallery)**
- **Fixed the watch failing to install on the GT 6** ("Installation failed: 30 — Failed to verify
  signature"). The cause was NOT signing: the three onboarding/privacy QR codes were bundled as
  pre-decoded BGRA (~2.9 MB), pushing the HAP over the Lite Wearable install-size budget, which the
  bundle manager surfaces as a misleading "verify signature" error. Replaced bundled QR images with
  the **native `<qrcode>` component** (renders on-device from a URL string) — HAP 4.65 MB → ~1.5 MB.
- **First-launch privacy-consent screen** (rule 7.5): Agree/Decline gate with a QR to the full policy
  (the watch has no webview), locale-switched EN/PL/ZH.
- **Onboarding / never-connected screen** with a QR to the companion's **AppGallery listing** plus
  connection persistence (records first connect + the phone app version, legacy connections default
  to 1.0.0) — fixes the "companion not discoverable" rejection.

**Watch — first-paint + visuals**
- **First-paint flicker fixed**: deterministic startup (measure screen → show splash → load
  i18n/privacy/connection in `setTimeout`-yielded chunks → reveal fully-texted). The splash
  (brand-gradient background + centred loading icon) stays painted during the load instead of flashing.
- **Default dark brand-gradient background** (scaled from a small source) when no custom background is
  set; the sync screen now matches the splash (background + icon).
- **New loading + sync icons** — `ion:hourglass-outline` (splash) and `famicons:sync-outline` (sync),
  both MIT.

**Phone**
- versionName bump to 1.0.5 only, kept in lock-step with the watch per the shared-`versionName` policy;
  no functional changes this release.

**Release engineering**
- `tools/icongen` gained a `src` override to render vendored non-Tabler SVGs (Ionicons / Famicons,
  MIT — `tools/icongen/iconify/` + `NOTICE.txt`).
- `scripts/build-watch*.sh` now fail loudly if the clean leaves a stale build dir (a Windows file lock
  could otherwise silently ship a stale incremental HAP).

> Verified on a real GT 6 Pro: install, native QR render + scan, privacy + onboarding screens, splash,
> new icons. The rectangular FIT path remains hardware-pending (no FIT device on hand).

## 1.0.2 — 2026-06-11
phone `versionCode 4` · watch `code 1000003` · tag `v1.0.2`

Addresses the AppGallery review rejections for both apps.

**Watch — layout fixes**
- **Responsive layout for every Lite Wearable screen.** The UI was hard-coded to the GT 6 Pro's
  466×466 round panel and rendered in a corner / clipped on rectangular FIT watches (the reviewer
  tested a FIT 5 Pro, 408×480). Every dimension is now computed from `@system.device.getInfo()`,
  so round GT (466), legacy round (454/390) and portrait FIT (408×480, 336×480) all fill + centre.
  On the GT 6 Pro the computed values reproduce the previous layout exactly (zero round-screen
  regression).
- **Swipe-right exits the app** (single-page app — previously a no-op back gesture).
- **The pink ring arc is hidden on rectangular screens** — a circular arc only suits a round face.

**Phone — privacy (AppGallery rules 7.5 / 7.1)**
- **First-launch privacy-consent prompt** — the policy is presented and must be accepted before use.
- **Chinese privacy policy** for the mainland-China region, in-app and on the landing site.

**Watch background — adaptive sizing** (one watch at a time)
- The phone learns the connected watch's screen (phone-initiated `screen_request` → `watch_screen`
  reply, cached per watch model) and sizes/shapes the background cropper + uploaded image to the
  real panel: circular crop for round, rounded-rect for FIT. An unrecognised resolution crops
  without the watch-UI overlay.

**Cleanup**
- Removed the dead singular `watch_log` wire path (superseded by `watch_log_batch`); extracted a
  shared cropper scaffold to de-duplicate the phone + watch background pickers.

> Note: the watch layout + adaptive-background paths are device-pending verification at tag time
> (the FIT rectangular path has no hardware on hand; GT 6 Pro is the dev device).

## 1.0.1 — 2026-06-11
phone `versionCode 3` · watch `code 1000002` · tag `v1.0.1`

**Fixes**
- **Watch sync/ring/dismiss restored.** A dead-code cleanup on 2026-06-09 orphaned a `len`
  reference in the watch's `onReceiveMessage`, which threw a `ReferenceError` on the
  ACELite/JerryScript engine and silently dropped **every** inbound message from the phone.
  Result: the watch stopped ringing on alarms, the alarm list stopped syncing, and
  dismiss/snooze stopped propagating (background images were unaffected, which masked it).
  Fixed and verified on a real GT 6 Pro — the watch rings on fire, sync-check hashes reply,
  and watch-side snooze/dismiss reach the phone again.

**Release engineering**
- **Phone signing migrated to RSA 2048** (`gtwake.phone`, cert `95F6…`) because Google Play
  App Signing rejects EC keys. The watch HAP keeps its EC key. Store-upload packages re-packed;
  the watch's pairing identity (`supportLists` + `PHONE_CERT_SHA256`) updated to the new phone cert.
- Store assets: Google Play + AppGallery screenshots, a 1024×500 feature graphic, and a landing
  page with the store links at `gtwake.kirkouski.com`.

_(Earlier internal builds — phone `0.1.0`, watch `1.0.0` — were never released.)_
