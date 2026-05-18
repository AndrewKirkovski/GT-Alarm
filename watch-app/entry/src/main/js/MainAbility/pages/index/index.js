// Single-page watch app. ONE page, ONE bundle, ONE Wear Engine P2P
// receiver registered once and never re-navigated away from. The three
// "screens" — list / sync / ring — are `if=`-rendered blocks switched
// by the `screen` data field. No router: the fragmentation and
// context-teardown of the old 3-page design is gone.
import app from '@system.app';
import storage from '@system.storage';
import vibrator from '@system.vibrator';
import AlarmStore from '../../common/alarmStore.js';
import SettingsStore from '../../common/settingsStore.js';
import WearBridge from '../../common/wearBridge.js';
import IncomingHandler from '../../common/incomingHandler.js';
import Logger from '../../common/logger.js';

// Bump per hardware-test build so the on-screen tag confirms the new
// HAP actually replaced the old one.
var BUILD_TAG = 'bg-ui3/05-18';

// How long the row-tap hint overlay stays up.
var TAP_HINT_MS = 2600;

// @system.storage key holding the on-watch path of the last P2P-received
// background file. The file itself persists in the app sandbox across
// restarts; this remembers WHERE so a relaunch can re-point <image> at
// it without waiting for another upload. Path is ~36 B, well under the
// 128 B storage cap.
var BG_PATH_KEY = 'bg_path';

var DAY_BITS = [1, 2, 4, 8, 16, 32, 64];
var DEFAULT_DAY_ORDER = [1, 2, 3, 4, 5, 6, 0];
var VIBRATE_INTERVAL_MS = 1500;
var ROW_RETRY_MS = 1500;
var PEER_FLASH_MS = 1200;

// Ring auto-terminate. The watch was woken for the alarm; once the
// action settles, close the app instead of sitting on the watch face.
var TERMINATE_HARD_CAP_MS = 6000;
// Long enough that the ring's DISMISSED/SNOOZED confirmation (shown for
// PEER_FLASH_MS) stays visible before the watch auto-closes.
var TERMINATE_AFTER_ACK_MS = 1400;
var _terminateScheduled = false;
var _terminateTimer = null;
// Side-button-during-ring guard: set by the peer-ended handler so the
// page's onHide does not boomerang an implicit snooze back to a phone
// that already ended the alarm. One bundle now — a plain module var,
// no @system.storage round-trip needed.
var _peerEndedAtMs = 0;
var PEER_END_WINDOW_MS = 2500;

function fireTerminate(tag) {
    Logger.i('term ' + tag);
    try {
        app.terminate();
    } catch (e) {
        Logger.err('term', e);
    }
}

function scheduleTerminate(reason) {
    if (_terminateScheduled) return;
    _terminateScheduled = true;
    _terminateTimer = setTimeout(function () {
        fireTerminate('cap:' + reason);
    }, TERMINATE_HARD_CAP_MS);
}

function expediteTerminate(tag) {
    if (!_terminateScheduled || _terminateTimer === null) return;
    clearTimeout(_terminateTimer);
    _terminateTimer = setTimeout(function () {
        fireTerminate('ack:' + tag);
    }, TERMINATE_AFTER_ACK_MS);
}

function cancelTerminate() {
    if (_terminateTimer !== null) {
        clearTimeout(_terminateTimer);
        _terminateTimer = null;
    }
    _terminateScheduled = false;
}

function pad2(n) {
    return (n < 10 ? '0' : '') + n;
}

// firstDayOfWeek: java.util.Calendar 1=SUN..7=SAT, or null = Monday-first.
function computeDayOrder(firstDayOfWeek) {
    if (typeof firstDayOfWeek !== 'number' || !isFinite(firstDayOfWeek)) {
        return DEFAULT_DAY_ORDER;
    }
    var start = Math.floor(firstDayOfWeek) - 1;
    if (start < 0 || start > 6) return DEFAULT_DAY_ORDER;
    var out = [0, 0, 0, 0, 0, 0, 0];
    for (var i = 0; i < 7; i++) out[i] = (start + i) % 7;
    return out;
}

