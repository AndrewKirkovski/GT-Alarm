package com.kirkouski.gtalarm.data.sync

import com.kirkouski.gtalarm.domain.Alarm

/**
 * Deterministic 8-char hex hash of an alarm set. Used by the sync-check
 * round-trip (phone → `sync_check` → watch → `sync_hash` → phone) to skip
 * a full alarm-by-alarm push when the watch already mirrors the phone's
 * state.
 *
 * **Algorithm:** canonicalize the alarm list to a stable text representation,
 * then apply Java `String.hashCode()`. The hashCode algorithm is spec'd as
 * `h * 31 + c` iterated over UTF-16 code units — trivially mirrored in JS
 * on the watch side (`((h << 5) - h + s.charCodeAt(i)) | 0`).
 *
 * **Canonical form:** alarms sorted by id ascending, each rendered as a
 * pipe-delimited line `id|hour|minute|daysOfWeek|enabled|audioUri|
 * isVibrationOnly|snoozeMinutes|updatedAtEpoch|relativeMinutes|selfDestruct`
 * with `\n` separator. Booleans render as `1`/`0`; null audioUri / null
 * relativeMinutes render as empty string between the surrounding pipes.
 *
 * Match the JS implementation in `watch-app/.../common/alarmHash.js` BYTE
 * FOR BYTE — any divergence makes the hash compare always-fail and the
 * check becomes useless overhead.
 *
 * **Intentionally NOT included** in the canonical form:
 *   - `label` — not part of the watch wire format at all; the alarm label
 *     is phone-only (shown on the phone ring screen). The watch never
 *     receives it, so including it here would force a permanent mismatch.
 *   - `backgroundImageUri` — device-local `content://` path that doesn't
 *     round-trip between phone and watch (watch never has the same URI).
 *     Reconciliation of the watch image happens via file-transfer of the
 *     BGRA `.bin` blob, orthogonal to this hash. Code-review pass 1
 *     (2026-05-13) confirmed including it broke the AlreadyInSync
 *     short-circuit on every sync.
 */
object AlarmHash {
    fun compute(alarms: List<Alarm>): String {
        val canonical = canonicalize(alarms)
        return canonical.hashCode().toUInt().toString(16).padStart(HEX_WIDTH, '0')
    }

    private fun canonicalize(alarms: List<Alarm>): String {
        val sb = StringBuilder()
        alarms.sortedBy { it.id }.forEach { a ->
            sb.append(a.id).append('|')
                .append(a.hour).append('|')
                .append(a.minute).append('|')
                .append(a.daysOfWeek).append('|')
                .append(if (a.enabled) '1' else '0').append('|')
                .append(a.audioUri.orEmpty()).append('|')
                .append(if (a.isVibrationOnly) '1' else '0').append('|')
                .append(a.snoozeMinutes).append('|')
                .append(a.updatedAtEpoch).append('|')
                .append(a.relativeMinutes?.toString().orEmpty()).append('|')
                .append(if (a.selfDestruct) '1' else '0')
                .append('\n')
        }
        return sb.toString()
    }

    private const val HEX_WIDTH = 8
}
