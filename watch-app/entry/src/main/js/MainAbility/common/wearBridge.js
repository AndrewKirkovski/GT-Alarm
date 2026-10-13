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
import Ticker from './ticker.js';

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
var PHONE_PKG_NAME = 'com.kirkouski.gtwake.companion';
var PHONE_CERT_SHA256 =
    '95F6001E2B44692CCE6FE6663A98AA2BDDB6AE557DB77DBBA346D03DBC33BE5C';

var client = null;
var incomingHandler = null;

// Handler for inbound P2P FILE messages (as opposed to JSON text). Set
// by the page via setFileHandler. Wear Engine delivers a received file
// as `{ isFileType: true, name: <on-watch path> }`; we hand that path
// to the page so it can try referencing it directly (e.g. as a
// background <image src>). null = files ignored.
var fileHandler = null;

// Per-bundle page tag (e.g. 'app', 'index', 'ring', 'syncing'). Set by
// each page via setPageTag before it wires its receiver. Lite Wearable
// isolates page bundles (gotcha #11), so this module + its receiver
// state is a SEPARATE copy per page — the tag tells the relayed logs
// which bundle's receiver actually fired.
var pageTag = 'app';

function noop() {}

function ensureClient() {
    if (client === null) {
        client = new P2pClient();
        client.setPeerPkgName(PHONE_PKG_NAME);
        client.setPeerFingerPrint(PHONE_CERT_SHA256);
    }
    return client;
}

function dispatchIncoming(rawMessage) {
    if (!incomingHandler) {
        return;
    }
    if (typeof rawMessage !== 'string') {
        return;
    }
    var parsed = null;
    try {
        parsed = JSON.parse(rawMessage);
    } catch (e) {
        Logger.err('bad JSON', e);
        return;
    }
    try {
        incomingHandler(parsed);
    } catch (e) {
        Logger.err('handler threw', e);
    }
}

// Bind this bundle's P2pClient receiver to the GLOBAL Wear Engine
// message subscription. Deliberately NOT guarded by an "already registered"
// flag — Fix 1
// (per-page receiver registration) REQUIRES this to re-register on
// every call. Each page bundle owns an isolated copy of this module
// (gotcha #11); when a page becomes foreground it must re-point the
// single global `subscribeMsg` slot at ITS OWN live callback closure,
// because the previously-foreground page's closure stops being
// serviced. The wrapper's registerReceiver does a global
// unsubscribeMsg()+subscribeMsg() so re-registering is the intended,
// idempotent operation.
function installReceiver() {
    var c = ensureClient();
    c.registerReceiver({
        onSuccess: function () {
            Logger.i('wearBridge[' + pageTag + '].registerReceiver onSuccess');
        },
        onFailure: function () {
            Logger.w('wearBridge[' + pageTag + '].registerReceiver onFailure');
        },
        onReceiveMessage: function (data) {
            // The wearengine.js wrapper passes the raw `data.message`
            // (string) for plain text messages. File messages arrive as
            // `{ isFileType: true, name: ..., mode: ... }` — we don't use those.
            if (data && data.isFileType) {
                if (fileHandler) {
                    try {
                        fileHandler(data.name);
                    } catch (e) {
                        Logger.err('wearBridge.fileHandler', e);
                    }
                }
                return;
            }
            // `len` was a declared local until commit bedafb7 (2026-06-09)
            // removed the diag counter `var len = …; bumpRawRx(len)` but left
            // this log referencing it — an undeclared read throws ReferenceError
            // on ACELite/JerryScript (ES5.1), aborting the callback BEFORE
            // dispatchIncoming and silently dropping every inbound JSON message.
            // Recompute locally so the dispatch can never be gated on a log.
            var n = (data == null) ? 0 : ('' + data).length;
            Logger.i('wearBridge[' + pageTag + '].onReceiveMessage len=' + n);
            dispatchIncoming(data);
        },
    });
}

// One-shot send. Calls `cb(success, reason)` exactly once.
// IMPORTANT: never log a send's outcome through Logger. The log relay
// itself sends watch_log_batch envelopes, so each of those sends would
// trigger ANOTHER send result -> another Logger call -> unbounded loop.
// (The console.* echo this used instead was removed 2026-10-01: HiLog is
// unreadable on the GT 6, so it only cost bundle bytes.)
//
// `reason` is a short tag: '207', '206', 'fail', 'timeout', 'throw'.
// Hard-timeout at SEND_TIMEOUT_MS guarantees the callback fires even when
// the SDK silently never invokes onSendResult / onFailure (observed on real
// GT 6 Pro when peer is unreachable — the JS callback just never fires and
// the caller hangs forever otherwise).
var SEND_TIMEOUT_MS = 3000;
var sendSequence = 0;

