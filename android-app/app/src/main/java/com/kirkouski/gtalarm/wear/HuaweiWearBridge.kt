package com.kirkouski.gtalarm.wear

import android.content.Context
import android.util.Log
import com.huawei.hmf.tasks.Task
import com.huawei.wearengine.HiWear
import com.huawei.wearengine.auth.AuthCallback
import com.huawei.wearengine.auth.Permission
import com.huawei.wearengine.device.Device
import com.huawei.wearengine.p2p.Message
import com.huawei.wearengine.p2p.P2pClient
import com.huawei.wearengine.p2p.PingCallback
import com.huawei.wearengine.p2p.Receiver
import com.huawei.wearengine.p2p.SendCallback
import com.kirkouski.gtalarm.data.sync.IncomingMessageHandler
import com.kirkouski.gtalarm.di.IoDispatcher
import com.kirkouski.gtalarm.domain.Alarm
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kirkouski.gtalarm.data.sync.AlarmHash
import com.kirkouski.gtalarm.data.sync.IncomingMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// reason: function count is dominated by the 7 send* overrides mandated by
// the WearBridgeService interface plus the 5 connection-lifecycle helpers.
// Splitting into two files would smear the same surface across a new ctor
// boundary for no readability gain.
@Suppress("TooManyFunctions")
@Singleton
class HuaweiWearBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WearBridgeService {

    private val supervisorJob: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(ioDispatcher + supervisorJob)

    private val _statusFlow = MutableStateFlow(WatchSyncStatus.NOT_CONNECTED)
    override val statusFlow: StateFlow<WatchSyncStatus> = _statusFlow.asStateFlow()

    private val _pairedDeviceInfo = MutableStateFlow<PairedDeviceInfo?>(null)
    override val pairedDeviceInfo: StateFlow<PairedDeviceInfo?> = _pairedDeviceInfo.asStateFlow()

    @Volatile
    private var incomingHandler: IncomingMessageHandler? = null

    @Volatile
    private var attachJob: Job? = null

    private val connectionMutex = Mutex()
    private var pairedDevice: Device? = null
    private var receiverRegistered: Boolean = false

    // Separate mutex for the ping-wake poll. Without this, sync-on-fire's N
    // concurrent sendAlarmAdded calls each launch their own
    // ensurePeerAppRunning, all miss the cache simultaneously, and N parallel
    // ping floods race the Wear Engine channel. With the mutex, only the
    // first caller polls; the rest queue and inherit the cache hit when the
    // first one succeeds.
    private val wakeMutex = Mutex()

    // Cache of "ping returned 202 (APP_RUNNING) at this wall-clock ms". Used
    // by ensurePeerAppRunning to skip the ping when we just confirmed the
    // peer is alive — sending again within PEER_RUNNING_CACHE_MS is safe
    // because the watch's receiver doesn't get torn down faster than that.
    // Invalidated on any 206 (COMM_FAIL) send result.
    @Volatile
    private var lastConfirmedRunningAtMs: Long = 0L

    private val p2pClient: P2pClient by lazy {
        HiWear.getP2pClient(context)
            .setPeerPkgName(PEER_PKG_NAME)
            .setPeerFingerPrint(PEER_FP)
    }

    // Pending sync_check round-trip. Held while forceSync's hash-precheck
    // is awaiting the watch's response. Stored as AtomicReference so the
    // receiver's read + complete is atomic against requestRemoteHash's
    // clear-on-finally: getAndSet(null) gives us a happens-before edge
    // without acquiring pendingHashMutex (which would deadlock if the
    // receiver fires inside requestRemoteHash's withLock block).
    private val pendingHashRequest =
        java.util.concurrent.atomic.AtomicReference<CompletableDeferred<String>?>(null)
    private val pendingHashMutex = Mutex()

    // Pending alarm_fired → alarm_ringing round-trip. AlarmRingService
    // calls sendAlarmFiredAwaiting BEFORE starting phone audio; the watch
    // ring page's onShow sends `alarm_ringing` back. Phone awaits that
    // reply so phone + watch ring within ~one frame instead of phone
    // beating watch by 500-1000ms on a cold-launch.
    private data class PendingRing(
        val alarmId: Long,
        val deferred: CompletableDeferred<Unit>,
    )

    private val pendingAlarmRinging =
        java.util.concurrent.atomic.AtomicReference<PendingRing?>(null)
    private val pendingAlarmRingingMutex = Mutex()

    /**
     * Receiver path for `alarm_ringing`. Extracted into its own method to
     * keep the receiver lambda's complexity in check (ComplexCondition
     * threshold). Matches by alarmId so a stale reply from a previous
     * fire can't satisfy a fresh pre-arm wait; compareAndSet guards
     * against the rare race with requestPreArm's finally-clear.
     */
    private fun handleAlarmRinging(msg: IncomingMessage.AlarmRinging) {
        val pending = pendingAlarmRinging.get() ?: run {
            Log.d(TAG, "alarm_ringing id=${msg.alarmId} but no pending request — dropping")
            return
        }
        val matches = pending.alarmId == msg.alarmId && !pending.deferred.isCompleted
        if (matches && pendingAlarmRinging.compareAndSet(pending, null)) {
            pending.deferred.complete(Unit)
            Log.i(TAG, "alarm_ringing received id=${msg.alarmId} — watch ring confirmed")
            return
        }
        Log.d(TAG, "alarm_ringing id=${msg.alarmId} mismatched pending (got ${pending.alarmId}) — dropping")
    }

