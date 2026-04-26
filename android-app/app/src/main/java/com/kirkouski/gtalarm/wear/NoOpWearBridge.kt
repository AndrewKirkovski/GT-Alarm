package com.kirkouski.gtalarm.wear

import android.util.Log
import com.kirkouski.gtalarm.domain.Alarm
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op wear bridge that logs the JSON shape that a real `HuaweiWearBridge`
 * will eventually send over Wear Engine P2P. Logging the actual payload
 * (rather than just scalar fields) lets dev verification confirm wire-format
 * parity with the watch's `WearBridge.send` log line — same JSON keys, same
 * type strings, same field types.
 */
@Singleton
class NoOpWearBridge @Inject constructor() : WearBridgeService {

    override fun sendAlarmAdded(alarm: Alarm) {
        require(alarm.updatedAtEpoch > 0L) { "sendAlarmAdded: stamp must be > 0" }
        log(buildAlarmEnvelope("alarm_added", alarm))
    }

    override fun sendAlarmUpdated(alarm: Alarm) {
        require(alarm.updatedAtEpoch > 0L) { "sendAlarmUpdated: stamp must be > 0" }
        log(buildAlarmEnvelope("alarm_updated", alarm))
    }

    override fun sendAlarmToggled(alarm: Alarm) {
        require(alarm.updatedAtEpoch > 0L) { "sendAlarmToggled: stamp must be > 0" }
        val obj = buildAlarmEnvelope("alarm_toggled", alarm)
        obj.put("enabled", alarm.enabled)
        log(obj)
    }

    override fun sendAlarmDeleted(alarmId: Long, updatedAtEpoch: Long) {
        require(updatedAtEpoch > 0L) { "sendAlarmDeleted: stamp must be > 0" }
        log(JSONObject().apply {
            put("type", "alarm_deleted")
            put("alarmId", alarmId)
            put("updatedAtEpoch", updatedAtEpoch)
        })
    }

    override fun sendAlarmFired(alarmId: Long) {
        log(JSONObject().apply {
            put("type", "alarm_fired")
            put("alarmId", alarmId)
            put("updatedAtEpoch", System.currentTimeMillis())
        })
    }

    override fun sendAlarmDismissed(alarmId: Long) {
        log(JSONObject().apply {
            put("type", "alarm_dismissed")
            put("alarmId", alarmId)
            put("updatedAtEpoch", System.currentTimeMillis())
        })
    }

    override fun sendAlarmSnoozed(alarmId: Long, rescheduleEpoch: Long) {
        log(JSONObject().apply {
            put("type", "alarm_snoozed")
            put("alarmId", alarmId)
            put("updatedAtEpoch", System.currentTimeMillis())
            put("rescheduleEpoch", rescheduleEpoch)
        })
    }

    override fun startListening(onDismiss: (Long) -> Unit, onSnooze: (Long) -> Unit) {
        Log.d(TAG, "startListening() — no watch connected (stub)")
    }

    override fun stopListening() {
        Log.d(TAG, "stopListening()")
    }

    private fun buildAlarmEnvelope(type: String, alarm: Alarm): JSONObject {
        val payload = JSONObject().apply {
            put("id", alarm.id)
            put("label", alarm.label)
            put("hour", alarm.hour)
            put("minute", alarm.minute)
            put("daysOfWeek", alarm.daysOfWeek)
            put("enabled", alarm.enabled)
            put("audioUri", alarm.audioUri)
            put("audioName", alarm.audioName)
            put("isVibrationOnly", alarm.isVibrationOnly)
            put("updatedAtEpoch", alarm.updatedAtEpoch)
        }
        return JSONObject().apply {
            put("type", type)
            put("alarmId", alarm.id)
            put("updatedAtEpoch", alarm.updatedAtEpoch)
            put("alarm", payload)
        }
    }

    private fun log(msg: JSONObject) {
        Log.d(TAG, "WearBridge: $msg")
    }

    private companion object {
        const val TAG = "WearBridge"
    }
}
