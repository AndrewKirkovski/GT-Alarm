package com.kirkouski.gtwake.companion.scheduler

import com.kirkouski.gtwake.companion.domain.Alarm

interface AlarmScheduler {
    fun schedule(alarm: Alarm)
    fun scheduleAt(alarm: Alarm, triggerAtMillis: Long)
    fun cancel(alarmId: Long)
    fun rescheduleAll(alarms: List<Alarm>)
    fun canScheduleExact(): Boolean
    fun nextTriggerMillis(alarm: Alarm): Long
}
