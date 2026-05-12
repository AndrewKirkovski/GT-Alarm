import storage from '@system.storage';
import file from '@system.file';
import prompt from '@system.prompt';
import AlarmStore from '../../common/alarmStore.js';
import WearBridge from '../../common/wearBridge.js';
import Logger from '../../common/logger.js';

var DAY_BITS = [1, 2, 4, 8, 16, 32, 64];

function pad2(n) {
    return (n < 10 ? '0' : '') + n;
}

function daysSummary(self, alarm) {
    // Three one-shot labels:
    //   - "Timer"   — relative ("in N min") origin. Detected via the
    //                 presence of relativeMinutes on the wire (Task #47
    //                 will start sending it; until then this branch is
    //                 inert and all relative alarms show "One-off").
    //   - "One-off" — one-shot absolute with self-destruct (fire once
    //                 then vanish). Auto-delete is the distinguishing
    //                 feature.
    //   - "Once"    — plain one-shot, persists in list after firing as a
    //                 re-arm-able template.
    if (alarm.daysOfWeek === 0 && alarm.relativeMinutes) return self.repeatTimer;
    if (alarm.daysOfWeek === 0 && alarm.selfDestruct) return self.repeatOneOff;
    if (!alarm.daysOfWeek || alarm.daysOfWeek === 0) return self.repeatOnce;
    if (alarm.daysOfWeek === 127) return self.repeatAll;
    if (alarm.daysOfWeek === 62) return self.repeatWeekdays; // Mo-Fr
    if (alarm.daysOfWeek === 65) return self.repeatWeekends; // Sun+Sat
    var labels = [self.d0, self.d1, self.d2, self.d3, self.d4, self.d5, self.d6];
    var out = [];
    for (var i = 0; i < 7; i++) {
        if ((alarm.daysOfWeek & DAY_BITS[i]) !== 0) out.push(labels[i]);
    }
    return out.join(' ');
}

function formatRow(self, alarm) {
    return {
        id: alarm.id,
        time: pad2(alarm.hour) + ':' + pad2(alarm.minute),
        daysText: daysSummary(self, alarm),
        enabled: !!alarm.enabled,
        // Class hints for the HML — let the row use a distinct color cue
        // for timers (relative origin). Lit only when the relativeMinutes
        // field is present in the wire payload (Task #47).
        isTimer: alarm.daysOfWeek === 0 && !!alarm.relativeMinutes,
    };
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
        // Two-section list per spec: Upcoming = enabled alarms, Others =
        // disabled. Split here so the HML can render two <list> blocks
        // bound to separate arrays without filter-in-template logic.
        // ACE Lite reactivity needs full array reassignment on update;
        // see memory:litewearable_rendering_gotchas.md.
        upcomingAlarms: [],
        othersAlarms: [],
        emptyText: '',
        emptyHint: '',
        lastSyncText: '',
        sectionUpcoming: '',
        sectionOthers: '',
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
        this.sectionUpcoming = this.$t('strings.section_upcoming');
        this.sectionOthers = this.$t('strings.section_others');
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
        // from ring.js, `self.upcomingAlarms` may still hold stale rows
        // from the prior visit, AND the dirty-check observer may be wired
        // to the OLD array reference. Clearing now and reassigning a fresh
        // array in refresh() guarantees the observer sees a new identity
        // and re-evaluates the <list-item for=> binding.
        self.upcomingAlarms = [];
        self.othersAlarms = [];
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
            var upcoming = [];
            var others = [];
            for (var i = 0; i < items.length; i++) {
                var row = formatRow(self, items[i]);
                if (row.enabled) {
                    upcoming.push(row);
                } else {
                    others.push(row);
                }
            }
            self.upcomingAlarms = upcoming;
            self.othersAlarms = others;
            self.storeStatusText = 'st:' + items.length +
                ' up:' + upcoming.length + ' ot:' + others.length;
            Logger.i('index.refresh up=' + upcoming.length +
                ' ot=' + others.length);
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
