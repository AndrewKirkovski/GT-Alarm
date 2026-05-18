import storage from '@system.storage';
import file from '@system.file';
import prompt from '@system.prompt';
import router from '@system.router';
import AlarmStore from '../../common/alarmStore.js';
import SettingsStore from '../../common/settingsStore.js';
import WearBridge from '../../common/wearBridge.js';
import IncomingHandler from '../../common/incomingHandler.js';
import Logger from '../../common/logger.js';

var DAY_BITS = [1, 2, 4, 8, 16, 32, 64];

// Default display order — Monday-first. Recomputed per-refresh from
// SettingsStore.snapshot().firstDayOfWeek when the user picked a
// different week-start on the phone Settings screen. Indices into
// DAY_BITS: 0=SUN .. 6=SAT.
var DEFAULT_DAY_DISPLAY_ORDER = [1, 2, 3, 4, 5, 6, 0];

function pad2(n) {
    return (n < 10 ? '0' : '') + n;
}

/**
 * Compute the day-display order from a phone-sourced firstDayOfWeek
 * (java.util.Calendar values: 1=SUNDAY .. 7=SATURDAY) or null = follow
 * the Monday-first default. Returns an array of 7 indices into
 * DAY_BITS — slot i shows the day at bit DAY_BITS[result[i]].
 */
function computeDayOrder(firstDayOfWeek) {
    if (typeof firstDayOfWeek !== 'number' || !isFinite(firstDayOfWeek)) {
        return DEFAULT_DAY_DISPLAY_ORDER;
    }
    var start = Math.floor(firstDayOfWeek) - 1; // 0=SUN..6=SAT
    if (start < 0 || start > 6) return DEFAULT_DAY_DISPLAY_ORDER;
    var out = [0, 0, 0, 0, 0, 0, 0];
    for (var i = 0; i < 7; i++) out[i] = (start + i) % 7;
    return out;
}

/**
 * Format an `hour:minute` pair honoring the phone-pushed 12/24h pref.
 *  - use24Hour=true  → "HH:MM" (24-hour, zero-padded)
 *  - use24Hour=false → "h:MM AM" / "h:MM PM" (12-hour, no leading
 *    zero on hours per common watch-face convention)
 *  - use24Hour=null  → default 24-hour (matches pre-pref baseline)
 *
 * `ampmAM` / `ampmPM` are the localized "AM"/"PM" suffixes — index.js
 * pulls them from i18n once in onInit, ring.js does the same.
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
 * Compute the effective fire-time of an alarm in epoch ms.
 *
 * For relative ("Timer" / "in N min") alarms, the phone ships `hour`/
 * `minute` as fixed placeholders (default 07:00 from the picker UI — see
 * AlarmRepository on Android) and encodes the actual fire moment as
 * `updatedAtEpoch + relativeMinutes * 60000`. Honouring the placeholders
 * would render every Timer as "07:00" (bug observed 2026-05-13).
 *
 * For absolute alarms, `relativeMinutes` is missing/null/0 and we fall
 * back to the literal `hour`/`minute` fields.
 *
 * Returns an epoch in ms (Number) for relative alarms, or `null` for
 * absolute alarms (caller uses hour/minute directly).
 */
function relativeFireEpoch(alarm) {
    if (!alarm) return null;
    var rel = alarm.relativeMinutes;
    if (typeof rel !== 'number' || !isFinite(rel) || rel < 1) return null;
    var stamp = alarm.updatedAtEpoch;
    if (typeof stamp !== 'number' || !isFinite(stamp) || stamp <= 0) return null;
    return stamp + rel * 60000;
}

/**
 * Format a "+Nm" / "+NhMm" duration label for a disabled relative
 * alarm. The enabled-relative branch of formatAlarmTime shows the
 * computed clock-time fire moment (e.g. "14:35"). Disabled rows can't
 * meaningfully display that — `updatedAtEpoch` got bumped on toggle-off
 * (phone's setEnabled stamps it) so the previously-rendered HH:MM is a
 * stale future moment that drifts into the past as wall-clock passes
 * (bug #93 2026-05-13). Switching to a duration label matches the
 * row's identity ("this is a 10-minute timer") and survives the toggle
 * without lying about the next fire moment.
 */
