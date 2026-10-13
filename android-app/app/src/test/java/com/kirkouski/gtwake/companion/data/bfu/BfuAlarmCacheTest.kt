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
    fun `snoozed trigger round-trips without folding pending snooze bumps`() = runTest {
        val alarm = Alarm(
            id = 21L,
            hour = 7,
            minute = 0,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true,
            updatedAtEpoch = 1L,
            consecutiveSnoozeCount = 2,
        )
        cache.replaceAll(listOf(alarm))
        cache.markPendingSnoozeBump(alarm.id)
        cache.setSnoozedUntil(alarm.id, 1_800_000_000_000L)

        val fresh = BfuAlarmCache(file)
        assertEquals(1_800_000_000_000L, fresh.getAll().single().snoozedUntilEpoch)
        assertEquals(mapOf(alarm.id to 1), fresh.drainPendingSnoozeBumps())
        assertEquals(2, fresh.getAll().single().consecutiveSnoozeCount)
    }

    @Test
    fun `snoozed trigger write failure keeps memory and durable snapshot unchanged`() = runTest {
        val alarm = Alarm(
            id = 23L,
            hour = 9,
            minute = 0,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true,
            updatedAtEpoch = 1L,
        )
        cache.upsert(alarm)
        val diskBefore = file.readText(Charsets.UTF_8)
        blockAtomicWrite()

        assertFalse(cache.setSnoozedUntil(alarm.id, 1_800_000_000_000L))
        assertEquals(null, cache.getAll().single().snoozedUntilEpoch)
        assertEquals(null, BfuAlarmCache(file).getAll().single().snoozedUntilEpoch)
        assertEquals(diskBefore, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `pre-unlock snooze and skip consumption do not fold pending bump into raw count`() = runTest {
        val alarm = Alarm(
            id = 26L,
            hour = 6,
            minute = 0,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true,
            updatedAtEpoch = 1L,
            consecutiveSnoozeCount = 2,
            skipNextEpoch = 1_800_000_000_000L,
            snoozedUntilEpoch = 1_800_000_000_000L,
        )
        cache.replaceAll(listOf(alarm))
        cache.markPendingSnoozeBump(alarm.id)
        assertEquals(3, cache.getAll().single().consecutiveSnoozeCount)

        assertTrue(cache.setSnoozedUntil(alarm.id, null))
        assertTrue(cache.consumePastSkip(alarm.id, nowEpoch = 1_800_000_000_001L))
        assertEquals(3, cache.getAll().single().consecutiveSnoozeCount)
        val storedAlarm = org.json.JSONObject(file.readText(Charsets.UTF_8))
            .getJSONArray("alarms").getJSONObject(0)
        assertEquals(2, storedAlarm.getInt("consecutiveSnoozeCount"))
        assertTrue(storedAlarm.isNull("snoozedUntilEpoch"))
        assertTrue(storedAlarm.isNull("skipNextEpoch"))

        assertEquals(mapOf(alarm.id to 1), cache.drainPendingSnoozeBumps())
        assertEquals(2, cache.getAll().single().consecutiveSnoozeCount)
    }

    @Test
    fun `future skip survives pre-unlock consumption without folding pending bump`() = runTest {
        val nowEpoch = 1_800_000_000_000L
        val futureSkipEpoch = nowEpoch + 60_000L
        val alarm = Alarm(
            id = 27L,
            hour = 7,
            minute = 0,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true,
            updatedAtEpoch = 1L,
            consecutiveSnoozeCount = 2,
            skipNextEpoch = futureSkipEpoch,
        )
        cache.replaceAll(listOf(alarm))
        cache.markPendingSnoozeBump(alarm.id)

        assertTrue(cache.consumePastSkip(alarm.id, nowEpoch))
        val runtimeAlarm = cache.getAll().single()
        assertEquals(3, runtimeAlarm.consecutiveSnoozeCount)
        assertEquals(futureSkipEpoch, runtimeAlarm.skipNextEpoch)
        assertEquals(mapOf(alarm.id to 1), cache.drainPendingSnoozeBumps())
        val rawAlarm = org.json.JSONObject(file.readText(Charsets.UTF_8))
            .getJSONArray("alarms").getJSONObject(0)
        assertEquals(2, rawAlarm.getInt("consecutiveSnoozeCount"))
        assertEquals(futureSkipEpoch, rawAlarm.getLong("skipNextEpoch"))
    }

    @Test
    fun `cache entries without optional snoozed trigger still load`() = runTest {
        val alarm = Alarm(
            id = 22L,
            hour = 8,
            minute = 0,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true,
            updatedAtEpoch = 1L,
        )
        cache.upsert(alarm)
        val root = org.json.JSONObject(file.readText(Charsets.UTF_8))
        root.getJSONArray("alarms").getJSONObject(0).remove("snoozedUntilEpoch")
        file.writeText(root.toString(), Charsets.UTF_8)

        assertEquals(null, BfuAlarmCache(file).getAll().single().snoozedUntilEpoch)
    }

    private fun alarm(id: Long, count: Int = 0) = Alarm(
        id = id, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
        enabled = true, updatedAtEpoch = 1L, consecutiveSnoozeCount = count,
    )

    private fun rawRow(): org.json.JSONObject =
        org.json.JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("alarms").getJSONObject(0)

    // Fails before the change: the receipt key is still written, so has("peerActionReceipts") is true.
    @Test
    fun `file with legacy peerActionReceipts key loads and drops the key on next write`() = runTest {
        cache.upsert(alarm(40L))
        val root = org.json.JSONObject(file.readText(Charsets.UTF_8))
        root.put("peerActionReceipts", org.json.JSONObject().put("40:1:alarm_dismissed:2", "succeeded"))
        file.writeText(root.toString(), Charsets.UTF_8)

        val fresh = BfuAlarmCache(file)
        assertEquals(40L, fresh.getAll().single().id)
        assertTrue(fresh.upsert(alarm(41L)))

        assertFalse(org.json.JSONObject(file.readText(Charsets.UTF_8)).has("peerActionReceipts"))
        assertEquals(2, BfuAlarmCache(file).getAll().size)
    }

    // Fails before the change: recordPreUnlockSnooze does not exist (compile error).
    @Test
    fun `recordPreUnlockSnooze writes trigger and bump together and survives reload`() = runTest {
        cache.replaceAll(listOf(alarm(42L, count = 1)))

        assertTrue(cache.recordPreUnlockSnooze(42L, 1_800_000_000_000L))
        assertTrue(cache.recordPreUnlockSnooze(42L, 1_800_000_300_000L))

        val fresh = BfuAlarmCache(file)
        val row = fresh.getAll().single()
        assertEquals(1_800_000_300_000L, row.snoozedUntilEpoch)
        assertEquals(3, row.consecutiveSnoozeCount) // raw 1 + 2 bumps
        assertEquals(1, rawRow().getInt("consecutiveSnoozeCount"))
        assertEquals(mapOf(42L to 2), fresh.drainPendingSnoozeBumps())
    }

    // Fails before the change: compile error (function missing).
    @Test
    fun `recordPreUnlockSnooze write failure publishes neither field`() = runTest {
        cache.replaceAll(listOf(alarm(43L)))
        val diskBefore = file.readText(Charsets.UTF_8)
        blockAtomicWrite()

        assertFalse(cache.recordPreUnlockSnooze(43L, 1_800_000_000_000L))
        assertEquals(null, cache.getAll().single().snoozedUntilEpoch)
        assertEquals(0, cache.getAll().single().consecutiveSnoozeCount)
        assertEquals(diskBefore, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `recordPreUnlockSnooze returns false for a missing row`() = runTest {
        assertFalse(cache.recordPreUnlockSnooze(99L, 1L))
        assertTrue(cache.drainPendingSnoozeBumps().isEmpty())
    }

    // Fails before the change: the pending bump survives the dismiss, so the drain returns {44=2}
    // and the raw count stays 1.
    @Test
    fun `markDismissed zeroes the row count and drops bumps in one write`() = runTest {
        cache.replaceAll(listOf(alarm(44L, count = 1)))
        cache.recordPreUnlockSnooze(44L, 1_800_000_000_000L)
        cache.recordPreUnlockSnooze(44L, 1_800_000_300_000L)

        assertTrue(cache.markDismissed(44L))

        val fresh = BfuAlarmCache(file)
        assertEquals(0, rawRow().getInt("consecutiveSnoozeCount"))
        assertEquals(0, fresh.getAll().single().consecutiveSnoozeCount)
        assertEquals(setOf(44L), fresh.getPendingDismissals())
        assertTrue(fresh.drainPendingSnoozeBumps().isEmpty())
    }

    @Test
    fun `snooze after a pre-unlock dismiss still counts`() = runTest {
        cache.replaceAll(listOf(alarm(45L)))
        cache.recordPreUnlockSnooze(45L, 1L)
        cache.markDismissed(45L)
        cache.recordPreUnlockSnooze(45L, 2L)

        assertEquals(mapOf(45L to 1), cache.drainPendingSnoozeBumps())
    }

    // Fails before the change: drainPendingSnoozes does not exist (compile error).
    @Test
    fun `drainPendingSnoozes returns bump and trigger from the same locked state and keeps them on write failure`() = runTest {
        cache.replaceAll(listOf(alarm(51L)))
        cache.recordPreUnlockSnooze(51L, 1_800_000_000_000L)
        blockAtomicWrite()
        assertTrue(cache.drainPendingSnoozes().isEmpty())
        File(file.parentFile, "${file.name}.tmp").deleteRecursively()

        assertEquals(
            mapOf(51L to BfuAlarmCache.PendingSnooze(1, 1_800_000_000_000L)),
            cache.drainPendingSnoozes(),
        )
        assertTrue(cache.drainPendingSnoozes().isEmpty())
    }

    // Fails before the change: undoPreUnlockSnooze does not exist (compile error).
    @Test
    fun `undoPreUnlockSnooze restores the trigger and removes only the failed bump`() = runTest {
        cache.replaceAll(listOf(alarm(52L)))
        cache.recordPreUnlockSnooze(52L, 1_000L)
        cache.recordPreUnlockSnooze(52L, 2_000L)

        assertTrue(cache.undoPreUnlockSnooze(52L, 1_000L))
        val fresh = BfuAlarmCache(file)
        assertEquals(1_000L, fresh.getAll().single().snoozedUntilEpoch)
        assertEquals(mapOf(52L to 1), fresh.drainPendingSnoozeBumps())

        cache.recordPreUnlockSnooze(52L, 3_000L)
        assertTrue(cache.undoPreUnlockSnooze(52L, 1_000L))
        assertTrue(cache.undoPreUnlockSnooze(52L, null))
        assertTrue(cache.drainPendingSnoozeBumps().isEmpty())
        assertEquals(null, cache.getAll().single().snoozedUntilEpoch)
    }

    @Test
    fun `dropPendingSnoozeBumps removes only that id in one write`() = runTest {
        cache.replaceAll(listOf(alarm(46L), alarm(47L)))
        cache.recordPreUnlockSnooze(46L, 1L)
        cache.recordPreUnlockSnooze(47L, 1L)

        assertTrue(cache.dropPendingSnoozeBumps(46L))
        assertEquals(mapOf(47L to 1), BfuAlarmCache(file).drainPendingSnoozeBumps())
    }

    // Fails before the change: the drain returns the items and empties memory although the write failed.
    @Test
    fun `drains keep their items when the clear cannot be written`() = runTest {
        cache.replaceAll(listOf(alarm(48L)))
        cache.markDismissed(48L)
        cache.markPendingSnoozeBump(48L)
        blockAtomicWrite()

        assertTrue(cache.drainPendingDismissals().isEmpty())
        assertTrue(cache.drainPendingSnoozeBumps().isEmpty())
        assertEquals(setOf(48L), cache.getPendingDismissals())
        assertEquals(setOf(48L), BfuAlarmCache(file).getPendingDismissals())
        // getAll() folds pending bumps into the count, so 1 proves the bump was
        // kept both in memory and on disk (a drain would need a write, still blocked).
        assertEquals(1, cache.getAll().single { it.id == 48L }.consecutiveSnoozeCount)
        assertEquals(1, BfuAlarmCache(file).getAll().single { it.id == 48L }.consecutiveSnoozeCount)
    }

    // Fails before the change: replaceAll/upsert/remove published memory before the write.
    @Test
    fun `replaceAll upsert and remove publish nothing when the write fails`() = runTest {
        cache.replaceAll(listOf(alarm(49L)))
        blockAtomicWrite()

        assertFalse(cache.replaceAll(emptyList()))
        assertFalse(cache.upsert(alarm(50L)))
        assertFalse(cache.remove(49L))

        assertEquals(listOf(49L), cache.getAll().map { it.id })
        assertEquals(listOf(49L), BfuAlarmCache(file).getAll().map { it.id })
    }

    @Test
    fun `pending dismissal write failure does not publish memory or disk state`() = runTest {
        cache.upsert(Alarm(id = 28L, hour = 6, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L))
        val diskBefore = file.readText(Charsets.UTF_8)
        blockAtomicWrite()

        assertFalse(cache.markDismissed(28L))
        assertTrue(cache.getPendingDismissals().isEmpty())
        assertTrue(BfuAlarmCache(file).getPendingDismissals().isEmpty())
        assertEquals(diskBefore, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `pending snooze bump write failure does not publish count in memory or disk`() = runTest {
        val alarm = Alarm(id = 29L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L, consecutiveSnoozeCount = 2)
        cache.upsert(alarm)
        val diskBefore = file.readText(Charsets.UTF_8)
        blockAtomicWrite()

        assertFalse(cache.markPendingSnoozeBump(alarm.id))
        assertEquals(2, cache.getAll().single().consecutiveSnoozeCount)
        assertTrue(cache.drainPendingSnoozeBumps().isEmpty())
        assertEquals(2, BfuAlarmCache(file).getAll().single().consecutiveSnoozeCount)
        assertEquals(diskBefore, file.readText(Charsets.UTF_8))
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

    private fun blockAtomicWrite() {
        val tempPath = File(file.parentFile, "${file.name}.tmp")
        assertTrue("failed to create blocking temp directory", tempPath.mkdir())
        File(tempPath, "blocker").writeText("keep", Charsets.UTF_8)
    }
}