    private val receiver: Receiver = Receiver { message ->
        val bytes = message.data ?: return@Receiver
        val parent = attachJob ?: return@Receiver
        scope.launch(parent) {
            val parsed = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
                .getOrNull()
                ?.let { WearJsonCodec.parseIncoming(it) }
            if (parsed == null) {
                Log.w(TAG, "received unrecognized payload (${bytes.size}B) — dropping")
                return@launch
            }
            // SyncHash is a meta-protocol response — route to the pending
            // request continuation, NOT to the LWW-applying handler.
            // getAndSet(null) atomically claims the deferred so a
            // concurrent timeout-finally in requestRemoteHash either sees
            // the cleared ref (we won the race) or already cleared it (we
            // lost — fall through to handler.handle, which logs+drops).
            if (parsed is IncomingMessage.SyncHash) {
                val pending = pendingHashRequest.getAndSet(null)
                if (pending != null && !pending.isCompleted) {
                    pending.complete(parsed.hash)
                    Log.d(TAG, "sync-check response received hash=${parsed.hash}")
                    return@launch
                }
            }
            // AlarmRinging: watch confirms its ring UI is on. Match
            // alarmId before claiming the deferred so a stale reply from a
            // prior fire can't accidentally satisfy a fresh pre-arm wait.
            // compareAndSet ensures we don't clobber a concurrent timeout-
            // finally's clear.
            if (parsed is IncomingMessage.AlarmRinging) {
                handleAlarmRinging(parsed)
                return@launch
            }
            incomingHandler?.handle(parsed)
        }
    }

    override fun sendAlarmAdded(alarm: Alarm) {
        if (!validateStamp("sendAlarmAdded", alarm.updatedAtEpoch)) return
        sendJson(buildAlarmEnvelope("alarm_added", alarm))
    }

    override fun sendAlarmUpdated(alarm: Alarm) {
        if (!validateStamp("sendAlarmUpdated", alarm.updatedAtEpoch)) return
        sendJson(buildAlarmEnvelope("alarm_updated", alarm))
    }

    override fun sendAlarmToggled(alarm: Alarm) {
        if (!validateStamp("sendAlarmToggled", alarm.updatedAtEpoch)) return
        sendJson(JSONObject().apply {
            put("type", "alarm_toggled")
            put("alarmId", alarm.id)
            put("updatedAtEpoch", alarm.updatedAtEpoch)
            put("enabled", alarm.enabled)
        })
    }

    override fun sendAlarmDeleted(alarmId: Long, updatedAtEpoch: Long) {
        if (!validateStamp("sendAlarmDeleted", updatedAtEpoch)) return
        sendJson(JSONObject().apply {
            put("type", "alarm_deleted")
            put("alarmId", alarmId)
            put("updatedAtEpoch", updatedAtEpoch)
        })
    }

    override fun sendAlarmFired(alarmId: Long) {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmFired", now)) return
        sendJson(JSONObject().apply {
            put("type", "alarm_fired")
            put("alarmId", alarmId)
            put("updatedAtEpoch", now)
        })
    }

    // Pre-arm path: send alarm_fired then AWAIT the watch's `alarm_ringing`
    // reply. Returns true iff the watch's ring page has actually rendered
    // (its onShow handler is what sends alarm_ringing). The phone uses
    // this to start its own audio in lock-step with the watch instead of
    // beating the watch by ~500-1000ms on cold-launches (the gap was
    // ping-wake + JS engine startup + page mount, all opaque to the phone
    // before this reply existed).
    //
    // Falls back to false on timeout — caller (AlarmRingService) rings
    // on phone alone, the watch ring will catch up when its app does
    // launch and re-emit `alarm_ringing` (we drop that late reply via
    // the receiver's "no pending match" path).
    override suspend fun sendAlarmFiredAwaiting(alarmId: Long, timeoutMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmFiredAwaiting", now)) return false
        val envelope = JSONObject().apply {
            put("type", "alarm_fired")
            put("alarmId", alarmId)
            put("updatedAtEpoch", now)
        }
        return pendingAlarmRingingMutex.withLock {
            val deferred = CompletableDeferred<Unit>()
            pendingAlarmRinging.set(PendingRing(alarmId, deferred))
            try {
                val outcome = withTimeoutOrNull(timeoutMs) {
                    val sent = performSend(envelope, retryOnError = true)
                    if (!sent) {
                        Log.w(TAG, "sendAlarmFiredAwaiting: alarm_fired send failed id=$alarmId")
                        return@withTimeoutOrNull false
                    }
                    deferred.await()
                    true
                }
                if (outcome == null) {
                    Log.w(
                        TAG,
                        "sendAlarmFiredAwaiting timed out after ${timeoutMs}ms id=$alarmId — " +
                            "ringing phone alone",
                    )
                    false
                } else {
                    outcome
                }
            } finally {
                // Only clear if this is still our pending entry. A concurrent
                // race (multiple fires?) shouldn't happen given the mutex,
                // but defense in depth.
                pendingAlarmRinging.updateAndGet { current ->
                    if (current?.deferred === deferred) null else current
                }
            }
        }
    }