// 12/24h formatter. use24Hour false => "h:MM AM/PM", else "HH:MM".
function formatHHMM(hour, minute, use24Hour, ampmAM, ampmPM) {
    if (use24Hour === false) {
        var h = hour % 12;
        if (h === 0) h = 12;
        return h + ':' + pad2(minute) + ' ' + (hour < 12 ? ampmAM : ampmPM);
    }
    return pad2(hour) + ':' + pad2(minute);
}

// Relative ("Timer") alarms ship placeholder hour/minute (07:00 from the
// phone picker) + relativeMinutes; the real fire moment is
// updatedAtEpoch + relativeMinutes*60000. Returns epoch ms, or null for
// absolute alarms (caller uses hour/minute directly).
function relativeFireEpoch(alarm) {
    if (!alarm) return null;
    var rel = alarm.relativeMinutes;
    if (typeof rel !== 'number' || !isFinite(rel) || rel < 1) return null;
    var stamp = alarm.updatedAtEpoch;
    if (typeof stamp !== 'number' || !isFinite(stamp) || stamp <= 0) return null;
    return stamp + rel * 60000;
}

// "+Nm" / "+NhMm" duration label for a DISABLED relative alarm — its
// updatedAtEpoch was bumped on toggle-off so the computed clock time
// would be a stale future moment.
function formatRelativeDuration(rel) {
    if (typeof rel !== 'number' || !isFinite(rel) || rel < 1) return '';
    if (rel < 60) return '+' + rel + 'm';
    var h = Math.floor(rel / 60);
    var m = rel % 60;
    return m > 0 ? '+' + h + 'h' + m + 'm' : '+' + h + 'h';
}

function formatAlarmTime(alarm, use24Hour, ampmAM, ampmPM) {
    var rel = alarm.relativeMinutes;
    var hasRel = typeof rel === 'number' && isFinite(rel) && rel >= 1;
    if (hasRel && !alarm.enabled) {
        return formatRelativeDuration(rel);
    }
    var fireEpoch = relativeFireEpoch(alarm);
    if (fireEpoch !== null) {
        var d = new Date(fireEpoch);
        return formatHHMM(d.getHours(), d.getMinutes(), use24Hour, ampmAM, ampmPM);
    }
    return formatHHMM(alarm.hour, alarm.minute, use24Hour, ampmAM, ampmPM);
}

function nonRecurringLabel(self, alarm) {
    if (alarm.relativeMinutes) return self.repeatTimer;
    if (alarm.selfDestruct) return self.repeatOneOff;
    return self.repeatOnce;
}

function letterArrayFromSelf(self, order) {
    var twoChar = [self.d0, self.d1, self.d2, self.d3, self.d4, self.d5, self.d6];
    var out = ['', '', '', '', '', '', ''];
    var ord = order || DEFAULT_DAY_ORDER;
    for (var i = 0; i < 7; i++) {
        var label = twoChar[ord[i]] || '';
        out[i] = label.length > 0 ? label.charAt(0) : '';
    }
    return out;
}

function formatRow(self, alarm) {
    var enabled = !!alarm.enabled;
    var isRecurring = !!alarm.daysOfWeek && alarm.daysOfWeek !== 0;
    var order = self._dayOrder || DEFAULT_DAY_ORDER;
    var dayBits = [false, false, false, false, false, false, false];
    for (var i = 0; i < 7; i++) {
        dayBits[i] = (alarm.daysOfWeek & DAY_BITS[order[i]]) !== 0;
    }
    var dayLetters = letterArrayFromSelf(self, order);
    return {
        id: alarm.id,
        time: formatAlarmTime(alarm, self._use24Hour, self.ampmAM, self.ampmPM),
        enabled: enabled,
        notEnabled: !enabled,
        isRecurring: isRecurring,
        isNotRecurring: !isRecurring,
        daysText: isRecurring ? '' : nonRecurringLabel(self, alarm),
        d0On: dayBits[0], d0Off: !dayBits[0],
        d1On: dayBits[1], d1Off: !dayBits[1],
        d2On: dayBits[2], d2Off: !dayBits[2],
        d3On: dayBits[3], d3Off: !dayBits[3],
        d4On: dayBits[4], d4Off: !dayBits[4],
        d5On: dayBits[5], d5Off: !dayBits[5],
        d6On: dayBits[6], d6Off: !dayBits[6],
        dL0: dayLetters[0], dL1: dayLetters[1], dL2: dayLetters[2],
        dL3: dayLetters[3], dL4: dayLetters[4], dL5: dayLetters[5],
        dL6: dayLetters[6],
    };
}

