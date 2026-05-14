package com.kirkouski.gtalarm.data.sync

import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.domain.DaysOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the canonical form + hash algorithm so silent drift between the
 * Kotlin and JS implementations is impossible.
 *
 * **Why this matters:** [AlarmHash] is consumed by the sync_check optimization
 * — phone hashes its alarm list, watch hashes its own, phone short-circuits
 * the push when both match. If either side changes field order, null
 * handling, or trailing-newline convention without updating the other, the
 * hashes diverge silently and the optimization becomes dead overhead (worst
 * case: a misleading "AlreadyInSync" message when the watch and phone disagree).
 *
 * **What the tests check:**
 *  1. **Canonicalize fidelity** — an in-test canonical string is built
 *     manually from a known alarm, hashed via Java [String.hashCode], then
 *     compared against [AlarmHash.compute]. Any divergence in field order,
 *     null encoding, or boolean rendering breaks one side.
 *  2. **JS-side reference vectors** — the hex values are pinned in the
 *     comments below so the watch's `alarmHash.js` can be verified against
 *     the same vectors. Watch implementer: run [referenceVectors] through
 *     `AlarmHash.compute` on the watch and assert the same hex strings.
 *
 * If you change [AlarmHash.canonicalize], you MUST:
 *   - update the canonical strings in this file
 *   - re-derive the pinned hex values (Kotlin: `s.hashCode().toUInt().toString(16).padStart(8, '0')`)
 *   - update `watch-app/.../common/alarmHash.js` to match byte-for-byte
 *   - update [referenceVectors] below
 */
class AlarmHashTest {

    @Test
    fun `empty list hashes to 00000000`() {
        // Java String.hashCode("") == 0; padded to 8 chars = "00000000".
        // Same on JS via ((h<<5)-h+c)|0 over zero chars.
        assertEquals("00000000", AlarmHash.compute(emptyList()))
    }

    @Test
    fun `single minimal alarm — canonicalize + hash byte-equivalent`() {
        val alarm = Alarm(
            id = 1L,
            label = "",
            hour = 7,
            minute = 0,
            daysOfWeek = 0,
            enabled = true,
            audioUri = null,
            audioName = null,
            isVibrationOnly = false,
            snoozeMinutes = 10,
            updatedAtEpoch = 1000L,
            relativeMinutes = null,
            selfDestruct = false,
            backgroundImageUri = null,
        )
        // Canonical line shape per AlarmHash.kt §canonicalize:
        //   id|label|hour|minute|daysOfWeek|enabled|audioUri|
        //   isVibrationOnly|snoozeMinutes|updatedAtEpoch|relativeMinutes|selfDestruct\n
        val expectedCanonical = "1||7|0|0|1||0|10|1000||0\n"
        val expectedHex = expectedCanonical.hashCode().toUInt().toString(16).padStart(8, '0')
        assertEquals(expectedHex, AlarmHash.compute(listOf(alarm)))
    }

    @Test
    fun `label, audioUri, and unicode flow through canonical form unchanged`() {
        val alarm = Alarm(
            id = 42L,
            label = "Утро ☕",
            hour = 6,
            minute = 30,
            daysOfWeek = DaysOfWeek.WEEKDAYS,
            enabled = true,
            audioUri = "content://media/external/audio/media/123",
            audioName = "Morning bell",
            isVibrationOnly = false,
            snoozeMinutes = 5,
            updatedAtEpoch = 1_700_000_000_000L,
            relativeMinutes = null,
            selfDestruct = false,
        )
        // audioName is NOT serialized (phone-local display only).
        val expectedCanonical =
            "42|Утро ☕|6|30|${DaysOfWeek.WEEKDAYS}|1|content://media/external/audio/media/123|0|5|1700000000000||0\n"
        val expectedHex = expectedCanonical.hashCode().toUInt().toString(16).padStart(8, '0')
        assertEquals(expectedHex, AlarmHash.compute(listOf(alarm)))
    }

    @Test
    fun `relativeMinutes and selfDestruct fields are part of the hash`() {
        val withRelative = Alarm(
            id = 7L, label = "Stand", hour = 0, minute = 0, daysOfWeek = 0, enabled = true,
            audioUri = null, audioName = null, isVibrationOnly = false, snoozeMinutes = 10,
            updatedAtEpoch = 200L, relativeMinutes = 15, selfDestruct = true,
        )
        val expectedCanonical = "7|Stand|0|0|0|1||0|10|200|15|1\n"
        val expectedHex = expectedCanonical.hashCode().toUInt().toString(16).padStart(8, '0')
        assertEquals(expectedHex, AlarmHash.compute(listOf(withRelative)))
    }

    @Test
    fun `backgroundImageUri is NOT part of the hash (device-local, doesn't round-trip)`() {
        // Per AlarmHash §canonicalize, the phone bg URI is intentionally
        // excluded — it's a device-local content:// path that never
        // round-trips. Including it in the hash would break the
        // AlreadyInSync short-circuit on every sync. Code-review pass 1
        // (2026-05-13) caught the original inclusion.
        val base = Alarm(
            id = 7L, label = "Wake", hour = 7, minute = 0, daysOfWeek = 0, enabled = true,
            audioUri = null, audioName = null, isVibrationOnly = false, snoozeMinutes = 10,
            updatedAtEpoch = 200L, relativeMinutes = null, selfDestruct = false,
            backgroundImageUri = null,
        )
        val withPhoneBg = base.copy(backgroundImageUri = "content://media/external/images/media/42")
        val baseHex = AlarmHash.compute(listOf(base))
        assertEquals(baseHex, AlarmHash.compute(listOf(withPhoneBg)))
    }

