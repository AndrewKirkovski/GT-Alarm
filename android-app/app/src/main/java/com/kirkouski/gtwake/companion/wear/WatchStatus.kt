package com.kirkouski.gtwake.companion.wear

/** Watch state read through Huawei Health. It does not open the watch app. */
data class WatchStatus(val batteryPercent: Int, val charging: Boolean)

/** The one message the alarm list shows about the watch. */
sealed interface WatchNotice {
    data object UpdateApp : WatchNotice
    data class LowBattery(val percent: Int) : WatchNotice
}

object WatchStatusPolicy {
    private const val LOW_BATTERY_PERCENT = 10

    /** One message at a time, most important first. A failed read shows nothing. */
    fun notice(
        appCode: Int?,
        status: WatchStatus?,
        anyAlarmEnabled: Boolean,
        bonded: Boolean,
        requiredCode: Int = WatchVersionPolicy.REQUIRED_WATCH_CODE,
    ): WatchNotice? = when {
        !bonded -> null
        WatchVersionPolicy.needsUpdate(appCode, requiredCode) -> WatchNotice.UpdateApp
        status != null && anyAlarmEnabled && !status.charging &&
            status.batteryPercent <= LOW_BATTERY_PERCENT -> WatchNotice.LowBattery(status.batteryPercent)
        else -> null
    }
}
