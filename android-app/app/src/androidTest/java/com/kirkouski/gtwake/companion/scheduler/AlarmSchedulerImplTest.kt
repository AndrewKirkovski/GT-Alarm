package com.kirkouski.gtwake.companion.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSchedulerImplTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scheduler = AlarmSchedulerImpl(context)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Test
    fun schedule_thenCancel_invalidatesPendingIntent() {
        val alarm = Alarm(id = 100_001L, hour = 23, minute = 59, daysOfWeek = DaysOfWeek.NONE, enabled = true)

        scheduler.schedule(alarm)

        // After schedule, FLAG_NO_CREATE must find the registered PI.
        val afterSchedule = lookupExistingPendingIntent(alarm.id)
        assertNotNull("schedule must register a PendingIntent for the alarm id", afterSchedule)

        scheduler.cancel(alarm.id)

        // After cancel, the same lookup must return null.
        val afterCancel = lookupExistingPendingIntent(alarm.id)
        assertNull("cancel must invalidate the PendingIntent", afterCancel)
    }

    @Test
    fun schedule_onDisabledAlarm_doesNotRegister() {
        val alarm = Alarm(id = 100_002L, hour = 8, minute = 0, daysOfWeek = DaysOfWeek.MON, enabled = false)
        scheduler.schedule(alarm)
        assertNull("disabled alarms must not register a PI", lookupExistingPendingIntent(alarm.id))
    }

    @Test
    fun rescheduleAll_registersEachEnabledAlarm() {
        val a1 = Alarm(id = 100_010L, hour = 6, minute = 0, daysOfWeek = DaysOfWeek.WEEKDAYS, enabled = true)
        val a2 = Alarm(id = 100_011L, hour = 7, minute = 30, daysOfWeek = 0, enabled = true)

        scheduler.rescheduleAll(listOf(a1, a2))

        try {
            assertNotNull(lookupExistingPendingIntent(a1.id))
            assertNotNull(lookupExistingPendingIntent(a2.id))
        } finally {
            scheduler.cancel(a1.id)
            scheduler.cancel(a2.id)
        }
    }

    @Test
    fun canScheduleExact_returnsBooleanWithoutCrashing() {
        // Smoke — actual return value depends on test device's permission state.
        scheduler.canScheduleExact()
    }

    @Test
    fun nextTriggerMillis_returnsFutureInstant() {
        val alarm = Alarm(id = 100_003L, hour = 10, minute = 0, daysOfWeek = DaysOfWeek.NONE, enabled = true)
        val trigger = scheduler.nextTriggerMillis(alarm)
        assert(trigger > System.currentTimeMillis()) {
            "nextTriggerMillis must be strictly future, got $trigger vs now ${System.currentTimeMillis()}"
        }
    }

    private fun lookupExistingPendingIntent(alarmId: Long): PendingIntent? {
        val intent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = AlarmSchedulerImpl.ACTION_FIRE
            putExtra(AlarmSchedulerImpl.EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
