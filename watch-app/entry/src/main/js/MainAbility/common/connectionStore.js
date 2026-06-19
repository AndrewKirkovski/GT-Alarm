// Tracks whether the watch has ever connected to the phone companion, and
// which companion version it last saw. Drives the onboarding screen: a
// never-connected watch shows a QR to install the companion (AppGallery);
// a connected-but-empty watch shows the normal "add alarms on the phone"
// hint.
//
// Backed by @system.storage — three tiny values, each far under the 128 B
// per-value cap (memory:litewearable_storage_limits):
//   gt_connected_once        '1' once any inbound envelope has arrived
//   gt_connected_first_epoch  epoch ms of the first connection (string)
//   gt_phone_app_version      companion versionName, e.g. '1.0.0'
//
// Forward-compatible: the phone does NOT send its version today, so the
// first connection records LEGACY_VERSION ('1.0.0'). A future phone build
// adds a `phoneAppVersion` envelope field; recordConnection() then upgrades
// the stored value whenever a real version arrives. ES5 only — plain var +
// function + callbacks (Lite Wearable / ACELite).
import storage from '@system.storage';
import Logger from './logger.js';

var KEY_ONCE = 'gt_connected_once';
var KEY_FIRST_EPOCH = 'gt_connected_first_epoch';
var KEY_PHONE_VERSION = 'gt_phone_app_version';
var ONCE_VALUE = '1';
// Assumed companion version when the inbound envelope carries no
// phoneAppVersion field (every shipped phone build to date).
var LEGACY_VERSION = '1.0.0';

var cache = {
    loaded: false,
    connectedOnce: false,
    firstEpoch: 0,
    phoneVersion: '',
};

function parseEpoch(s) {
    var n = parseInt(s, 10);
    return (isFinite(n) && n > 0) ? n : 0;
}

function finishLoad(once, ver, ep, cb) {
    cache.loaded = true;
    cache.connectedOnce = (once === ONCE_VALUE);
    cache.phoneVersion = (typeof ver === 'string') ? ver : '';
    cache.firstEpoch = parseEpoch(ep);
    cb({
        connectedOnce: cache.connectedOnce,
        firstEpoch: cache.firstEpoch,
        phoneVersion: cache.phoneVersion,
    });
}

// Run an array of `function(next){...}` tasks in sequence, then `done()`.
// Keeps the @system.storage writes ordered (callback-only API, no Promise).
function runSeq(tasks, done) {
    var i = 0;
    function step() {
        if (i >= tasks.length) {
            done();
            return;
        }
        var t = tasks[i];
        i = i + 1;
        t(step);
    }
    step();
}

export default {
    LEGACY_VERSION: LEGACY_VERSION,

    // cb({connectedOnce, firstEpoch, phoneVersion}). Cached after first read.
    get: function (cb) {
        if (cache.loaded) {
            cb({
                connectedOnce: cache.connectedOnce,
                firstEpoch: cache.firstEpoch,
                phoneVersion: cache.phoneVersion,
            });
            return;
        }
        storage.get({
            key: KEY_ONCE,
            default: '',
            success: function (once) {
                storage.get({
                    key: KEY_PHONE_VERSION,
                    default: '',
                    success: function (ver) {
                        storage.get({
                            key: KEY_FIRST_EPOCH,
                            default: '0',
                            success: function (ep) { finishLoad(once, ver, ep, cb); },
                            fail: function () { finishLoad(once, ver, '0', cb); },
                        });
                    },
                    fail: function () { finishLoad(once, '', '0', cb); },
                });
            },
            fail: function () { finishLoad('', '', '0', cb); },
        });
    },

    // Record an inbound connection. `phoneVersion` is the version from the
    // envelope, or null/undefined when absent (→ LEGACY_VERSION). Idempotent:
    // gt_connected_once + first-epoch are written only once; the version is
    // updated whenever a non-empty value arrives. cb(ok) optional.
    recordConnection: function (phoneVersion, cb) {
        // Ensure the cache is populated first so we never clobber the
        // existing first-epoch / connected flag.
        this.get(function (s) {
            var ver = (typeof phoneVersion === 'string' && phoneVersion.length > 0)
                ? phoneVersion : LEGACY_VERSION;
            var tasks = [];
            if (!s.connectedOnce && !cache.connectedOnce) {
                // Latch the cache OPTIMISTICALLY before the async writes so a
                // concurrent recordConnection (e.g. two version-bearing
                // envelopes in a burst, once the phone starts sending a
                // version) sees connectedOnce=true and skips the once/epoch
                // writes — keeping first-epoch write-once. If the set later
                // fails, the next launch re-reads storage (false) and re-records.
                cache.connectedOnce = true;
                tasks.push(function (next) {
                    storage.set({
                        key: KEY_ONCE,
                        value: ONCE_VALUE,
                        success: function () { next(); },
                        fail: function () { cache.connectedOnce = false; next(); },
                    });
                });
                tasks.push(function (next) {
                    var ep = '' + Date.now();
                    storage.set({
                        key: KEY_FIRST_EPOCH,
                        value: ep,
                        success: function () { cache.firstEpoch = parseEpoch(ep); next(); },
                        fail: function () { next(); },
                    });
                });
            }
            if (ver !== cache.phoneVersion) {
                tasks.push(function (next) {
                    storage.set({
                        key: KEY_PHONE_VERSION,
                        value: ver,
                        success: function () { cache.phoneVersion = ver; next(); },
                        fail: function () { next(); },
                    });
                });
            }
            runSeq(tasks, function () {
                Logger.i('connection.record once=' + cache.connectedOnce +
                    ' ver=' + cache.phoneVersion);
                if (cb) cb(true);
            });
        });
    },
};
