package com.kirkouski.gtalarm.domain

import java.time.ZoneId
import java.time.ZonedDateTime

object NextTriggerCalculator {
    // reason: 4 early returns are intentional and each handles a distinct
    // alarm class (relative, one-shot absolute, recurring loop hit, recurring
    // loop fallback). A single-return refactor with a nullable accumulator
    // would obscure the branches and require an `error()` for the unreachable
    // fallback. The branches are explicit by design.
    @Suppress("ReturnCount")
    fun nextTriggerEpochMillis(
        alarm: Alarm,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
    ): Long {
        // Relative ("in N min") alarms: fire time is fixed at the moment of
        // activation. Toggle-off-then-on bumps `updatedAtEpoch` so the timer
        // slides forward — the calculator doesn't need any reset logic. Cold
        // reboot recovery handled in BootReceiver (past-due rule).
        if (alarm.isRelative) {
            return alarm.computedFireEpoch()
        }

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
