package com.kirkouski.gtalarm.wear

/**
 * Connection state of the paired watch as observed by [WearBridgeService].
 *
 * Surfaced to the UI via [WearBridgeService.statusFlow]. The Hilt-bound
 * `HuaweiWearBridge` transitions through [CONNECTING] → [CONNECTED] as the
 * Wear Engine peer status changes, and emits [ERROR] on transport failures
 * the user should know about. [NOT_CONNECTED] is the initial state and the
 * resting state when no watch is paired.
 */
enum class WatchSyncStatus {
    NOT_CONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}
