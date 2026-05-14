import router from '@system.router';
import storage from '@system.storage';
import vibrator from '@system.vibrator';
import AlarmStore from '../../common/alarmStore.js';
import SettingsStore from '../../common/settingsStore.js';
import WearBridge from '../../common/wearBridge.js';
import Logger from '../../common/logger.js';

var VIBRATE_INTERVAL_MS = 1500;
var DOW_NONE = 0;
// Re-attempt the alarm-row lookup once after this delay if it wasn't found
// on first try. The alarm_fired envelope and the alarm_added envelope come
// over the same P2P channel but can be processed out of order if the watch
// app cold-started just before the fire (registration race). One retry
// covers the gap without needing a full polling loop.
var ROW_RETRY_MS = 1500;

function pad2(n) {
    return (n < 10 ? '0' : '') + n;
}

/**
 * Honor the phone-pushed 12/24h pref. Mirrors index.js's formatHHMM —
 * kept duplicated because importing across pages on Lite Wearable is
 * unreliable (per-bundle module isolation, gotcha #11). When changing
 * this, update index.js's copy in lock-step.
 */
function formatHHMM(hour, minute, use24Hour, ampmAM, ampmPM) {
    if (use24Hour === false) {
        var h = hour % 12;
        if (h === 0) h = 12;
        var suffix = hour < 12 ? ampmAM : ampmPM;
        return h + ':' + pad2(minute) + ' ' + suffix;
    }
    return pad2(hour) + ':' + pad2(minute);
}

/**
 * Compute fire-time as "HH:MM" (or "h:MM AM/PM") for the ring screen.
 * Relative alarms carry placeholder hour/minute fields (default 07:00
 * from the phone's picker UI) plus updatedAtEpoch + relativeMinutes;
 * honouring the placeholders would render every Timer as 07:00 (bug
 * 2026-05-13). Mirrors helpers in pages/index/index.js — keep in sync.
 */
function formatAlarmTime(alarm, use24Hour, ampmAM, ampmPM) {
    if (alarm) {
        var rel = alarm.relativeMinutes;
        var stamp = alarm.updatedAtEpoch;
        if (typeof rel === 'number' && isFinite(rel) && rel >= 1 &&
            typeof stamp === 'number' && isFinite(stamp) && stamp > 0) {
            var d = new Date(stamp + rel * 60000);
            return formatHHMM(d.getHours(), d.getMinutes(), use24Hour, ampmAM, ampmPM);
        }
    }
    return formatHHMM(alarm.hour, alarm.minute, use24Hour, ampmAM, ampmPM);
}

// Side-button on Lite Wearable cannot be intercepted directly as a
// key event — per memory:research on Phase 5b. The OS reacts to a side-
// button press by routing the page out (onHide fires). To turn that
// into an implicit "snooze" we set _explicitAction whenever the user
// taps a UI button (Dismiss / Snooze) and check it in onHide. If onHide
// fires without _explicitAction set, the user pressed the side button
// (or the watch screen timed out) — we send notifySnoozed so the phone
// snoozes instead of leaving the alarm ringing.
//
// PEER-ENDED guard: when the phone dismisses/snoozes its end, app.js's
// peer-ended callback writes `peer_ended_at_ms` into @system.storage
// BEFORE router.replace pops the ring page. onHide reads that flag —
// if it's recent (PEER_END_WINDOW_MS), suppress the implicit snooze.
// PEER_END_KEY here MUST match the one written in app.js. Bump both
// sites together if the contract changes.
var PEER_END_KEY = 'ring_peer_ended_at_ms';
var PEER_END_WINDOW_MS = 2000;

