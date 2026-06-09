# Privacy Policy — GT Wake

**Effective date:** 2026-06-08
**Developer:** Andrei Kirkouski
**Contact:** andrew.kirkovski@gmail.com

This policy covers both apps:

- **GT Wake Companion** — Android phone app (`com.kirkouski.gtwake.companion`), available on Google Play and Huawei AppGallery.
- **GT Wake** — Huawei watch app (`com.kirkouski.gtwatch.watch`), available on Huawei AppGallery.

Together they are referred to as "the apps", "we", or "our".

---

## Summary

- We do **not** collect, store, or transmit your personal data to us or to any server.
- There are **no user accounts**, **no analytics**, **no advertising**, and **no tracking**.
- Your alarms, settings, and any images you choose stay **on your own devices**.
- Alarm syncing happens **directly between your phone and your paired watch** over Bluetooth (Huawei Wear Engine, via Huawei Health) — it does not pass through our servers.
- The apps request **no internet permission** and cannot send your data over the internet.

---

## 1. Data we collect

**None that is sent to us.** The apps have no backend, no developer-operated server, and no account system. We do not receive, see, or store any of your information.

The following data is created and kept **locally on your device(s)** so the apps can work:

- Your alarms and their settings (time, repeat days, label, snooze options, vibration pattern, volume ramp, etc.).
- App preferences (e.g. 24-hour clock, first day of week, language).
- Optional background images you choose for an alarm (see §4).

This data lives in the app's private storage on your phone, and a mirror of your alarms is kept on your paired watch so it can display and ring them. None of it is uploaded anywhere.

## 2. Network and data transmission

- The phone app's manifest declares **no `INTERNET` permission**, so it cannot open internet connections to send your data anywhere.
- A `ACCESS_NETWORK_STATE` permission is present only because it is included by the Huawei Wear Engine library; it is used to read whether a connection exists and does **not** transmit your data.
- All communication created by the apps is **device-to-device** between your phone and your own paired watch (see §4).

## 3. Permissions, and why each is used

**Phone app (Android):**

| Permission | Why |
|---|---|
| Exact alarms (`USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM`) | To ring your alarms at the precise time you set. |
| Notifications (`POST_NOTIFICATIONS`) | To show the ringing alarm and alarm-related notifications. |
| Full-screen intent (`USE_FULL_SCREEN_INTENT`) | To show the alarm screen over the lock screen when an alarm fires. |
| Foreground service (`FOREGROUND_SERVICE` + special-use / media-playback types) | To keep the alarm reliably ringing while it is active. |
| Vibrate (`VIBRATE`) | For alarm vibration patterns. |
| Wake lock (`WAKE_LOCK`) | To wake the screen/CPU so the alarm can ring. |
| Run on boot (`RECEIVE_BOOT_COMPLETED`) | To re-arm your alarms after the phone restarts. |
| Ignore battery optimizations (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Optional; lets you exempt the app from deep sleep so alarms fire on time. You choose whether to grant it. |
| Watch connectivity (Huawei Wear Engine — requested at runtime, not a manifest permission) | To send/receive alarm and ring/snooze/dismiss signals to your paired watch. |

The apps do **not** request location, contacts, microphone, camera, call logs, SMS, or device identifiers.

**Watch app (HarmonyOS):** uses Huawei Wear Engine to receive alarm data from, and send ring/snooze/dismiss signals to, your paired phone. It does not collect personal data.

## 4. Phone ↔ watch syncing (Huawei Wear Engine)

To make alarms ring on your wrist in sync with your phone, the apps exchange messages **directly between your phone and your own paired watch** using **Huawei Wear Engine**, which operates over Bluetooth through the **Huawei Health** app. The messages contain your alarm data and ring/snooze/dismiss signals, and — if you choose a watch background image for an alarm — that image.

This data goes only between your two paired devices. We do not receive it. Because this transport is provided by Huawei, Huawei's handling of the pairing/connection is governed by **Huawei's own privacy policy** (see <https://consumer.huawei.com/en/privacy/privacy-policy/>) and the Huawei Health app.

## 5. Voice alarms

When you create an alarm by voice, your device's assistant (for example, Google Assistant) handles the voice interaction using Android's standard "set alarm" feature, and the apps simply receive the resulting alarm time. Any voice processing is performed by that assistant and is governed by **its** provider's privacy policy, not ours. The apps do not access your microphone.

## 6. Background images

If you pick an image as an alarm background, the app reads that image from your device to display it (on the lock-screen alarm and/or on the watch). For a watch background, the image is sent to your paired watch via Wear Engine (§4). Images are not uploaded to us or to any third party.

## 7. Advertising, analytics, and tracking

The apps contain **no advertising, no analytics SDKs, and no tracking**. We do not build profiles, and we do not share or sell any data (we have none to share or sell).

## 8. Data retention and deletion

Because all data is stored locally on your devices, you control it entirely:

- Deleting an alarm removes it from the phone and (on the next sync) from the watch.
- Uninstalling the phone app removes its data from the phone.
- Uninstalling the watch app removes its data from the watch.

We hold no copies, so there is nothing for us to delete on your behalf.

## 9. Children's privacy

The apps are general-purpose alarm clocks and are not directed at children. They collect no personal data from anyone, including children.

## 10. Security

Your data stays in each app's private on-device storage and is exchanged only between your paired devices over Huawei's Wear Engine/Bluetooth transport. Because we operate no servers and collect no data, there is no central store of your information that could be breached.

## 11. Changes to this policy

If this policy changes, we will update the "Effective date" above and post the new version at this URL. Material changes will be reflected before or when they take effect.

## 12. Contact

Questions about this policy or your privacy in the apps:

andrew.kirkovski@gmail.com

---

*This document is the privacy policy for both GT Wake Companion and GT Wake. The same URL may be supplied as the privacy-policy link for both apps on Google Play and Huawei AppGallery. Remember to also expose an in-app link to this policy (Huawei AppGallery requires an in-app privacy-policy link).*
