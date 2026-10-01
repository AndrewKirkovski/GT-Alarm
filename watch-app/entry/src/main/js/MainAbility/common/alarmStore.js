// Persistent alarm list backed by @system.file. The alarm-list JSON
// regularly exceeds @system.storage's hard 128-byte per-value cap (verified
// 2026-05-12 with the bisect probe), so we store the full list as a single
// JSON file at internal://app/alarms.json. The file API has no equivalent
// per-value cap on Lite Wearable.
//
// onChange: a single optional listener fired after every successful
// mutation. The receive-side IncomingHandler runs in the same process as
// any active page, so this lets the page refresh on peer-driven changes the
// same way as user-driven ones.
//
// Trimmed 2026-10-01 for page-bundle size: the pre-MVP `gt_alarms` migration
// (every published build already uses the file), the write-only last-sync
// epoch, the uncalled deleteById/clear, and the per-read log lines (each one
// was relayed to the phone over P2P on every 2 s poll). Errors still log.
import file from '@system.file';
import Logger from './logger.js';

var ALARMS_URI = 'internal://app/alarms.json';
// IMPORTANT: do NOT cache reads across calls. Lite Wearable bundles each
// page/script as a separate webpack output (per gotcha #11 — module state
// isolated per bundle), so the index page and IncomingHandler each have
// their OWN cache even though both `import` the same alarmStore. If we
// cached on read, IncomingHandler writing to the file would leave the index
// page reading stale cached items — exactly the "alarms appear only after
// relaunch" bug observed 2026-05-12. Always read fresh from file.
var onChangeListener = null;

function fireChange() {
    if (onChangeListener) {
        try {
            onChangeListener();
        } catch (e) {
            Logger.err('alarmStore.onChange listener', e);
        }
    }
}

function isValidAlarm(a) {
    return (
        a &&
        typeof a.id === 'number' &&
        isFinite(a.id) &&
        a.id > 0 &&
        typeof a.hour === 'number' &&
        typeof a.minute === 'number'
    );
}

function readRaw(callback) {
    file.readText({
        uri: ALARMS_URI,
        success: function (data) {
            var text = (data && typeof data.text === 'string') ? data.text : '[]';
            var parsed = null;
            try {
                parsed = JSON.parse(text);
            } catch (e) {
                Logger.err('store.read parse', e);
                callback([]);
                return;
            }
            callback((parsed && parsed.length !== undefined) ? parsed : []);
        },
        // File doesn't exist yet (fresh install) — an empty list.
        fail: function () {
            callback([]);
        },
    });
}

function writeFile(items, callback) {
    var serialized = JSON.stringify(items);
    file.writeText({
        uri: ALARMS_URI,
        text: serialized,
        success: function () {
            if (callback) callback(true);
        },
        fail: function (data, code) {
            Logger.err('store.write fail code=' + code + ' bytes=' + serialized.length, data);
            // The file API has been hardware-verified working at 2 KB, so this
            // should not fire in practice. Still callback(true) so the caller's
            // flow continues; the file just keeps its prior state.
            if (callback) callback(true);
        },
    });
}

function writeRaw(items, callback, silent) {
    writeFile(items, function (ok) {
        if (!silent) fireChange();
        if (callback) callback(ok);
    });
}

export default {
    setOnChange: function (cb) {
        onChangeListener = cb;
    },
    getAll: function (callback) {
        readRaw(function (items) {
            var out = [];
            for (var i = 0; i < items.length; i++) {
                if (isValidAlarm(items[i])) {
                    out.push(items[i]);
                }
            }
            callback(out);
        });
    },
    // `silent` (optional, default false): when true, the onChange listener
    // is NOT fired after a successful write. Used by user-driven mutations
    // where the page already re-rendered optimistically.
    add: function (alarm, callback, silent) {
        readRaw(function (items) {
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === alarm.id) {
                    if (callback) callback(false);
                    return;
                }
            }
            items.push(alarm);
            writeRaw(items, callback, silent);
        });
    },
    // Calls back false when no row has this id (nothing written).
    update: function (alarm, callback, silent) {
        readRaw(function (items) {
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === alarm.id) {
                    items[i] = alarm;
                    writeRaw(items, callback, silent);
                    return;
                }
            }
            if (callback) callback(false);
        });
    },
    // Authoritative wholesale replace — drops the entire current list and
    // writes `items` instead. Used by the sync_replace handler so a Force
    // sync prunes watch-only rows the additive add path can't remove.
    // `.slice()` so the caller's array isn't aliased into store internals.
    replaceAll: function (items, callback, silent) {
        writeRaw(items.slice(), callback, silent);
    },
};
