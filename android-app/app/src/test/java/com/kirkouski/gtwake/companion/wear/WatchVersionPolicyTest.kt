package com.kirkouski.gtwake.companion.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchVersionPolicyTest {

    @Test
    fun `an unknown version never prompts`() {
        assertFalse(WatchVersionPolicy.needsUpdate(stored = null, required = 1000014))
    }

    @Test
    fun `an old watch prompts once a version is required`() {
        assertTrue(WatchVersionPolicy.needsUpdate(stored = WATCH_CODE_OLDER, required = 1000014))
    }

    @Test
    fun `a lower code prompts and an equal code does not`() {
        assertTrue(WatchVersionPolicy.needsUpdate(stored = 1000013, required = 1000014))
        assertFalse(WatchVersionPolicy.needsUpdate(stored = 1000014, required = 1000014))
    }

    @Test
    fun `a missing watch app never prompts an update`() {
        assertFalse(WatchVersionPolicy.needsUpdate(WATCH_NOT_INSTALLED, required = 1_000_013))
        assertFalse(WatchVersionPolicy.needsUpdate(WATCH_NOT_INSTALLED))
    }

    @Test
    fun `no prompt ships while no version is required`() {
        assertFalse(WatchVersionPolicy.needsUpdate(stored = WATCH_CODE_OLDER))
    }

    @Test
    fun `a code maps to its version name`() {
        assertEquals("1.0.13", WatchVersionPolicy.versionName(1000013))
    }

    @Test
    fun `special values have no version name`() {
        assertNull(WatchVersionPolicy.versionName(null))
        assertNull(WatchVersionPolicy.versionName(WATCH_CODE_OLDER))
    }
}
