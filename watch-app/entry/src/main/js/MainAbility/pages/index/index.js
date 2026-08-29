// Single-page watch app. ONE page, ONE bundle, ONE Wear Engine P2P
// receiver registered once and never re-navigated away from. The three
// "screens" — list / sync / ring — are `if=`-rendered blocks switched
// by the `screen` data field. No router: the fragmentation and
// context-teardown of the old 3-page design is gone.
import app from '@system.app';
import brightness from '@system.brightness';
import storage from '@system.storage';
import file from '@system.file';
import vibrator from '@system.vibrator';
import AlarmStore from '../../common/alarmStore.js';
import SettingsStore from '../../common/settingsStore.js';
import WearBridge from '../../common/wearBridge.js';
import IncomingHandler from '../../common/incomingHandler.js';
import Logger from '../../common/logger.js';
import Screen from '../../common/screen.js';
import PrivacyStore from '../../common/privacyStore.js';
import ConnectionStore from '../../common/connectionStore.js';

// Bump per hardware-test build so the on-screen tag confirms the new
// HAP actually replaced the old one.
var BUILD_TAG = 'ringfix-screenlock-live';

// Startup sequence (deterministic): get size → set responsive dims → show
// splash (bg + centred hourglass) → load the rest (i18n/privacy/connection/
// alarms) → reveal content. SIZE is the critical hard gate — NOTHING renders
// before `sized` (the AppGallery flicker rejection was a pre-size reflow).
// Two safety nets for the pathological cases:
//   SIZED_SAFETY_MS  — getInfo never returns → apply default dims so the
//                      splash + load still proceed.
//   REVEAL_SAFETY_MS — the cold-start load stalls → reveal anyway rather than
//                      sit on the splash forever (normal reveal ~2–2.5 s,
//                      dominated by the ACELite $t i18n cost).
var SIZED_SAFETY_MS = 1200;
var REVEAL_SAFETY_MS = 5000;

// i18n strings load per-key in loadStrings() via explicit this.$t('strings.<key>')
// calls. Per-key literal $t() is the proven snapshot-safe form on ACELite — a
// $t('strings') sub-object fetch or nested named functions break install (err 10).

// How long the row-tap hint overlay stays up.
var TAP_HINT_MS = 2600;

// @system.storage key holding the on-watch path of the last P2P-received
// background file. The file itself persists in the app sandbox across
// restarts; this remembers WHERE so a relaunch can re-point <image> at
// it without waiting for another upload. Path is ~36 B, well under the
// 128 B storage cap.
var BG_PATH_KEY = 'bg_path';
// F4: @system.storage key holding the content hash of the default background
// this watch currently holds, parsed out of the received file name
// `bgd_<hash>.bin`. Reported back to the phone in the watch_screen reply so it
// can auto-restore a lost image / never re-send a stale one. MUST match
// BG_HASH_KEY in common/incomingHandler.js (separate bundles, shared storage).
// ~8 chars, well under the 128 B storage cap.
var BG_HASH_KEY = 'bg_hash';
// Sentinel stored in BG_PATH_KEY when the phone clears the default background.
// Writing '' did NOT persist on the watch runtime (empty values don't stick),
// so the cleared state must be a real non-empty string the restore ignores.
var BG_CLEARED = '__cleared__';
// Shipped default backdrop — a dark brand gradient (tools: .local gen script).
// Small 240x240 source; the bg-full <image> upscales it to each screen
// (a smooth gradient shows no upscale artifacts), keeping the bundled BGRA
// bitmap small. Shown whenever no custom P2P background is set, and as the
// loading splash so the first frame our app paints is branded, not flat
// dark. Bundled PNG (the build pre-decodes it to BGRA, like the ring icons;
// runtime-RECEIVED images still require the BGRA .bin path).
var DEFAULT_BG = 'common/default-bg.png';

var DAY_BITS = [1, 2, 4, 8, 16, 32, 64];
var DEFAULT_DAY_ORDER = [1, 2, 3, 4, 5, 6, 0];
var VIBRATE_INTERVAL_MS = 1500;

// Vibration-pattern presets — mirrors VibrationPattern.kt on the phone.
// Each step is { mode: 'short'|'long', delayAfterMs: number }. The
// orchestrator calls vibrator.vibrate({mode}) then waits delayAfterMs
// before the next step. The whole sequence loops while the ring page
// is visible. ES5 only — plain object literal, no const/arrow.
var VIBRATION_PATTERNS = {
    PULSE:     [ { mode: 'long',  delay: 1500 } ],
    HEARTBEAT: [ { mode: 'short', delay: 150 }, { mode: 'short', delay: 1000 } ],
    THREE_TAP: [ { mode: 'short', delay: 200 }, { mode: 'short', delay: 200 }, { mode: 'short', delay: 1200 } ],
    LONG_LONG: [ { mode: 'long',  delay: 800 }, { mode: 'long',  delay: 1200 } ],
    OFF:       [],
};
var PEER_FLASH_MS = 1200;

// Ring auto-terminate. The watch was woken for the alarm; once the
// action settles, close the app instead of sitting on the watch face.
var TERMINATE_HARD_CAP_MS = 6000;
// Longer cap for the dismiss/snooze paths: those fire a watch->phone
// retry chain (wearBridge.sendJsonWithRetry) whose worst case is
// maxAttempts(3) x SEND_TIMEOUT_MS(3000) + 2 x 500ms retry gap = 10s.
// The cap MUST exceed that — otherwise app.terminate() fires mid-chain
// and the un-sent retries are lost (Lite setTimeout is foreground-only,
// and the process is gone). Keep this in lock-step with wearBridge.js's
// SEND_TIMEOUT_MS / retry-count if either changes.
var TERMINATE_RETRY_CAP_MS = 11000;
// Long enough that the ring's DISMISSED/SNOOZED confirmation (shown for
// PEER_FLASH_MS) stays visible before the watch auto-closes.
var TERMINATE_AFTER_ACK_MS = 1400;
var _terminateScheduled = false;
var _terminateTimer = null;
// Crown-to-snooze (ring screen). The crown only drives a focused
// <list>'s scroll — raw rotation is not exposed — so a hidden, always-
// scrollable <list> on the ring screen turns the crown into a stream of
// `scrollend` ticks. Those are debounced into gestures (like single/
// double click): ticks <CROWN_GAP_MS apart are one continuous rotation;
// one rotation then quiet for CROWN_DOUBLE_MS -> snooze; a second
// rotation starting within that window -> dismiss. CROWN_GAP_MS and
// CROWN_DOUBLE_MS are device-tuning knobs.
var CROWN_ITEM_COUNT = 80;
var CROWN_MID_INDEX = 40;
var CROWN_GAP_MS = 250;
var CROWN_DOUBLE_MS = 550;
var CROWN_SETTLE_MS = 150;
// Safety net: if a wake-by-sync lands us on the 'sync' screen but no sync_done
// ever arrives (e.g. a bare bg-clear / settings envelope woke us), never strand
// the UI — terminate after this fallback. A real forceSync round-trip ends in
// sync_done well under this.
var SYNC_FALLBACK_MS = 8000;

function fireTerminate(tag) {
    Logger.i('term ' + tag);
    try {
        app.terminate();
    } catch (e) {
        Logger.err('term', e);
    }
}

