// Store destinations for the phone and watch apps, plus the platform sniff the
// pages use to decide which one to lead with.
//
// Why this module exists: AppGallery has TWO web URL forms and only one works.
//
//   ✗ `https://appgallery.huawei.com/app/detail?id=<package-name>` — renders
//     "This app is only available in the HarmonyOS 5 version or later of
//     AppGallery" and never shows the listing. Measured 2026-09-05: the same
//     wall appears for Huawei's own `com.huawei.hmos.vmall`, so the package-name
//     lookup is simply not served to browsers. Using it in the watch onboarding
//     QR cost the watch app an AppGallery rule-3.1 rejection.
//   ✓ `https://appgallery.huawei.com/app/C<agcAppId>` — the form AppGallery's
//     own share button produces. Verified 2026-09-05: renders the full listing
//     with an Install button in a plain desktop browser.
//
// So always link the C-number form. The C-number is "C" + the AGC app id.

const PKG_PHONE = 'com.kirkouski.gtwake.companion'

/** AGC app id for the phone app; the web listing is "C" + this. */
const AGC_APP_ID_PHONE = '117892565'

export const PLAY_URL = `https://play.google.com/store/apps/details?id=${PKG_PHONE}`

/** Working web listing — renders in any browser, and hands off to the AppGallery app on a Huawei phone. */
export const APPGALLERY_PHONE_WEB = `https://appgallery.huawei.com/app/C${AGC_APP_ID_PHONE}`

/** Direct hand-off to the installed AppGallery app. Inert on a device without it, so never the only route. */
export const APPGALLERY_PHONE_DEEPLINK = `appmarket://details?id=${PKG_PHONE}`

// No watch equivalent here on purpose: the watch app has no web install path at
// all (it goes on via Huawei Health), and its AGC app id is a 19-digit value
// that is not a C-number, so no working web URL is known for it.

export type PhonePlatform =
  /** HarmonyOS 5 / NEXT — no AOSP layer, so it cannot install the APK at all. */
  | 'harmony-next'
  /** Huawei/Honor on an Android-based build (HarmonyOS ≤ 4, EMUI) — AppGallery first. */
  | 'huawei-android'
  /** Any other Android phone — Play first. */
  | 'android'
  /** Desktop or anything unrecognised — show every option evenly. */
  | 'other'

/**
 * Best-effort UA sniff. Callers may only use this to ORDER options and add a
 * note — never to hide a destination, because a wrong guess would strand the
 * user with no way to install anything.
 */
export function detectPhone(
  ua: string = typeof navigator === 'undefined' ? '' : navigator.userAgent,
): PhonePlatform {
  const android = /android/i.test(ua)
  const harmony = /harmonyos/i.test(ua)
  const huawei = /huawei|honor|hmscore/i.test(ua)

  // HarmonyOS NEXT browsers report HarmonyOS with no Android token; the
  // Android-based builds (HarmonyOS ≤ 4 / EMUI) still carry "Android".
  if (harmony && !android) return 'harmony-next'
  if (android && (huawei || harmony)) return 'huawei-android'
  if (android) return 'android'
  return 'other'
}

/**
 * Which store to lead with. AppGallery is the default whenever we are not
 * confident: only a UA that reads as a *non-Huawei* Android phone gets Play
 * first, because that is the one case where Play is clearly the better route.
 * Desktop, iOS and anything unrecognised fall to AppGallery — the watch app is
 * AppGallery-only, so a visitor who reached us from the watch is far more
 * likely to be in Huawei's ecosystem than not.
 */
export function preferAppGallery(platform: PhonePlatform): boolean {
  return platform !== 'android'
}