function formatRelativeDuration(rel) {
    if (typeof rel !== 'number' || !isFinite(rel) || rel < 1) return '';
    if (rel < 60) return '+' + rel + 'm';
    var h = Math.floor(rel / 60);
    var m = rel % 60;
    return m > 0 ? '+' + h + 'h' + m + 'm' : '+' + h + 'h';
}

/**
 * Format the visible "HH:MM" string for an alarm row. Branches on
 * relativeMinutes per relativeFireEpoch's contract, and honors the
 * phone-pushed 12/24h pref via formatHHMM. For DISABLED relative
 * alarms, returns a duration label ("+10m") instead of a clock-time —
 * see formatRelativeDuration JSDoc for the rationale.
 */
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

/**
 * Build the text label for a non-recurring alarm. Three flavors:
 *   "Timer"   — relative ("in N min") origin
 *   "One-off" — absolute one-shot with selfDestruct (auto-delete)
 *   "Once"    — plain one-shot, persists in list after firing
 * Recurring alarms (daysOfWeek != 0) use the dot grid instead, so this
 * is only consulted in the non-recurring branch.
 */
function nonRecurringLabel(self, alarm) {
    if (alarm.relativeMinutes) return self.repeatTimer;
    if (alarm.selfDestruct) return self.repeatOneOff;
    return self.repeatOnce;
}

/**
 * Build the per-row state for the HML binding. All class names are
 * computed in JS so the HML stays a flat tree — Lite Wearable's class
 * binding accepts a plain string per element, no compound expressions.
 */
