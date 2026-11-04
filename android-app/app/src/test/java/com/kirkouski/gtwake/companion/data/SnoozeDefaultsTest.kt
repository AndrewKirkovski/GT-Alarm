package com.kirkouski.gtwake.companion.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SnoozeDefaultsTest {

    @Test
    fun `a read failure gives the built-in values`() = runTest {
        val result = snoozeDefaultsOr(SnoozeDefaults.ALARM) { throw IOException("disk") }

        assertEquals(SnoozeDefaults.ALARM, result)
    }

    @Test
    fun `a good read gives the stored values`() = runTest {
        val stored = SnoozeDefaults(5, 3)

        assertEquals(stored, snoozeDefaultsOr(SnoozeDefaults.ALARM) { stored })
    }

    @Test
    fun `timers start with snooze off`() {
        assertEquals(0, SnoozeDefaults.TIMER.minutes)
    }
}
