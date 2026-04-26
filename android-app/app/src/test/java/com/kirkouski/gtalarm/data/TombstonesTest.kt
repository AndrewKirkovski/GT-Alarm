package com.kirkouski.gtalarm.data

import com.kirkouski.gtalarm.data.Tombstones.Companion.RETENTION_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TombstonesTest {

    private val now = 10_000_000L

    @Test
    fun `prune drops entries older than retention window`() {
        val entries = listOf(
            Tombstones.Entry(1L, now - RETENTION_MS - 1),  // expired
            Tombstones.Entry(2L, now - RETENTION_MS),       // edge: exactly at cutoff, keep
            Tombstones.Entry(3L, now),                      // fresh
        )
        val pruned = Tombstones.prune(entries, now)
        assertEquals(listOf(2L, 3L), pruned.map { it.id })
    }

    @Test
    fun `pruneAndAdd inserts new entry`() {
        val result = Tombstones.pruneAndAdd(emptyList(), Tombstones.Entry(5L, now), now)
        assertEquals(1, result.size)
        assertEquals(5L, result[0].id)
        assertEquals(now, result[0].updatedAtEpoch)
    }

    @Test
    fun `pruneAndAdd dedupes by id, keeping the new stamp`() {
        val existing = listOf(Tombstones.Entry(7L, now - 1000L))
        val result = Tombstones.pruneAndAdd(existing, Tombstones.Entry(7L, now), now)
        assertEquals(1, result.size)
        assertEquals(now, result[0].updatedAtEpoch)
    }

    @Test
    fun `pruneAndAdd caps at MAX_ENTRIES, evicting oldest insertion (head of list)`() {
        val full = (1L..Tombstones.MAX_ENTRIES.toLong()).map {
            Tombstones.Entry(it, now - it)
        }
        val result = Tombstones.pruneAndAdd(full, Tombstones.Entry(9999L, now), now)
        assertEquals(Tombstones.MAX_ENTRIES, result.size)
        assertEquals(9999L, result.last().id)
        // First inserted entry (id=1) is the head of the list and gets evicted
        // when the new entry pushes size past the cap. id=2 survives as new head.
        assertFalse("id=1 (head/oldest insertion) should be evicted", result.any { it.id == 1L })
        assertEquals(2L, result.first().id)
    }

    @Test
    fun `isTombstoned returns false for unknown id`() {
        assertFalse(Tombstones.isTombstoned(emptyList(), 1L, now))
    }

    @Test
    fun `isTombstoned blocks resurrection when stamp equals incoming (ties favour local)`() {
        val entries = listOf(Tombstones.Entry(5L, now))
        assertTrue(Tombstones.isTombstoned(entries, 5L, now))
    }

    @Test
    fun `isTombstoned blocks resurrection when stamp newer than incoming`() {
        val entries = listOf(Tombstones.Entry(5L, now))
        assertTrue(Tombstones.isTombstoned(entries, 5L, now - 1L))
    }

    @Test
    fun `isTombstoned allows fresh peer write when its stamp is newer`() {
        val entries = listOf(Tombstones.Entry(5L, now - 1L))
        assertFalse(Tombstones.isTombstoned(entries, 5L, now))
    }
}