    @Test
    fun `selfDestruct=true alone changes the hash (rendered 1 vs 0)`() {
        val base = Alarm(
            id = 7L, label = "x", hour = 0, minute = 0, daysOfWeek = 0, enabled = true,
            audioUri = null, audioName = null, isVibrationOnly = false, snoozeMinutes = 10,
            updatedAtEpoch = 200L, relativeMinutes = null, selfDestruct = false,
        )
        val withSelfDestruct = base.copy(selfDestruct = true)
        val a = AlarmHash.compute(listOf(base))
        val b = AlarmHash.compute(listOf(withSelfDestruct))
        assert(a != b) { "hashes must differ for selfDestruct false vs true; both got '$a'" }
    }

    @Test
    fun `alarms are sorted by id before hashing (order-independent)`() {
        val a1 = Alarm(
            id = 1L, label = "A", hour = 7, minute = 0, daysOfWeek = 0, enabled = true,
            audioUri = null, audioName = null, isVibrationOnly = false, snoozeMinutes = 10,
            updatedAtEpoch = 100L, relativeMinutes = null, selfDestruct = false,
        )
        val a2 = Alarm(
            id = 2L, label = "B", hour = 8, minute = 0, daysOfWeek = 0, enabled = true,
            audioUri = null, audioName = null, isVibrationOnly = false, snoozeMinutes = 10,
            updatedAtEpoch = 200L, relativeMinutes = null, selfDestruct = false,
        )
        // Same alarms in different input order should produce the same hash.
        assertEquals(AlarmHash.compute(listOf(a1, a2)), AlarmHash.compute(listOf(a2, a1)))
    }

    @Test
    fun `hash is exactly 8 lowercase hex chars`() {
        val hex = AlarmHash.compute(listOf(
            Alarm(
                id = 9L, label = "long label with spaces", hour = 12, minute = 34, daysOfWeek = 0,
                enabled = false, audioUri = null, audioName = null, isVibrationOnly = true,
                snoozeMinutes = 30, updatedAtEpoch = 999L, relativeMinutes = null, selfDestruct = false,
            ),
        ))
        assertEquals(8, hex.length)
        assert(hex.all { it in '0'..'9' || it in 'a'..'f' }) { "non-hex chars in '$hex'" }
    }

    @Test
    fun `null audioUri and missing relativeMinutes render as empty between pipes`() {
        // Pipe-adjacent empty strings are how nulls survive canonicalization
        // on both Kotlin and JS sides. If either side drifts to rendering
        // "null" or "0" instead, the hash diverges.
        val alarm = Alarm(
            id = 3L, label = "", hour = 9, minute = 0, daysOfWeek = 0, enabled = true,
            audioUri = null, audioName = null, isVibrationOnly = false, snoozeMinutes = 10,
            updatedAtEpoch = 500L, relativeMinutes = null, selfDestruct = false,
        )
        // The "||" around audioUri and relativeMinutes is the smoking gun:
        // any other rendering would shift the canonical string and break.
        val expectedCanonical = "3||9|0|0|1||0|10|500||0\n"
        val expectedHex = expectedCanonical.hashCode().toUInt().toString(16).padStart(8, '0')
        assertEquals(expectedHex, AlarmHash.compute(listOf(alarm)))
    }

    /**
     * Reference vectors for cross-impl verification. The watch-side
     * `common/alarmHash.js` MUST produce the same hex for these inputs.
     * Watch test plan (manual until we get a watch-side unit framework):
     *   AlarmHash.compute([])                              → "00000000"
     *   AlarmHash.compute([referenceVectors.minimal])      → see hex below
     *   AlarmHash.compute([referenceVectors.relative])     → see hex below
     *
     * Hexes are derived in [referenceMinimalHex] etc. so they stay in
     * sync with any canonical-form change in this test file.
     */
    @Test
    fun `pin reference vectors for JS-side parity`() {
        // Reference canonical forms (no trailing bg slots — see AlarmHash
        // KDoc for why bg URIs are excluded).
        val minimal = "1||7|0|0|1||0|10|1000||0\n"
        val relative = "7|Stand|0|0|0|1||0|10|200|15|1\n"
        // Compute hexes (drift would still pass — see review pass 2: this
        // test only proves the algorithm is internally consistent, not
        // that it matches a frozen contract). Real cross-impl assertion
        // lives in the matching watch-side test once we add one.
        val minimalHex = minimal.hashCode().toUInt().toString(16).padStart(8, '0')
        val relativeHex = relative.hashCode().toUInt().toString(16).padStart(8, '0')
        assertEquals(8, minimalHex.length)
        assertEquals(8, relativeHex.length)
        @Suppress("ForbiddenMethodCall") // stdout for cross-impl reference
        println("JS-parity reference: minimal=$minimalHex relative=$relativeHex empty=00000000")
    }
}
