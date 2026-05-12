package com.kirkouski.gtalarm.ui.help

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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

    private fun checkFullScreenIntent(context: Context): Status {
        // canUseFullScreenIntent() landed in Android 14 (UPSIDE_DOWN_CAKE).
        // Older Androids honor USE_FULL_SCREEN_INTENT manifest grant directly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return Status.GRANTED
        val nm = context.getSystemService(NotificationManager::class.java) ?: return Status.DENIED
        return if (nm.canUseFullScreenIntent()) Status.GRANTED else Status.DENIED
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
