// First-launch privacy-consent flag for the watch (AppGallery review rule
// 7.5: the app must surface the privacy policy on first launch). Backed by
// @system.storage — a single tiny boolean (~4 B, far under the 128 B
// per-value cap pinned in memory:litewearable_storage_limits).
//
// The watch consent screen mirrors the phone's PrivacyConsentDialog: the
// user must Agree to continue or Decline to exit. Once agreed, the flag
// persists across relaunches so the gate never re-shows. ES5 only — plain
// var + function + callbacks (Lite Wearable / ACELite).
import storage from '@system.storage';
import Logger from './logger.js';

var KEY_AGREED = 'gt_privacy_agreed';
var AGREED_VALUE = 'true';

// In-process cache; `loaded` stays false until the first get().
var cache = { loaded: false, agreed: false };

export default {
    // cb(agreed:boolean). Cached after the first read so the 2 s refresh
    // poll costs zero storage round-trips once resolved.
    get: function (cb) {
        if (cache.loaded) {
            cb(cache.agreed);
            return;
        }
        storage.get({
            key: KEY_AGREED,
            default: '',
            success: function (v) {
                cache.loaded = true;
                cache.agreed = (v === AGREED_VALUE);
                cb(cache.agreed);
            },
            fail: function () {
                // Read failure → "not agreed". Fail safe: show the consent
                // gate rather than silently skip it.
                cache.loaded = true;
                cache.agreed = false;
                cb(false);
            },
        });
    },

    // Persist consent. cb(ok:boolean) optional.
    setAgreed: function (cb) {
        storage.set({
            key: KEY_AGREED,
            value: AGREED_VALUE,
            success: function () {
                cache.loaded = true;
                cache.agreed = true;
                Logger.i('privacy.setAgreed ok');
                if (cb) cb(true);
            },
            fail: function (data, code) {
                Logger.w('privacy.setAgreed fail code=' + code);
                if (cb) cb(false);
            },
        });
    },
};
