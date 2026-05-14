// Persistent display preferences synced from the phone. Backed by
// @system.storage because each value is tiny (≤ 16 bytes — well under
// the 128 B per-value cap pinned in memory:litewearable_storage_limits).
//
// Wire contract (settings_changed envelope from phone):
//   { type: 'settings_changed', use24Hour: bool|null, firstDayOfWeek: int|null,
//     updatedAtEpoch }
//
// `null` on either field means "follow watch system locale". Stored as the
// string 'null' in @system.storage; getters translate back to JS null so
// callers can switch on (value === null) vs an explicit Boolean / Int.
//
// Watch never edits these — phone is the only place the user can change
// 12/24h or first-day-of-week (see Android Settings screen). This module
// is write-from-receive-only + read-from-page-only.
import storage from '@system.storage';
import Logger from './logger.js';

var KEY_USE_24H = 'gt_use_24h';
var KEY_FIRST_DOW = 'gt_first_dow';
var NULL_SENTINEL = 'null';

// In-process cache to avoid one storage.get per refresh tick (index page
// polls every 2 s — a cache hit shaves two callbacks per cycle). Reset
// on every applySettings write so a fresh phone push is immediately
// visible.
var cache = { loaded: false, use24Hour: null, firstDayOfWeek: null };

var onChangeListener = null;

function loadInto(callback) {
    storage.get({
        key: KEY_USE_24H,
        default: NULL_SENTINEL,
        success: function (rawHour) {
            var u = parseBoolOrNull(rawHour);
            storage.get({
                key: KEY_FIRST_DOW,
                default: NULL_SENTINEL,
                success: function (rawDow) {
                    var d = parseIntOrNull(rawDow);
                    cache.loaded = true;
                    cache.use24Hour = u;
                    cache.firstDayOfWeek = d;
                    callback({ use24Hour: u, firstDayOfWeek: d });
                },
                fail: function () {
                    cache.loaded = true;
                    cache.use24Hour = u;
                    cache.firstDayOfWeek = null;
                    callback({ use24Hour: u, firstDayOfWeek: null });
                },
            });
        },
        fail: function () {
            cache.loaded = true;
            cache.use24Hour = null;
            cache.firstDayOfWeek = null;
            callback({ use24Hour: null, firstDayOfWeek: null });
        },
    });
}

function parseBoolOrNull(s) {
    if (s === 'true') return true;
    if (s === 'false') return false;
    return null;
}

function parseIntOrNull(s) {
    if (typeof s !== 'string' || s === NULL_SENTINEL) return null;
    var n = parseInt(s, 10);
    if (!isFinite(n)) return null;
    return n;
}

function serializeBool(v) {
    if (v === true) return 'true';
    if (v === false) return 'false';
    return NULL_SENTINEL;
}

function serializeInt(v) {
    if (typeof v !== 'number' || !isFinite(v)) return NULL_SENTINEL;
    return '' + Math.floor(v);
}

function fireChange() {
    if (onChangeListener) {
        try {
            onChangeListener();
        } catch (e) {
            Logger.err('settingsStore.onChange listener', e);
        }
    }
}

export default {
    // Page callers (index/ring) bind on the cached values via this hook.
    // Cache is populated on first get(); subsequent gets are sync via
    // the in-memory cache. Polling pages should call get() once per
    // refresh — the call is cheap (zero storage round-trips after the
    // first one).
    get: function (callback) {
        if (cache.loaded) {
            callback({
                use24Hour: cache.use24Hour,
                firstDayOfWeek: cache.firstDayOfWeek,
            });
            return;
        }
        loadInto(callback);
    },

    // Synchronous accessor — returns the in-process cache without
    // hitting storage. Callers MUST have called get() at least once
    // beforehand or the values are the default `null`s (matching
    // "follow watch locale" behaviour, so still correct).
    snapshot: function () {
        return {
            use24Hour: cache.use24Hour,
            firstDayOfWeek: cache.firstDayOfWeek,
        };
    },

    // Apply an inbound settings_changed envelope. NOT LWW — overwrites
    // unconditionally because phone is the sole authoritative editor.
    // Writes are sequenced so the firstDayOfWeek slot lands AFTER the
    // use24Hour slot — that keeps the in-memory cache consistent if a
    // page reads between the two writes.
    apply: function (use24Hour, firstDayOfWeek, callback) {
        var hourSerialized = serializeBool(use24Hour);
        var dowSerialized = serializeInt(firstDayOfWeek);
        storage.set({
            key: KEY_USE_24H,
            value: hourSerialized,
            success: function () {
                cache.use24Hour = use24Hour;
                storage.set({
                    key: KEY_FIRST_DOW,
                    value: dowSerialized,
                    success: function () {
                        cache.firstDayOfWeek = firstDayOfWeek;
                        cache.loaded = true;
                        Logger.i(
                            'settings.apply use24Hour=' + hourSerialized +
                            ' firstDow=' + dowSerialized
                        );
                        fireChange();
                        if (callback) callback(true);
                    },
                    fail: function (data, code) {
                        Logger.w('settings.write firstDow fail code=' + code);
                        if (callback) callback(false);
                    },
                });
            },
            fail: function (data, code) {
                Logger.w('settings.write use24Hour fail code=' + code);
                if (callback) callback(false);
            },
        });
    },

    setOnChange: function (cb) {
        onChangeListener = cb;
    },
};
