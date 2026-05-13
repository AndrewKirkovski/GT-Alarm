package com.kirkouski.gtalarm.wear

import android.util.Log
import com.kirkouski.gtalarm.data.sync.IncomingMessage
import com.kirkouski.gtalarm.domain.Alarm
import org.json.JSONObject

internal object WearJsonCodec {

    private const val TAG = "WearJsonCodec"

    // Hash MUST be exactly 8 lowercase hex chars (Java String.hashCode →
    // unsigned 32-bit → lowercase hex, see AlarmHash). Any non-matching
    // payload is treated as corrupt and dropped — accepting anything would
    // open the door to spoofed "match" answers that silently skip a real
    // push.
    private val HASH_REGEX = Regex("^[0-9a-f]{8}$")

    private val DISPATCH: Map<String, (Long, Long, JSONObject) -> IncomingMessage?> = mapOf(
        "alarm_added" to { id, ts, j -> j.optJSONObject("alarm")?.let { payload ->
            parseAlarm(payload, id)?.let { IncomingMessage.AlarmAdded(id, ts, it) }
        } },
        "alarm_updated" to { id, ts, j -> j.optJSONObject("alarm")?.let { payload ->
            parseAlarm(payload, id)?.let { IncomingMessage.AlarmUpdated(id, ts, it) }
        } },
        "alarm_toggled" to { id, ts, j ->
            if (!j.has("enabled") || j.isNull("enabled")) null
            else IncomingMessage.AlarmToggled(id, ts, j.getBoolean("enabled"))
        },
        "alarm_deleted" to { id, ts, _ -> IncomingMessage.AlarmDeleted(id, ts) },
        "alarm_fired" to { id, ts, _ -> IncomingMessage.AlarmFired(id, ts) },
        "alarm_dismissed" to { id, ts, _ -> IncomingMessage.AlarmDismissed(id, ts) },
        "alarm_snoozed" to { id, ts, _ -> IncomingMessage.AlarmSnoozed(id, ts) },
        "alarm_ringing" to { id, ts, _ -> IncomingMessage.AlarmRinging(id, ts) },
    )

    // reason: ReturnCount=5 covers: missing type / watch_log envelope /
    // sync_hash envelope / malformed alarm envelope / dispatch result.
    // Each return matches a distinct wire-format shape; collapsing them
    // would smear unrelated parsing logic together.
    @Suppress("ReturnCount")
    fun parseIncoming(json: JSONObject): IncomingMessage? {
        val type = json.optString("type").takeIf { it.isNotEmpty() } ?: return null
        // Dev-only log relay from the watch. Doesn't carry alarmId/epoch in
        // the standard sense, so handle it before the regular envelope
        // validation that would otherwise reject it.
        if (type == "watch_log") {
            val level = json.optString("level", "I")
            val msg = json.optString("msg", "")
            val ts = json.optLong("ts", System.currentTimeMillis())
            return IncomingMessage.WatchLog(alarmId = 0L, updatedAtEpoch = ts, level = level, msg = msg)
        }
        // Sync-check response from the watch. Carries only `hash`; no
        // alarmId/updatedAtEpoch on the wire (this is a meta-protocol
        // message). Phone uses the hash to decide whether to skip the
        // full force-sync. Strict 8-char lowercase hex validation —
        // anything else means a corrupted or spoofed payload and we drop
        // it (caller will time out and fall through to a full push).
        if (type == "sync_hash") {
            val hash = json.optString("hash", "")
            if (!HASH_REGEX.matches(hash)) {
                Log.w(TAG, "sync_hash dropped — malformed hash='$hash'")
                return null
            }
            return IncomingMessage.SyncHash(alarmId = 0L, updatedAtEpoch = 0L, hash = hash)
        }
        val alarmId = json.optLong("alarmId", -1L).takeIf { it >= 0L }
        val ts = json.optLong("updatedAtEpoch", -1L).takeIf { it >= 0L }
        return if (alarmId == null || ts == null) {
            null
        } else {
            DISPATCH[type]?.invoke(alarmId, ts, json)
        }
    }