export default {
    data: {
        title: '',
        labelSnooze: '',
        labelDismiss: '',
        // Routed param — only alarmId arrives from the alarm_fired
        // dispatcher; label/time are looked up from the local store
        // when present. Snooze DURATION lives on the phone (per-alarm
        // `alarm.snoozeMinutes`); the watch never picks it.
        alarmId: 0,
        label: '',
        time: '--:--',
        ampmAM: '',
        ampmPM: '',
        _use24Hour: null,
        _vibrateTimer: null,
        snoozePressed: false,
        dismissPressed: false,
        // Side-button = implicit snooze workaround. Set to 'dismiss' or
        // 'snooze' the moment the user taps either UI button so the
        // subsequent onHide doesn't double-fire notifySnoozed. Cleared
        // by onShow so a fresh ring (e.g. snoozed alarm re-fires)
        // starts in the "no explicit action yet" state.
        _explicitAction: null,
    },

    onSnoozeDown:  function () { this.snoozePressed = true; },
    onSnoozeUp:    function () { this.snoozePressed = false; },
    onDismissDown: function () { this.dismissPressed = true; },
    onDismissUp:   function () { this.dismissPressed = false; },

    onInit: function () {
        Logger.i('ring.onInit alarmId=' + this.alarmId);
        this.title = this.$t('strings.reminder_default_title');
        this.labelSnooze = this.$t('strings.reminder_action_snooze');
        this.labelDismiss = this.$t('strings.reminder_action_close');
        this.ampmAM = this.$t('strings.ampm_am');
        this.ampmPM = this.$t('strings.ampm_pm');

        var self = this;
        SettingsStore.get(function (s) {
            self._use24Hour = s.use24Hour;
            // Re-format the now-anchored time once the locale pref lands.
            // No-op for use24Hour=null/true; formats 12-hour when false.
            var nowCal2 = new Date();
            self.time = formatHHMM(nowCal2.getHours(), nowCal2.getMinutes(),
                self._use24Hour, self.ampmAM, self.ampmPM);
        });

        // RING TIME IS ALWAYS "NOW". The ring screen fires at the alarm's
        // fire moment — that moment is the wall-clock instant the user is
        // looking at the watch. Earlier versions tried to compute the
        // display from `updatedAtEpoch + relativeMinutes` (relative) or
        // alarm.hour/minute (absolute); both paths produced "07:00" any
        // time the row was missing or had a stale/null `relativeMinutes`
        // field (THREE separate user reports 2026-05-13). Anchoring to
        // Date.now() at fire time can never lie about the wake-up
        // moment, regardless of what's in AlarmStore.
        var nowCal = new Date();
        this.time = formatHHMM(nowCal.getHours(), nowCal.getMinutes(),
            this._use24Hour, this.ampmAM, this.ampmPM);

        var idNum = Number(this.alarmId);
        if (!isFinite(idNum) || idNum <= 0) return;
        function applyRow(items) {
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === idNum) {
                    self.label = items[i].label || '';
                    // Snooze-duration label on the button (display only;
                    // phone remains authoritative for actual reschedule).
                    var raw = items[i].snoozeMinutes;
                    if (typeof raw === 'number' && isFinite(raw) && raw >= 1 && raw <= 60) {
                        self.labelSnooze = self.$t('strings.reminder_action_snooze') + ' ' + raw + 'm';
                    }
                    return true;
                }
            }
            return false;
        }
        AlarmStore.getAll(function (items) {
            if (applyRow(items)) return;
            Logger.i('ring.onInit no local alarm for id=' + idNum + ' — retrying in ' + ROW_RETRY_MS + 'ms');
            // One-shot retry: if the alarm_fired envelope outraced the
            // alarm_added that would have populated this row, give the
            // receive-side handler a moment to land it.
            setTimeout(function () {
                AlarmStore.getAll(function (items2) {
                    if (!applyRow(items2)) {
                        Logger.i('ring.onInit retry still missing id=' + idNum + ' — phone picks duration');
                    }
                });
            }, ROW_RETRY_MS);
        });
    },

    onShow: function () {
        Logger.i('ring.onShow alarmId=' + this.alarmId);
        // Fresh ring lifecycle — clear any prior explicit-action marker
        // so onHide's implicit-snooze fallback is armed.
        this._explicitAction = null;
        // Tell the phone our ring UI is up. Phone awaits this reply
        // before starting its own audio so the two devices ring in
        // sync. Fire-and-retry — phone falls back to ringing alone if
        // we don't reach it within its 3 s window.
        var idNum = Number(this.alarmId);
        if (isFinite(idNum) && idNum > 0) {
            try {
                WearBridge.sendRinging(idNum);
            } catch (e) {
                Logger.err('ring.sendRinging', e);
            }
        }
        function buzz() {
            try {
                if (vibrator && typeof vibrator.vibrate === 'function') {
                    vibrator.vibrate({ mode: 'long' });
                }
            } catch (e) {
                Logger.err('ring.vibrate', e);
            }
        }
        buzz();
        this._vibrateTimer = setInterval(buzz, VIBRATE_INTERVAL_MS);
    },

    // Lite Wearable side-button intercepts navigate the page out via
    // onHide. We can't capture the key event itself, so onHide is our
    // only signal that "the user pressed a button and we weren't told
    // which UI action they intended." Per task #85 and user research:
    // treat that as an implicit Snooze rather than letting the alarm
    // ring forever on the phone.
    //
    // Guards (else we'd double-snooze or misroute a peer-driven close):
    //   1. _explicitAction != null  → user tapped Dismiss/Snooze in
    //      our own UI; the dedicated path already notified the phone.
    //   2. peer_ended_at_ms within PEER_END_WINDOW_MS → app.js's
    //      peer-ended callback wrote this stamp BEFORE router.replace
    //      moved us out. The phone already handled the close on its
    //      side; the watch must not boomerang a snooze back.
    onHide: function () {
        Logger.i('ring.onHide alarmId=' + this.alarmId +
            ' explicit=' + this._explicitAction);
        this.stopVibrate();
        var explicit = this._explicitAction;
        var alarmId = Number(this.alarmId);
        if (explicit || !isFinite(alarmId) || alarmId <= 0) return;
        // Async storage read for the peer-ended marker. We're already
        // exiting the page; the read happens off the lifecycle path
        // and dispatches notifySnoozed in its success/fail tail.
        storage.get({
            key: PEER_END_KEY,
            default: '0',
            success: function (data) {
                var t = parseInt(data, 10);
                if (!isFinite(t) || t < 0) t = 0;
                var ageMs = Date.now() - t;
                if (t > 0 && ageMs >= 0 && ageMs < PEER_END_WINDOW_MS) {
                    Logger.i('ring.onHide peer-ended ' + ageMs +
                        'ms ago — suppress implicit snooze id=' + alarmId);
                    return;
                }
                Logger.i('ring.onHide implicit-snooze (side button?) id=' + alarmId);
                try {
                    WearBridge.notifySnoozed(alarmId);
                } catch (e) {
                    Logger.err('ring.onHide notifySnoozed', e);
                }
            },
            fail: function () {
                // No flag present = no peer-ended; still send implicit snooze.
                Logger.i('ring.onHide implicit-snooze (no flag) id=' + alarmId);
                try {
                    WearBridge.notifySnoozed(alarmId);
                } catch (e) {
                    Logger.err('ring.onHide notifySnoozed', e);
                }
            },
        });
    },

    stopVibrate: function () {
        if (this._vibrateTimer !== null) {
            clearInterval(this._vibrateTimer);
            this._vibrateTimer = null;
        }
    },

    onSwipe: function () {
        Logger.i('ring.onSwipe swallowed');
    },

    onDismiss: function () {
        Logger.i('ring.onDismiss-tap alarmId=' + this.alarmId);
        var self = this;
        self._explicitAction = 'dismiss';
        self.stopVibrate();
        var alarmId = self.alarmId;
        // CRITICAL: notify + navigate happen IMMEDIATELY, NOT inside
        // AlarmStore.getAll's callback. Previous version put them in
        // `done()` which only fired after storage returned — if
        // @system.storage hangs (seen on real GT 6 hardware), the user's
        // tap stopped vibration but neither hid the screen nor told the
        // phone. The one-shot auto-disable below is best-effort; the user
        // experience (close ring + tell phone) must not depend on it.
        //
        // No RingState clear here — that module was per-bundle-isolated
        // (gotcha #11) and didn't actually share state between app.js
        // and ring.js. App.js now always routes to index on peer-ended;
        // the navigation is idempotent so a phone-driven close + our own
        // local close don't conflict.
        Logger.i('ring.dismiss notify id=' + alarmId);
        WearBridge.notifyDismissed(alarmId);
        try {
            router.replace({ uri: 'pages/index/index' });
            Logger.i('ring.dismiss router.replace returned id=' + alarmId);
        } catch (e) {
            Logger.err('ring.dismiss router.replace threw', e);
        }
        // Best-effort one-shot auto-disable. Runs after navigation; if
        // storage is broken this silently does nothing.
        AlarmStore.getAll(function (items) {
            var found = null;
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === alarmId) {
                    found = items[i];
                    break;
                }
            }
            if (found !== null && found.daysOfWeek === DOW_NONE) {
                var updated = {
                    id: found.id,
                    label: found.label,
                    hour: found.hour,
                    minute: found.minute,
                    daysOfWeek: found.daysOfWeek,
                    enabled: false,
                    updatedAtEpoch: Date.now(),
                };
                AlarmStore.update(updated, function () {
                    Logger.i('ring.dismiss auto-disabled one-shot id=' + alarmId);
                });
            }
        });
    },

    onSnooze: function () {
        this._explicitAction = 'snooze';
        this.stopVibrate();
        var alarmId = this.alarmId;
        // Phone owns snooze duration (per-alarm `alarm.snoozeMinutes`).
        // We just notify; phone schedules the reschedule itself.
        WearBridge.notifySnoozed(alarmId);
        Logger.i('ring.snooze notify+navigate id=' + alarmId);
        try {
            router.replace({ uri: 'pages/index/index' });
            Logger.i('ring.snooze router.replace returned id=' + alarmId);
        } catch (e) {
            Logger.err('ring.snooze router.replace threw', e);
        }
    },
};
