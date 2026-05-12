// Canonical hash of the local alarm set. Mirrors `AlarmHash.kt` on the
// phone byte-for-byte. The phone polls the watch with `sync_check`; we
// reply with `sync_hash` carrying this 8-char hex. If the phone's
// computed hash matches, it skips the full alarm-by-alarm push.
//
// Algorithm:
//   1. Sort alarms by id ascending
//   2. Render each as a pipe-delimited line:
//        id|label|hour|minute|daysOfWeek|enabled|audioUri|
//        isVibrationOnly|snoozeMinutes|updatedAtEpoch|
//        relativeMinutes|selfDestruct
//      - booleans: '1' / '0'
//      - null audioUri / undefined relativeMinutes: empty string
//   3. Join with '\n' (newline after EVERY line, including last)
//   4. hash = Java String.hashCode → unsigned → 8-char lowercase hex
//
// Java String.hashCode contract: `h = h * 31 + char` iterated over UTF-16
// code units, with overflow wrapping inside 32-bit signed int. JS
// expresses that with `((h << 5) - h + c) | 0` — `h * 31 == (h << 5) - h`
// and `| 0` collapses to 32-bit signed semantics on every step.
//
// Any divergence from the Kotlin canonicalize() function makes hash
// compare always-fail and the optimization becomes dead overhead. If
// either side changes the format, update BOTH in the same commit.

function boolFlag(v) {
    return v ? '1' : '0';
}

function strOrEmpty(v) {
    if (v === null || v === undefined) return '';
    return '' + v;
}

function sortById(alarms) {
    var sorted = alarms.slice();
    sorted.sort(function (a, b) {
        if (a.id < b.id) return -1;
        if (a.id > b.id) return 1;
        return 0;
    });
    return sorted;
}

function canonicalize(alarms) {
    var sorted = sortById(alarms);
    var out = '';
    for (var i = 0; i < sorted.length; i++) {
        var a = sorted[i];
        out += a.id + '|' +
            strOrEmpty(a.label) + '|' +
            a.hour + '|' +
            a.minute + '|' +
            (a.daysOfWeek || 0) + '|' +
            boolFlag(a.enabled) + '|' +
            strOrEmpty(a.audioUri) + '|' +
            boolFlag(a.isVibrationOnly) + '|' +
            (a.snoozeMinutes || 0) + '|' +
            (a.updatedAtEpoch || 0) + '|' +
            strOrEmpty(a.relativeMinutes) + '|' +
            boolFlag(a.selfDestruct) +
            '\n';
    }
    return out;
}

function javaHashCode(s) {
    var h = 0;
    for (var i = 0; i < s.length; i++) {
        h = ((h << 5) - h + s.charCodeAt(i)) | 0;
    }
    return h;
}

function toUnsignedHex8(h32) {
    // Convert 32-bit signed to unsigned, then to lowercase hex padded to 8.
    var u = h32 >>> 0;
    var hex = u.toString(16);
    while (hex.length < 8) hex = '0' + hex;
    return hex;
}

export default {
    compute: function (alarms) {
        var canonical = canonicalize(alarms || []);
        return toUnsignedHex8(javaHashCode(canonical));
    },
};
