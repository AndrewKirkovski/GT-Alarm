package com.kirkouski.gtalarm.data.sync

import android.content.Context
import com.kirkouski.gtalarm.data.Tombstones
import com.kirkouski.gtalarm.data.db.AlarmDao
import com.kirkouski.gtalarm.data.db.AlarmEntity
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import com.kirkouski.gtalarm.scheduler.AlarmScheduler
import com.kirkouski.gtalarm.widget.WidgetRefresher
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IncomingMessageHandlerTest {

    private lateinit var context: Context
    private lateinit var dao: FakeAlarmDao
    private lateinit var tombstones: Tombstones
    private lateinit var scheduler: AlarmScheduler
    private lateinit var widgetRefresher: WidgetRefresher
    private lateinit var handler: IncomingMessageHandler

    @Before
    fun setUp() {
        context = mockk(relaxed = true) {
            every { startService(any()) } returns null
        }
        dao = FakeAlarmDao()
        tombstones = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        widgetRefresher = mockk(relaxed = true)
        // Catchall for the 3-arg signature including the default `now` param.
        // MockK does NOT auto-fill matchers for params with default values, so
        // tests overriding this MUST also pass an explicit `any()` for the 3rd
        // arg (see the sticky-tombstone tie-break test).
        every { tombstones.isTombstoned(any(), any(), any()) } returns false
        handler = IncomingMessageHandler(context, dao, tombstones, scheduler, widgetRefresher)
    }

    private fun makeAlarm(
        id: Long = 0L,
        enabled: Boolean = true,
        epoch: Long = 1_000L,
        days: Int = DaysOfWeek.NONE,
    ) = Alarm(id = id, label = "x", hour = 7, minute = 0, daysOfWeek = days, enabled = enabled, updatedAtEpoch = epoch)

    // --- Add / Update ---

    @Test
    fun `AlarmAdded with no local row inserts and schedules`() = runTest {
        val incoming = makeAlarm(id = 5L, epoch = 100L)
        handler.handle(IncomingMessage.AlarmAdded(alarmId = 5L, updatedAtEpoch = 100L, alarm = incoming))

        val row = dao.getById(5L)
        assertNotNull(row)
        assertEquals(100L, row!!.updatedAtEpoch)
        verify { scheduler.schedule(match { it.id == 5L && it.enabled }) }
        coVerify { widgetRefresher.refresh() }
    }

    @Test
    fun `AlarmAdded for tombstoned id is suppressed (no DAO write)`() = runTest {
        every { tombstones.isTombstoned(eq(5L), any(), any()) } returns true
        val incoming = makeAlarm(id = 5L, epoch = 100L)

        handler.handle(IncomingMessage.AlarmAdded(5L, 100L, incoming))

        assertNull(dao.getById(5L))
        verify(exactly = 0) { scheduler.schedule(any()) }
        coVerify(exactly = 0) { widgetRefresher.refresh() }
    }

    @Test
    fun `AlarmAdded with epoch equal to tombstone epoch is suppressed (sticky-tombstone tie-break)`() = runTest {
        // §2 carve-out: tombstone wins on tie. Tombstones.isTombstoned uses >=
        // so when the test tombstone returns true for the boundary, the handler
        // must suppress even though the live-row LwwResolver would have applied.
        // Explicit `any()` for the default `now` param — MockK does NOT auto-fill
        // matchers for params with default values; without this the stub
        // narrows to `now == default-evaluated-at-stub-time` which never matches
        // the call-site `System.currentTimeMillis()`.
        every { tombstones.isTombstoned(eq(5L), eq(100L), any()) } returns true
        val incoming = makeAlarm(id = 5L, epoch = 100L)

        handler.handle(IncomingMessage.AlarmAdded(5L, 100L, incoming))

        assertNull(dao.getById(5L))
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test
    fun `handler rejects malformed messages with non-positive updatedAtEpoch`() = runTest {
        val incoming = makeAlarm(id = 5L, epoch = 100L)
        handler.handle(IncomingMessage.AlarmAdded(5L, 0L, incoming))
        handler.handle(IncomingMessage.AlarmAdded(5L, -1L, incoming))
        handler.handle(IncomingMessage.AlarmDeleted(7L, 0L))

        assertNull(dao.getById(5L))
        verify(exactly = 0) { tombstones.add(any(), any(), any()) }
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test
    fun `AlarmUpdated with newer stamp overwrites local row`() = runTest {
        dao.upsert(AlarmEntity(id = 7L, label = "old", hour = 6, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 50L))
        val incoming = makeAlarm(id = 7L, epoch = 200L).copy(label = "new")

        handler.handle(IncomingMessage.AlarmUpdated(7L, 200L, incoming))

        val row = dao.getById(7L)
        assertEquals("new", row!!.label)
        assertEquals(200L, row.updatedAtEpoch)
        verify { scheduler.schedule(match { it.id == 7L }) }
    }

    @Test
    fun `AlarmUpdated with older stamp is ignored (DAO untouched)`() = runTest {
        dao.upsert(AlarmEntity(id = 7L, label = "keep", hour = 6, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 200L))
        val incoming = makeAlarm(id = 7L, epoch = 100L).copy(label = "stale")

        handler.handle(IncomingMessage.AlarmUpdated(7L, 100L, incoming))

        val row = dao.getById(7L)
        assertEquals("keep", row!!.label)
        assertEquals(200L, row.updatedAtEpoch)
        verify(exactly = 0) { scheduler.schedule(any()) }
        coVerify(exactly = 0) { widgetRefresher.refresh() }
    }

    @Test
    fun `AlarmUpdated with equal stamp applies (sync-architecture incoming-wins tie-break)`() = runTest {
        dao.upsert(AlarmEntity(id = 7L, label = "old", hour = 6, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 100L))
        val incoming = makeAlarm(id = 7L, epoch = 100L).copy(label = "new")

        handler.handle(IncomingMessage.AlarmUpdated(7L, 100L, incoming))

        assertEquals("new", dao.getById(7L)!!.label)
    }

    @Test
    fun `AlarmAdded with disabled alarm cancels scheduler instead of scheduling`() = runTest {
        val incoming = makeAlarm(id = 5L, enabled = false, epoch = 100L)
        handler.handle(IncomingMessage.AlarmAdded(5L, 100L, incoming))

        verify { scheduler.cancel(5L) }
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    // --- Delete ---

    @Test
    fun `AlarmDeleted for existing row writes tombstone, removes row, cancels scheduler`() = runTest {
        dao.upsert(AlarmEntity(id = 8L, label = "bye", hour = 7, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 50L))

        handler.handle(IncomingMessage.AlarmDeleted(8L, 200L))

        assertNull(dao.getById(8L))
        verify { tombstones.add(eq(8L), eq(200L), any()) }
        verify { scheduler.cancel(8L) }
        coVerify { widgetRefresher.refresh() }
    }

    @Test
    fun `AlarmDeleted for unknown id records tombstone only (no DAO change)`() = runTest {
        handler.handle(IncomingMessage.AlarmDeleted(99L, 200L))

        verify { tombstones.add(eq(99L), eq(200L), any()) }
        verify(exactly = 0) { scheduler.cancel(any()) }
        coVerify(exactly = 0) { widgetRefresher.refresh() }
    }

    @Test
    fun `AlarmDeleted with older stamp than local still records tombstone but does not delete row`() = runTest {
        dao.upsert(AlarmEntity(id = 8L, label = "alive", hour = 7, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 500L))

        handler.handle(IncomingMessage.AlarmDeleted(8L, 100L))

        // Tombstone is sticky — always recorded so the LWW tie-break in §2 carve-out works.
        verify { tombstones.add(eq(8L), eq(100L), any()) }
        // But the row stays since the local stamp is newer.
        assertNotNull(dao.getById(8L))
        verify(exactly = 0) { scheduler.cancel(any()) }
    }

    // --- Toggle ---

    @Test
    fun `AlarmToggled flips enabled and re-stamps with incoming epoch`() = runTest {
        dao.upsert(AlarmEntity(id = 9L, label = "x", hour = 7, minute = 0, daysOfWeek = 0,
            enabled = false, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 50L))

        handler.handle(IncomingMessage.AlarmToggled(9L, enabled = true, updatedAtEpoch = 200L))

        val row = dao.getById(9L)
        assertTrue(row!!.enabled)
        assertEquals(200L, row.updatedAtEpoch)
        verify { scheduler.schedule(match { it.id == 9L && it.enabled }) }
    }

    @Test
    fun `AlarmToggled with older stamp is ignored`() = runTest {
        dao.upsert(AlarmEntity(id = 9L, label = "x", hour = 7, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 200L))

        handler.handle(IncomingMessage.AlarmToggled(9L, enabled = false, updatedAtEpoch = 100L))

        val row = dao.getById(9L)
        assertTrue("enabled must stay true", row!!.enabled)
        assertEquals(200L, row.updatedAtEpoch)
    }

    @Test
    fun `AlarmToggled for unknown id is a no-op (waits for matching add)`() = runTest {
        handler.handle(IncomingMessage.AlarmToggled(99L, enabled = true, updatedAtEpoch = 200L))

        assertNull(dao.getById(99L))
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    // --- Snooze / Dismiss / Fired ---

    @Test
    fun `AlarmSnoozed dispatches a peer snooze intent for the live ring service`() = runTest {
        dao.upsert(AlarmEntity(id = 11L, label = "z", hour = 8, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))

        handler.handle(IncomingMessage.AlarmSnoozed(11L, updatedAtEpoch = 200L))

        verify { context.startService(any()) }
    }

    @Test
    fun `AlarmSnoozed for unknown id is a no-op (no intent, no scheduler call)`() = runTest {
        handler.handle(IncomingMessage.AlarmSnoozed(999L, 200L))

        verify(exactly = 0) { context.startService(any()) }
        verify(exactly = 0) { scheduler.scheduleAt(any(), any()) }
    }

    @Test
    fun `AlarmFired is non-mutating (informational only)`() = runTest {
        dao.upsert(AlarmEntity(id = 4L, label = "ring", hour = 7, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))

        handler.handle(IncomingMessage.AlarmFired(4L, 200L))

        val row = dao.getById(4L)
        assertEquals(1L, row!!.updatedAtEpoch)
        assertTrue(row.enabled)
        verify(exactly = 0) { scheduler.schedule(any()) }
        verify(exactly = 0) { scheduler.scheduleAt(any(), any()) }
        verify(exactly = 0) { scheduler.cancel(any()) }
        coVerify(exactly = 0) { widgetRefresher.refresh() }
    }

    @Test
    fun `AlarmDismissed dispatches a dismiss intent to AlarmRingService`() = runTest {
        handler.handle(IncomingMessage.AlarmDismissed(4L, 200L))

        verify { context.startService(any()) }
    }

    // --- New field coverage: relativeMinutes + selfDestruct ---

    @Test
    fun `AlarmAdded preserves relativeMinutes and selfDestruct in DAO row`() = runTest {
        val incoming = Alarm(
            id = 10L,
            label = "Timer",
            hour = 0,
            minute = 0,
            daysOfWeek = 0,
            enabled = true,
            updatedAtEpoch = 500L,
            relativeMinutes = 25,
            selfDestruct = true,
        )
        handler.handle(IncomingMessage.AlarmAdded(10L, 500L, incoming))

        val row = dao.getById(10L)
        assertNotNull(row)
        assertEquals(25, row!!.relativeMinutes)
        assertTrue(row.selfDestruct)
    }

    @Test
    fun `AlarmUpdated preserves new fields when overwriting older local row`() = runTest {
        // Local row has the new fields at one value pair; incoming with a
        // newer stamp flips both. The handler must persist the new pair —
        // a bug that dropped them on the read-modify-write path would leave
        // the row in a stale mixed state.
        dao.upsert(AlarmEntity(
            id = 11L, label = "x", hour = 0, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false,
            updatedAtEpoch = 100L, snoozeMinutes = 10,
            relativeMinutes = 5, selfDestruct = false,
        ))
        val incoming = Alarm(
            id = 11L, label = "x", hour = 0, minute = 0, daysOfWeek = 0,
            enabled = true, updatedAtEpoch = 200L,
            relativeMinutes = 60, selfDestruct = true,
        )
        handler.handle(IncomingMessage.AlarmUpdated(11L, 200L, incoming))

        val row = dao.getById(11L)
        assertNotNull(row)
        assertEquals(60, row!!.relativeMinutes)
        assertTrue(row.selfDestruct)
    }

    @Test
    fun `AlarmAdded with relativeMinutes null persists as null (absolute alarm)`() = runTest {
        // Symmetry with the non-null case: an absolute alarm coming from
        // the peer must stay absolute. If the mapper coerced null to 0
        // accidentally, the row would render as a 0-minute timer on the list.
        val incoming = Alarm(
            id = 12L, label = "Wake", hour = 8, minute = 0,
            daysOfWeek = DaysOfWeek.WEEKDAYS, enabled = true,
            updatedAtEpoch = 300L,
            relativeMinutes = null, selfDestruct = false,
        )
        handler.handle(IncomingMessage.AlarmAdded(12L, 300L, incoming))

        val row = dao.getById(12L)
        assertNotNull(row)
        assertNull(row!!.relativeMinutes)
        assertEquals(false, row.selfDestruct)
    }
}

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
}
