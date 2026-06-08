package com.kirkouski.gtwake.companion.data

import app.cash.turbine.test
import com.kirkouski.gtwake.companion.data.bfu.BfuAlarmCache
import com.kirkouski.gtwake.companion.data.db.AlarmDao
import com.kirkouski.gtwake.companion.data.db.AlarmEntity
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import com.kirkouski.gtwake.companion.scheduler.AlarmScheduler
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import com.kirkouski.gtwake.companion.widget.WidgetRefresher
import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
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
    private lateinit var settingsStore: SettingsStore
    private lateinit var bfuCache: BfuAlarmCache
    private lateinit var appContext: Context
    private lateinit var repo: AlarmRepository

    @Before
    fun setUp() {
        dao = FakeAlarmDao()
        scheduler = mockk(relaxed = true)
        wearBridge = mockk(relaxed = true)
        tombstones = mockk(relaxed = true)
        widgetRefresher = mockk(relaxed = true)
        // Default-ringtone settings are queried on save() for new alarms;
        // return an empty SettingsState so the existing tests keep their
        // null-audio-URI semantics. Per-test overrides where needed.
        settingsStore = mockk(relaxed = true)
        coEvery { settingsStore.snapshot() } returns SettingsState()
        // BFU cache: relaxed mock — these unit tests don't assert on cache
        // writes; the cache's correctness is covered separately. Suspending
        // upsert/remove/replaceAll are no-ops here.
        bfuCache = mockk(relaxed = true)
        // Context is only used by rescheduleAllOnBoot() for the missed
        // notification — these unit tests don't exercise that path. A
        // relaxed mock is enough; if a test ever hits it, the NotificationManager
        // call would NPE and that's the signal to switch to a real Robolectric ctx.
        appContext = mockk(relaxed = true)
        // UnconfinedTestDispatcher so the repo's suspending DB/scheduler
        // work runs in-line under runTest. Watch sync is debounced onto the
        // repo's own appScope and is intentionally NOT asserted here.
        repo = AlarmRepository(
            dao = dao,
            scheduler = scheduler,
            wearBridge = wearBridge,
            tombstones = tombstones,
            widgetRefresher = widgetRefresher,
            settingsStore = settingsStore,
            bfuCache = bfuCache,
            appContext = appContext,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `save assigns id, stamps updatedAtEpoch, schedules, refreshes widget`() = runTest {
        val draft = Alarm(id = 0L, label = "Wake", hour = 7, minute = 30, daysOfWeek = DaysOfWeek.NONE, enabled = true)
        val before = System.currentTimeMillis()

        val newId = repo.save(draft)

        assertTrue("save must assign a non-zero id", newId > 0L)
        val persisted = dao.getById(newId)
        assertNotNull(persisted)
        assertTrue("save must stamp updatedAtEpoch", persisted!!.updatedAtEpoch >= before)
        verify { scheduler.schedule(match { it.id == newId && it.enabled }) }
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
    fun `saveLocalOnly persists fields and refreshes widget`() = runTest {
        val draft = Alarm(id = 0L, label = "Local", hour = 7, minute = 0, daysOfWeek = 0, enabled = true)
        val newId = repo.saveLocalOnly(draft)
        assertNotNull(dao.getById(newId))
        coVerify { widgetRefresher.refresh() }
    }

    @Test
    fun `save on existing alarm updates the persisted row`() = runTest {
        // Pre-seed an existing row.
        dao.upsert(AlarmEntity(id = 5L, label = "Old", hour = 7, minute = 0, daysOfWeek = 0, enabled = true,
            audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))
        val edit = Alarm(id = 5L, label = "Updated", hour = 8, minute = 15, daysOfWeek = 0, enabled = true)

        repo.save(edit)

        val persisted = dao.getById(5L)
        assertNotNull(persisted)
        assertEquals("Updated", persisted!!.label)
        assertEquals(8, persisted.hour)
    }

    @Test
    fun `setEnabled flips dao state, schedules, refreshes`() = runTest {
        dao.upsert(AlarmEntity(id = 9L, label = "X", hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = false, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))

        repo.setEnabled(9L, true)

        val after = dao.getById(9L)
        assertTrue("enabled must flip", after!!.enabled)
        assertTrue("setEnabled must re-stamp updatedAtEpoch", after.updatedAtEpoch > 1L)
        verify { scheduler.schedule(match { it.id == 9L && it.enabled }) }
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
    fun `delete cancels scheduler, removes row, writes tombstone with stamp greater than zero`() = runTest {
        dao.upsert(AlarmEntity(id = 7L, label = "Bye", hour = 7, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L))
        val before = System.currentTimeMillis()

        repo.delete(7L)

        assertNull(dao.getById(7L))
        verify { scheduler.cancel(7L) }
        verify { tombstones.add(7L, match { it >= before }, match { it >= before }) }
        coVerify { widgetRefresher.refresh() }
    }

    @Test
    fun `snooze with explicit override schedules at trigger, returns trigger`() = runTest {
        dao.upsert(AlarmEntity(id = 11L, label = "Z", hour = 8, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L,
            snoozeMinutes = 10))
        val before = System.currentTimeMillis()

        val trigger = repo.snooze(11L, minutesOverride = 5)

        assertNotNull(trigger)
        assertTrue("snooze must return a future trigger time", trigger!! >= before + 5 * 60_000L - 100L)
        verify { scheduler.scheduleAt(match { it.id == 11L }, eq(trigger)) }
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
    fun `snooze on missing alarm returns null and does not schedule`() = runTest {
        val trigger = repo.snooze(999L)
        assertNull(trigger)
        verify(exactly = 0) { scheduler.scheduleAt(any(), any()) }
    }

    @Test
    fun `snooze without override on snoozeMinutes=0 alarm returns null and does not schedule`() = runTest {
        // Regression test: a 0-minute "snooze" would compute trigger = now + 0
        // and immediately re-fire the alarm. The guard rejects the call.
        dao.upsert(AlarmEntity(id = 13L, label = "Off", hour = 8, minute = 0, daysOfWeek = 0,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L,
            snoozeMinutes = 0))

        val trigger = repo.snooze(13L)

        assertNull(trigger)
        verify(exactly = 0) { scheduler.scheduleAt(any(), any()) }
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
    fun `save applies default-absolute ringtone from settings for new alarm with no URI`() = runTest {
        coEvery { settingsStore.snapshot() } returns SettingsState(
            defaultAbsoluteRingtoneUri = "content://abs-default",
            defaultAbsoluteRingtoneName = "Cabin",
        )
        val draft = Alarm(id = 0L, label = "Wake", hour = 7, minute = 0, daysOfWeek = 0, enabled = true)

        val newId = repo.save(draft)

        val persisted = dao.getById(newId)
        assertEquals("content://abs-default", persisted!!.audioUri)
        assertEquals("Cabin", persisted.audioName)
    }

    @Test
    fun `save uses default-relative ringtone for new relative alarm`() = runTest {
        coEvery { settingsStore.snapshot() } returns SettingsState(
            defaultRelativeRingtoneUri = "content://rel-default",
            defaultRelativeRingtoneName = "Chime",
        )
        val draft = Alarm(
            id = 0L, hour = 0, minute = 0, daysOfWeek = 0, enabled = true,
            relativeMinutes = 10,
        )

        val newId = repo.save(draft)

        val persisted = dao.getById(newId)
        assertEquals("content://rel-default", persisted!!.audioUri)
    }

    @Test
    fun `save respects explicit audio URI over settings default`() = runTest {
        coEvery { settingsStore.snapshot() } returns SettingsState(
            defaultAbsoluteRingtoneUri = "content://settings-default",
        )
        val draft = Alarm(
            id = 0L, label = "Explicit", hour = 7, minute = 0, daysOfWeek = 0, enabled = true,
            audioUri = "content://user-chose-this",
        )

        val newId = repo.save(draft)

        assertEquals("content://user-chose-this", dao.getById(newId)!!.audioUri)
    }

    @Test
    fun `save on existing alarm does not pull settings default`() = runTest {
        // Edit on an existing row keeps its own audio choice (including null = system default).
        coEvery { settingsStore.snapshot() } returns SettingsState(
            defaultAbsoluteRingtoneUri = "content://settings-default",
        )
        dao.upsert(
            AlarmEntity(
                id = 3L, label = "Old", hour = 7, minute = 0, daysOfWeek = 0, enabled = true,
                audioUri = null, audioName = null, isVibrationOnly = false, updatedAtEpoch = 1L,
            ),
        )

        repo.save(Alarm(id = 3L, label = "Renamed", hour = 7, minute = 0, daysOfWeek = 0, enabled = true))

        assertNull(dao.getById(3L)!!.audioUri)
    }

    @Test
    fun `rescheduleFromBfu re-arms only recurring alarms, skipping one-shots and relatives`() = runTest {
        // Phase A v1 scope cut: BFU re-arm path skips one-shots because pre-
        // unlock dismiss can't persist to Room. Recurring alarms have a
        // well-defined next-occurrence so they re-arm cleanly.
        val recurring = Alarm(
            id = 1L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L,
        )
        val oneShot = Alarm(
            id = 2L, hour = 8, minute = 0, daysOfWeek = 0,
            enabled = true, updatedAtEpoch = 1L,
        )
        val relative = Alarm(
            id = 3L, hour = 0, minute = 0, daysOfWeek = 0, enabled = true,
            updatedAtEpoch = 1L, relativeMinutes = 5,
        )
        io.mockk.every { bfuCache.getAll() } returns listOf(recurring, oneShot, relative)

        repo.rescheduleFromBfu()

        verify { scheduler.rescheduleAll(match { it.size == 1 && it[0].id == 1L }) }
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

    @Test
    fun `snooze fromPeer=false (local) bumps consecutiveSnoozeCount`() = runTest {
        dao.upsert(AlarmEntity(
            id = 1L, label = "x", hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false,
            snoozeMinutes = 5, updatedAtEpoch = 1L, consecutiveSnoozeCount = 1,
        ))

        repo.snoozeAt(1L, System.currentTimeMillis() + 60_000L, fromPeer = false)

        assertEquals(2, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    @Test
    fun `snoozeAt fromPeer=true ALSO bumps consecutiveSnoozeCount`() = runTest {
        dao.upsert(AlarmEntity(
            id = 1L, label = "x", hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false,
            snoozeMinutes = 5, updatedAtEpoch = 1L, consecutiveSnoozeCount = 1,
        ))

        repo.snoozeAt(1L, System.currentTimeMillis() + 60_000L, fromPeer = true)

        // Spec line 499: counter increments on EVERY snooze, local OR
        // peer-driven. Earlier logic skipped the bump on peer snoozes
        // ("watch is authoritative"), but that broke the cap when the
        // user kept tapping Snooze on the watch — the cap never tripped
        // because the phone-side counter never advanced. Now uniform.
        assertEquals(2, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    @Test
    fun `resetSnoozeCounter clears the counter`() = runTest {
        dao.upsert(AlarmEntity(
            id = 1L, label = "x", hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false,
            updatedAtEpoch = 1L, consecutiveSnoozeCount = 4,
        ))

        repo.resetSnoozeCounter(1L)

        assertEquals(0, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    @Test
    fun `consumePastSkip clears skipNextEpoch when skip is in the past`() = runTest {
        val past = System.currentTimeMillis() - 60_000L
        dao.upsert(AlarmEntity(
            id = 1L, label = "x", hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false,
            updatedAtEpoch = 1L, skipNextEpoch = past,
        ))

        repo.consumePastSkip(1L)

        assertNull("past skip must be cleared", dao.getById(1L)?.skipNextEpoch)
    }

    @Test
    fun `consumePastSkip is no-op when skip is still in the future`() = runTest {
        val future = System.currentTimeMillis() + 60 * 60_000L
        dao.upsert(AlarmEntity(
            id = 1L, label = "x", hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false,
            updatedAtEpoch = 1L, skipNextEpoch = future,
        ))

        repo.consumePastSkip(1L)

        assertEquals(
            "future skip must be preserved",
            future,
            dao.getById(1L)?.skipNextEpoch,
        )
    }

    @Test
    fun `skipNext clears an active snoozedUntilEpoch before computing skip`() = runTest {
        // A user who skipped-next while the alarm was snoozed used to set
        // skipNextEpoch = snoozedUntilEpoch (calculator returns the snooze
        // override). Now we clear the snooze first, then compute the next
        // CLOCK occurrence as the skip target.
        val now = System.currentTimeMillis()
        dao.upsert(AlarmEntity(
            id = 1L, label = "x",
            hour = 7, minute = 0,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false,
            updatedAtEpoch = 1L,
            snoozedUntilEpoch = now + 60_000L,
        ))

        val skipped = repo.skipNext(1L)

        val row = dao.getById(1L)!!
        assertNull("snooze override must be cleared by skipNext", row.snoozedUntilEpoch)
        assertNotNull(skipped)
        // The skip target is the clock-time next-recurrence, not the now+60s
        // snooze override.
        assertTrue("skip target must be strictly after the cleared snooze", (skipped ?: 0L) > now + 60_000L)
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

    override suspend fun setConsecutiveSnoozeCount(id: Long, count: Int) {
        rows.update { map ->
            map[id]?.let { map + (id to it.copy(consecutiveSnoozeCount = count)) } ?: map
        }
    }
}
