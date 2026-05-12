package com.kirkouski.gtalarm.data

import app.cash.turbine.test
import com.kirkouski.gtalarm.data.db.AlarmDao
import com.kirkouski.gtalarm.data.db.AlarmEntity
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import com.kirkouski.gtalarm.scheduler.AlarmScheduler
import com.kirkouski.gtalarm.wear.WearBridgeService
import com.kirkouski.gtalarm.widget.WidgetRefresher
import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmRepositoryTest {

    private lateinit var dao: FakeAlarmDao
    private lateinit var scheduler: AlarmScheduler
    private lateinit var wearBridge: WearBridgeService
    private lateinit var tombstones: Tombstones
    private lateinit var widgetRefresher: WidgetRefresher
    private lateinit var appContext: Context
    private lateinit var repo: AlarmRepository

    @Before
    fun setUp() {
        dao = FakeAlarmDao()
        scheduler = mockk(relaxed = true)
        wearBridge = mockk(relaxed = true)
        tombstones = mockk(relaxed = true)
        widgetRefresher = mockk(relaxed = true)
        // Context is only used by rescheduleAllOnBoot() for the missed
        // notification — these unit tests don't exercise that path. A
        // relaxed mock is enough; if a test ever hits it, the NotificationManager
        // call would NPE and that's the signal to switch to a real Robolectric ctx.
        appContext = mockk(relaxed = true)
        // UnconfinedTestDispatcher so the repo's internal appScope.launch
        // (pushAlarmToWatch) runs in-line under runTest, letting verify()
        // calls observe the broadcast synchronously.
        repo = AlarmRepository(
            dao = dao,
            scheduler = scheduler,
            wearBridge = wearBridge,
            tombstones = tombstones,
            widgetRefresher = widgetRefresher,
            appContext = appContext,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `save assigns id, stamps updatedAtEpoch, schedules, broadcasts add, refreshes widget`() = runTest {
        val draft = Alarm(id = 0L, label = "Wake", hour = 7, minute = 30, daysOfWeek = DaysOfWeek.NONE, enabled = true)
        val before = System.currentTimeMillis()

        val newId = repo.save(draft)

        assertTrue("save must assign a non-zero id", newId > 0L)
        val persisted = dao.getById(newId)
        assertNotNull(persisted)
        assertTrue("save must stamp updatedAtEpoch", persisted!!.updatedAtEpoch >= before)
        verify { scheduler.schedule(match { it.id == newId && it.enabled }) }
        verify { wearBridge.sendAlarmAdded(match { it.id == newId }) }
        coVerify { widgetRefresher.refresh() }
    }

    @Test
    fun `save on disabled alarm cancels scheduler instead of scheduling`() = runTest {
        val draft = Alarm(id = 0L, hour = 6, minute = 0, daysOfWeek = DaysOfWeek.MON, enabled = false)
        val newId = repo.save(draft)
        verify { scheduler.cancel(newId) }
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test
    fun `save preserves relativeMinutes and selfDestruct in persisted row`() = runTest {
        val draft = Alarm(
            id = 0L, label = "Timer", hour = 0, minute = 0, daysOfWeek = 0, enabled = true,
            relativeMinutes = 30, selfDestruct = true,
        )
        val newId = repo.save(draft)
        val persisted = dao.getById(newId)
        assertNotNull(persisted)
        assertEquals(30, persisted!!.relativeMinutes)
        assertTrue("save must preserve selfDestruct=true on one-shot timer", persisted.selfDestruct)
    }

    @Test
    fun `saveLocalOnly persists fields but does NOT broadcast to watch`() = runTest {
        val draft = Alarm(id = 0L, label = "Local", hour = 7, minute = 0, daysOfWeek = 0, enabled = true)
        val newId = repo.saveLocalOnly(draft)
        assertNotNull(dao.getById(newId))
        verify(exactly = 0) { wearBridge.sendAlarmAdded(any()) }
        verify(exactly = 0) { wearBridge.sendAlarmUpdated(any()) }
        coVerify { widgetRefresher.refresh() }
    }

    @Test
    fun `pushAlarmToWatch broadcasts the current DAO state via sendAlarmUpdated`() = runTest {
        // Pre-load a row via the local-only path so the broadcast path has
        // something to pick up.
        val newId = repo.saveLocalOnly(Alarm(label = "x", hour = 0, minute = 0, daysOfWeek = 0, enabled = true))
        repo.pushAlarmToWatch(newId)
        // UnconfinedTestDispatcher runs the appScope.launch in-line so the
        // verify can observe the broadcast immediately.
        verify { wearBridge.sendAlarmUpdated(match { it.id == newId }) }
    }

    @Test
    fun `pushAlarmToWatch for a missing id silently no-ops`() = runTest {
        // Edit-screen race: user edited an alarm, then deleted it on the
        // list before the flush ran. The push should drop instead of
        // crashing or broadcasting a stale snapshot.
        repo.pushAlarmToWatch(99_999L)
        verify(exactly = 0) { wearBridge.sendAlarmUpdated(any()) }
    }

    @Test
    fun `deleteLocalOnly removes row without writing tombstone or broadcasting`() = runTest {
        val newId = repo.saveLocalOnly(Alarm(label = "draft", hour = 0, minute = 0, daysOfWeek = 0, enabled = true))
        repo.deleteLocalOnly(newId)
        assertNull(dao.getById(newId))
        verify(exactly = 0) { wearBridge.sendAlarmDeleted(any(), any()) }
        // No tombstone — the alarm was never broadcast to begin with.
        coVerify(exactly = 0) { tombstones.add(any(), any(), any()) }
    }

    @Test
    fun `save on existing alarm broadcasts updated, not added`() = runTest {
        // Pre-seed an existing row.
        dao.upsert(AlarmEntity(id = 5L, label = "Old", hour = 7, minute = 0, daysOfWeek = 0, enabled = true,
            audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))
        val edit = Alarm(id = 5L, label = "Updated", hour = 8, minute = 15, daysOfWeek = 0, enabled = true)

        repo.save(edit)

        verify { wearBridge.sendAlarmUpdated(match { it.id == 5L && it.label == "Updated" }) }
        verify(exactly = 0) { wearBridge.sendAlarmAdded(any()) }
    }

    @Test
    fun `setEnabled flips dao state, schedules, broadcasts toggled, refreshes`() = runTest {
        dao.upsert(AlarmEntity(id = 9L, label = "X", hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = false, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))

        repo.setEnabled(9L, true)

        val after = dao.getById(9L)
        assertTrue("enabled must flip", after!!.enabled)
        assertTrue("setEnabled must re-stamp updatedAtEpoch", after.updatedAtEpoch > 1L)
        verify { scheduler.schedule(match { it.id == 9L && it.enabled }) }
        verify { wearBridge.sendAlarmToggled(match { it.id == 9L && it.enabled }) }
        coVerify { widgetRefresher.refresh() }
    }

    @Test
    fun `setEnabled false cancels scheduler`() = runTest {
        dao.upsert(AlarmEntity(id = 9L, label = "X", hour = 7, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))

        repo.setEnabled(9L, false)

        verify { scheduler.cancel(9L) }
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test
    fun `delete cancels scheduler, removes row, writes tombstone with stamp greater than zero, broadcasts deleted`() = runTest {
        dao.upsert(AlarmEntity(id = 7L, label = "Bye", hour = 7, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))
        val before = System.currentTimeMillis()

        repo.delete(7L)

        assertNull(dao.getById(7L))
        verify { scheduler.cancel(7L) }
        verify { tombstones.add(7L, match { it >= before }, match { it >= before }) }
        verify { wearBridge.sendAlarmDeleted(7L, match { it >= before }) }
        coVerify { widgetRefresher.refresh() }
    }

    @Test
    fun `snooze with explicit override schedules at trigger, broadcasts snoozed, returns trigger`() = runTest {
        dao.upsert(AlarmEntity(id = 11L, label = "Z", hour = 8, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L,
            snoozeMinutes = 10))
        val before = System.currentTimeMillis()

        val trigger = repo.snooze(11L, minutesOverride = 5)

        assertNotNull(trigger)
        assertTrue("snooze must return a future trigger time", trigger!! >= before + 5 * 60_000L - 100L)
        verify { scheduler.scheduleAt(match { it.id == 11L }, eq(trigger)) }
        verify { wearBridge.sendAlarmSnoozed(11L, eq(trigger)) }
    }

    @Test
    fun `snooze without override reads the per-alarm snoozeMinutes`() = runTest {
        dao.upsert(AlarmEntity(id = 12L, label = "Custom", hour = 8, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L,
            snoozeMinutes = 7))
        val before = System.currentTimeMillis()

        val trigger = repo.snooze(12L)

        assertNotNull(trigger)
        assertTrue(
            "snooze must honor alarm's own 7-min duration",
            trigger!! >= before + 7 * 60_000L - 100L && trigger <= before + 7 * 60_000L + 1_000L,
        )
        verify { scheduler.scheduleAt(match { it.id == 12L }, eq(trigger)) }
    }

    @Test
    fun `snooze on missing alarm returns null and does not broadcast`() = runTest {
        val trigger = repo.snooze(999L)
        assertNull(trigger)
        verify(exactly = 0) { scheduler.scheduleAt(any(), any()) }
        verify(exactly = 0) { wearBridge.sendAlarmSnoozed(any(), any()) }
    }

    @Test
    fun `observeAlarms emits mapped domain objects`() = runTest {
        dao.upsert(AlarmEntity(id = 1L, label = "A", hour = 7, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 5L))

        repo.observeAlarms().test {
            val first = awaitItem()
            assertEquals(1, first.size)
            assertEquals(1L, first[0].id)
            assertEquals("A", first[0].label)
            assertEquals(5L, first[0].updatedAtEpoch)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rescheduleAll reschedules only enabled alarms`() = runTest {
        dao.upsert(AlarmEntity(id = 1L, label = "On", hour = 7, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))
        dao.upsert(AlarmEntity(id = 2L, label = "Off", hour = 8, minute = 0, daysOfWeek = 0,
            enabled = false, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))

        repo.rescheduleAll()

        verify { scheduler.rescheduleAll(match { list -> list.size == 1 && list[0].id == 1L }) }
    }
}

// In-memory fake DAO that exposes the same surface AlarmDao does. Pure Kotlin,
// no Android Context needed — keeps AlarmRepositoryTest a fast JVM unit test.
private class FakeAlarmDao : AlarmDao {
    private val rows = MutableStateFlow<Map<Long, AlarmEntity>>(emptyMap())
    private var nextId = 1L

    override fun observeAll(): Flow<List<AlarmEntity>> =
        rows.map { map -> map.values.sortedBy { it.id } }

    override suspend fun getAll(): List<AlarmEntity> = rows.value.values.toList()

    override suspend fun getById(id: Long): AlarmEntity? = rows.value[id]

    override suspend fun upsert(entity: AlarmEntity): Long {
        val id = if (entity.id == 0L) nextId++ else entity.id.also { if (it >= nextId) nextId = it + 1 }
        val toStore = if (entity.id == 0L) entity.copy(id = id) else entity
        rows.update { it + (id to toStore) }
        return id
    }

    override suspend fun delete(entity: AlarmEntity) {
        rows.update { it - entity.id }
    }

    override suspend fun deleteById(id: Long) {
        rows.update { it - id }
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        rows.update { map -> map[id]?.let { map + (id to it.copy(enabled = enabled)) } ?: map }
    }

    override suspend fun setEnabledStamped(id: Long, enabled: Boolean, stamp: Long) {
        rows.update { map ->
            map[id]?.let { map + (id to it.copy(enabled = enabled, updatedAtEpoch = stamp)) } ?: map
        }
    }

    override suspend fun setSnoozedUntil(id: Long, until: Long?) {
        rows.update { map ->
            map[id]?.let { map + (id to it.copy(snoozedUntilEpoch = until)) } ?: map
        }
    }
}
