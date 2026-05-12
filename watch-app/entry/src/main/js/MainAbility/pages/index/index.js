import storage from '@system.storage';
import file from '@system.file';
import prompt from '@system.prompt';
import AlarmStore from '../../common/alarmStore.js';
import WearBridge from '../../common/wearBridge.js';
import Logger from '../../common/logger.js';

var DAY_BITS = [1, 2, 4, 8, 16, 32, 64];

// Display order for the day-dot grid. Indexed slot 0..6 ↔ DAY_BITS[i].
// Monday-first per user pref (task #68 will make this configurable);
// for now hardcoded to Mon Tue Wed Thu Fri Sat Sun → bits 2,4,8,16,32,64,1.
var DAY_DISPLAY_ORDER = [1, 2, 3, 4, 5, 6, 0];

function pad2(n) {
    return (n < 10 ? '0' : '') + n;
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

    // 7 day-dot booleans in display order (Monday-first per user pref,
    // see DAY_DISPLAY_ORDER + task #68). dN points at the bit slot
    // visible in column N; the HML template binds dNOn / dNOff.
    var dayBits = [false, false, false, false, false, false, false];
    for (var i = 0; i < 7; i++) {
        var bitIdx = DAY_DISPLAY_ORDER[i];
        dayBits[i] = (alarm.daysOfWeek & DAY_BITS[bitIdx]) !== 0;
    }

    // Single-char letter labels for the dot grid. Pulled from the
    // localized 2-char day names via charAt(0) so we get "M T W T F S S"
    // in English, "Пн Вт Ср..." → "П В С..." in Russian, etc. Stored
    // per-row because templates inside <list-item for=> may not see
    // parent-scope variables reliably on ACE Lite.
    var dayLetters = letterArrayFromSelf(self);

    return {
        id: alarm.id,
        time: pad2(alarm.hour) + ':' + pad2(alarm.minute),
        enabled: enabled,
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
function letterArrayFromSelf(self) {
    var twoChar = [self.d0, self.d1, self.d2, self.d3, self.d4, self.d5, self.d6];
    var out = ['', '', '', '', '', '', ''];
    for (var i = 0; i < 7; i++) {
        var idx = DAY_DISPLAY_ORDER[i];
        var label = twoChar[idx] || '';
        out[i] = label.length > 0 ? label.charAt(0) : '';
    }
    return out;
}

/**
 * Compare two alarms for the flat list sort. Enabled rows come first
 * (true < false in our DESC convention here), then by hour:minute
 * ascending so the soonest in the day shows on top.
 */
function compareAlarms(a, b) {
    if (a.enabled !== b.enabled) return a.enabled ? -1 : 1;
    if (a.hour !== b.hour) return a.hour - b.hour;
    return a.minute - b.minute;
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
        // The earlier two-section split (Upcoming/Others) is replaced
        // by per-row visual cues: green dot vs hollow grey ring for
        // status, dim text + dim day-dots for disabled rows. ACE Lite
        // reactivity needs full array reassignment to fire the dirty-
        // check observer; see memory:litewearable_rendering_gotchas.md.
        //
        // `alarms` is the combined array, retained for the `if=
        // "{{alarms.length > 0}}"` empty-check. The HML renders two
        // separate <list-item for=> templates because Lite Wearable
        // HML rejects class data binding (verified 2026-05-12) — so we
        // split rows into enabledAlarms/disabledAlarms here and bind
        // each to its own fixed-class template.
        alarms: [],
        enabledAlarms: [],
        disabledAlarms: [],
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
        _refreshTimer: null,

        // Debug diagnostics — shown under the PING PHONE button to give
        // user direct visibility into watch→phone P2P health AND the
        // AlarmStore state. No Logger.i / DevEco HiLog needed.
        pingStatusText: 'tap to test',
        pingsSent: 0,
        pingsOk: 0,
        // timer: setTimeout(3000) isolation probe. If 'fired @+N ms' shows
        // up but the ping line hangs on 'before c.send', the JS event loop
        // is alive — the SDK callback chain is the failure point.
        timerStatusText: 'tap TEST TIMEOUT',
        timersStarted: 0,
        // store: "st:N rd:M w:<status>" + first-fail tag if present.
        storeStatusText: 'no read yet',
        // inbound: "in:N last:<type>" — cross-bundle counter via storage.
        inboundStatusText: 'in: no read yet',
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
        this.buildTag = 'oneoff';
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

    refreshInboundDiag: function () {
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
                    self.inboundStatusText = 'diag:parse-fail';
                    return;
                }
                var line = 'in:' + (obj.total || 0);
                if (obj.lastType) line = line + ' last:' + obj.lastType;
                self.inboundStatusText = line;
            },
            fail: function (data, code) {
                self.inboundStatusText = 'diag:read-fail ' + code;
            },
        });
    },

    onShow: function () {
        Logger.i('index.onShow');
        var self = this;
        // Force a fresh array reference BEFORE refresh fills it. ACE Lite
        // page state survives router.replace navigation — if we came back
        // from ring.js, `self.alarms` may still hold stale rows from the
        // prior visit, AND the dirty-check observer may be wired to the
        // OLD array reference. Clearing now and reassigning a fresh array
        // in refresh() guarantees the observer sees a new identity and
        // re-evaluates the <list-item for=> binding.
        self.alarms = [];
        self.enabledAlarms = [];
        self.disabledAlarms = [];
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
    },

    refresh: function () {
        var self = this;
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
            var enabledRows = [];
            var disabledRows = [];
            for (var i = 0; i < sorted.length; i++) {
                var row = formatRow(self, sorted[i]);
                rows.push(row);
                if (row.enabled) enabledRows.push(row);
                else disabledRows.push(row);
            }
            self.alarms = rows;
            self.enabledAlarms = enabledRows;
            self.disabledAlarms = disabledRows;
            var enabledCount = enabledRows.length;
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

    // Diagnostic: fire a single watch_log message at the phone with a
    // result callback. The status line below the button updates with the
    // SDK's onSendResult code:
    //   207 = COMM_SUCCESS — watch's send call delivered. If phone's adb
    //         logcat still shows no WatchLog entry, phone is rejecting at
    //         the validation layer (PEER_FP mismatch most likely).
    //   206 = COMM_FAIL — watch's send didn't deliver. PHONE_CERT_SHA256
    //         on watch side wrong, or BT channel down.
    //   err — onFailure callback fired before onSendResult (transport-
    //         level error).
    onPingPhone: function () {
        var self = this;
        self.pingsSent = self.pingsSent + 1;
        var n = self.pingsSent;
        // Update pingStatusText on every step so user can see WHERE the
        // SDK call stalls when callbacks never fire. Trace events come
        // from sendOnceWithResult: ensureClient → buildMsg → schedTimeout
        // → before c.send → after c.send → (onSuccess/onFailure/
        // onSendResult c=N or TIMEOUT fired) → settle.
        self.pingStatusText = '#' + n + ': start';
        WearBridge.sendPing(
            function (success, reason) {
                if (success) self.pingsOk = self.pingsOk + 1;
                self.pingStatusText = '#' + n + ' DONE ' + reason +
                    ' (ok ' + self.pingsOk + '/' + self.pingsSent + ')';
            },
            function (step) {
                self.pingStatusText = '#' + n + ': ' + step;
            }
        );
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
