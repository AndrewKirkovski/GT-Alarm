package com.kirkouski.gtwake.companion.ring

import android.os.Handler
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.SettingsState
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.data.bfu.BfuAlarmCache
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import com.kirkouski.gtwake.companion.ring.AlarmRingService.Companion.dismissAction
import com.kirkouski.gtwake.companion.ring.AlarmRingService.DismissAction
import com.kirkouski.gtwake.companion.scheduler.AlarmScheduler
import com.kirkouski.gtwake.companion.wear.AlarmRingHints
import com.kirkouski.gtwake.companion.wear.PairedDeviceInfo
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Service-level tests. There is no Robolectric here, so the real service
 * methods run on an Unsafe-allocated instance with every collaborator mocked,
 * a test dispatcher for IO, and a mocked main Handler that runs posted work
 * inline and records delayed runnables so the test can fire them by hand.
 *
 * What cannot be exercised without Robolectric (said explicitly, not skipped
 * silently): building the ring notification and startForeground
 * (`startForegroundOnly`), the MediaPlayer/vibrator start
 * (`AlarmAudioPlayer`), the backup `PendingIntent.send()`
 * (`launchRingActivity`) and the AlarmActivity itself. The first two are
 * replaced by the `foregroundStarter` / `ringPresenter` seams; the launch
 * decision lives in the pure `needsBackupLaunch`, tested below.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlarmRingServiceTest {

    private val baseAlarm = Alarm(
        id = 1L,
        label = "Wake",
        hour = 7,
        minute = 0,
        daysOfWeek = DaysOfWeek.NONE,
        enabled = true,
        updatedAtEpoch = 1_700_000_000_000L,
    )
    private val recurring = baseAlarm.copy(daysOfWeek = DaysOfWeek.WEEKDAYS)
    private val dismissed = RingActionKind.DISMISS
    private val snoozed = RingActionKind.SNOOZE

    // ---- dismissAction (pure) --------------------------------------------

    @Test
    fun `legacy one-shot (selfDestruct=false) gets DISABLE on dismiss`() {
        assertEquals(DismissAction.DISABLE, dismissAction(baseAlarm))
    }

    @Test
    fun `one-shot with selfDestruct=true gets DELETE on dismiss`() {
        assertEquals(DismissAction.DELETE, dismissAction(baseAlarm.copy(selfDestruct = true)))
    }

    @Test
    fun `relative alarm (always one-shot, default selfDestruct) gets DELETE`() {
        val relative = baseAlarm.copy(daysOfWeek = DaysOfWeek.NONE, relativeMinutes = 15, selfDestruct = true)
        assertEquals(DismissAction.DELETE, dismissAction(relative))
    }

    @Test
    fun `relative alarm with selfDestruct=false (user opt-out) gets DISABLE`() {
        val relative = baseAlarm.copy(daysOfWeek = DaysOfWeek.NONE, relativeMinutes = 15, selfDestruct = false)
        assertEquals(DismissAction.DISABLE, dismissAction(relative))
    }

    @Test
    fun `repeating alarm (Mon-Fri) stays armed — KEEP`() {
        assertEquals(DismissAction.KEEP, dismissAction(baseAlarm.copy(daysOfWeek = DaysOfWeek.WEEKDAYS)))
    }

    @Test
    fun `repeating alarm (single day) stays armed — KEEP`() {
        assertEquals(DismissAction.KEEP, dismissAction(baseAlarm.copy(daysOfWeek = DaysOfWeek.MON)))
    }

    @Test
    fun `disabled alarm is a no-op — KEEP`() {
        assertEquals(DismissAction.KEEP, dismissAction(baseAlarm.copy(enabled = false)))
    }

    @Test
    fun `null alarm (deleted before dismiss) is a no-op — KEEP`() {
        assertEquals(DismissAction.KEEP, dismissAction(null))
    }

    @Test
    fun `every-day repeating alarm stays armed — KEEP`() {
        assertEquals(DismissAction.KEEP, dismissAction(baseAlarm.copy(daysOfWeek = DaysOfWeek.ALL)))
    }

    // ---- launch decision (pure) ------------------------------------------

    /*
     * Fails before: the working tree always sent the backup PI.send() (the
     * isVisible skip had been removed) and had no decision function, so a
     * visible Activity showing the SAME occurrence was destroyed and
     * recreated. assertFalse on the first call is the contract.
     */
    @Test
    fun `backup launch is skipped only for a visible Activity that shows this occurrence`() {
        assertFalse(AlarmRingService.needsBackupLaunch(true, 1L to 1000L, 1L, 1000L))
        assertTrue(AlarmRingService.needsBackupLaunch(true, 1L to 900L, 1L, 1000L))
        assertTrue(AlarmRingService.needsBackupLaunch(true, 2L to 1000L, 1L, 1000L))
        assertTrue(AlarmRingService.needsBackupLaunch(true, null, 1L, 1000L))
        assertTrue(AlarmRingService.needsBackupLaunch(false, 1L to 1000L, 1L, 1000L))
    }

    // ---- R1 recurrence ---------------------------------------------------

    /*
     * Fails before: scheduler.schedule ran only at the very end of the ring
     * job, so the mid-pre-arm verify(exactly = 1) saw 0 calls.
     */
    @Test
    fun `recurrence is armed once before the pre-arm and never re-armed later`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns recurring
        env.watchConnected()
        coEvery { env.wearBridge.sendAlarmFiredAwaiting(1L, any(), any()) } coAnswers {
            delay(3_000)
            true
        }

        env.ring(1L)
        runCurrent()

        assertTrue("still inside the pre-arm window", env.presented.isEmpty())
        verify(exactly = 1) { env.scheduler.schedule(any()) }
        advanceUntilIdle()
        assertEquals(1, env.presented.size)
        verify(exactly = 1) { env.scheduler.schedule(any()) }
    }

    /*
     * Fails before: claiming the dismiss made the ring job return before the
     * late scheduler.schedule, so it was called 0 times (the alarm stopped
     * recurring).
     */
    @Test
    fun `dismiss during the pre-arm leaves the recurring alarm armed`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns recurring
        env.watchConnected()
        coEvery { env.wearBridge.sendAlarmFiredAwaiting(1L, any(), any()) } coAnswers {
            delay(3_000)
            true
        }
        env.ring(1L)
        runCurrent()

        env.action(dismissed, RingActionRequest(1L, fromPeer = false, firedAtEpoch = env.firedAt()))
        advanceUntilIdle()

        verify(exactly = 1) { env.scheduler.schedule(any()) }
        verify(exactly = 0) { env.scheduler.cancel(any()) }
        coVerify(exactly = 1) { env.repository.resetSnoozeCounter(1L) }
        coVerify(exactly = 0) { env.repository.setEnabled(any(), any()) }
        assertTrue("the ended ring must not start audio afterwards", env.presented.isEmpty())
    }

    /*
     * Fails before: schedule() was never called before the snooze, so the
     * ordered verify failed; with the old late call it would have come AFTER
     * the snooze and overwritten it.
     */
    @Test
    fun `snooze during the pre-arm is not overwritten by the recurrence arm`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns recurring
        coEvery { env.repository.snoozeAt(1L, any(), any()) } returns 123L
        env.watchConnected()
        coEvery { env.wearBridge.sendAlarmFiredAwaiting(1L, any(), any()) } coAnswers {
            delay(3_000)
            true
        }
        env.ring(1L)
        runCurrent()

        env.action(snoozed, RingActionRequest(1L, fromPeer = false, firedAtEpoch = env.firedAt()))
        advanceUntilIdle()

        coVerifyOrder {
            env.scheduler.schedule(any())
            env.repository.snoozeAt(1L, any(), any())
        }
        verify(exactly = 1) { env.scheduler.schedule(any()) }
    }

    // ---- R2 / R3 takeover ------------------------------------------------

    /*
     * Fails before: handleRing ran finishSupersededRing for ANY previous id,
     * including the same alarm, so the one-shot was disabled and its snooze
     * counter reset by the second "Fire alarm now".
     */
    @Test
    fun `same alarm fired again applies no dismiss policy to it`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns baseAlarm

        env.ring(1L)
        runCurrent()
        env.ring(1L)
        runCurrent()

        assertEquals(2, env.presented.size)
        assertTrue(env.presented[1].second > env.presented[0].second)
        coVerify(exactly = 0) { env.repository.setEnabledLocalOnly(any(), any()) }
        coVerify(exactly = 0) { env.repository.setEnabled(any(), any()) }
        coVerify(exactly = 0) { env.repository.resetSnoozeCounter(any()) }
        coVerify(exactly = 0) { env.wearBridge.sendAlarmDismissed(any(), any()) }
    }

    /*
     * Fails before: the new ring's job awaited finishSupersededRing, which
     * awaited sendAlarmDismissed (no outer timeout). With that send hanging,
     * alarm 2 never reached its presenter.
     */
    @Test
    fun `takeover commits the old policy but does not wait for the old alarm's send`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns baseAlarm
        coEvery { env.repository.getById(2L) } returns baseAlarm.copy(id = 2L)
        coEvery { env.wearBridge.sendAlarmDismissed(1L, any()) } coAnswers { awaitCancellation() }
        env.ring(1L)
        runCurrent()

        env.ring(2L)
        runCurrent()

        assertTrue("alarm 2 presents without waiting for the hung send", env.presented.any { it.first == 2L })
        assertTrue(currentTime < 5_000L)
        coVerify(exactly = 1) { env.repository.setEnabledLocalOnly(1L, false) }
        coVerify(exactly = 1) { env.wearBridge.sendAlarmDismissed(1L, any()) }
        advanceTimeBy(6_000)
        runCurrent()
    }

    // ---- R4 / R5 / R6 reply matrix and grace -----------------------------

    /*
     * Fails before: the stale/ended occurrence path answered ok:false and
     * there was no grace window; the service tore down at once and the second
     * request below got ok:false (verify(exactly = 2) ... true failed).
     */
    @Test
    fun `duplicate request inside the grace window replays ok true and is not applied twice`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } returns baseAlarm
        val request = RingActionRequest(1L, fromPeer = true, firedAtEpoch = 1_000L, requestStamp = 5L)

        env.action(dismissed, request)
        advanceUntilIdle()
        env.action(dismissed, request)
        advanceUntilIdle()

        verify(exactly = 2) { env.wearBridge.sendPeerActionAck(1L, "alarm_dismissed", 5L, 1_000L, true) }
        verify(exactly = 0) { env.wearBridge.sendPeerActionAck(any(), any(), any(), any(), false) }
        coVerify(exactly = 1) { env.repository.setEnabledLocalOnly(1L, false) }
        verify(exactly = 0) { env.wearBridge.sendPeerEnded(any(), any(), any()) }
    }

    /*
     * Fails before: no grace window existed (immediate stopForegroundAndSelf),
     * so there was no 6 s runnable to find and the ring was torn down at commit.
     */
    @Test
    fun `watch commit keeps the service for the grace window, then the occurrence-checked teardown runs`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } returns baseAlarm

        env.action(dismissed, RingActionRequest(1L, true, 1_000L, 5L))
        advanceUntilIdle()

        assertEquals(1L, env.read("currentAlarmId"))
        assertTrue(env.read("ringEnded") as Boolean)
        val grace = env.delayed.single { it.second == 6_000L }.first
        grace.run()
        assertEquals("grace teardown ends the ring", -1L, env.read("currentAlarmId"))
    }

    /*
     * The delayed teardown of an earlier occurrence must not touch a ring that
     * took over meanwhile (even if the runnable somehow ran).
     */
    @Test
    fun `grace teardown of an old occurrence leaves a newer ring untouched`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } returns baseAlarm
        val audio = mockk<AlarmAudioPlayer>(relaxed = true)

        env.action(dismissed, RingActionRequest(1L, true, 1_000L, 5L))
        advanceUntilIdle()
        val grace = env.delayed.single { it.second == 6_000L }.first
        env.setRing(1L, 2_000L)
        env.write("player", audio)

        grace.run()

        assertEquals(1L, env.read("currentAlarmId"))
        assertEquals(2_000L, env.read("currentFiredAtEpoch"))
        assertFalse(env.read("ringEnded") as Boolean)
        assertSame(audio, env.read("player"))
        verify(exactly = 0) { audio.stop() }
    }

    @Test
    fun `a takeover cancels the pending grace teardown`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } returns baseAlarm
        env.action(dismissed, RingActionRequest(1L, true, 1_000L, 5L))
        advanceUntilIdle()
        assertTrue(env.delayed.any { it.second == 6_000L })

        env.invoke("stopCurrentRingResources")

        assertTrue(env.delayed.none { it.second == 6_000L })
    }

    /*
     * Fails before: a duplicate that arrived while the first copy was still
     * committing got ok:false (PENDING receipt), which un-armed the watch.
     */
    @Test
    fun `duplicate while the first request is still committing gets no reply`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } coAnswers {
            delay(1_000)
            baseAlarm
        }
        val request = RingActionRequest(1L, true, 1_000L, 5L)

        env.action(dismissed, request)
        runCurrent()
        env.action(dismissed, request)
        advanceUntilIdle()

        verify(exactly = 0) { env.wearBridge.sendPeerActionAck(any(), any(), any(), any(), false) }
        verify(exactly = 1) { env.wearBridge.sendPeerActionAck(1L, "alarm_dismissed", 5L, 1_000L, true) }
        coVerify(exactly = 1) { env.repository.setEnabledLocalOnly(1L, false) }
    }

    /*
     * Fails before: a committed action whose receipt write failed answered
     * ok:false; here a failed COMMIT answers ok:false, the phone keeps
     * ringing (audio untouched, occurrence still current), and a retry with a
     * new stamp is applied.
     */
    @Test
    fun `failed watch commit replies ok false, keeps the ring, and a retry is applied`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        val audio = mockk<AlarmAudioPlayer>(relaxed = true)
        env.write("player", audio)
        coEvery { env.repository.getById(1L) } throws IOException("disk full")

        env.action(dismissed, RingActionRequest(1L, true, 1_000L, 5L))
        advanceUntilIdle()

        verify(exactly = 1) { env.wearBridge.sendPeerActionAck(1L, "alarm_dismissed", 5L, 1_000L, false) }
        assertFalse(env.read("ringEnded") as Boolean)
        assertFalse(env.read("ringActionStarted") as Boolean)
        verify(exactly = 0) { audio.stop() }

        coEvery { env.repository.getById(1L) } returns baseAlarm
        env.action(dismissed, RingActionRequest(1L, true, 1_000L, 6L))
        advanceUntilIdle()

        verify(exactly = 1) { env.wearBridge.sendPeerActionAck(1L, "alarm_dismissed", 6L, 1_000L, true) }
        verify(exactly = 1) { audio.stop() }
    }

    @Test
    fun `request for an ended occurrence is answered with the recorded outcome and applies nothing`() = runTest {
        val env = newEnv()
        env.setRing(1L, 2_000L)
        env.peerState().recordOutcome(OccurrenceKey(1L, 1_000L), RingOutcome.SNOOZED)

        env.action(dismissed, RingActionRequest(1L, true, 1_000L, 9L))
        advanceUntilIdle()

        verify(exactly = 1) { env.wearBridge.sendPeerEnded(1L, "alarm_snoozed", 1_000L) }
        verify(exactly = 0) { env.wearBridge.sendPeerActionAck(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { env.repository.getById(any()) }
        assertEquals(2_000L, env.read("currentFiredAtEpoch"))
        assertFalse(env.read("ringEnded") as Boolean)
    }

    /*
     * Legacy guard. The previous code also sent no ack without a stamp, so
     * this test would pass there too; it pins the rule (HEAD behaviour on the
     * current matching ring, never a reply) against regression.
     */
    @Test
    fun `legacy watch request is applied to the current matching ring and gets no reply`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } returns baseAlarm

        env.action(dismissed, RingActionRequest(1L, fromPeer = true))
        advanceUntilIdle()

        coVerify(exactly = 1) { env.repository.setEnabledLocalOnly(1L, false) }
        verify(exactly = 0) { env.wearBridge.sendPeerActionAck(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { env.wearBridge.sendPeerEnded(any(), any(), any()) }
        assertEquals("legacy tears down at once (HEAD)", -1L, env.read("currentAlarmId"))
    }

    @Test
    fun `legacy watch request for another alarm or an ended ring is ignored without a reply`() = runTest {
        val env = newEnv()
        env.setRing(2L, 1_000L)
        env.action(dismissed, RingActionRequest(1L, fromPeer = true))
        env.setRing(1L, 1_000L)
        env.write("ringEnded", true)
        env.action(snoozed, RingActionRequest(1L, fromPeer = true))
        advanceUntilIdle()

        coVerify(exactly = 0) { env.repository.getById(any()) }
        verify(exactly = 0) { env.wearBridge.sendPeerActionAck(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { env.wearBridge.sendPeerEnded(any(), any(), any()) }
    }

    /*
     * Fails before: audio stopped only after a successful commit, nothing
     * told the Activity, and auto-stop was never re-armed.
     */
    @Test
    fun `failed local dismiss silences the phone, emits ActionFailed and re-arms auto-stop`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        val audio = mockk<AlarmAudioPlayer>(relaxed = true)
        env.write("player", audio)
        coEvery { env.repository.getById(1L) } throws IOException("disk full")
        val failures = mutableListOf<RingEndedSignal.Event>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            RingEndedSignal.actionFailures.collect { failures += it }
        }

        env.action(dismissed, RingActionRequest(1L, fromPeer = false, firedAtEpoch = 1_000L))
        advanceUntilIdle()

        verify(exactly = 1) { audio.stop() }
        assertEquals(listOf(RingEndedSignal.Event(1L, 1_000L)), failures)
        assertFalse(env.read("ringEnded") as Boolean)
        assertFalse("buttons work again", env.read("ringActionStarted") as Boolean)
        assertNotNull("auto-stop is armed again", env.read("autoStopRunnable"))
        assertTrue(env.delayed.any { it.second == 30_000L })
    }

    /*
     * Fails before: auto-stop was posted once at audio start; no 30 s retry
     * existed and a failing save repeated forever without an end.
     */
    @Test
    fun `auto-stop is armed at ring start, retries with backoff, then ends the ring without saving`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns baseAlarm
        env.ring(1L)
        advanceUntilIdle()
        coEvery { env.repository.getById(1L) } throws IOException("disk full")

        env.fire(300_000L)
        assertTrue(env.delayed.any { it.second == 30_000L })
        env.fire(30_000L)
        assertTrue(env.delayed.any { it.second == 60_000L })
        env.fire(60_000L)
        assertTrue(env.delayed.any { it.second == 120_000L })
        assertFalse("still retrying", env.read("ringEnded") as Boolean)
        env.fire(120_000L)

        assertTrue("gave up: ring ended without a save", env.read("ringEnded") as Boolean)
        assertEquals(RingOutcome.DISMISSED, env.peerState().outcomeOf(OccurrenceKey(1L, env.firedAt())))
        assertTrue(env.delayed.none { it.second in setOf(30_000L, 60_000L, 120_000L) })
        assertTrue("grace teardown is scheduled", env.delayed.any { it.second == 6_000L })
    }

    @Test
    fun `cancellation of an in-flight commit is not reported as a failed action`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } coAnswers { awaitCancellation() }
        val failures = mutableListOf<RingEndedSignal.Event>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            RingEndedSignal.actionFailures.collect { failures += it }
        }
        env.action(dismissed, RingActionRequest(1L, true, 1_000L, 5L))
        runCurrent()

        env.scope.cancel()
        advanceUntilIdle()

        assertTrue(failures.isEmpty())
        verify(exactly = 0) { env.wearBridge.sendPeerActionAck(any(), any(), any(), any(), false) }
    }

    // ---- pre-unlock snooze ordering --------------------------------------

    /*
     * Fails before: scheduleAt ran first, then two separate cache writes; the
     * ordered verify (record, then arm) failed.
     */
    @Test
    fun `pre-unlock snooze records first and arms only afterwards`() = runTest {
        val env = newEnv(unlocked = false)
        env.setRing(1L, 1_000L)
        every { env.bfuCache.getAll() } returns listOf(baseAlarm)
        coEvery { env.bfuCache.recordPreUnlockSnooze(1L, any()) } returns true

        env.action(snoozed, RingActionRequest(1L, fromPeer = false, firedAtEpoch = 1_000L))
        advanceUntilIdle()

        coVerifyOrder {
            env.bfuCache.recordPreUnlockSnooze(1L, any())
            env.scheduler.scheduleAtChecked(any(), any())
        }
    }

    /*
     * Fails before: scheduleAt was already called when the cache write failed.
     */
    @Test
    fun `pre-unlock snooze that cannot be recorded arms nothing and reports the failure`() = runTest {
        val env = newEnv(unlocked = false)
        env.setRing(1L, 1_000L)
        every { env.bfuCache.getAll() } returns listOf(baseAlarm)
        coEvery { env.bfuCache.recordPreUnlockSnooze(1L, any()) } returns false
        val failures = mutableListOf<RingEndedSignal.Event>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            RingEndedSignal.actionFailures.collect { failures += it }
        }

        env.action(snoozed, RingActionRequest(1L, fromPeer = false, firedAtEpoch = 1_000L))
        advanceUntilIdle()

        verify(exactly = 0) { env.scheduler.scheduleAtChecked(any(), any()) }
        assertEquals(1, failures.size)
        assertFalse(env.read("ringEnded") as Boolean)
    }

    // ---- R10 / R11 hints and settings ------------------------------------

    /*
     * Fails before: buildRingHints/resolveAudioUri let a settings failure
     * propagate into the broad catch, tearing the ring down, so the presenter
     * was never reached; and there was no supportsActionAck.
     */
    @Test
    fun `a failing settings read never cancels ring startup and the hint reflects receive readiness`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns baseAlarm
        coEvery { env.settingsStore.snapshot() } throws IOException("datastore broken")
        coEvery { env.wearBridge.awaitReceiveReady(any()) } returns true
        val hints = slot<AlarmRingHints>()
        every { env.wearBridge.sendAlarmFired(1L, capture(hints)) } just Runs

        env.ring(1L)
        advanceUntilIdle()

        assertEquals(1, env.presented.size)
        assertTrue(hints.captured.supportsActionAck)
        assertNull(hints.captured.listSync)
    }

    @Test
    fun `supportsActionAck is false before unlock even when the receiver is ready`() = runTest {
        val env = newEnv(unlocked = false)
        every { env.bfuCache.getAll() } returns listOf(baseAlarm)
        coEvery { env.wearBridge.awaitReceiveReady(any()) } returns true
        val hints = slot<AlarmRingHints>()
        every { env.wearBridge.sendAlarmFired(1L, capture(hints)) } just Runs

        env.ring(1L)
        advanceUntilIdle()

        assertFalse(hints.captured.supportsActionAck)
        coVerify(exactly = 0) { env.settingsStore.snapshot() }
        coVerify(exactly = 0) { env.wearBridge.awaitReceiveReady(any()) }
    }

    @Test
    fun `supportsActionAck is false after unlock while the receiver is not ready`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns baseAlarm
        coEvery { env.settingsStore.snapshot() } returns SettingsState()
        coEvery { env.wearBridge.awaitReceiveReady(any()) } returns false
        val hints = slot<AlarmRingHints>()
        every { env.wearBridge.sendAlarmFired(1L, capture(hints)) } just Runs

        env.ring(1L)
        advanceUntilIdle()

        assertFalse(hints.captured.supportsActionAck)
    }

    // ---- round 2 ---------------------------------------------------------

    /*
     * Fails before: markCommitted set ringEnded = true and cancelled the timers
     * with no occurrence check, so a takeover that ran between the open check
     * and the main-thread hop left alarm 2 silent (isCurrent false) with its
     * auto-stop cancelled.
     */
    @Test
    fun `commit that finishes after a takeover does not end or silence the new ring`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } coAnswers {
            delay(1_000)
            baseAlarm
        }
        coEvery { env.repository.getById(2L) } returns baseAlarm.copy(id = 2L)
        env.action(dismissed, RingActionRequest(1L, true, 1_000L, 5L))
        runCurrent()

        env.ring(2L)
        advanceUntilIdle()

        assertEquals(listOf(2L), env.presented.map { it.first })
        assertEquals(2L, env.read("currentAlarmId"))
        assertFalse(env.read("ringEnded") as Boolean)
        verify(exactly = 1) { env.wearBridge.sendPeerActionAck(1L, "alarm_dismissed", 5L, 1_000L, true) }
        assertTrue("alarm 2 keeps its auto-stop", env.delayed.any { it.second == 300_000L })
        assertTrue("no grace teardown for the old occurrence", env.delayed.none { it.second == 6_000L })
    }

    /*
     * Fails before: the finalization ran inside the new ring's job, so the
     * third alarm cancelled the second's job before it ran and alarm 1 never
     * got its dismiss policy (setEnabledLocalOnly(1) was called 0 times).
     */
    @Test
    fun `three quick takeovers still finalize every superseded alarm`() = runTest {
        val env = newEnv()
        for (id in 1L..3L) coEvery { env.repository.getById(id) } returns baseAlarm.copy(id = id)
        env.ring(1L)
        runCurrent()

        env.ring(2L)
        env.ring(3L)
        advanceUntilIdle()

        coVerify(exactly = 1) { env.repository.setEnabledLocalOnly(1L, false) }
        coVerify(exactly = 1) { env.repository.setEnabledLocalOnly(2L, false) }
        coVerify(exactly = 0) { env.repository.setEnabledLocalOnly(3L, false) }
        assertEquals(3L, env.presented.last().first)
    }

    /*
     * Fails before: runRing awaited finishSupersededRing, which waits for the
     * mutex / the stalled save of alarm 1, so alarm 2 never presented.
     */
    @Test
    fun `a stalled save of the old alarm never delays the new ring`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns baseAlarm
        coEvery { env.repository.getById(2L) } returns baseAlarm.copy(id = 2L)
        env.ring(1L)
        runCurrent()
        coEvery { env.repository.getById(1L) } coAnswers { awaitCancellation() }

        env.ring(2L)
        runCurrent()

        assertTrue(env.presented.any { it.first == 2L })
        assertEquals(0L, currentTime)
    }

    /*
     * Fails before: a failed WATCH action emitted no ActionFailed (a phone tap
     * ignored while it was in flight stayed on "waiting for watch") and did not
     * re-arm the auto-stop that had already run and been ignored.
     */
    @Test
    fun `failed watch action tells the Activity and re-arms the auto-stop`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } throws IOException("disk full")
        val failures = mutableListOf<RingEndedSignal.Event>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            RingEndedSignal.actionFailures.collect { failures += it }
        }

        env.action(dismissed, RingActionRequest(1L, true, 1_000L, 5L))
        advanceUntilIdle()

        verify(exactly = 1) { env.wearBridge.sendPeerActionAck(1L, "alarm_dismissed", 5L, 1_000L, false) }
        assertEquals(listOf(RingEndedSignal.Event(1L, 1_000L)), failures)
        assertTrue(env.delayed.any { it.second == 30_000L })
    }

    /*
     * Fails before: actionFailures had replay = 0, so a failure while the
     * Activity was stopped was lost.
     */
    @Test
    fun `action failure is replayed to a collector that starts later`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } throws IOException("disk full")

        env.action(dismissed, RingActionRequest(1L, fromPeer = false, firedAtEpoch = 1_000L))
        advanceUntilIdle()

        assertEquals(listOf(RingEndedSignal.Event(1L, 1_000L)), RingEndedSignal.actionFailures.replayCache)
    }

    /*
     * Fails before: a failing clearSnoozedUntil threw out of the ring job and
     * the broad catch tore the ring down (silent alarm).
     */
    @Test
    fun `failure while clearing the consumed snooze still rings`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns baseAlarm.copy(snoozedUntilEpoch = 5L)
        coEvery { env.repository.clearSnoozedUntil(1L) } throws IOException("disk full")

        env.ring(1L)
        advanceUntilIdle()

        assertEquals(1, env.presented.size)
    }

    /*
     * Fails before: there was no retained current occurrence (and the replayed
     * event of round 2 kept only the last takeover), so a stopped Activity
     * could not follow two takeovers.
     */
    @Test
    fun `the retained current occurrence follows every takeover and is cleared on teardown`() = runTest {
        val env = newEnv()
        for (id in 1L..3L) coEvery { env.repository.getById(id) } returns baseAlarm.copy(id = id)
        env.ring(1L)
        env.ring(2L)
        env.ring(3L)
        advanceUntilIdle()
        val third = env.firedAt()

        assertEquals(RingEndedSignal.Event(3L, third), RingEndedSignal.current.value)

        env.invoke("stopForegroundAndSelf", 2L, third - 1)
        assertEquals("a stale teardown clears nothing", RingEndedSignal.Event(3L, third), RingEndedSignal.current.value)
        env.invoke("stopForegroundAndSelf", 3L, third)
        assertNull(RingEndedSignal.current.value)
    }

    /*
     * Fails before: one global mutex. Alarm 1's finalization hung while holding
     * it, so a dismiss for alarm 2 waited forever and setEnabled(2) was never
     * called.
     */
    @Test
    fun `a stalled save of alarm A does not block a dismiss of alarm B`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns baseAlarm
        coEvery { env.repository.getById(2L) } returns baseAlarm.copy(id = 2L)
        env.ring(1L)
        runCurrent()
        coEvery { env.repository.getById(1L) } coAnswers { awaitCancellation() }
        env.ring(2L)
        runCurrent()

        env.action(dismissed, RingActionRequest(2L, fromPeer = false, firedAtEpoch = env.firedAt()))
        advanceUntilIdle()

        coVerify(exactly = 1) { env.repository.setEnabled(2L, false) }
        assertTrue(env.read("ringEnded") as Boolean)
    }

    /*
     * Fails before: with an action in flight the auto-stop was refused and no
     * timer re-armed, so the ring (and its tone) never ended.
     */
    @Test
    fun `auto-stop silences a ring whose action is stuck, retries, then ends it without saving`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(2L) } returns baseAlarm.copy(id = 2L)
        env.ring(2L)
        advanceUntilIdle()
        val audio = mockk<AlarmAudioPlayer>(relaxed = true)
        env.write("player", audio)
        coEvery { env.repository.getById(2L) } coAnswers { awaitCancellation() }
        env.action(dismissed, RingActionRequest(2L, true, env.firedAt(), 5L))
        runCurrent()

        env.fire(300_000L)
        verify(exactly = 1) { audio.stop() }
        assertTrue(env.delayed.any { it.second == 30_000L })
        env.fire(30_000L)
        env.fire(60_000L)
        assertFalse("still retrying", env.read("ringEnded") as Boolean)
        env.fire(120_000L)

        assertTrue("gave up: ring ended without saving", env.read("ringEnded") as Boolean)
        assertTrue(env.delayed.any { it.second == 6_000L })
    }

    /*
     * Fails before: giveUpAutoStop left the stalled watch dismiss's claim in
     * flight, so the policy returned Ignore (no reply) for the watch's retry.
     */
    @Test
    fun `watch retry after the auto-stop gave up on a stalled dismiss gets the peer-ended reply`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(2L) } returns baseAlarm.copy(id = 2L)
        env.ring(2L)
        advanceUntilIdle()
        val firedAt = env.firedAt()
        coEvery { env.repository.getById(2L) } coAnswers { awaitCancellation() }
        env.action(dismissed, RingActionRequest(2L, true, firedAt, 5L))
        runCurrent()
        env.fire(300_000L)
        env.fire(30_000L)
        env.fire(60_000L)
        env.fire(120_000L)
        assertTrue("gave up: ring ended without saving", env.read("ringEnded") as Boolean)

        env.action(dismissed, RingActionRequest(2L, true, firedAt, 5L))
        runCurrent()

        verify(exactly = 1) { env.wearBridge.sendPeerEnded(2L, "alarm_dismissed", firedAt) }
    }

    /*
     * Fails before: scheduleAt swallowed a refused alarm; the record stayed and
     * the reply was ok:true. scheduleAtChecked=false must roll the record back
     * and fail the action.
     */
    @Test
    fun `pre-unlock snooze that AlarmManager refuses is rolled back and fails`() = runTest {
        val env = newEnv(unlocked = false)
        env.setRing(1L, 1_000L)
        every { env.bfuCache.getAll() } returns listOf(baseAlarm.copy(snoozedUntilEpoch = 77L))
        coEvery { env.bfuCache.recordPreUnlockSnooze(1L, any()) } returns true
        every { env.scheduler.scheduleAtChecked(any(), any()) } returns false

        env.action(snoozed, RingActionRequest(1L, true, 1_000L, 7L))
        advanceUntilIdle()

        // Restores the previous trigger and removes exactly one bump; the old
        // pair (setSnoozedUntil(null) + dropPendingSnoozeBumps) erased the
        // counts of earlier successful snoozes.
        coVerify(exactly = 1) { env.bfuCache.undoPreUnlockSnooze(1L, 77L) }
        coVerify(exactly = 0) { env.bfuCache.dropPendingSnoozeBumps(any()) }
        coVerify(exactly = 0) { env.bfuCache.setSnoozedUntil(any(), any()) }
        verify(exactly = 1) { env.wearBridge.sendPeerActionAck(1L, "alarm_snoozed", 7L, 1_000L, false) }
        assertFalse(env.read("ringEnded") as Boolean)
    }

    /*
     * Fails before: the scheduler swallows the SecurityException of
     * setAlarmClock, so a snooze that could not be armed replied ok:true.
     */
    @Test
    fun `snooze that cannot be armed fails before any mutation, for phone and watch`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } returns baseAlarm
        every { env.scheduler.canScheduleExact() } returns false
        val failures = mutableListOf<RingEndedSignal.Event>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            RingEndedSignal.actionFailures.collect { failures += it }
        }

        env.action(snoozed, RingActionRequest(1L, fromPeer = false, firedAtEpoch = 1_000L))
        advanceUntilIdle()
        env.action(snoozed, RingActionRequest(1L, true, 1_000L, 7L))
        advanceUntilIdle()

        coVerify(exactly = 0) { env.repository.snoozeAt(any(), any(), any()) }
        verify(exactly = 1) { env.wearBridge.sendPeerActionAck(1L, "alarm_snoozed", 7L, 1_000L, false) }
        assertEquals(2, failures.size)
        assertFalse(env.read("ringEnded") as Boolean)
    }

    @Test
    fun `pre-unlock snooze that cannot be armed records nothing`() = runTest {
        val env = newEnv(unlocked = false)
        env.setRing(1L, 1_000L)
        every { env.bfuCache.getAll() } returns listOf(baseAlarm)
        every { env.scheduler.canScheduleExact() } returns false

        env.action(snoozed, RingActionRequest(1L, fromPeer = false, firedAtEpoch = 1_000L))
        advanceUntilIdle()

        coVerify(exactly = 0) { env.bfuCache.recordPreUnlockSnooze(any(), any()) }
        verify(exactly = 0) { env.scheduler.scheduleAtChecked(any(), any()) }
    }

    // ---- round 4 ---------------------------------------------------------

    /*
     * Fails before: the auto-stop's own save could hang with no timer armed
     * (its runnable had cleared autoStopRunnable), so no 30 s runnable existed
     * and the ring never ended.
     */
    @Test
    fun `auto-stop whose own save hangs still ends the ring on the retry schedule`() = runTest {
        val env = newEnv()
        coEvery { env.repository.getById(1L) } returns baseAlarm
        env.ring(1L)
        advanceUntilIdle()
        coEvery { env.repository.getById(1L) } coAnswers { awaitCancellation() }

        env.fire(300_000L)
        assertTrue("watchdog armed at the claim", env.delayed.any { it.second == 30_000L })
        env.fire(30_000L)
        env.fire(60_000L)
        assertFalse("still within the retries", env.read("ringEnded") as Boolean)
        env.fire(120_000L)

        assertTrue(env.read("ringEnded") as Boolean)
    }

    /*
     * Fails before: resetSnoozeCounter ran BEFORE the disable, so a failing
     * disable left the counter reset behind an ok:false.
     */
    @Test
    fun `failed disable leaves the snooze counter untouched`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } returns baseAlarm
        coEvery { env.repository.setEnabled(1L, false) } throws IOException("disk full")

        env.action(dismissed, RingActionRequest(1L, fromPeer = false, firedAtEpoch = 1_000L))
        advanceUntilIdle()

        coVerify(exactly = 0) { env.repository.resetSnoozeCounter(any()) }
        assertFalse(env.read("ringEnded") as Boolean)
    }

    /*
     * Fails before: a failing counter reset (a step AFTER the mutation) turned
     * a committed dismiss into ok:false.
     */
    @Test
    fun `committed dismiss stays ok true when the best-effort counter reset fails`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        coEvery { env.repository.getById(1L) } returns baseAlarm
        coEvery { env.repository.resetSnoozeCounter(1L) } throws IOException("disk full")

        env.action(dismissed, RingActionRequest(1L, true, 1_000L, 5L))
        advanceUntilIdle()

        coVerify(exactly = 1) { env.repository.setEnabledLocalOnly(1L, false) }
        verify(exactly = 1) { env.wearBridge.sendPeerActionAck(1L, "alarm_dismissed", 5L, 1_000L, true) }
        verify(exactly = 0) { env.wearBridge.sendPeerActionAck(any(), any(), any(), any(), false) }
    }

    // ---- R12 catch-up ----------------------------------------------------

    /*
     * Fails before: the loop was guarded by the shared ringEnded flag, which a
     * NEWER ring resets to false, so a superseded ring kept re-sending.
     */
    @Test
    fun `catch-up for a superseded occurrence sends nothing`() = runTest {
        val env = newEnv()
        env.setRing(1L, 1_000L)
        val hints = AlarmRingHints.from(baseAlarm)

        env.invokeSuspend("runWatchCatchUp", 1L, 999L, hints, Long.MAX_VALUE / 2)

        coVerify(exactly = 0) { env.wearBridge.sendAlarmFiredAwaiting(any(), any(), any()) }
    }

    // ---- teardown --------------------------------------------------------

    @Test
    fun `teardown of an old occurrence leaves a newer ring of the same alarm active`() = runTest {
        val env = newEnv()
        env.setRing(1L, 2_000L)
        val audio = mockk<AlarmAudioPlayer>(relaxed = true)
        env.write("player", audio)

        env.invoke("stopForegroundAndSelf", 1L, 1_000L)

        assertFalse(env.read("ringEnded") as Boolean)
        assertSame(audio, env.read("player"))
        assertEquals(1L, env.read("currentAlarmId"))
    }

    // ---- harness ---------------------------------------------------------

    private fun TestScope.newEnv(unlocked: Boolean = true) = Env(this, unlocked)

    private inner class Env(private val testScope: TestScope, unlocked: Boolean) {
        val repository = mockk<AlarmRepository>(relaxed = true)
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        val scheduler = mockk<AlarmScheduler>(relaxed = true)
        val wearBridge = mockk<WearBridgeService>(relaxed = true)
        val bfuCache = mockk<BfuAlarmCache>(relaxed = true)
        private val handler = mockk<Handler>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScope.testScheduler))

        /** Delayed runnables posted on the mocked main Handler: (runnable, delayMs). */
        val delayed = mutableListOf<Pair<Runnable, Long>>()

        /** (alarmId, firedAt) of every ring the presenter seam was asked to start. */
        val presented = mutableListOf<Pair<Long, Long>>()
        val service: AlarmRingService

        init {
            every { handler.post(any()) } answers {
                firstArg<Runnable>().run()
                true
            }
            every { handler.postDelayed(any<Runnable>(), any<Long>()) } answers {
                delayed += firstArg<Runnable>() to secondArg<Long>()
                true
            }
            every { handler.removeCallbacks(any<Runnable>()) } answers {
                val target = firstArg<Runnable>()
                delayed.removeAll { it.first === target }
                Unit
            }
            every { handler.removeCallbacksAndMessages(any()) } answers {
                delayed.clear()
                Unit
            }
            every { wearBridge.pairedDeviceInfo } returns MutableStateFlow<PairedDeviceInfo?>(null)
            coEvery { settingsStore.snapshot() } returns SettingsState()
            every { scheduler.canScheduleExact() } returns true
            every { scheduler.scheduleAtChecked(any(), any()) } returns true
            RingEndedSignal.resetForTest()

            service = allocate()
            write("repository", repository)
            write("settingsStore", settingsStore)
            write("scheduler", scheduler)
            write("wearBridge", wearBridge)
            write("bfuCache", bfuCache)
            write("ioDispatcher", StandardTestDispatcher(testScope.testScheduler))
            write("serviceScope\$delegate", lazyOf(scope))
            write("mainHandler", handler)
            write("alarmMutexes", java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.sync.Mutex>())
            write("peerState", PeerActionState())
            write("unlockedProbe", { unlocked })
            write("foregroundStarter", { _: Alarm, _: Long -> })
            write("ringPresenter", { alarm: Alarm, _: String?, firedAt: Long -> presented += alarm.id to firedAt })
            write("currentAlarmId", -1L)
            write("currentFiredAtEpoch", -1L)
        }

        fun watchConnected() {
            every { wearBridge.pairedDeviceInfo } returns
                MutableStateFlow<PairedDeviceInfo?>(PairedDeviceInfo("Watch", true))
        }

        fun setRing(alarmId: Long, firedAt: Long) {
            write("currentAlarmId", alarmId)
            write("currentFiredAtEpoch", firedAt)
            write("ringEnded", false)
            write("ringActionStarted", false)
            write("localStopRequested", false)
        }

        fun firedAt(): Long = read("currentFiredAtEpoch") as Long

        fun peerState(): PeerActionState = read("peerState") as PeerActionState

        fun ring(alarmId: Long) {
            invoke("handleRing", alarmId)
        }

        fun action(kind: RingActionKind, request: RingActionRequest) {
            invoke("handleAction", kind, request)
        }

        /** Runs the delayed runnable posted with [delayMs] and lets the follow-up work finish. */
        fun fire(delayMs: Long) {
            val entry = delayed.first { it.second == delayMs }
            delayed.remove(entry)
            entry.first.run()
            testScope.advanceUntilIdle()
        }

        fun read(name: String): Any? = readField(service, name)

        fun write(name: String, value: Any?) = setField(service, name, value)

        fun invoke(name: String, vararg args: Any?): Any? = privateMethod(name).invoke(service, *args)

        suspend fun invokeSuspend(name: String, vararg args: Any?): Any? =
            invokePrivateSuspend(service, name, *args)
    }

    private fun allocate(): AlarmRingService {
        // Avoid Android's Handler/Looper/Context constructors while executing
        // the real service methods rather than relaxed mock implementations.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, AlarmRingService::class.java) as AlarmRingService
    }

    private fun setField(target: Any, name: String, value: Any?) {
        AlarmRingService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun readField(target: Any, name: String): Any? =
        AlarmRingService::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(target)
        }

    private suspend fun invokePrivateSuspend(target: Any, name: String, vararg args: Any?): Any? =
        suspendCoroutine { continuation ->
            try {
                val result = privateMethod(name).invoke(target, *args, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    private fun privateMethod(name: String): Method =
        AlarmRingService::class.java.declaredMethods.single { it.name == name }.apply {
            isAccessible = true
        }

}
