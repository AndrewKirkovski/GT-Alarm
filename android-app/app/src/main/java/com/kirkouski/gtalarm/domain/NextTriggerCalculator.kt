package com.kirkouski.gtalarm.domain

import java.time.ZoneId
import java.time.ZonedDateTime

object NextTriggerCalculator {
    fun nextTriggerEpochMillis(
        alarm: Alarm,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
    ): Long {
        val candidateToday = now
            .withHour(alarm.hour)
            .withMinute(alarm.minute)
            .withSecond(0)
            .withNano(0)

        if (alarm.daysOfWeek == DaysOfWeek.NONE) {
            val base = if (candidateToday.isAfter(now)) candidateToday else candidateToday.plusDays(1)
            return base.toInstant().toEpochMilli()
        }

        // reason: two `continue`s — one for "this day-of-week isn't in the
        // mask", one for "today's slot has already passed". Each guard is
        // an independent reject; collapsing them with `find { ... }` would
        // produce a single dense boolean that's harder to read and harder
        // to step through in a debugger. Detekt's
        // LoopWithTooManyJumpStatements is a heuristic, not a correctness
        // rule — the explicit form is intentional here.
        @Suppress("LoopWithTooManyJumpStatements")
        for (offset in 0..7) {
            val candidate = candidateToday.plusDays(offset.toLong())
            val mask = DaysOfWeek.fromJavaDayOfWeek(candidate.dayOfWeek)
            if (!DaysOfWeek.contains(alarm.daysOfWeek, mask)) continue
            if (offset == 0 && !candidate.isAfter(now)) continue
            return candidate.toInstant().toEpochMilli()
        }
        return candidateToday.plusDays(7).toInstant().toEpochMilli()
    }
}
