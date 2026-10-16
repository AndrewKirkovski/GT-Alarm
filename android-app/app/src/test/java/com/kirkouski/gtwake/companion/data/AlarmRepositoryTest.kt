package com.kirkouski.gtwake.companion.data

import app.cash.turbine.test
import com.kirkouski.gtwake.companion.data.bfu.BfuAlarmCache
import com.kirkouski.gtwake.companion.data.db.AlarmDao
import com.kirkouski.gtwake.companion.data.db.AlarmEntity
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import com.kirkouski.gtwake.companion.ring.AlarmNotifications
import com.kirkouski.gtwake.companion.scheduler.AlarmScheduler
import com.kirkouski.gtwake.companion.wear.ForceSyncResult
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import com.kirkouski.gtwake.companion.widget.WidgetRefresher
import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

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
        // Relaxed mocks return false for Boolean; the real scheduler arms by default.
        every { scheduler.scheduleAtChecked(any(), any()) } returns true
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

    // --- Opt-in watch list sync (2026-09-28) -------------------------------

    @Test
    fun `syncWatchNow with list sync off sends nothing to the watch`() = runTest {
        coEvery { settingsStore.snapshot() } returns SettingsState(watchListSync = false)

        val result = repo.syncWatchNow()

        assertEquals(ForceSyncResult.ListSyncOff, result)
        // sendSettingsChanged wakes the watch on its own, so it must be gated too.
        verify(exactly = 0) { wearBridge.sendSettingsChanged(any(), any()) }
        coVerify(exactly = 0) { wearBridge.forceSync(any()) }
    }

    @Test
    fun `syncWatchNow with list sync on pushes settings and the list`() = runTest {
        coEvery { settingsStore.snapshot() } returns SettingsState(watchListSync = true, watchListSyncUnlocked = true)
        coEvery { wearBridge.forceSync(any()) } returns ForceSyncResult.Ok(0)

        val result = repo.syncWatchNow()

        assertEquals(ForceSyncResult.Ok(0), result)
        verify(exactly = 1) { wearBridge.sendSettingsChanged(any(), any()) }
        coVerify(exactly = 1) { wearBridge.forceSync(any()) }
    }

    @Test
    fun `locked legacy enabled preference cannot send or clear the watch list`() = runTest {
        coEvery { settingsStore.snapshot() } returns SettingsState(watchListSync = true)

        assertEquals(ForceSyncResult.ListSyncOff, repo.syncWatchNow())
        assertEquals(ForceSyncResult.ListSyncOff, repo.setWatchListSync(false))

        verify(exactly = 0) { wearBridge.sendSettingsChanged(any(), any()) }
        coVerify(exactly = 0) { wearBridge.forceSync(any()) }
        coVerify(exactly = 0) { settingsStore.setWatchListSync(any()) }
    }

    @Test
    fun `turning list sync off stores it and pushes an empty list`() = runTest {
        coEvery { settingsStore.snapshot() } returns SettingsState(watchListSync = false, watchListSyncUnlocked = true)
        val pushed = mutableListOf<List<Alarm>>()
        coEvery { wearBridge.forceSync(any()) } coAnswers {
            val supplier = firstArg<suspend () -> List<Alarm>>()
            pushed += supplier()
            ForceSyncResult.Ok(0)
        }
        repo.save(Alarm(id = 0L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.MON, enabled = true))

        repo.setWatchListSync(false)

        coVerify { settingsStore.setWatchListSync(false) }
        assertEquals(listOf(emptyList<Alarm>()), pushed)
    }

    @Test
    fun `turning list sync on stores it and pushes the real list`() = runTest {
        coEvery { settingsStore.snapshot() } returns SettingsState(watchListSync = true, watchListSyncUnlocked = true)
        val pushed = mutableListOf<List<Alarm>>()
        coEvery { wearBridge.forceSync(any()) } coAnswers {
            val supplier = firstArg<suspend () -> List<Alarm>>()
            pushed += supplier()
            ForceSyncResult.Ok(1)
        }
        repo.save(Alarm(id = 0L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.MON, enabled = true))

        repo.setWatchListSync(true)

        coVerify { settingsStore.setWatchListSync(true) }
        assertEquals(1, pushed.single().size)
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
    fun `rescheduleFromBfu leaves one-shots whose time passed before boot unarmed`() = runTest {
        // Both one-shots were due in 1970 (updatedAtEpoch = 1), long before
        // this boot: the unlock reconcile reports them as missed instead.
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
        verify(exactly = 0) { scheduler.scheduleAt(any(), any()) }
    }

    // A one-shot set last night for a time after this boot: the phone restarted
    // and is still locked when it is due. Fails before the change: one-shots
    // were never armed before the first unlock, so this alarm did not ring.
    private fun oneShotDueIn(id: Long, hours: Long): Alarm {
        val due = java.time.ZonedDateTime.now().plusHours(hours)
        return Alarm(
            id = id, hour = due.hour, minute = due.minute, daysOfWeek = 0,
            enabled = true, updatedAtEpoch = System.currentTimeMillis() - 3_600_000L,
        )
    }

    private fun expectedFire(alarm: Alarm): Long = com.kirkouski.gtwake.companion.domain.NextTriggerCalculator
        .nextTriggerEpochMillis(
            alarm,
            java.time.Instant.ofEpochMilli(alarm.updatedAtEpoch).atZone(java.time.ZoneId.systemDefault()),
        )

    @Test
    fun `rescheduleFromBfu arms a one-shot that is due after boot at its intended time`() = runTest {
        val oneShot = oneShotDueIn(2L, hours = 2)
        every { bfuCache.getAll() } returns listOf(oneShot)
        every { bfuCache.pendingDismissalIds() } returns emptySet()

        repo.rescheduleFromBfu()

        verify(exactly = 1) { scheduler.scheduleAt(match { it.id == 2L }, expectedFire(oneShot)) }
    }

    @Test
    fun `rescheduleFromBfu does not re-arm a one-shot dismissed on the lock screen`() = runTest {
        every { bfuCache.getAll() } returns listOf(oneShotDueIn(2L, hours = 2))
        every { bfuCache.pendingDismissalIds() } returns setOf(2L)

        repo.rescheduleFromBfu()

        verify(exactly = 0) { scheduler.scheduleAt(any(), any()) }
    }

    // Fails before the change: a relative timer's intended fire ignored its
    // snooze, so a timer snoozed before a restart was not armed at all.
    @Test
    fun `rescheduleFromBfu arms a snoozed timer at its snooze time`() = runTest {
        val snoozeAt = System.currentTimeMillis() + 300_000L
        val timer = Alarm(
            id = 3L, hour = 0, minute = 0, daysOfWeek = 0, enabled = true,
            updatedAtEpoch = System.currentTimeMillis() - 3_600_000L, relativeMinutes = 5,
            snoozedUntilEpoch = snoozeAt,
        )
        every { bfuCache.getAll() } returns listOf(timer)
        every { bfuCache.pendingDismissalIds() } returns emptySet()

        repo.rescheduleFromBfu()

        verify(exactly = 1) { scheduler.scheduleAt(match { it.id == 3L }, snoozeAt) }
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

    // Fails before the change: scheduleAt ran first, so it is called even though the UPDATE throws.
    @Test
    fun `snoozeAt with a failing UPDATE arms nothing and changes nothing`() = runTest {
        dao.upsert(recurringRow())
        dao.failSetSnooze = true

        val thrown = runCatching { repo.snoozeAt(1L, System.currentTimeMillis() + 60_000L) }.exceptionOrNull()

        assertNotNull(thrown)
        verify(exactly = 0) { scheduler.scheduleAtChecked(any(), any()) }
        assertEquals(0, dao.getById(1L)?.consecutiveSnoozeCount)
        assertNull(dao.getById(1L)?.snoozedUntilEpoch)
    }

    // Fails before the change: scheduleAt was called before the UPDATE, so the recorded order is reversed.
    @Test
    fun `snoozeAt writes the UPDATE before arming AlarmManager`() = runTest {
        dao.upsert(recurringRow())
        every { scheduler.scheduleAtChecked(any(), any()) } answers { dao.events += "scheduleAt"; true }

        repo.snoozeAt(1L, 1_800_000_000_000L)

        assertEquals(listOf("setSnooze", "scheduleAt"), dao.events)
        assertEquals(1, dao.getById(1L)?.consecutiveSnoozeCount)
        assertEquals(1_800_000_000_000L, dao.getById(1L)?.snoozedUntilEpoch)
    }

    // Fails before the change: the refusal was swallowed, the row kept the snooze and snoozeAt returned the trigger.
    @Test
    fun `snoozeAt reverts the row and throws when arming is refused`() = runTest {
        dao.upsert(recurringRow().copy(consecutiveSnoozeCount = 1, snoozedUntilEpoch = 5L))
        every { scheduler.scheduleAtChecked(any(), any()) } returns false

        val thrown = runCatching { repo.snoozeAt(1L, 1_800_000_000_000L) }.exceptionOrNull()

        assertNotNull(thrown)
        assertEquals(1, dao.getById(1L)?.consecutiveSnoozeCount)
        assertEquals(5L, dao.getById(1L)?.snoozedUntilEpoch)
        coVerify(exactly = 0) { bfuCache.upsert(any()) }
    }

    @Test
    fun `snoozeAt with a failing UPDATE arms nothing`() = runTest {
        dao.upsert(recurringRow())
        dao.failSetSnooze = true

        runCatching { repo.snoozeAt(1L, 1_800_000_000_000L) }

        verify(exactly = 0) { scheduler.scheduleAtChecked(any(), any()) }
    }

    // Fails before the change: the widget/BFU exception propagated out of snoozeAt after the arm.
    @Test
    fun `snoozeAt still returns the trigger when follow-ups fail after a successful arm`() = runTest {
        dao.upsert(recurringRow())
        coEvery { widgetRefresher.refresh() } throws IllegalStateException("widget")
        coEvery { bfuCache.upsert(any()) } throws IllegalStateException("bfu")

        assertEquals(1_800_000_000_000L, repo.snoozeAt(1L, 1_800_000_000_000L))
        assertEquals(1, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    // Fails before the change: the widget exception propagated out after the row was already changed.
    @Test
    fun `setEnabledLocalOnly keeps the change when the widget refresh throws`() = runTest {
        dao.upsert(recurringRow())
        coEvery { widgetRefresher.refresh() } throws IllegalStateException("widget")

        repo.setEnabledLocalOnly(1L, false)

        assertEquals(false, dao.getById(1L)?.enabled)
        verify { scheduler.cancel(1L) }
    }

    @Test
    fun `delete keeps the change when mirrors and widget throw`() = runTest {
        dao.upsert(recurringRow())
        coEvery { widgetRefresher.refresh() } throws IllegalStateException("widget")
        coEvery { bfuCache.remove(any()) } throws IllegalStateException("bfu")
        coEvery { tombstones.add(any(), any(), any()) } throws IllegalStateException("tomb")

        repo.delete(1L)

        assertNull(dao.getById(1L))
    }

    @Test
    fun `resetSnoozeCounter keeps the reset when the BFU mirror throws`() = runTest {
        dao.upsert(recurringRow().copy(consecutiveSnoozeCount = 2))
        coEvery { bfuCache.dropPendingSnoozeBumps(any()) } throws IllegalStateException("bfu")

        repo.resetSnoozeCounter(1L)

        assertEquals(0, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    // Fails before the change: the trigger survived (only the counter was cleared) and two statements ran.
    @Test
    fun `resetSnoozeCounter clears trigger and counter in one statement`() = runTest {
        dao.upsert(recurringRow().copy(consecutiveSnoozeCount = 3, snoozedUntilEpoch = 1_800_000_000_000L))

        repo.resetSnoozeCounter(1L)

        assertEquals(listOf("setSnooze"), dao.events)
        assertNull(dao.getById(1L)?.snoozedUntilEpoch)
        assertEquals(0, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    // Fails before the change: the trigger survived when only the later steps failed.
    @Test
    fun `resetSnoozeCounter clears the trigger and stays best effort after the statement`() = runTest {
        dao.upsert(recurringRow().copy(consecutiveSnoozeCount = 3, snoozedUntilEpoch = 1_800_000_000_000L))
        dao.failGetById = true
        coEvery { bfuCache.dropPendingSnoozeBumps(any()) } throws IllegalStateException("bfu")

        repo.resetSnoozeCounter(1L)

        dao.failGetById = false
        assertNull(dao.getById(1L)?.snoozedUntilEpoch)
        assertEquals(0, dao.getById(1L)?.consecutiveSnoozeCount)
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

    // --- Disable is all-or-nothing (R4-1) ----------------------------------

    private suspend fun seedEnabledSnoozed() {
        dao.upsert(recurringRow().copy(snoozedUntilEpoch = 1_800_000_000_000L, consecutiveSnoozeCount = 2))
    }

    // Fails before the change: cancel/row read/mirror exceptions escaped after the commit.
    @Test
    fun `setEnabledLocalOnly false commits one statement and swallows later failures`() = runTest {
        seedEnabledSnoozed()
        every { scheduler.cancel(any()) } throws IllegalStateException("cancel boom")
        dao.failGetById = true
        coEvery { bfuCache.upsert(any()) } throws IllegalStateException("bfu boom")

        repo.setEnabledLocalOnly(1L, false)

        dao.failGetById = false
        assertEquals(1, dao.disableCalls)
        assertEquals(false, dao.getById(1L)?.enabled)
        assertNull(dao.getById(1L)?.snoozedUntilEpoch)
    }

    // Fails before the change: same escape path.
    @Test
    fun `setEnabled false commits one statement and swallows later failures`() = runTest {
        seedEnabledSnoozed()
        every { scheduler.cancel(any()) } throws IllegalStateException("cancel boom")
        dao.failGetById = true
        coEvery { bfuCache.upsert(any()) } throws IllegalStateException("bfu boom")

        repo.setEnabled(1L, false)

        dao.failGetById = false
        assertEquals(1, dao.disableCalls)
        assertEquals(false, dao.getById(1L)?.enabled)
        assertNull(dao.getById(1L)?.snoozedUntilEpoch)
    }

    @Test
    fun `setEnabled true still propagates a scheduler failure`() = runTest {
        dao.upsert(recurringRow().copy(enabled = false))
        every { scheduler.schedule(any()) } throws IllegalStateException("schedule boom")

        val failure = runCatching { repo.setEnabled(1L, true) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    // Fails before the change: the unguarded post-reset row read threw.
    @Test
    fun `resetSnoozeCounter swallows a failing post-reset row read`() = runTest {
        seedEnabledSnoozed()
        dao.failGetById = true

        repo.resetSnoozeCounter(1L)

        dao.failGetById = false
        assertEquals(0, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    private fun repoWith(cache: BfuAlarmCache) = AlarmRepository(
        dao = dao,
        scheduler = scheduler,
        wearBridge = wearBridge,
        tombstones = tombstones,
        widgetRefresher = widgetRefresher,
        settingsStore = settingsStore,
        bfuCache = cache,
        appContext = appContext,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun recurringRow() = AlarmEntity(
        id = 1L, label = "x", hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
        enabled = true, audioUri = null, audioName = null, isVibrationOnly = false,
        updatedAtEpoch = 1L, consecutiveSnoozeCount = 0,
    )

    private fun recurringAlarm() = Alarm(
        id = 1L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
        enabled = true, updatedAtEpoch = 1L,
    )

    // Fails before the change: Room ends at 2 (the two bumps survive the dismiss and are applied).
    @Test
    fun `two pre-unlock snoozes then dismiss then unlock leaves the Room count at zero`() = runTest {
        val cache = BfuAlarmCache(java.io.File(tmp.root, "bfu.json"))
        dao.upsert(recurringRow())
        cache.replaceAll(listOf(recurringAlarm()))
        cache.recordPreUnlockSnooze(1L, 1_800_000_000_000L)
        cache.recordPreUnlockSnooze(1L, 1_800_000_300_000L)
        cache.markDismissed(1L)

        repoWith(cache).rescheduleAllOnBoot()

        assertEquals(0, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    @Test
    fun `snooze after a pre-unlock dismiss still counts at unlock`() = runTest {
        val cache = BfuAlarmCache(java.io.File(tmp.root, "bfu.json"))
        dao.upsert(recurringRow())
        cache.replaceAll(listOf(recurringAlarm()))
        cache.recordPreUnlockSnooze(1L, 1_800_000_000_000L)
        cache.markDismissed(1L)
        cache.recordPreUnlockSnooze(1L, 1_800_000_300_000L)

        repoWith(cache).rescheduleAllOnBoot()

        assertEquals(1, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    // Fails before the change: the pending bump stays and the next unlock adds it to the reset count.
    @Test
    fun `resetSnoozeCounter also drops pending BFU bumps`() = runTest {
        val cache = BfuAlarmCache(java.io.File(tmp.root, "bfu.json"))
        dao.upsert(recurringRow().copy(consecutiveSnoozeCount = 2))
        cache.replaceAll(listOf(recurringAlarm()))
        cache.recordPreUnlockSnooze(1L, 1_800_000_000_000L)

        repoWith(cache).resetSnoozeCounter(1L)

        assertTrue(cache.drainPendingSnoozes().isEmpty())
        assertEquals(0, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    // Fails before the change: the Room count stays 4 because applyPendingDismissals never reset it.
    @Test
    fun `applyPendingDismissals resets the Room counter for each drained id`() = runTest {
        dao.upsert(recurringRow().copy(consecutiveSnoozeCount = 4))
        coEvery { bfuCache.drainPendingDismissals() } returns setOf(1L)

        repo.rescheduleAllOnBoot()

        assertEquals(0, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    // Fails if applyPendingDismissals only resets the counter: the Room snooze trigger would survive.
    @Test
    fun `applyPendingDismissals clears the snooze trigger and counter together`() = runTest {
        dao.upsert(recurringRow().copy(consecutiveSnoozeCount = 3, snoozedUntilEpoch = 1_800_000_000_000L))
        coEvery { bfuCache.drainPendingDismissals() } returns setOf(1L)

        repo.rescheduleAllOnBoot()

        assertEquals(null, dao.getById(1L)?.snoozedUntilEpoch)
        assertEquals(0, dao.getById(1L)?.consecutiveSnoozeCount)
    }

    @Test
    fun `unlock reconciliation retains snooze trigger committed before unlock`() = runTest {
        val trigger = System.currentTimeMillis() + 600_000L
        dao.upsert(AlarmEntity(
            id = 1L, label = "x", hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, audioUri = null, audioName = null, isVibrationOnly = false,
            updatedAtEpoch = 1L, consecutiveSnoozeCount = 0,
        ))
        every { bfuCache.getAll() } returns listOf(Alarm(
            id = 1L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true, updatedAtEpoch = 1L, snoozedUntilEpoch = trigger,
        ))
        coEvery { bfuCache.drainPendingSnoozes() } returns
            mapOf(1L to BfuAlarmCache.PendingSnooze(1, trigger))

        repo.rescheduleAllOnBoot()

        assertEquals(trigger, dao.getById(1L)?.snoozedUntilEpoch)
        assertEquals(1, dao.getById(1L)?.consecutiveSnoozeCount)
        verify { scheduler.rescheduleAll(match { it.single().snoozedUntilEpoch == trigger }) }
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

    // --- missed-during-downtime routes through dismissAction (1.0.8) ---
    //
    // handleMissedDuringDowntime used to branch on `alarm.isRelative ||
    // alarm.selfDestruct`, which deleted EVERY missed timer regardless of the
    // flag. With "Don't delete after firing" now user-settable on timers, that
    // silently overrode the setting whenever the fire was missed rather than
    // dismissed live. Both paths now share AlarmRingService.dismissAction.
    //
    // AlarmNotifications is an object, so mockkObject stubs the missed-notification
    // side effect out — the mocked Context can't build a real Notification, and
    // the routing decision is what's under test here.

    private fun pastRelativeRow(id: Long, selfDestruct: Boolean) = AlarmEntity(
        id = id, label = "",
        hour = 0, minute = 0,
        daysOfWeek = DaysOfWeek.NONE,
        enabled = true, audioUri = null, audioName = null, isVibrationOnly = false,
        // Fired an hour ago: intendedFire = updatedAtEpoch + relativeMinutes,
        // which lands well before bootCompleteAt.
        updatedAtEpoch = System.currentTimeMillis() - 3_600_000L,
        relativeMinutes = 1,
        selfDestruct = selfDestruct,
    )

    @Test
    fun `missed timer with keep-after-firing is disabled, not deleted`() = runTest {
        mockkObject(AlarmNotifications)
        every { AlarmNotifications.postMissedNotification(any(), any()) } returns Unit
        try {
            dao.upsert(pastRelativeRow(1L, selfDestruct = false))

            repo.rescheduleAllOnBoot()

            val row = dao.getById(1L)
            assertNotNull("row must survive — the user asked to keep it", row)
            assertEquals(false, row?.enabled)
        } finally {
            unmockkObject(AlarmNotifications)
        }
    }

    @Test
    fun `missed timer with self-destruct is still deleted`() = runTest {
        mockkObject(AlarmNotifications)
        every { AlarmNotifications.postMissedNotification(any(), any()) } returns Unit
        try {
            dao.upsert(pastRelativeRow(2L, selfDestruct = true))

            repo.rescheduleAllOnBoot()

            assertNull("self-destruct timers must still vanish", dao.getById(2L))
        } finally {
            unmockkObject(AlarmNotifications)
        }
    }
}

// In-memory fake DAO that exposes the same surface AlarmDao does. Pure Kotlin,
// no Android Context needed — keeps AlarmRepositoryTest a fast JVM unit test.
private class FakeAlarmDao : AlarmDao {
    private val rows = MutableStateFlow<Map<Long, AlarmEntity>>(emptyMap())
    private var nextId = 1L
    var disableCalls = 0
    var failGetById = false

    override fun observeAll(): Flow<List<AlarmEntity>> =
        rows.map { map -> map.values.sortedBy { it.id } }

    override suspend fun getAll(): List<AlarmEntity> = rows.value.values.toList()

    override suspend fun getById(id: Long): AlarmEntity? {
        if (failGetById) error("getById boom")
        return rows.value[id]
    }

    override suspend fun upsert(entity: AlarmEntity): Long {
        val id = if (entity.id == 0L) nextId++ else entity.id.also { if (it >= nextId) nextId = it + 1 }
        val toStore = if (entity.id == 0L) entity.copy(id = id) else entity
        rows.update { it + (id to toStore) }
        return id
    }

    override suspend fun deleteById(id: Long) {
        rows.update { it - id }
    }

    override suspend fun setEnabledStamped(id: Long, enabled: Boolean, stamp: Long) {
        rows.update { map ->
            map[id]?.let { map + (id to it.copy(enabled = enabled, updatedAtEpoch = stamp)) } ?: map
        }
    }

    override suspend fun disableStamped(id: Long, stamp: Long) {
        disableCalls++
        rows.update { map ->
            map[id]?.let { map + (id to it.copy(enabled = false, snoozedUntilEpoch = null, updatedAtEpoch = stamp)) } ?: map
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

    var failSetSnooze = false
    val events = mutableListOf<String>()

    override suspend fun setSnooze(id: Long, until: Long?, count: Int) {
        if (failSetSnooze) throw IllegalStateException("simulated UPDATE failure")
        events += "setSnooze"
        rows.update { map ->
            map[id]?.let { map + (id to it.copy(snoozedUntilEpoch = until, consecutiveSnoozeCount = count)) } ?: map
        }
    }
}
