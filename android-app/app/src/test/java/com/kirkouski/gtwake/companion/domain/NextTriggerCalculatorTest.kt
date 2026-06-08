package com.kirkouski.gtwake.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NextTriggerCalculatorTest {

    private val zone = ZoneId.of("UTC")
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    @Test
    fun `one-shot future today`() {
        val now = at(2026, 4, 24, 10, 0)
        val alarm = Alarm(hour = 15, minute = 30, daysOfWeek = DaysOfWeek.NONE)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(at(2026, 4, 24, 15, 30).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `one-shot past rolls to tomorrow`() {
        val now = at(2026, 4, 24, 16, 0)
        val alarm = Alarm(hour = 15, minute = 30, daysOfWeek = DaysOfWeek.NONE)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(at(2026, 4, 25, 15, 30).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `one-shot exactly now rolls to tomorrow`() {
        val now = at(2026, 4, 24, 15, 30)
        val alarm = Alarm(hour = 15, minute = 30, daysOfWeek = DaysOfWeek.NONE)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(at(2026, 4, 25, 15, 30).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `repeating picks next matching weekday`() {
        // 2026-04-24 is a Friday. Alarm set for Mon+Wed, time passed.
        val now = at(2026, 4, 24, 20, 0)
        val alarm = Alarm(hour = 7, minute = 0, daysOfWeek = DaysOfWeek.MON or DaysOfWeek.WED)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        // Next Monday is 2026-04-27
        assertEquals(at(2026, 4, 27, 7, 0).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `repeating picks today when today matches and time is future`() {
        // 2026-04-24 is a Friday.
        val now = at(2026, 4, 24, 6, 0)
        val alarm = Alarm(hour = 7, minute = 0, daysOfWeek = DaysOfWeek.FRI)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(at(2026, 4, 24, 7, 0).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `repeating skips today if time already passed`() {
        val now = at(2026, 4, 24, 8, 0) // Friday 08:00
        val alarm = Alarm(hour = 7, minute = 0, daysOfWeek = DaysOfWeek.FRI)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        // Next Friday is 2026-05-01
        assertEquals(at(2026, 5, 1, 7, 0).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `weekend only skips weekdays`() {
        val now = at(2026, 4, 20, 8, 0) // Monday
        val alarm = Alarm(hour = 9, minute = 0, daysOfWeek = DaysOfWeek.WEEKENDS)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        // Next Saturday = 2026-04-25
        assertEquals(at(2026, 4, 25, 9, 0).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `returns strictly future instant`() {
        val now = ZonedDateTime.now(zone)
        val alarm = Alarm(hour = now.hour, minute = now.minute, daysOfWeek = DaysOfWeek.NONE)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertTrue(trigger > now.toInstant().toEpochMilli())
    }

    @Test
    fun `midnight wrap rolls to next day at 00 00`() {
        // 23:59 today, alarm at 00:00 (midnight) → fires tomorrow 00:00.
        val now = at(2026, 4, 24, 23, 59)
        val alarm = Alarm(hour = 0, minute = 0, daysOfWeek = DaysOfWeek.NONE)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(at(2026, 4, 25, 0, 0).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `repeating with empty daysOfWeek behaves as one-shot`() {
        // daysOfWeek=0 must NOT mean "never fires"; the source code treats
        // it as a one-shot for the next slot. Locking that contract here.
        val now = at(2026, 4, 24, 9, 0)
        val alarm = Alarm(hour = 10, minute = 0, daysOfWeek = 0)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(at(2026, 4, 24, 10, 0).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `DST spring-forward at 02 00 in Europe Berlin`() {
        // CEST: 2026-03-29 02:00 → 03:00 (spring forward).
        // 02:30 doesn't exist that day. ZonedDateTime resolves the gap by
        // shifting forward to 03:30. The contract: trigger is a real
        // future instant (no NPE / impossible time).
        val tz = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, tz)
        val alarm = Alarm(hour = 2, minute = 30, daysOfWeek = DaysOfWeek.NONE)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertTrue(
            "DST-skipped slot must still produce a future instant",
            trigger > now.toInstant().toEpochMilli(),
        )
    }

    @Test
    fun `snoozedUntilEpoch overrides hour-minute when in future`() {
        // Instant test alarm at 14:30 (today is in the past at 16:00) +
        // snoozedUntilEpoch=now+1min. Calculator must return the snooze
        // trigger, NOT tomorrow's 14:30 — that's the fix for the
        // user-reported "in 23h" list display bug.
        val now = at(2026, 4, 24, 16, 0)
        val snoozeUntil = at(2026, 4, 24, 16, 1).toInstant().toEpochMilli()
        val alarm = Alarm(
            hour = 14,
            minute = 30,
            daysOfWeek = DaysOfWeek.NONE,
            snoozedUntilEpoch = snoozeUntil,
        )
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(snoozeUntil, trigger)
    }

    @Test
    fun `snoozedUntilEpoch in past falls through to normal calculation`() {
        // Stale snooze (alarm should have fired but didn't clear). Don't
        // return the past snooze — fall back to the normal one-shot
        // tomorrow path. AlarmRingService also clears the field on fire,
        // so this guards against a missed clear.
        val now = at(2026, 4, 24, 16, 0)
        val stalePastSnooze = at(2026, 4, 24, 15, 0).toInstant().toEpochMilli()
        val alarm = Alarm(
            hour = 14,
            minute = 30,
            daysOfWeek = DaysOfWeek.NONE,
            snoozedUntilEpoch = stalePastSnooze,
        )
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(at(2026, 4, 25, 14, 30).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `DST fall-back at 02 00 in Europe Berlin`() {
        // CEST→CET: 2026-10-25 03:00 → 02:00 (fall back). 02:30 occurs
        // twice. Either occurrence is acceptable, but the trigger must
        // still be strictly in the future.
        val tz = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 10, 25, 1, 0, 0, 0, tz)
        val alarm = Alarm(hour = 2, minute = 30, daysOfWeek = DaysOfWeek.NONE)
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertTrue(trigger > now.toInstant().toEpochMilli())
    }

    @Test
    fun `skipNextEpoch advances past the matched occurrence`() {
        // Friday 2026-04-24 06:00; recurring Fri+Mon at 07:00.
        // Without skip, next is Friday 07:00. With skipNextEpoch=Friday 07:00,
        // calculator returns the FOLLOWING occurrence — Monday 07:00.
        val now = at(2026, 4, 24, 6, 0)
        val fridayMatch = at(2026, 4, 24, 7, 0).toInstant().toEpochMilli()
        val mondayMatch = at(2026, 4, 27, 7, 0).toInstant().toEpochMilli()
        val alarm = Alarm(
            hour = 7,
            minute = 0,
            daysOfWeek = DaysOfWeek.FRI or DaysOfWeek.MON,
            skipNextEpoch = fridayMatch,
        )
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(mondayMatch, trigger)
    }

    @Test
    fun `skipNextEpoch with no exact match passes through unchanged`() {
        // Skip pointing at an instant that isn't the next trigger (stale
        // skip, or skip pointing at yesterday). Calculator returns the
        // raw next-trigger without advancing.
        val now = at(2026, 4, 24, 6, 0)
        val staleSkip = at(2026, 4, 23, 7, 0).toInstant().toEpochMilli()  // yesterday
        val alarm = Alarm(
            hour = 7,
            minute = 0,
            daysOfWeek = DaysOfWeek.FRI,
            skipNextEpoch = staleSkip,
        )
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(at(2026, 4, 24, 7, 0).toInstant().toEpochMilli(), trigger)
    }

    @Test
    fun `skipNextEpoch ignored when alarm is not recurring`() {
        // One-shots can be just disabled; the skip-next path is recurring-only.
        // If somehow set on a one-shot, the calculator must NOT roll past — that
        // would push the fire arbitrarily far into the future.
        val now = at(2026, 4, 24, 6, 0)
        val target = at(2026, 4, 24, 7, 0).toInstant().toEpochMilli()
        val alarm = Alarm(
            hour = 7,
            minute = 0,
            daysOfWeek = DaysOfWeek.NONE,
            skipNextEpoch = target,
        )
        val trigger = NextTriggerCalculator.nextTriggerEpochMillis(alarm, now)
        assertEquals(target, trigger)
    }
}
