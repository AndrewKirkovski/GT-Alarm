// Wear Engine bridge — phone-side peer talks to us via P2P. We register
// a receiver in app.onCreate so any inbound JSON is routed through
// IncomingHandler. Sends from watch→phone are reserved for the user's
// dismiss/snooze acks; watch never originates schedule changes.
//
// Per Huawei official wearengine.js wrapper (Apache 2.0,
// Explore-In-HMOS-Wearable/wear-engine-lite-wearable-to-mobile), both
// `peerPkgName` (the phone-side bundle) AND `peerFingerPrint` (the
// phone-side signing cert SHA-256 in UPPERCASE hex, no colons) MUST be
// set or registerReceiver fails. This is the inverse of the phone's
// setPeerFingerPrint(watch_bundle_base64_pubkey) call.
import { P2pClient, Message, Builder } from '../wearEngine/wearengine.js';
import Logger from './logger.js';

// PHONE_PKG_NAME is the Android phone APK's applicationId. Used by
// setPeerPkgName so the watch's P2pClient knows which peer to send to.
//
// PHONE_CERT_SHA256 is the phone signing-cert SHA-256 (uppercase hex,
// no separators). Set at runtime via setPeerFingerPrint because the
// vendored wearengine.js wrapper's isEmpty check on send() returns
// synchronously without transmitting if either peerPkgName OR
// peerFingerPrint is unset (see line ~269 of wearengine/wearengine.js).
// PHONE_CERT_SHA256 ALSO appears in config.json's supportLists for the
// inbound-validation side.
//
// Note: the format/exact value the SDK validates is undocumented and
// under active investigation (HMS-Core public demo doesn't show the
// exact format used). Treat this as best-guess until verified.
var PHONE_PKG_NAME = 'com.kirkouski.gtalarm';
var PHONE_CERT_SHA256 =
    '69DB34264AB9A5DFB162A0D5B306D90F1515AC76D42B7B9E96A04C7B778CD0D9';

var client = null;
var incomingHandler = null;
var receiverRegistered = false;

// Trace of the most recent send-step the code reached. Set by
// sendOnceWithResult before/after each phase. Exported via getLastTrace()
// so the index page can render it — invaluable when c.send hangs and we
// can't see HiLog.
var lastTrace = 'idle';
function trace(s) {
    lastTrace = s;
}

function ensureClient() {
    if (client === null) {
        client = new P2pClient();
        client.setPeerPkgName(PHONE_PKG_NAME);
        client.setPeerFingerPrint(PHONE_CERT_SHA256);
        Logger.i('wearBridge.ensureClient created (pkg+fp set, wrapper-required)');
    }
    return client;
}

function dispatchIncoming(rawMessage) {
    if (!incomingHandler) {
        Logger.w('wearBridge.incoming no handler set — dropping');
        return;
    }
    if (typeof rawMessage !== 'string') {
        Logger.w('wearBridge.incoming non-string payload');
        return;
    }
    var parsed = null;
    try {
        parsed = JSON.parse(rawMessage);
    } catch (e) {
        Logger.err('wearBridge.incoming JSON.parse', e);
        return;
    }
    try {
        incomingHandler(parsed);
    } catch (e) {
        Logger.err('wearBridge.incoming handler threw', e);
    }
}

function registerOnce() {
    if (receiverRegistered) return;
    var c = ensureClient();
    c.registerReceiver({
        onSuccess: function () {
            receiverRegistered = true;
            Logger.i('wearBridge.registerReceiver onSuccess');
        },
        onFailure: function () {
            receiverRegistered = false;
            Logger.w('wearBridge.registerReceiver onFailure');
        },
        onReceiveMessage: function (data) {
            // The wearengine.js wrapper passes the raw `data.message`
            // (string) for plain text messages. File messages arrive as
            // `{ isFileType: true, name: ..., mode: ... }` — we don't
            // use those.
            if (data && data.isFileType) {
                Logger.i('wearBridge.onReceiveMessage file=' + data.name + ' (ignored)');
                return;
            }
            Logger.i('wearBridge.onReceiveMessage len=' + (data ? ('' + data).length : 0));
            dispatchIncoming(data);
        },
    });
}

function unregister() {
    if (!receiverRegistered || !client) return;
    client.unregisterReceiver({
        onSuccess: function () {
            receiverRegistered = false;
            Logger.i('wearBridge.unregister onSuccess');
        },
        onFailure: function () {
            Logger.w('wearBridge.unregister onFailure');
        },
    });
}

function buildMessage(payloadStr) {
    var builder = new Builder();
    builder.setPayload(payloadStr);
    var msg = new Message();
    msg.builder = builder;
    return msg;
}

