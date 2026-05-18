// Shown when the phone wakes the watch app via Wear Engine auto-launch
// to push a sync burst. app.js's bootstrap receiver routes here on the
// first state-mutation envelope; from then on THIS page owns the
// process lifetime — it registers its own receiver (Fix 1, per-page
// registration), shows a live line of arriving envelopes, and
// terminates on either an explicit `sync_done` envelope (forceSync) or
// a drain-quiescence timeout (lone settings_changed / alarm_deleted
// wakes that carry no sync_done).
import app from '@system.app';
import router from '@system.router';
import storage from '@system.storage';
import WearBridge from '../../common/wearBridge.js';
import IncomingHandler from '../../common/incomingHandler.js';
import Logger from '../../common/logger.js';

// Terminate this long after the last envelope if no `sync_done` arrives.
// Re-armed on every inbound envelope, so a burst keeps the page alive.
var SYNC_DRAIN_MS = 3000;

function terminateNow(tag) {
    Logger.i('syncing.terminate ' + tag);
    try {
        app.terminate();
    } catch (e) {
        Logger.err('syncing.app.terminate threw', e);
    }
}

export default {
    data: {
        title: '',
        subtitle: '',
        // Live line — updates as each envelope arrives so a stuck or
        // partial sync is visible instead of a frozen "Syncing…".
        liveLine: '',
        // Diagnostic strip (debug build).
        diagLine1: 'rx:--',
        diagLine2: '',
        _eventCount: 0,
        _diagTimer: null,
        _drainTimer: null,
    },

    onInit: function () {
        Logger.i('syncing.onInit');
        this.title = this.$t('strings.syncing_title');
        this.subtitle = this.$t('strings.syncing_subtitle');
    },

    onShow: function () {
        Logger.i('syncing.onShow');
        var self = this;
        // Per-page receiver registration (Fix 1).
        WearBridge.setPageTag('syncing');
        IncomingHandler.setOnAlarmFiredNavigator(function (alarmId) {
            // An alarm can fire mid-sync — hand off to the ring page.
            try {
                router.replace({ uri: 'pages/ring/ring', params: { alarmId: alarmId } });
            } catch (e) {
                Logger.err('syncing.onAlarmFired router.replace', e);
            }
        });
        IncomingHandler.setOnPeerEndedRing(function (alarmId) {
            try {
                router.replace({ uri: 'pages/index/index' });
            } catch (e) {
                Logger.err('syncing.onPeerEndedRing router.replace', e);
            }
        });
        // The syncing page exists only because a wake-by-sync routed
        // here — so `sync_done` means terminate immediately.
        IncomingHandler.setOnSyncDone(function () {
            self.clearTimers();
            terminateNow('sync_done');
        });
        WearBridge.setIncomingHandler(function (msg) {
            try {
                if (msg && msg.type) {
                    self._eventCount = self._eventCount + 1;
                    self.liveLine = 'rx #' + self._eventCount + ': ' + msg.type;
                    // Any envelope re-arms the drain; sync_done's handler
                    // (above) terminates faster on the forceSync path.
                    self.armDrain();
                }
                IncomingHandler.handle(msg);
            } catch (e) {
                Logger.err('syncing.incomingHandler', e);
            }
        });
        this.armDrain();
        this.refreshDiag();
        if (this._diagTimer === null) {
            this._diagTimer = setInterval(function () {
                self.refreshDiag();
            }, 1000);
        }
    },

    onHide: function () {
        Logger.i('syncing.onHide');
        this.clearTimers();
        // Drain the batched log relay — its flush timer is foreground-only.
        Logger.flushNow();
    },

    // Arm / re-arm the quiescence terminate. A burst of envelopes pushes
    // the deadline out to last-envelope + SYNC_DRAIN_MS.
    armDrain: function () {
        var self = this;
        if (this._drainTimer !== null) clearTimeout(this._drainTimer);
        this._drainTimer = setTimeout(function () {
            self._drainTimer = null;
            terminateNow('drain-quiesced');
        }, SYNC_DRAIN_MS);
    },

    clearTimers: function () {
        if (this._diagTimer !== null) {
            clearInterval(this._diagTimer);
            this._diagTimer = null;
        }
        if (this._drainTimer !== null) {
            clearTimeout(this._drainTimer);
            this._drainTimer = null;
        }
    },

    refreshDiag: function () {
        var self = this;
        var rx = WearBridge.getReceiverState();
        var rxTxt = (rx.result === 'ok')
            ? ('ok@' + Math.floor((Date.now() - rx.atMs) / 1000) + 's')
            : rx.result;
        var snd = WearBridge.getLastSend();
        self.diagLine2 = 'tx:' + (snd.type ? (snd.type + '=' + snd.reason) : 'none') +
            ' pg:syncing';
        storage.get({
            key: 'diag_rawrx',
            default: '{"count":0}',
            success: function (rawData) {
                var raw;
                try {
                    raw = JSON.parse(rawData);
                } catch (e) {
                    raw = { count: 0 };
                }
                self.applyDiag(rxTxt, raw.count || 0);
            },
            fail: function () {
                self.applyDiag(rxTxt, 0);
            },
        });
    },

    applyDiag: function (rxTxt, rawCount) {
        var self = this;
        storage.get({
            key: 'diag_inbound',
            default: '{"total":0}',
            success: function (data) {
                var obj;
                try {
                    obj = JSON.parse(data);
                } catch (e) {
                    obj = null;
                }
                if (!obj) {
                    self.diagLine1 = 'rx:' + rxTxt + ' diag:parse-fail';
                    return;
                }
                var last = obj.lastType ? obj.lastType : '-';
                var age = obj.lastTs
                    ? (Math.floor((Date.now() - obj.lastTs) / 1000) + 's')
                    : '-';
                self.diagLine1 = 'rx:' + rxTxt + ' raw:' + rawCount +
                    ' in:' + (obj.total || 0) + ' last:' + last + '@' + age;
            },
            fail: function () {
                self.diagLine1 = 'rx:' + rxTxt + ' diag-fail';
            },
        });
    },
};
