package com.kirkouski.gtwake.companion.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.DaysOfWeek
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AlarmStatusNotifierTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val alarm = Alarm(id = 7L, hour = 7, minute = 30, daysOfWeek = DaysOfWeek.MON, enabled = true)

    private lateinit var prefs: SharedPreferences
    private lateinit var alarmManager: AlarmManager
    private lateinit var userManager: UserManager
    private lateinit var notifier: AlarmStatusNotifier

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        every { prefs.getInt(any(), any()) } answers { secondArg() }
        alarmManager = mockk(relaxed = true)
        userManager = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.createDeviceProtectedStorageContext().getSharedPreferences(any(), any()) } returns prefs
        every { context.getSystemService(AlarmManager::class.java) } returns alarmManager
        every { context.getSystemService(UserManager::class.java) } returns userManager
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)
        notifier = AlarmStatusNotifier(context)
    }

    @After
    fun tearDown() {
        unmockkStatic(PendingIntent::class)
    }

    @Test
    fun `an absent setting reads as 30 minutes`() {
        assertEquals(30, notifier.upcomingLeadMinutes)
    }

    @Test
    fun `no notice is armed before the first unlock`() {
        every { userManager.isUserUnlocked } returns false

        notifier.armUpcoming(alarm, System.currentTimeMillis() + 120 * minute)

        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `after unlock the notice is armed the lead before the trigger`() {
        every { userManager.isUserUnlocked } returns true
        val trigger = System.currentTimeMillis() + 120 * minute

        notifier.armUpcoming(alarm, trigger)

        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger - 30 * minute, any())
        }
    }

    @Test
    fun `the notice is due the lead before the trigger`() {
        assertEquals(now + 30 * minute, upcomingNoticeAt(alarm, now + 60 * minute, 30, now))
    }

    @Test
    fun `Off gives no notice`() {
        assertNull(upcomingNoticeAt(alarm, now + 60 * minute, 0, now))
    }

    @Test
    fun `a timer gets no notice`() {
        val timer = Alarm.newTimer(minutes = 120, snoozeMinutes = 0, maxSnoozeCount = 0)
        assertNull(upcomingNoticeAt(timer, now + 120 * minute, 30, now))
    }

    @Test
    fun `a snoozed alarm gets no notice`() {
        val snoozed = alarm.copy(snoozedUntilEpoch = now + 60 * minute)
        assertNull(upcomingNoticeAt(snoozed, now + 60 * minute, 30, now))
    }

    @Test
    fun `a lead that has already passed gives no notice`() {
        assertNull(upcomingNoticeAt(alarm, now + 20 * minute, 30, now))
    }
}