// One-shot send. Calls `cb(success, reason)` exactly once.
// IMPORTANT: transport-level logs use console.* directly, NOT Logger.i.
// If we routed them through Logger, the log relay would mirror them back
// to the phone as watch_log envelopes — and each of those sends triggers
// ANOTHER onSendResult → another Logger call → unbounded loop. The
// on-device HiLog still captures them.
//
// `reason` is a short tag for the caller's UI: '207', '206', 'fail',
// 'timeout', 'throw'. Hard-timeout at SEND_TIMEOUT_MS guarantees the
// callback fires even when the SDK silently never invokes onSendResult /
// onFailure (observed on real GT 6 Pro when peer is unreachable — the
// JS callback just never fires and the caller hangs forever otherwise).
var SEND_TIMEOUT_MS = 3000;

function sendOnceWithResult(envelope, cb, onTrace) {
    function tr(step) {
        trace(step);
        if (onTrace) onTrace(step);
    }
    tr('ensureClient');
    var c = ensureClient();
    // Finer-grained tracing — earlier "buildMsg" label was set BEFORE the
    // actual build, so any hang inside JSON.stringify / new Builder /
    // setPayload / new Message looked identical to "stuck at buildMsg."
    // Splitting into per-step labels so we can see exactly which SDK
    // constructor blocks.
    tr('stringify');
    var payload = JSON.stringify(envelope);
    tr('newBuilder');
    var builder = new Builder();
    // CRITICAL: use setDescription (string path) NOT setPayload (ArrayBuffer
    // path). setPayload(jsString) calls setBufferPlayload internally, which
    // does `new Uint16Array(string)` — that yields zeros, then
    // String.fromCharCode.apply(null, [0,0,0...]) produces a string of NUL
    // bytes. The SDK then receives garbage and silently never fires a
    // callback. Matches HMS-Core official demo's setDescription("hello
    // wearEngine") pattern exactly. Verified by reading wearengine.js
    // 5.0.2.306 lines 430-457 against HMS demo index.js.
    tr('setDescription');
    builder.setDescription(payload);
    tr('newMessage');
    var msg = new Message();
    msg.builder = builder;
    var settled = false;
    function settle(success, reason) {
        if (settled) return;
        settled = true;
        if (cb) cb(success, reason);
    }
    tr('schedTimeout');
    var timeoutId = setTimeout(function () {
        tr('TIMEOUT fired');
        if (!settled) {
            console.warn('GT-Alarm: wearBridge.send TIMEOUT type=' + envelope.type +
                ' (no onSendResult after ' + SEND_TIMEOUT_MS + 'ms)');
            settle(false, 'timeout');
        }
    }, SEND_TIMEOUT_MS);
    try {
        tr('before c.send');
        c.send(msg, {
            onSuccess: function () {
                tr('onSuccess fired');
                console.info('GT-Alarm: wearBridge.send onSuccess type=' + envelope.type);
            },
            onFailure: function () {
                tr('onFailure fired');
                console.warn('GT-Alarm: wearBridge.send onFailure type=' + envelope.type);
                clearTimeout(timeoutId);
                settle(false, 'fail');
            },
            onSendResult: function (resultCode) {
                var code = resultCode && typeof resultCode === 'object'
                    ? resultCode.code
                    : resultCode;
                tr('onSendResult c=' + code);
                console.info('GT-Alarm: wearBridge.send onSendResult type=' +
                    envelope.type + ' code=' + code);
                clearTimeout(timeoutId);
                settle(code === 207, '' + code);
            },
            onSendProgress: function () {},
        });
        tr('after c.send');
    } catch (e) {
        tr('c.send threw');
        console.error('GT-Alarm: wearBridge.send threw type=' + envelope.type +
            ' err=' + (e && e.message ? e.message : '' + e));
        clearTimeout(timeoutId);
        settle(false, 'throw');
    }
}

// Fire-and-forget — for log relay and non-critical traffic. Drops the
// result code on the floor.
function sendJson(envelope) {
    sendOnceWithResult(envelope, null);
}

// Retry up to `maxAttempts` times with 500 ms between retries when the
// onSendResult callback reports anything other than 207 (COMM_SUCCESS).
// Used for dismiss/snooze where missing the message means the phone
// keeps ringing — must NOT silently fail. Total worst case latency:
// (attempts - 1) × 500 ms + per-send round trips, typically <2 s.
//
// Note: setTimeout is foreground-only on Lite Wearable (gotcha #13). If
// the page hides between the failed send and the retry, the retry never
// fires. Acceptable: in the dismiss case the user is still looking at
// the watch when they tap dismiss, so the page stays alive for the
// retry window.
// Optional terminalCb fires exactly once on terminal state: either the
// first successful attempt or after `maxAttempts` failures. Callers like
// ring.js use it to expedite app.terminate after the ack lands instead
// of waiting out the hard-cap.
function sendJsonWithRetry(envelope, maxAttempts, attempt, terminalCb) {
    if (!maxAttempts) maxAttempts = 3;
    if (!attempt) attempt = 1;
    sendOnceWithResult(envelope, function (success, reason) {
        if (success) {
            if (terminalCb) terminalCb(true, reason);
            return;
        }
        if (attempt >= maxAttempts) {
            console.warn('GT-Alarm: wearBridge.send gave up type=' + envelope.type + ' after ' + attempt + ' attempts');
            if (terminalCb) terminalCb(false, reason);
            return;
        }
        setTimeout(function () {
            console.info('GT-Alarm: wearBridge.send retry type=' + envelope.type + ' attempt=' + (attempt + 1));
            sendJsonWithRetry(envelope, maxAttempts, attempt + 1, terminalCb);
        }, 500);
    });
}

