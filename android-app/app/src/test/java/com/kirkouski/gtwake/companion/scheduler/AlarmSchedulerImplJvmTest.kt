package com.kirkouski.gtwake.companion.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit test for [AlarmSchedulerImpl].
 *
 * Uses mockk + `mockkStatic(PendingIntent::class)` to verify the production
 * code calls `setAlarmClock(AlarmClockInfo(triggerEpoch, showIntent), firePI)`
 * and reuses `requestCode = alarm.id.toInt()` for both the fire-PI on schedule
 * and the lookup-PI on cancel. Pairs with the instrumented test
 * `AlarmSchedulerImplTest` in `androidTest/` (which exercises the real
 * AlarmManager round-trip on a device).
 *
 * Relies on `testOptions.unitTests.isReturnDefaultValues = true` so static
 * `Log.d/w` calls inside the impl don't throw.
 */
class AlarmSchedulerImplJvmTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: AlarmSchedulerImpl

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        alarmManager = mockk(relaxed = true)
        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        every { context.packageName } returns "com.kirkouski.gtwake.companion"

        // PendingIntent.getBroadcast / getActivity are static — wrap so
        // the impl gets back a deterministic mock instead of a no-op null.
        mockkStatic(PendingIntent::class)
        every {
            PendingIntent.getBroadcast(any(), any(), any(), any())
        } answers {
            // Return a non-null PendingIntent mock so the impl proceeds
            // past the null guard in scheduleAt.
            mockk(relaxed = true)
        }
        every {
            PendingIntent.getActivity(any(), any(), any(), any())
        } returns mockk(relaxed = true)

        scheduler = AlarmSchedulerImpl(context)
    }

    @After
    fun tearDown() {
        unmockkStatic(PendingIntent::class)
    }

    @Test
    fun `schedule on enabled alarm calls setAlarmClock once with non-null AlarmClockInfo and fire PI`() {
        val alarm = Alarm(id = 42L, hour = 7, minute = 30, daysOfWeek = DaysOfWeek.NONE, enabled = true)

        scheduler.schedule(alarm)

        val infoSlot = slot<AlarmManager.AlarmClockInfo>()
        val piSlot = slot<PendingIntent>()
        verify(exactly = 1) {
            alarmManager.setAlarmClock(capture(infoSlot), capture(piSlot))
        }
        // Under JVM stubbed-Android (`isReturnDefaultValues=true`),
        // AlarmClockInfo's *getters* return defaults regardless of what was
        // passed to the constructor — so we verify CONSTRUCTION happened
        // (non-null capture) and verify the `PendingIntent.getActivity`
        // call separately for the show-intent contract.
        assertNotNull("setAlarmClock must receive a non-null AlarmClockInfo", infoSlot.captured)
        assertNotNull("setAlarmClock must receive a non-null fire PendingIntent", piSlot.captured)
        // Show intent is constructed via PendingIntent.getActivity exactly
        // once — this is the AlarmClockInfo's second arg.
        verify(exactly = 1) {
            PendingIntent.getActivity(any(), any(), any(), any())
        }
    }

    @Test
    fun `schedule uses requestCode equal to alarm id (Int) for fire PendingIntent`() {
        val alarm = Alarm(id = 1234L, hour = 9, minute = 0, daysOfWeek = DaysOfWeek.NONE, enabled = true)

        scheduler.schedule(alarm)

        // requestCode must equal alarm.id.toInt(). Don't match on Intent
        // action — under stubbed Android, Intent getters return null even
        // after setAction(). We only own the requestCode contract here.
        verify(exactly = 1) {
            PendingIntent.getBroadcast(
                any(),
                eq(1234), // alarm.id.toInt()
                any(),
                any(),
            )
        }
    }

    @Test
    fun `scheduleAt with explicit trigger calls setAlarmClock exactly once`() {
        val alarm = Alarm(id = 7L, hour = 6, minute = 0, daysOfWeek = DaysOfWeek.MON, enabled = true)
        val explicitTrigger = System.currentTimeMillis() + 60_000L

        scheduler.scheduleAt(alarm, explicitTrigger)

        // Under stubbed Android, AlarmClockInfo.triggerTime getter returns 0
        // regardless of constructor arg. Asserting on it is meaningless. We
        // verify exactly-once construction and rely on the instrumented
        // test in androidTest/ to round-trip the actual epoch.
        verify(exactly = 1) { alarmManager.setAlarmClock(any(), any()) }
    }

    @Test
    fun `schedule on disabled alarm does NOT call setAlarmClock`() {
        val alarm = Alarm(id = 99L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.MON, enabled = false)

        scheduler.schedule(alarm)

        verify(exactly = 0) { alarmManager.setAlarmClock(any(), any()) }
    }

    @Test
    fun `cancel calls AlarmManager cancel with the same requestCode the alarm was scheduled with`() {
        val alarm = Alarm(id = 555L, hour = 8, minute = 0, daysOfWeek = DaysOfWeek.NONE, enabled = true)

        scheduler.cancel(alarm.id)

        // cancel only resolves the existing PI via FLAG_NO_CREATE; it never
        // talks to alarmManager.cancel() if the lookup returns null. We mock
        // getBroadcast to always return non-null so the cancel branch fires.
        verify {
            PendingIntent.getBroadcast(
                any(),
                eq(555), // same requestCode = alarm.id.toInt() as schedule
                any(),
                any(),
            )
        }
        verify(exactly = 1) { alarmManager.cancel(any<PendingIntent>()) }
    }

    @Test
    fun `rescheduleAll schedules every alarm in the list`() {
        val alarms = listOf(
            Alarm(id = 1L, hour = 6, minute = 0, daysOfWeek = DaysOfWeek.NONE, enabled = true),
            Alarm(id = 2L, hour = 7, minute = 0, daysOfWeek = DaysOfWeek.NONE, enabled = true),
            Alarm(id = 3L, hour = 8, minute = 0, daysOfWeek = DaysOfWeek.NONE, enabled = true),
        )

        scheduler.rescheduleAll(alarms)

        verify(exactly = 3) { alarmManager.setAlarmClock(any(), any()) }
    }

    @Test
    fun `nextTriggerMillis returns a strictly future epoch`() {
        val alarm = Alarm(id = 1L, hour = 10, minute = 0, daysOfWeek = DaysOfWeek.NONE, enabled = true)
        val trigger = scheduler.nextTriggerMillis(alarm)
        assertTrue(
            "nextTriggerMillis must be strictly future, got $trigger",
            trigger > System.currentTimeMillis(),
        )
    }
}
