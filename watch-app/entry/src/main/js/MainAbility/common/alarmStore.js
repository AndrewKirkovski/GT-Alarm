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
import Ticker from './ticker.js';

var ALARMS_URI = 'internal://app/alarms.json';
// IMPORTANT: do NOT cache reads across calls. Lite Wearable bundles each
// page/script as a separate webpack output (per gotcha #11 — module state
// isolated per bundle), so the index page and IncomingHandler each have
// their OWN cache even though both `import` the same alarmStore. If we
// cached on read, IncomingHandler writing to the file would leave the index
// page reading stale cached items — exactly the "alarms appear only after
// relaunch" bug observed 2026-05-12. Always read fresh from file.
var onChangeListener = null;
var mutationQueue = [];
var mutationBusy = false;
// A native write that never calls back must not stall the queue. The job is
// failed after this long (never acknowledged), then the queue stays held for
// the same time again so the late native write cannot land after a newer one.
var MUTATION_DEADLINE_MS = 10000;

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
            Logger.err('write fail c=' + code + ' bytes=' + serialized.length, data);
            if (callback) callback(false);
        },
    });
}

function runMutation() {
    if (mutationBusy || mutationQueue.length === 0) return;
    mutationBusy = true;
    var job = mutationQueue.shift();
    var finished = false;
    var held = false;
    var finish = function (ok, timedOut) {
        if (finished) {
            // The late callback of a timed-out job, or its hold deadline.
            if (held) {
                held = false;
                Ticker.clear('storeHold');
                mutationBusy = false;
                runMutation();
            }
            return;
        }
        finished = true;
        Ticker.clear('storeJob');
        if (timedOut) {
            held = true;
            Ticker.set('storeHold', MUTATION_DEADLINE_MS, function () { finish(); });
        } else {
            mutationBusy = false;
        }
        if (ok && !job.silent) fireChange();
        try {
            if (job.callback) job.callback(ok);
        } catch (e) {
            Logger.err('store.mutation callback', e);
        } finally {
            runMutation();
        }
    };
    Ticker.set('storeJob', MUTATION_DEADLINE_MS, function () { finish(false, true); });
    try {
        // The second argument tells a read continuation whether its job is
        // still live: after a timeout it must not dispatch a write.
        job.run(finish, function () { return !finished; });
    } catch (e) {
        Logger.err('store.mutation', e);
        finish(false);
    }
}

function enqueueMutation(run, callback, silent) {
    mutationQueue.push({ run: run, callback: callback, silent: silent });
    runMutation();
}

function readValid(callback) {
    readRaw(function (items) {
        var out = [];
        for (var i = 0; i < items.length; i++) {
            if (isValidAlarm(items[i])) {
                out.push(items[i]);
            }
        }
        callback(out);
    });
}

function writeCurrent(items, done) {
    writeFile(items, done);
}

export default {
    setOnChange: function (cb) {
        onChangeListener = cb;
    },
    getAll: readValid,
    // `silent` (optional, default false): when true, the onChange listener
    // is NOT fired after a successful write. Used by user-driven mutations
    // where the page already re-rendered optimistically.
    add: function (alarm, callback, silent) {
        enqueueMutation(function (done, live) {
            readRaw(function (items) {
                if (!live()) return;
                for (var i = 0; i < items.length; i++) {
                    if (items[i].id === alarm.id) {
                        done(false);
                        return;
                    }
                }
                items.push(alarm);
                writeCurrent(items, done);
            });
        }, callback, silent);
    },
    // Calls back false when no row has this id (nothing written).
    update: function (alarm, callback, silent) {
        enqueueMutation(function (done, live) {
            readRaw(function (items) {
                if (!live()) return;
                for (var i = 0; i < items.length; i++) {
                    if (items[i].id === alarm.id) {
                        items[i] = alarm;
                        writeCurrent(items, done);
                        return;
                    }
                }
                done(false);
            });
        }, callback, silent);
    },
    // Authoritative wholesale replace — drops the entire current list and
    // writes `items` instead. Used by the sync_replace handler so a Force
    // sync prunes watch-only rows the additive add path can't remove.
    // `.slice()` so the caller's array isn't aliased into store internals.
    // `verify` (optional): called with the list read back INSIDE the same queue
    // job, before any other write can run; false fails the job.
    replaceAll: function (items, callback, silent, verify) {
        enqueueMutation(function (done) {
            writeCurrent(items.slice(), function (ok) {
                if (!ok || !verify) {
                    done(ok);
                    return;
                }
                readValid(function (stored) {
                    done(verify(stored) !== false);
                });
            });
        }, callback, silent);
    },
};
