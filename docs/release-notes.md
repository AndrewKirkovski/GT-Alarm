# GT Wake — Release Notes

Version log for both apps. **`versionName` is shared across the phone and watch**;
the integer release codes are **per-app** and increment by 1 each release.

| versionName | phone code | watch code | tag | date |
|---|---|---|---|---|
| 1.0.1 | 3 | 1000002 | `v1.0.1` | 2026-06-11 |

Apps:
- **Phone** — `com.kirkouski.gtwake.companion` (Android, Google Play + AppGallery), `versionCode` in `android-app/app/build.gradle.kts`.
- **Watch** — `com.kirkouski.gtwatch.watch` (HarmonyOS Lite Wearable, AppGallery only), `version.code` in `watch-app/entry/src/main/config.json`.

---

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
