package com.kirkouski.gtalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Defensive parsing for the wire `vibrationPattern` field. The watch JS
 * and any future peer build may ship an unknown string (forward-compat),
 * a renamed enum, or omit the field entirely. The Kotlin parser MUST
 * collapse all of those to [VibrationPattern.DEFAULT] rather than
 * throwing — otherwise a stray watch envelope crashes the phone's sync
 * pipeline.
 */
class VibrationPatternTest {

    @Test fun `each known enum name round-trips`() {
        VibrationPattern.entries.forEach { p ->
            assertEquals(p, VibrationPattern.fromWireName(p.name))
        }
    }

    @Test fun `null collapses to DEFAULT`() {
        assertEquals(VibrationPattern.DEFAULT, VibrationPattern.fromWireName(null))
    }

    @Test fun `empty string collapses to DEFAULT`() {
        assertEquals(VibrationPattern.DEFAULT, VibrationPattern.fromWireName(""))
    }

    @Test fun `unknown name collapses to DEFAULT (forward-compat)`() {
        assertEquals(VibrationPattern.DEFAULT, VibrationPattern.fromWireName("FUTURE_PATTERN_X"))
    }

    @Test fun `case-sensitive — lowercase pulse does NOT match (wire is enum name)`() {
        // The wire ships `enum.name` which is upper-case. A lower-case
        // payload is treated as unknown (collapses to default) rather
        // than silently matching — that prevents a peer using a different
        // casing convention from quietly desyncing the hash.
        assertEquals(VibrationPattern.DEFAULT, VibrationPattern.fromWireName("pulse"))
    }

    @Test fun `DEFAULT is PULSE`() {
        // PULSE is the spec's documented default; verify the constant
        // hasn't drifted. The watch ships the literal string "PULSE" as
        // its fallback in alarmHash.js + index.js.
        assertEquals(VibrationPattern.PULSE, VibrationPattern.DEFAULT)
    }
}
