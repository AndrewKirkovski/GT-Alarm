package com.kirkouski.gtwake.companion.wear

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Whether this phone can drive watch sync at all.
 *
 * Wear Engine is not a self-contained SDK: Huawei's own docs describe it as
 * reaching the **Huawei Health Android app over AIDL**. No Android Huawei
 * Health on the device means no sync, no matter what else is installed.
 *
 * That makes the *capability* — is the Android Huawei Health package present —
 * the authoritative signal, and it is what this object keys on. OS-string
 * sniffing is deliberately NOT the primary test: HarmonyOS 5 (NEXT) has no
 * AOSP layer, so an APK there is running inside a third-party compatibility
 * container, and what such a container reports for `Build.*` or for
 * `ohos.*` class lookups is undocumented and unverified. Asking "can I do the
 * thing" is sound in a way that asking "what OS am I on" is not.
 *
 * Background: AppGallery rejected the watch app under rule 3.1 (2026-09) after
 * a reviewer on a HarmonyOS 5 phone found sync silently failing. The Wear
 * Engine Android SDK documents support for "Android 6.0-14.0" and Huawei
 * phones on "EMUI 4.1/HarmonyOS 2.0 or later" — HarmonyOS 5 is absent, and the
 * ArkTS replacement (Wear Engine Kit) is Chinese-mainland-only for phones, so
 * there is no path for this app. The product's job is to say so plainly.
 */
object WatchSupport {

    /** The Android Huawei Health package Wear Engine bridges into. */
    const val HUAWEI_HEALTH_PACKAGE = "com.huawei.health"

    /**
     * True when the Android Huawei Health app is installed and therefore Wear
     * Engine has something to bind to.
     *
     * Requires the `<queries><package android:name="com.huawei.health"/>`
     * entry in the manifest — without it Android 11+ package visibility hides
     * the package and this returns false even when Health is installed.
     */
    fun isHuaweiHealthInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(HUAWEI_HEALTH_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        Log.i(TAG, "Huawei Health not installed — watch sync unavailable: ${e.message}")
        false
    }

    /**
     * Heuristic HarmonyOS hint, used ONLY to choose wording once
     * [isHuaweiHealthInstalled] has already established that sync cannot work.
     * Never gate behaviour on this — a false negative here must not turn an
     * unsupported device back into a supported one.
     */
    fun looksLikeHarmonyOs(): Boolean = try {
        Class.forName("ohos.utils.system.SystemCapability")
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    /**
     * The single value the UI switches on. [WatchSupportState.Unsupported]
     * means watch sync is impossible on this phone — alarms themselves are
     * unaffected and keep working.
     */
    fun state(context: Context): WatchSupportState = when {
        isHuaweiHealthInstalled(context) -> WatchSupportState.Supported
        looksLikeHarmonyOs() -> WatchSupportState.UnsupportedHarmonyOs
        else -> WatchSupportState.UnsupportedNoHealth
    }

    private const val TAG = "WatchSupport"
}

/** @see WatchSupport.state */
enum class WatchSupportState {
    /** Huawei Health is present; the normal sync UI applies. */
    Supported,

    /** No Huawei Health and the runtime looks like HarmonyOS — name it explicitly. */
    UnsupportedHarmonyOs,

    /** No Huawei Health on an otherwise ordinary Android phone — installing it is the fix. */
    UnsupportedNoHealth,
}
