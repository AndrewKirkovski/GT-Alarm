package com.kirkouski.gtwake.companion.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LwwResolverTest {

    @Test
    fun `shouldApply true when no local row exists`() {
        assertTrue(LwwResolver.shouldApply(incomingEpoch = 1_000L, localEpoch = null))
    }

    @Test
    fun `shouldApply true when incoming is newer than local`() {
        assertTrue(LwwResolver.shouldApply(incomingEpoch = 1_001L, localEpoch = 1_000L))
    }

    @Test
    fun `shouldApply true on tie (sync-architecture incoming-wins tie-break)`() {
        assertTrue(LwwResolver.shouldApply(incomingEpoch = 1_000L, localEpoch = 1_000L))
    }

    @Test
    fun `shouldApply false when incoming is older than local`() {
        assertFalse(LwwResolver.shouldApply(incomingEpoch = 999L, localEpoch = 1_000L))
    }

    @Test
    fun `shouldApply true when both stamps are zero (no row, fresh write)`() {
        // localEpoch == null path is the no-row case; this test pins behaviour
        // for the legitimate case where both sides start at the same moment.
        assertTrue(LwwResolver.shouldApply(incomingEpoch = 0L, localEpoch = 0L))
    }
}