function formatRow(self, alarm) {
    var enabled = !!alarm.enabled;
    var isRecurring = !!alarm.daysOfWeek && alarm.daysOfWeek !== 0;
    var daysText = isRecurring ? '' : nonRecurringLabel(self, alarm);

    // 7 day-dot booleans in display order — computed per-refresh from
    // SettingsStore.snapshot().firstDayOfWeek into self._dayOrder so a
    // phone-pushed week-start change re-renders without restarting the
    // page. dN points at the bit slot visible in column N; HML binds
    // dNOn / dNOff.
    var order = self._dayOrder || DEFAULT_DAY_DISPLAY_ORDER;
    var dayBits = [false, false, false, false, false, false, false];
    for (var i = 0; i < 7; i++) {
        var bitIdx = order[i];
        dayBits[i] = (alarm.daysOfWeek & DAY_BITS[bitIdx]) !== 0;
    }

    // Single-char letter labels for the dot grid. Pulled from the
    // localized 2-char day names via charAt(0) so we get "M T W T F S S"
    // in English, "Пн Вт Ср..." → "П В С..." in Russian, etc. Stored
    // per-row because templates inside <list-item for=> may not see
    // parent-scope variables reliably on ACE Lite.
    var dayLetters = letterArrayFromSelf(self, order);

    return {
        id: alarm.id,
        // formatAlarmTime resolves the relative-alarm placeholder hour/
        // minute (default 07:00 from the phone's picker UI) to the actual
        // fire clock-time using updatedAtEpoch + relativeMinutes. Absolute
        // alarms use the literal hour/minute. See relativeFireEpoch JSDoc.
        // 12/24h pref pulled from self._use24Hour (cached by refresh()).
        time: formatAlarmTime(alarm, self._use24Hour, self.ampmAM, self.ampmPM),
        enabled: enabled,
        // Lite Wearable if= directive does NOT coerce truthy values
        // (gotcha #8 in memory). Expose the negation pre-computed so
        // the disabled-row template can use `if="{{$item.notEnabled}}"`.
        notEnabled: !enabled,
        isRecurring: isRecurring,
        // Lite Wearable's HML `if=` may not handle `!` expressions
        // reliably — expose the negation pre-computed so the template
        // can just bind a boolean field.
        isNotRecurring: !isRecurring,
        daysText: daysText,
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

/**
 * Single-char letter labels in DAY_DISPLAY_ORDER. Pulled from the
 * localized 2-char day strings via charAt(0). Used by formatRow to
 * decorate each dot in the grid; recomputed per row (cheap — 7 chars
 * per call) for template-scope safety on ACE Lite.
 */
function letterArrayFromSelf(self, order) {
    var twoChar = [self.d0, self.d1, self.d2, self.d3, self.d4, self.d5, self.d6];
    var out = ['', '', '', '', '', '', ''];
    var ord = order || DEFAULT_DAY_DISPLAY_ORDER;
    for (var i = 0; i < 7; i++) {
        var idx = ord[i];
        var label = twoChar[idx] || '';
        out[i] = label.length > 0 ? label.charAt(0) : '';
    }
    return out;
}

/**
 * Compare two alarms for the flat list sort. Enabled rows come first
 * (true < false in our DESC convention here), then by effective clock
 * fire-time ascending so the soonest in the day shows on top.
 *
 * For relative ("Timer") alarms, the literal `hour`/`minute` fields are
 * placeholders (default 07:00) — we project them onto the actual fire
 * clock-time via relativeFireEpoch so Timers don't all clump at 07:00.
 */
function compareAlarms(a, b) {
    if (a.enabled !== b.enabled) return a.enabled ? -1 : 1;
    var aFireEpoch = relativeFireEpoch(a);
    var bFireEpoch = relativeFireEpoch(b);
    var aHour = a.hour, aMin = a.minute;
    var bHour = b.hour, bMin = b.minute;
    if (aFireEpoch !== null) {
        var da = new Date(aFireEpoch);
        aHour = da.getHours(); aMin = da.getMinutes();
    }
    if (bFireEpoch !== null) {
        var db = new Date(bFireEpoch);
        bHour = db.getHours(); bMin = db.getMinutes();
    }
    if (aHour !== bHour) return aHour - bHour;
    return aMin - bMin;
}

function formatLastSync(self, epoch) {
    if (!epoch || epoch <= 0) return self.noSyncYet;
    var ageMs = Date.now() - epoch;
    if (ageMs < 0) return self.noSyncYet; // clock skew, treat as "never"
    var ageSec = Math.floor(ageMs / 1000);
    if (ageSec < 60) return self.syncJustNow;
    var ageMin = Math.floor(ageSec / 60);
    if (ageMin < 60) return self.syncMinAgo.replace('{n}', '' + ageMin);
    var ageHr = Math.floor(ageMin / 60);
    if (ageHr < 24) return self.syncHrAgo.replace('{n}', '' + ageHr);
    var ageDay = Math.floor(ageHr / 24);
    return self.syncDayAgo.replace('{n}', '' + ageDay);
}

export default {
    data: {
        title: '',
        // Single flat list — sort by enabled-first, then hour:minute.
        // Per-row visual cues: green dot vs hollow grey ring for
        // status, dim text + dim day-dots for disabled rows. ACE Lite
        // reactivity needs full array reassignment to fire the dirty-
        // check observer; see memory:litewearable_rendering_gotchas.md.
        //
        // HML uses ONE <list-item for=> over `alarms` and switches
        // between an enabled wrapper and a disabled wrapper via inner
        // if= directives (`$item.enabled` / `$item.notEnabled`). The
        // earlier attempt to use two <list-item for=> templates inside
        // one <list> rendered NOTHING on real GT 6 hardware — Lite
        // Wearable apparently only honors a single per-list template.
        alarms: [],
        emptyText: '',
        emptyHint: '',
        lastSyncText: '',
        editOnPhoneToast: '',
        // Localized "Timer" label for relative ("in N min") alarms.
        // ORIGIN-based, not behavior-based — a relative alarm with
        // selfDestruct=false (user opted out of auto-delete) is still a
        // Timer that can be re-toggled.
        repeatTimer: '',
        // Localized "One-off" label for one-shot absolute alarms with
        // selfDestruct=true (auto-delete after firing). Distinct from
        // "Once" (plain one-shot, persists in list).
        repeatOneOff: '',
        // i18n bag — populated in onInit; passed as `self` into helpers
        // because LiteWearable JS has no easy way to read `this.$t()` from
        // outside a method.
        noSyncYet: '',
        syncJustNow: '',
        syncMinAgo: '',
        syncHrAgo: '',
        syncDayAgo: '',
        repeatOnce: '',
        repeatAll: '',
        repeatWeekdays: '',
        repeatWeekends: '',
        d0: '', d1: '', d2: '', d3: '', d4: '', d5: '', d6: '',
        ampmAM: '',
        ampmPM: '',
        _refreshTimer: null,
        // NOTE: cached display prefs (use24Hour / dayOrder) are NOT
        // declared inside `data:`. They're read by formatRow and folded
        // into the per-row state via self.alarms reassignment — that is
        // what triggers re-render, not the prefs themselves. Putting
        // them in data was found 2026-05-13 to break the dirty-check
        // observer for the whole page (no list, no empty-state). Pages
        // accept arbitrary `this.*` props alongside the data block; we
        // set `this._use24Hour` / `this._dayOrder` straight in onInit
        // and refresh().

        // timer: setTimeout(3000) isolation probe. If 'fired @+N ms' shows
        // up but the ping line hangs on 'before c.send', the JS event loop
        // is alive — the SDK callback chain is the failure point.
        timerStatusText: 'tap TEST TIMEOUT',
        timersStarted: 0,
        // store: "st:N rd:M w:<status>" + first-fail tag if present.
        storeStatusText: 'no read yet',
        // Diagnostic strip line 1: "rx:<state> raw:N in:N last:<type>@age".
        // raw = every inbound message the receiver fired for; in = handled.
        inboundStatusText: 'rx: no read yet',
        // Diagnostic strip line 2: "tx:<type>=<code> pg:index b:<tag>".
        rxDiagText: '',
        // probe: persistent storage health from onInit. Stays visible
        // alongside inbound; separate field so refreshInboundDiag doesn't
        // wipe it.
        probeStatusText: '',
        // Short BUILD_TAG suffix from app.js — displayed in the title
        // row so the user can confirm the new HAP is actually running
        // on the watch (DevEco Assistant sometimes silently fails to
        // sideload). Set in onInit.
        buildTag: '',
    },

    onInit: function () {
        this.title = this.$t('strings.app_name');
        this.emptyText = this.$t('strings.no_alarms');
        this.emptyHint = this.$t('strings.no_alarms_hint');
        this.noSyncYet = this.$t('strings.sync_never');
        this.syncJustNow = this.$t('strings.sync_just_now');
        this.syncMinAgo = this.$t('strings.sync_min_ago');
        this.syncHrAgo = this.$t('strings.sync_hr_ago');
        this.syncDayAgo = this.$t('strings.sync_day_ago');
        this.repeatOnce = this.$t('strings.repeat_once');
        this.repeatAll = this.$t('strings.repeat_every_day');
        this.repeatWeekdays = this.$t('strings.repeat_weekdays');
        this.repeatWeekends = this.$t('strings.repeat_weekends');
        this.repeatTimer = this.$t('strings.repeat_timer');
        this.repeatOneOff = this.$t('strings.repeat_oneoff');
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
        // Prime the SettingsStore cache so the first refresh tick already
        // has 12/24h + week-start values without an extra render pass.
        var self = this;
        SettingsStore.get(function (s) {
            self._use24Hour = s.use24Hour;
            self._dayOrder = computeDayOrder(s.firstDayOfWeek);
        });
        // Probe @system.file health on first load. Writes/reads a 2 KB
        // sentinel to internal://app/, plus tracks a last-run timestamp
        // across launches so we can see if files survive page navigation
        // AND watch reboots. The 128 B @system.storage cap is already
        // pinned in memory from the bisect probe — no need to re-prove.
        this.probeFile();
        // Pull short build tag from app.js (the part after the last `/`).
        // We need a fixed local copy because pages can't reach app.js
        // module state (per-bundle isolation gotcha #11). Bumped per
        // build alongside app.js's BUILD_TAG.
        this.buildTag = 'perpage-rx';
    },

    // Validates @system.file basics on real GT 6 Pro firmware:
    //   1. writeText to internal://app/probe.txt → success?
    //   2. readText same uri → returns what we wrote?
    //   3. access → success?
    //   4. write a 2 KB value (well above @system.storage's 128 B cap) and
    //      read back identically → confirms file API has no 128 B cap
    //   5. delete → success?
    //
    // ALSO writes a "last_run" sentinel at internal://app/last_run.txt
    // every app launch and reads any prior value first. The diag line then
    // shows whether the prior-run timestamp survived (across page nav
    // ALWAYS, across watch reboot if we got that far). This is the only
    // way we can verify reboot-persistence without DevEco HiLog access.
    //
    // The @system.storage 128 B cap was already pinned in memory from the
    // bisect probe — we don't re-prove it here. probeStatusText now
    // surfaces FILE API status instead.
    probeFile: function () {
        var self = this;
        var sentinelUri = 'internal://app/last_run.txt';
        var probeUri = 'internal://app/probe.txt';
        var big = '';  // 2 KB payload built below to test large value
        while (big.length < 2048) big += 'BBBBBBBBBBBBBBBB';
        big = big.substring(0, 2048);

        var nowStr = '' + Date.now();
        var prior = '';  // filled by readText of sentinel before we overwrite

        function setStatus(s) { self.probeStatusText = s; }

        function readPriorSentinel(done) {
            file.readText({
                uri: sentinelUri,
                success: function (data) {
                    prior = (data && data.text) ? data.text : '';
                    done();
                },
                fail: function () {
                    // No prior sentinel — fresh install or it was cleared.
                    prior = '';
                    done();
                },
            });
        }

        function writeSentinel(done) {
            file.writeText({
                uri: sentinelUri,
                text: nowStr,
                success: function () { done(true); },
                fail: function (data, code) {
                    setStatus('file:sentinel-WRITE fail c=' + code + ' "' +
                        truncMsg(data) + '"');
                    done(false);
                },
            });
        }

        function writeBig(done) {
            file.writeText({
                uri: probeUri,
                text: big,
                success: function () { done(true); },
                fail: function (data, code) {
                    setStatus('file:big-WRITE fail @2KB c=' + code + ' "' +
                        truncMsg(data) + '"');
                    done(false);
                },
            });
        }

        function readBig(done) {
            file.readText({
                uri: probeUri,
                success: function (data) {
                    var got = (data && data.text) ? data.text : '';
                    if (got === big) {
                        done(true);
                    } else {
                        setStatus('file:big-READ mismatch (len ' + got.length +
                            '/' + big.length + ')');
                        done(false);
                    }
                },
                fail: function (data, code) {
                    setStatus('file:big-READ fail c=' + code + ' "' +
                        truncMsg(data) + '"');
                    done(false);
                },
            });
        }

        function deleteProbe(done) {
            file.delete({
                uri: probeUri,
                success: function () { done(true); },
                fail: function (data, code) {
                    // Delete fail is non-fatal — leftover file is harmless.
                    setStatus('file:DELETE fail c=' + code + ' "' +
                        truncMsg(data) + '" (non-fatal)');
                    done(true);
                },
            });
        }

        function truncMsg(d) {
            var m = (typeof d === 'string') ? d : ('' + d);
            if (m.length > 50) m = m.substring(0, 50) + '…';
            return m;
        }

        setStatus('file:probing…');
        readPriorSentinel(function () {
            writeSentinel(function (ok) {
                if (!ok) return;
                writeBig(function (ok) {
                    if (!ok) return;
                    readBig(function (ok) {
                        if (!ok) return;
                        deleteProbe(function () {
                            // All steps succeeded. Report sentinel result.
                            var priorTag = prior
                                ? ('prev=' + prior.substring(prior.length - 6))
                                : 'prev=none';
                            setStatus('file:OK 2KB rw+del ' + priorTag);
                        });
                    });
                });
            });
        });
    },

    // Diagnostic strip. Line 1 = rx-state + raw/handled inbound counters;
    // line 2 = last send + page tag + build. raw counts EVERY message the
    // receiver fired for; in counts handled ones — the gap disambiguates
    // a receive failure from a parse/dispatch failure on a failed test.
    refreshInboundDiag: function () {
        var self = this;
        var rx = WearBridge.getReceiverState();
        var rxTxt = (rx.result === 'ok')
            ? ('ok@' + Math.floor((Date.now() - rx.atMs) / 1000) + 's')
            : rx.result;
        var snd = WearBridge.getLastSend();
        self.rxDiagText = 'tx:' + (snd.type ? (snd.type + '=' + snd.reason) : 'none') +
            ' pg:index b:' + self.buildTag;
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
                self.applyInboundLine(rxTxt, raw.count || 0);
            },
            fail: function () {
                self.applyInboundLine(rxTxt, 0);
            },
        });
    },

    applyInboundLine: function (rxTxt, rawCount) {
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
                    self.inboundStatusText = 'rx:' + rxTxt + ' diag:parse-fail';
                    return;
                }
                var last = obj.lastType ? obj.lastType : '-';
                var age = obj.lastTs
                    ? (Math.floor((Date.now() - obj.lastTs) / 1000) + 's')
                    : '-';
                self.inboundStatusText = 'rx:' + rxTxt + ' raw:' + rawCount +
                    ' in:' + (obj.total || 0) + ' last:' + last + '@' + age;
            },
            fail: function (data, code) {
                self.inboundStatusText = 'rx:' + rxTxt + ' in:diag-fail ' + code;
            },
        });
    },

    onShow: function () {
        Logger.i('index.onShow');
        var self = this;
        // Per-page receiver registration (Fix 1). Each page bundle is
        // isolated (gotcha #11); the watch's single global Wear Engine
        // subscription is serviced only in the bundle that last called
        // registerReceiver. index re-registers on every onShow so while
        // the list is foreground inbound P2P lands in THIS bundle's
        // live callback. NOT unregistered in onHide — the next page's
        // onShow re-registers; unregistering could race that.
        WearBridge.setPageTag('index');
        IncomingHandler.setOnAlarmFiredNavigator(function (alarmId) {
            Logger.i('index.onAlarmFired routing to ring id=' + alarmId);
            try {
                router.replace({ uri: 'pages/ring/ring', params: { alarmId: alarmId } });
            } catch (e) {
                Logger.err('index.onAlarmFired router.replace', e);
            }
        });
        IncomingHandler.setOnPeerEndedRing(function (alarmId) {
            // No ring page is up while index is foreground — nothing to
            // close. Logged only, for the diagnostic timeline.
            Logger.i('index.onPeerEndedRing noop id=' + alarmId);
        });
        // index deliberately leaves onSyncDone unset — a live force-sync
        // while the user is on the list must NOT terminate the app.
        WearBridge.setIncomingHandler(function (msg) {
            try {
                IncomingHandler.handle(msg);
            } catch (e) {
                Logger.err('index.incomingHandler', e);
            }
        });
        // Force a fresh array reference BEFORE refresh fills it. ACE Lite
        // page state survives router.replace navigation — if we came back
        // from ring.js, `self.alarms` may still hold stale rows from the
        // prior visit, AND the dirty-check observer may be wired to the
        // OLD array reference. Clearing now and reassigning a fresh array
        // in refresh() guarantees the observer sees a new identity and
        // re-evaluates the <list-item for=> binding.
        self.alarms = [];
        AlarmStore.setOnChange(function () {
            self.refresh();
            self.refreshInboundDiag();
        });
        this.refresh();
        this.refreshInboundDiag();
        // Refresh every 2 s. Two reasons:
        //   1. IncomingHandler runs in a different webpack bundle than this
        //      page (gotcha #11), so the AlarmStore.setOnChange listener
        //      it would fire is bound to a DIFFERENT module copy and
        //      cannot reach our `self.refresh`. Polling is the reliable
        //      cross-bundle signal — file.readText returns fresh content
        //      no matter who wrote it.
        //   2. Sub-second sync feedback on the user-visible alarm list.
        // The read is cheap (one file.readText of <few-KB JSON), so 2 s
        // cadence is well within the JS event-loop budget.
        if (this._refreshTimer === null) {
            this._refreshTimer = setInterval(function () {
                self.refresh();
                self.refreshInboundDiag();
            }, 2000);
        }
    },

    onHide: function () {
        Logger.i('index.onHide');
        AlarmStore.setOnChange(null);
        if (this._refreshTimer !== null) {
            clearInterval(this._refreshTimer);
            this._refreshTimer = null;
        }
        // Drain the batched log relay — its flush timer is foreground-only.
        Logger.flushNow();
    },

    refresh: function () {
        var self = this;
        // Re-pull settings every tick so a phone-pushed settings_changed
        // envelope re-renders the next refresh cycle without restart.
        // SettingsStore.get is cache-fast after first call.
        SettingsStore.get(function (s) {
            self._use24Hour = s.use24Hour;
            self._dayOrder = computeDayOrder(s.firstDayOfWeek);
        });
        AlarmStore.getAll(function (items) {
            // ACE Lite uses dirty-check reactivity (Angular 1-style), NOT
            // Vue 2-style array-mutator hooks. Full reassignment of a new
            // array reference is required for the dirty-check to fire.
            // See memory:litewearable_rendering_gotchas.md "Data reactivity".
            //
            // Sort enabled-first for visual scanability. Disabled rows
            // appear at the bottom dimmed; the status-dot tells the user
            // the state at a glance.
            var sorted = items.slice();
            sorted.sort(compareAlarms);
            var rows = [];
            var enabledCount = 0;
            for (var i = 0; i < sorted.length; i++) {
                if (sorted[i].enabled) enabledCount++;
                rows.push(formatRow(self, sorted[i]));
            }
            self.alarms = rows;
            self.storeStatusText = 'st:' + items.length +
                ' on:' + enabledCount + ' off:' + (items.length - enabledCount);
            Logger.i('index.refresh n=' + items.length + ' on=' + enabledCount);
        });
        AlarmStore.getLastSyncEpoch(function (epoch) {
            self.lastSyncText = formatLastSync(self, epoch);
        });
    },

    // Tap on any alarm row. List is info-only — alarms are managed on
    // phone (per architecture: phone is sole scheduler, watch is thin
    // display). Show a brief toast prompting the user back to the phone.
    onRowTap: function (id) {
        try {
            prompt.showToast({
                message: this.editOnPhoneToast,
                duration: 1500,
            });
        } catch (e) {
            Logger.err('onRowTap toast', e);
        }
        Logger.i('index.onRowTap id=' + id);
    },

    // Isolation probe — does setTimeout actually fire on Lite Wearable?
    // The PING button's 3-second SDK timeout (SEND_TIMEOUT_MS in wearBridge)
    // has been observed to NOT fire on real GT 6 Pro: the status line gets
    // stuck on 'before c.send' or 'after c.send' forever. Two possible
    // causes:
    //   (a) c.send is synchronously blocking the JS thread — setTimeout
    //       never gets a chance to run. Multi-tap reaching #8 makes this
    //       unlikely but doesn't rule out a foreground-only suspension.
    //   (b) setTimeout is broken/unreliable on Lite Wearable.
    // This handler fires a standalone setTimeout(3000) — no SDK involved.
    // If 'fired @+~3000 ms' appears, setTimeout works and the bug is in
    // the SDK callback chain. If it sticks on 'pending', setTimeout itself
    // is broken and our timeout safety net never fires.
    onTestTimeout: function () {
        var self = this;
        self.timersStarted = self.timersStarted + 1;
        var n = self.timersStarted;
        var t0 = Date.now();
        self.timerStatusText = '#' + n + ' pending';
        setTimeout(function () {
            var elapsed = Date.now() - t0;
            self.timerStatusText = '#' + n + ' fired @+' + elapsed + 'ms';
        }, 3000);
    },

};