    override fun sendAlarmDismissed(alarmId: Long) {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmDismissed", now)) return
        val envelope = JSONObject().apply {
            put("type", "alarm_dismissed")
            put("alarmId", alarmId)
            put("updatedAtEpoch", now)
        }
        sendJson(envelope, retryOnError = true)
    }

    // Suspending dismiss: wait up to timeoutMs for the watch to ACK the
    // P2P send (onSendResult=207). Used by AlarmRingService.handleDismiss
    // to make sure the watch heard us BEFORE the service tears down —
    // earlier the service stopped, the process could be killed by the
    // OS within seconds, and any in-flight broadcast got dropped (leaving
    // the watch still ringing).
    override suspend fun sendAlarmDismissedAwaiting(alarmId: Long, timeoutMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmDismissedAwaiting", now)) return false
        val envelope = JSONObject().apply {
            put("type", "alarm_dismissed")
            put("alarmId", alarmId)
            put("updatedAtEpoch", now)
        }
        return withTimeoutOrNull(timeoutMs) {
            performSend(envelope, retryOnError = true)
        } ?: run {
            Log.w(TAG, "sendAlarmDismissedAwaiting timed out id=$alarmId after ${timeoutMs}ms")
            false
        }
    }

    override fun sendAlarmSnoozed(alarmId: Long, rescheduleEpoch: Long) {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmSnoozed", now)) return
        val envelope = JSONObject().apply {
            put("type", "alarm_snoozed")
            put("alarmId", alarmId)
            put("updatedAtEpoch", now)
            put("rescheduleEpoch", rescheduleEpoch)
        }
        sendJson(envelope, retryOnError = true)
    }

    // Suspending snooze: same rationale as sendAlarmDismissedAwaiting —
    // the watch's ring page must hear about the snooze before the phone
    // service shuts down, or the watch keeps ringing.
    override suspend fun sendAlarmSnoozedAwaiting(
        alarmId: Long,
        rescheduleEpoch: Long,
        timeoutMs: Long,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmSnoozedAwaiting", now)) return false
        val envelope = JSONObject().apply {
            put("type", "alarm_snoozed")
            put("alarmId", alarmId)
            put("updatedAtEpoch", now)
            put("rescheduleEpoch", rescheduleEpoch)
        }
        return withTimeoutOrNull(timeoutMs) {
            performSend(envelope, retryOnError = true)
        } ?: run {
            Log.w(TAG, "sendAlarmSnoozedAwaiting timed out id=$alarmId after ${timeoutMs}ms")
            false
        }
    }

    override fun setIncomingHandler(handler: IncomingMessageHandler?) {
        attachJob?.cancel()
        incomingHandler = handler
        if (handler != null) {
            val newAttach = SupervisorJob(supervisorJob)
            attachJob = newAttach
            scope.launch(newAttach) { ensureReceiverRegistered() }
        } else {
            attachJob = null
            scope.launch { detachReceiver() }
        }
    }

