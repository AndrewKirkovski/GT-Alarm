package com.kirkouski.gtwake.companion.wear

import android.content.Context
import android.util.Log
import com.huawei.hmf.tasks.Task
import com.huawei.wearengine.HiWear
import com.huawei.wearengine.auth.AuthCallback
import com.huawei.wearengine.auth.Permission
import com.huawei.wearengine.device.Device
import com.huawei.wearengine.monitor.MonitorClient
import com.huawei.wearengine.monitor.MonitorItem
import com.huawei.wearengine.monitor.MonitorListener
import com.huawei.wearengine.p2p.Message
import com.huawei.wearengine.p2p.P2pClient
import com.huawei.wearengine.p2p.PingCallback
import com.huawei.wearengine.p2p.Receiver
import com.huawei.wearengine.p2p.SendCallback
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.data.sync.IncomingMessageHandler
import com.kirkouski.gtwake.companion.di.IoDispatcher
import com.kirkouski.gtwake.companion.domain.Alarm
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kirkouski.gtwake.companion.data.sync.AlarmHash
import com.kirkouski.gtwake.companion.data.sync.IncomingMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// reason: function count is dominated by the 7 send* overrides mandated by
// the WearBridgeService interface plus the 5 connection-lifecycle helpers.
// Splitting into two files would smear the same surface across a new ctor
// boundary for no readability gain.
@Suppress("TooManyFunctions", "LargeClass")
// reason: LargeClass — bridge class spans the full Wear Engine surface (auth
// + ping + send + receive + file transfer + status flow + 12 helpers). Each
// is tied to the others by shared connection state (mDevice, mClient,
// connection mutex). Splitting would require duplicating that state across
// classes; the natural seam (file transfer in its own collaborator) is
// already partial via WatchBackgroundEncoder. Revisit when receive-side
// state grows further.
//
// ─────────────────────────────────────────────────────────────────────
// WEAR ENGINE ACTIVITY RULE — Huawei Wear Engine P2P *receive* only works
// while an Activity is in the task. Sends may originate anywhere (this is
// a Service-injected @Singleton), but any method that AWAITS a reply from
// the watch — sendAlarmFiredAwaiting, sendAlarmDismissedAwaiting,
// sendAlarmSnoozedAwaiting (→ sendAwaitingAck), and the receiver-side
// completions of alarm_ringing / *_ack — only resolves while an Activity
// (AlarmActivity) is alive in the task. A caller that tears the Activity
// down and THEN awaits a reply will time out every time — that was the
// "watch dismiss never propagates" bug. Callers MUST keep AlarmActivity
// alive for the whole round-trip.
// ─────────────────────────────────────────────────────────────────────
@Singleton
class HuaweiWearBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val settingsStore: SettingsStore,
) : WearBridgeService {

    private val supervisorJob: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(ioDispatcher + supervisorJob)

    private val _statusFlow = MutableStateFlow(WatchSyncStatus.NOT_CONNECTED)
    override val statusFlow: StateFlow<WatchSyncStatus> = _statusFlow.asStateFlow()

    private val _pairedDeviceInfo = MutableStateFlow<PairedDeviceInfo?>(null)
    override val pairedDeviceInfo: StateFlow<PairedDeviceInfo?> = _pairedDeviceInfo.asStateFlow()

    // Connection-status monitor (event-driven, no polling). Obtained lazily so
    // non-HMS devices never touch it. The listener is light: each change event
    // just re-runs the authoritative getBondedDevices() query, because the
    // monitor payload's int mapping is undocumented (verified by decompiling the
    // pinned AAR). Registered for the UI's lifetime only — never a bg service.
    private val monitorClient: MonitorClient by lazy { HiWear.getMonitorClient(context) }

    @Volatile
    private var connectionMonitor: MonitorListener? = null
    @Volatile
    private var connectionMonitorDeviceUuid: String? = null

    // Per-connection bg-delivery guard (see WearBridgeService.markBgDelivered).
    // Reset to null on any disconnect so a fresh connection re-delivers once.
    @Volatile
    private var deliveredBgHash: String? = null

    @Volatile
    private var incomingHandler: IncomingMessageHandler? = null

    // F4 default-watch-bg reconciler. Registered by GtAlarmApp (the bridge
    // can't hold AlarmRepository — that would be a DI cycle). Invoked from
    // persistWatchScreen with the marker the watch reported in watch_screen.bg.
    @Volatile
    private var watchBgReconciler: (suspend (String) -> Unit)? = null

    @Volatile
    private var attachJob: Job? = null

    private val connectionMutex = Mutex()
    private val connectionMonitorMutex = Mutex()
    @Volatile
    private var pairedDevice: Device? = null
    private var pairedDeviceResolvedAtElapsedMs: Long = 0L

    // Uuid of the device the receiver is registered for. Changed together with
    // [receiverRegistered]: cleared by [unregisterReceiverLocked], set or cleared
    // by the register result in [ensureReceiverRegisteredFor].
    @Volatile
    private var receiverDeviceUuid: String? = null
    @Volatile
    private var receiverRegistered: Boolean = false

    // True while the SDK may hold a registration we do not know about: a
    // register or unregister call that timed out (or was cancelled) may still
    // complete later. A definite failure does not set it. While it is set, the
    // next register is preceded by an unregister (idempotent). Guarded by
    // connectionMutex.
    private var receiverRegistrationUncertain: Boolean = false

    // Unregister calls whose Task has not reached a terminal result (success or
    // failure) yet. A timed-out unregister is still pending in the SDK and may
    // complete later, possibly after a newer register, and remove it. While any
    // entry is live we never register. Entries are removed by the Task's own
    // listener (UI thread, however late), so the set is concurrent and removal
    // of an already-removed (abandoned) entry is a harmless no-op: it can never
    // go "negative".
    private class PendingUnregister(val dispatchedAtMs: Long)

    private val pendingUnregisters: MutableSet<PendingUnregister> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    // Safety valve clock (SystemClock.elapsedRealtime is 0 in JVM unit tests).
    @Volatile
    internal var elapsedClock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    // Entries still tracked, including ones that are only waiting to be abandoned.
    internal val outstandingUnregisterCount: Int
        get() = pendingUnregisters.size

    /**
     * True while a timed-out unregister may still complete. An unregister that
     * has been pending longer than [ABANDON_UNREGISTER_AFTER_MS] is treated as
     * lost (a hung Huawei Health binder call that will never answer): it is
     * dropped from the set so registration cannot stay blocked until the
     * process restarts. If it does complete after all, its listener finds the
     * entry gone and does nothing.
     */
    private fun hasOutstandingUnregister(): Boolean {
        val now = elapsedClock()
        var blocking = false
        val entries = pendingUnregisters.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (now - entry.dispatchedAtMs > ABANDON_UNREGISTER_AFTER_MS) {
                Log.w(TAG, "unregister outstanding for ${now - entry.dispatchedAtMs}ms — treating it as abandoned")
                entries.remove()
            } else {
                blocking = true
            }
        }
        return blocking
    }

    // Uuid of the device last seen CONNECTED. forceScreenRefresh is armed only
    // when this changes, not on every re-resolve. Guarded by connectionMutex.
    private var lastConnectedDeviceUuid: String? = null

    // (alarmId, firedAt) of the ring for which a reply WITH firedAt already
    // matched. From then on a legacy reply (no firedAt) must not satisfy a
    // pending request of that ring: an updated watch always sends firedAt, so a
    // legacy-shaped message in that ring is a stale or foreign copy.
    private val strictRing = java.util.concurrent.atomic.AtomicReference<Pair<Long, Long>?>(null)

    // Separate mutex for the ping-wake poll. Without this, concurrent sends
    // each launch their own ensurePeerAppRunning, all miss the cache
    // simultaneously, and N parallel ping floods race the Wear Engine
    // channel. With the mutex, only the first caller polls; the rest queue
    // and inherit the cache hit when the first one succeeds.
    private val wakeMutex = Mutex()

    // Cache of "ping returned 202 (APP_RUNNING) at this wall-clock ms". Used
    // by ensurePeerAppRunning to skip the ping when we just confirmed the
    // peer is alive — sending again within PEER_RUNNING_CACHE_MS is safe
    // because the watch's receiver doesn't get torn down faster than that.
    // Invalidated on any 206 (COMM_FAIL) send result.
    @Volatile
    private var lastConfirmedRunningAtMs: Long = 0L

    // Debounce for the phone-initiated screen_request — collapses rapid
    // forceSyncs into one in-flight request (see maybeRequestWatchScreen).
    @Volatile
    private var screenRequestInFlightUntilMs: Long = 0L

    // Set on a fresh (re)connect so the NEXT screen handshake bypasses the
    // model gate once. Without it, screen_request only fires when the bonded
    // model changes, so the F4 bg reconcile (which rides the watch_screen
    // reply) never re-runs after a watch reset that kept the same model — the
    // image would stay lost. A reconnect always re-resolves pairedDevice, so
    // that's our "watch may have been reset" signal.
    @Volatile
    private var forceScreenRefresh: Boolean = false

    // Set by refreshWatchScreen so the watch_screen reply it triggers does not
    // run the bg reconcile (see persistWatchScreen).
    @Volatile private var skipNextBgReconcile: Boolean = false

    private val p2pClient: P2pClient by lazy {
        HiWear.getP2pClient(context)
            .setPeerPkgName(PEER_PKG_NAME)
            .setPeerFingerPrint(PEER_FP)
    }

    // Pending sync_check round-trip. Held while forceSync's hash-precheck
    // is awaiting the watch's response. Stored as AtomicReference so the
    // receiver's read + complete is atomic against requestRemoteHash's
    // clear-on-finally: compareAndSet gives us a happens-before edge
    // without acquiring pendingHashMutex (which would deadlock if the
    // receiver fires inside requestRemoteHash's withLock block).
    private data class PendingHashRequest(
        val updatedAtEpoch: Long,
        val deferred: CompletableDeferred<IncomingMessage.SyncHash>,
    )

    private val pendingHashRequest =
        java.util.concurrent.atomic.AtomicReference<PendingHashRequest?>(null)
    private val pendingHashMutex = Mutex()

    // Pending bg-file → `bg_received` app-level ack. The watch's file handler
    // sends `bg_received` with the hash AFTER it actually stores the file, which
    // is the only proof the JS layer processed it (207 only means the Wear
    // Engine layer accepted delivery — the watch app may have terminated mid-
    // send). uploadDefaultWatchBackground retries until this resolves.
    private val pendingBgAck =
        java.util.concurrent.atomic.AtomicReference<Pair<String, CompletableDeferred<Unit>>?>(null)

    // App-level ack for the file-based full-state replace (the alarm list sent as
    // a FILE when it exceeds the text-message ceiling — see pushFileReplace). The
    // watch sends `alarms_received` with the file's hash AFTER it parses + stores
    // the list, which is the only proof the JS layer applied it (207 only means
    // Wear Engine accepted delivery; the thin-client watch may terminate mid-send
    // before its receiver activated). pushFileReplace retries until this resolves.
    private data class PendingAlarmsAck(
        val hash: String,
        val updatedAtEpoch: Long,
        val requireStamp: Boolean,
        val deferred: CompletableDeferred<Unit>,
    )

    private val pendingAlarmsAck =
        java.util.concurrent.atomic.AtomicReference<PendingAlarmsAck?>(null)

    // Pending alarm_fired → alarm_ringing round-trip. AlarmRingService
    // calls sendAlarmFiredAwaiting BEFORE starting phone audio; the watch
    // ring page's onShow sends `alarm_ringing` back. Phone awaits that
    // reply so phone + watch ring within ~one frame instead of phone
    // beating watch by 500-1000ms on a cold-launch.
    private data class PendingRing(
        val alarmId: Long,
        val firedAtEpoch: Long?,
        val requestStamp: Long,
        val deferred: CompletableDeferred<Unit>,
    )

    private val pendingAlarmRinging =
        java.util.concurrent.atomic.AtomicReference<PendingRing?>(null)
    private val pendingAlarmRingingMutex = Mutex()

    // alarmId -> elapsedRealtime of the last `alarm_fired` the transport
    // confirmed (207). elapsedRealtime, not wall time, so an NTP or manual
    // clock change mid-ring cannot make a real delivery look older than the
    // ring's start. AlarmRingService reads it to decide whether a dismiss/snooze
    // must go to the watch: if no fire ever reached the watch for this ring,
    // sending one would only launch the watch app for nothing (a visible
    // wake right after the alarm, which review flagged).
    private val alarmFiredDeliveredAt =
        java.util.concurrent.ConcurrentHashMap<Long, Long>()

    // Pending alarm_dismissed → alarm_dismissed_ack round-trip. The 207
    // transport ACK on dismiss only proves "delivered to watch's Wear
    // Engine layer", NOT "watch's JS receiver processed it" — the JS
    // receiver can be in a transient state (mid-bundle-load, mid-page-
    // transition) and silently drop the envelope. Without an application-
    // level reply the phone has no way to detect this, and the user has
    // to manually dismiss on the watch too. The watch's incomingHandler
    // sends `alarm_dismissed_ack` after `onPeerEndedRing(alarmId)` runs.
    private data class PendingAck(
        val alarmId: Long,
        val firedAtEpoch: Long?,
        val updatedAtEpoch: Long,
        val deferred: CompletableDeferred<Unit>,
    )

    private val pendingDismissAck =
        java.util.concurrent.atomic.AtomicReference<PendingAck?>(null)
    private val pendingDismissAckMutex = Mutex()

    // Symmetric to pendingDismissAck for snooze.
    private val pendingSnoozeAck =
        java.util.concurrent.atomic.AtomicReference<PendingAck?>(null)
    private val pendingSnoozeAckMutex = Mutex()

    /**
     * Shared completion path for the dismiss / snooze ack deferreds.
     * Matching rules are in [replyMatches] (strict on firedAt + stamp, alarm
     * ID only for legacy watches); compareAndSet guards against the rare race
     * with the awaiter's timeout-finally clear.
     */
    private fun completePendingAck(
        slot: java.util.concurrent.atomic.AtomicReference<PendingAck?>,
        ack: IncomingMessage.AlarmDismissedAck,
        label: String,
    ) {
        // Watch's application-level ack also proves the receiver is bound
        // and JS layer is healthy — same as alarm_ringing's effect on the
        // running cache. Refresh stamp so we don't re-ping needlessly on
        // any subsequent send in the immediate window.
        lastConfirmedRunningAtMs = System.currentTimeMillis()
        val pending = slot.get() ?: run {
            Log.d(TAG, "$label id=${ack.alarmId} but no pending request — dropping")
            return
        }
        val matches = replyMatches(
            pending.alarmId,
            pending.firedAtEpoch,
            pending.updatedAtEpoch,
            ack.alarmId,
            ack.firedAtEpoch,
            ack.updatedAtEpoch,
            ack.ok,
        ) && !pending.deferred.isCompleted
        if (matches && slot.compareAndSet(pending, null)) {
            noteStrictMatch(pending.alarmId, pending.firedAtEpoch, ack.firedAtEpoch)
            pending.deferred.complete(Unit)
            Log.i(TAG, "$label received id=${ack.alarmId} — watch confirmed action")
            return
        }
        Log.d(TAG, "$label id=${ack.alarmId} mismatched or negative ACK (pending=${pending.alarmId}) — dropping")
    }

    /**
     * Decides whether a watch reply satisfies the phone's pending request.
     *
     * - A reply WITHOUT `firedAt` is legacy (released watches, 1.0.11 and
     *   older, never send it): it matches by alarm ID only, the rule these
     *   builds were written against. The phone always stores `firedAt` in its
     *   pending request now, so requiring it on both sides would reject every
     *   released watch.
     * - A reply WITH `firedAt` comes from an occurrence-aware watch: `firedAt`
     *   AND the request stamp must match, and `ok` must not be false.
     * - Once a strict reply matched for a ring ([noteStrictMatch]), legacy
     *   replies no longer match that ring.
     */
    // reason: the seven values are the two sides of one correlation; a wrapper type would only rename them.
    @Suppress("LongParameterList")
    private fun replyMatches(
        pendingAlarmId: Long,
        pendingFiredAt: Long?,
        pendingStamp: Long,
        replyAlarmId: Long,
        replyFiredAt: Long?,
        replyStamp: Long,
        replyOk: Boolean?,
    ): Boolean {
        if (replyOk == false || pendingAlarmId != replyAlarmId) return false
        if (replyFiredAt == null) {
            return pendingFiredAt == null || strictRing.get() != (pendingAlarmId to pendingFiredAt)
        }
        return replyFiredAt == pendingFiredAt && replyStamp == pendingStamp
    }

    private fun noteStrictMatch(alarmId: Long, pendingFiredAt: Long?, replyFiredAt: Long?) {
        if (pendingFiredAt != null && replyFiredAt != null) {
            strictRing.set(alarmId to pendingFiredAt)
        }
    }

    /**
     * Receiver path for `alarm_ringing`. Extracted into its own method to
     * keep the receiver lambda's complexity in check (ComplexCondition
     * threshold). Matching rules are in [replyMatches], so a stale reply
     * from a previous fire can't satisfy a fresh pre-arm wait; compareAndSet
     * guards against the rare race with requestPreArm's finally-clear.
     */
    private fun handleAlarmRinging(msg: IncomingMessage.AlarmRinging) {
        // The watch just confirmed its ring page is up — peer is
        // definitively running and the receiver is bound. Refresh the
        // running-cache stamp so subsequent sends in the ring window
        // (dismiss/snooze) skip the wake-poll. Without this, the dismiss
        // ~5+ seconds later would re-ping for no reason — user-noticed
        // 2026-05-13: "why we need 'reconnecting' to watch to dismiss".
        lastConfirmedRunningAtMs = System.currentTimeMillis()
        val pending = pendingAlarmRinging.get() ?: run {
            Log.d(TAG, "alarm_ringing id=${msg.alarmId} but no pending request — dropping")
            return
        }
        val matches = replyMatches(
            pending.alarmId,
            pending.firedAtEpoch,
            pending.requestStamp,
            msg.alarmId,
            msg.firedAtEpoch,
            msg.updatedAtEpoch,
            null,
        ) && !pending.deferred.isCompleted
        if (matches && pendingAlarmRinging.compareAndSet(pending, null)) {
            noteStrictMatch(pending.alarmId, pending.firedAtEpoch, msg.firedAtEpoch)
            pending.deferred.complete(Unit)
            Log.i(TAG, "alarm_ringing received id=${msg.alarmId} — watch ring confirmed")
            return
        }
        Log.d(TAG, "alarm_ringing id=${msg.alarmId} mismatched pending (got ${pending.alarmId}) — dropping")
    }

    /**
     * Expand a watch-side `watch_log_batch` envelope to one logcat line
     * per entry under the `WatchLog` tag. Each line keeps the watch's own
     * `ts` prefix so the relayed lines stay ordered when interleaved with
     * phone-side log lines on a single `adb logcat` timeline.
     */
    private fun expandWatchLogBatch(json: JSONObject) {
        val lines = json.optJSONArray("lines") ?: return
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val msg = "[${line.optLong("ts", 0L)}] ${line.optString("msg", "")}"
            when (line.optString("level", "I")) {
                "E" -> Log.e(WATCH_LOG_TAG, msg)
                "W" -> Log.w(WATCH_LOG_TAG, msg)
                else -> Log.i(WATCH_LOG_TAG, msg)
            }
        }
    }

    // reason: ACK routing checks independent correlation fields before claiming a pending request.
    @Suppress("ComplexCondition")
    private val receiver: Receiver = Receiver { message ->
        val bytes = message.data ?: return@Receiver
        val parent = attachJob ?: return@Receiver
        scope.launch(parent) {
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            if (json == null) {
                Log.w(TAG, "received unparseable payload (${bytes.size}B) — dropping")
                return@launch
            }
            // Dev-only batched log relay — expand to one logcat line per
            // entry and exit; never reaches the LWW handler.
            if (json.optString("type") == "watch_log_batch") {
                expandWatchLogBatch(json)
                return@launch
            }
            // App-level bg-file ack — the watch confirms its file handler stored
            // the image. Resolve the matching upload's pending wait so it stops
            // retrying. Meta envelope (no alarmId) — handled raw before parse.
            if (json.optString("type") == "bg_received") {
                val ackHash = json.optString("hash")
                val pending = pendingBgAck.get()
                if (pending != null && pending.first == ackHash && !pending.second.isCompleted) {
                    pendingBgAck.compareAndSet(pending, null)
                    pending.second.complete(Unit)
                    Log.i(TAG, "bg_received ack hash=$ackHash — watch confirmed")
                } else {
                    Log.d(TAG, "bg_received ack hash=$ackHash (no matching pending)")
                }
                return@launch
            }
            // App-level alarm-list-file ack — the watch confirms it parsed + stored
            // the file-delivered full replace. Resolve the matching pushFileReplace
            // wait so it stops retrying. Meta envelope (no alarmId) — handled raw.
            if (json.optString("type") == "alarms_received") {
                val ackHash = json.optString("hash")
                val ackStamp = json.optLong("updatedAtEpoch", 0L)
                val pending = pendingAlarmsAck.get()
                // Released watches stamp this ack with their OWN clock, so a
                // pending replace that does not require a stamp (legacy file
                // push) accepts any stamp when the hash matches.
                if (pending != null && pending.hash == ackHash &&
                    (!pending.requireStamp || ackStamp == pending.updatedAtEpoch) &&
                    !pending.deferred.isCompleted
                ) {
                    pendingAlarmsAck.compareAndSet(pending, null)
                    pending.deferred.complete(Unit)
                    Log.i(TAG, "alarms_received ack hash=$ackHash stamp=$ackStamp — watch confirmed")
                } else {
                    Log.d(TAG, "alarms_received ack hash=$ackHash (no matching pending)")
                }
                return@launch
            }
            val parsed = WearJsonCodec.parseIncoming(json)
            if (parsed == null) {
                Log.w(TAG, "received unrecognized payload (${bytes.size}B) — dropping")
                return@launch
            }
            // SyncHash is a meta-protocol response — route to the pending
            // request continuation, NOT to the LWW-applying handler.
            // compareAndSet atomically claims a matching deferred. A stamped
            // reply for another request must not consume the live request.
            if (parsed is IncomingMessage.SyncHash) {
                val pending = pendingHashRequest.get()
                if (pending != null && !pending.deferred.isCompleted &&
                    (parsed.updatedAtEpoch == 0L || parsed.updatedAtEpoch == pending.updatedAtEpoch) &&
                    pendingHashRequest.compareAndSet(pending, null)
                ) {
                    pending.deferred.complete(parsed)
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
            // AlarmDismissedAck / AlarmSnoozedAck: watch confirms it
            // processed the dismiss/snooze envelope (closed its ring
            // page or accepted the action). Application-level ack —
            // proves the JS receiver actually ran our handler, not just
            // that Wear Engine's transport ACK'd at 207. Routed via
            // shared completePendingAck helper.
            if (parsed is IncomingMessage.AlarmDismissedAck) {
                completePendingAck(pendingDismissAck, parsed, "alarm_dismissed_ack")
                return@launch
            }
            if (parsed is IncomingMessage.AlarmSnoozedAck) {
                completePendingAck(
                    pendingSnoozeAck,
                    IncomingMessage.AlarmDismissedAck(
                        parsed.alarmId,
                        parsed.updatedAtEpoch,
                        parsed.firedAtEpoch,
                        parsed.ok,
                    ),
                    "alarm_snoozed_ack",
                )
                return@launch
            }
            // WatchScreen is the watch's reply to our phone-initiated
            // screen_request. Persist it keyed on the bonded model (we own
            // that knowledge here, the LWW handler doesn't) so the background
            // picker/encoder can size to the real panel; never reaches the
            // LWW handler.
            if (parsed is IncomingMessage.WatchScreen) {
                persistWatchScreen(parsed)
                return@launch
            }
            incomingHandler?.handle(parsed)
        }
    }

    override fun sendAlarmFired(alarmId: Long, ringHints: AlarmRingHints?) {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmFired", now)) return
        val envelope = buildAlarmFiredEnvelope(alarmId, now, ringHints)
        scope.launch {
            if (performSend(envelope, retryOnError = false)) {
                alarmFiredDeliveredAt[alarmId] = android.os.SystemClock.elapsedRealtime()
            }
        }
    }

    override fun wasAlarmFiredDelivered(alarmId: Long, sinceElapsedMs: Long): Boolean {
        val at = alarmFiredDeliveredAt[alarmId] ?: return false
        return at >= sinceElapsedMs
    }

    override suspend fun sendAlarmDismissed(alarmId: Long, firedAt: Long?): Boolean {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmDismissed", now)) return false
        return performSend(
            JSONObject().apply {
                put("type", "alarm_dismissed")
                put("alarmId", alarmId)
                put("updatedAtEpoch", now)
                firedAt?.let { put("firedAt", it) }
            },
            retryOnError = true,
        )
    }

    override suspend fun sendAlarmSnoozed(
        alarmId: Long,
        rescheduleEpoch: Long,
        firedAt: Long?,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmSnoozed", now)) return false
        return performSend(
            JSONObject().apply {
                put("type", "alarm_snoozed")
                put("alarmId", alarmId)
                put("updatedAtEpoch", now)
                put("rescheduleEpoch", rescheduleEpoch)
                firedAt?.let { put("firedAt", it) }
            },
            retryOnError = true,
        )
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
    // Awaits the watch's alarm_ringing reply — a P2P receive, so per the
    // class-level ACTIVITY RULE it only resolves while an Activity is in
    // the task. The fire path satisfies that for free: FSI launches
    // AlarmActivity concurrently with this call.
    override suspend fun sendAlarmFiredAwaiting(
        alarmId: Long,
        timeoutMs: Long,
        ringHints: AlarmRingHints?,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmFiredAwaiting", now)) return false
        val envelope = buildAlarmFiredEnvelope(alarmId, now, ringHints)
        return pendingAlarmRingingMutex.withLock {
            val deferred = CompletableDeferred<Unit>()
            pendingAlarmRinging.set(PendingRing(alarmId, ringHints?.firedAtEpoch, now, deferred))
            try {
                val outcome = withTimeoutOrNull(timeoutMs) {
                    val sent = performSend(envelope, retryOnError = true)
                    if (!sent) {
                        Log.w(TAG, "sendAlarmFiredAwaiting: alarm_fired send failed id=$alarmId")
                        return@withTimeoutOrNull false
                    }
                    alarmFiredDeliveredAt[alarmId] = android.os.SystemClock.elapsedRealtime()
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
                    Log.i(
                        TAG,
                        "sendAlarmFiredAwaiting id=$alarmId outcome=$outcome " +
                            "elapsed=${System.currentTimeMillis() - now}ms",
                    )
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

    // Suspending dismiss: wait up to timeoutMs for the WATCH'S APPLICATION-
    // LEVEL ACK (`alarm_dismissed_ack` envelope). See sendAwaitingAck for
    // the full retry-layering rationale.
    override suspend fun sendAlarmDismissedAwaiting(
        alarmId: Long,
        timeoutMs: Long,
        firedAt: Long?,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmDismissedAwaiting", now)) return false
        val envelope = JSONObject().apply {
            put("type", "alarm_dismissed")
            put("alarmId", alarmId)
            put("updatedAtEpoch", now)
            firedAt?.let { put("firedAt", it) }
        }
        return sendAwaitingAck(
            "sendAlarmDismissedAwaiting",
            envelope,
            alarmId,
            firedAt,
            now,
            pendingDismissAck,
            pendingDismissAckMutex,
            timeoutMs,
        )
    }

    // Shared impl for the suspending dismiss / snooze paths. Waits up to
    // timeoutMs for the watch's APPLICATION-LEVEL ack envelope, not just
    // the P2P transport 207. The transport ACK says "delivered to the
    // watch's Wear Engine layer" but NOT "the JS receiver processed it" —
    // the receiver can be mid-bundle-load / mid-page-transition and
    // silently drop the envelope.
    //
    // ACTIVITY RULE (see class header): the watch's ack is a P2P *receive*
    // — it is only delivered while an Activity is alive in the task. The
    // caller (AlarmRingService's phone-side dismiss/snooze path) MUST keep
    // AlarmActivity alive for the whole of this call, or the await below
    // times out unconditionally.
    //
    // Two retry layers stack here:
    //   • transport — performSend(retryOnError=true) → retrySend re-fires
    //     up to RETRY_MAX_ATTEMPTS times when the SDK reports 206 / throws.
    //   • application — this loop re-sends the WHOLE envelope up to
    //     ACK_RESEND_ROUNDS times when the transport reported 207 but no
    //     ack came back within ACK_RESEND_WAIT_MS. That 207-but-no-ack
    //     case is exactly the silent-drop the ack protocol exists to
    //     catch — detecting it is not enough, we must re-send so the
    //     (now-bound) receiver gets another chance. A re-sent
    //     alarm_dismissed / alarm_snoozed is idempotent on the watch
    //     (re-navigates to index = visual no-op), so re-sending is safe.
    //
    // Every round force-repings (bypasses the 30 s cache) so a channel
    // that degraded since the fire's wake-protocol is re-woken first.
    // reason: the shared retry path needs request identity, ACK slot, mutex, and caller budget.
    @Suppress("LongParameterList")
    private suspend fun sendAwaitingAck(
        label: String,
        envelope: JSONObject,
        alarmId: Long,
        firedAt: Long?,
        updatedAtEpoch: Long,
        slot: java.util.concurrent.atomic.AtomicReference<PendingAck?>,
        mutex: Mutex,
        timeoutMs: Long,
    ): Boolean = mutex.withLock {
        val deferred = CompletableDeferred<Unit>()
        slot.set(PendingAck(alarmId, firedAt, updatedAtEpoch, deferred))
        try {
            val outcome = withTimeoutOrNull(timeoutMs) {
                val device = ensurePairedDevice() ?: return@withTimeoutOrNull false
                for (round in 1..ACK_RESEND_ROUNDS) {
                    // && short-circuits: a failed reping skips the send.
                    // Both ensurePeerAppRunning and performSend log their
                    // own failure specifics, so a single combined branch
                    // here keeps the loop to one jump statement.
                    val sent = ensurePeerAppRunning(device, force = true) &&
                        performSend(envelope, retryOnError = true)
                    if (sent) {
                        val acked = withTimeoutOrNull(ACK_RESEND_WAIT_MS) {
                            deferred.await()
                            true
                        }
                        if (acked == true) return@withTimeoutOrNull true
                        Log.w(
                            TAG,
                            "$label: no app-ack within ${ACK_RESEND_WAIT_MS}ms round=$round id=$alarmId",
                        )
                    } else {
                        Log.w(TAG, "$label: reping/send failed round=$round id=$alarmId")
                    }
                }
                false
            }
            if (outcome == null) {
                Log.w(TAG, "$label timed out after ${timeoutMs}ms id=$alarmId — no application-level ack")
                false
            } else {
                outcome
            }
        } finally {
            slot.updateAndGet { current ->
                if (current?.deferred === deferred) null else current
            }
        }
    }

    // Suspending snooze: same rationale + protocol as
    // sendAlarmDismissedAwaiting — see sendAwaitingAck.
    override suspend fun sendAlarmSnoozedAwaiting(
        alarmId: Long,
        rescheduleEpoch: Long,
        timeoutMs: Long,
        firedAt: Long?,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendAlarmSnoozedAwaiting", now)) return false
        val envelope = JSONObject().apply {
            put("type", "alarm_snoozed")
            put("alarmId", alarmId)
            put("updatedAtEpoch", now)
            put("rescheduleEpoch", rescheduleEpoch)
            firedAt?.let { put("firedAt", it) }
        }
        return sendAwaitingAck(
            "sendAlarmSnoozedAwaiting",
            envelope,
            alarmId,
            firedAt,
            now,
            pendingSnoozeAck,
            pendingSnoozeAckMutex,
            timeoutMs,
        )
    }

    override fun sendPeerActionAck(
        alarmId: Long,
        type: String,
        requestStamp: Long,
        firedAtEpoch: Long?,
        ok: Boolean,
    ) {
        val ackType = when (type) {
            "alarm_dismissed" -> "alarm_dismissed_ack"
            "alarm_snoozed" -> "alarm_snoozed_ack"
            else -> {
                Log.w(TAG, "sendPeerActionAck dropped unknown type=$type")
                return
            }
        }
        // Only occurrence-aware requests (they carried firedAt) get a reply;
        // released watches ignore the ack type and must not be woken for it.
        if (firedAtEpoch == null || requestStamp <= 0L) {
            Log.d(TAG, "sendPeerActionAck skipped id=$alarmId type=$type — legacy request, no reply")
            return
        }
        sendPeerReply(JSONObject().apply {
            put("type", ackType)
            put("alarmId", alarmId)
            put("updatedAtEpoch", requestStamp)
            put("firedAt", firedAtEpoch)
            put("ok", ok)
        })
    }

    override fun sendPeerEnded(alarmId: Long, type: String, firedAtEpoch: Long) {
        if (type != "alarm_dismissed" && type != "alarm_snoozed") {
            Log.w(TAG, "sendPeerEnded dropped unknown type=$type")
            return
        }
        val now = System.currentTimeMillis()
        if (!validateStamp("sendPeerEnded", now)) return
        // Same shape the phone sends for its own dismiss/snooze (no
        // rescheduleEpoch: it is optional on the watch and unknown here).
        sendPeerReply(JSONObject().apply {
            put("type", type)
            put("alarmId", alarmId)
            put("updatedAtEpoch", now)
            put("firedAt", firedAtEpoch)
        })
    }

    override val isReceiveReady: Boolean
        get() = receiverRegistered && receiverDeviceUuid != null

    private fun sendPeerReply(envelope: JSONObject) {
        scope.launch { sendPeerReplyBounded(envelope) }
    }

    /**
     * Reply to a watch request: NO ping or wake (the watch app is running, it
     * is waiting for this), and NOT a connection error on failure (a closed
     * watch app is expected). Retries a failed transport send with short
     * delays; the whole attempt is bounded by [PEER_REPLY_TOTAL_MS].
     */
    private suspend fun sendPeerReplyBounded(envelope: JSONObject): Boolean {
        val type = envelope.optString("type")
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        val sent = withTimeoutOrNull(PEER_REPLY_TOTAL_MS) {
            val device = pairedDevice ?: ensurePairedDevice()
            var delivered = false
            var attempt = 0
            while (device != null && !delivered && attempt < PEER_REPLY_MAX_ATTEMPTS) {
                attempt++
                delivered = sendBytesQuietly(device, bytes, PEER_REPLY_SEND_TIMEOUT_MS)
                if (!delivered && attempt < PEER_REPLY_MAX_ATTEMPTS) delay(PEER_REPLY_RETRY_DELAY_MS * attempt)
            }
            delivered
        } ?: false
        if (sent) Log.d(TAG, "peer reply sent type=$type") else Log.w(TAG, "peer reply NOT delivered type=$type")
        return sent
    }

    // reason: Wear Engine reports binder and transport failures as RuntimeException.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendBytesQuietly(device: Device, bytes: ByteArray, timeoutMs: Long): Boolean = try {
        sendOnce(device, Message.Builder().setPayload(bytes).build(), timeoutMs)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        Log.w(TAG, "quiet send threw: ${e.message}")
        false
    }

    // File-transfer path for the shared default watch background.
    // Differs from the normal data-Message path in two ways:
    //   1. Builder.setPayload(File) — Wear Engine routes any non-string
    //      payload to the watch's file-receive handler instead of the
    //      byte-msg Receiver.
    //   2. Builder.setDescription(bgFile.name) — the description carries
    //      the file name (incl. extension), so the watch's <image src>
    //      sees the right format. Per memory:wear_engine_lite_facts
    //      §setdesc-fix, setDescription takes a String.
    // reason: ReturnCount — early-out on missing/empty file, no paired device,
    // and the success return inside the retry loop are each a distinct outcome.
    // (The sendOnce try/catch now lives in sendBgFileAndAwaitAck, which carries
    // its own TooGenericExceptionCaught suppression.)
    @Suppress("ReturnCount")
    override suspend fun uploadDefaultWatchBackground(bgFile: java.io.File): Boolean {
        if (!bgFile.exists() || bgFile.length() == 0L) {
            Log.w(TAG, "uploadDefaultWatchBackground: file missing/empty at ${bgFile.absolutePath}")
            return false
        }
        // Hash for the app-level ack match — the file is named bgd_<hash>.<ext>.
        val hash = bgFile.name.removePrefix("bgd_").substringBeforeLast(".")
        // Retry until the watch's JS file handler ACKs (bg_received). 207 only
        // means the Wear Engine layer accepted it; this thin-client watch
        // terminates after each sync and its receiver takes ~2 s to go live, so
        // a single send routinely lands on a dead/not-yet-ready app. Each retry
        // re-launches (force-wake) AND re-arms the watch's drain timer, keeping
        // it alive until a send lands and it confirms.
        var attempt = 0
        while (attempt < BG_UPLOAD_MAX_ATTEMPTS) {
            attempt++
            val device = ensurePairedDevice() ?: return false
            if (ensurePeerAppRunning(device, force = true)) {
                if (sendBgFileAndAwaitAck(device, bgFile, hash, attempt)) return true
            } else {
                Log.w(TAG, "uploadDefaultWatchBackground: peer not running (attempt=$attempt)")
            }
        }
        Log.w(TAG, "uploadDefaultWatchBackground failed: no ack after $BG_UPLOAD_MAX_ATTEMPTS attempts")
        return false
    }

    // reason: TooGenericExceptionCaught — sendOnce surfaces every transport
    // failure as WearEngineException (RuntimeException); a narrower catch would
    // miss real 206/binder errors.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendBgFileAndAwaitAck(
        device: Device,
        bgFile: java.io.File,
        hash: String,
        attempt: Int,
    ): Boolean {
        val ackDeferred = CompletableDeferred<Unit>()
        val pair = hash to ackDeferred
        pendingBgAck.set(pair)
        val msg = Message.Builder().setPayload(bgFile).setDescription(bgFile.name).build()
        val sent = try {
            sendOnce(device, msg, FILE_SEND_TIMEOUT_MS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "uploadDefaultWatchBackground threw (attempt=$attempt): ${e.message}")
            invalidateAndMarkError()
            false
        }
        if (!sent) {
            invalidateAndMarkError()
            pendingBgAck.compareAndSet(pair, null)
            return false
        }
        Log.i(TAG, "uploadDefaultWatchBackground sent ${bgFile.name} attempt=$attempt — awaiting ack")
        val acked = withTimeoutOrNull(BG_ACK_TIMEOUT_MS) { ackDeferred.await() } != null
        pendingBgAck.compareAndSet(pair, null)
        if (acked) {
            Log.i(TAG, "uploadDefaultWatchBackground confirmed ${bgFile.name} after $attempt attempt(s)")
        } else {
            Log.w(TAG, "uploadDefaultWatchBackground no ack (attempt=$attempt) — retrying")
        }
        return acked
    }

    override fun sendDefaultWatchBackgroundCleared() {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendDefaultWatchBackgroundCleared", now)) return
        val envelope = JSONObject().apply {
            put("type", "watch_default_bg_cleared")
            put("updatedAtEpoch", now)
        }
        // performSend pings first, so this DOES launch a sleeping watch app
        // (the user agreed to that in the picture consent dialog). The watch
        // then shows its sync screen, so follow the clear with sync_done: the
        // watch closes right away instead of waiting out its fallback. With
        // list sync off there is no later forceSync to carry the clear, so
        // this send is the only delivery.
        scope.launch {
            if (performSend(envelope, retryOnError = true)) {
                sendWithoutWake(buildSyncDoneEnvelope())
            }
        }
        // The watch no longer holds our image — allow a re-deliver if the user
        // re-picks the same one.
        deliveredBgHash = null
    }

    override fun markBgDelivered(hash: String) {
        deliveredBgHash = hash
    }

    override fun bgAlreadyDeliveredThisConnection(hash: String): Boolean =
        deliveredBgHash == hash

    override fun sendSettingsChanged(use24Hour: Boolean?, firstDayOfWeek: Int?) {
        val now = System.currentTimeMillis()
        if (!validateStamp("sendSettingsChanged", now)) return
        sendJson(JSONObject().apply {
            put("type", "settings_changed")
            put("updatedAtEpoch", now)
            // null is sent as JSON null so the watch can distinguish "follow
            // system" (null) from a Boolean false override. JSONObject.put
            // with null erases the key; use JSONObject.NULL for explicit null.
            put("use24Hour", if (use24Hour == null) JSONObject.NULL else use24Hour)
            put("firstDayOfWeek", if (firstDayOfWeek == null) JSONObject.NULL else firstDayOfWeek)
        })
    }

    override fun setIncomingHandler(handler: IncomingMessageHandler?) {
        // Re-installing the SAME handler must not cancel attachJob: it is the
        // parent of in-flight receive coroutines, and cancelling it would drop
        // a watch message that is being handled right now. Just make sure the
        // registration exists (repairs one that did not complete).
        val currentJob = attachJob
        if (handler != null && incomingHandler === handler && currentJob?.isActive == true) {
            if (!receiverRegistered) scope.launch(currentJob) { ensureReceiverRegistered() }
            return
        }
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

    override fun setWatchBgReconciler(reconciler: (suspend (reportedMarker: String) -> Unit)?) {
        watchBgReconciler = reconciler
    }

    override fun refreshWatchConnection() {
        scope.launch { seedConnectionStatus() }
    }

    override fun startConnectionMonitor() {
        scope.launch {
            seedConnectionStatus()
            registerConnectionMonitor()
        }
    }

    override fun stopConnectionMonitor() {
        scope.launch {
            connectionMonitorMutex.withLock {
                val listener = connectionMonitor ?: return@withLock
                connectionMonitor = null
                connectionMonitorDeviceUuid = null
                awaitTaskCompleted("unregisterConnectionMonitor") { monitorClient.unregister(listener) }
            }
        }
    }

    // Authoritative, low-traffic connection read that seeds/refreshes statusFlow.
    // getBondedDevices() queries Huawei Health's pairing service ON THE PHONE —
    // NOT a P2P round-trip to the watch — so it costs zero watch traffic and
    // never pings. This is the single signal every status UI reads.
    // Single chokepoint for status changes so EVERY transition is logged with a
    // reason. This is how we diagnose the watch-bg "block vanished mid-save"
    // class of bug: a transient CONNECTING/ERROR from a background send used to
    // hide the conditionally-rendered block.
    private fun setStatus(next: WatchSyncStatus, reason: String) {
        val prev = _statusFlow.value
        if (prev != next) {
            Log.i(TAG, "status $prev -> $next ($reason)")
            _statusFlow.value = next
        }
    }

    // Result of asking Huawei Health for the bonded devices. [Failed] (a null
    // from a failed or timed-out Task) says nothing about the watch, so it must
    // never clear the cached device or the registration; only a definite answer
    // ([Denied] or [Devices], even an empty list) may.
    private sealed interface BondedLookup {
        data object Failed : BondedLookup
        data object Denied : BondedLookup
        data class Devices(val devices: List<Device>) : BondedLookup
    }

    private suspend fun lookupBondedDevices(op: String, beforeQuery: () -> Unit = {}): BondedLookup {
        return when (hasWatchPermission()) {
            null -> BondedLookup.Failed
            false -> BondedLookup.Denied
            true -> {
                beforeQuery()
                awaitTask(op) { HiWear.getDeviceClient(context).bondedDevices }
                    ?.let { BondedLookup.Devices(it) }
                    ?: BondedLookup.Failed
            }
        }
    }

    // Keeps the device we already hold (registered, else cached) while it is
    // still bonded and connected, so two connected bonded devices or an unstable
    // list order cannot flip the selection on every 2 s re-resolve (each flip
    // would unregister and re-register the receiver).
    private fun selectDevice(devices: List<Device>): Device? {
        val heldUuid = receiverDeviceUuid ?: pairedDevice?.uuid
        return devices.firstOrNull { it.isConnected && it.uuid == heldUuid }
            ?: devices.firstOrNull { it.isConnected }
            ?: devices.firstOrNull()
    }

    private suspend fun seedConnectionStatus() {
        when (val lookup = lookupBondedDevices("getBondedDevices(status)")) {
            BondedLookup.Failed -> Log.w(TAG, "seedConnectionStatus: lookup failed — keeping cached device")
            BondedLookup.Denied -> {
                updatePairedDevice(null)
                setStatus(WatchSyncStatus.NOT_CONNECTED, "seed:no-permission")
            }
            is BondedLookup.Devices -> applySeededDevices(lookup.devices)
        }
    }

    private suspend fun applySeededDevices(devices: List<Device>) {
        val selected = selectDevice(devices)
        val connected = selected?.takeIf { it.isConnected }
        updatePairedDevice(selected)
        if (connected != null) {
            setStatus(WatchSyncStatus.CONNECTED, "seed:bonded-connected")
        } else {
            // Disconnected — allow the bg to re-deliver once on reconnect.
            deliveredBgHash = null
            setStatus(WatchSyncStatus.NOT_CONNECTED, "seed:no-connected-device")
        }
        if (connected != null) {
            _pairedDeviceInfo.value = PairedDeviceInfo(
                displayName = normalizeDeviceLabel(connected.name, connected.model),
                connected = true,
            )
        }
    }

    // reason: TooGenericExceptionCaught — getMonitorClient()/register() can throw
    // WearEngineException (RuntimeException) / IllegalStateException on HMS
    // hiccups; a leak would unwind the launch coroutine. The monitor is
    // best-effort (the seed already set a correct status), so log and move on.
    // reason: monitor setup has separate permission, device-selection, replacement, and recovery branches.
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
    private suspend fun registerConnectionMonitor(): Unit = connectionMonitorMutex.withLock {
        val lookup = lookupBondedDevices("getBondedDevices(monitor)")
        if (lookup == BondedLookup.Failed) {
            // Unknown, not "gone": keep the monitor and the cached device.
            Log.w(TAG, "registerConnectionMonitor: lookup failed — keeping monitor and device")
            return@withLock
        }
        if (lookup == BondedLookup.Denied) {
            val previous = connectionMonitor
            connectionMonitor = null
            connectionMonitorDeviceUuid = null
            if (previous != null) {
                awaitTaskCompleted("unregisterConnectionMonitor(no-permission)") {
                    monitorClient.unregister(previous)
                }
            }
            return@withLock
        }
        val devices = (lookup as? BondedLookup.Devices)?.devices ?: return@withLock
        val device = selectDevice(devices)
        updatePairedDevice(device)
        if (connectionMonitorDeviceUuid == device?.uuid) return@withLock
        connectionMonitor?.let { previous ->
            awaitTaskCompleted("replaceConnectionMonitor") { monitorClient.unregister(previous) }
            connectionMonitor = null
            connectionMonitorDeviceUuid = null
        }
        if (device == null) return@withLock
        try {
            // Don't trust the change payload (its int mapping is undocumented);
            // use the event only as a trigger to re-run the local query.
            val listener = MonitorListener { _, _, _ ->
                scope.launch {
                    seedConnectionStatus()
                    registerConnectionMonitor()
                }
            }
            // Best-effort: if registration fails, the onStart seed still kept
            // status correct and the next onStop→onStart cycle retries.
            val registered = awaitTaskCompleted("registerConnectionMonitor") {
                monitorClient.register(device, MonitorItem.MONITOR_ITEM_CONNECTION, listener)
            }
            if (registered) {
                connectionMonitor = listener
                connectionMonitorDeviceUuid = device.uuid
                Log.i(TAG, "connection monitor registered uuid=${device.uuid}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "connection monitor setup threw: ${e.message}", e)
            connectionMonitor = null
            connectionMonitorDeviceUuid = null
        }
    }

    private suspend fun detachReceiver() {
        connectionMutex.withLock {
            if (!receiverRegistered && !receiverRegistrationUncertain) return@withLock
            unregisterReceiverLocked("unregisterReceiver")
        }
    }

    /**
     * Clears receiver ownership. Unregister is idempotent, and either way the
     * phone no longer wants the receiver registered; the result only decides
     * whether ownership is settled (see [receiverRegistrationUncertain]).
     * Returns true when settled. Caller holds [connectionMutex].
     */
    private suspend fun unregisterReceiverLocked(op: String): Boolean {
        val outcome = awaitTaskOutcome(op, pendingUnregisters) { p2pClient.unregisterReceiver(receiver) }
        receiverRegistered = false
        receiverDeviceUuid = null
        // A timeout proves nothing: the registration may still exist, so keep
        // the flag and let the next attempt unregister again before registering.
        // A definite answer (success, or the SDK failing the call) settles it,
        // unless an EARLIER unregister is still pending and could remove a new
        // registration when it finally completes.
        val settled = outcome != TaskOutcome.TIMED_OUT && !hasOutstandingUnregister()
        receiverRegistrationUncertain = !settled
        return settled
    }

    private fun sendJson(envelope: JSONObject, retryOnError: Boolean = false) {
        scope.launch { performSend(envelope, retryOnError) }
    }

    // reason: Wear Engine reports binder and transport failures as RuntimeException.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendWithoutWake(envelope: JSONObject): Boolean {
        val device = pairedDevice ?: return false
        return try {
            val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
            sendOnce(device, Message.Builder().setPayload(bytes).build(), SEND_TIMEOUT_MS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "sendWithoutWake failed type=${envelope.optString("type")}: ${e.message}")
            false
        }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
    private suspend fun ensurePeerAppRunning(
        device: Device,
        force: Boolean = false,
        timeoutMs: Long = PING_WAKE_TIMEOUT_MS,
    ): Boolean {
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
            pollUntilRunning(device, timeoutMs)
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
    private suspend fun pollUntilRunning(device: Device, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var iteration = 0
        while (true) {
            iteration++
            val code = try {
                pingPeer(device)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
                            "pollUntilRunning: TIMEOUT after ${timeoutMs}ms — " +
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

    private suspend fun sendOnce(device: Device, msg: Message, timeoutMs: Long = SEND_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { sendOnceUnbounded(device, msg) } ?: run {
            // A SendCallback that never fires must not hold the caller (and any
            // mutex it owns) forever; treat it like a failed send.
            Log.w(TAG, "send timed out after ${timeoutMs}ms")
            false
        }

    // reason: ForbiddenVoid suppressed because Task<Void> is the Huawei SDK signature.
    @Suppress("ForbiddenVoid")
    private suspend fun sendOnceUnbounded(device: Device, msg: Message): Boolean =
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
                    if (resultCode == COMM_SUCCESS) {
                        // Successful delivery = peer is definitely up + receiver
                        // bound. Refresh the running cache so subsequent sends
                        // in the same burst (or shortly after) skip the wake-
                        // poll. The 30 s window in PEER_RUNNING_CACHE_MS lets a
                        // typical alarm-ring → user-react → dismiss flow proceed
                        // without re-pinging mid-flow.
                        lastConfirmedRunningAtMs = System.currentTimeMillis()
                    }
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
            // Forces a re-resolve. The receiver registration is deliberately
            // left alone: a failed SEND says nothing about the phone-side
            // RECEIVE registration, and clearing the flag without unregistering
            // made the next send register a second time.
            pairedDevice = null
            // A 206/send-throw means the receiver channel is no longer
            // hot — drop the "peer is running" confirmation so the next
            // send re-polls instead of trusting a stale cache hit.
            lastConfirmedRunningAtMs = 0L
            setStatus(WatchSyncStatus.ERROR, "invalidate:206-or-send-throw")
            // Connection broke — allow the bg to re-deliver once on reconnect.
            deliveredBgHash = null
        }
    }

    private suspend fun ensurePairedDevice(): Device? {
        val candidate = connectionMutex.withLock { resolvePairedDeviceLocked() }
        if (candidate != null && incomingHandler != null) ensureReceiverRegisteredFor(candidate)
        return candidate
    }

    // Caller holds connectionMutex. A failed lookup keeps the cached device (and
    // so the registration) instead of treating "could not ask" as "no watch".
    private suspend fun resolvePairedDeviceLocked(): Device? {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val cached = pairedDevice
        if (cached != null && cached.isConnected && nowElapsed - pairedDeviceResolvedAtElapsedMs < DEVICE_CACHE_MS) {
            setStatus(WatchSyncStatus.CONNECTED, "ensurePaired:elapsed-cache")
            return cached
        }
        val lookup = lookupBondedDevices("getBondedDevices") {
            // Only a first connect shows "connecting". A routine re-resolve of a
            // known watch (2 s cache expiry) must not flicker the status.
            if (cached == null) setStatus(WatchSyncStatus.CONNECTING, "ensurePaired:resolving")
        }
        if (lookup == BondedLookup.Failed) {
            Log.w(TAG, "ensurePairedDevice: lookup failed — keeping cached device=${cached != null}")
            setStatus(
                if (cached?.isConnected == true) WatchSyncStatus.CONNECTED else WatchSyncStatus.NOT_CONNECTED,
                "ensurePaired:lookup-failed",
            )
            return cached
        }
        val devices = (lookup as? BondedLookup.Devices)?.devices.orEmpty()
        if (lookup is BondedLookup.Devices) logBondedDevices(devices)
        val resolved = selectDevice(devices)
        setStatus(
            if (resolved?.isConnected == true) WatchSyncStatus.CONNECTED else WatchSyncStatus.NOT_CONNECTED,
            "ensurePaired:resolved candidate=${resolved != null} connected=${resolved?.isConnected}",
        )
        commitPairedDeviceLocked(resolved, nowElapsed)
        return resolved
    }

    private fun logBondedDevices(devices: List<Device>) {
        Log.i(TAG, "bondedDevices count=${devices.size}")
        devices.forEachIndexed { i, d ->
            Log.i(TAG, "  [$i] name='${d.name}' model='${d.model}' connected=${d.isConnected} uuid=${d.uuid}")
        }
    }

    /**
     * Adopt [resolved] as the paired device. If the receiver is registered for a
     * different device (or the watch is gone) it is unregistered first, so a
     * registration never outlives its device. Caller holds [connectionMutex].
     */
    private suspend fun commitPairedDeviceLocked(resolved: Device?, nowElapsed: Long) {
        if (receiverRegistered && receiverDeviceUuid != resolved?.uuid) {
            unregisterReceiverLocked("unregisterReceiver(device-change)")
        }
        _pairedDeviceInfo.value = resolved?.let {
            PairedDeviceInfo(
                displayName = normalizeDeviceLabel(it.name, it.model),
                connected = it.isConnected,
            )
        }
        pairedDevice = resolved
        pairedDeviceResolvedAtElapsedMs = nowElapsed
        // The next watch-screen handshake is refreshed only when the watch
        // becomes connected or the connected device changes, not on every
        // re-resolve of the same connected watch.
        val connectedUuid = resolved?.takeIf { it.isConnected }?.uuid
        if (connectedUuid != null && connectedUuid != lastConnectedDeviceUuid) forceScreenRefresh = true
        lastConnectedDeviceUuid = connectedUuid
    }

    private suspend fun updatePairedDevice(candidate: Device?) {
        connectionMutex.withLock {
            commitPairedDeviceLocked(candidate, android.os.SystemClock.elapsedRealtime())
        }
        if (candidate != null && incomingHandler != null) ensureReceiverRegisteredFor(candidate)
    }

    // Registers the P2P receiver. NOTE: registration is process-scoped
    // (this @Singleton owns it) — but Wear Engine only DELIVERS inbound
    // messages to it while an Activity is in the task. See the class-level
    // ACTIVITY RULE: a registered receiver is necessary but not sufficient;
    // an Activity must also be alive for replies/acks to arrive.
    private suspend fun ensureReceiverRegistered() {
        val device = ensurePairedDevice() ?: return
        // With a handler installed, ensurePairedDevice already made its one
        // register attempt for this pass. A second attempt here would repeat a
        // timed-out pre-register unregister and double the wait (10 s) inside
        // awaitReceiveReady's budget.
        if (incomingHandler == null) ensureReceiverRegisteredFor(device)
    }

    private suspend fun ensureReceiverRegisteredFor(device: Device) {
        connectionMutex.withLock {
            if (pairedDevice?.uuid != device.uuid) return@withLock
            if (receiverRegistered && receiverDeviceUuid == device.uuid) return@withLock
            // An earlier unregister that timed out has not finished in the SDK.
            // It could complete after a new register and remove it, whatever
            // order the SDK uses. Wait for its own completion listener to clear
            // the marker; do not even queue another unregister behind it.
            if (hasOutstandingUnregister()) {
                receiverRegistrationUncertain = true
                Log.w(TAG, "earlier unregister still outstanding — not registering now, will retry")
                return@withLock
            }
            // Never register on top of a registration that may exist: one for
            // another device, or one whose last register call did not report
            // success (it may still complete late).
            // If that unregister itself times out it may still complete AFTER a
            // new register and silently remove it (reception gone while the
            // flags say ready). So register only after an answered unregister;
            // otherwise stay not-ready with the flag set. A later call (send,
            // awaitReceiveReady, handler install, seed) tries the unregister again.
            if ((receiverRegistered || receiverRegistrationUncertain) &&
                !unregisterReceiverLocked("unregisterReceiver(pre-register)")
            ) {
                Log.w(TAG, "pre-register unregister timed out — not registering now, will retry")
                return@withLock
            }
            // Uncertain BEFORE the call and settled only by a definite result.
            // If this coroutine is cancelled mid-flight (a caller timeout, a
            // replaced handler) the Task may still register later, and the flag
            // stays set so the next register is preceded by an unregister.
            receiverRegistrationUncertain = true
            val outcome = awaitTaskOutcome("registerReceiver") {
                p2pClient.registerReceiver(device, receiver)
            }
            val ok = outcome == TaskOutcome.SUCCESS
            receiverRegistered = ok
            receiverDeviceUuid = if (ok) device.uuid else null
            receiverRegistrationUncertain = outcome == TaskOutcome.TIMED_OUT
        }
    }

    override suspend fun awaitReceiveReady(timeoutMs: Long): Boolean {
        if (isReceiveReady) return true
        // Nothing to register for until the app installed its handler.
        if (incomingHandler == null) return false
        // Device lookup + register only: no ping, no send, so the watch is never
        // woken. Cancellation at the timeout releases connectionMutex.
        withTimeoutOrNull(timeoutMs) { ensureReceiverRegistered() }
        return isReceiveReady
    }

    override suspend fun hasWatchPermission(): Boolean? =
        awaitTask("checkPermission") {
            HiWear.getAuthClient(context).checkPermission(Permission.DEVICE_MANAGER)
        }

    private suspend fun ensurePermissionGranted(): Boolean = hasWatchPermission() == true

    // reason: same as performSend — Wear Engine wraps all transport errors in
    // RuntimeException subclasses; narrower catches would miss real failures.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> awaitTask(
        op: String,
        block: () -> Task<T>,
    ): T? {
        return try {
            val task = block()
            // Bounded: a Task that never completes would otherwise hold the
            // connection mutex (and every send behind it) forever. Timeout is
            // a failure (null), the same as a failed Task.
            val result = withTimeoutOrNull(SDK_WAIT_TIMEOUT_MS) {
                suspendCancellableCoroutine<T?> { cont ->
                    task.addOnSuccessListener { value ->
                        if (cont.isActive) cont.resumeWith(Result.success(value))
                    }.addOnFailureListener { e ->
                        Log.w(TAG, "$op failed: ${e.message}")
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                    cont.invokeOnCancellation {
                        Log.d(TAG, "$op cancelled")
                    }
                }
            }
            if (result == null) Log.w(TAG, "$op gave no result (failed or timed out)")
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "$op threw: ${e.message}", e)
            null
        }
    }

    private suspend fun awaitTaskCompleted(op: String, block: () -> Task<*>): Boolean =
        awaitTaskOutcome(op, block = block) == TaskOutcome.SUCCESS

    // A Task that does not answer is NOT the same as one that failed: the SDK
    // may still complete it later. Register/unregister need the difference.
    private enum class TaskOutcome { SUCCESS, FAILED, TIMED_OUT }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun awaitTaskOutcome(
        op: String,
        outstanding: MutableSet<PendingUnregister>? = null,
        block: () -> Task<*>,
    ): TaskOutcome = try {
        val task = block()
        // Listeners are attached right here, before any suspension point, so they
        // exist even if this coroutine is cancelled or times out. They keep
        // running after that: when the Task finally reaches a terminal result
        // (success or failure, possibly on the UI thread, possibly much later)
        // it removes its entry from `outstanding` (a no-op if it was abandoned).
        val release: () -> Unit = if (outstanding == null) {
            {}
        } else {
            val entry = PendingUnregister(elapsedClock())
            outstanding.add(entry)
            ({ outstanding.remove(entry) })
        }
        val done = CompletableDeferred<Boolean>()
        // release() BEFORE complete(): complete() can resume the waiting
        // coroutine on another thread, which must not still see this entry.
        // Removing an entry twice (or after it was abandoned) is a no-op.
        task.addOnSuccessListener {
            release()
            done.complete(true)
        }.addOnFailureListener { e ->
            Log.w(TAG, "$op failed: ${e.message}")
            release()
            done.complete(false)
        }
        // Bounded like awaitTask: a Task that never completes must not hold a lock forever.
        val completed = withTimeoutOrNull(SDK_WAIT_TIMEOUT_MS) { done.await() }
        when (completed) {
            true -> TaskOutcome.SUCCESS
            false -> TaskOutcome.FAILED
            null -> {
                Log.w(TAG, "$op timed out after ${SDK_WAIT_TIMEOUT_MS}ms")
                TaskOutcome.TIMED_OUT
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        Log.w(TAG, "$op threw: ${e.message}", e)
        TaskOutcome.FAILED
    }

    // The per-alarm `alarm` payload object. Shared by buildAlarmEnvelope
    // (alarm_added / alarm_updated) and buildSyncReplaceEnvelope so the
    // two paths cannot drift in field set or encoding.
    private fun buildAlarmPayload(alarm: Alarm): JSONObject = JSONObject().apply {
        put("id", alarm.id)
        // alarm.label is intentionally NOT sent — the label is phone-only
        // (shown on the phone ring screen). See sync-architecture.md §2.2.
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
        // backgroundImageUri: only serialized when set, mirroring
        // relativeMinutes. Old watch builds without the field render
        // the default ring screen (background-less) — correct
        // degradation. The watch currently ignores it; future watch
        // builds may consume it for a custom ring backdrop.
        alarm.backgroundImageUri?.let { put("backgroundImageUri", it) }
        // vibrationPattern: wire name of the preset enum. Old watch builds
        // without the field fall back to the default phone pattern on
        // their side (PULSE) — correct degradation.
        put("vibrationPattern", alarm.vibrationPattern.name)
        // watchVibrationEnabled (1.0.6): whether the WATCH buzzes for this alarm.
        // Old watch builds without the field default to vibrating — correct degradation.
        put("watchVibrationEnabled", alarm.watchVibrationEnabled)
        // maxSnoozeCount: 0 = unlimited. Old watch builds default to
        // unlimited (current behavior) — correct degradation.
        put("maxSnoozeCount", alarm.maxSnoozeCount)
        // volumeRampSeconds: 0 = off. Watch doesn't use this today
        // (single ringtone, no ramp on Lite Wearable), forwarded for
        // forward-compat / log telemetry only.
        put("volumeRampSeconds", alarm.volumeRampSeconds)
        // skipNextEpoch: optional — only sent when set. Used by the
        // watch for its display ("next skipped" hint) and to compute
        // the right ring time after one skipped occurrence.
        alarm.skipNextEpoch?.let { put("skipNextEpoch", it) }
    }

    /**
     * Thin-client `alarm_fired` envelope. The spec calls this out explicitly:
     * the watch MUST be able to ring with the correct pattern and Snooze
     * affordance state WITHOUT prior `sync_replace` (cold-paired watch, sync
     * drift, etc). So we ship the per-fire fields inline:
     *
     *  - `vibrationPattern` — enum name; watch reads VIBRATION_PATTERNS[name]
     *    and falls back to PULSE on unknown / missing.
     *  - `snoozeAllowed` — pre-computed boolean = `alarm.isSnoozeEnabled`
     *    (collapses snoozeMinutes==0 OR consecutiveSnoozeCount >= cap). The
     *    watch hides its Snooze button when false. Pre-computing on the phone
     *    avoids shipping the raw counter (transient ring-cycle state that
     *    would force hash mismatches) and avoids reimplementing the
     *    cap-vs-minutes logic on the watch.
     *
     * The phone-side caller resolves the alarm from BFU cache pre-unlock
     * (Room locked) or Room post-unlock; either path yields a domain Alarm
     * with the right consecutiveSnoozeCount because BfuAlarmCache mirrors it.
     */
    private fun buildAlarmFiredEnvelope(
        alarmId: Long,
        now: Long,
        ringHints: AlarmRingHints?,
    ): JSONObject = JSONObject().apply {
        put("type", "alarm_fired")
        put("alarmId", alarmId)
        put("updatedAtEpoch", now)
        // Advertised only when the ring service verified the phone can receive
        // the watch's reply (unlocked + receiver registered). Otherwise the
        // watch completes on transport 207 like released builds.
        if (ringHints?.supportsActionAck == true) put("supportsActionAck", true)
        // Hints are nullable so the legacy missed-cache path (no domain Alarm
        // resolvable) still ships a valid envelope; the watch falls back to
        // its AlarmStore for the missing fields.
        if (ringHints != null) {
            put("vibrationPattern", ringHints.vibrationPatternName)
            put("snoozeAllowed", ringHints.snoozeAllowed)
            put("watchVibrationEnabled", ringHints.watchVibrationEnabled)
            // Omitted when unknown (pre-unlock): the watch keeps its state.
            ringHints.listSync?.let { put("listSync", it) }
            ringHints.use24Hour?.let { put("use24Hour", it) }
            ringHints.firedAtEpoch?.let { put("firedAt", it) }
        }
    }

    // Authoritative full-state replace for force-sync (see
    // docs/sync-architecture.md §5.7). The watch replaces its whole
    // AlarmStore with this list and tombstones the dropped ids — that
    // is what prunes watch-only rows an additive push can never remove.
    private fun buildSyncReplaceEnvelope(alarms: List<Alarm>, updatedAtEpoch: Long): JSONObject = JSONObject().apply {
        put("type", "sync_replace")
        put("updatedAtEpoch", updatedAtEpoch)
        put("alarms", JSONArray().apply { alarms.forEach { put(buildAlarmPayload(it)) } })
    }

    // End-of-sync marker — the last message of every forceSync. Lets the
    // watch terminate its syncing page promptly instead of waiting out
    // the drain-quiescence timer.
    private fun buildSyncDoneEnvelope(): JSONObject = JSONObject().apply {
        put("type", "sync_done")
        put("updatedAtEpoch", System.currentTimeMillis())
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
    // user-visible distinct ForceSyncResult case (auth / device / connected /
    // ping-throw / ping-not-installed / wake-failed / already-in-sync /
    // pushed-result); collapsing would require a wrapper type or nested
    // when, neither of which is clearer. The push itself is delegated to
    // pushFullReplace so the bulk of the complexity is out.
    // reason: CyclomaticComplexMethod — the chain of distinct connection/
    // wake guards still sits near the threshold; each guard is a separate
    // user-visible outcome and extracting them would smear the when-cases.
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "CyclomaticComplexMethod")
    override suspend fun forceSync(freshAlarms: suspend () -> List<Alarm>): ForceSyncResult =
        withTimeoutOrNull(MANUAL_SYNC_TIMEOUT_MS) { forceSyncWithinDeadline(freshAlarms) }
            ?: ForceSyncResult.Error("Watch sync timed out after ${MANUAL_SYNC_TIMEOUT_MS}ms")

    // reason: each guard maps to a distinct actionable sync outcome.
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "CyclomaticComplexMethod")
    private suspend fun forceSyncWithinDeadline(freshAlarms: suspend () -> List<Alarm>): ForceSyncResult {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "forceSync: ping threw: ${e.message}", e)
            return ForceSyncResult.Error(e.message.orEmpty())
        }
        Log.i(TAG, "forceSync: initial ping=$pingCode")
        if (pingCode == PING_APP_NOT_INSTALLED) {
            return ForceSyncResult.PeerAppMissing(pingCode)
        }
        // A user-initiated sync gets a longer wake budget than the alarm path.
        // 10 s was tuned on a GT 6 Pro; a cold launch on other watch hardware
        // may simply be slower, and an AppGallery reviewer on a square watch
        // hit this timeout (rule 3.1, 2026-09). Waiting is free here — the user
        // is watching a spinner — whereas the alarm path keeps the short budget
        // because a late alarm is worse than a missed watch mirror.
        if (!ensurePeerAppRunning(device, force = true, timeoutMs = FORCE_SYNC_WAKE_TIMEOUT_MS)) {
            return ForceSyncResult.PeerAppNotRunning
        }
        // Phone-initiated, model-gated screen fetch — reuses the wake above
        // (never an extra ping) and only fires when the bonded model differs
        // from the resolution we already cached.
        maybeRequestWatchScreen()
        // Sync-check optimization: ask the watch for its current AlarmHash
        // BEFORE snapshotting our alarm list. The ~2s round-trip is the
        // TOCTOU window where the user could add/edit/delete an alarm; by
        // re-fetching `alarms` AFTER the response, we hash the latest
        // state and avoid skipping a needed push.
        val remote = requestRemoteHash(SYNC_CHECK_TIMEOUT_MS)
        val remoteHash = remote?.hash
        val alarms = freshAlarms()
        Log.i(TAG, "forceSync alarms=${alarms.size}")
        val localHash = AlarmHash.compute(alarms)
        if (remoteHash != null && remoteHash == localHash) {
            Log.i(TAG, "forceSync: hashes match ($localHash) — skipping full push")
            // Still send sync_done so a watch woken purely for the
            // sync_check terminates promptly instead of waiting out its
            // drain timer.
            sendWithoutWake(buildSyncDoneEnvelope())
            return ForceSyncResult.AlreadyInSync(alarms.size)
        }
        Log.i(TAG, "forceSync: hashes differ local=$localHash remote=${remoteHash ?: "<timeout>"} — full push")
        val result = pushFullReplace(alarms, remote?.supportsCommitAck == true)
        // Always mark the sync finished — both the replaced path here and
        // the matched path above — so the watch's syncing page closes on
        // the explicit signal rather than its drain-quiescence fallback.
        sendWithoutWake(buildSyncDoneEnvelope())
        return result
    }

    /**
     * Push the phone's full alarm list to the watch as ONE authoritative
     * `sync_replace`. Unlike the legacy per-alarm `alarm_added` burst, this
     * PRUNES watch-only rows (the watch replaces its store wholesale), so the
     * hash converges. Small lists go as a TEXT message; lists over the
     * text-message ceiling ([SYNC_REPLACE_MAX_BYTES]) go as a FILE transfer
     * ([pushFileReplace]) which has no ~1 KB limit. Both paths require a
     * persisted commit acknowledgement or a legacy hash verification.
     */
    @Suppress("ReturnCount")
    private suspend fun pushFullReplace(alarms: List<Alarm>, supportsCommitAck: Boolean): ForceSyncResult {
        val stamp = System.currentTimeMillis()
        val envelope = buildSyncReplaceEnvelope(alarms, stamp)
        val hash = AlarmHash.compute(alarms)
        val sizeBytes = envelope.toString().toByteArray(Charsets.UTF_8).size
        if (sizeBytes > SYNC_REPLACE_MAX_BYTES) {
            Log.i(
                TAG,
                "forceSync: sync_replace ${sizeBytes}B over ${SYNC_REPLACE_MAX_BYTES}B text cap " +
                    "— sending as file transfer (n=${alarms.size})",
            )
            return pushFileReplace(alarms, stamp, hash, supportsCommitAck)
        }
        if (supportsCommitAck) {
            val committed = sendTextReplaceAndAwaitAck(envelope, hash, stamp)
            return if (committed) ForceSyncResult.Ok(alarms.size)
            else ForceSyncResult.Error("Watch did not confirm saved alarm list")
        }
        val delivered = performSend(envelope, retryOnError = true)
        Log.i(TAG, "forceSync done sync_replace ${sizeBytes}B delivered=$delivered n=${alarms.size}")
        if (!delivered) return ForceSyncResult.Error("Watch comm failed (sync_replace not delivered)")
        val committed = awaitRemoteHashConvergence(hash, LEGACY_COMMIT_VERIFY_TIMEOUT_MS)
        return if (committed) ForceSyncResult.Ok(alarms.size)
        else ForceSyncResult.Error("Watch alarm list was not confirmed")
    }

    /**
     * Send the full-state replace as a Wear Engine FILE transfer (no ~1 KB text-
     * message limit). Mirrors the background-image upload: write the same
     * `sync_replace` JSON to a temp file `alarms_<hash>.json`, send it, and retry
     * until the watch's JS handler ACKs (`alarms_received`) — 207 only means the
     * transport accepted it, and the thin-client watch may terminate before its
     * receiver activates. The watch replaces its store wholesale + tombstones the
     * dropped ids, so this still prunes. The temp file is removed on every exit.
     */
    private suspend fun pushFileReplace(
        alarms: List<Alarm>,
        stamp: Long,
        hash: String,
        supportsCommitAck: Boolean,
    ): ForceSyncResult {
        val file = writeAlarmsSyncFile(buildSyncReplaceEnvelope(alarms, stamp).toString(), hash)
            ?: return ForceSyncResult.Error("Failed to stage alarm-sync file")
        return try {
            var attempt = 0
            var result: ForceSyncResult = ForceSyncResult.Error("Watch comm failed (alarms file not acked)")
            while (attempt < BG_UPLOAD_MAX_ATTEMPTS && result !is ForceSyncResult.Ok) {
                attempt++
                val device = ensurePairedDevice()
                if (device != null && ensurePeerAppRunning(device, force = true)) {
                    if (sendAlarmsFileAndAwaitAck(
                            device,
                            file,
                            hash,
                            stamp,
                            supportsCommitAck,
                            alarms.size,
                            attempt,
                        )
                    ) {
                        result = ForceSyncResult.Ok(alarms.size)
                    }
                } else {
                    Log.w(TAG, "pushFileReplace: peer not running (attempt=$attempt)")
                }
            }
            Log.i(TAG, "pushFileReplace done result=$result n=${alarms.size}")
            result
        } finally {
            runCatching { file.delete() }
        }
    }

    // reason: transport errors use RuntimeException; all request identity and retry fields
    // are needed to correlate the persisted ACK to this file transfer.
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private suspend fun sendAlarmsFileAndAwaitAck(
        device: Device,
        file: java.io.File,
        hash: String,
        stamp: Long,
        requireStamp: Boolean,
        n: Int,
        attempt: Int,
    ): Boolean {
        val ackDeferred = CompletableDeferred<Unit>()
        val pair = PendingAlarmsAck(hash, stamp, requireStamp, ackDeferred)
        pendingAlarmsAck.set(pair)
        val msg = Message.Builder().setPayload(file).setDescription(file.name).build()
        try {
            val sent = try {
                sendOnce(device, msg, FILE_SEND_TIMEOUT_MS)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w(TAG, "pushFileReplace send threw (attempt=$attempt): ${e.message}")
                invalidateAndMarkError()
                false
            }
            if (!sent) {
                invalidateAndMarkError()
                return false
            }
            Log.i(TAG, "pushFileReplace sent ${file.name} n=$n attempt=$attempt — awaiting ack")
            val acked = withTimeoutOrNull(SYNC_COMMIT_ACK_TIMEOUT_MS) { ackDeferred.await() } != null
            if (requireStamp) {
                if (acked) Log.i(TAG, "pushFileReplace confirmed n=$n after $attempt attempt(s)")
                else Log.w(TAG, "pushFileReplace no ack (attempt=$attempt) — retrying")
                return acked
            }
            if (!acked) Log.w(TAG, "pushFileReplace no legacy ack (attempt=$attempt) — verifying hash")
            // A legacy file ACK may be emitted before its async store write
            // finishes, so verify convergence even when the ACK arrived.
            return awaitRemoteHashConvergence(hash, LEGACY_COMMIT_VERIFY_TIMEOUT_MS)
        } finally {
            pendingAlarmsAck.compareAndSet(pair, null)
        }
    }

    // reason: the finally block must clear this exact pending ACK slot on every result.
    @Suppress("ReturnFromFinally")
    private suspend fun sendTextReplaceAndAwaitAck(envelope: JSONObject, hash: String, stamp: Long): Boolean {
        val deferred = CompletableDeferred<Unit>()
        val pending = PendingAlarmsAck(hash, stamp, requireStamp = true, deferred = deferred)
        pendingAlarmsAck.set(pending)
        return try {
            // 207 only proves the Wear Engine layer took the message. If no ack
            // follows, re-send the whole (idempotent) replace for another round
            // instead of giving up after the first silent drop.
            var acked = false
            var round = 0
            while (!acked && round < SYNC_COMMIT_ROUNDS) {
                round++
                val sent = performSend(envelope, retryOnError = true)
                acked = sent && withTimeoutOrNull(SYNC_COMMIT_ACK_TIMEOUT_MS) { deferred.await(); true } == true
                if (!acked) Log.w(TAG, "sync_replace: no app-ack round=$round sent=$sent")
            }
            acked
        } finally {
            pendingAlarmsAck.compareAndSet(pending, null)
        }
    }

    // Stage the sync_replace JSON to a private cache file named with the list's
    // AlarmHash, so the watch can echo that hash back as the app-level ack.
    private fun writeAlarmsSyncFile(json: String, hash: String): java.io.File? = try {
        java.io.File(context.cacheDir, "alarms_$hash.json").apply { writeText(json, Charsets.UTF_8) }
    } catch (e: java.io.IOException) {
        Log.w(TAG, "writeAlarmsSyncFile failed: ${e.message}")
        null
    }

    private suspend fun awaitRemoteHashConvergence(hash: String, timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remainingMs = deadline - android.os.SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return false
            val requestBudgetMs = minOf(SYNC_CHECK_TIMEOUT_MS, remainingMs)
            val remote = withTimeoutOrNull(requestBudgetMs) {
                requestRemoteHash(requestBudgetMs)
            }
            if (remote?.hash == hash) return true
            val pauseMs = minOf(LEGACY_HASH_RETRY_DELAY_MS, deadline - android.os.SystemClock.elapsedRealtime())
            if (pauseMs > 0L) delay(pauseMs)
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
    private suspend fun requestRemoteHash(timeoutMs: Long): IncomingMessage.SyncHash? = pendingHashMutex.withLock {
        val stamp = System.currentTimeMillis()
        val deferred = CompletableDeferred<IncomingMessage.SyncHash>()
        val pending = PendingHashRequest(stamp, deferred)
        pendingHashRequest.set(pending)
        try {
            val envelope = JSONObject().apply {
                put("type", "sync_check")
                put("updatedAtEpoch", stamp)
            }
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
            pendingHashRequest.compareAndSet(pending, null)
        }
    }

    // Phone-initiated, model-gated screen fetch. Sends `screen_request`; the
    // watch replies `watch_screen` (intercepted by [persistWatchScreen]). We
    // ask ONLY when the bonded model differs from the one we already cached a
    // resolution for — so a watch we've already measured is never re-pinged.
    // The in-flight debounce additionally collapses rapid forceSyncs into a
    // single request while a reply is still pending (without it, every
    // forceSync re-sends until the reply lands). Fire-and-forget: if the reply
    // is lost (watch app closed), the cropper falls back to the cached/default
    // size and a later forceSync — past the debounce — re-asks.
    // With list sync off, forceSync never runs, so the watch's screen size is
    // never fetched and the picture cropper has no frame to crop to. The
    // picture flow calls this after its consent dialog: one wake + one
    // screen_request, only when the size for the bonded model is unknown.
    // Waits briefly for the reply so the cropper opens with the right shape.
    // reason: ReturnCount — each early return is a distinct precondition
    // (permission, paired, connected, model, already cached, wake failed),
    // mirroring forceSync's guard ladder.
    @Suppress("ReturnCount")
    override suspend fun refreshWatchScreen(): Boolean {
        if (!ensurePermissionGranted()) return false
        val device = ensurePairedDevice() ?: return false
        if (!device.isConnected) return false
        val model = _pairedDeviceInfo.value?.displayName.orEmpty()
        if (model.isBlank()) return false
        val cached = settingsStore.snapshot()
        if (model == cached.watchScreenModel && cached.watchScreenWidth > 0) return true
        if (!ensurePeerAppRunning(device, force = true, timeoutMs = FORCE_SYNC_WAKE_TIMEOUT_MS)) {
            return false
        }
        skipNextBgReconcile = true
        forceScreenRefresh = true
        maybeRequestWatchScreen()
        val known = withTimeoutOrNull(SCREEN_REPLY_WAIT_MS) {
            settingsStore.state.first { it.watchScreenModel == model && it.watchScreenWidth > 0 }
        } != null
        // Bound the skip to this request's own wait. If the reply never came
        // (send dropped, or debounced behind another request), a stale flag
        // would silently skip the reconcile of some later, unrelated reply.
        skipNextBgReconcile = false
        // The watch was woken only for this; let it close.
        sendWithoutWake(buildSyncDoneEnvelope())
        Log.i(TAG, "refreshWatchScreen model='$model' known=$known")
        return known
    }

    private suspend fun maybeRequestWatchScreen() {
        val model = _pairedDeviceInfo.value?.displayName.orEmpty()
        if (model.isBlank()) return
        val cached = settingsStore.snapshot()
        // Normally skip once we've measured THIS model. A fresh-reconnect refresh
        // re-asks anyway so the watch_screen reply (with its bg marker) drives the
        // F4 reconcile after a watch reset that kept the same model.
        val refresh = forceScreenRefresh
        if (!refresh && model == cached.watchScreenModel && cached.watchScreenWidth > 0) return
        val now = System.currentTimeMillis()
        if (now < screenRequestInFlightUntilMs) return
        screenRequestInFlightUntilMs = now + SCREEN_REQUEST_DEBOUNCE_MS
        forceScreenRefresh = false
        Log.i(TAG, "screen_request (model='$model' cached='${cached.watchScreenModel}' refresh=$refresh)")
        performSend(JSONObject().apply { put("type", "screen_request") }, retryOnError = false)
    }

    // Persist the watch's `watch_screen` reply, keyed on the bonded model so
    // [maybeRequestWatchScreen] won't re-ask for a watch we've measured. Drop
    // a reply we can't key (no bonded model resolved) — persisting an empty
    // key would never satisfy the gate, re-requesting forever. The next
    // forceSync (with a resolved model) re-asks and persists correctly.
    private suspend fun persistWatchScreen(msg: IncomingMessage.WatchScreen) {
        // F4 bg reconcile is independent of the screen-model gate — run it
        // whether or not we can key the screen dims. The marker tells us if
        // the watch holds the same default bg the phone last uploaded.
        // Skipped once after refreshWatchScreen: the user is about to pick a
        // new picture, so re-uploading the old one would be a wasted send.
        if (skipNextBgReconcile) {
            skipNextBgReconcile = false
        } else {
            reconcileWatchBgMarker(msg.bgMarker)
        }
        val model = _pairedDeviceInfo.value?.displayName.orEmpty()
        if (model.isBlank()) {
            Log.w(TAG, "watch_screen ${msg.width}x${msg.height} dropped — no bonded model to key on")
            return
        }
        Log.i(TAG, "watch_screen ${msg.width}x${msg.height} ${msg.shape} bg='${msg.bgMarker}' model='$model'")
        settingsStore.setWatchScreen(msg.width, msg.height, msg.shape, model)
    }

    // reason: TooGenericExceptionCaught — the reconciler closure runs
    // repository code (encode + file I/O + Wear Engine send) that can surface
    // RuntimeException/IOException across OEM codecs; a leak here would unwind
    // into the receiver's coroutine and drop the rest of the watch_screen
    // handling. Reconcile is best-effort; log and move on.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun reconcileWatchBgMarker(marker: String) {
        val reconciler = watchBgReconciler ?: return
        try {
            reconciler(marker)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "reconcileWatchBgMarker threw: ${e.message}", e)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "reconcileWatchBgMarker I/O: ${e.message}", e)
        }
    }

    // reason: ForbiddenVoid suppressed because Task<Void> is the Huawei SDK
    // signature for `P2pClient.ping`; replacing with Task<*> would lose
    // the type information from the SDK.
    @Suppress("ForbiddenVoid")
    private suspend fun pingPeer(device: Device): Int =
        // Bounded so a ping callback that never fires cannot hold wakeMutex.
        withTimeoutOrNull(SDK_WAIT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { cont ->
                val task = p2pClient.ping(device, PingCallback { code ->
                    if (cont.isActive) cont.resumeWith(Result.success(code))
                })
                task.addOnFailureListener { e ->
                    Log.w(TAG, "ping Task failed: ${e.message}")
                    if (cont.isActive) cont.resumeWith(Result.success(PING_FAILED_SENTINEL))
                }
            }
        } ?: PING_FAILED_SENTINEL.also { Log.w(TAG, "ping timed out after ${SDK_WAIT_TIMEOUT_MS}ms") }

    // reason: Wear Engine's requestPermission is documented to throw
    // RuntimeException when Huawei Health / HMS Core is missing or out of
    // date — degrade silently so the host activity isn't crashed by a
    // missing-vendor case.
    @Suppress("TooGenericExceptionCaught")
    override fun requestPermissionFromActivity(
        activity: android.app.Activity,
        onResult: (WearPermissionOutcome) -> Unit,
    ) {
        try {
            HiWear.getAuthClient(activity).requestPermission(
                object : AuthCallback {
                    override fun onOk(grantedPermissions: Array<out Permission>?) {
                        Log.i(TAG, "Wear Engine permission granted: ${grantedPermissions?.size ?: 0}")
                        scope.launch { ensureReceiverRegistered() }
                        onResult(WearPermissionOutcome.Granted)
                    }
                    override fun onCancel() {
                        Log.w(TAG, "Wear Engine permission cancelled by user")
                        onResult(WearPermissionOutcome.Cancelled)
                    }
                },
                Permission.DEVICE_MANAGER,
            )
        } catch (e: RuntimeException) {
            // No dialog was ever shown. Reporting this is the whole point:
            // the callback lets the UI say so instead of leaving the user
            // tapping a button that appears inert.
            Log.w(TAG, "requestPermission threw: ${e.message}", e)
            onResult(WearPermissionOutcome.Unavailable(e.message.orEmpty()))
        }
    }

    private companion object {
        const val TAG = "HuaweiWearBridge"

        // Logcat tag for watch-side log lines relayed via watch_log_batch.
        const val WATCH_LOG_TAG = "WatchLog"

        const val PEER_PKG_NAME = "com.kirkouski.gtwatch.watch"

        // Format `<watch_bundleName>_<base64(raw_uncompressed_EC_pubKey)>`
        // (P-256 self-signed keystore alias `gtalarm.watch`). Extraction
        // procedure: see memory:wear_engine_lite_facts.md.
        // Tracked Phase 5b: end-to-end pairing test on real GT 6 + Samsung
        // phone validates this constant. If the SDK rejects this encoding,
        // the fallback is the watch's own keystore-cert SHA-256 (hex),
        // NOT the value in the watch's supportLists (that string carries
        // the PHONE cert SHA-256 — opposite direction).
        const val PEER_FP =
            "com.kirkouski.gtwatch.watch_" +
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
        private const val DEVICE_CACHE_MS = 2_000L

        // Upper bound for one SDK wait that is not a message send: a Task result
        // (bonded devices, permission, register/unregister) or a ping callback.
        // Without it a Task that never completes holds connectionMutex or
        // wakeMutex, and every send queues behind it forever.
        private const val SDK_WAIT_TIMEOUT_MS = 5_000L

        // A timed-out unregister still pending after this long is treated as lost
        // (hung binder call) so registration is not blocked until the process
        // restarts. Far above any plausible late completion.
        private const val ABANDON_UNREGISTER_AFTER_MS = 60_000L

        // Upper bound for one send callback. The alarm-path budgets (pre-arm
        // 10 s, retry deadline 12 s) are larger than this on purpose.
        private const val SEND_TIMEOUT_MS = 8_000L

        // File transfers (default picture, alarm-list file) take longer.
        private const val FILE_SEND_TIMEOUT_MS = 30_000L

        // Replies to a watch request (sendPeerActionAck / sendPeerEnded): no
        // wake, a few quick retries, never longer than the total below.
        private const val PEER_REPLY_TOTAL_MS = 4_000L
        private const val PEER_REPLY_SEND_TIMEOUT_MS = 1_500L
        private const val PEER_REPLY_RETRY_DELAY_MS = 300L
        private const val PEER_REPLY_MAX_ATTEMPTS = 4
        private const val MANUAL_SYNC_TIMEOUT_MS = 60_000L
        private const val SYNC_COMMIT_ACK_TIMEOUT_MS = 4_000L

        // Rounds of send + ack wait for a text sync_replace (inside the 60 s
        // manual-sync deadline).
        private const val SYNC_COMMIT_ROUNDS = 2

        // Wake budget for the manual "Sync now" tap only — see forceSync.
        const val FORCE_SYNC_WAKE_TIMEOUT_MS = 25_000L
        // How long refreshWatchScreen waits for the watch_screen reply.
        private const val SCREEN_REPLY_WAIT_MS = 5_000L
        const val PING_WAKE_POLL_DELAY_MS = 400L

        // Cache "peer is 202 RUNNING" for this long so the dismiss / snooze
        // path doesn't re-poll just because a few seconds passed since fire.
        //
        // 30 s, bumped 2026-05-13 (was 5 s). The cache is also refreshed on
        // every successful onSendResult 207 and on every `alarm_ringing`
        // receive, so as long as the watch keeps confirming it's reachable
        // the cache stays warm indefinitely. Any 206 (COMM_FAIL) or send
        // throw immediately invalidates it — never wedged stale.
        //
        // User-noticed: "why we need 'reconnecting' to watch to dismiss?" —
        // 5 s was too short for a ring window where the user often takes
        // 10–20 s to react before tapping Dismiss on the phone.
        const val PEER_RUNNING_CACHE_MS = 30_000L

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

        // Application-level re-send budget for the dismiss / snooze ack
        // path. retrySend (above) only re-fires on a transport error
        // (206/throw); it does NOT cover the case where the transport
        // ACKs 207 but the watch's JS receiver was mid-transition and
        // silently dropped the envelope. sendAwaitingAck re-sends the
        // whole envelope ACK_RESEND_ROUNDS times, waiting
        // ACK_RESEND_WAIT_MS for the watch's app-level ack each round.
        // 2 rounds × 4 s ack-wait + per-round ping/send overhead fits
        // inside the 12 s BROADCAST_AWAIT_MS caller budget.
        const val ACK_RESEND_ROUNDS = 2
        const val ACK_RESEND_WAIT_MS = 4_000L

        // Hash-precheck budget for forceSync. The watch responds with a
        // sync_hash envelope after computing the canonical hash of its
        // alarm set. Round-trip is one send + one receive, so 2 s gives
        // generous headroom even on a slow BT link. On timeout we fall
        // through to a full push — never user-blocking.
        const val SYNC_CHECK_TIMEOUT_MS = 2_000L
        private const val LEGACY_COMMIT_VERIFY_TIMEOUT_MS = 10_000L
        private const val LEGACY_HASH_RETRY_DELAY_MS = 250L

        // In-flight window for the model-gated screen_request. A watch that's
        // up replies in well under a second; 5 s comfortably covers a slow BT
        // round-trip so rapid forceSyncs don't each re-send while one reply is
        // pending. If the watch never replies, the next forceSync past this
        // window re-asks (matches the fire-and-forget fallback).
        const val SCREEN_REQUEST_DEBOUNCE_MS = 5_000L

        // Bg-file upload: retry until the watch's JS handler ACKs (bg_received).
        // The thin-client watch terminates after each sync and its receiver
        // takes ~2 s to go live, so a send routinely lands on a dead/not-ready
        // app. Each attempt re-launches + keeps the watch alive; the timeout is
        // long enough for the receiver to activate, short enough to retry while
        // the watch is still in its post-launch drain window.
        const val BG_ACK_TIMEOUT_MS = 4_000L
        const val BG_UPLOAD_MAX_ATTEMPTS = 6

        // Ceiling (UTF-8 bytes) for sending the full-state replace as a P2P
        // TEXT message. Empirically the Wear Engine text-message limit on this
        // Lite-Wearable pairing is ~1 KB: a 3-alarm/817 B replace round-trips,
        // a 4-alarm/1068 B one is accepted by the transport (207) but truncated
        // before the watch's JSON.parse — the watch silently keeps its prior
        // state and the hashes never converge (the >3-alarm sync bug). 768 B is
        // a conservative cutoff below the largest proven-good size (817 B). Over
        // the cap, the replace is sent as a FILE transfer (pushFileReplace) which
        // has no such limit. The old 4096 value sat ~4× above the real ceiling.
        const val SYNC_REPLACE_MAX_BYTES = 768
    }
}
