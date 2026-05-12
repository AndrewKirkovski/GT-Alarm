// Tombstone ring buffer for deleted alarm ids. Bounded at MAX entries;
// items older than RETENTION_MS are pruned on each write. Persistence
// via @system.storage with an in-memory fallback for the Lite Wearable
// simulator (which returns code 200 on every storage op).
import storage from '@system.storage';
import Logger from './logger.js';

var KEY = 'gt_tombstones';
var MAX = 256;
var RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
// Re-probe @system.storage every STORAGE_RETRY_MS ms after a failure so a
// transient glitch doesn't permanently sink us into memory-only mode (which
// would lose all tombstones across watch app restarts and let the phone
// resurrect deleted alarms).
var STORAGE_RETRY_MS = 60 * 1000;
var memoryCache = null;
var storageWorking = true;
var storageDownAt = 0;

function isValid(t) {
    return (
        t &&
        typeof t.id === 'number' &&
        isFinite(t.id) &&
        t.id > 0 &&
        typeof t.epoch === 'number' &&
        isFinite(t.epoch) &&
        t.epoch > 0
    );
}

function shouldRetryStorage() {
    if (storageWorking) return true;
    return (Date.now() - storageDownAt) >= STORAGE_RETRY_MS;
}

function markStorageHealthy() {
    if (!storageWorking) {
        Logger.i('tombstones.storage recovered');
    }
    storageWorking = true;
    storageDownAt = 0;
}

function markStorageDown() {
    storageWorking = false;
    storageDownAt = Date.now();
}

function readRaw(callback) {
    // If storage is in a known-bad state AND we're inside the retry
    // backoff window, serve the in-memory copy without hitting storage.
    // After the backoff elapses we'll try storage again so a transient
    // failure doesn't permanently demote us to memory-only mode.
    if (memoryCache !== null && !shouldRetryStorage()) {
        callback(memoryCache.slice());
        return;
    }
    storage.get({
        key: KEY,
        default: '[]',
        success: function (data) {
            markStorageHealthy();
            var parsed;
            try {
                parsed = JSON.parse(data);
            } catch (e) {
                Logger.err('tombstones.readRaw parse', e);
                callback([]);
                return;
            }
            if (parsed && parsed.length !== undefined) {
                var out = [];
                for (var i = 0; i < parsed.length; i++) {
                    if (isValid(parsed[i])) out.push(parsed[i]);
                }
                memoryCache = out.slice();
                callback(out);
                return;
            }
            memoryCache = [];
            callback([]);
        },
        fail: function (data, code) {
            Logger.err('tombstones.readRaw storage code=' + code, data);
            markStorageDown();
            if (memoryCache === null) memoryCache = [];
            callback(memoryCache.slice());
        },
    });
}

function writeRaw(items, callback) {
    memoryCache = items.slice();
    // Even when storage is in a known-bad state, attempt a write after the
    // retry backoff has elapsed — that's how we detect recovery.
    if (!shouldRetryStorage()) {
        if (callback) callback(true);
        return;
    }
    storage.set({
        key: KEY,
        value: JSON.stringify(items),
        success: function () {
            markStorageHealthy();
            if (callback) callback(true);
        },
        fail: function (data, code) {
            Logger.err('tombstones.writeRaw storage code=' + code, data);
            markStorageDown();
            if (callback) callback(true);
        },
    });
}

function pruneAndAppend(items, id, epoch) {
    var cutoff = Date.now() - RETENTION_MS;
    var pruned = [];
    var i;
    for (i = 0; i < items.length; i++) {
        var t = items[i];
        if (t.id === id) continue;
        if (t.epoch < cutoff) continue;
        pruned.push(t);
    }
    pruned.push({ id: id, epoch: epoch });
    if (pruned.length > MAX) {
        pruned.sort(function (a, b) { return a.epoch - b.epoch; });
        pruned = pruned.slice(pruned.length - MAX);
    }
    return pruned;
}

export default {
    add: function (id, epoch, callback) {
        readRaw(function (items) {
            var next = pruneAndAppend(items, id, epoch);
            writeRaw(next, callback);
        });
    },
    lookupEpoch: function (id, callback) {
        readRaw(function (items) {
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === id) {
                    callback(items[i].epoch);
                    return;
                }
            }
            callback(null);
        });
    },
    clear: function (callback) {
        writeRaw([], callback);
    },
};
