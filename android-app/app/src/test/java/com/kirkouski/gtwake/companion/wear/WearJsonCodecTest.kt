package com.kirkouski.gtwake.companion.wear

import com.kirkouski.gtwake.companion.data.sync.IncomingMessage
import com.kirkouski.gtwake.companion.domain.Alarm
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearJsonCodecTest {

    @Test
    fun `parseIncoming returns null when type missing`() {
        val json = JSONObject().apply {
            put("alarmId", 1L)
            put("updatedAtEpoch", 100L)
        }
        assertNull(WearJsonCodec.parseIncoming(json))
    }

    @Test
    fun `parseIncoming returns null when type empty string`() {
        val json = JSONObject().apply {
            put("type", "")
            put("alarmId", 1L)
            put("updatedAtEpoch", 100L)
        }
        assertNull(WearJsonCodec.parseIncoming(json))
    }

    @Test
    fun `parseIncoming returns null when alarmId missing`() {
        val json = JSONObject().apply {
            put("type", "alarm_added")
            put("updatedAtEpoch", 100L)
        }
        assertNull(WearJsonCodec.parseIncoming(json))
    }

    @Test
    fun `parseIncoming returns null when updatedAtEpoch missing`() {
        val json = JSONObject().apply {
            put("type", "alarm_added")
            put("alarmId", 1L)
        }
        assertNull(WearJsonCodec.parseIncoming(json))
    }

    @Test
    fun `parseIncoming returns null on negative alarmId`() {
        val json = JSONObject().apply {
            put("type", "alarm_added")
            put("alarmId", -1L)
            put("updatedAtEpoch", 100L)
        }
        assertNull(WearJsonCodec.parseIncoming(json))
    }

    @Test
    fun `parseIncoming returns null for unknown type`() {
        val json = JSONObject().apply {
            put("type", "alarm_undefined_action")
            put("alarmId", 1L)
            put("updatedAtEpoch", 100L)
        }
        assertNull(WearJsonCodec.parseIncoming(json))
    }

    @Test
    fun `alarm_added requires alarm payload — returns null when missing`() {
        val json = JSONObject().apply {
            put("type", "alarm_added")
            put("alarmId", 1L)
            put("updatedAtEpoch", 100L)
        }
        assertNull(WearJsonCodec.parseIncoming(json))
    }

    @Test
    fun `alarm_added with full payload parses to AlarmAdded`() {
        val alarm = JSONObject().apply {
            put("id", 5L)
            put("label", "Wake")
            put("hour", 7)
            put("minute", 30)
            put("daysOfWeek", 0b0011111)
            put("enabled", true)
            put("audioUri", "content://media/wake.mp3")
            put("isVibrationOnly", false)
            put("snoozeMinutes", 15)
            put("updatedAtEpoch", 100L)
        }
        val json = JSONObject().apply {
            put("type", "alarm_added")
            put("alarmId", 5L)
            put("updatedAtEpoch", 100L)
            put("alarm", alarm)
        }
        val msg = WearJsonCodec.parseIncoming(json)
        assertTrue(msg is IncomingMessage.AlarmAdded)
        msg as IncomingMessage.AlarmAdded
        assertEquals(5L, msg.alarmId)
        assertEquals(100L, msg.updatedAtEpoch)
        assertEquals(
            Alarm(
                id = 5L,
                // The wire `label` ("Wake" above) is ignored — the alarm
                // label is phone-only and never crosses the watch wire.
                label = "",
                hour = 7,
                minute = 30,
                daysOfWeek = 0b0011111,
                enabled = true,
                audioUri = "content://media/wake.mp3",
                audioName = null,
                isVibrationOnly = false,
                snoozeMinutes = 15,
                updatedAtEpoch = 100L,
            ),
            msg.alarm,
        )
    }

    @Test
    fun `parseAlarm omits snoozeMinutes - defaults applied`() {
        val alarm = JSONObject().apply {
            put("id", 5L)
            put("hour", 7)
            put("minute", 30)
            put("enabled", true)
        }
        val msg = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added")
            put("alarmId", 5L)
            put("updatedAtEpoch", 100L)
            put("alarm", alarm)
        }) as IncomingMessage.AlarmAdded
        assertEquals(Alarm.DEFAULT_SNOOZE_MINUTES, msg.alarm.snoozeMinutes)
    }

    @Test
    fun `parseAlarm clamps out-of-range snoozeMinutes to bounds`() {
        val payloadHigh = JSONObject().apply {
            put("id", 5L); put("hour", 7); put("minute", 0); put("enabled", true); put("snoozeMinutes", 999)
        }
        val high = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", payloadHigh)
        }) as IncomingMessage.AlarmAdded
        assertEquals(Alarm.MAX_SNOOZE_MINUTES, high.alarm.snoozeMinutes)

        // 0 = SNOOZE_DISABLED is a valid sentinel and passes through.
        val payloadOff = JSONObject().apply {
            put("id", 5L); put("hour", 7); put("minute", 0); put("enabled", true); put("snoozeMinutes", 0)
        }
        val off = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", payloadOff)
        }) as IncomingMessage.AlarmAdded
        assertEquals(Alarm.SNOOZE_DISABLED, off.alarm.snoozeMinutes)

        // Negative values still clamp to SNOOZE_DISABLED rather than the
        // enabled minimum — there's no semantic where "<0" means "1 min".
        val payloadNeg = JSONObject().apply {
            put("id", 5L); put("hour", 7); put("minute", 0); put("enabled", true); put("snoozeMinutes", -3)
        }
        val neg = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", payloadNeg)
        }) as IncomingMessage.AlarmAdded
        assertEquals(Alarm.SNOOZE_DISABLED, neg.alarm.snoozeMinutes)
    }

    @Test
    fun `parseAlarm ignores any label on the wire — label is phone-only`() {
        // The alarm label never crosses the watch wire (sync-architecture.md
        // §2.2). A compromised / buggy peer could still stuff a multi-megabyte
        // `label` into the payload; parseAlarm must ignore it outright rather
        // than letting it reach Alarm's init validator.
        val huge = "x".repeat(Alarm.MAX_LABEL_LENGTH + 100)
        val payload = JSONObject().apply {
            put("id", 5L); put("hour", 7); put("minute", 0); put("enabled", true); put("label", huge)
        }
        val msg = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", payload)
        }) as IncomingMessage.AlarmAdded
        assertEquals("", msg.alarm.label)
    }

    @Test
    fun `alarm_updated treats null audioUri as null on the parsed Alarm`() {
        val alarm = JSONObject().apply {
            put("id", 5L)
            put("hour", 6)
            put("minute", 0)
            put("daysOfWeek", 0)
            put("enabled", true)
            put("audioUri", JSONObject.NULL)
            put("isVibrationOnly", true)
            put("updatedAtEpoch", 200L)
        }
        val json = JSONObject().apply {
            put("type", "alarm_updated")
            put("alarmId", 5L)
            put("updatedAtEpoch", 200L)
            put("alarm", alarm)
        }
        val msg = WearJsonCodec.parseIncoming(json) as IncomingMessage.AlarmUpdated
        assertNull(msg.alarm.audioUri)
        assertNull(msg.alarm.audioName)
        assertTrue(msg.alarm.isVibrationOnly)
    }

    @Test
    fun `alarm_updated treats empty-string audioUri as null on the parsed Alarm`() {
        val alarm = JSONObject().apply {
            put("id", 5L)
            put("hour", 6)
            put("minute", 0)
            put("daysOfWeek", 0)
            put("enabled", true)
            put("audioUri", "")
            put("isVibrationOnly", false)
            put("updatedAtEpoch", 200L)
        }
        val json = JSONObject().apply {
            put("type", "alarm_updated")
            put("alarmId", 5L)
            put("updatedAtEpoch", 200L)
            put("alarm", alarm)
        }
        val msg = WearJsonCodec.parseIncoming(json) as IncomingMessage.AlarmUpdated
        assertNull(msg.alarm.audioUri)
    }

    @Test
    fun `alarm_added returns null when enabled field missing`() {
        val alarm = JSONObject().apply {
            put("id", 5L)
            put("hour", 7)
            put("minute", 0)
            put("daysOfWeek", 0)
            put("isVibrationOnly", false)
            put("updatedAtEpoch", 100L)
        }
        val json = JSONObject().apply {
            put("type", "alarm_added")
            put("alarmId", 5L)
            put("updatedAtEpoch", 100L)
            put("alarm", alarm)
        }
        assertNull(WearJsonCodec.parseIncoming(json))
    }

    @Test
    fun `alarm_added returns null when hour or minute missing`() {
        val noHour = JSONObject().apply {
            put("id", 5L)
            put("minute", 0)
            put("enabled", true)
        }
        assertNull(WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added")
            put("alarmId", 5L)
            put("updatedAtEpoch", 100L)
            put("alarm", noHour)
        }))

        val noMinute = JSONObject().apply {
            put("id", 5L)
            put("hour", 7)
            put("enabled", true)
        }
        assertNull(WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added")
            put("alarmId", 5L)
            put("updatedAtEpoch", 100L)
            put("alarm", noMinute)
        }))
    }

    @Test
    fun `alarm_toggled parses alarmId enabled and timestamp`() {
        val json = JSONObject().apply {
            put("type", "alarm_toggled")
            put("alarmId", 9L)
            put("updatedAtEpoch", 300L)
            put("enabled", false)
        }
        val msg = WearJsonCodec.parseIncoming(json) as IncomingMessage.AlarmToggled
        assertEquals(9L, msg.alarmId)
        assertEquals(300L, msg.updatedAtEpoch)
        assertEquals(false, msg.enabled)
    }

    @Test
    fun `alarm_toggled returns null when enabled field missing`() {
        val json = JSONObject().apply {
            put("type", "alarm_toggled")
            put("alarmId", 9L)
            put("updatedAtEpoch", 300L)
        }
        assertNull(WearJsonCodec.parseIncoming(json))
    }

    @Test
    fun `alarm_deleted parses with no extra fields`() {
        val json = JSONObject().apply {
            put("type", "alarm_deleted")
            put("alarmId", 11L)
            put("updatedAtEpoch", 400L)
        }
        val msg = WearJsonCodec.parseIncoming(json) as IncomingMessage.AlarmDeleted
        assertEquals(11L, msg.alarmId)
        assertEquals(400L, msg.updatedAtEpoch)
    }

    @Test
    fun `alarm_fired parses to AlarmFired`() {
        val json = JSONObject().apply {
            put("type", "alarm_fired")
            put("alarmId", 4L)
            put("updatedAtEpoch", 500L)
        }
        val msg = WearJsonCodec.parseIncoming(json) as IncomingMessage.AlarmFired
        assertEquals(4L, msg.alarmId)
        assertEquals(500L, msg.updatedAtEpoch)
    }

    @Test
    fun `alarm_dismissed parses to AlarmDismissed`() {
        val json = JSONObject().apply {
            put("type", "alarm_dismissed")
            put("alarmId", 4L)
            put("updatedAtEpoch", 600L)
        }
        val msg = WearJsonCodec.parseIncoming(json) as IncomingMessage.AlarmDismissed
        assertEquals(4L, msg.alarmId)
        assertEquals(600L, msg.updatedAtEpoch)
    }

    @Test
    fun `alarm_snoozed parses with no reschedule field — phone owns the duration`() {
        val json = JSONObject().apply {
            put("type", "alarm_snoozed")
            put("alarmId", 7L)
            put("updatedAtEpoch", 700L)
        }
        val msg = WearJsonCodec.parseIncoming(json) as IncomingMessage.AlarmSnoozed
        assertEquals(7L, msg.alarmId)
        assertEquals(700L, msg.updatedAtEpoch)
    }

    @Test
    fun `alarm_snoozed ignores extraneous rescheduleEpoch from older watch builds`() {
        val json = JSONObject().apply {
            put("type", "alarm_snoozed")
            put("alarmId", 7L)
            put("updatedAtEpoch", 700L)
            put("rescheduleEpoch", 1_700_000_000_000L)
        }
        val msg = WearJsonCodec.parseIncoming(json) as IncomingMessage.AlarmSnoozed
        assertEquals(7L, msg.alarmId)
        assertEquals(700L, msg.updatedAtEpoch)
    }

    @Test
    fun `parsed alarm id is taken from envelope alarmId not the payload id`() {
        val alarm = JSONObject().apply {
            put("hour", 8)
            put("minute", 0)
            put("enabled", true)
        }
        val json = JSONObject().apply {
            put("type", "alarm_added")
            put("alarmId", 42L)
            put("updatedAtEpoch", 100L)
            put("alarm", alarm)
        }
        val msg = WearJsonCodec.parseIncoming(json) as IncomingMessage.AlarmAdded
        assertEquals(42L, msg.alarm.id)
        assertEquals(42L, msg.alarmId)
    }

    @Test
    fun `envelope alarmId overrides payload id when they disagree`() {
        val alarm = JSONObject().apply {
            put("id", 99L)
            put("hour", 8)
            put("minute", 0)
            put("enabled", true)
        }
        val json = JSONObject().apply {
            put("type", "alarm_updated")
            put("alarmId", 42L)
            put("updatedAtEpoch", 200L)
            put("alarm", alarm)
        }
        val msg = WearJsonCodec.parseIncoming(json) as IncomingMessage.AlarmUpdated
        assertEquals(42L, msg.alarm.id)
    }

    @Test
    fun `alarm_toggled round-trip stable across send-then-parse`() {
        val envelope = JSONObject().apply {
            put("type", "alarm_toggled")
            put("alarmId", 9L)
            put("updatedAtEpoch", 300L)
            put("enabled", true)
        }
        val parsed = WearJsonCodec.parseIncoming(JSONObject(envelope.toString()))
            as IncomingMessage.AlarmToggled
        assertEquals(9L, parsed.alarmId)
        assertEquals(300L, parsed.updatedAtEpoch)
        assertTrue(parsed.enabled)
    }

    @Test
    fun `parseAlarm with absent relativeMinutes and selfDestruct uses backward-compat defaults`() {
        val alarm = JSONObject().apply {
            put("id", 5L); put("hour", 7); put("minute", 0); put("enabled", true); put("daysOfWeek", 0)
        }
        val msg = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", alarm)
        }) as IncomingMessage.AlarmAdded
        assertNull(msg.alarm.relativeMinutes)
        assertEquals(false, msg.alarm.selfDestruct)
    }

    @Test
    fun `parseAlarm round-trips valid relativeMinutes for a one-shot alarm`() {
        val alarm = JSONObject().apply {
            put("id", 5L); put("hour", 0); put("minute", 0); put("enabled", true)
            put("daysOfWeek", 0); put("relativeMinutes", 15); put("selfDestruct", true)
        }
        val msg = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", alarm)
        }) as IncomingMessage.AlarmAdded
        assertEquals(15, msg.alarm.relativeMinutes)
        assertTrue(msg.alarm.selfDestruct)
    }

    @Test
    fun `parseAlarm rejects relativeMinutes when daysOfWeek is non-zero (illegal combo)`() {
        // Reject-not-coerce: silent coercion would let the phone overwrite
        // the watch's intended state on the next push. Drop the envelope
        // and let the peer re-send in a valid shape.
        val alarm = JSONObject().apply {
            put("id", 5L); put("hour", 7); put("minute", 0); put("enabled", true)
            put("daysOfWeek", 0b0011111); put("relativeMinutes", 15)
        }
        val msg = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", alarm)
        })
        assertNull("illegal combo must be rejected", msg)
    }

    @Test
    fun `parseAlarm rejects out-of-range relativeMinutes`() {
        val tooHigh = JSONObject().apply {
            put("id", 5L); put("hour", 0); put("minute", 0); put("enabled", true)
            put("daysOfWeek", 0); put("relativeMinutes", 9999)
        }
        assertNull(
            "relativeMinutes above MAX must be rejected",
            WearJsonCodec.parseIncoming(JSONObject().apply {
                put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", tooHigh)
            }),
        )

        val zero = JSONObject().apply {
            put("id", 5L); put("hour", 0); put("minute", 0); put("enabled", true)
            put("daysOfWeek", 0); put("relativeMinutes", 0)
        }
        assertNull(
            "relativeMinutes below MIN must be rejected",
            WearJsonCodec.parseIncoming(JSONObject().apply {
                put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", zero)
            }),
        )

        val negative = JSONObject().apply {
            put("id", 5L); put("hour", 0); put("minute", 0); put("enabled", true)
            put("daysOfWeek", 0); put("relativeMinutes", -1)
        }
        assertNull(
            "negative relativeMinutes must be rejected",
            WearJsonCodec.parseIncoming(JSONObject().apply {
                put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", negative)
            }),
        )
    }

    @Test
    fun `parseAlarm rejects selfDestruct=true with daysOfWeek!=0 (illegal combo)`() {
        val alarm = JSONObject().apply {
            put("id", 5L); put("hour", 7); put("minute", 0); put("enabled", true)
            put("daysOfWeek", 0b0011111); put("selfDestruct", true)
        }
        val msg = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", alarm)
        })
        assertNull("selfDestruct+recurring must be rejected", msg)
    }

    @Test
    fun `parseAlarm preserves selfDestruct=true on one-shot`() {
        val alarm = JSONObject().apply {
            put("id", 5L); put("hour", 8); put("minute", 0); put("enabled", true)
            put("daysOfWeek", 0); put("selfDestruct", true)
        }
        val msg = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added"); put("alarmId", 5L); put("updatedAtEpoch", 1L); put("alarm", alarm)
        }) as IncomingMessage.AlarmAdded
        assertTrue(msg.alarm.selfDestruct)
    }

    // ─── Tier 1+2 field parsing (parser-boundary coverage) ───
    //
    // The parser is the only gate against malformed peer payloads. A
    // missed clamp here means a bad watch (or future-version peer) can
    // poison Room. These tests pin defaults, clamps, and unknown-value
    // handling for the four new wire fields.

    private fun parseWithExtras(extras: JSONObject.() -> Unit): com.kirkouski.gtwake.companion.domain.Alarm {
        val alarm = JSONObject().apply {
            put("id", 1L); put("hour", 7); put("minute", 0); put("enabled", true)
            put("daysOfWeek", 0); put("selfDestruct", false)
            extras()
        }
        val msg = WearJsonCodec.parseIncoming(JSONObject().apply {
            put("type", "alarm_added"); put("alarmId", 1L); put("updatedAtEpoch", 1L)
            put("alarm", alarm)
        }) as IncomingMessage.AlarmAdded
        return msg.alarm
    }

    @Test fun `parseAlarm defaults vibrationPattern to PULSE when missing`() {
        assertEquals(
            com.kirkouski.gtwake.companion.domain.VibrationPattern.PULSE,
            parseWithExtras { }.vibrationPattern,
        )
    }

    @Test fun `parseAlarm reads known vibrationPattern name`() {
        assertEquals(
            com.kirkouski.gtwake.companion.domain.VibrationPattern.HEARTBEAT,
            parseWithExtras { put("vibrationPattern", "HEARTBEAT") }.vibrationPattern,
        )
    }

    @Test fun `parseAlarm coerces unknown vibrationPattern to PULSE`() {
        // Forward-compat: a peer running a future build sends a pattern
        // name we don't recognise. Reject would drop the whole envelope;
        // coerce keeps everything else intact.
        assertEquals(
            com.kirkouski.gtwake.companion.domain.VibrationPattern.PULSE,
            parseWithExtras { put("vibrationPattern", "FUTURE_X") }.vibrationPattern,
        )
    }

    @Test fun `parseAlarm clamps volumeRampSeconds to MAX_VOLUME_RAMP_SECONDS`() {
        assertEquals(
            Alarm.MAX_VOLUME_RAMP_SECONDS,
            parseWithExtras { put("volumeRampSeconds", 9_999) }.volumeRampSeconds,
        )
    }

    @Test fun `parseAlarm clamps negative volumeRampSeconds to 0`() {
        assertEquals(
            0,
            parseWithExtras { put("volumeRampSeconds", -5) }.volumeRampSeconds,
        )
    }

    @Test fun `parseAlarm clamps maxSnoozeCount above CAP to CAP`() {
        assertEquals(
            Alarm.MAX_SNOOZE_COUNT_CAP,
            parseWithExtras { put("maxSnoozeCount", 9_999) }.maxSnoozeCount,
        )
    }

    @Test fun `parseAlarm coerces negative maxSnoozeCount to UNLIMITED`() {
        // 0 = unlimited is the sentinel; anything below 0 should also be
        // treated as unlimited (degraded read of a malformed payload).
        assertEquals(
            Alarm.MAX_SNOOZE_COUNT_UNLIMITED,
            parseWithExtras { put("maxSnoozeCount", -1) }.maxSnoozeCount,
        )
    }

    @Test fun `parseAlarm leaves skipNextEpoch null when absent`() {
        assertNull(parseWithExtras { }.skipNextEpoch)
    }

    @Test fun `parseAlarm preserves positive skipNextEpoch`() {
        assertEquals(
            1_700_000_000_000L,
            parseWithExtras { put("skipNextEpoch", 1_700_000_000_000L) }.skipNextEpoch,
        )
    }

    @Test fun `parseAlarm coerces zero or negative skipNextEpoch to null`() {
        // <=0 is meaningless (epoch 0 is 1970); treat as absent so the
        // calculator + UI hint never act on a sentinel value.
        assertNull(parseWithExtras { put("skipNextEpoch", 0L) }.skipNextEpoch)
        assertNull(parseWithExtras { put("skipNextEpoch", -1L) }.skipNextEpoch)
    }

    @Test fun `parseAlarm always zeroes consecutiveSnoozeCount on inbound`() {
        // consecutiveSnoozeCount is phone-side ring-cycle state; it
        // never crosses the wire. If a future peer DOES send it, we
        // ignore the field and reset to 0 (per WearJsonCodec comment).
        assertEquals(
            0,
            parseWithExtras { put("consecutiveSnoozeCount", 42) }.consecutiveSnoozeCount,
        )
    }
}