function compareAlarms(a, b) {
    if (a.enabled !== b.enabled) return a.enabled ? -1 : 1;
    var aHour = a.hour, aMin = a.minute, bHour = b.hour, bMin = b.minute;
    var aFire = relativeFireEpoch(a);
    var bFire = relativeFireEpoch(b);
    if (aFire !== null) {
        var da = new Date(aFire);
        aHour = da.getHours(); aMin = da.getMinutes();
    }
    if (bFire !== null) {
        var db = new Date(bFire);
        bHour = db.getHours(); bMin = db.getMinutes();
    }
    if (aHour !== bHour) return aHour - bHour;
    return aMin - bMin;
}

function formatLastSync(self, epoch) {
    if (!epoch || epoch <= 0) return self.noSyncYet;
    var ageMs = Date.now() - epoch;
    if (ageMs < 0) return self.noSyncYet;
    var ageSec = Math.floor(ageMs / 1000);
    if (ageSec < 60) return self.syncJustNow;
    var ageMin = Math.floor(ageSec / 60);
    if (ageMin < 60) return self.syncMinAgo.replace('{n}', '' + ageMin);
    var ageHr = Math.floor(ageMin / 60);
    if (ageHr < 24) return self.syncHrAgo.replace('{n}', '' + ageHr);
    return self.syncDayAgo.replace('{n}', '' + Math.floor(ageHr / 24));
}

