package com.kirkouski.gtalarm.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock

// Android does NOT have a system role for "alarm app" (no `RoleManager.ROLE_ALARM`).
// What users call the "default alarm app" is just the **preferred activity** for
// `ACTION_SET_ALARM`, set when the disambiguator chooser is dismissed with "Always".
// `resolveActivity(intent, MATCH_DEFAULT_ONLY)` returns:
//   - our package → we're either the only handler or the user's preferred choice
//   - a system "ResolverActivity"-style stub → multiple handlers, no preferred yet
//   - another app → that app is the user's preferred
// Note: Android 11+ requires a `<queries>` element in the manifest for
// `queryIntentActivities` to see apps outside our package.
object DefaultAlarmDetector {

    sealed interface DefaultStatus {
        // We're the resolved handler. Either sole installer or user's preferred.
        data object WeAreDefault : DefaultStatus
        // Multiple alarm apps installed, no user preference set — voice fires the
        // disambiguator chooser. Pick GT Alarm + Always to lock it in.
        data class Disambiguator(val handlerCount: Int) : DefaultStatus
        // Another app holds the preferred-activity slot today.
        data class OtherIsDefault(val packageName: String, val displayName: String) : DefaultStatus
        // Should never happen if we declare the intent-filter; reported for visibility.
        data object NoHandlers : DefaultStatus
    }

    fun detect(context: Context): DefaultStatus {
        val pm = context.packageManager
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return DefaultStatus.NoHandlers
        val resolvedPackage = resolved.activityInfo.packageName
        // Android returns "android" or a system stub when multiple apps match and
        // no preferred is set. Treat any non-app package as the disambiguator.
        val isSystemStub = resolvedPackage == "android" ||
            resolved.activityInfo.name.endsWith("ResolverActivity")
        return when {
            resolvedPackage == context.packageName -> DefaultStatus.WeAreDefault
            isSystemStub -> {
                val handlers = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                DefaultStatus.Disambiguator(handlerCount = handlers.size)
            }
            else -> DefaultStatus.OtherIsDefault(
                packageName = resolvedPackage,
                displayName = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(resolvedPackage, 0)).toString()
                }.getOrDefault(resolvedPackage),
            )
        }
    }
}

enum class DeviceBrand { PIXEL, SAMSUNG, XIAOMI, ONEPLUS, HUAWEI, OTHER }

object DeviceBrandDetector {
    fun current(): DeviceBrand = when (Build.MANUFACTURER.lowercase()) {
        "google" -> DeviceBrand.PIXEL
        "samsung" -> DeviceBrand.SAMSUNG
        "xiaomi", "redmi", "poco" -> DeviceBrand.XIAOMI
        "oneplus" -> DeviceBrand.ONEPLUS
        "huawei", "honor" -> DeviceBrand.HUAWEI
        else -> DeviceBrand.OTHER
    }
}
