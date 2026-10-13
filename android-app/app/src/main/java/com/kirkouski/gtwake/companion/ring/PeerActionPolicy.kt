package com.kirkouski.gtwake.companion.ring

/**
 * Pure decision logic for watch-originated dismiss/snooze requests. No Android
 * types, so every row of the reply matrix is unit-tested without a device.
 *
 * Matrix (occurrence-aware = the request carried `firedAt` AND a request stamp):
 *
 *  1. an action for this occurrence is already in flight      -> [PeerDecision.Ignore]
 *  2. this exact request already succeeded                    -> [PeerDecision.ReplayOk]
 *  3/4. occurrence is current and not ended                   -> [PeerDecision.Apply]
 *       (the caller commits; ok:true on success, ok:false when the commit
 *       failed and the phone ring continues)
 *  5. occurrence is not current (ended, replaced, unknown)    -> [PeerDecision.PeerEnded]
 *
 * Legacy requests (no `firedAt` or no stamp) never get a reply. They are
 * applied only to the current, not-ended ring of the same alarm.
 *
 * State lives in memory for one service instance (see [PeerActionState]).
 */
internal enum class RingActionKind(val wire: String) {
    DISMISS("alarm_dismissed"),
    SNOOZE("alarm_snoozed"),
}

internal enum class RingOutcome { DISMISSED, SNOOZED }

internal data class OccurrenceKey(val alarmId: Long, val firedAtEpoch: Long)

internal data class PeerRequestKey(
    val occurrence: OccurrenceKey,
    val type: String,
    val stamp: Long,
)

/** What the watch asked for, as parsed from the service intent. */
internal data class PeerRequest(
    val alarmId: Long,
    val type: String,
    val firedAtEpoch: Long?,
    val requestStamp: Long?,
) {
    /** Only occurrence-aware requests are acknowledged or answered. */
    val isOccurrenceAware: Boolean
        get() = firedAtEpoch != null && requestStamp != null

    fun occurrenceOrNull(): OccurrenceKey? = firedAtEpoch?.let { OccurrenceKey(alarmId, it) }

    fun requestKeyOrNull(): PeerRequestKey? {
        val occurrence = occurrenceOrNull() ?: return null
        val stamp = requestStamp ?: return null
        return PeerRequestKey(occurrence, type, stamp)
    }
}

/** The ring this service instance owns right now. [alarmId] < 0 = none. */
internal data class CurrentRing(
    val alarmId: Long,
    val firedAtEpoch: Long,
    val ended: Boolean,
) {
    fun isOpen(occurrence: OccurrenceKey): Boolean =
        alarmId >= 0L && !ended && alarmId == occurrence.alarmId && firedAtEpoch == occurrence.firedAtEpoch

    fun isOpenForAlarm(id: Long): Boolean = alarmId >= 0L && !ended && alarmId == id
}

internal sealed interface PeerDecision {
    /** Case 1, or a legacy request that cannot be applied. No effect, no reply. */
    data object Ignore : PeerDecision

    /** Case 2: exact replay of a request that already succeeded. Reply ok:true, change nothing. */
    data object ReplayOk : PeerDecision

    /** Cases 3 and 4: current and open. The caller claims, commits and replies. */
    data object Apply : PeerDecision

    /** Legacy request on the current matching ring: apply like HEAD, never reply. */
    data object ApplyLegacy : PeerDecision

    /** Case 5: nothing is applied; tell the watch how the occurrence ended. */
    data class PeerEnded(val type: String, val firedAtEpoch: Long) : PeerDecision
}

/**
 * Per service instance, main thread only. Bounded so a long-lived process
 * cannot grow it without limit.
 */
internal class PeerActionState(private val capacity: Int = DEFAULT_CAPACITY) {
    private val inFlight = HashSet<OccurrenceKey>()
    private val succeeded = LinkedHashSet<PeerRequestKey>()
    private val outcomes = LinkedHashMap<OccurrenceKey, RingOutcome>()

    fun isInFlight(occurrence: OccurrenceKey): Boolean = occurrence in inFlight

    /** False when an action for this occurrence is already in flight. */
    fun tryClaim(occurrence: OccurrenceKey): Boolean = inFlight.add(occurrence)

    fun release(occurrence: OccurrenceKey) {
        inFlight.remove(occurrence)
    }

    fun hasSucceeded(request: PeerRequestKey): Boolean = request in succeeded

    fun outcomeOf(occurrence: OccurrenceKey): RingOutcome? = outcomes[occurrence]

    /** An occurrence ended without a watch request (takeover, auto-stop, local tap). */
    fun recordOutcome(occurrence: OccurrenceKey, outcome: RingOutcome) {
        outcomes.remove(occurrence)
        outcomes[occurrence] = outcome
        while (outcomes.size > capacity) {
            val eldest = outcomes.keys.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    /** A committed action: ends the claim, records the outcome and the request. */
    fun recordSuccess(occurrence: OccurrenceKey, outcome: RingOutcome, request: PeerRequestKey?) {
        inFlight.remove(occurrence)
        recordOutcome(occurrence, outcome)
        if (request == null) return
        succeeded.add(request)
        while (succeeded.size > capacity) {
            val eldest = succeeded.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}

internal object PeerActionPolicy {

    fun decide(state: PeerActionState, request: PeerRequest, current: CurrentRing): PeerDecision =
        if (request.isOccurrenceAware) decideAware(state, request, current) else decideLegacy(state, request, current)

    private fun decideAware(state: PeerActionState, request: PeerRequest, current: CurrentRing): PeerDecision {
        val occurrence = checkNotNull(request.occurrenceOrNull())
        val requestKey = checkNotNull(request.requestKeyOrNull())
        return when {
            state.hasSucceeded(requestKey) -> PeerDecision.ReplayOk
            state.isInFlight(occurrence) -> PeerDecision.Ignore
            current.isOpen(occurrence) -> PeerDecision.Apply
            else -> PeerDecision.PeerEnded(endedType(state.outcomeOf(occurrence)), occurrence.firedAtEpoch)
        }
    }

    private fun decideLegacy(state: PeerActionState, request: PeerRequest, current: CurrentRing): PeerDecision {
        val open = current.isOpenForAlarm(request.alarmId)
        val busy = open && state.isInFlight(OccurrenceKey(current.alarmId, current.firedAtEpoch))
        return if (open && !busy) PeerDecision.ApplyLegacy else PeerDecision.Ignore
    }

    /** `alarm_snoozed` only when this instance recorded a snooze for the occurrence. */
    fun endedType(outcome: RingOutcome?): String =
        if (outcome == RingOutcome.SNOOZED) RingActionKind.SNOOZE.wire else RingActionKind.DISMISS.wire
}