export default {
    // Called from app.onCreate with a callback that handles parsed
    // BridgeMessage objects. Passing null detaches the handler and
    // unregisters the receiver (used in app.onDestroy).
    setIncomingHandler: function (handler) {
        incomingHandler = handler;
        if (handler !== null) {
            registerOnce();
        } else {
            unregister();
        }
    },

    notifyToggled: function (alarm) {
        sendJson({
            type: 'alarm_toggled',
            alarmId: alarm.id,
            updatedAtEpoch: alarm.updatedAtEpoch,
            enabled: !!alarm.enabled,
        });
    },
    // Dismiss + snooze use the retry path because dropping them means
    // the phone keeps ringing. 3 attempts × 500 ms delay = up to ~1.5 s
    // of recovery window per tap, in addition to the per-send round
    // trips. notifyToggled/notifyDeleted stay fire-and-forget since the
    // user is staring at the list UI and can re-tap if a sync fails.
    // `cb(success, reason)` fires once on terminal state (success or
    // exhausted retries). Ring page uses it to expedite app.terminate
    // after the phone acks, instead of waiting out the hard-cap.
    notifyDismissed: function (alarmId, cb) {
        sendJsonWithRetry({
            type: 'alarm_dismissed',
            alarmId: alarmId,
            updatedAtEpoch: Date.now(),
        }, 3, 1, cb);
    },
    // The phone owns snooze duration (per-alarm `alarm.snoozeMinutes`),
    // so the watch never sends a reschedule time — phone derives it from
    // the local row when the message arrives.
    notifySnoozed: function (alarmId, cb) {
        sendJsonWithRetry({
            type: 'alarm_snoozed',
            alarmId: alarmId,
            updatedAtEpoch: Date.now(),
        }, 3, 1, cb);
    },
    notifyDeleted: function (alarmId) {
        sendJson({
            type: 'alarm_deleted',
            alarmId: alarmId,
            updatedAtEpoch: Date.now(),
        });
    },

    // Response to a phone-originated `sync_check` envelope. Phone uses the
    // hash to decide whether to skip the full force-sync push. Fire-and-
    // forget — if it's dropped, the phone just times out and does the
    // full push (graceful degradation, never user-blocking).
    sendSyncHash: function (hash) {
        sendJson({
            type: 'sync_hash',
            hash: hash,
        });
    },

    // Confirms to the phone that the watch's ring page is up and
    // vibrating. Phone awaits this before starting its own audio so the
    // two devices ring in lock-step instead of phone-first / watch-late.
    // Use retry path because losing this means the phone has to time out
    // (3 s) before falling back to ringing alone.
    sendRinging: function (alarmId) {
        sendJsonWithRetry({
            type: 'alarm_ringing',
            alarmId: alarmId,
            updatedAtEpoch: Date.now(),
        }, 3, 1);
    },

    // Dev-only: relay a watch-side log line to the phone so it surfaces in
    // adb logcat (Lite Wearable HiLog is hard to read from DevEco on Windows).
    // No fingerprint validation, no LWW — just envelope + send. NOT called
    // from inside wearBridge's own log paths to avoid re-entry.
    sendLog: function (level, msg) {
        sendJson({
            type: 'watch_log',
            level: level,
            msg: msg,
            ts: Date.now(),
        });
    },

    // Dev-only diagnostic send with result callback. Used by the PING PHONE
    // button on index page to show the SDK's onSendResult code (207=ok,
    // 206=fail) on the watch face — lets us tell whether watch→phone P2P
    // is failing at the watch's send call or arriving and being rejected
    // by phone's receiver layer.
    //
    // onTrace(step) is invoked at each major phase of send so the caller
    // can show real-time progress in the UI — invaluable when the SDK
    // hangs and HiLog isn't accessible.
    sendPing: function (cb, onTrace) {
        sendOnceWithResult({
            type: 'watch_log',
            level: 'I',
            msg: 'PING_FROM_WATCH ts=' + Date.now(),
            ts: Date.now(),
        }, cb, onTrace);
    },

    // Read-only — last send-step the bridge reached. Useful for the
    // index page's diagnostic UI to render between callback events.
    getLastTrace: function () {
        return lastTrace;
    },
};
