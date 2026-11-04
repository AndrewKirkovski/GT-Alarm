package com.kirkouski.gtwake.companion.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchStatusPolicyTest {

    private fun notice(
        status: WatchStatus? = WatchStatus(batteryPercent = 8, charging = false),
        anyAlarmEnabled: Boolean = true,
        bonded: Boolean = true,
        appCode: Int? = 1000013,
        requiredCode: Int = 0,
    ) = WatchStatusPolicy.notice(appCode, status, anyAlarmEnabled, bonded, requiredCode)

    @Test
    fun `battery at the limit warns`() {
        assertEquals(WatchNotice.LowBattery(10), notice(WatchStatus(10, false)))
    }

    @Test
    fun `battery above the limit says nothing`() {
        assertNull(notice(WatchStatus(11, false)))
    }

    @Test
    fun `a charging watch says nothing`() {
        assertNull(notice(WatchStatus(5, true)))
    }

    @Test
    fun `no enabled alarm says nothing`() {
        assertNull(notice(anyAlarmEnabled = false))
    }

    @Test
    fun `a failed read says nothing`() {
        assertNull(notice(status = null))
    }

    @Test
    fun `no bonded watch says nothing`() {
        assertNull(notice(bonded = false, requiredCode = 1000099))
    }

    @Test
    fun `an update comes before the battery`() {
        assertEquals(WatchNotice.UpdateApp, notice(appCode = 1000013, requiredCode = 1000014))
    }
}
