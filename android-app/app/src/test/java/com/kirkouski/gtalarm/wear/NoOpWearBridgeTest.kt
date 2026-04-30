package com.kirkouski.gtalarm.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class NoOpWearBridgeTest {

    @Test
    fun `statusFlow initial value is NOT_CONNECTED`() {
        val bridge = NoOpWearBridge()
        assertEquals(WatchSyncStatus.NOT_CONNECTED, bridge.statusFlow.value)
    }

    @Test
    fun `statusFlow stays NOT_CONNECTED after send and bridge calls (no peer to connect)`() {
        val bridge = NoOpWearBridge()
        bridge.sendAlarmFired(42L)
        bridge.sendAlarmDismissed(42L)
        bridge.setIncomingHandler(null)
        // Pin guarantee — until HuaweiWearBridge replaces this, the flow
        // never advances. UI cards rely on this for the "not connected"
        // baseline rendering.
        assertEquals(WatchSyncStatus.NOT_CONNECTED, bridge.statusFlow.value)
    }
}
