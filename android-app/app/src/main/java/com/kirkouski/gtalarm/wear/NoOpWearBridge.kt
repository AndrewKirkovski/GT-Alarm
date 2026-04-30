package com.kirkouski.gtalarm.wear

import android.util.Log
import com.kirkouski.gtalarm.data.sync.IncomingMessageHandler
import com.kirkouski.gtalarm.domain.Alarm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // No peer to connect to — pinned at NOT_CONNECTED for the lifetime of the
    // process. The future HuaweiWearBridge will mutate its own MutableStateFlow
    // from Wear-Engine connection callbacks.
    private val _statusFlow = MutableStateFlow(WatchSyncStatus.NOT_CONNECTED)
    override val statusFlow: StateFlow<WatchSyncStatus> = _statusFlow.asStateFlow()

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

    @Volatile
    private var incomingHandler: IncomingMessageHandler? = null

    override fun setIncomingHandler(handler: IncomingMessageHandler?) {
        incomingHandler = handler
        Log.d(TAG, "setIncomingHandler(${if (handler == null) "null" else "set"}) — no watch connected (stub)")
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
