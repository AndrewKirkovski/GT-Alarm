// Persistent alarm list backed by @system.file. The alarm-list JSON
// regularly exceeds @system.storage's hard 128-byte per-value cap (verified
// 2026-05-12 with the bisect probe), so we store the full list as a single
// JSON file at internal://app/alarms.json. The file API has no equivalent
// per-value cap on Lite Wearable.
//
// `last_sync_epoch` stays in @system.storage — it's a 13-char number string
// (well under 128 B) and a single round-trip read on the index page is
// cheaper than the file API. Same for the small diagnostic keys.
//
// On first launch after upgrading, we migrate any pre-existing list out of
// the legacy `gt_alarms` storage key (where it lived before MVP), then
// delete the legacy key so we don't keep re-migrating empty data.
//
// onChange: a single optional listener fired after every successful
// mutation (add/update/delete/clear). The receive-side IncomingHandler
// runs in the same process as any active page, so this lets the page
// refresh on peer-driven changes the same way as user-driven ones.
import storage from '@system.storage';
import file from '@system.file';
import Logger from './logger.js';

var ALARMS_URI = 'internal://app/alarms.json';
var KEY_LAST_SYNC = 'gt_last_sync_epoch';
var KEY_LEGACY_ALARMS = 'gt_alarms';
// IMPORTANT: do NOT cache reads across calls. Lite Wearable bundles each
// page/script as a separate webpack output (per gotcha #11 — module state
// isolated per bundle), so the index page and IncomingHandler each have
// their OWN `memoryCache` even though both `import` the same alarmStore.
// If we cached on read, IncomingHandler writing to the file would leave
// the index page reading stale cached items — exactly the "alarms appear
// only after relaunch" bug observed 2026-05-12. Always read fresh from
// file; the perf cost is one file.readText per refresh, negligible.
var lastSyncMemoryCache = null;
var onChangeListener = null;
var legacyMigrationAttempted = false;

// Process-lifetime sequence counter for storage ops. Lets us pair the
// `enter` log with the matching `success`/`fail` log so we can tell from
// hilog whether a callback ever fired.
var storageSeq = 0;

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

// First-launch-after-upgrade migration. Reads the legacy gt_alarms key
// from @system.storage; if non-empty, parses it, writes to the new file
// path, then deletes the legacy key. Idempotent — once the legacy key is
// gone, this is a single read returning empty and we don't write the file.
//
// Guarded by `legacyMigrationAttempted` to avoid re-attempting within the
// same process (we only ever need to try it on the first read of the file
// that returns "not found").
function migrateLegacyOnce(callback) {
    if (legacyMigrationAttempted) {
        callback([]);
        return;
    }
    legacyMigrationAttempted = true;
    storage.get({
        key: KEY_LEGACY_ALARMS,
        default: '',
        success: function (data) {
            if (!data || data.length === 0 || data === '[]') {
                callback([]);
                return;
            }
            var items = null;
            try {
                items = JSON.parse(data);
            } catch (e) {
                Logger.err('legacy-migration parse', e);
                callback([]);
                return;
            }
            if (!items || items.length === undefined) {
                callback([]);
                return;
            }
            Logger.i('legacy-migration found=' + items.length +
                ' bytes=' + data.length + ' → writing file');
            writeFile(items, function () {
                // Best-effort cleanup of the legacy key. Failure here is
                // non-fatal: next launch the file exists and we never read
                // the legacy key again anyway.
                storage.delete({
                    key: KEY_LEGACY_ALARMS,
                    success: function () { Logger.i('legacy key deleted'); },
                    fail: function () { Logger.w('legacy key delete failed (non-fatal)'); },
                });
                callback(items);
            });
        },
        fail: function () {
            // Legacy key not present → fresh install OR already migrated.
            callback([]);
        },
    });
}

function readRaw(callback) {
    storageSeq++;
    var s = storageSeq;
    Logger.i('store.read#' + s + ' enter uri=' + ALARMS_URI);
    file.readText({
        uri: ALARMS_URI,
        success: function (data) {
            var text = (data && typeof data.text === 'string') ? data.text : '[]';
            var parsed = null;
            try {
                parsed = JSON.parse(text);
            } catch (e) {
                Logger.err('store.read#' + s + ' parse', e);
                callback([]);
                return;
            }
            if (parsed && parsed.length !== undefined) {
                Logger.i('store.read#' + s + ' ok len=' + parsed.length);
                callback(parsed);
                return;
            }
            callback([]);
        },
        fail: function (data, code) {
            // File doesn't exist yet — fresh install, fresh upgrade, or
            // post-clear. Try the one-shot legacy migration; if it yields
            // items the file is now seeded and next read will hit success.
            Logger.i('store.read#' + s + ' file-miss code=' + code +
                ' — trying legacy migration');
            migrateLegacyOnce(function (legacyItems) {
                callback(legacyItems.slice());
            });
        },
    });
}