function scheduleTerminate(reason, capMs) {
    if (_terminateScheduled) return;
    _terminateScheduled = true;
    _terminateTimer = setTimeout(function () {
        fireTerminate('cap:' + reason);
    }, capMs || TERMINATE_HARD_CAP_MS);
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

// ACELite populates `$refs` only for refs that are CURRENTLY rendered. Every
// ref on this page lives inside an `if=`-gated screen block, so while a screen
// that renders none of them is up, `this.$refs` is `undefined` — NOT an empty
// object. JerryScript throws on a property read off undefined, and because the
// ref sites sit on the teardown path (`_disarmCrownGesture`, reached from
// onPeerEnded / onHide) that throw escapes the inbound-message handler and
// takes the page down.
//
// Observed on device 2026-08-28: `alarm_dismissed` arriving while the watch was
// back on the list screen logged "index.handle Cannot read property 'crownList'
// of undefined", so the watch never sent its app-level ack, the phone burned
// both retry rounds (`no app-ack within 4000ms round=1/2`) and gave up with
// `acked=false`, leaving AlarmActivity in "waiting for watch" until it timed out.
//
// Guard the container, not just the entry. Same class of bug as the orphaned
// `len` read — see memory:watch_inbound_len_orphan_bug.
function safeRef(vm, name) {
    var refs = vm.$refs;
    if (!refs) return null;
    return refs[name] || null;
}

function pad2(n) {
    return (n < 10 ? '0' : '') + n;
}

// F4: parse the content hash out of a received bg file path/name shaped like
// `.../bgd_<hash>.bin` (the phone names the Wear Engine file after the hash).
// Returns the hash string, or '' when the name doesn't match the convention
// (older phone build / unexpected name) — '' tells the phone "I hold a bg but
// don't know its marker", which it treats as a mismatch and re-uploads.
// Module-level (snapshot-safe), plain ES5 string ops only.
function parseBgHash(path) {
    if (typeof path !== 'string' || path.length === 0) return '';
    var slash = path.lastIndexOf('/');
    var name = (slash >= 0) ? path.substring(slash + 1) : path;
    var prefix = 'bgd_';
    if (name.substring(0, prefix.length) !== prefix) return '';
    var rest = name.substring(prefix.length);
    var dot = rest.indexOf('.');
    var hash = (dot >= 0) ? rest.substring(0, dot) : rest;
    return hash;
}

// Hash echoed back to the phone as the alarms_received ack — parsed from a
// file-delivered full replace named `alarms_<hash>.json`. Mirrors parseBgHash.
function parseAlarmsHash(path) {
    if (typeof path !== 'string' || path.length === 0) return '';
    var slash = path.lastIndexOf('/');
    var name = (slash >= 0) ? path.substring(slash + 1) : path;
    var prefix = 'alarms_';
    if (name.substring(0, prefix.length) !== prefix) return '';
    var rest = name.substring(prefix.length);
    var dot = rest.indexOf('.');
    return (dot >= 0) ? rest.substring(0, dot) : rest;
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
    // selfDestruct reads "Once" (identical phone + watch). A plain
    // one-time alarm shows no label at all — the absent day grid already
    // says "fires once", so a redundant word just adds noise.
    if (alarm.selfDestruct) return self.repeatOnce;
    return '';
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
        // Responsive geometry (computed in applyScreen) carried per-row so the
        // <list-item> inline-style bindings use the proven $item mechanism.
        rowItemW: self.rowItemW,
        rowW: self.rowW,
        rowMargin: self.rowMargin,
        timeW: self.timeW,
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

export default {
    data: {
        // 'list' | 'sync' | 'ring' | 'privacy'
        screen: 'list',

        // SIZE is the critical root gate (the AppGallery flicker rejection was
        // a pre-size reflow): NOTHING renders until `sized` (real dims known).
        // Set in applyScreen.
        sized: false,
        // Cold-start load complete (dims + i18n + privacy + connection) — gates
        // ONLY the list/privacy screens. Set in maybeReveal. Ring/sync gate on
        // `sized` alone so an alarm shows at once, not waiting for the ~2 s i18n.
        contentReady: false,
        // Gates the splash (bg + centred hourglass): true from applyScreen
        // (once sized) until maybeReveal or an event (ring/sync) takes over.
        showSplash: false,

        // --- list screen ---
        title: '',
        alarms: [],
        emptyText: '',
        emptyHint: '',

        // --- privacy consent screen (first launch; rule 7.5) ---
        privacyTitle: '', privacyBody: '', privacyScan: '',
        privacyAgree: '', privacyDecline: '', privacyQr: '',

        // --- onboarding (empty / never-connected) ---
        // hasConnected drives the empty-state copy: never-connected shows
        // "install the companion" + an AppGallery QR; connected-but-empty
        // shows the plain "add alarms on the phone" hint. Both show the QR.
        onbScan: '',
        hasConnected: false,

        // --- background photo ---
        // bgSrc: on-watch path of a P2P-received PNG, fed straight into
        // the full-screen <image src> with NO decode/copy. showScrim
        // gates the 50% dimming layer (list/sync screens only — the ring
        // screen shows the photo at full strength).
        bgSrc: '',
        // hasBg MUST be a real boolean — HML `if=` does NOT coerce a
        // truthy string, so `if="{{bgSrc}}"` never rendered the <image>.
        hasBg: false,
        showScrim: false,
        // Always-on backdrop src: the custom bg if received, else the shipped
        // default gradient. Set in updateScrim(); defaults to the gradient so
        // the very first paint (and the no-custom-bg case) is branded.
        splashBg: 'common/default-bg.png',

        // --- row-tap hint overlay ---
        tapHint: '',
        tapHintShown: false,

        // --- ring screen ---
        ringTitle: '',
        ringTime: '--:--',
        // Placeholder rows for the hidden crown-capture <list> (filled in
        // onInit) — the list needs scrollable content for the crown to
        // produce scroll events.
        crownItems: [],
        // `ended` — set once the alarm is dismissed/snoozed (by phone OR
        // watch). Swaps the arc green and replaces the action buttons
        // with the thumbs-up confirmation image.
        ended: false,
        // Phone owns snoozeMinutes; we hide the Snooze button + suppress
        // implicit-snooze + crown-snooze when 0. Default true is safe
        // for an unknown alarm (better to allow snooze than block it).
        ringSnoozeEnabled: true,
        // VibrationPattern name resolved from the synced alarm at ring time.
        // Falls back to PULSE when the alarm isn't in the store yet (thin-
        // client envelope without prior sync).
        ringVibrationPattern: 'PULSE',
        ringWatchVibrationEnabled: true,

        // --- responsive layout (computed in onInit from @system.device) ---
        // Defaults = GT 6 Pro (466x466 round). applyScreen() recomputes these
        // from the REAL screen so rectangular watches (e.g. FIT 5 Pro 480x408)
        // fill + centre instead of rendering a 466 box in a corner. Bound via
        // inline style in index.hml — `%` does NOT propagate on Lite, computed
        // px via data-bound `style="..."` does.
        screenW: 466,
        screenH: 466,
        isRound: true,
        listH: 320,
        // Empty / onboarding block fills the FULL screen (the header is
        // hidden when empty) so its content centres clear of the round
        // chords — otherwise the bottom scan line clips on a round face.
        emptyH: 466,
        arcCx: 233,
        arcCy: 233,
        arcR: 220,
        padTop: 24,
        padBot: 56,
        // Responsive list-row geometry. Defaults = GT6 (the previous hard-
        // coded CSS values). On narrower watches (FIT 408 / FIT2 336) the
        // row + time box shrink so content never clips the right edge; the
        // day-grid stays fixed (legibility). Bound per-row via $item so the
        // binding uses the same proven mechanism as $item.time. titleW is
        // page-level (not in a loop) so it binds page data directly.
        titleW: 420,
        rowItemW: 466,
        rowW: 390,
        rowMargin: 38,
        timeW: 204,

        // --- i18n bag (read by helpers via `self`) ---
        repeatOnce: '', repeatTimer: '',
        repeatAll: '', repeatWeekdays: '', repeatWeekends: '',
        noSyncYet: '', syncJustNow: '', syncMinAgo: '',
        syncHrAgo: '', syncDayAgo: '', editOnPhoneToast: '',
        d0: '', d1: '', d2: '', d3: '', d4: '', d5: '', d6: '',
        ampmAM: '', ampmPM: '',

        // non-reactive scratch — not bound in HML
        // Cold-start load latches; maybeReveal() flips contentReady when all set.
        _onInitMs: 0,
        // Reliable startup timing (Date.now() captured on the watch at each
        // step; emitted as ONE consolidated line at contentReady so the log
        // relay can't drop/reorder individual marks).
        _tApplyScreen: 0,
        _tLoadRestStart: 0,
        _tLoadStringsStart: 0,
        _tLoadStringsEnd: 0,
        _i18nDone: false,
        _privacyDone: false,
        _connDone: false,
        // One-shot guard so startLoadRest() runs once (applyScreen + the
        // sized-safety could both reach it).
        _loadRestStarted: false,
        // Default true → a forced reveal (safety timeout) with consent still
        // unresolved fails SAFE to showing the consent gate, never skipping it.
        needsConsent: true,
        // Cached i18n copy for the empty-state variants (see _applyEmptyCopy).
        _noAlarms: '', _noAlarmsHint: '', _onbTitle: '', _onbHint: '',
        _alarmId: 0,
        _explicitAction: null,
        _userEngaged: false,
        _refreshTimer: null,
        _vibrateTimer: null,
        _tapHintTimer: null,
        _syncTimer: null,
        _use24Hour: null,
        _dayOrder: DEFAULT_DAY_ORDER,
        _crownGestureCount: 0,
        _crownActive: false,
        _crownCommitted: false,
        _crownIgnoreScroll: false,
        _crownGapTimer: null,
        _crownDecideTimer: null,
    },

    onInit: function () {
        var self = this;
        // Timing anchor for the applyScreen / loadStrings deltas.
        this._onInitMs = Date.now();
        // SIZE safety: if getInfo never returns, apply default dims so the
        // splash + load still proceed (nothing renders before `sized`).
        setTimeout(function () {
            if (!self.sized) {
                Logger.w('index.sized-safety — forcing default dims');
                self.applyScreen({ W: 466, H: 466, shape: 'circle' });
            }
        }, SIZED_SAFETY_MS);
        // REVEAL safety: if the cold-start load stalls, reveal anyway rather
        // than sit on the splash forever.
        setTimeout(function () {
            self.maybeReveal(true);
        }, REVEAL_SAFETY_MS);
        // Fill the hidden crown-capture <list> with placeholder rows so it
        // always has scrollable content (see the CROWN_* constants).
        var crownFill = [];
        for (var ci = 0; ci < CROWN_ITEM_COUNT; ci++) {
            crownFill.push(ci);
        }
        this.crownItems = crownFill;
        // Restore a previously-received custom background FIRST (before the
        // other async kickoffs) so the loading splash shows the user's
        // photo, not the default gradient. updateScrim() recomputes splashBg →
        // the custom bg the moment it's restored; WITHOUT it splashBg stays at
        // the default gradient and the splash shows the gradient even when a
        // custom bg exists. The default gradient is only the fallback for when
        // no custom bg has ever been received.
        storage.get({
            key: BG_PATH_KEY,
            default: '',
            success: function (v) {
                Logger.i('index.bgPath get=[' + v + ']');
                if (v && v !== BG_CLEARED) {
                    // F4: the persisted path could point at a file that no
                    // longer exists (watch reset/reinstall wiped the sandbox
                    // but a stale path lingered, or the file was never
                    // delivered). Verify with @system.file BEFORE restoring; if
                    // it's gone, clear both markers so the next watch_screen
                    // reply reports bg='' and the phone re-uploads the image.
                    self.verifyBgFile('' + v);
                }
            },
            fail: function () {},
        });
        // DIAGNOSTIC (temporary): read BG_HASH_KEY at cold start to find why
        // watch_screen reports bg=''. If a fresh cold session reads '' for a
        // hash a PRIOR session logged as 'persisted', @system.storage is NOT
        // durably flushing before this thin client terminates. If it reads the
        // hash, the '' is purely the handshake-before-file ordering.
        storage.get({
            key: BG_HASH_KEY,
            default: '',
            success: function (h) {
                Logger.i('index.coldstart bgHash=[' + h + ']');
            },
            fail: function (d, c) {
                Logger.i('index.coldstart bgHash FAIL c=' + c);
            },
        });
        // i18n + privacy + connection + alarms all load in startLoadRest(),
        // which applyScreen() kicks off AFTER size is known and the splash has
        // painted. The ~35 per-key $t() lookups (~1.8 s on ACELite) are the long
        // pole — they run behind the visible splash, not on the first-paint path.

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

        // F3 watch-audio experiment REMOVED (2026-06-24). Verdict: @system.audio
        // resolves on the GT 6 Lite Wearable (getPlayState works) but its `src`
        // setter is a NO-OP — readback + getPlayState.src are always empty, so no
        // source can be loaded and every play() instantly fires onerror
        // (errorType undefined) with state stuck at 'stop'. Playback is not
        // achievable; the watch buzzes (vibrator) for alarms instead. See memory.

        // Responsive layout: query the real screen size + shape and recompute
        // every screen-dependent dimension. getInfo is async (callback-only on
        // Lite); the first render uses the 466 defaults, then this corrects
        // them — a no-op on the GT6 (466==466), a reflow-to-fit on rect watches.
        Screen.load(function (m) {
            self.applyScreen(m);
        });

        // (Privacy consent + connection state load in startLoadRest, AFTER
        // size is known and the splash is up — see applyScreen.)

        // Incoming-envelope -> screen transitions. One page, so the
        // handler updates this page's data directly.
        IncomingHandler.setOnAlarmFiredNavigator(function (alarmId, ringHints) {
            // ringHints (optional) = inline thin-client envelope fields —
            // vibrationPattern + snoozeAllowed — so the watch rings with
            // the right pattern + Snooze gating WITHOUT prior sync_replace.
            self.enterRing(alarmId, ringHints);
        });
        IncomingHandler.setOnPeerEndedRing(function (alarmId, type) {
            self.onPeerEnded(alarmId, type);
        });
        // Phone cleared the default watch background. Persist the cleared
        // sentinel FIRST so a relaunch can't restore the old path even if a
        // later call throws; then drop it from the UI and best-effort delete
        // the received file.
        IncomingHandler.setOnBgCleared(function () {
            var oldPath = self.bgSrc;
            // Canonical key removal (documented) + non-empty sentinel fallback:
            // delete may be unsupported on some runtimes, so the sentinel (which
            // a non-empty set reliably persists) guarantees the restore rejects it.
            storage.delete({
                key: BG_PATH_KEY,
                success: function () { Logger.i('bgCleared delkey ok'); },
                fail: function (d, c) { Logger.i('bgCleared delkey FAIL c=' + c); },
            });
            storage.set({
                key: BG_PATH_KEY,
                value: BG_CLEARED,
                success: function () { Logger.i('bgCleared set ok'); },
                fail: function (d, c) { Logger.i('bgCleared set FAIL c=' + c + ' d=' + d); },
            });
            // F4: also drop the hash marker so the next watch_screen reply
            // reports bg='' (we no longer hold a default bg).
            storage.set({
                key: BG_HASH_KEY,
                value: '',
                success: function () {},
                fail: function () {},
            });
            self.bgSrc = '';
            self.hasBg = false;
            self.updateScrim();
            if (oldPath && oldPath !== BG_CLEARED) {
                file.delete({
                    uri: oldPath,
                    success: function () { Logger.i('bgCleared del ok ' + oldPath); },
                    fail: function (d, c) { Logger.i('bgCleared del FAIL c=' + c + ' uri=' + oldPath); },
                });
            }
            Logger.i('index.bgCleared');
        });
        IncomingHandler.setOnSyncWake(function () {
            if (self.screen === 'list') {
                self.screen = 'sync';
                // Sync gates on `sized` only (waits for size, skips the ~2 s
                // i18n). Drop the splash. applyScreen always triggers the load
                // (deferred), so don't call it here (it blocks ~2 s).
                self.showSplash = false;
                self.updateScrim();
                Logger.i('index.screen=sync (wake-by-sync)');
                // Never strand on 'sync': if no sync_done follows (a bare
                // bg-clear/settings envelope woke us), terminate after the
                // fallback. Cleared by sync_done / onHide.
                if (self._syncTimer !== null) { clearTimeout(self._syncTimer); }
                self._syncTimer = setTimeout(function () {
                    self._syncTimer = null;
                    if (self.screen === 'sync') {
                        Logger.i('index.sync fallback -> terminate (no sync_done)');
                        fireTerminate('sync_fallback');
                    }
                }, SYNC_FALLBACK_MS);
            }
        });
        IncomingHandler.setOnSyncDone(function () {
            if (self._syncTimer !== null) {
                clearTimeout(self._syncTimer);
                self._syncTimer = null;
            }
            // Only self-close when this instance was woken PURELY to sync
            // (screen=='sync', set by onSyncWake). If the user already had
            // the app open (screen=='list') or an alarm is ringing
            // (screen=='ring'), the sync applied silently in the
            // background — terminating here would yank the app out from
            // under the user, and mid-ring it ends the alarm (onHide ->
            // implicit snooze). Keep the app where the user left it.
            if (self.screen === 'sync') {
                Logger.i('index.sync_done -> terminate (woken for sync)');
                fireTerminate('sync_done');
            } else {
                Logger.i('index.sync_done -> stay (screen=' + self.screen + ')');
            }
        });

        SettingsStore.get(function (s) {
            self._use24Hour = s.use24Hour;
            self._dayOrder = computeDayOrder(s.firstDayOfWeek);
        });
    },

    // Recompute every screen-dependent dimension from real device metrics.
    // GT6 (466x466 round) reproduces the previous hard-coded values exactly;
    // rectangular screens fit content to their real W x H. Round-vs-rect is
    // expressed through computed values (paddings), NOT a class swap — Lite
    // forbids `class="{{...}}"` data binding.
    applyScreen: function (m) {
        var w = m.W;
        var h = m.H;
        var round = (m.shape !== 'rect');
        this.screenW = w;
        this.screenH = h;
        this.isRound = round;
        // List height: GT6 -> 320 (466-146), exact. Rect screens get their
        // (shorter) height minus the header; the list scrolls either way.
        this.listH = h - 146;
        // Empty/onboarding fills the full screen (header hidden when empty)
        // and centres its content — keeps the bottom scan line off the chord.
        this.emptyH = h;
        // Decorative arc centred on the real screen, radius tracking the
        // smaller side. GT6: cx/cy 233, r round(0.472*466)=220 — unchanged.
        this.arcCx = Math.round(w / 2);
        this.arcCy = Math.round(h / 2);
        this.arcR = Math.round(Math.min(w, h) * 0.472);
        // Over-scroll chord headroom only matters on a round face.
        this.padTop = round ? 24 : 8;
        this.padBot = round ? 56 : 8;
        // Row geometry. GT6 (w=466) reproduces the old CSS exactly:
        //   rowW=min(390,418)=390, rowMargin=(466-390)/2=38, timeW=204≈200.
        // Narrower screens shrink the row (keeping ~24px side gutters) and the
        // time box; the 126px day-grid + 18px paddings stay fixed, so the time
        // box absorbs the difference (FIT 408 -> timeW 174, FIT2 336 -> 102).
        this.titleW = Math.min(420, w - 24);
        this.rowItemW = w;
        var rowW = Math.min(390, w - 48);
        this.rowW = rowW;
        this.rowMargin = Math.round((w - rowW) / 2);
        // 186 = dot(12)+dotMargin(12) + padLeft(18)+padRight(18) + dayGrid(126).
        this.timeW = rowW - 186;
        // Rebuild any already-rendered rows so they pick up the new per-row
        // geometry immediately (getInfo is async; rows may have first rendered
        // with the 466 defaults before this ran). No-op cost on the GT6.
        if (typeof this.refresh === 'function') {
            this.refresh();
        }
        // SIZE known — the critical gate. Now the splash (bg + centred
        // hourglass) can paint, correctly sized on every resolution. If an
        // event already chose ring/sync, leave showSplash false so that screen
        // paints instead (it was waiting on `sized`).
        this.sized = true;
        this._tApplyScreen = Date.now();
        if (this.screen !== 'ring' && this.screen !== 'sync' && !this.contentReady) {
            this.showSplash = true;
        }
        Logger.i('index.applyScreen +' + (this._tApplyScreen - this._onInitMs) +
            'ms ' + w + 'x' + h + ' round=' + round);
        // Defer the heavy load so the splash PAINTS before the blocking i18n.
        var selfAS = this;
        setTimeout(function () {
            selfAS.startLoadRest();
        }, 0);
    },

    // Reveal the cold-start content once dims + i18n + privacy + connection are
    // all loaded (or `force` from the reveal-safety timeout). Picks privacy vs
    // list and drops the splash. Idempotent. Ring/sync do NOT go through here —
    // they gate on `sized` alone and reveal immediately on their event.
    maybeReveal: function (force) {
        if (this.contentReady) return;
        if (!force && !(this._i18nDone && this._privacyDone && this._connDone)) {
            return;
        }
        // Invariant: content never renders before size is known. Normally
        // `sized` is already true (applyScreen runs upstream of every reveal
        // path); this guards the pathological forced-reveal-without-dims case.
        this.sized = true;
        this._pickScreen();
        this.contentReady = true;
        this.showSplash = false;
        // ONE consolidated, watch-side timing breakdown (Date.now() marks) so
        // we can see exactly where startup time goes, relay-drop-proof:
        //   size     = onInit → applyScreen (getInfo)
        //   splashGap= applyScreen → startLoadRest (render/macrotask gap; how
        //              long until the splash actually gets a chance to paint)
        //   preI18n  = startLoadRest → loadStrings start (privacy/conn/refresh)
        //   i18n     = loadStrings $t block (the suspected ~1.8 s)
        //   total    = onInit → contentReady
        var t0 = this._onInitMs;
        Logger.i('index.TIMING size=' + (this._tApplyScreen - t0) +
            ' splashGap=' + (this._tLoadRestStart - this._tApplyScreen) +
            ' preI18n=' + (this._tLoadStringsStart - this._tLoadRestStart) +
            ' i18n=' + (this._tLoadStringsEnd - this._tLoadStringsStart) +
            ' total=' + (Date.now() - t0) + 'ms' + (force ? ' (forced)' : ''));
        this.refresh();
    },

    // Choose the cold-start screen (privacy gate vs list). Never overrides a
    // ring/sync screen an inbound event already selected.
    _pickScreen: function () {
        if (this.screen === 'ring' || this.screen === 'sync') return;
        this.screen = this.needsConsent ? 'privacy' : 'list';
        this.updateScrim();
    },

    // Empty-state copy: never-connected → "set up GT Wake" + onboarding hint;
    // connected-but-empty → plain "no alarms". Both render the AppGallery QR.
    _applyEmptyCopy: function () {
        if (this.hasConnected) {
            this.emptyText = this._noAlarms;
            this.emptyHint = this._noAlarmsHint;
        } else {
            this.emptyText = this._onbTitle;
            this.emptyHint = this._onbHint;
        }
    },

    // Deferred i18n load — see startLoadRest. ~35 per-key $t() lookups (each an
    // explicit literal call, the snapshot-safe form) split into 4 setTimeout-
    // yielded CHUNKS so ACELite can flush the render between batches and keep the
    // splash painted during the ~1.7 s load (one unbroken $t block starves the
    // renderer → the splash only flashes). Reveal fires after the LAST chunk
    // (_loadStrings4) sets _i18nDone + maybeReveal — content shows fully texted.
    loadStrings: function () {
        this._tLoadStringsStart = Date.now();
        this.title = this.$t('strings.app_name');
        this.emptyText = this.$t('strings.no_alarms');
        this.emptyHint = this.$t('strings.no_alarms_hint');
        this.ringTitle = this.$t('strings.reminder_default_title');
        this.repeatOnce = this.$t('strings.repeat_once');
        this.repeatTimer = this.$t('strings.repeat_timer');
        this.repeatAll = this.$t('strings.repeat_every_day');
        this.repeatWeekdays = this.$t('strings.repeat_weekdays');
        this.repeatWeekends = this.$t('strings.repeat_weekends');
        var self = this;
        setTimeout(function () { self._loadStrings2(); }, 0);
    },
    _loadStrings2: function () {
        this.noSyncYet = this.$t('strings.sync_never');
        this.syncJustNow = this.$t('strings.sync_just_now');
        this.syncMinAgo = this.$t('strings.sync_min_ago');
        this.syncHrAgo = this.$t('strings.sync_hr_ago');
        this.syncDayAgo = this.$t('strings.sync_day_ago');
        this.editOnPhoneToast = this.$t('strings.edit_on_phone');
        this.d0 = this.$t('strings.day_sun');
        this.d1 = this.$t('strings.day_mon');
        this.d2 = this.$t('strings.day_tue');
        var self = this;
        setTimeout(function () { self._loadStrings3(); }, 0);
    },
    _loadStrings3: function () {
        this.d3 = this.$t('strings.day_wed');
        this.d4 = this.$t('strings.day_thu');
        this.d5 = this.$t('strings.day_fri');
        this.d6 = this.$t('strings.day_sat');
        this.ampmAM = this.$t('strings.ampm_am');
        this.ampmPM = this.$t('strings.ampm_pm');
        this.privacyTitle = this.$t('strings.privacy_consent_title');
        this.privacyBody = this.$t('strings.privacy_consent_body_short');
        this.privacyScan = this.$t('strings.privacy_consent_scan');
        var self = this;
        setTimeout(function () { self._loadStrings4(); }, 0);
    },
    _loadStrings4: function () {
        this.privacyAgree = this.$t('strings.privacy_consent_agree');
        this.privacyDecline = this.$t('strings.privacy_consent_decline');
        this.privacyQr = this.$t('strings.privacy_qr');
        this.onbScan = this.$t('strings.onboarding_scan');
        this._noAlarms = this.$t('strings.no_alarms');
        this._noAlarmsHint = this.$t('strings.no_alarms_hint');
        this._onbTitle = this.$t('strings.onboarding_get_app_title');
        this._onbHint = this.$t('strings.onboarding_get_app_hint');
        this._applyEmptyCopy();
        this._tLoadStringsEnd = Date.now();
        this._i18nDone = true;
        Logger.i('index.loadStrings +' + (this._tLoadStringsEnd - this._onInitMs) + 'ms chunked');
        this.maybeReveal();
    },

    // Load everything the cold-start content needs, AFTER size is known (called
    // deferred from applyScreen). Privacy/connection are quick storage reads;
    // loadStrings (i18n) is the ~1.7 s cost, run in setTimeout-yielded chunks so
    // the splash keeps painting. Each completion calls maybeReveal(); content
    // reveals when all are in.
    startLoadRest: function () {
        if (this._loadRestStarted) return;
        this._loadRestStarted = true;
        this._tLoadRestStart = Date.now();
        var self = this;
        PrivacyStore.get(function (agreed) {
            self.needsConsent = !agreed;
            self._privacyDone = true;
            self.maybeReveal();
        });
        ConnectionStore.get(function (c) {
            self.hasConnected = !!c.connectedOnce;
            self._applyEmptyCopy();
            self._connDone = true;
            self.maybeReveal();
        });
        // Settings + alarms for the list rows.
        this.refresh();
        // i18n LAST — chunked $t load (yields between batches so the splash
        // stays painted); the async gets above resolve alongside it.
        this.loadStrings();
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
            var p = '' + path;
            // Re-arm the thin-client drain timer on ANY inbound file. The 3s drain
            // is armed by the earlier sync_check; a file transfer (large alarm list
            // / slow link) + read + apply + ack can outrun it, and the file path
            // did NOT re-arm it — so the watch could terminate mid-receive. During
            // a user-opened session this is a no-op (grace-window guard inside).
            IncomingHandler.noteFileActivity();
            // Full-state alarm replace delivered as a FILE — the phone sends the
            // list this way when it exceeds the ~1 KB P2P text-message ceiling
            // (see HuaweiWearBridge.pushFileReplace). Routed on the BASENAME prefix
            // (parseAlarmsHash returns '' unless the file is named alarms_<hash>.*)
            // so a bg path that merely contains "alarms_" can't misroute. Parse +
            // apply via the sync_replace path, then ack so the phone stops retrying.
            if (parseAlarmsHash(p) !== '') {
                self.applyAlarmsFile(p);
                return;
            }
            Logger.i('index.bgFile path=' + p);
            self.bgSrc = p;
            self.hasBg = true;
            var hash = parseBgHash(p);
            // CHAIN the two persistent writes, then ACK only AFTER they commit.
            // @system.storage.set is async; the thin client often terminates
            // (sync_done) right after, so firing the ack/terminate before the
            // writes land left BG_PATH_KEY/BG_HASH_KEY empty — the watch held
            // the file but forgot its path (no restore on the next open) and
            // reported bg='' (spurious re-uploads). Acking inside the success
            // chain guarantees: when the phone sees bg_received, the watch has
            // PERSISTED both the path (restores + renders on open) and the hash
            // (next watch_screen reports it, so no re-upload).
            var ackOnce = function () {
                try {
                    WearBridge.sendBgReceived(hash);
                } catch (e) {
                    Logger.err('index.bgFile ack', e);
                }
            };
            storage.set({
                key: BG_PATH_KEY,
                value: '' + path,
                success: function () {
                    storage.set({
                        key: BG_HASH_KEY,
                        value: hash,
                        success: function () {
                            Logger.i('index.bgFile persisted hash=' + hash);
                            ackOnce();
                        },
                        fail: function (d, c) {
                            Logger.i('index.bgFile hash persist FAIL c=' + c);
                            ackOnce();
                        },
                    });
                },
                fail: function (d, c) {
                    Logger.i('index.bgFile path persist FAIL c=' + c);
                    ackOnce();
                },
            });
            // The 2 s poll (refresh) surfaces bgSrc into the bg-box gate
            // — no reliance on if=/async reactivity here.
            self.refresh();
        });
        WearBridge.setIncomingHandler(function (msg) {
            try {
                IncomingHandler.handle(msg);
            } catch (e) {
                Logger.err('index.handle', e);
            }
        });

        AlarmStore.setOnChange(function () {
            self.refresh();
        });
        this.refresh();
        if (this._refreshTimer === null) {
            this._refreshTimer = setInterval(function () {
                self.refresh();
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
        if (this._syncTimer !== null) {
            clearTimeout(this._syncTimer);
            this._syncTimer = null;
        }
        this.stopVibrate();
        this._disarmCrownGesture();
        // NO implicit snooze on hide. This used to treat "ring page hidden
        // without an explicit Dismiss/Snooze tap" as a side-button press and
        // snooze the alarm. The page has no way to tell a deliberate press
        // from a system-initiated hide, so a plain screen timeout silently
        // snoozed the user's alarm — measured on a GT 6 Pro 2026-08-28:
        //   index.enterRing id=3  (snoozeAllowed=true)
        //   +3.1s  index.onHide screen=ring explicit=null
        //   +3.1s  index.onHide implicit-snooze id=3
        //          AlarmRepo: snoozeAt id=3 +5min count=1
        // — an alarm that snoozed itself three seconds in, with no user
        // action at all.
        //
        // For an alarm clock the fail-safe direction is to KEEP RINGING: a
        // spurious snooze can make someone miss a wake-up, while a missed
        // snooze gesture costs one extra tap on a phone that is still
        // audibly ringing. Dismiss and Snooze remain available as explicit
        // taps on the ring UI (onDismiss / onSnooze), and the phone stays
        // in charge of the alarm until one of those, or its own timeout,
        // ends it. Do not reintroduce a gesture that infers user intent
        // from a lifecycle callback.
        Logger.flushNow();
    },

    // ---- LIST ----
    refresh: function () {
        var self = this;
        // Connection state may flip (first sync arrives while the empty
        // screen is up) — re-read so the onboarding copy updates live.
        // ConnectionStore caches after the first read, so this is cheap.
        ConnectionStore.get(function (c) {
            self.hasConnected = !!c.connectedOnce;
            self._applyEmptyCopy();
        });
        SettingsStore.get(function (s) {
            self._use24Hour = s.use24Hour;
            self._dayOrder = computeDayOrder(s.firstDayOfWeek);
        });
        AlarmStore.getAll(function (items) {
            var sorted = items.slice();
            sorted.sort(compareAlarms);
            var rows = [];
            for (var i = 0; i < sorted.length; i++) {
                rows.push(formatRow(self, sorted[i]));
            }
            // CROWN-SCROLL FIX: only reassign self.alarms when the rendered
            // content actually changed. The 2 s poll rebuilt + reassigned this
            // array EVERY tick (new formatRow objects each time), which
            // re-rendered the <list> and DROPPED its digital-crown focus — so
            // rotation({focus}) was set then thrown away every 2 s and the crown
            // never scrolled. A stable array keeps the list element (and its
            // focus) alive between real changes.
            var sig = JSON.stringify(rows);
            if (sig !== self._alarmsSig) {
                self._alarmsSig = sig;
                self.alarms = rows;
            }
            // (Re-)assert crown focus, DEFERRED so it runs AFTER any re-render
            // settles — a synchronous call right after a reassign targets the
            // pre-render element. The working ring crown-list focuses the same
            // way (deferred from enterRing). rotation() is idempotent; the poll
            // recovers focus after a ring screen / background cycle.
            // SDK: ListElement.rotation({focus}).
            if (self.screen === 'list' && rows.length > 0) {
                setTimeout(function () {
                    if (self.screen !== 'list') { return; }
                    var listEl = safeRef(self, 'alarmList');
                    if (listEl && listEl.rotation) {
                        try {
                            listEl.rotation({ focus: true });
                        } catch (crownErr) {
                            Logger.err('index.crownFocus', crownErr);
                        }
                    }
                }, 0);
            }
            // Scrim gate for the background photo. bgSrc is a plain field
            // set by the P2P file handler; the poll surfaces it reliably
            // even if if=/async reactivity is not.
            self.hasBg = !!self.bgSrc;
            self.updateScrim();
        });
    },

    // F4: restore a persisted bg file on cold start. The watch is a thin client
    // that TERMINATES after each sync, so this runs on EVERY relaunch. CRUCIAL:
    // we must NOT wipe the hash markers when file.access fails. The stored hash
    // (BG_HASH_KEY) is the watch's reliable record of "what the phone gave us";
    // @system.storage and the received file live in the SAME app sandbox, so a
    // genuine reset wipes BOTH (BG_PATH_KEY would already be '' and we never get
    // here). A file.access failure with the markers still present is therefore
    // either a Lite probe quirk or a volatile received path — and wiping the
    // hash on it is exactly what desynced the watch from the phone and caused
    // the re-upload loop on every cold relaunch. So: on success render the file;
    // on failure keep the markers (report the hash, no re-upload) and just fall
    // back to the default gradient visually. Only the phone's explicit
    // watch_default_bg_cleared (onBgCleared) ever clears the markers.
    // Apply a full-state replace delivered as a FILE (large alarm list over the
    // ~1 KB text-message ceiling). Read the staged `alarms_<hash>.json`, parse,
    // run the same sync_replace apply path (validate + replaceAll + tombstone
    // pruned — replaceAll's fireChange re-renders the list), then ACK
    // (alarms_received) ONLY after the store write commits, and delete the
    // transient file. Mirrors the bg ack-after-persist ordering.
    applyAlarmsFile: function (path) {
        var p = '' + path;
        var hash = parseAlarmsHash(p);
        Logger.i('index.alarmsFile path=' + p + ' hash=' + hash);
        file.readText({
            uri: p,
            success: function (data) {
                var text = (data && typeof data.text === 'string') ? data.text : '';
                var parsed = null;
                try {
                    parsed = JSON.parse(text);
                } catch (e) {
                    Logger.err('index.alarmsFile JSON.parse', e);
                    // Drop the transient file so a corrupt/partial transfer doesn't
                    // linger; the phone's ack timeout will re-send a fresh copy.
                    file.delete({ uri: p, success: function () {}, fail: function () {} });
                    return;
                }
                IncomingHandler.applyReplaceFromFile(parsed, function (ok) {
                    // ACK only AFTER the store write actually committed (ok===true).
                    // applyReplace passes false for a parseable-but-malformed file
                    // (no alarms array) — acking that would tell the phone we
                    // converged when we stored nothing (breaks persist-then-ack).
                    if (!ok) {
                        Logger.w('index.alarmsFile applyReplace failed — NOT acking');
                        return;
                    }
                    Logger.i('index.alarmsFile applied — ack hash=' + hash);
                    try {
                        WearBridge.sendAlarmsReceived(hash);
                    } catch (e2) {
                        Logger.err('index.alarmsFile ack', e2);
                    }
                    // Transient sync file — delete so the sandbox doesn't accrue
                    // stale alarms_*.json (unlike the bg image, it isn't shown).
                    file.delete({ uri: p, success: function () {}, fail: function () {} });
                });
            },
            fail: function (data, code) {
                Logger.i('index.alarmsFile read FAIL code=' + code);
            },
        });
    },

    verifyBgFile: function (path) {
        var self = this;
        try {
            file.access({
                uri: path,
                success: function () {
                    self.bgSrc = path;
                    self.hasBg = true;
                    self.updateScrim();
                    Logger.i('index.bgPath restored=' + path);
                },
                fail: function (data, code) {
                    // Keep the hash — do NOT clearBgMarkers (that caused the loop).
                    Logger.i('index.bgPath access-fail code=' + code +
                        ' — keeping hash, showing default (no re-upload)');
                },
            });
        } catch (e) {
            Logger.err('index.verifyBgFile', e);
            // Same: a probe error must not wipe the hash.
        }
    },

    // F4: drop the on-watch bg path + hash so a relaunch won't restore a stale
    // file and the next watch_screen reply reports bg='' (phone re-uploads).
    // Uses storage.delete + a sentinel for the path (empty values don't persist
    // on this runtime — same reason BG_CLEARED is non-empty), and stores '' for
    // the hash, which is fine: '' means "no marker" both as absent and as set.
    clearBgMarkers: function () {
        this.bgSrc = '';
        this.hasBg = false;
        this.updateScrim();
        storage.delete({
            key: BG_PATH_KEY,
            success: function () {},
            fail: function () {},
        });
        storage.set({
            key: BG_PATH_KEY,
            value: BG_CLEARED,
            success: function () {},
            fail: function () {},
        });
        storage.set({
            key: BG_HASH_KEY,
            value: '',
            success: function () {},
            fail: function () {},
        });
    },

    // Scrim shows on the list + sync screens (dims the photo so white
    // text stays legible); the ring screen shows the photo at full
    // strength. Recomputed on every screen change + the 2 s poll.
    updateScrim: function () {
        this.showScrim = !!this.bgSrc && this.screen !== 'ring';
        // Backdrop src tracks the custom bg, falling back to the default
        // gradient. Updated here since updateScrim() is called on every bg /
        // screen change. Scrim stays custom-bg-only (the dark gradient needs
        // none — white text is legible on it).
        this.splashBg = this.bgSrc ? this.bgSrc : DEFAULT_BG;
    },

    // ---- CROWN-TO-SNOOZE (ring screen) ----
    // The digital crown only drives a focused <list>'s scroll, so the
    // hidden .crown-list captures it as `scrollend` ticks. Ticks are
    // debounced into gestures: ticks <CROWN_GAP_MS apart are one
    // continuous rotation; one rotation then quiet -> snooze; a second
    // rotation within CROWN_DOUBLE_MS -> dismiss.
    _clearCrownTimers: function () {
        if (this._crownGapTimer !== null) {
            clearTimeout(this._crownGapTimer);
            this._crownGapTimer = null;
        }
        if (this._crownDecideTimer !== null) {
            clearTimeout(this._crownDecideTimer);
            this._crownDecideTimer = null;
        }
    },
    // Called (deferred) from enterRing once .crown-list has rendered.
    _armCrownGesture: function () {
        var self = this;
        this._crownGestureCount = 0;
        this._crownActive = false;
        this._crownCommitted = false;
        this._crownIgnoreScroll = true;
        this._clearCrownTimers();
        var el = safeRef(this, 'crownList');
        if (el) {
            try {
                el.scrollTo({ index: CROWN_MID_INDEX });
                el.rotation({ focus: true });
            } catch (e) {
                Logger.err('index.armCrown', e);
            }
        }
        // Swallow any scrollend the scrollTo() above may emit, then start
        // honoring real crown ticks.
        setTimeout(function () {
            self._crownIgnoreScroll = false;
        }, CROWN_SETTLE_MS);
    },
    // Hold the watch screen on for the duration of the ring, and release it
    // the moment the ring is over.
    //
    // Without this the ring is bounded by the watch's own screen timeout, not
    // by the alarm: measured on a GT 6 Pro 2026-08-28, `enterRing` at T+0 was
    // followed by `onHide screen=ring explicit=null` at T+6.6s and the app was
    // torn down — the user sees the alarm appear and then the watch drop
    // straight back to the always-on clock while the phone is still ringing.
    //
    // `@system.brightness.setKeepScreenOn` is the Lite Wearable API for this
    // (confirmed in the canonical FA-model reference, espinr/litewearable
    // pages/index/index.js). Failure is non-fatal: we still ring, we just ring
    // for as long as the system allows.
    _setKeepScreenOn: function (on) {
        try {
            brightness.setKeepScreenOn({
                keepScreenOn: on,
                success: function () {},
                fail: function (data, code) {
                    Logger.w('index.keepScreenOn ' + on + ' failed c=' + code);
                },
            });
        } catch (e) {
            Logger.err('index.keepScreenOn', e);
        }
    },
    // Called when the ring is torn down (action taken / peer-ended /
    // page hidden) — stop the gesture machine, release crown focus, and
    // drop the screen-on hold. This is the one function every ring-end path
    // already funnels through (onDismiss / onSnooze / onPeerEnded / onHide),
    // which is why the screen release lives here rather than being repeated
    // at all five `screen = 'list'` assignments.
    _disarmCrownGesture: function () {
        this._clearCrownTimers();
        this._crownActive = false;
        this._setKeepScreenOn(false);
        var el = safeRef(this, 'crownList');
        if (el) {
            try {
                el.rotation({ focus: false });
            } catch (e) {
                Logger.err('index.disarmCrown', e);
            }
        }
    },
    onCrownTick: function () {
        if (this.screen !== 'ring' || this.ended ||
                this._crownCommitted || this._crownIgnoreScroll) {
            return;
        }
        var self = this;
        if (this._crownGapTimer !== null) {
            clearTimeout(this._crownGapTimer);
            this._crownGapTimer = null;
        }
        if (!this._crownActive) {
            // First tick of a fresh rotation burst.
            this._crownActive = true;
            this._crownGestureCount = this._crownGestureCount + 1;
            if (this._crownGestureCount >= 2) {
                // Second rotation within the double-window -> dismiss.
                this._clearCrownTimers();
                this._crownCommitted = true;
                Logger.i('index.crown gesture=2 -> dismiss');
                this.onDismiss();
                return;
            }
        }
        // Burst still running: when ticks stop for CROWN_GAP_MS the
        // rotation is considered finished.
        this._crownGapTimer = setTimeout(function () {
            self._crownGapTimer = null;
            self._crownActive = false;
            if (self._crownGestureCount === 1 && !self._crownCommitted) {
                // One rotation done — wait CROWN_DOUBLE_MS for a second;
                // if none arrives it was a single rotation -> snooze.
                self._crownDecideTimer = setTimeout(function () {
                    self._crownDecideTimer = null;
                    if (!self._crownCommitted &&
                            self.screen === 'ring' && !self.ended &&
                            self.ringSnoozeEnabled) {
                        self._crownCommitted = true;
                        Logger.i('index.crown gesture=1 -> snooze');
                        self.onSnooze();
                    }
                }, CROWN_DOUBLE_MS);
            }
        }, CROWN_GAP_MS);
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


    // ---- RING ----
    enterRing: function (alarmId, ringHints) {
        Logger.i('index.enterRing id=' + alarmId);
        var self = this;
        this._alarmId = alarmId;
        this._explicitAction = null;
        this.ended = false;
        // Default-open: a thin-client envelope without prior sync wins
        // the "show snooze" by default. ringHints (from alarm_fired
        // envelope) override below; AlarmStore lookup is the last resort
        // for old phone builds that didn't ship the hints.
        this.ringSnoozeEnabled = true;
        this.ringVibrationPattern = 'PULSE';
        this.ringWatchVibrationEnabled = true;
        cancelTerminate();
        this.screen = 'ring';
        // Hold the screen on for the ring. Paired with the release in
        // _disarmCrownGesture, which every ring-end path funnels through.
        // Without this the ring lives only as long as the watch's display
        // timeout — measured at 3.1 s on a GT 6 Pro 2026-08-28.
        this._setKeepScreenOn(true);
        // Ring gates on `sized` only — it shows the instant dims are known
        // (waits for size to avoid the flicker, but NOT for the ~2 s i18n;
        // ringTime + buttons render now, ringTitle fills in when loadStrings
        // lands). Drop the splash. Do NOT call startLoadRest here — it BLOCKS
        // ~2 s, which would delay the ring paint; applyScreen always triggers
        // it (deferred), so the title fills in without blocking the ring.
        this.showSplash = false;
        this.updateScrim();
        // Apply inline ring hints from the envelope FIRST — that's the
        // thin-client criterion: the watch must ring with the right
        // pattern + Snooze gating even on a cold-paired/unsynced state.
        var hintPattern = (ringHints && ringHints.vibrationPattern) || null;
        var hintSnoozeAllowed = (ringHints && (typeof ringHints.snoozeAllowed === 'boolean'))
            ? ringHints.snoozeAllowed : null;
        var hintWatchVib = (ringHints && (typeof ringHints.watchVibrationEnabled === 'boolean'))
            ? ringHints.watchVibrationEnabled : null;
        if (hintPattern && VIBRATION_PATTERNS[hintPattern]) {
            this.ringVibrationPattern = hintPattern;
        }
        if (hintSnoozeAllowed !== null) {
            this.ringSnoozeEnabled = hintSnoozeAllowed;
        }
        if (hintWatchVib !== null) {
            this.ringWatchVibrationEnabled = hintWatchVib;
        }
        Logger.i('index.enterRing hints pattern=' + hintPattern +
            ' snoozeAllowed=' + hintSnoozeAllowed +
            ' → snoozeEnabled=' + this.ringSnoozeEnabled +
            ' pattern=' + this.ringVibrationPattern);
        // Fallback AlarmStore resolution — only fills fields the hints
        // didn't carry (legacy phone build, or hints came back null).
        AlarmStore.getAll(function (items) {
            var idNum = Number(alarmId);
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === idNum) {
                    if (hintSnoozeAllowed === null) {
                        self.ringSnoozeEnabled = (items[i].snoozeMinutes || 0) > 0;
                    }
                    if (!hintPattern) {
                        var p = items[i].vibrationPattern;
                        if (p && VIBRATION_PATTERNS[p]) {
                            self.ringVibrationPattern = p;
                        }
                    }
                    if (hintWatchVib === null) {
                        // default true when the field is absent (old alarm)
                        self.ringWatchVibrationEnabled = (items[i].watchVibrationEnabled !== false);
                    }
                    Logger.i('index.enterRing store-fallback snoozeEnabled=' +
                        self.ringSnoozeEnabled + ' pattern=' + self.ringVibrationPattern +
                        ' watchVib=' + self.ringWatchVibrationEnabled);
                    // 1.0.6: enterRing already armed vibration SYNCHRONOUSLY with the
                    // default-true flag (startVibrate at the end of enterRing). This
                    // async fallback runs AFTER that, so when the alarm_fired envelope
                    // carried no watch-vib hint we must RE-APPLY the resolved decision —
                    // otherwise a "silent on watch" alarm fired without a hint keeps
                    // buzzing on the default true. startVibrate() begins with
                    // stopVibrate() and honors the disabled flag, so this both stops a
                    // now-disabled alarm and re-arms the correct pattern. Guard on the
                    // ring still being up so we never re-arm after dismiss/snooze.
                    if ((hintWatchVib === null || !hintPattern) &&
                        self.screen === 'ring' && !self.ended) {
                        self.startVibrate();
                    }
                    return;
                }
            }
            Logger.i('index.enterRing alarm not in store — keeping hint values');
        });
        // Arm crown-to-snooze once the ring stack (with .crown-list) has
        // rendered — the $refs entry resolves only after the render pass.
        setTimeout(function () {
            self._armCrownGesture();
        }, 0);

        // Ring time is always "now" — the wake-up instant the user sees.
        var now = new Date();
        this.ringTime = formatHHMM(now.getHours(), now.getMinutes(),
            this._use24Hour, this.ampmAM, this.ampmPM);

        // Tell the phone our ring UI is up (it awaits this before
        // starting its own audio).
        var idNum = Number(alarmId);
        if (isFinite(idNum) && idNum > 0) {
            try {
                WearBridge.sendRinging(idNum, function () {});
            } catch (e) {
                Logger.err('index.sendRinging', e);
            }
        }
        this.startVibrate();
    },

    startVibrate: function () {
        this.stopVibrate();
        // 1.0.6: "silent on watch" — skip the buzz entirely for this alarm.
        if (!this.ringWatchVibrationEnabled) {
            Logger.i('startVibrate skip — watch vibration disabled');
            return;
        }
        var pattern = VIBRATION_PATTERNS[this.ringVibrationPattern] || VIBRATION_PATTERNS.PULSE;
        // OFF preset → no vibration at all.
        if (!pattern || pattern.length === 0) {
            Logger.i('startVibrate skip pattern=' + this.ringVibrationPattern);
            return;
        }
        var self = this;
        var stepIdx = 0;
        function step() {
            // Guard: stopVibrate() nulls _vibrateTimer; if the page changed
            // mid-sequence we must not re-fire.
            if (self._vibrateTimer === null) return;
            var s = pattern[stepIdx];
            try {
                if (vibrator && typeof vibrator.vibrate === 'function') {
                    vibrator.vibrate({ mode: s.mode });
                }
            } catch (e) {
                Logger.err('vibrate', e);
            }
            stepIdx = (stepIdx + 1) % pattern.length;
            self._vibrateTimer = setTimeout(step, s.delay);
        }
        // Sentinel so the guard inside step() doesn't trip on the first
        // synchronous call before setTimeout assigns.
        this._vibrateTimer = -1;
        step();
    },

    stopVibrate: function () {
        if (this._vibrateTimer !== null && this._vibrateTimer !== -1) {
            clearTimeout(this._vibrateTimer);
        }
        this._vibrateTimer = null;
    },

    // Swipe-right is the system back/exit gesture on Huawei watches. This is a
    // single-page app with no router, so nothing pops on back — we terminate
    // explicitly (AppGallery rejected the app for not exiting on right-swipe).
    // Guard the RING screen: there the user must dismiss/snooze, not swipe away.
    onSwipe: function (e) {
        if (e && e.direction === 'right' && this.screen !== 'ring') {
            Logger.i('index.onSwipe right -> terminate (screen=' + this.screen + ')');
            app.terminate();
        }
    },

    // ---- PRIVACY CONSENT (first launch) ----
    // Agree → persist + drop into the list. Decline → exit the app (mirrors
    // the phone's PrivacyConsentDialog Disagree path).
    onPrivacyAgree: function () {
        Logger.i('index.privacy agree');
        this.needsConsent = false;
        PrivacyStore.setAgreed(function (ok) {
            Logger.i('index.privacy persisted ok=' + ok);
        });
        this.screen = 'list';
        this.updateScrim();
        this.refresh();
    },

    onPrivacyDecline: function () {
        Logger.i('index.privacy decline -> terminate');
        try {
            app.terminate();
        } catch (e) {
            Logger.err('index.privacy decline term', e);
        }
    },

    onDismiss: function () {
        Logger.i('index.onDismiss id=' + this._alarmId);
        var self = this;
        this._explicitAction = 'dismiss';
        this.stopVibrate();
        this._disarmCrownGesture();
        // Show the green thumbs-up confirmation in place of the buttons,
        // then leave the ring after the same flash window the phone-
        // initiated path uses.
        this.ended = true;
        var alarmId = this._alarmId;
        WearBridge.notifyDismissed(alarmId, function (ok, reason) {
            Logger.i('index.dismiss-ack ok=' + ok + ' r=' + reason);
            expediteTerminate('dismiss');
        });
        // Retry-cap: must outlast the notifyDismissed retry chain so the
        // chain is never killed mid-flight. The cb above expedites the
        // common (fast) case; this cap is the unreachable-phone fallback.
        scheduleTerminate('dismiss', TERMINATE_RETRY_CAP_MS);
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
        this._disarmCrownGesture();
        this.ended = true;
        WearBridge.notifySnoozed(this._alarmId, function (ok, reason) {
            Logger.i('index.snooze-ack ok=' + ok + ' r=' + reason);
            expediteTerminate('snooze');
        });
        // Retry-cap — see onDismiss: outlasts the notifySnoozed retry chain.
        scheduleTerminate('snooze', TERMINATE_RETRY_CAP_MS);
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
        this._disarmCrownGesture();
        if (this.screen !== 'ring') {
            // No ring up — nothing to flash; just make sure we are home.
            this.screen = 'list';
            this.updateScrim();
            return;
        }
        this._explicitAction = 'peer-ended';
        this.ended = true;
        setTimeout(function () {
            self.screen = 'list';
            self.updateScrim();
            self.refresh();
            scheduleTerminate('peer-ended');
        }, PEER_FLASH_MS);
    },
};
