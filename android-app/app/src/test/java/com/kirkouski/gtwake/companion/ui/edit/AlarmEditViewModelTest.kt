package com.kirkouski.gtwake.companion.ui.edit

import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.SettingsState
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the two independent self-destruct drafts introduced in 1.0.8.
 *
 * `Alarm.selfDestruct` is a single persisted boolean, but the edit screen
 * exposes it with opposite polarity and opposite defaults per mode:
 * ABSOLUTE asks "Delete after firing" (default off), RELATIVE asks
 * "Don't delete after firing" (default off, i.e. it does delete). The
 * defaults, the independence across tab switches, and the collapse back
 * onto the persisted field are exactly what regresses silently, so they
 * are asserted here rather than left to manual QA.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: AlarmRepository
    private lateinit var settingsStore: SettingsStore

    @Before
    fun setUp() {
        // viewModelScope is hard-wired to Dispatchers.Main; without this the
        // load() coroutine never runs and every state read sees loaded=false.
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        settingsStore = mockk(relaxed = true)
        every { settingsStore.state } returns MutableStateFlow(SettingsState())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AlarmEditViewModel(repository, settingsStore)

    /** Drives the VM through save() and returns the Alarm handed to the repo. */
    private suspend fun savedAlarm(vm: AlarmEditViewModel): Alarm {
        val slot = mutableListOf<Alarm>()
        coEvery { repository.saveLocalOnly(capture(slot)) } returns 1L
        vm.save()
        return slot.single()
    }

    @Test
    fun `new dated alarm defaults to NOT deleting after firing`() = runTest {
        val vm = viewModel()
        vm.load(id = null, initialMode = AlarmMode.ABSOLUTE)

        assertFalse(vm.state.value.absoluteSelfDestruct)
        assertFalse(savedAlarm(vm).selfDestruct)
    }

    @Test
    fun `new timer defaults to deleting after firing`() = runTest {
        val vm = viewModel()
        vm.load(id = null, initialMode = AlarmMode.RELATIVE)

        // The switch reads "Don't delete after firing" and is OFF...
        assertFalse(vm.state.value.timerKeepAfterFiring)
        // ...which persists as selfDestruct = true (inverted polarity).
        assertTrue(savedAlarm(vm).selfDestruct)
    }

    @Test
    fun `timer keep-after-firing ON persists as selfDestruct false`() = runTest {
        val vm = viewModel()
        vm.load(id = null, initialMode = AlarmMode.RELATIVE)
        vm.toggleSelfDestruct()

        assertTrue(vm.state.value.timerKeepAfterFiring)
        assertFalse(savedAlarm(vm).selfDestruct)
    }

    @Test
    fun `switching tabs preserves each mode's own draft independently`() = runTest {
        val vm = viewModel()
        vm.load(id = null, initialMode = AlarmMode.ABSOLUTE)

        // Turn the dated alarm's "Delete after firing" ON.
        vm.toggleSelfDestruct()
        assertTrue(vm.state.value.absoluteSelfDestruct)

        // Hop to the Timer tab: the timer draft must still be at ITS default,
        // untouched by the absolute toggle.
        vm.updateMode(AlarmMode.RELATIVE)
        assertFalse(vm.state.value.timerKeepAfterFiring)
        assertTrue(vm.state.value.absoluteSelfDestruct)

        // Flip the timer's toggle, hop back: the absolute value survived.
        vm.toggleSelfDestruct()
        vm.updateMode(AlarmMode.ABSOLUTE)
        assertTrue(vm.state.value.absoluteSelfDestruct)
        assertTrue(vm.state.value.timerKeepAfterFiring)
        assertTrue(savedAlarm(vm).selfDestruct)
    }

    @Test
    fun `loading a relative alarm inverts selfDestruct into the timer draft`() = runTest {
        coEvery { repository.getById(7L) } returns Alarm(
            id = 7L,
            relativeMinutes = 20,
            selfDestruct = false,
            updatedAtEpoch = 1L,
        )
        val vm = viewModel()
        vm.load(id = 7L)

        assertEquals(AlarmMode.RELATIVE, vm.state.value.mode)
        assertTrue(vm.state.value.timerKeepAfterFiring)
        // The other mode's draft starts at its own default, not a mirror.
        assertFalse(vm.state.value.absoluteSelfDestruct)
    }

    @Test
    fun `loading a dated alarm reads selfDestruct into the absolute draft`() = runTest {
        coEvery { repository.getById(3L) } returns Alarm(
            id = 3L,
            selfDestruct = true,
            updatedAtEpoch = 1L,
        )
        val vm = viewModel()
        vm.load(id = 3L)

        assertTrue(vm.state.value.absoluteSelfDestruct)
        assertFalse(vm.state.value.timerKeepAfterFiring)
    }

    /**
     * The discriminating case for the "not a mirror" rule.
     *
     * The two load tests above happen to use fixtures where the correct
     * asymmetric branch and a naive mirror
     * (`timerKeepAfterFiring = !alarm.selfDestruct` unconditionally) agree, so
     * neither of them pins the rule. This one forks: a dated alarm with
     * selfDestruct=false loaded, then switched to the Timer tab, must save as
     * selfDestruct=true (the timer's OWN default — it deletes itself). Under a
     * mirror the timer draft would inherit keep=true and save false instead.
     */
    @Test
    fun `existing dated alarm switched to Timer adopts the timer default, not a mirror`() = runTest {
        coEvery { repository.getById(11L) } returns Alarm(
            id = 11L,
            selfDestruct = false,
            updatedAtEpoch = 1L,
        )
        val vm = viewModel()
        vm.load(id = 11L)
        assertFalse(vm.state.value.absoluteSelfDestruct)
        // Mirror would have set this to true; the asymmetric branch leaves it false.
        assertFalse(vm.state.value.timerKeepAfterFiring)

        vm.updateMode(AlarmMode.RELATIVE)

        assertTrue("timer must self-destruct by default", savedAlarm(vm).selfDestruct)
    }

    @Test
    fun `existing timer switched to Alarm adopts the dated default, not a mirror`() = runTest {
        coEvery { repository.getById(12L) } returns Alarm(
            id = 12L,
            relativeMinutes = 20,
            selfDestruct = true,
            updatedAtEpoch = 1L,
        )
        val vm = viewModel()
        vm.load(id = 12L)
        assertFalse(vm.state.value.timerKeepAfterFiring)
        // Mirror would have set this to true; the asymmetric branch leaves it false.
        assertFalse(vm.state.value.absoluteSelfDestruct)

        vm.updateMode(AlarmMode.ABSOLUTE)

        assertFalse("dated alarm must be kept by default", savedAlarm(vm).selfDestruct)
    }

    @Test
    fun `selecting days masks self-destruct off but keeps the draft`() = runTest {
        val vm = viewModel()
        vm.load(id = null, initialMode = AlarmMode.ABSOLUTE)
        vm.toggleSelfDestruct()
        vm.toggleDay(DaysOfWeek.MON)

        // Draft is preserved (the row is hidden, not reset)...
        assertTrue(vm.state.value.absoluteSelfDestruct)
        // ...but buildAlarm masks it off so Alarm.init's invariant holds.
        val saved = savedAlarm(vm)
        assertFalse(saved.selfDestruct)
        assertEquals(DaysOfWeek.MON, saved.daysOfWeek)
    }

    @Test
    fun `clearing all days restores the preserved self-destruct draft`() = runTest {
        val vm = viewModel()
        vm.load(id = null, initialMode = AlarmMode.ABSOLUTE)
        vm.toggleSelfDestruct()
        vm.toggleDay(DaysOfWeek.MON)
        vm.toggleDay(DaysOfWeek.MON)

        assertEquals(DaysOfWeek.NONE, vm.state.value.daysOfWeek)
        assertTrue(savedAlarm(vm).selfDestruct)
    }
}