function writeFile(items, callback) {
    storageSeq++;
    var s = storageSeq;
    var serialized = JSON.stringify(items);
    Logger.i('store.write#' + s + ' enter len=' + items.length +
        ' bytes=' + serialized.length);
    file.writeText({
        uri: ALARMS_URI,
        text: serialized,
        success: function () {
            Logger.i('store.write#' + s + ' ok bytes=' + serialized.length);
            if (callback) callback(true);
        },
        fail: function (data, code) {
            Logger.err('store.write#' + s + ' fail code=' + code +
                ' bytes=' + serialized.length, data);
            // The file API has been hardware-verified working at 2 KB
            // (probeFile in index.js) so this fail path should not fire
            // in practice. We still callback(true) so the caller's flow
            // continues; the file just won't have the latest write. Next
            // refresh will read the prior file state — acceptable degradation.
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
                    Logger.w('alarmStore.add duplicate id=' + alarm.id);
                    if (callback) callback(false);
                    return;
                }
            }
            items.push(alarm);
            writeRaw(items, callback, silent);
        });
    },
    update: function (alarm, callback, silent) {
        readRaw(function (items) {
            var found = false;
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === alarm.id) {
                    items[i] = alarm;
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (callback) callback(false);
                return;
            }
            writeRaw(items, callback, silent);
        });
    },
    deleteById: function (id, callback, silent) {
        readRaw(function (items) {
            var out = [];
            for (var i = 0; i < items.length; i++) {
                if (items[i].id !== id) out.push(items[i]);
            }
            writeRaw(out, callback, silent);
        });
    },
    clear: function (callback, silent) {
        writeRaw([], callback, silent);
    },
    // Authoritative wholesale replace — drops the entire current list and
    // writes `items` instead. Used by the sync_replace handler so a Force
    // sync prunes watch-only rows the additive add/update path can't
    // remove. `.slice()` so the caller's array isn't aliased into store
    // internals. fireChange notifies the visible page to re-render.
    replaceAll: function (items, callback, silent) {
        writeRaw(items.slice(), callback, silent);
    },

    // Last successful peer-driven mutation timestamp. Bumped by
    // IncomingHandler whenever a wire message is applied locally so the
    // "Last sync: 3 min ago" line on the index page tracks actual P2P
    // throughput. The value is a single-number ASCII string (≤13 chars),
    // well under @system.storage's 128 B cap — keeping it in storage saves
    // the file-API round-trip for a value the index page reads frequently.
    getLastSyncEpoch: function (callback) {
        if (lastSyncMemoryCache !== null) {
            callback(lastSyncMemoryCache);
            return;
        }
        storage.get({
            key: KEY_LAST_SYNC,
            default: '0',
            success: function (data) {
                var n = parseInt(data, 10);
                if (!isFinite(n) || n < 0) n = 0;
                lastSyncMemoryCache = n;
                callback(n);
            },
            fail: function (data, code) {
                // Key-not-found is normal on a fresh install — don't log
                // as an error. Treat as 0.
                Logger.i('lastSync.read miss code=' + code);
                lastSyncMemoryCache = 0;
                callback(0);
            },
        });
    },
    setLastSyncEpoch: function (epoch, callback) {
        if (typeof epoch !== 'number' || !isFinite(epoch) || epoch <= 0) {
            if (callback) callback(false);
            return;
        }
        lastSyncMemoryCache = epoch;
        storage.set({
            key: KEY_LAST_SYNC,
            value: '' + epoch,
            success: function () { if (callback) callback(true); },
            fail: function (data, code) {
                Logger.err('lastSync.write fail code=' + code, data);
                // Still callback(true) — mem cache is populated, page will
                // show fresh value this session. Next session it'll show 0
                // until the next inbound message bumps it again. Acceptable.
                if (callback) callback(true);
            },
        });
    },
};
