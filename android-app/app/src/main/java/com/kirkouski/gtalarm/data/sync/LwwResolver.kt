package com.kirkouski.gtalarm.data.sync

import com.kirkouski.gtalarm.data.Tombstones

/**
 * Pure last-write-wins helpers for the receive-side of the phone↔watch
 * sync. Rules are the design-of-record in `docs/sync-architecture.md` §2.
 *
 * No Android dependencies — keeps the unit tests JVM-pure and prevents
 * test/main coupling on `Context`. Tombstone semantics live in
 * [Tombstones.isTombstoned]; this object handles the live-row LWW.
 */
object LwwResolver {

    /**
     * Decide whether an incoming peer write should be applied to the local
     * row (or inserted, if no local row exists).
     *
     * Rules (sync-architecture §2):
     *   - no local row (`localEpoch == null`)         → apply
     *   - `incoming > local`                          → apply
     *   - tie (`incoming == local`) on a live row     → apply (incoming-wins tie-break)
     *   - `incoming < local`                          → ignore
     *
     * The tombstone tie-break ("local tombstone wins on tie") is enforced
     * separately by [Tombstones.isTombstoned] before this function is
     * consulted.
     */
    fun shouldApply(incomingEpoch: Long, localEpoch: Long?): Boolean {
        if (localEpoch == null) return true
        return incomingEpoch >= localEpoch
    }
}
