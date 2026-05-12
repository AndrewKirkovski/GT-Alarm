import router from '@system.router';
import vibrator from '@system.vibrator';
import AlarmStore from '../../common/alarmStore.js';
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
        _vibrateTimer: null,
        snoozePressed: false,
        dismissPressed: false,
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

        // Thin-client fallback: show the current time if the alarm row
        // hasn't synced yet, so dismiss/snooze still work end-to-end
        // (phone will look up the row when our notify arrives).
        var nowCal = new Date();
        this.time = pad2(nowCal.getHours()) + ':' + pad2(nowCal.getMinutes());

        var self = this;
        var idNum = Number(this.alarmId);
        if (!isFinite(idNum) || idNum <= 0) return;
        function applyRow(items) {
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === idNum) {
                    self.label = items[i].label || '';
                    self.time = pad2(items[i].hour) + ':' + pad2(items[i].minute);
                    // Show snooze duration on the button when synced — this
                    // is display-only metadata; phone remains authoritative
                    // for the actual reschedule time.
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

    onHide: function () {
        Logger.i('ring.onHide alarmId=' + this.alarmId);
        this.stopVibrate();
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
