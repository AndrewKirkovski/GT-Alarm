package com.kirkouski.gtwake.companion.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Only the past-trigger guard is testable as pure JVM code; the actual
 * `DateUtils.getRelativeTimeSpanString` formatting needs the Android runtime
 * (its locale-aware string lookup throws "Stub!" against the unit-test
 * Android jar). End-to-end formatting verification belongs in
 * `connectedDebugAndroidTest`.
 */
class RelativeTimeTest {

    @Test
    fun `past trigger returns empty so the row hides the relative hint`() {
        val now = 10_000_000L
        val past = now - 1L
        assertEquals("", RelativeTime.formatUntil(past, now))
    }

    @Test
    fun `equal-to-now trigger also returns empty (boundary)`() {
        val now = 10_000_000L
        assertEquals("", RelativeTime.formatUntil(now, now))
    }
}
