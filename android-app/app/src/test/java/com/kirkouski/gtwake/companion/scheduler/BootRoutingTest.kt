package com.kirkouski.gtwake.companion.scheduler

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class BootRoutingTest {

    private val timeActions = listOf(
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
    )

    // Fails before the change: these actions always went to Room (rescheduleAll), which throws pre-unlock.
    @Test
    fun `time and package actions use the BFU cache while locked`() {
        timeActions.forEach { action ->
            assertEquals(action, BootRoute.RESCHEDULE_FROM_BFU, routeFor(action, unlocked = false))
        }
    }

    @Test
    fun `time and package actions use Room once unlocked`() {
        timeActions.forEach { action ->
            assertEquals(action, BootRoute.RESCHEDULE_ALL, routeFor(action, unlocked = true))
        }
    }

    @Test
    fun `locked boot always uses the BFU cache`() {
        assertEquals(BootRoute.RESCHEDULE_FROM_BFU, routeFor(Intent.ACTION_LOCKED_BOOT_COMPLETED, false))
        assertEquals(BootRoute.RESCHEDULE_FROM_BFU, routeFor(Intent.ACTION_LOCKED_BOOT_COMPLETED, true))
    }

    @Test
    fun `boot completed re-arms from Room on boot`() {
        assertEquals(BootRoute.RESCHEDULE_ON_BOOT, routeFor(Intent.ACTION_BOOT_COMPLETED, true))
        assertEquals(BootRoute.RESCHEDULE_ON_BOOT, routeFor(Intent.ACTION_BOOT_COMPLETED, false))
    }

    @Test
    fun `locale change only refreshes widgets in either state`() {
        assertEquals(BootRoute.REFRESH_WIDGETS, routeFor(Intent.ACTION_LOCALE_CHANGED, true))
        assertEquals(BootRoute.REFRESH_WIDGETS, routeFor(Intent.ACTION_LOCALE_CHANGED, false))
    }

    @Test
    fun `unknown or null action while locked never touches Room`() {
        assertEquals(BootRoute.RESCHEDULE_FROM_BFU, routeFor(null, unlocked = false))
        assertEquals(BootRoute.RESCHEDULE_FROM_BFU, routeFor("x.UNKNOWN", unlocked = false))
    }
}
