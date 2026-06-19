# GT Wake — Release Notes

Version log for both apps. **`versionName` is shared across the phone and watch**;
the integer release codes are **per-app** and increment by 1 each release.

| versionName | phone code | watch code | tag | date |
|---|---|---|---|---|
| 1.0.5 | 5 | 1000005 | `v1.0.5` | 2026-06-19 |
| 1.0.2 | 4 | 1000003 | `v1.0.2` | 2026-06-11 |
| 1.0.1 | 3 | 1000002 | `v1.0.1` | 2026-06-11 |

Apps:
- **Phone** — `com.kirkouski.gtwake.companion` (Android, Google Play + AppGallery), `versionCode` in `android-app/app/build.gradle.kts`.
- **Watch** — `com.kirkouski.gtwatch.watch` (HarmonyOS Lite Wearable, AppGallery only), `version.code` in `watch-app/entry/src/main/config.json`.

---

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