    private suspend fun detachReceiver() {
        connectionMutex.withLock {
            if (!receiverRegistered) return@withLock
            awaitTask("unregisterReceiver") { p2pClient.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    private fun sendJson(envelope: JSONObject, retryOnError: Boolean = false) {
        scope.launch { performSend(envelope, retryOnError) }
    }

    // reason: Wear Engine surfaces ALL failure modes via WearEngineException
    // (a RuntimeException subclass) plus IllegalStateException from internal
    // binder lookups. Narrower catches would miss real errors.
    // reason: ReturnCount is 4 here because each early-return models a distinct
    // failure mode (no device / peer not running / send threw / send returned
    // false) and is logged separately for diagnosability — collapsing them
    // would lose the per-cause warning lines without changing logic.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun performSend(envelope: JSONObject, retryOnError: Boolean): Boolean {
        val device = ensurePairedDevice() ?: return false
        if (!ensurePeerAppRunning(device)) {
            Log.w(
                TAG,
                "performSend: peer app not 202 (APP_RUNNING) after wake-poll — dropping type=" +
                    envelope.optString("type"),
            )
            return false
        }
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        val msg = Message.Builder().setPayload(bytes).build()
        val result = try {
            sendOnce(device, msg)
        } catch (e: RuntimeException) {
            Log.w(TAG, "send threw: ${e.message}", e)
            invalidateAndMarkError()
            return if (retryOnError) retrySend(envelope) else false
        }
        return if (result) {
            true
        } else {
            invalidateAndMarkError()
            if (retryOnError) retrySend(envelope) else false
        }
    }

    // reason: same as performSend — Wear Engine wraps all transport errors in
    // RuntimeException subclasses; narrower catches would miss real failures.
    //
    // Important: GT 6 logs (2026-05-11) show that ping CAN return 202
    // (APP_RUNNING) while sends still fail 206 (COMM_FAIL). The Wear Engine
    // ping reports OS process aliveness, not JS-receiver bound state. So
    // re-pinging is NOT sufficient when 206 happens — we must also wait for
    // the watch JS receiver to (re)bind. This loop sleeps RETRY_BACKOFF_MS
    // before each retry, gives up after RETRY_MAX_ATTEMPTS or once the
    // total time budget RETRY_TOTAL_DEADLINE_MS is exhausted. The deadline
    // is important because retrySend can be reached from the AlarmRingService
    // pre-arm path (gated by Android's 10 s ANR window for service-start);
    // worst case must stay under ~8 s including the initial ping wait.
    //
    // reason: ReturnCount=4 covers no-device / not-running / per-attempt
    // success / final-give-up, each a distinct outcome with its own log.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun retrySend(envelope: JSONObject): Boolean {
        val device = ensurePairedDevice() ?: return false
        val type = envelope.optString("type")
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        val deadline = System.currentTimeMillis() + RETRY_TOTAL_DEADLINE_MS
        for (attempt in 1..RETRY_MAX_ATTEMPTS) {
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "retrySend hit total deadline ${RETRY_TOTAL_DEADLINE_MS}ms type=$type")
                return false
            }
            delay(RETRY_BACKOFF_MS * attempt)
            // Force re-poll because the previous 206 invalidated the cache;
            // even if ping returns 202 we still might 206 again — that's
            // what the per-attempt delay is for (gives the watch JS
            // receiver time to bind).
            if (!ensurePeerAppRunning(device, force = true)) {
                Log.w(TAG, "retrySend attempt=$attempt: peer not 202 — abandoning type=$type")
                return false
            }
            val msg = Message.Builder().setPayload(bytes).build()
            val ok = try {
                sendOnce(device, msg)
            } catch (e: RuntimeException) {
                Log.w(TAG, "retrySend attempt=$attempt threw: ${e.message}", e)
                invalidateAndMarkError()
                false
            }
            if (ok) {
                Log.i(TAG, "retrySend attempt=$attempt succeeded type=$type")
                return true
            }
            Log.w(TAG, "retrySend attempt=$attempt still 206 type=$type")
            invalidateAndMarkError()
        }
        Log.w(TAG, "retrySend gave up after $RETRY_MAX_ATTEMPTS attempts type=$type")
        return false
    }

    // Ping the peer until it returns 202 (APP_RUNNING) or the timeout is hit.
    // Lite Wearable peer apps auto-launch on incoming P2P, but the launch
    // (onCreate → setIncomingHandler → registerReceiver chain) takes 1–3
    // seconds during which sends drop with 206 (COMM_FAIL). This poll
    // closes the race: we wait for 202 before letting any send through.
    //
    // Result is cached in `lastConfirmedRunningAtMs` so a burst of sends
    // (forceSync, sync-on-fire) only pays the ping cost once.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun ensurePeerAppRunning(device: Device, force: Boolean = false): Boolean {
        // Fast-path cache check (lock-free): if a recent caller confirmed
        // running, skip the mutex + poll entirely. The `>= 0` explicit check
        // matters because System.currentTimeMillis() can move BACKWARDS
        // (NTP correction, manual clock change) — a negative `sinceConfirm`
        // is NOT in `0 until N`, so the original `in` form silently
        // re-polled on every send during a clock jump. We treat negative
        // skew as "cache is fresh enough" (the stamp is in the future, but
        // a future stamp can't be more stale than the cache window allows).
        if (!force && cacheHit()) return true
        // Serialize the poll. Concurrent callers from sync-on-fire / forceSync
        // queue here; the first one polls, the rest re-check the cache after
        // the lock is released and almost always short-circuit.
        return wakeMutex.withLock {
            if (!force && cacheHit()) return@withLock true
            pollUntilRunning(device)
        }
    }

    private fun cacheHit(): Boolean {
        val sinceConfirm = System.currentTimeMillis() - lastConfirmedRunningAtMs
        // Negative = future stamp (clock skew) — still treat as fresh.
        // 0..CACHE_MS = recently confirmed.
        return sinceConfirm < PEER_RUNNING_CACHE_MS && lastConfirmedRunningAtMs > 0L
    }

    // reason: TooGenericExceptionCaught — pingPeer throws WearEngineException
    // (RuntimeException subclass) plus IllegalStateException from internal
    // binder lookups; narrower catches would miss real failure modes.
    // ReturnCount — each branch of the ping-code `when` maps to a distinct
    // failure outcome (running / not-running-but-timeout / not-installed /
    // p2p-error / unexpected) with its own log line; collapsing them would
    // smear diagnostically-useful cases into one path.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun pollUntilRunning(device: Device): Boolean {
        val deadline = System.currentTimeMillis() + PING_WAKE_TIMEOUT_MS
        var iteration = 0
        while (true) {
            iteration++
            val code = try {
                pingPeer(device)
            } catch (e: RuntimeException) {
                Log.w(TAG, "pollUntilRunning: ping threw: ${e.message}")
                return false
            }
            Log.d(TAG, "pollUntilRunning iter=$iteration ping=$code")
            when (code) {
                PING_APP_RUNNING -> {
                    lastConfirmedRunningAtMs = System.currentTimeMillis()
                    return true
                }
                PING_APP_NOT_RUNNING -> {
                    if (System.currentTimeMillis() >= deadline) {
                        Log.w(
                            TAG,
                            "pollUntilRunning: TIMEOUT after ${PING_WAKE_TIMEOUT_MS}ms — " +
                                "watch app stuck at 201 (not running). Cold launch failed.",
                        )
                        return false
                    }
                    delay(PING_WAKE_POLL_DELAY_MS)
                }
                PING_APP_NOT_INSTALLED -> {
                    Log.w(TAG, "pollUntilRunning: peer app NOT installed (200)")
                    return false
                }
                PING_OTHER_ERROR -> {
                    Log.w(TAG, "pollUntilRunning: P2P error (203)")
                    return false
                }
                else -> {
                    Log.w(TAG, "pollUntilRunning: unexpected ping=$code, bailing")
                    return false
                }
            }
        }
        // reason: while(true) above only exits via `return` in each branch
        // of the `when`, but the Kotlin flow analyzer doesn't infer that
        // the loop is non-terminating. Adding a trailing return keeps the
        // function shape symmetric and satisfies the "must return Boolean"
        // contract without a label hack. Genuinely unreachable.
        @Suppress("UNREACHABLE_CODE") return false
    }

    // reason: ForbiddenVoid suppressed because Task<Void> is the Huawei SDK
    // signature; replacing with Task<*> loses the type information.
    @Suppress("ForbiddenVoid")
    private suspend fun sendOnce(device: Device, msg: Message): Boolean =
        suspendCancellableCoroutine { cont ->
            // Per Huawei Wear Engine docs, onSendResult is the source of
            // truth for actual delivery (207 = COMM_SUCCESS, 206 = COMM_FAIL).
            // The Task<Void> only signals dispatch acknowledgment, not
            // delivery. Resolve the continuation from the callback when
            // available; use Task.onFailure only for dispatch failure
            // (which prevents the callback from firing at all).
            val task: Task<Void> = p2pClient.send(device, msg, object : SendCallback {
                override fun onSendResult(resultCode: Int) {
                    Log.d(TAG, "send onSendResult code=$resultCode (207=ok 206=fail)")
                    if (cont.isActive) cont.resumeWith(Result.success(resultCode == COMM_SUCCESS))
                }

                override fun onSendProgress(progress: Long) {
                    Log.v(TAG, "send onSendProgress=$progress")
                }
            })
            task.addOnFailureListener { e ->
                Log.w(TAG, "send Task dispatch failed: ${e.message}")
                if (cont.isActive) cont.resumeWith(Result.success(false))
            }
        }

    private suspend fun invalidateAndMarkError() {
        connectionMutex.withLock {
            pairedDevice = null
            receiverRegistered = false
            // A 206/send-throw means the receiver channel is no longer
            // hot — drop the "peer is running" confirmation so the next
            // send re-polls instead of trusting a stale cache hit.
            lastConfirmedRunningAtMs = 0L
            _statusFlow.value = WatchSyncStatus.ERROR
        }
    }

    private suspend fun ensurePairedDevice(): Device? = connectionMutex.withLock {
        pairedDevice?.let {
            _statusFlow.value = WatchSyncStatus.CONNECTED
            return@withLock it
        }
        val candidate = if (!ensurePermissionGranted()) {
            null
        } else {
            _statusFlow.value = WatchSyncStatus.CONNECTING
            val devices = awaitTask("getBondedDevices") {
                HiWear.getDeviceClient(context).bondedDevices
            }.orEmpty()
            Log.i(TAG, "bondedDevices count=${devices.size}")
            devices.forEachIndexed { i, d ->
                Log.i(
                    TAG,
                    "  [$i] name='${d.name}' model='${d.model}' connected=${d.isConnected} uuid=${d.uuid}",
                )
            }
            devices.firstOrNull { it.isConnected } ?: devices.firstOrNull()
        }
        _statusFlow.value = if (candidate == null) {
            WatchSyncStatus.NOT_CONNECTED
        } else if (candidate.isConnected) {
            WatchSyncStatus.CONNECTED
        } else {
            WatchSyncStatus.NOT_CONNECTED
        }
        _pairedDeviceInfo.value = candidate?.let {
            PairedDeviceInfo(
                name = it.name.orEmpty(),
                model = it.model.orEmpty(),
                connected = it.isConnected,
            )
        }
        pairedDevice = candidate
        candidate
    }

    private suspend fun ensureReceiverRegistered() {
        val device = ensurePairedDevice() ?: return
        connectionMutex.withLock {
            if (pairedDevice !== device) return@withLock
            if (receiverRegistered) return@withLock
            val ok = awaitTask("registerReceiver") {
                p2pClient.registerReceiver(device, receiver)
            }
            if (ok != null) {
                receiverRegistered = true
            }
        }
    }

    private suspend fun ensurePermissionGranted(): Boolean {
        return awaitTask("checkPermission") {
            HiWear.getAuthClient(context).checkPermission(Permission.DEVICE_MANAGER)
        } == true
    }

    // reason: same as performSend — Wear Engine wraps all transport errors in
    // RuntimeException subclasses; narrower catches would miss real failures.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> awaitTask(
        op: String,
        block: () -> Task<T>,
    ): T? {
        return try {
            val task = block()
            suspendCancellableCoroutine { cont ->
                task.addOnSuccessListener { result ->
                    if (cont.isActive) cont.resumeWith(Result.success(result))
                }.addOnFailureListener { e ->
                    Log.w(TAG, "$op failed: ${e.message}")
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
                cont.invokeOnCancellation {
                    Log.d(TAG, "$op cancelled")
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "$op threw: ${e.message}", e)
            null
        }
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
            put("isVibrationOnly", alarm.isVibrationOnly)
            put("snoozeMinutes", alarm.snoozeMinutes)
            put("updatedAtEpoch", alarm.updatedAtEpoch)
            // Only include relativeMinutes when present — keeps absolute
            // alarm payloads byte-identical to the v1 wire format so old
            // watch builds parse them unchanged. Watches that understand
            // the field read it for the "Timer" label + cold-reboot recovery.
            alarm.relativeMinutes?.let { put("relativeMinutes", it) }
            // selfDestruct: always serialized so default-false is explicit
            // on the wire. Old watch builds without the field treat the
            // alarm as plain one-shot ("Once" label) which is the v1
            // behavior — correct degradation.
            put("selfDestruct", alarm.selfDestruct)
        }
        return JSONObject().apply {
            put("type", type)
            put("alarmId", alarm.id)
            put("updatedAtEpoch", alarm.updatedAtEpoch)
            put("alarm", payload)
        }
    }

    private fun validateStamp(op: String, stamp: Long): Boolean {
        if (stamp < MIN_VALID_EPOCH) {
            Log.w(TAG, "$op: dropping send — stamp $stamp below NTP-sane threshold")
            return false
        }
        return true
    }

    // reason: Wear Engine's ping is RuntimeException-heavy (P2pClient missing,
    // bonded device went stale, Huawei Health out of date) — same policy as
    // performSend's catch-all. ReturnCount: each early-return maps to a
    // user-visible distinct ForceSyncResult case; collapsing would require
    // a wrapper type or nested when, neither of which is clearer.
    // reason: CyclomaticComplexMethod fires because each guard (auth/device/
    // connected/ping-throw/ping-not-installed/wake-failed/per-alarm-loop/
    // count-buckets) maps to a distinct user-visible outcome. Splitting into
    // helpers smears the same when-case across files without changing logic.
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "CyclomaticComplexMethod")
    override suspend fun forceSync(freshAlarms: suspend () -> List<Alarm>): ForceSyncResult {
        if (!ensurePermissionGranted()) {
            Log.w(TAG, "forceSync: not authorized")
            return ForceSyncResult.NotAuthorized
        }
        val device = ensurePairedDevice() ?: run {
            Log.w(TAG, "forceSync: no bonded device")
            return ForceSyncResult.NoDevice
        }
        if (!device.isConnected) {
            Log.w(TAG, "forceSync: bonded but disconnected — '${device.name}'")
            return ForceSyncResult.Disconnected
        }
        // Wake-and-wait BEFORE iterating sends. If the peer app isn't
        // installed or never reaches 202 within the timeout, surface a
        // distinct UI outcome instead of trying N doomed sends.
        val pingCode = try {
            pingPeer(device)
        } catch (e: RuntimeException) {
            Log.w(TAG, "forceSync: ping threw: ${e.message}", e)
            return ForceSyncResult.Error(e.message.orEmpty())
        }
        Log.i(TAG, "forceSync: initial ping=$pingCode")
        if (pingCode == PING_APP_NOT_INSTALLED) {
            return ForceSyncResult.PeerAppMissing(pingCode)
        }
        if (!ensurePeerAppRunning(device, force = true)) {
            return ForceSyncResult.Error("Watch app didn't reach RUNNING within ${PING_WAKE_TIMEOUT_MS}ms")
        }
        // Sync-check optimization: ask the watch for its current AlarmHash
        // BEFORE snapshotting our alarm list. The ~2s round-trip is the
        // TOCTOU window where the user could add/edit/delete an alarm; by
        // re-fetching `alarms` AFTER the response, we hash the latest
        // state and avoid skipping a needed push.
        val remoteHash = requestRemoteHash(SYNC_CHECK_TIMEOUT_MS)
        val alarms = freshAlarms()
        Log.i(TAG, "forceSync alarms=${alarms.size}")
        val localHash = AlarmHash.compute(alarms)
        if (remoteHash != null && remoteHash == localHash) {
            Log.i(TAG, "forceSync: hashes match ($localHash) — skipping full push")
            return ForceSyncResult.AlreadyInSync(alarms.size)
        }
        Log.i(TAG, "forceSync: hashes differ local=$localHash remote=${remoteHash ?: "<timeout>"} — full push")
        // Send every alarm as alarm_added and AWAIT each result. Bypass
        // sendAlarmAdded's fire-and-forget path so we can surface a truthful
        // count to the UI ("Synced N of M alarms"). Receive-side LWW
        // handles the case where the watch already has the row. Each
        // performSend re-checks ensurePeerAppRunning via its cache hit, so
        // the ping cost is paid once for the whole burst.
        var ok = 0
        for (alarm in alarms) {
            if (!validateStamp("forceSync.send", alarm.updatedAtEpoch)) continue
            val envelope = buildAlarmEnvelope("alarm_added", alarm)
            val delivered = performSend(envelope, retryOnError = true)
            if (delivered) ok++
        }
        Log.i(TAG, "forceSync done sent=$ok of=${alarms.size}")
        return if (ok == alarms.size) {
            ForceSyncResult.Ok(ok)
        } else if (ok == 0) {
            ForceSyncResult.Error("Watch comm failed (0/${alarms.size} delivered)")
        } else {
            ForceSyncResult.Error("Partial: $ok/${alarms.size} delivered")
        }
    }

    // Round-trip a `sync_check` envelope to the watch and await its
    // `sync_hash` response. Returns the watch's hash, or null if the send
    // failed / no response arrived within `timeoutMs`. forceSync treats
    // null as "fall through to full push" — never user-blocking.
    //
    // The mutex serializes concurrent callers. Only one outstanding hash
    // request lives at a time. The atomic-ref pendingHashRequest gives
    // the receiver lock-free atomic claim of the deferred via getAndSet,
    // avoiding a mutex re-entry deadlock if the response fires while we
    // still hold pendingHashMutex.
    private suspend fun requestRemoteHash(timeoutMs: Long): String? = pendingHashMutex.withLock {
        val deferred = CompletableDeferred<String>()
        pendingHashRequest.set(deferred)
        try {
            val envelope = JSONObject().apply { put("type", "sync_check") }
            val sent = performSend(envelope, retryOnError = false)
            if (!sent) {
                Log.w(TAG, "requestRemoteHash: sync_check send failed — falling back to full push")
                return@withLock null
            }
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result == null) {
                Log.w(TAG, "requestRemoteHash: timed out after ${timeoutMs}ms")
            }
            result
        } finally {
            // compareAndSet so we don't accidentally clear a deferred set
            // by a re-entrant caller (shouldn't happen — pendingHashMutex
            // serializes — but defense in depth against future refactors).
            pendingHashRequest.compareAndSet(deferred, null)
        }
    }

    // reason: ForbiddenVoid suppressed because Task<Void> is the Huawei SDK
    // signature for `P2pClient.ping`; replacing with Task<*> would lose
    // the type information from the SDK.
    @Suppress("ForbiddenVoid")
    private suspend fun pingPeer(device: Device): Int =
        suspendCancellableCoroutine { cont ->
            val task = p2pClient.ping(device, PingCallback { code ->
                if (cont.isActive) cont.resumeWith(Result.success(code))
            })
            task.addOnFailureListener { e ->
                Log.w(TAG, "ping Task failed: ${e.message}")
                if (cont.isActive) cont.resumeWith(Result.success(PING_FAILED_SENTINEL))
            }
        }

    // reason: Wear Engine's requestPermission is documented to throw
    // RuntimeException when Huawei Health / HMS Core is missing or out of
    // date — degrade silently so the host activity isn't crashed by a
    // missing-vendor case.
    @Suppress("TooGenericExceptionCaught")
    override fun requestPermissionFromActivity(activity: android.app.Activity) {
        try {
            HiWear.getAuthClient(activity).requestPermission(
                object : AuthCallback {
                    override fun onOk(grantedPermissions: Array<out Permission>?) {
                        Log.i(TAG, "Wear Engine permission granted: ${grantedPermissions?.size ?: 0}")
                        scope.launch { ensureReceiverRegistered() }
                    }
                    override fun onCancel() {
                        Log.w(TAG, "Wear Engine permission cancelled by user")
                    }
                },
                Permission.DEVICE_MANAGER,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "requestPermission threw: ${e.message}", e)
        }
    }

    private companion object {
        const val TAG = "HuaweiWearBridge"
        const val PEER_PKG_NAME = "com.kirkouski.gtalarm.watch"

        // Format `<watch_bundleName>_<base64(raw_uncompressed_EC_pubKey)>`
        // (P-256 self-signed keystore alias `gtalarm.watch`). Extraction
        // procedure: see memory:wear_engine_lite_facts.md.
        // Tracked Phase 5b: end-to-end pairing test on real GT 6 + Samsung
        // phone validates this constant. If the SDK rejects this encoding,
        // the fallback is the watch's own keystore-cert SHA-256 (hex),
        // NOT the value in the watch's supportLists (that string carries
        // the PHONE cert SHA-256 — opposite direction).
        const val PEER_FP =
            "com.kirkouski.gtalarm.watch_" +
                "BKRRSb3Ens59ZZwrulVtsslDoBTwkSVHfkB803lwadnV7BXOg7rGXh5WrR6ddT84" +
                "gZ6qnzwbJAh5my93Tx75XbY="

        // Year 2023+ — reject sends before NTP sync after factory reset.
        const val MIN_VALID_EPOCH = 1_700_000_000_000L

        // Wear Engine ping result codes from
        // https://developer.huawei.com/consumer/en/doc/development/connectivity-Guides/errocode-0000001054450278
        const val PING_APP_NOT_INSTALLED = 200
        const val PING_APP_NOT_RUNNING = 201
        const val PING_APP_RUNNING = 202
        const val PING_OTHER_ERROR = 203

        // Wear Engine comm result codes (returned by SendCallback.onSendResult).
        const val COMM_FAIL = 206
        const val COMM_SUCCESS = 207

        // Internal sentinel for "ping Task itself failed before a callback
        // value arrived" — only used inside pingPeer to bubble the failure
        // out of the suspending continuation.
        const val PING_FAILED_SENTINEL = -1

        // Wake-poll tuning. Calibrated against real GT 6 Pro behavior
        // (2026-05-11, user-confirmed):
        //
        // - First ping that finds a dead watch app returns 201 in <500ms.
        //   The ping itself IMPLICITLY triggers Wear Engine to launch the
        //   watch app.
        // - JS engine startup on the watch (onCreate → require chain →
        //   setIncomingHandler → registerReceiver) completes in ~1 second.
        //   Until registerReceiver fires on the watch side, every send from
        //   the phone returns 206 (COMM_FAIL) — the receiver isn't bound.
        // - Once the receiver is bound, the next ping returns 202 and
        //   subsequent sends return 207 reliably.
        //
        // 10 s timeout = 10× the observed cold-launch (~1 s) — generous
        // headroom for BT contention, watch CPU pressure, and the
        // occasional GT 6 Pro launch that takes 5–8 s when the app was
        // killed minutes ago and the JS engine has to fully restart.
        // User-reported 2026-05-12: 5 s sometimes wasn't enough.
        // 400 ms poll delay catches the 201→202 transition within ~one
        // extra poll.
        const val PING_WAKE_TIMEOUT_MS = 10_000L
        const val PING_WAKE_POLL_DELAY_MS = 400L

        // Cache "peer is 202 RUNNING" for this long so a burst of sends
        // (forceSync, sync-on-fire) doesn't re-ping per message. The watch's
        // receiver tends to stay bound for tens of seconds after registration;
        // 5 s is the conservative lower bound and means at most one extra
        // ping per burst. Invalidated on any 206 or send-throw.
        const val PEER_RUNNING_CACHE_MS = 5_000L

        // On 206 (COMM_FAIL) the watch's JS receiver is either still
        // launching or has detached. Wait between retries so the receiver
        // has time to (re)bind. attempt 1 sleeps RETRY_BACKOFF_MS, attempt
        // 2 sleeps 2× — total worst-case 3 s of sleep + 2 × ping waits
        // (up to PING_WAKE_TIMEOUT_MS each). The total budget caps the
        // whole retry path so we don't blow Android's 10 s ANR window on
        // service-start callers (AlarmRingService pre-arm).
        const val RETRY_BACKOFF_MS = 1_000L
        const val RETRY_MAX_ATTEMPTS = 2
        // Total budget for the retry path. Two attempts × up to 10 s wake
        // each + 1 s backoff = 21 s worst case, but most operations resolve
        // in well under 5 s. 12 s = one full PING_WAKE attempt + a 1 s
        // backoff + most of a second. AlarmRingService callers run inside
        // serviceScope and don't hit the ANR window; bridge callers from
        // BroadcastReceivers should use shorter explicit timeouts.
        const val RETRY_TOTAL_DEADLINE_MS = 12_000L

        // Hash-precheck budget for forceSync. The watch responds with a
        // sync_hash envelope after computing the canonical hash of its
        // alarm set. Round-trip is one send + one receive, so 2 s gives
        // generous headroom even on a slow BT link. On timeout we fall
        // through to a full push — never user-blocking.
        const val SYNC_CHECK_TIMEOUT_MS = 2_000L
    }
}
