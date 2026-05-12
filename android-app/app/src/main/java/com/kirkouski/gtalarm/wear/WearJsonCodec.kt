package com.kirkouski.gtalarm.wear

import com.kirkouski.gtalarm.data.sync.IncomingMessage
import com.kirkouski.gtalarm.domain.Alarm
import org.json.JSONObject

internal object WearJsonCodec {

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
        // full force-sync.
        if (type == "sync_hash") {
            val hash = json.optString("hash", "").takeIf { it.isNotEmpty() } ?: return null
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

    private fun parseAlarm(j: JSONObject, envelopeAlarmId: Long): Alarm? {
        val missingRequired = REQUIRED_ALARM_FIELDS.any { !j.has(it) || j.isNull(it) }
        if (missingRequired) return null
        val rawSnooze = j.optInt("snoozeMinutes", Alarm.DEFAULT_SNOOZE_MINUTES)
        val daysOfWeek = j.optInt("daysOfWeek", 0)
        // Clamp peer-supplied relativeMinutes into the documented range; if
        // out-of-range or paired with daysOfWeek != 0 (illegal combination),
        // drop it to null — the Alarm domain's init block would otherwise
        // throw. Same defensive clamp as the Android edit-screen save path.
        val rawRelative = if (j.has("relativeMinutes") && !j.isNull("relativeMinutes")) {
            j.optInt("relativeMinutes", -1)
        } else {
            -1
        }
        val relativeRange = Alarm.MIN_RELATIVE_MINUTES..Alarm.MAX_RELATIVE_MINUTES
        val relativeMinutes: Int? = if (rawRelative in relativeRange && daysOfWeek == 0) {
            rawRelative
        } else {
            null
        }
        // selfDestruct + daysOfWeek != 0 is illegal (per spec). Coerce to
        // false in that case to avoid the Alarm init-block throw.
        val rawSelfDestruct = j.optBoolean("selfDestruct", false)
        val selfDestruct = rawSelfDestruct && daysOfWeek == 0
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
        )
    }

    private val REQUIRED_ALARM_FIELDS = listOf("hour", "minute", "enabled")
}