export default {
    data: {
        // 'list' | 'sync' | 'ring'
        screen: 'list',

        // --- list screen ---
        title: '',
        alarms: [],
        emptyText: '',
        emptyHint: '',
        diagLine1: 'rx:--',
        diagLine2: '',

        // --- background photo ---
        // bgSrc: on-watch path of a P2P-received PNG, fed straight into
        // the full-screen <image src> with NO decode/copy. showScrim
        // gates the 50% dimming layer (list/sync screens only — the ring
        // screen shows the photo at full strength). bgDiag is an
        // always-rendered debug line refreshed by the 2 s poll.
        bgSrc: '',
        // hasBg MUST be a real boolean — HML `if=` does NOT coerce a
        // truthy string, so `if="{{bgSrc}}"` never rendered the <image>.
        hasBg: false,
        bgDiag: 'bg:none',
        showScrim: false,

        // --- row-tap hint overlay ---
        tapHint: '',
        tapHintShown: false,

        // --- sync screen ---
        syncTitle: '',
        syncSubtitle: '',
        liveLine: '',

        // --- ring screen ---
        ringTitle: '',
        ringTime: '--:--',
        ringLabel: '',
        labelSnooze: '',
        labelDismiss: '',
        snoozePressed: false,
        dismissPressed: false,
        // `ended` — set once the alarm is dismissed/snoozed (by phone OR
        // watch). Swaps the arc green and replaces the action buttons
        // with the `endedText` confirmation (DISMISSED / SNOOZED).
        ended: false,
        endedText: '',
        ringDiagText: 'snt:-- rx:--',

        // --- i18n bag (read by helpers via `self`) ---
        repeatOnce: '', repeatTimer: '', repeatOneOff: '',
        repeatAll: '', repeatWeekdays: '', repeatWeekends: '',
        noSyncYet: '', syncJustNow: '', syncMinAgo: '',
        syncHrAgo: '', syncDayAgo: '', editOnPhoneToast: '',
        d0: '', d1: '', d2: '', d3: '', d4: '', d5: '', d6: '',
        ampmAM: '', ampmPM: '',

        // non-reactive scratch — not bound in HML
        _alarmId: 0,
        _explicitAction: null,
        _ringSent: '--',
        _evCount: 0,
        _userEngaged: false,
        _refreshTimer: null,
        _diagTimer: null,
        _vibrateTimer: null,
        _tapHintTimer: null,
        _use24Hour: null,
        _dayOrder: DEFAULT_DAY_ORDER,
    },

    onInit: function () {
        var self = this;
        this.title = this.$t('strings.app_name');
        this.emptyText = this.$t('strings.no_alarms');
        this.emptyHint = this.$t('strings.no_alarms_hint');
        this.syncTitle = this.$t('strings.syncing_title');
        this.syncSubtitle = this.$t('strings.syncing_subtitle');
        this.ringTitle = this.$t('strings.reminder_default_title');
        this.labelSnooze = this.$t('strings.reminder_action_snooze');
        this.labelDismiss = this.$t('strings.reminder_action_close');
        this.repeatOnce = this.$t('strings.repeat_once');
        this.repeatTimer = this.$t('strings.repeat_timer');
        this.repeatOneOff = this.$t('strings.repeat_oneoff');
        this.repeatAll = this.$t('strings.repeat_every_day');
        this.repeatWeekdays = this.$t('strings.repeat_weekdays');
        this.repeatWeekends = this.$t('strings.repeat_weekends');
        this.noSyncYet = this.$t('strings.sync_never');
        this.syncJustNow = this.$t('strings.sync_just_now');
        this.syncMinAgo = this.$t('strings.sync_min_ago');
        this.syncHrAgo = this.$t('strings.sync_hr_ago');
        this.syncDayAgo = this.$t('strings.sync_day_ago');
        this.editOnPhoneToast = this.$t('strings.edit_on_phone');
        this.d0 = this.$t('strings.day_sun');
        this.d1 = this.$t('strings.day_mon');
        this.d2 = this.$t('strings.day_tue');
        this.d3 = this.$t('strings.day_wed');
        this.d4 = this.$t('strings.day_thu');
        this.d5 = this.$t('strings.day_fri');
        this.d6 = this.$t('strings.day_sat');
        this.ampmAM = this.$t('strings.ampm_am');
        this.ampmPM = this.$t('strings.ampm_pm');

        // Anchor the wake-reason clock BEFORE the receiver is installed.
        IncomingHandler.markAppStart();

        // Wire THIS bundle's log relay (one bundle => one logger).
        Logger.setRemoteSink(function (lines) {
            try {
                WearBridge.sendLogBatch(lines);
            } catch (e) {
                // best-effort
            }
        });
        Logger.i('index.onInit build=' + BUILD_TAG);

        // Incoming-envelope -> screen transitions. One page, so the
        // handler updates this page's data directly.
        IncomingHandler.setOnAlarmFiredNavigator(function (alarmId) {
            self.enterRing(alarmId);
        });
        IncomingHandler.setOnPeerEndedRing(function (alarmId, type) {
            self.onPeerEnded(alarmId, type);
        });
        IncomingHandler.setOnSyncWake(function () {
            if (self.screen === 'list') {
                self.screen = 'sync';
                self.updateScrim();
                Logger.i('index.screen=sync (wake-by-sync)');
            }
        });
        IncomingHandler.setOnSyncDone(function () {
            Logger.i('index.sync_done -> terminate');
            fireTerminate('sync_done');
        });

        SettingsStore.get(function (s) {
            self._use24Hour = s.use24Hour;
            self._dayOrder = computeDayOrder(s.firstDayOfWeek);
        });

        // Restore a previously-received background path so a relaunch
        // shows the image without re-uploading from the phone.
        storage.get({
            key: BG_PATH_KEY,
            default: '',
            success: function (v) {
                if (v) {
                    self.bgSrc = '' + v;
                    self.hasBg = true;
                    Logger.i('index.bgPath restored=' + v);
                }
            },
            fail: function () {},
        });
    },

    onShow: function () {
        Logger.i('index.onShow screen=' + this.screen);
        var self = this;
        // Register the single P2P receiver. Re-registered on every show
        // (idempotent) so a background/foreground cycle re-points the
        // global Wear Engine subscription at this live callback.
        WearBridge.setPageTag('m');
        // Background-image test: a P2P-received file is handed back as an
        // on-watch path. Reference it directly via <image src> — no
        // decode, no copy — and surface the path on-screen so a failed
        // render is still self-diagnosing.
        WearBridge.setFileHandler(function (path) {
            Logger.i('index.bgFile path=' + path);
            self.bgSrc = '' + path;
            self.hasBg = true;
            // Persist the path so a relaunch restores it (the file itself
            // already survives in the sandbox).
            storage.set({
                key: BG_PATH_KEY,
                value: '' + path,
                success: function () {},
                fail: function () {},
            });
            // The 2 s poll (refresh) surfaces bgSrc into bgDiag + the
            // bg-box gate — no reliance on if=/async reactivity here.
            self.refresh();
        });
        WearBridge.setIncomingHandler(function (msg) {
            try {
                if (msg && msg.type) {
                    self._evCount = self._evCount + 1;
                    self.liveLine = 'rx #' + self._evCount + ': ' + msg.type;
                }
                IncomingHandler.handle(msg);
            } catch (e) {
                Logger.err('index.handle', e);
            }
        });

        AlarmStore.setOnChange(function () {
            self.refresh();
        });
        this.refresh();
        this.refreshDiag();
        if (this._refreshTimer === null) {
            this._refreshTimer = setInterval(function () {
                self.refresh();
                self.refreshDiag();
            }, 2000);
        }
    },

    onHide: function () {
        Logger.i('index.onHide screen=' + this.screen +
            ' explicit=' + this._explicitAction);
        var self = this;
        AlarmStore.setOnChange(null);
        if (this._refreshTimer !== null) {
            clearInterval(this._refreshTimer);
            this._refreshTimer = null;
        }
        if (this._diagTimer !== null) {
            clearInterval(this._diagTimer);
            this._diagTimer = null;
        }
        this.stopVibrate();
        // Side-button-while-ringing = implicit snooze. Only when the ring
        // screen is up, no explicit Dismiss/Snooze tap happened, and the
        // phone did not just peer-end the alarm.
        if (this.screen === 'ring' && !this._explicitAction) {
            var ageMs = Date.now() - _peerEndedAtMs;
            if (_peerEndedAtMs > 0 && ageMs >= 0 && ageMs < PEER_END_WINDOW_MS) {
                Logger.i('index.onHide peer-ended — suppress implicit snooze');
            } else {
                var idNum = Number(this._alarmId);
                if (isFinite(idNum) && idNum > 0) {
                    Logger.i('index.onHide implicit-snooze id=' + idNum);
                    try {
                        WearBridge.notifySnoozed(idNum, function (ok, reason) {
                            Logger.i('index.onHide-snooze ack ok=' + ok + ' r=' + reason);
                        });
                    } catch (e) {
                        Logger.err('index.onHide snooze', e);
                    }
                }
            }
        }
        Logger.flushNow();
    },

    // ---- LIST ----
    refresh: function () {
        var self = this;
        SettingsStore.get(function (s) {
            self._use24Hour = s.use24Hour;
            self._dayOrder = computeDayOrder(s.firstDayOfWeek);
        });
        AlarmStore.getAll(function (items) {
            var sorted = items.slice();
            sorted.sort(compareAlarms);
            var rows = [];
            var on = 0;
            for (var i = 0; i < sorted.length; i++) {
                if (sorted[i].enabled) on++;
                rows.push(formatRow(self, sorted[i]));
            }
            self.alarms = rows;
            // Background photo debug line + scrim gate. bgSrc is a plain
            // field set by the P2P file handler; the poll surfaces it
            // reliably even if if=/async reactivity is not.
            self.hasBg = !!self.bgSrc;
            self.bgDiag = self.bgSrc ? ('bg:' + self.bgSrc) : 'bg:none';
            self.updateScrim();
            AlarmStore.getLastSyncEpoch(function (epoch) {
                self.diagLine2 = 'st:' + items.length + ' on:' + on +
                    ' ' + formatLastSync(self, epoch) + ' b:' + BUILD_TAG;
            });
        });
    },

    // Scrim shows on the list + sync screens (dims the photo so white
    // text stays legible); the ring screen shows the photo at full
    // strength. Recomputed on every screen change + the 2 s poll.
    updateScrim: function () {
        this.showScrim = !!this.bgSrc && this.screen !== 'ring';
    },

    // Alarms are phone-only — tapping a row shows an on-screen hint.
    // (An inline overlay, not @system.prompt.showToast — the toast did
    // not surface on GT 6 hardware.)
    onRowTap: function (id) {
        var self = this;
        this._userEngaged = true;
        this.tapHint = this.editOnPhoneToast;
        this.tapHintShown = true;
        if (this._tapHintTimer !== null) {
            clearTimeout(this._tapHintTimer);
        }
        this._tapHintTimer = setTimeout(function () {
            self.tapHintShown = false;
            self._tapHintTimer = null;
        }, TAP_HINT_MS);
    },

    // ---- DIAGNOSTIC STRIP ----
    // One read updates both the list/sync strip (diagLine1) and the ring
    // strip (ringDiagText): rx-state + raw vs handled inbound counters.
    refreshDiag: function () {
        var self = this;
        var rx = WearBridge.getReceiverState();
        var rxTxt = (rx.result === 'ok')
            ? ('ok@' + Math.floor((Date.now() - rx.atMs) / 1000) + 's')
            : rx.result;
        storage.get({
            key: 'diag_rawrx',
            default: '{"count":0}',
            success: function (raw) {
                var r;
                try {
                    r = JSON.parse(raw);
                } catch (e) {
                    r = { count: 0 };
                }
                self.applyDiag(rxTxt, r.count || 0);
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
                var o;
                try {
                    o = JSON.parse(data);
                } catch (e) {
                    o = null;
                }
                var inCount = o ? (o.total || 0) : 0;
                var last = (o && o.lastType) ? o.lastType : '-';
                var age = (o && o.lastTs)
                    ? (Math.floor((Date.now() - o.lastTs) / 1000) + 's')
                    : '-';
                self.diagLine1 = 'rx:' + rxTxt + ' raw:' + rawCount +
                    ' in:' + inCount + ' ' + last + '@' + age;
                self.ringDiagText = 'snt:' + self._ringSent + ' rx:' + rxTxt +
                    ' raw:' + rawCount + ' in:' + inCount;
            },
            fail: function () {
                self.diagLine1 = 'rx:' + rxTxt + ' diag:fail';
                self.ringDiagText = 'snt:' + self._ringSent + ' rx:' + rxTxt;
            },
        });
    },

    // ---- RING ----
    enterRing: function (alarmId) {
        Logger.i('index.enterRing id=' + alarmId);
        var self = this;
        this._alarmId = alarmId;
        this._explicitAction = null;
        this.ended = false;
        this.endedText = '';
        this.ringLabel = '';
        cancelTerminate();
        this.screen = 'ring';
        this.updateScrim();

        // Ring time is always "now" — the wake-up instant the user sees.
        var now = new Date();
        this.ringTime = formatHHMM(now.getHours(), now.getMinutes(),
            this._use24Hour, this.ampmAM, this.ampmPM);

        // Tell the phone our ring UI is up (it awaits this before
        // starting its own audio). Surface the result code on-screen.
        var idNum = Number(alarmId);
        if (isFinite(idNum) && idNum > 0) {
            try {
                WearBridge.sendRinging(idNum, function (ok, reason) {
                    self._ringSent = reason;
                });
            } catch (e) {
                Logger.err('index.sendRinging', e);
            }
        }
        this.lookupRingLabel(idNum, true);
        this.startVibrate();
    },

    lookupRingLabel: function (idNum, allowRetry) {
        var self = this;
        AlarmStore.getAll(function (items) {
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === idNum) {
                    self.ringLabel = items[i].label || '';
                    var raw = items[i].snoozeMinutes;
                    if (typeof raw === 'number' && isFinite(raw) && raw >= 1 && raw <= 60) {
                        self.labelSnooze = self.$t('strings.reminder_action_snooze') + ' ' + raw + 'm';
                    }
                    return;
                }
            }
            // alarm_fired can outrace the alarm_added that populates the
            // row — one retry covers the gap.
            if (allowRetry) {
                setTimeout(function () {
                    self.lookupRingLabel(idNum, false);
                }, ROW_RETRY_MS);
            }
        });
    },

    startVibrate: function () {
        this.stopVibrate();
        function buzz() {
            try {
                if (vibrator && typeof vibrator.vibrate === 'function') {
                    vibrator.vibrate({ mode: 'long' });
                }
            } catch (e) {
                Logger.err('vibrate', e);
            }
        }
        buzz();
        this._vibrateTimer = setInterval(buzz, VIBRATE_INTERVAL_MS);
    },

    stopVibrate: function () {
        if (this._vibrateTimer !== null) {
            clearInterval(this._vibrateTimer);
            this._vibrateTimer = null;
        }
    },

    onSnoozeDown: function () { this.snoozePressed = true; },
    onSnoozeUp: function () { this.snoozePressed = false; },
    onDismissDown: function () { this.dismissPressed = true; },
    onDismissUp: function () { this.dismissPressed = false; },
    onSwipe: function () {},

    onDismiss: function () {
        Logger.i('index.onDismiss id=' + this._alarmId);
        var self = this;
        this._explicitAction = 'dismiss';
        this.stopVibrate();
        // Show the green DISMISSED confirmation in place of the buttons,
        // then leave the ring after the same flash window the phone-
        // initiated path uses.
        this.ended = true;
        this.endedText = 'DISMISSED';
        var alarmId = this._alarmId;
        WearBridge.notifyDismissed(alarmId, function (ok, reason) {
            Logger.i('index.dismiss-ack ok=' + ok + ' r=' + reason);
            expediteTerminate('dismiss');
        });
        scheduleTerminate('dismiss');
        setTimeout(function () {
            self.screen = 'list';
            self.updateScrim();
            self.refresh();
        }, PEER_FLASH_MS);
        // Best-effort one-shot auto-disable for non-recurring alarms.
        AlarmStore.getAll(function (items) {
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === alarmId && items[i].daysOfWeek === 0) {
                    var u = {};
                    for (var k in items[i]) {
                        if (items[i].hasOwnProperty(k)) u[k] = items[i][k];
                    }
                    u.enabled = false;
                    u.updatedAtEpoch = Date.now();
                    AlarmStore.update(u, function () {});
                    return;
                }
            }
        });
    },

    onSnooze: function () {
        Logger.i('index.onSnooze id=' + this._alarmId);
        var self = this;
        this._explicitAction = 'snooze';
        this.stopVibrate();
        this.ended = true;
        this.endedText = 'SNOOZED';
        WearBridge.notifySnoozed(this._alarmId, function (ok, reason) {
            Logger.i('index.snooze-ack ok=' + ok + ' r=' + reason);
            expediteTerminate('snooze');
        });
        scheduleTerminate('snooze');
        setTimeout(function () {
            self.screen = 'list';
            self.updateScrim();
            self.refresh();
        }, PEER_FLASH_MS);
    },

    // Phone reported the alarm dismissed/snoozed on its side — the
    // headline Bug-1 indicator. Flash the ring screen green, then close.
    onPeerEnded: function (alarmId, type) {
        Logger.i('index.onPeerEnded type=' + type + ' id=' + alarmId);
        var self = this;
        this.stopVibrate();
        if (this.screen !== 'ring') {
            // No ring up — nothing to flash; just make sure we are home.
            this.screen = 'list';
            this.updateScrim();
            return;
        }
        this._explicitAction = 'peer-ended';
        _peerEndedAtMs = Date.now();
        this.endedText = (type === 'alarm_snoozed') ? 'SNOOZED' : 'DISMISSED';
        this.ended = true;
        setTimeout(function () {
            self.screen = 'list';
            self.updateScrim();
            self.refresh();
            scheduleTerminate('peer-ended');
        }, PEER_FLASH_MS);
    },
};
