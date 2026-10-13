package com.kirkouski.gtwake.companion.scheduler

import com.kirkouski.gtwake.companion.domain.Alarm

interface AlarmScheduler {
    fun schedule(alarm: Alarm)
    fun scheduleAt(alarm: Alarm, triggerAtMillis: Long)

    /** Like [scheduleAt] but reports whether the trigger was actually armed. */
    fun scheduleAtChecked(alarm: Alarm, triggerAtMillis: Long): Boolean {
        scheduleAt(alarm, triggerAtMillis)
        return true
    }
    fun cancel(alarmId: Long)
    fun rescheduleAll(alarms: List<Alarm>)
    fun canScheduleExact(): Boolean
    fun nextTriggerMillis(alarm: Alarm): Long
}
