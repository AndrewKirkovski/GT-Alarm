// Lite Wearable runtime is interpreted, not compiled — keep this tiny
// and avoid any modern syntax. console.{info,warn,error} are the only
// safe sinks on-device.
//
// Optional remote sink: set via setRemoteSink(fn). Used to mirror each
// log line over P2P to the phone (so the dev can grep adb logcat for
// watch-side flow without HiLog access). Guarded against re-entry so the
// bridge's own send-result logs don't loop back through us.
var remoteSink = null;
var inSink = false;

function emit(level, msg) {
    if (remoteSink !== null && !inSink) {
        inSink = true;
        try {
            remoteSink(level, msg);
        } catch (e) {
            // Swallow — never let the relay break the original log.
        }
        inSink = false;
    }
}

export default {
    setRemoteSink: function (fn) {
        remoteSink = fn;
    },
    i: function (msg) {
        console.info('GT-Alarm: ' + msg);
        emit('I', msg);
    },
    w: function (msg) {
        console.warn('GT-Alarm: ' + msg);
        emit('W', msg);
    },
    err: function (msg, e) {
        var detail = '';
        if (e) {
            if (e.message) detail = e.message;
            else detail = '' + e;
        }
        var full = msg + (detail ? ' ' + detail : '');
        console.error('GT-Alarm: ' + full);
        emit('E', full);
    },
};
