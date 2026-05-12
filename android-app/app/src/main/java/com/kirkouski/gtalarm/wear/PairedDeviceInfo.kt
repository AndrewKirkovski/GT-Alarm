package com.kirkouski.gtalarm.wear

/**
 * Snapshot of the currently bonded Huawei wearable as reported by Wear Engine's
 * `DeviceClient.getBondedDevices()`. Surfaced via [WearBridgeService.pairedDeviceInfo]
 * so the alarm-list "Watch sync" card can show "name · model" instead of just a
 * connection state.
 *
 * `null` means "no bonded device yet resolved" (cold start, Wear Engine SDK
 * absent, or Huawei Health permission not granted).
 */
data class PairedDeviceInfo(
    val name: String,
    val model: String,
    val connected: Boolean,
)
