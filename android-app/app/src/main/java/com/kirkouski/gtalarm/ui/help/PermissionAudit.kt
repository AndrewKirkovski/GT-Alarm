package com.kirkouski.gtalarm.ui.help

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Programmatic checks for the per-app permissions the alarm app needs to ring
 * reliably on a locked device, plus deep-links to the matching system Settings
 * page when a check fails. Brand-agnostic — the brand-specific instruction
 * cards in HelpScreen layer on top of these for the bits we can't query
 * (Samsung Never-sleeping apps, MIUI Autostart, etc.).
 */
object PermissionAudit {

    enum class Status { GRANTED, DENIED, NOT_APPLICABLE }

    enum class Item {
        POST_NOTIFICATIONS,
        EXACT_ALARM,
        FULL_SCREEN_INTENT,
        BATTERY_UNRESTRICTED,
    }

    data class Check(val item: Item, val status: Status)

    /** Run every check + return them in the order the UI should display. */
    fun audit(context: Context): List<Check> = listOf(
        Check(Item.POST_NOTIFICATIONS, checkPostNotifications(context)),
        Check(Item.EXACT_ALARM, checkExactAlarm(context)),
        Check(Item.FULL_SCREEN_INTENT, checkFullScreenIntent(context)),
        Check(Item.BATTERY_UNRESTRICTED, checkBatteryUnrestricted(context)),
    )

    private fun checkPostNotifications(context: Context): Status {
        // POST_NOTIFICATIONS only exists as a runtime permission on Tiramisu+.
        // Below 33 the manifest <uses-permission> alone grants posting — and
        // we still want notifications enabled, which the same toggle controls.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val nm = context.getSystemService(NotificationManager::class.java)
            return if (nm != null && nm.areNotificationsEnabled()) Status.GRANTED else Status.DENIED
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) Status.GRANTED else Status.DENIED
    }

    private fun checkExactAlarm(context: Context): Status {
        // canScheduleExactAlarms() was added in S; minSdk is 31 so the
        // pre-S branch is unreachable. We still declare USE_EXACT_ALARM
        // (33+) and SCHEDULE_EXACT_ALARM in the manifest for the runtime
        // grant path. On Android 14, denied by default — the system shows
        // a one-time dialog when canScheduleExactAlarms() returns false.
        val am = context.getSystemService(AlarmManager::class.java) ?: return Status.DENIED
        return if (am.canScheduleExactAlarms()) Status.GRANTED else Status.DENIED
    }

    // canUseFullScreenIntent() landed in Android 14 (UPSIDE_DOWN_CAKE).
    // Older Androids honor USE_FULL_SCREEN_INTENT manifest grant directly.
    //
    // Why we DON'T trust NotificationManager.canUseFullScreenIntent() on 14+:
    // on Samsung One UI 6, the API returns true for AppOps MODE_DEFAULT (the
    // auto-grant path for apps with a SET_ALARM intent filter) — but
    // Samsung's vendor FSI-delivery layer STILL silently rejects the op at
    // fire time, leaving the alarm rendered as a heads-up only. Confirmed
    // 2026-05-12 via `adb shell cmd appops get com.kirkouski.gtalarm
    // USE_FULL_SCREEN_INTENT` showing `default; rejectTime=...` right after
    // a missed fire. Querying AppOps directly and requiring MODE_ALLOWED
    // forces the user through the explicit Settings toggle, which One UI
    // honors at delivery time.
    @Suppress("ReturnCount")
    // reason: 4 returns reflect 4 distinct fallthrough states (pre-API-34
    // shortcut, missing service, missing op string, op-mode result). A
    // result-variable rewrite would be longer and harder to read.
    private fun checkFullScreenIntent(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return Status.GRANTED
        val aom = context.getSystemService(AppOpsManager::class.java) ?: return Status.DENIED
        // OPSTR_USE_FULL_SCREEN_INTENT is @SystemApi (not public), so resolve
        // the op string from the permission name via the public helper.
        val opStr = AppOpsManager.permissionToOp(Manifest.permission.USE_FULL_SCREEN_INTENT)
            ?: return Status.GRANTED
        // reason: unsafeCheckOpNoThrow is flagged @Deprecated in compileSdk 36
        // in favor of the AttributionSource-based variants, but those require
        // building a full AttributionSource (uid/pid/package/tag) just to
        // query our OWN app's op mode. The "unsafe" variant is unsafe ONLY
        // when called for ANOTHER package (caller-spoofing risk). Querying
        // our own package by our own uid is the documented safe pattern.
        @Suppress("DEPRECATION")
        val mode = aom.unsafeCheckOpNoThrow(
            opStr,
            Process.myUid(),
            context.packageName,
        )
        return if (mode == AppOpsManager.MODE_ALLOWED) Status.GRANTED else Status.DENIED
    }

    private fun checkBatteryUnrestricted(context: Context): Status {
        // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is fine to call any time; the
        // OS just reports whether we're already on the exempt list.
        val pm = context.getSystemService(PowerManager::class.java) ?: return Status.DENIED
        return if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            Status.GRANTED
        } else {
            Status.DENIED
        }
    }

    // ---------------------------------------------------------------------
    // Deep-links. Each returns an Intent ready to startActivity. Callers
    // should wrap in runCatching since OEM customization sometimes deletes
    // these actions; the per-app Settings page is the universal fallback.
    // ---------------------------------------------------------------------

    fun intentFor(context: Context, item: Item): Intent = when (item) {
        Item.POST_NOTIFICATIONS -> notificationSettingsIntent(context)
        Item.EXACT_ALARM -> exactAlarmIntent(context)
        Item.FULL_SCREEN_INTENT -> fullScreenIntentIntent(context)
        Item.BATTERY_UNRESTRICTED -> batteryOptimizationIntent(context)
    }

    private fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun exactAlarmIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = pkgUri(context)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun fullScreenIntentIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = pkgUri(context)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            appDetailsIntent(context)
        }

    @Suppress("BatteryLife")
    // reason: alarm-clock apps are an explicit Google Play allowed use case for
    // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — see
    // https://developer.android.com/training/monitoring-device-state/doze-standby
    // and the alarm-clock-category exemption in Play policy.
    private fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = pkgUri(context)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = pkgUri(context)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun pkgUri(context: Context): Uri = "package:${context.packageName}".toUri()
}
