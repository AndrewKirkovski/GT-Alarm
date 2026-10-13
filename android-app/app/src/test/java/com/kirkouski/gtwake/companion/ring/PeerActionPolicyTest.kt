package com.kirkouski.gtwake.companion.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every row of the phone reply rule (docs/sync-architecture.md, "Phone reply
 * rule") as pure logic. Case numbers in the test names match the numbered list
 * in the PeerActionPolicy header.
 *
 * Before the change there was no PeerActionPolicy: the service decided through
 * persistent receipts and answered `ok:false` for a pending duplicate and for a
 * request whose occurrence had ended. Each test below therefore did not
 * compile / could not hold on the previous code; the behavioural contrasts are
 * called out per test.
 */
class PeerActionPolicyTest {

    private val dismissed = RingActionKind.DISMISS.wire
    private val snoozed = RingActionKind.SNOOZE.wire
    private val occurrence = OccurrenceKey(alarmId = 7L, firedAtEpoch = 1_000L)
    private val open = CurrentRing(alarmId = 7L, firedAtEpoch = 1_000L, ended = false)

    private fun aware(type: String = dismissed, stamp: Long = 55L, firedAt: Long = 1_000L) =
        PeerRequest(7L, type, firedAt, stamp)

    private fun keyOf(request: PeerRequest) = checkNotNull(request.requestKeyOrNull())

    @Test
    fun `case 3 - current open occurrence is applied`() {
        val decision = PeerActionPolicy.decide(PeerActionState(), aware(), open)
        assertEquals(PeerDecision.Apply, decision)
    }

    @Test
    fun `case 1 - action already in flight gets no effect and no reply`() {
        // Pre-change: a pending duplicate got ok:false, which un-armed the watch.
        val state = PeerActionState()
        assertTrue(state.tryClaim(occurrence))

        val decision = PeerActionPolicy.decide(state, aware(stamp = 56L), open)

        assertEquals(PeerDecision.Ignore, decision)
    }

    @Test
    fun `case 1 - a phone-local action in flight also silences a watch request`() {
        // The claim is per occurrence, whoever made it (phone tap, auto-stop or watch).
        val state = PeerActionState()
        state.tryClaim(occurrence)

        assertEquals(PeerDecision.Ignore, PeerActionPolicy.decide(state, aware(type = snoozed), open))
    }

    @Test
    fun `case 1 - claim is exclusive and released after a failure`() {
        val state = PeerActionState()
        assertTrue(state.tryClaim(occurrence))
        assertFalse(state.tryClaim(occurrence))
        state.release(occurrence)
        assertTrue(state.tryClaim(occurrence))
    }

    @Test
    fun `case 2 - exact replay of a succeeded request replies ok true`() {
        val state = PeerActionState()
        val request = aware()
        state.tryClaim(occurrence)
        state.recordSuccess(occurrence, RingOutcome.DISMISSED, keyOf(request))
        val ended = open.copy(ended = true)

        assertEquals(PeerDecision.ReplayOk, PeerActionPolicy.decide(state, request, ended))
    }

    @Test
    fun `case 2 - replay still answers ok true after a newer ring took over`() {
        val state = PeerActionState()
        val request = aware()
        state.recordSuccess(occurrence, RingOutcome.DISMISSED, keyOf(request))
        val newer = CurrentRing(alarmId = 9L, firedAtEpoch = 5_000L, ended = false)

        assertEquals(PeerDecision.ReplayOk, PeerActionPolicy.decide(state, request, newer))
    }

    @Test
    fun `case 2 - a different stamp is not a replay`() {
        val state = PeerActionState()
        state.recordSuccess(occurrence, RingOutcome.DISMISSED, keyOf(aware(stamp = 55L)))
        val ended = open.copy(ended = true)

        val decision = PeerActionPolicy.decide(state, aware(stamp = 99L), ended)

        assertEquals(PeerDecision.PeerEnded(dismissed, 1_000L), decision)
    }

    @Test
    fun `case 2 - a different type for the same stamp is not a replay`() {
        val state = PeerActionState()
        state.recordSuccess(occurrence, RingOutcome.DISMISSED, keyOf(aware(type = dismissed)))

        val decision = PeerActionPolicy.decide(state, aware(type = snoozed), open.copy(ended = true))

        assertTrue(decision is PeerDecision.PeerEnded)
    }

    @Test
    fun `case 5 - ended occurrence with a recorded snooze answers alarm_snoozed`() {
        val state = PeerActionState()
        state.recordOutcome(occurrence, RingOutcome.SNOOZED)

        val decision = PeerActionPolicy.decide(state, aware(type = dismissed), open.copy(ended = true))

        assertEquals(PeerDecision.PeerEnded(snoozed, 1_000L), decision)
    }

    @Test
    fun `case 5 - ended occurrence with a recorded dismiss answers alarm_dismissed`() {
        val state = PeerActionState()
        state.recordOutcome(occurrence, RingOutcome.DISMISSED)

        val decision = PeerActionPolicy.decide(state, aware(type = snoozed), open.copy(ended = true))

        assertEquals(PeerDecision.PeerEnded(dismissed, 1_000L), decision)
    }

    @Test
    fun `case 5 - replaced occurrence is never applied`() {
        // Same alarm, newer ring (snooze re-fire): the old request must not touch it.
        val state = PeerActionState()
        val newer = CurrentRing(alarmId = 7L, firedAtEpoch = 2_000L, ended = false)

        val decision = PeerActionPolicy.decide(state, aware(firedAt = 1_000L), newer)

        assertEquals(PeerDecision.PeerEnded(dismissed, 1_000L), decision)
    }

    @Test
    fun `case 5 - unknown occurrence after a service restart is answered as ended dismissed`() {
        val none = CurrentRing(alarmId = -1L, firedAtEpoch = -1L, ended = false)

        val decision = PeerActionPolicy.decide(PeerActionState(), aware(type = snoozed), none)

        assertEquals(PeerDecision.PeerEnded(dismissed, 1_000L), decision)
    }

    @Test
    fun `case 5 - a different alarm ringing is not this occurrence`() {
        val other = CurrentRing(alarmId = 8L, firedAtEpoch = 1_000L, ended = false)

        assertTrue(PeerActionPolicy.decide(PeerActionState(), aware(), other) is PeerDecision.PeerEnded)
    }

    @Test
    fun `legacy - no firedAt on the current open ring of the same alarm is applied without a reply`() {
        val legacy = PeerRequest(7L, dismissed, firedAtEpoch = null, requestStamp = null)

        assertFalse(legacy.isOccurrenceAware)
        assertEquals(PeerDecision.ApplyLegacy, PeerActionPolicy.decide(PeerActionState(), legacy, open))
    }

    @Test
    fun `legacy - firedAt without a stamp is legacy too`() {
        val partial = PeerRequest(7L, snoozed, firedAtEpoch = 1_000L, requestStamp = null)

        assertFalse(partial.isOccurrenceAware)
        assertEquals(PeerDecision.ApplyLegacy, PeerActionPolicy.decide(PeerActionState(), partial, open))
    }

    @Test
    fun `legacy - stamp without firedAt is legacy too`() {
        val partial = PeerRequest(7L, snoozed, firedAtEpoch = null, requestStamp = 12L)

        assertFalse(partial.isOccurrenceAware)
        assertEquals(PeerDecision.ApplyLegacy, PeerActionPolicy.decide(PeerActionState(), partial, open))
    }

    @Test
    fun `legacy - other alarm, ended ring, no ring and in-flight are all ignored`() {
        val legacy = PeerRequest(7L, dismissed, null, null)
        val state = PeerActionState()

        assertEquals(
            PeerDecision.Ignore,
            PeerActionPolicy.decide(state, legacy, CurrentRing(8L, 1_000L, ended = false)),
        )
        assertEquals(PeerDecision.Ignore, PeerActionPolicy.decide(state, legacy, open.copy(ended = true)))
        assertEquals(PeerDecision.Ignore, PeerActionPolicy.decide(state, legacy, CurrentRing(-1L, -1L, false)))

        state.tryClaim(occurrence)
        assertEquals(PeerDecision.Ignore, PeerActionPolicy.decide(state, legacy, open))
    }

    @Test
    fun `endedType is alarm_snoozed only for a recorded snooze`() {
        assertEquals(snoozed, PeerActionPolicy.endedType(RingOutcome.SNOOZED))
        assertEquals(dismissed, PeerActionPolicy.endedType(RingOutcome.DISMISSED))
        assertEquals(dismissed, PeerActionPolicy.endedType(null))
    }

    @Test
    fun `recordSuccess ends the claim and remembers the outcome and the request`() {
        val state = PeerActionState()
        val request = aware()
        state.tryClaim(occurrence)

        state.recordSuccess(occurrence, RingOutcome.SNOOZED, keyOf(request))

        assertFalse(state.isInFlight(occurrence))
        assertEquals(RingOutcome.SNOOZED, state.outcomeOf(occurrence))
        assertTrue(state.hasSucceeded(keyOf(request)))
    }

    @Test
    fun `a failed action leaves no outcome and no succeeded request`() {
        val state = PeerActionState()
        state.tryClaim(occurrence)
        state.release(occurrence)

        assertNull(state.outcomeOf(occurrence))
        assertFalse(state.hasSucceeded(keyOf(aware())))
        // and the same request is applicable again
        assertEquals(PeerDecision.Apply, PeerActionPolicy.decide(state, aware(), open))
    }

    @Test
    fun `state is bounded - the eldest outcomes and requests are dropped`() {
        val state = PeerActionState(capacity = 4)
        for (i in 1L..10L) {
            val occ = OccurrenceKey(1L, i)
            state.recordSuccess(occ, RingOutcome.DISMISSED, PeerRequestKey(occ, dismissed, i))
        }

        assertNull(state.outcomeOf(OccurrenceKey(1L, 1L)))
        assertNull(state.outcomeOf(OccurrenceKey(1L, 6L)))
        assertEquals(RingOutcome.DISMISSED, state.outcomeOf(OccurrenceKey(1L, 7L)))
        assertEquals(RingOutcome.DISMISSED, state.outcomeOf(OccurrenceKey(1L, 10L)))
        assertFalse(state.hasSucceeded(PeerRequestKey(OccurrenceKey(1L, 1L), dismissed, 1L)))
        assertTrue(state.hasSucceeded(PeerRequestKey(OccurrenceKey(1L, 10L), dismissed, 10L)))
    }
}