    /**
     * Parse a peer-supplied alarm payload. **Rejects** (returns null +
     * loud log) on any invariant violation rather than silently coercing.
     *
     * Why reject-not-coerce: if we accepted `{ selfDestruct: true,
     * daysOfWeek: MON }` by quietly stripping selfDestruct, the phone
     * would then re-broadcast the alarm with selfDestruct=false on the
     * next user action — permanently overwriting the peer's intended
     * state. Both sides agree on the wrong value and AlarmHash matches.
     * The bug becomes invisible. Better to drop the envelope and let
     * the peer's next push reach us in a valid shape (or surface a sync
     * mismatch via the hash precheck).
     *
     * The only coercion we still do is `snoozeMinutes` clamping into
     * [MIN_SNOOZE_MINUTES, MAX_SNOOZE_MINUTES], because that range is a
     * UI-level constraint and out-of-range values are typically legacy
     * data from earlier app versions where the bounds were different.
     */
    // reason: complexity rose to 11 because each invariant check (missing-
    // required / relativeMinutes-out-of-range / relativeMinutes-with-days /
    // selfDestruct-with-days) is a distinct rejection path with its own
    // diagnostic log. Extracting into helpers would smear the same chain
    // across more functions without changing the early-return logic.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun parseAlarm(j: JSONObject, envelopeAlarmId: Long): Alarm? {
        val missingRequired = REQUIRED_ALARM_FIELDS.any { !j.has(it) || j.isNull(it) }
        if (missingRequired) {
            Log.w(TAG, "parseAlarm rejected — missing required field for id=$envelopeAlarmId")
            return null
        }
        val daysOfWeek = j.optInt("daysOfWeek", 0)
        val rawRelative = if (j.has("relativeMinutes") && !j.isNull("relativeMinutes")) {
            j.optInt("relativeMinutes", Int.MIN_VALUE)
        } else {
            Int.MIN_VALUE
        }
        val relativeRange = Alarm.MIN_RELATIVE_MINUTES..Alarm.MAX_RELATIVE_MINUTES
        val relativeMinutes: Int? = when {
            rawRelative == Int.MIN_VALUE -> null
            rawRelative !in relativeRange -> {
                Log.w(
                    TAG,
                    "parseAlarm rejected id=$envelopeAlarmId — " +
                        "relativeMinutes=$rawRelative outside [${relativeRange.first}, ${relativeRange.last}]",
                )
                return null
            }
            daysOfWeek != 0 -> {
                Log.w(
                    TAG,
                    "parseAlarm rejected id=$envelopeAlarmId — " +
                        "relativeMinutes=$rawRelative with daysOfWeek=$daysOfWeek (illegal combo)",
                )
                return null
            }
            else -> rawRelative
        }
        val selfDestruct = j.optBoolean("selfDestruct", false)
        if (selfDestruct && daysOfWeek != 0) {
            Log.w(
                TAG,
                "parseAlarm rejected id=$envelopeAlarmId — " +
                    "selfDestruct=true with daysOfWeek=$daysOfWeek (illegal combo)",
            )
            return null
        }
        val rawSnooze = j.optInt("snoozeMinutes", Alarm.DEFAULT_SNOOZE_MINUTES)
        return Alarm(
            id = envelopeAlarmId,
            label = j.optString("label", ""),
            hour = j.getInt("hour"),
            minute = j.getInt("minute"),
            daysOfWeek = daysOfWeek,
            enabled = j.getBoolean("enabled"),
            audioUri = j.optString("audioUri", "").takeIf { it.isNotEmpty() },
            audioName = null,
            isVibrationOnly = j.optBoolean("isVibrationOnly", false),
            snoozeMinutes = rawSnooze.coerceIn(Alarm.MIN_SNOOZE_MINUTES, Alarm.MAX_SNOOZE_MINUTES),
            updatedAtEpoch = j.optLong("updatedAtEpoch", 0L),
            relativeMinutes = relativeMinutes,
            selfDestruct = selfDestruct,
            // Per-alarm background image URI. Peer (watch) does not currently
            // consume this — it's preserved for hash equality + future use.
            // Empty string on the wire is treated as null so an absent /
            // explicit-no-bg peer payload doesn't bind a useless string.
            backgroundImageUri = j.optString("backgroundImageUri", "").takeIf { it.isNotEmpty() },
        )
    }

    private val REQUIRED_ALARM_FIELDS = listOf("hour", "minute", "enabled")
}
