package com.kirkouski.gtwake.companion.ring

import com.kirkouski.gtwake.companion.ring.RingEndedSignal.BackChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBackPolicyTest {

    @Test
    fun ringingWithSnoozeBackSnoozes() {
        assertEquals(BackChoice.SNOOZE, RingEndedSignal.backChoice(true, false, true))
    }

    @Test
    fun ringingWithoutSnoozeBackDismisses() {
        assertEquals(BackChoice.DISMISS, RingEndedSignal.backChoice(true, false, false))
    }

    @Test
    fun waitingForWatchBackIsConsumed() {
        assertEquals(BackChoice.IGNORE, RingEndedSignal.backChoice(true, true, true))
        assertEquals(BackChoice.IGNORE, RingEndedSignal.backChoice(true, true, false))
    }

    @Test
    fun nothingRingingBackFinishes() {
        assertEquals(BackChoice.FINISH, RingEndedSignal.backChoice(false, false, true))
        assertEquals(BackChoice.FINISH, RingEndedSignal.backChoice(false, true, false))
    }

    @Test
    fun failureOlderThanLatestTapIsStale() {
        assertTrue(RingEndedSignal.isStaleFailure(1_000L, 2_000L))
    }

    @Test
    fun failureAfterLatestTapIsFresh() {
        assertFalse(RingEndedSignal.isStaleFailure(2_000L, 2_000L))
        assertFalse(RingEndedSignal.isStaleFailure(3_000L, 2_000L))
    }
}
