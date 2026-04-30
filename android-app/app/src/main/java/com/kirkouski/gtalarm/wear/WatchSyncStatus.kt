package com.kirkouski.gtalarm.wear

/**
 * Connection state of the paired watch as observed by [WearBridgeService].
 *
 * Surfaced to the UI via [WearBridgeService.statusFlow]. The current
 * [NoOpWearBridge] is permanently [NOT_CONNECTED] (no peer); a future
 * `HuaweiWearBridge` will transition through [CONNECTING] → [CONNECTED]
 * once Huawei Wear Engine P2P is wired up, and emit [ERROR] on transport
 * failures the user should know about.
 */
enum class WatchSyncStatus {
    NOT_CONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}
