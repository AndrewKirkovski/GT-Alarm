package com.kirkouski.gtwake.companion.data.bfu

import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BfuAlarmCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var cache: BfuAlarmCache

    @Before
    fun setUp() {
        file = File(tmp.root, "bfu_alarms.json")
        cache = BfuAlarmCache(file)
    }

    @Test
    fun `round-trip preserves all persisted fields including null relativeMinutes`() = runTest {
        val absolute = Alarm(
            id = 1L,
            label = "Wake",
            hour = 7,
            minute = 30,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true,
            isVibrationOnly = false,
            snoozeMinutes = 7,
            updatedAtEpoch = 1_000L,
            relativeMinutes = null,
            selfDestruct = false,
        )
        val relative = Alarm(
            id = 2L,
            label = "Coffee",
            hour = 0,
            minute = 0,
            daysOfWeek = 0,
            enabled = true,
            isVibrationOnly = true,
            snoozeMinutes = 1,
            updatedAtEpoch = 2_000L,
            relativeMinutes = 10,
            selfDestruct = true,
        )
        cache.replaceAll(listOf(absolute, relative))

        // Force a fresh load via a new instance pointing at the same file —
        // proves the round-trip survives JSON serialise/deserialise, not
        // just the in-memory snapshot.
        val fresh = BfuAlarmCache(file)
        val read = fresh.getAll()

        assertEquals(2, read.size)
        val r1 = read.first { it.id == 1L }
        assertEquals(7, r1.hour); assertEquals(30, r1.minute)
        assertEquals(DaysOfWeek.WEEKDAYS, r1.daysOfWeek)
        assertEquals(7, r1.snoozeMinutes)
        assertEquals(1_000L, r1.updatedAtEpoch)
        assertEquals(null, r1.relativeMinutes)
        assertFalse(r1.selfDestruct)

        val r2 = read.first { it.id == 2L }
        assertEquals(10, r2.relativeMinutes)
        assertTrue(r2.selfDestruct)
        assertTrue(r2.isVibrationOnly)
    }

    @Test
    fun `upsert with enabled=false removes existing row`() = runTest {
        val a = Alarm(id = 5L, hour = 6, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L)
        cache.replaceAll(listOf(a))
        assertEquals(1, cache.getAll().size)

        cache.upsert(a.copy(enabled = false, updatedAtEpoch = 2L))

        assertTrue("disabled alarm must be removed", cache.getAll().isEmpty())
    }

    @Test
    fun `upsert with enabled=false on absent id is no-op (no phantom write)`() = runTest {
        cache.upsert(Alarm(id = 99L, hour = 8, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = false, updatedAtEpoch = 1L))

        assertTrue(cache.getAll().isEmpty())
    }

    @Test
    fun `replaceAll filters out disabled alarms`() = runTest {
        val on = Alarm(id = 1L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L)
        val off = Alarm(id = 2L, hour = 8, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = false, updatedAtEpoch = 1L)

        cache.replaceAll(listOf(on, off))

        val read = cache.getAll()
        assertEquals(1, read.size)
        assertEquals(1L, read[0].id)
    }

    @Test
    fun `remove drops the matching id, leaves others`() = runTest {
        val a = Alarm(id = 1L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L)
        val b = Alarm(id = 2L, hour = 8, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L)
        cache.replaceAll(listOf(a, b))

        cache.remove(1L)

        val read = cache.getAll()
        assertEquals(1, read.size)
        assertEquals(2L, read[0].id)
    }

    @Test
    fun `schema-version mismatch returns empty list and does not throw`() {
        file.writeText("""{"v":999,"alarms":[{"id":1,"hour":7}]}""", Charsets.UTF_8)
        // Fresh instance to bypass any in-memory snapshot from setUp.
        val fresh = BfuAlarmCache(file)

        assertTrue(fresh.getAll().isEmpty())
    }

    @Test
    fun `corrupt JSON returns empty list and does not throw`() {
        file.writeText("not json {{{", Charsets.UTF_8)
        val fresh = BfuAlarmCache(file)

        assertTrue(fresh.getAll().isEmpty())
    }

    @Test
    fun `missing file returns empty list`() {
        // setUp doesn't create the file; ensure that's the starting state.
        assertFalse(file.exists())

        assertTrue(cache.getAll().isEmpty())
    }

    @Test
    fun `consecutive upserts accumulate without losing rows`() = runTest {
        cache.upsert(Alarm(id = 1L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L))
        cache.upsert(Alarm(id = 2L, hour = 8, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L))
        cache.upsert(Alarm(id = 3L, hour = 9, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L))

        assertEquals(3, cache.getAll().size)
    }

    @Test
    fun `upsert updates fields on existing id rather than duplicating`() = runTest {
        cache.upsert(Alarm(id = 7L, hour = 6, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L))
        cache.upsert(Alarm(id = 7L, hour = 8, minute = 15, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 2L))

        val read = cache.getAll()
        assertEquals(1, read.size)
        assertEquals(8, read[0].hour)
        assertEquals(15, read[0].minute)
        assertEquals(2L, read[0].updatedAtEpoch)
    }

    @Test
    fun `caller downcast-and-mutate must not corrupt subsequent reads`() = runTest {
        // Defense-in-depth — caller mutating a downcast list must not
        // corrupt the cache snapshot.
        cache.upsert(Alarm(id = 1L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L))
        cache.upsert(Alarm(id = 2L, hour = 8, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L))

        @Suppress("UNCHECKED_CAST")
        val mutable = cache.getAll() as? MutableList<Alarm>
        if (mutable != null) runCatching { mutable.clear() }

        val readAfter = cache.getAll()
        assertEquals("snapshot mutated by external caller", 2, readAfter.size)
    }

    @Test
    fun `markDismissed accumulates ids and survives disk round-trip`() = runTest {
        cache.markDismissed(7L)
        cache.markDismissed(13L)
        cache.markDismissed(7L)  // duplicate must not double-count

        val fresh = BfuAlarmCache(file)
        assertEquals(setOf(7L, 13L), fresh.getPendingDismissals())
    }

    @Test
    fun `drainPendingDismissals returns set and clears it atomically`() = runTest {
        cache.markDismissed(1L)
        cache.markDismissed(2L)

        val drained = cache.drainPendingDismissals()

        assertEquals(setOf(1L, 2L), drained)
        assertTrue(cache.getPendingDismissals().isEmpty())
        // Persistence: a fresh instance also sees the cleared state.
        assertTrue(BfuAlarmCache(file).getPendingDismissals().isEmpty())
    }

    @Test
    fun `remove drops the matching id from pendingDismissals too`() = runTest {
        cache.markDismissed(5L)
        cache.markDismissed(6L)

        cache.remove(5L)

        assertEquals(setOf(6L), cache.getPendingDismissals())
    }

    @Test
    fun `replaceAll filters out disabled alarms and a fresh instance round-trips the filter`() = runTest {
        val on = Alarm(id = 1L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L)
        val off = Alarm(id = 2L, hour = 8, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = false, updatedAtEpoch = 1L)
        val onAgain = Alarm(id = 3L, hour = 9, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L)
        cache.replaceAll(listOf(on, off, onAgain))

        // Fresh instance proves the filter survives the JSON round-trip,
        // not just the in-memory snapshot.
        val fresh = BfuAlarmCache(file)
        val read = fresh.getAll()
        assertEquals(2, read.size)
        assertEquals(setOf(1L, 3L), read.map { it.id }.toSet())
    }

    @Test
    fun `v3 round-trip preserves Tier 1+2 fields`() = runTest {
        // BfuAlarmCache schema v3 must persist + read back vibrationPattern,
        // volumeRampSeconds, maxSnoozeCount, consecutiveSnoozeCount,
        // skipNextEpoch. Without this round-trip, the pre-unlock fire path
        // would lose the user-chosen pattern + cap state, and the
        // applyPendingSnoozeBumps drain would always start from 0.
        val a = Alarm(
            id = 11L,
            label = "x",
            hour = 6,
            minute = 45,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true,
            isVibrationOnly = false,
            snoozeMinutes = 5,
            updatedAtEpoch = 1_000L,
            relativeMinutes = null,
            selfDestruct = false,
            vibrationPattern = com.kirkouski.gtwake.companion.domain.VibrationPattern.HEARTBEAT,
            volumeRampSeconds = 30,
            maxSnoozeCount = 3,
            consecutiveSnoozeCount = 2,
            skipNextEpoch = 1_700_000_000_000L,
        )
        cache.upsert(a)

        val fresh = BfuAlarmCache(file)
        val read = fresh.getAll().single()
        assertEquals(com.kirkouski.gtwake.companion.domain.VibrationPattern.HEARTBEAT, read.vibrationPattern)
        assertEquals(30, read.volumeRampSeconds)
        assertEquals(3, read.maxSnoozeCount)
        assertEquals(2, read.consecutiveSnoozeCount)
        assertEquals(1_700_000_000_000L, read.skipNextEpoch)
    }

    @Test
    fun `markPendingSnoozeBump increments the count and survives disk round-trip`() = runTest {
        cache.markPendingSnoozeBump(7L)
        cache.markPendingSnoozeBump(7L)
        cache.markPendingSnoozeBump(13L)

        val fresh = BfuAlarmCache(file)
        val drained = fresh.drainPendingSnoozeBumps()
        assertEquals(2, drained[7L])
        assertEquals(1, drained[13L])
    }

    @Test
    fun `drainPendingSnoozeBumps clears the map atomically`() = runTest {
        cache.markPendingSnoozeBump(1L)
        cache.markPendingSnoozeBump(1L)

        val drained = cache.drainPendingSnoozeBumps()
        assertEquals(mapOf(1L to 2), drained)
        assertTrue(BfuAlarmCache(file).drainPendingSnoozeBumps().isEmpty())
    }

    @Test
    fun `remove drops the matching id from pendingSnoozeBumps too`() = runTest {
        cache.markPendingSnoozeBump(5L)
        cache.markPendingSnoozeBump(6L)

        cache.remove(5L)

        val drained = cache.drainPendingSnoozeBumps()
        assertEquals(mapOf(6L to 1), drained)
    }
}
