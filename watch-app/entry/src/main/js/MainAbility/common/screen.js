// Screen metrics for responsive layout. `@system.device.getInfo()` is
// available on Lite Wearable and returns windowWidth/windowHeight/screenShape
// (circle | rect). It is async + callback-only, so we cache the first result
// and expose it synchronously; callers render with the GT 6 Pro fallback
// (466x466 round) until the callback lands (identical on the GT6, so no
// visible reflow there). Part C reuses the cached metrics for the
// `watch_screen` report sent to the phone.
//
// IN-APP LAYOUT TEST SEAM (dev-only): before querying the device we read an
// OPTIONAL @system.storage key `dev_force_screen`. If it holds "WxH:shape"
// (e.g. "408x480:rect"), we render AS IF the watch had that screen — so a
// single physical GT6 (466 round) can exercise every supported profile
// (FIT 408x480, FIT2 336x480, …) and we can eyeball reflow without owning the
// hardware. The key is NEVER set in production (the phone doesn't write it),
// so release behaviour is unchanged. Set it from DevEco:
//   hdc shell  →  the watch has no shell; instead push a one-line @system.file
//   OR toggle it from the phone dev menu (writes the shared storage key).
// Cleared by setting `dev_force_screen` to "" (empty).
import device from '@system.device';
import storage from '@system.storage';
import Logger from './logger.js';

var cached = null;
var OVERRIDE_KEY = 'dev_force_screen';

function fallback() {
    return { W: 466, H: 466, shape: 'circle' };
}

// Parse "WxH:shape" → metrics, or null if malformed. Defensive: the value is
// dev-authored, so reject anything that isn't two positive ints + circle/rect.
function parseOverride(raw) {
    if (!raw) return null;
    var colon = ('' + raw).indexOf(':');
    if (colon < 0) return null;
    var dims = ('' + raw).substring(0, colon).split('x');
    var shape = ('' + raw).substring(colon + 1);
    if (dims.length !== 2) return null;
    var w = parseInt(dims[0], 10);
    var h = parseInt(dims[1], 10);
    if (!(w > 0) || !(h > 0)) return null;
    if (shape !== 'circle' && shape !== 'rect') return null;
    return { W: w, H: h, shape: shape };
}

// Real device query (the production path). `cb(metrics)` fires on success OR
// on fallback.
function queryDevice(cb) {
    try {
        device.getInfo({
            success: function (info) {
                var w = (info && (info.windowWidth || info.screenWidth)) || 466;
                var h = (info && (info.windowHeight || info.screenHeight)) || 466;
                var shape = (info && info.screenShape) || 'circle';
                cached = { W: w, H: h, shape: shape };
                Logger.i('screen ' + w + 'x' + h + ' ' + shape);
                if (cb) cb(cached);
            },
            fail: function (data, code) {
                Logger.w('screen.getInfo fail code=' + code + ' — fallback 466 round');
                cached = fallback();
                if (cb) cb(cached);
            },
        });
    } catch (e) {
        Logger.err('screen.getInfo threw', e);
        cached = fallback();
        if (cb) cb(cached);
    }
}

// Query the device once and cache it. `cb(metrics)` fires on success OR on
// fallback. Safe to call repeatedly (re-queries; cheap). Checks the dev
// override first; on any miss/error falls through to the real device.
function load(cb) {
    try {
        storage.get({
            key: OVERRIDE_KEY,
            default: '',
            success: function (value) {
                var forced = parseOverride(value);
                if (forced) {
                    cached = forced;
                    Logger.w('screen FORCED ' + forced.W + 'x' + forced.H + ' ' + forced.shape + ' (dev_force_screen)');
                    if (cb) cb(cached);
                } else {
                    queryDevice(cb);
                }
            },
            fail: function () { queryDevice(cb); },
        });
    } catch (e) {
        queryDevice(cb);
    }
}

export default {
    // Best-effort synchronous read: cached metrics or the GT6 fallback.
    get: function () {
        return cached || fallback();
    },
    load: load,
};