function sendOnceWithResult(envelope, cb) {
    // CRITICAL: use setDescription (string path) NOT setPayload (ArrayBuffer
    // path). setPayload(jsString) calls setBufferPlayload internally, which
    // does `new Uint16Array(string)` — that yields zeros, then
    // String.fromCharCode.apply(null, [0,0,0...]) produces a string of NUL
    // bytes. The SDK then receives garbage and silently never fires a
    // callback. Matches HMS-Core official demo's setDescription("hello
    // wearEngine") pattern exactly. Verified by reading wearengine.js
    // 5.0.2.306 lines 430-457 against HMS demo index.js.
    var settled = false;
    var timeoutId = 'wearSend' + (++sendSequence);
    var settle = function (success, reason) {
        if (settled) return;
        settled = true;
        Ticker.clear(timeoutId);
        if (cb) cb(success, reason);
    };
    Ticker.set(timeoutId, SEND_TIMEOUT_MS, function () {
        settle(false, 'timeout');
    });
    try {
        var c = ensureClient();
        var builder = new Builder();
        builder.setDescription(JSON.stringify(envelope));
        var msg = new Message();
        msg.builder = builder;
        c.send(msg, {
            onSuccess: noop,
            onFailure: function () {
                settle(false, 'fail');
            },
            onSendResult: function (resultCode) {
                var code = resultCode && typeof resultCode === 'object'
                    ? resultCode.code
                    : resultCode;
                settle(code === 207, '' + code);
            },
            onSendProgress: noop,
        });
    } catch (e) {
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
// Optional isLive() is checked before every retry: once it returns false the
// chain stops silently, so a request the page has given up on is never re-sent.
function sendJsonWithRetry(envelope, maxAttempts, attempt, terminalCb, isLive) {
    if (!maxAttempts) maxAttempts = 3;
    if (!attempt) attempt = 1;
    sendOnceWithResult(envelope, function (success, reason) {
        if (success) {
            if (terminalCb) terminalCb(true, reason);
            return;
        }
        if (attempt >= maxAttempts) {
            if (terminalCb) terminalCb(false, reason);
            return;
        }
        Ticker.set('wearRetry' + (++sendSequence), 500, function () {
            if (isLive && !isLive()) return;
            sendJsonWithRetry(envelope, maxAttempts, attempt + 1, terminalCb, isLive);
        });
    });
}

export default {
    // Called from the page's onShow with a callback that handles parsed
    // BridgeMessage objects. Re-registers on every call (see installReceiver).
    setIncomingHandler: function (handler) {
        incomingHandler = handler;
        if (handler !== null) installReceiver();
    },

    // Per-bundle page tag for diagnostics — call before setIncomingHandler.
    setPageTag: function (tag) {
        pageTag = tag;
    },

    // Register a handler for inbound P2P FILE messages. `fn(path)` gets
    // the on-watch path Wear Engine saved the received file to. Used by
    // the background-image test: the page points an <image src> at it.
    setFileHandler: function (fn) {
        fileHandler = fn;
    },

    // Dismiss + snooze use the retry path because dropping them means
    // the phone keeps ringing. 3 attempts × 500 ms delay = up to ~1.5 s
    // of recovery window per tap, in addition to the per-send round
    // trips.
    //
    // RETRIES-ONLY BY DESIGN — no blocking wait for a phone application
    // ack. The watch CANNOT wake the Android app: a watch→phone message
    // only lands if the phone already has a live Activity in its task
    // (Wear Engine asymmetry). The watch has no way to know whether the
    // phone has one, so blocking on a phone reply could hang forever —
    // and a hidden-page setTimeout never fires anyway (Lite gotcha #13).
    // `cb(success, reason)` therefore reports only the TRANSPORT outcome
    // (207 = delivered to the phone's Wear Engine layer, or retries
    // exhausted), NOT phone-side application confirmation. The phone is
    // the always-on side and owns reliable convergence: it re-pushes the
    // full alarm list (debounced forceSync) on every mutation and on
    // foreground. Ring page uses `cb` to expedite app.terminate once the
    // transport succeeds, instead of waiting out the hard-cap.
    notifyDismissed: function (alarmId, firedAt, requestStamp, cb, isLive) {
        var envelope = {
            type: 'alarm_dismissed',
            alarmId: alarmId,
            updatedAtEpoch: requestStamp,
        };
        if (typeof firedAt === 'number' && firedAt > 0) envelope.firedAt = firedAt;
        sendJsonWithRetry(envelope, 3, 1, cb, isLive);
    },
    // The phone owns snooze duration (per-alarm `alarm.snoozeMinutes`),
    // so the watch never sends a reschedule time — phone derives it from
    // the local row when the message arrives.
    notifySnoozed: function (alarmId, firedAt, requestStamp, cb, isLive) {
        var envelope = {
            type: 'alarm_snoozed',
            alarmId: alarmId,
            updatedAtEpoch: requestStamp,
        };
        if (typeof firedAt === 'number' && firedAt > 0) envelope.firedAt = firedAt;
        sendJsonWithRetry(envelope, 3, 1, cb, isLive);
    },
    // Response to a phone-originated `sync_check` envelope. Phone uses the
    // hash to decide whether to skip the full force-sync push. Fire-and-
    // forget — if it's dropped, the phone just times out and does the
    // full push (graceful degradation, never user-blocking).
    sendSyncHash: function (hash, updatedAtEpoch) {
        var envelope = {
            type: 'sync_hash',
            hash: hash,
            supportsCommitAck: true,
        };
        if (typeof updatedAtEpoch === 'number') envelope.updatedAtEpoch = updatedAtEpoch;
        sendJson(envelope);
    },

    // Reply to a phone-originated `screen_request` with this watch's panel
    // metrics so the phone can size + shape the uploaded background to the
    // real screen. Phone-initiated only — the watch never pushes this
    // unprompted. Fire-and-forget: if it's lost the phone re-asks on its next
    // sync (gated on the bonded model, so a known watch isn't re-pinged).
    //
    // F4: `bg` carries the content hash of the default background this watch
    // currently holds (parsed from the received file name `bgd_<hash>.bin`),
    // or '' when there's no bg / the received file is missing. The phone
    // reconciles it against its last-uploaded hash to auto-restore a lost
    // image and to never re-send a stale/mismatching one. Defaults to '' so
    // callers that don't pass it stay wire-compatible.
    sendWatchScreen: function (width, height, shape, bg) {
        sendJson({
            type: 'watch_screen',
            width: width,
            height: height,
            shape: shape,
            bg: (typeof bg === 'string') ? bg : '',
            updatedAtEpoch: Date.now(),
        });
    },

    // Confirms to the phone that THIS watch's file handler actually STORED the
    // received default-bg file (hash parsed from `bgd_<hash>.<ext>`). The phone
    // awaits this `bg_received` ack and re-sends the file until it arrives — a
    // 207 only means the Wear Engine layer accepted delivery, NOT that this
    // (often just-launched / about-to-terminate) app processed it. Retry the
    // small ack so a single drop doesn't trigger a full file re-upload.
    sendBgReceived: function (hash) {
        sendJsonWithRetry({
            type: 'bg_received',
            hash: '' + hash,
            updatedAtEpoch: Date.now(),
        }, 3, 1);
    },

    // Confirms to the phone that THIS watch parsed + stored a file-delivered
    // full-state replace (the alarm list, sent as a FILE when it exceeds the
    // ~1 KB text-message ceiling — see HuaweiWearBridge.pushFileReplace). The
    // phone awaits this `alarms_received` ack (hash echoed from `alarms_<hash>.
    // json`) and re-sends until it arrives; a 207 only means Wear Engine accepted
    // delivery, NOT that this (often just-launched / about-to-terminate) app
    // applied it. Retry the small ack so a single drop doesn't re-send the file.
    sendAlarmsReceived: function (hash, requestStamp) {
        sendJsonWithRetry({
            type: 'alarms_received',
            hash: '' + hash,
            updatedAtEpoch: requestStamp,
        }, 3, 1);
    },

    // Confirms to the phone that the watch's ring page is up and
    // vibrating. Phone awaits this before starting its own audio so the
    // two devices ring in lock-step instead of phone-first / watch-late.
    // Use retry path because losing this means the phone has to time out
    // (3 s) before falling back to ringing alone.
    sendRinging: function (alarmId, firedAt, requestStamp) {
        var envelope = {
            type: 'alarm_ringing',
            alarmId: alarmId,
            updatedAtEpoch: requestStamp,
        };
        if (typeof firedAt === 'number' && firedAt > 0) envelope.firedAt = firedAt;
        sendJsonWithRetry(envelope, 3, 1);
    },

    // Application-level ack for a phone-originated `alarm_dismissed`
    // envelope. Sent by incomingHandler.js AFTER `onPeerEndedRing(alarmId)`
    // executes (i.e. after the ring page has been navigated away from /
    // PEER_END_KEY has been written). Lets the phone tell that the watch's
    // JS receiver actually processed the dismiss, not just that the
    // transport layer ACK'd at 207. Without this loop the phone has no
    // way to detect a silently-dropped envelope and the user would have
    // to manually dismiss on the watch too. Retry path because losing
    // it means the phone falls back to time-out + "stale dismiss" UX.
    sendDismissedAck: function (message, cb) {
        sendJsonWithRetry({
            type: 'alarm_dismissed_ack',
            alarmId: message.alarmId,
            updatedAtEpoch: message.updatedAtEpoch,
            firedAt: message.firedAt,
            ok: true,
        }, 3, 1, cb);
    },

    // Symmetric to sendDismissedAck — for the snooze action.
    sendSnoozedAck: function (message, cb) {
        sendJsonWithRetry({
            type: 'alarm_snoozed_ack',
            alarmId: message.alarmId,
            updatedAtEpoch: message.updatedAtEpoch,
            firedAt: message.firedAt,
            ok: true,
        }, 3, 1, cb);
    },

    // Dev-only: relay a BATCH of watch-side log lines to the phone so they
    // surface in adb logcat (Lite Wearable HiLog is hard to read from
    // DevEco on Windows). `lines` is an array of { level, msg, ts }.
    // logger.js coalesces lines and calls this once per ~700 ms instead of
    // once per line — one P2P send per line was a channel-pressure
    // confound. Fire-and-forget; its send outcome is never logged through
    // Logger (that would loop, see sendOnceWithResult).
    sendLogBatch: function (lines) {
        sendJson({
            type: 'watch_log_batch',
            lines: lines,
            ts: Date.now(),
        });
    },
};
