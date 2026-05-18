// Lite Wearable runtime is interpreted, not compiled — keep this tiny
// and avoid any modern syntax. console.{info,warn,error} are the only
// safe sinks on-device.
//
// Optional remote sink: set via setRemoteSink(fn). Mirrors log lines
// over P2P to the phone (so the dev can grep adb logcat for watch-side
// flow without HiLog access).
//
// The relay is BATCHED. Each Logger call enqueues a line; the queue is
// flushed as ONE envelope per FLUSH_INTERVAL_MS, or immediately once it
// reaches FLUSH_MAX_LINES. The old design did one P2P send per log line
// — that added real watch->phone channel pressure and confounded the
// 206 receive-failure diagnosis. HiLog per-line console.* output is
// unchanged; only the P2P relay is batched.
//
// setTimeout is foreground-only on Lite Wearable, so a hidden page would
// lose its queued lines. flushNow() is exposed for page onHide /
// app.onDestroy to drain the queue before the JS engine is suspended.
var remoteSink = null;
var queue = [];
var flushTimer = null;
var FLUSH_INTERVAL_MS = 700;
var FLUSH_MAX_LINES = 8;

function doFlush() {
    flushTimer = null;
    if (remoteSink === null || queue.length === 0) return;
    var batch = queue;
    queue = [];
    try {
        // The sink (wearBridge.sendLogBatch) uses console.* directly,
        // never Logger.* — so this does not re-enter enqueue in a loop.
        remoteSink(batch);
    } catch (e) {
        // Swallow — never let the relay break the original log.
    }
}

function scheduleFlush() {
    if (flushTimer !== null) return;
    flushTimer = setTimeout(doFlush, FLUSH_INTERVAL_MS);
}

function enqueue(level, msg) {
    if (remoteSink === null) return;
    queue.push({ level: level, msg: msg, ts: Date.now() });
    if (queue.length >= FLUSH_MAX_LINES) {
        if (flushTimer !== null) {
            clearTimeout(flushTimer);
            flushTimer = null;
        }
        doFlush();
    } else {
        scheduleFlush();
    }
}

export default {
    setRemoteSink: function (fn) {
        remoteSink = fn;
    },
    // Drain the queue immediately. Called from page onHide / app.onDestroy
    // because the FLUSH_INTERVAL_MS timer is foreground-only and a hidden
    // page would otherwise lose its queued lines.
    flushNow: function () {
        if (flushTimer !== null) {
            clearTimeout(flushTimer);
            flushTimer = null;
        }
        doFlush();
    },
    i: function (msg) {
        console.info('GT-Alarm: ' + msg);
        enqueue('I', msg);
    },
    w: function (msg) {
        console.warn('GT-Alarm: ' + msg);
        enqueue('W', msg);
    },
    err: function (msg, e) {
        var detail = '';
        if (e) {
            if (e.message) detail = e.message;
            else detail = '' + e;
        }
        var full = msg + (detail ? ' ' + detail : '');
        console.error('GT-Alarm: ' + full);
        enqueue('E', full);
    },
};
