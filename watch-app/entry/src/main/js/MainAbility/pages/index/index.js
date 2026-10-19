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
import Ticker from '../../common/ticker.js';

// Logged at onInit so a log relay shows which build is running. For a
// hardware-test build, also append it to `title` so the screen shows it.
var BUILD_TAG = '1.0.12';

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
// A late alarm_fired copy for a ring that already ended closes the app again
// only if the app started this recently (it was woken just for that copy).
var STALE_FIRE_COLD_MS = 10000;
// @system.storage key: last ended ring as "<alarmId>:<firedAt>".
var LAST_RING_KEY = 'gt_last_ring';
var PENDING_ACTION_KEY = 'gt_pending_action';

function noop() {}

function isPos(n) {
    return typeof n === 'number' && n > 0 && isFinite(n);
}

// Ring bookkeeping that the screen never renders. Plain module variables, not
// page `data`: every data field gets ACELite watcher machinery on the JS heap,
// and adding these as data fields was part of the build that rebooted the
// watch at its install-time launch (2026-10-01). One page, one instance, so
// module scope is equivalent.
var _vibRunId = 0;          // per-start vibration token (see startVibrate)
var _ringFiredAt = 0;       // phone `firedAt` of the ring on screen
var _ringSupportsActionAck = false;
var _pendingAction = null;
var _resumeGen = 0;         // bumped by a tap, an end message or an accepted resume; see _resumePendingAction
var _releasedAction = null; // given up on after the last try; a late reply may still end the ring
var _pendingActionAttempts = 0;
var _lastActionStamp = 0;
var _lastEndedAlarmId = 0;  // last ring this watch ended (also persisted)
var _lastEndedFiredAt = 0;
var _peerEndedAlarmId = 0;  // phone ended this alarm before our ring showed
var _peerEndedAt = 0;
var _clearListPending = false; // listSync:false seen; drop the list at ring end

// Page state that the template never binds. Module variables, not `data`:
// ACELite gives every data key reactive accessors (two per key, with their
// own closures) on the JS heap, and the build that rebooted the watch at its
// install-time launch (2026-10-01) carried 88 data keys, 51 of them never
// bound. One page and one instance per process, so module scope is
// equivalent. The template-bound fields stay in `data`.
var _bgSrc = '';               // on-watch path of a P2P-received PNG ('' = none)
var _ringPattern = 'PULSE';    // VibrationPattern name for the ring on screen
var _ringWatchVib = true;      // per-alarm "Watch vibration"
// Responsive list-row geometry (GT6 defaults), copied into each row so the
// row binding uses the proven $item path.
var _rowItemW = 466;
var _rowW = 390;
var _rowMargin = 38;
var _timeW = 204;
// i18n strings read by the row/clock helpers.
var _repeatOnce = '';
var _repeatTimer = '';
var _editOnPhoneToast = '';
var _d0 = '', _d1 = '', _d2 = '', _d3 = '', _d4 = '', _d5 = '', _d6 = '';
var _ampmAM = '';
var _ampmPM = '';
// Cold-start load latches; maybeReveal() flips contentReady when all set.
var _onInitMs = 0;
var _i18nDone = false;
var _i18nStage = 1;
var _privacyDone = false;
// One-shot guard so startLoadRest() runs once (applyScreen + the
// sized-safety could both reach it).
var _loadRestStarted = false;
// Default true → a forced reveal (safety timeout) with consent still
// unresolved fails SAFE to showing the consent gate, never skipping it.
var _needsConsent = true;
var _alarmId = 0;
var _refreshTimer = null;
var _tapHintTimer = null;
var _use24Hour = null;
var _dayOrder = DEFAULT_DAY_ORDER;
var _crownGestureCount = 0;
var _crownActive = false;
var _crownCommitted = false;
var _crownIgnoreScroll = false;
var _crownGapTimer = null;
var _crownDecideTimer = null;

// Ring auto-terminate. The watch was woken for the alarm; once the
// action settles, close the app instead of sitting on the watch face.
var TERMINATE_HARD_CAP_MS = 6000;
// Longer cap for the dismiss/snooze paths. One watch->phone send
// (wearBridge.sendJsonWithRetry) takes at most maxAttempts(3) x
// SEND_TIMEOUT_MS(3000) + 2 x 500ms retry gap = 10s, and the page may repeat
// the send up to 3 times while it waits for the phone's reply. The cap is
// NOT meant to outlast every repeat: when no reply arrives the page gives
// up on its own (_sendPendingAction), and the cap then closes the app. It
// only has to outlast the first send, so the app is not closed mid-send.
var TERMINATE_RETRY_CAP_MS = 11000;
// After the phone refuses an action (ok:false) the ring is back on screen and
// buzzing, so the short action cap is cancelled. This cap bounds the ring.
var RING_CAP_MS = 360000;
// A normal ring has no phone reply to wait for, so without a bound a dropped
// Bluetooth link leaves the watch buzzing. Longer than the phone's 5 minute
// auto-stop plus its 30/60/120 s retries.
var RING_MAX_MS = 540000;
// A saved action older than this is not replayed (see _resumePendingAction).
var ACTION_RESUME_MAX_MS = 120000;
// Long enough that the ring's DISMISSED/SNOOZED confirmation (shown for
// PEER_FLASH_MS) stays visible before the watch auto-closes.
var TERMINATE_AFTER_ACK_MS = 1400;
var _terminateScheduled = false;
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
// The fallback waits for pending store writes, but never past this absolute
// deadline: a write callback that never fires must not keep the app open.
var SYNC_MAX_MS = 20000;

function fireTerminate(tag) {
    try {
        app.terminate();
    } catch (e) {
    }
}

function armSyncFallback(page, until) {
    until = until || Date.now() + SYNC_MAX_MS;
    Ticker.set('syncFallback', SYNC_FALLBACK_MS, function () {
        if (page.screen !== 'sync') return;
        if (IncomingHandler.hasPendingWrites() && Date.now() < until) armSyncFallback(page, until);
        else fireTerminate('sync_fallback');
    });
}

// Terminate deadlines run on the Ticker (common/ticker.js): a one-shot
// setTimeout can be dropped on a busy queue, which would leave the app open.
// alarm_ringing back to the phone. alarmId is already validated (finite,
// > 0) by incomingHandler's rejectMalformed before alarm_fired reaches us.
function ackRing(alarmId, firedAt, requestStamp) {
    try {
        WearBridge.sendRinging(alarmId, firedAt, requestStamp);
    } catch (e) {
    }
}

function scheduleTerminate(reason, capMs) {
    if (_terminateScheduled) return;
    _terminateScheduled = true;
    Ticker.set('terminate', capMs || TERMINATE_HARD_CAP_MS, function () {
        fireTerminate('cap:' + reason);
    });
}

// Woken only by a stray message (a late copy or reply for a ring that is
// over): close again rather than sit open. Only in the app's first seconds.
function closeIfCold() {
    if (Date.now() - _onInitMs < STALE_FIRE_COLD_MS) scheduleTerminate('stale');
}

function expediteTerminate(tag) {
    if (!_terminateScheduled || !Ticker.has('terminate')) return;
    Ticker.set('terminate', TERMINATE_AFTER_ACK_MS, function () {
        fireTerminate('ack:' + tag);
    });
}

function armRingCap(ms) {
    Ticker.set('ringCap', ms, function () { fireTerminate('ring'); });
}

function cancelTerminate() {
    Ticker.clear('terminate');
    Ticker.clear('ringCap');
    _terminateScheduled = false;
}

// The saved record always mirrors the LATEST request. A write or delete that
// lands late (its callback arrives after a newer request exists) puts the
// latest state back, so an older request can never be what a relaunch replays.
function saveRecord(action) {
    storage.set({ key: PENDING_ACTION_KEY, value: JSON.stringify(action),
        success: function () {
            if (_pendingAction !== action) fixRecord();
        }, fail: noop });
}

function repairRecord() {
    if (_pendingAction && _pendingAction.v === 1) saveRecord(_pendingAction);
}

// Every deletion repairs after it lands: a delete that arrives late must not
// remove the record of a newer request saved in the meantime.
function dropRecord() {
    storage.delete({ key: PENDING_ACTION_KEY, success: repairRecord, fail: noop });
}

function fixRecord() {
    if (_pendingAction && _pendingAction.v === 1) saveRecord(_pendingAction);
    else dropRecord();
}

// Yield to the renderer between i18n chunks. A setTimeout keeps the load fast
// (a ticker hop is up to 250 ms, x3 chunks); the ticker backstop runs the chunk
// if a cold start drops that timer, which would leave the strings empty.
function yieldTo(page, n) {
    var run = function () {
        if (_i18nStage >= n) return;
        _i18nStage = n;
        page['_loadStrings' + n]();
    };
    setTimeout(run, 0);
    Ticker.set('i18n', 1000, run);
}

function clearPendingAction() {
    _pendingAction = null;
    Ticker.clear('actionAckWait');
    Ticker.clear('actionRetry');
    dropRecord();
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
    if (alarm.relativeMinutes) return _repeatTimer;
    // selfDestruct reads "Once" (identical phone + watch). A plain
    // one-time alarm shows no label at all — the absent day grid already
    // says "fires once", so a redundant word just adds noise.
    if (alarm.selfDestruct) return _repeatOnce;
    return '';
}

function letterArrayFromSelf(self, order) {
    var twoChar = [_d0, _d1, _d2, _d3, _d4, _d5, _d6];
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
    var order = _dayOrder || DEFAULT_DAY_ORDER;
    var dayBits = [false, false, false, false, false, false, false];
    for (var i = 0; i < 7; i++) {
        dayBits[i] = (alarm.daysOfWeek & DAY_BITS[order[i]]) !== 0;
    }
    var dayLetters = letterArrayFromSelf(self, order);
    return {
        id: alarm.id,
        time: formatAlarmTime(alarm, _use24Hour, _ampmAM, _ampmPM),
        enabled: enabled,
        notEnabled: !enabled,
        // Responsive geometry (computed in applyScreen) carried per-row so the
        // <list-item> inline-style bindings use the proven $item mechanism.
        rowItemW: _rowItemW,
        rowW: _rowW,
        rowMargin: _rowMargin,
        timeW: _timeW,
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
        privacyAgree: '', privacyDecline: '',
        // Fallback, overwritten by $t('strings.privacy_qr') in _loadStrings4.
        // NOT '' — the reveal-safety timeout can paint this screen before
        // i18n loads, and an empty <qrcode> renders a blank white square.
        privacyQr: 'https://gtwake.kirkouski.com/privacy',

        // --- empty state ---
        // One message whether or not the phone ever connected: alarms are set
        // on the phone, and the watch vibrates when one goes off. With list
        // sync off (the default since 2026-09-28) the list is always empty,
        // and a fresh watch has not connected until its first alarm, so a
        // "set up GT Wake" variant would wrongly show on a working install.
        // onbQr is the help page (/watch), i18n'd so zh gets /watch/zh.
        onbScan: '',
        // Fallback, overwritten by $t('strings.onboarding_qr'). Same reason
        // as privacyQr above: never leave a <qrcode> bound to ''.
        onbQr: 'https://gtwake.kirkouski.com/watch',

        // --- background photo ---
        // The received bg path lives in the module var _bgSrc (not bound).
        // showScrim gates the 50% dimming layer (list/sync screens only —
        // the ring screen shows the photo at full strength).
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
        // Empty-state hint box: 2 lines at 26 px (76) on round faces, 3 lines
        // (104) on rect faces. The narrow FIT widths wrap the hint to 3 lines,
        // and rect panels have the height for it; round faces can't take the
        // extra height without pushing the scan line into the curved edge.
        emptyHintH: 76,
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
        // Everything the template does not bind lives in module variables
        // (see "Page state that the template never binds" near the top).
    },

    onInit: function () {
        var self = this;
        // Timing anchor for the applyScreen / loadStrings deltas.
        _onInitMs = Date.now();
        // Last ended ring, so a late re-sent alarm_fired that relaunched this
        // app is recognised (see enterRing). A storage read takes a few ms;
        // the first alarm_fired lands ~1 s after onInit.
        storage.get({
            key: LAST_RING_KEY,
            default: '',
            success: function (v) {
                var parts = ('' + v).split(':');
                if (parts.length === 2) {
                    _lastEndedAlarmId = Number(parts[0]);
                    _lastEndedFiredAt = Number(parts[1]);
                }
            },
            fail: noop,
        });
        // SIZE safety: if getInfo never returns, apply default dims so the
        // splash + load still proceed (nothing renders before `sized`).
        // Both safety nets run on the Ticker: they exist to rescue a stalled
        // cold start, which is exactly when one-shot timers get dropped.
        Ticker.set('sizedSafety', SIZED_SAFETY_MS, function () {
            if (!self.sized) {
                self.applyScreen({ W: 466, H: 466, shape: 'circle' });
            }
        });
        // REVEAL safety: if the cold-start load stalls, reveal anyway rather
        // than sit on the splash forever.
        Ticker.set('revealSafety', REVEAL_SAFETY_MS, function () {
            self.maybeReveal(true);
        });
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
            fail: noop,
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
        IncomingHandler.setOnPeerEndedRing(function (message, type) {
            return self.onPeerEnded(message, type);
        });
        IncomingHandler.setOnActionAck(function (message) {
            self._handleActionAck(message);
        });
        // Phone cleared the default watch background. Persist the cleared
        // sentinel FIRST so a relaunch can't restore the old path even if a
        // later call throws; then drop it from the UI and best-effort delete
        // the received file.
        IncomingHandler.setOnBgCleared(function () {
            var oldPath = _bgSrc;
            // Canonical key removal (documented) + non-empty sentinel fallback:
            // delete may be unsupported on some runtimes, so the sentinel (which
            // a non-empty set reliably persists) guarantees the restore rejects it.
            storage.delete({
                key: BG_PATH_KEY,
                success: noop,
                fail: noop,
            });
            storage.set({
                key: BG_PATH_KEY,
                value: BG_CLEARED,
                success: noop,
                fail: noop,
            });
            // F4: also drop the hash marker so the next watch_screen reply
            // reports bg='' (we no longer hold a default bg).
            storage.set({
                key: BG_HASH_KEY,
                value: '',
                success: noop,
                fail: noop,
            });
            _bgSrc = '';
            self.updateScrim();
            if (oldPath && oldPath !== BG_CLEARED) {
                file.delete({
                    uri: oldPath,
                    success: noop,
                    fail: noop,
                });
            }
        });
        IncomingHandler.setOnSyncWake(function () {
            if (self.screen === 'list') {
                self.screen = 'sync';
                // Sync gates on `sized` only (waits for size, skips the ~2 s
                // i18n). Drop the splash. applyScreen always triggers the load
                // (deferred), so don't call it here (it blocks ~2 s).
                self.showSplash = false;
                self.updateScrim();
                // Never strand on 'sync': if no sync_done follows (a bare
                // bg-clear/settings envelope woke us), terminate after the
                // fallback. Cleared by sync_done / onHide.
                // On the Ticker: a dropped one-shot timer here would strand
                // the watch on the sync page.
                armSyncFallback(self);
            }
        });
        IncomingHandler.setOnSyncDone(function () {
            Ticker.clear('syncFallback');
            // Only self-close when this instance was woken PURELY to sync
            // (screen=='sync', set by onSyncWake). If the user already had
            // the app open (screen=='list') or an alarm is ringing
            // (screen=='ring'), the sync applied silently in the
            // background — terminating here would yank the app out from
            // under the user, and mid-ring it ends the alarm (onHide ->
            // implicit snooze). Keep the app where the user left it.
            if (self.screen === 'sync') fireTerminate('sync_done');
        });
        // Settings load in refresh(), which onShow calls right after onInit.
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
        // The empty block fills the full screen (binds screenH; header hidden
        // when empty) and centres its content — keeps the scan line off the chord.
        this.emptyHintH = round ? 76 : 104;
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
        _rowItemW = w;
        var rowW = Math.min(390, w - 48);
        _rowW = rowW;
        _rowMargin = Math.round((w - rowW) / 2);
        // 186 = dot(12)+dotMargin(12) + padLeft(18)+padRight(18) + dayGrid(126).
        // 186 = dot(12)+dotMargin(12) + padLeft(18)+padRight(18) + dayGrid(126),
        // a FIXED budget. Below ~280px wide it exceeds the whole row and this
        // goes to zero or negative: at Huawei's 194x368 profile (Band, FIT mini)
        // rowW is 146, so the raw result is -40. A negative width is undefined
        // behaviour in ACELite and can blank the page. Those profiles are
        // excluded from distribution via distroFilter, so this is a floor, not
        // a layout: it keeps a malformed value away from the renderer if one
        // ever reports a screen we did not model.
        _timeW = Math.max(60, rowW - 186);
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
        if (this.screen !== 'ring' && this.screen !== 'sync' && !this.contentReady) {
            this.showSplash = true;
        }
        // Defer the heavy load so the splash PAINTS before the blocking i18n.
        var selfAS = this;
        // The one-shot timer is the fast path; the ticker is the backstop for a
        // cold start that drops it (startLoadRest runs once, whichever is first).
        setTimeout(function () { selfAS.startLoadRest(); }, 0);
        Ticker.set('loadRest', 1000, function () { selfAS.startLoadRest(); });
    },

    // Reveal the cold-start content once dims + i18n + privacy + connection are
    // all loaded (or `force` from the reveal-safety timeout). Picks privacy vs
    // list and drops the splash. Idempotent. Ring/sync do NOT go through here —
    // they gate on `sized` alone and reveal immediately on their event.
    maybeReveal: function (force) {
        if (this.contentReady) return;
        if (!force && !(_i18nDone && _privacyDone)) {
            return;
        }
        // Invariant: content never renders before size is known. Normally
        // `sized` is already true (applyScreen runs upstream of every reveal
        // path); this guards the pathological forced-reveal-without-dims case.
        this.sized = true;
        this._pickScreen();
        this.contentReady = true;
        this.showSplash = false;
        this.refresh();
    },

    // Choose the cold-start screen (privacy gate vs list). Never overrides a
    // ring/sync screen an inbound event already selected.
    _pickScreen: function () {
        if (this.screen === 'ring' || this.screen === 'sync') return;
        this.screen = _needsConsent ? 'privacy' : 'list';
        this.updateScrim();
    },

    // Deferred i18n load — see startLoadRest. ~35 per-key $t() lookups (each an
    // explicit literal call, the snapshot-safe form) split into 4 setTimeout-
    // yielded CHUNKS so ACELite can flush the render between batches and keep the
    // splash painted during the ~1.7 s load (one unbroken $t block starves the
    // renderer → the splash only flashes). Reveal fires after the LAST chunk
    // (_loadStrings4) sets _i18nDone + maybeReveal — content shows fully texted.
    loadStrings: function () {
        this.title = this.$t('strings.app_name');
        this.emptyText = this.$t('strings.no_alarms');
        this.emptyHint = this.$t('strings.no_alarms_hint');
        this.ringTitle = this.$t('strings.reminder_default_title');
        _repeatOnce = this.$t('strings.repeat_once');
        _repeatTimer = this.$t('strings.repeat_timer');
        yieldTo(this, 2);
    },
    _loadStrings2: function () {
        _editOnPhoneToast = this.$t('strings.edit_on_phone');
        _d0 = this.$t('strings.day_sun');
        _d1 = this.$t('strings.day_mon');
        _d2 = this.$t('strings.day_tue');
        yieldTo(this, 3);
    },
    _loadStrings3: function () {
        _d3 = this.$t('strings.day_wed');
        _d4 = this.$t('strings.day_thu');
        _d5 = this.$t('strings.day_fri');
        _d6 = this.$t('strings.day_sat');
        _ampmAM = this.$t('strings.ampm_am');
        _ampmPM = this.$t('strings.ampm_pm');
        this.privacyTitle = this.$t('strings.privacy_consent_title');
        this.privacyBody = this.$t('strings.privacy_consent_body_short');
        this.privacyScan = this.$t('strings.privacy_consent_scan');
        yieldTo(this, 4);
    },
    _loadStrings4: function () {
        this.privacyAgree = this.$t('strings.privacy_consent_agree');
        this.privacyDecline = this.$t('strings.privacy_consent_decline');
        this.privacyQr = this.$t('strings.privacy_qr');
        this.onbScan = this.$t('strings.onboarding_scan');
        this.onbQr = this.$t('strings.onboarding_qr');
        _i18nDone = true;
        Ticker.clear('i18n');
        this.maybeReveal();
    },

    // Load everything the cold-start content needs, AFTER size is known (called
    // deferred from applyScreen). Privacy/connection are quick storage reads;
    // loadStrings (i18n) is the ~1.7 s cost, run in setTimeout-yielded chunks so
    // the splash keeps painting. Each completion calls maybeReveal(); content
    // reveals when all are in.
    startLoadRest: function () {
        if (_loadRestStarted) return;
        _loadRestStarted = true;
        var self = this;
        PrivacyStore.get(function (agreed) {
            _needsConsent = !agreed;
            _privacyDone = true;
            self.maybeReveal();
        });
        // Settings + alarms for the list rows.
        this.refresh();
        // i18n LAST — chunked $t load (yields between batches so the splash
        // stays painted); the async gets above resolve alongside it.
        this.loadStrings();
    },

    onShow: function () {
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
            _bgSrc = p;
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
                }
            };
            storage.set({
                key: BG_PATH_KEY,
                value: '' + path,
                success: function () {
                    storage.set({
                        key: BG_HASH_KEY,
                        value: hash,
                        success: ackOnce,
                        fail: noop,
                    });
                },
                fail: noop,
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
        // onHide releases the hold; take it again while a ring is open or a
        // request is waiting for its reply.
        if (this.screen === 'ring' && (!this.ended || _pendingAction)) this._setKeepScreenOn(true);
        this._resumePendingAction();
        this.refresh();
        if (_refreshTimer === null) {
            _refreshTimer = setInterval(function () {
                self.refresh();
            }, 2000);
        }
    },

    onHide: function () {
        AlarmStore.setOnChange(null);
        if (_refreshTimer !== null) {
            clearInterval(_refreshTimer);
            _refreshTimer = null;
        }
        Ticker.clear('syncFallback');
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
        // Backstop for the Ticker: if its interval could not be created, this
        // 2 s poll still runs due deadlines (vibration, terminate caps).
        Ticker.poke();
        SettingsStore.get(function (s) {
            _use24Hour = s.use24Hour;
            _dayOrder = computeDayOrder(s.firstDayOfWeek);
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
                        }
                    }
                }, 0);
            }
            // Scrim gate for the background photo. bgSrc is a plain field
            // set by the P2P file handler; the poll surfaces it reliably
            // even if if=/async reactivity is not.
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
        var filePending = true;
        var finishFileRead = function () {
            if (!filePending) return;
            filePending = false;
            IncomingHandler.finishPendingWrite();
        };
        IncomingHandler.beginPendingWrite();
        file.readText({
            uri: p,
            success: function (data) {
                var text = (data && typeof data.text === 'string') ? data.text : '';
                var parsed = null;
                try {
                    parsed = JSON.parse(text);
                } catch (e) {
                    // Drop the transient file so a corrupt/partial transfer doesn't
                    // linger; the phone's ack timeout will re-send a fresh copy.
                    file.delete({ uri: p, success: noop, fail: noop });
                    finishFileRead();
                    return;
                }
                if (!parsed || typeof parsed !== 'object') {
                    file.delete({ uri: p, success: noop, fail: noop });
                    finishFileRead();
                    return;
                }
                parsed.hash = hash;
                IncomingHandler.applyReplaceFromFile(parsed, function (ok) {
                    // ACK only AFTER the store write actually committed (ok===true).
                    // applyReplace passes false for a parseable-but-malformed file
                    // (no alarms array) — acking that would tell the phone we
                    // converged when we stored nothing (breaks persist-then-ack).
                    if (!ok) {
                        finishFileRead();
                        return;
                    }
                    // Transient sync file — delete so the sandbox doesn't accrue
                    // stale alarms_*.json (unlike the bg image, it isn't shown).
                    file.delete({ uri: p, success: noop, fail: noop });
                    finishFileRead();
                });
            },
            fail: function (data, code) {
                finishFileRead();
            },
        });
    },

    verifyBgFile: function (path) {
        var self = this;
        try {
            file.access({
                uri: path,
                success: function () {
                    _bgSrc = path;
                    self.updateScrim();
                },
                fail: function (data, code) {
                    // Keep the hash — do NOT clearBgMarkers (that caused the loop).
                },
            });
        } catch (e) {
            // Same: a probe error must not wipe the hash.
        }
    },

    // Scrim shows on the list + sync screens (dims the photo so white
    // text stays legible); the ring screen shows the photo at full
    // strength. Recomputed on every screen change + the 2 s poll.
    updateScrim: function () {
        this.showScrim = !!_bgSrc && this.screen !== 'ring';
        // Backdrop src tracks the custom bg, falling back to the default
        // gradient. Updated here since updateScrim() is called on every bg /
        // screen change. Scrim stays custom-bg-only (the dark gradient needs
        // none — white text is legible on it).
        this.splashBg = _bgSrc ? _bgSrc : DEFAULT_BG;
    },

    // ---- CROWN-TO-SNOOZE (ring screen) ----
    // The digital crown only drives a focused <list>'s scroll, so the
    // hidden .crown-list captures it as `scrollend` ticks. Ticks are
    // debounced into gestures: ticks <CROWN_GAP_MS apart are one
    // continuous rotation; one rotation then quiet -> snooze; a second
    // rotation within CROWN_DOUBLE_MS -> dismiss.
    _clearCrownTimers: function () {
        if (_crownGapTimer !== null) {
            clearTimeout(_crownGapTimer);
            _crownGapTimer = null;
        }
        if (_crownDecideTimer !== null) {
            clearTimeout(_crownDecideTimer);
            _crownDecideTimer = null;
        }
    },
    // Called (deferred) from enterRing once .crown-list has rendered.
    _armCrownGesture: function () {
        _crownGestureCount = 0;
        _crownActive = false;
        _crownCommitted = false;
        _crownIgnoreScroll = true;
        this._clearCrownTimers();
        var el = safeRef(this, 'crownList');
        if (el) {
            try {
                el.scrollTo({ index: CROWN_MID_INDEX });
                el.rotation({ focus: true });
            } catch (e) {
            }
        }
        // Swallow any scrollend the scrollTo() above may emit, then start
        // honoring real crown ticks.
        Ticker.set('crownSettle', CROWN_SETTLE_MS, function () {
            _crownIgnoreScroll = false;
        });
    },
    // Arm the crown gesture on the Ticker, after the ring stack has rendered.
    _armCrown: function () {
        var self = this;
        Ticker.set('crownArm', 0, function () {
            self._armCrownGesture();
        });
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
                success: noop,
                fail: noop,
            });
        } catch (e) {
        }
    },
    // Called when the ring is torn down (action taken / peer-ended /
    // page hidden) — stop the gesture machine, release crown focus, and
    // drop the screen-on hold. This is the one function every ring-end path
    // already funnels through (onDismiss / onSnooze / onPeerEnded / onHide),
    // which is why the screen release lives here rather than being repeated
    // at all five `screen = 'list'` assignments.
    _disarmCrownGesture: function (holdScreen) {
        this._clearCrownTimers();
        _crownActive = false;
        if (!holdScreen) this._setKeepScreenOn(false);
        var el = safeRef(this, 'crownList');
        if (el) {
            try {
                el.rotation({ focus: false });
            } catch (e) {
            }
        }
    },
    onCrownTick: function () {
        if (this.screen !== 'ring' || this.ended ||
                _crownCommitted || _crownIgnoreScroll) {
            return;
        }
        var self = this;
        if (_crownGapTimer !== null) {
            clearTimeout(_crownGapTimer);
            _crownGapTimer = null;
        }
        if (!_crownActive) {
            // First tick of a fresh rotation burst.
            _crownActive = true;
            _crownGestureCount = _crownGestureCount + 1;
            if (_crownGestureCount >= 2) {
                // Second rotation within the double-window -> dismiss.
                this._clearCrownTimers();
                _crownCommitted = true;
                this.onDismiss();
                return;
            }
        }
        // Burst still running: when ticks stop for CROWN_GAP_MS the
        // rotation is considered finished.
        _crownGapTimer = setTimeout(function () {
            _crownGapTimer = null;
            _crownActive = false;
            if (_crownGestureCount === 1 && !_crownCommitted) {
                // One rotation done — wait CROWN_DOUBLE_MS for a second;
                // if none arrives it was a single rotation -> snooze.
                _crownDecideTimer = setTimeout(function () {
                    _crownDecideTimer = null;
                    if (!_crownCommitted &&
                            self.screen === 'ring' && !self.ended &&
                            self.ringSnoozeEnabled) {
                        _crownCommitted = true;
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
        this.tapHint = _editOnPhoneToast;
        this.tapHintShown = true;
        if (_tapHintTimer !== null) {
            clearTimeout(_tapHintTimer);
        }
        _tapHintTimer = setTimeout(function () {
            self.tapHintShown = false;
            _tapHintTimer = null;
        }, TAP_HINT_MS);
    },


    // ---- RING ----
    enterRing: function (alarmId, ringHints) {
        // The ring contract is the alarm_fired payload. Every published phone
        // build sends vibrationPattern + snoozeAllowed (watchVibrationEnabled
        // since 1.0.6; its default true matches what older phones meant), so
        // the watch no longer falls back to its stored alarm list (removed
        // 2026-10-01; list sync is opt-in and the store is usually empty).
        var h = ringHints || {};
        var firedAt = (typeof h.firedAt === 'number') ? h.firedAt : 0;
        // The phone re-sends alarm_fired until we confirm (slow cold starts).
        // A copy for the ring already on screen only needs the confirmation
        // again: restarting would reset the buzz pattern and the screen.
        // A 1.0.10 phone sends no firedAt: same alarm ID is the duplicate rule.
        var requestStamp = (typeof h.updatedAtEpoch === 'number') ? h.updatedAtEpoch : 0;
        if (this.screen === 'ring' && !this.ended && _alarmId === alarmId &&
                (firedAt === 0 || _ringFiredAt === firedAt)) {
            ackRing(alarmId, firedAt, requestStamp);
            return;
        }
        if (this.screen === 'ring' && !this.ended && firedAt > 0 &&
                _ringFiredAt > firedAt) {
            return;
        }
        // A late copy of a ring this watch already ended must not ring again.
        // firedAt is the phone's ring start, identical on every re-send, so a
        // snooze re-fire of the same alarm (new firedAt) still rings.
        var endedBeforeShown = firedAt > 0 && alarmId === _peerEndedAlarmId &&
            firedAt <= _peerEndedAt;
        if (endedBeforeShown ||
                (firedAt > 0 && firedAt === _lastEndedFiredAt && alarmId === _lastEndedAlarmId)) {
            // Woken only by this stale copy: close again rather than sit open.
            closeIfCold();
            return;
        }
        // A different occurrence voids any saved action (the stored record too).
        // A saved action for THIS occurrence (replayed after a relaunch) is
        // already in flight: show the ring without buzzing, like after a tap.
        var resumed = !!_pendingAction && _pendingAction.a === alarmId &&
            _pendingAction.f === firedAt;
        if (!resumed && (_alarmId !== alarmId || _ringFiredAt !== firedAt)) clearPendingAction();
        _ringFiredAt = firedAt;
        // A phone that sent no firedAt is legacy and never replies.
        _ringSupportsActionAck = h.supportsActionAck === true && firedAt > 0;
        _alarmId = alarmId;
        this.ended = false;
        // Snooze defaults to allowed (better than blocking an unknown alarm).
        this.ringSnoozeEnabled = h.snoozeAllowed !== false;
        _ringPattern = VIBRATION_PATTERNS[h.vibrationPattern] ? h.vibrationPattern : 'PULSE';
        _ringWatchVib = h.watchVibrationEnabled !== false;
        cancelTerminate();
        // A previous ring's "back to list" flip must not land on this one.
        Ticker.clear('peerFlash');
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
        // Opt-in list sync (2026-09-28). With it off the phone never sends
        // settings_changed, so 12/24 h rides the fire; used for this ring's
        // clock only (not persisted, so a missing field keeps stored state).
        if (typeof h.use24Hour === 'boolean') {
            _use24Hour = h.use24Hour;
        }
        // List sync off: drop the list this watch may still hold (an upgrade
        // from an always-sync build). Deferred to ring end so the store is
        // not rewritten while the ring is on screen.
        _clearListPending = (h.listSync === false);

        // Ring time is always "now" — the wake-up instant the user sees.
        var now = new Date();
        this.ringTime = formatHHMM(now.getHours(), now.getMinutes(),
            _use24Hour, _ampmAM, _ampmPM);

        // Tell the phone our ring UI is up (it awaits this before
        // starting its own audio).
        ackRing(alarmId, firedAt, requestStamp);
        if (resumed) {
            this._holdForReply();
        } else {
            // Arm crown-to-snooze once the ring stack (with .crown-list) has
            // rendered — the $refs entry resolves only after the render pass.
            this._armCrown();
            this.startVibrate();
            armRingCap(RING_MAX_MS);
        }
    },

    startVibrate: function () {
        this.stopVibrate();
        // 1.0.6: "silent on watch" — skip the buzz entirely for this alarm.
        if (!_ringWatchVib) {
            return;
        }
        var pattern = VIBRATION_PATTERNS[_ringPattern] || VIBRATION_PATTERNS.PULSE;
        // OFF preset → no vibration at all.
        if (!pattern || pattern.length === 0) {
            return;
        }
        var stepIdx = 0;
        // Per-start token: a step queued by a previous start sees a different
        // id and bails, so a stop + restart can never leave two chains.
        _vibRunId++;
        var runId = _vibRunId;
        function step() {
            // Guard: stopVibrate() bumps _vibRunId; if the page changed
            // mid-sequence we must not re-fire.
            if (_vibRunId !== runId) return;
            var s = pattern[stepIdx];
            try {
                if (vibrator && typeof vibrator.vibrate === 'function') {
                    vibrator.vibrate({ mode: s.mode });
                }
            } catch (e) {
            }
            stepIdx = (stepIdx + 1) % pattern.length;
            // The next step runs on the Ticker, not a setTimeout chain. ACELite
            // drops one-shot timers when the app queue is full, which on a cold
            // wake killed the chain after the first buzz (device-proven
            // 2026-09-28). Pattern delays round to the nearest 250 ms tick.
            Ticker.set('vibrate', s.delay, step);
        }
        step();
    },

    stopVibrate: function () {
        _vibRunId++;
        Ticker.clear('vibrate');
    },

    // Swipe-right is the system back/exit gesture on Huawei watches. This is a
    // single-page app with no router, so nothing pops on back — we terminate
    // explicitly (AppGallery rejected the app for not exiting on right-swipe).
    // Guard the RING screen: there the user must dismiss/snooze, not swipe away.
    onSwipe: function (e) {
        if (e && e.direction === 'right' && this.screen !== 'ring') {
            app.terminate();
        }
    },

    // ---- PRIVACY CONSENT (first launch) ----
    // Agree → persist + drop into the list. Decline → exit the app (mirrors
    // the phone's PrivacyConsentDialog Disagree path).
    onPrivacyAgree: function () {
        _needsConsent = false;
        PrivacyStore.setAgreed(noop);
        this.screen = 'list';
        this.updateScrim();
        this.refresh();
    },

    onPrivacyDecline: function () {
        try {
            app.terminate();
        } catch (e) {
        }
    },

    // Shared by every ring end (dismiss, snooze, peer-ended). Remembers the
    // ring so a late re-sent copy of it is ignored (see enterRing), and drops
    // the stale alarm list when the phone said list sync is off.
    _onRingEnded: function () {
        _lastEndedAlarmId = _alarmId;
        _lastEndedFiredAt = _ringFiredAt;
        // Persisted: the app terminates ~1.4 s after a ring, and a late copy
        // can relaunch it with fresh memory. "<alarmId>:<firedAt>" < 128 B.
        if (_ringFiredAt > 0) {
            storage.set({
                key: LAST_RING_KEY,
                value: _alarmId + ':' + _ringFiredAt,
                success: noop,
                fail: noop,
            });
        }
        if (_clearListPending) {
            _clearListPending = false;
            AlarmStore.replaceAll([], noop);
        }
    },

    onDismiss: function () {
        this._beginRingAction('dismiss');
    },
    onSnooze: function () {
        this._beginRingAction('snooze');
    },
    // Dismiss/Snooze tap. The request goes out at once and keeps one stamp for
    // every retry. A phone that answers (v === 1) is waited for: the ring ends
    // only on its ok:true (or a matching peer-ended). The record is saved in
    // parallel for a relaunch; a phone that never answers (v === 0) completes
    // on the transport result and saves nothing.
    _beginRingAction: function (type) {
        if (_pendingAction || this.screen !== 'ring' || this.ended) return;
        if (type === 'snooze' && !this.ringSnoozeEnabled) return;
        this.stopVibrate();
        this._disarmCrownGesture(true);
        _resumeGen++;
        _lastActionStamp = Math.max(Date.now(), _lastActionStamp + 1);
        var action = { t: type === 'dismiss' ? 'd' : 's', a: _alarmId,
            f: _ringFiredAt, s: _lastActionStamp, v: _ringSupportsActionAck ? 1 : 0 };
        _pendingAction = action;
        _pendingActionAttempts = 0;
        // A fresh cap for this request: an earlier one may be nearly spent.
        cancelTerminate();
        scheduleTerminate(type, TERMINATE_RETRY_CAP_MS);
        this._sendPendingAction();
        if (action.v === 1) saveRecord(action);
    },
    _sendPendingAction: function () {
        var self = this;
        var action = _pendingAction;
        if (!action) return;
        _pendingActionAttempts++;
        var send = action.t === 'd' ? WearBridge.notifyDismissed : WearBridge.notifySnoozed;
        // The bridge retries one send itself; the page starts its own retry only
        // after that chain ended, so the two never overlap. The bridge stops its
        // chain as soon as this request is no longer pending.
        send(action.a, action.f, action.s, function (transportOk) {
            if (_pendingAction !== action) return;
            if (!transportOk) self._retryPendingAction();
            else if (action.v !== 1) self._completeRingAction(action);
            else Ticker.set('actionAckWait', 1800, function () { self._retryPendingAction(); });
        }, function () { return _pendingAction === action; });
    },
    _retryPendingAction: function () {
        var self = this;
        if (!_pendingAction) return;
        if (_pendingActionAttempts < 3) {
            Ticker.set('actionRetry', 700, function () { self._sendPendingAction(); });
            return;
        }
        // No reply after every try: release the request. No success flash, the
        // buzz stays off, and the action terminate cap still closes the app. A
        // late ok:true for it can still end the ring (see _handleActionAck).
        _releasedAction = _pendingAction;
        clearPendingAction();
        this._armCrown();
    },
    _handleActionAck: function (message) {
        var action = _pendingAction || _releasedAction;
        if (!action || !message || message.alarmId !== action.a ||
                message.updatedAtEpoch !== action.s ||
                message.type !== (action.t === 'd' ? 'alarm_dismissed_ack' : 'alarm_snoozed_ack') ||
                (action.v === 1 && action.f > 0 && message.firedAt !== action.f) ||
                (typeof message.firedAt === 'number' && message.firedAt !== action.f)) {
            // A stray reply that woke a cold app has nothing to act on.
            if (!_pendingAction && this.screen !== 'ring') closeIfCold();
            return;
        }
        _releasedAction = null;
        if (message.ok === true) {
            this._completeRingAction(action);
        } else if (message.ok === false) {
            // The phone refused: it is still ringing, so the watch rings again.
            clearPendingAction();
            if (this.screen === 'ring' && !this.ended && _alarmId === action.a &&
                    _ringFiredAt === action.f) {
                cancelTerminate();
                armRingCap(RING_CAP_MS);
                this._setKeepScreenOn(true);
                this._armCrown();
                this.startVibrate();
            }
        }
    },
    // A saved action is replayed only if it is from a phone that answers, has an
    // occurrence, is under two minutes old and fits the ring on screen.
    _resumePendingAction: function () {
        var self = this;
        if (_pendingAction) return;
        // The read can answer late. If a tap, a reply, an end message or a new
        // ring settled things in the meantime, its record is old: drop it.
        var gen = _resumeGen;
        storage.get({ key: PENDING_ACTION_KEY, default: '', success: function (value) {
            if (_pendingAction || typeof value !== 'string' || value.length === 0) return;
            var action = null;
            try {
                action = JSON.parse(value);
            } catch (e) {
                action = null;
            }
            if (!action || (action.t !== 'd' && action.t !== 's') || action.v !== 1 ||
                    !isPos(action.a) || !isPos(action.f) || !isPos(action.s) ||
                    Math.abs(Date.now() - action.s) >= ACTION_RESUME_MAX_MS || gen !== _resumeGen ||
                    action.s <= _lastActionStamp ||
                    (action.a === _lastEndedAlarmId && action.f === _lastEndedFiredAt) ||
                    (self.screen === 'ring' && (self.ended || _alarmId !== action.a ||
                        _ringFiredAt !== action.f))) {
                dropRecord();
                return;
            }
            _pendingAction = action;
            _resumeGen++;
            _lastActionStamp = Math.max(_lastActionStamp, action.s);
            _pendingActionAttempts = 0;
            _alarmId = action.a;
            _ringFiredAt = action.f;
            _ringSupportsActionAck = true;
            // The ring may already be up (its alarm_fired came before this read
            // returned): it is now waiting for the reply, like after a tap.
            if (self.screen === 'ring') self._holdForReply();
            self._sendPendingAction();
        }, fail: noop });
    },
    // The ring stays on screen while a request is in flight: no buzz, no crown,
    // screen held, and a fresh cap so the app still closes if no reply comes.
    _holdForReply: function () {
        this.stopVibrate();
        this._disarmCrownGesture(true);
        cancelTerminate();
        scheduleTerminate('resume', TERMINATE_RETRY_CAP_MS);
    },
    _completeRingAction: function (action) {
        if (_pendingAction && _pendingAction !== action) return;
        clearPendingAction();
        if (_alarmId !== action.a || _ringFiredAt !== action.f || this.screen !== 'ring') {
            if (this.screen !== 'ring') closeIfCold();
            return;
        }
        if (this.ended) return;
        this.stopVibrate();
        this.ended = true;
        this._onRingEnded();
        this._disarmCrownGesture(false);
        var self = this;
        if (action.t === 'd') {
            AlarmStore.getAll(function (items) {
                for (var i = 0; i < items.length; i++) {
                    if (items[i].id === action.a && items[i].daysOfWeek === 0) {
                        var u = {};
                        for (var k in items[i]) if (items[i].hasOwnProperty(k)) u[k] = items[i][k];
                        u.enabled = false;
                        u.updatedAtEpoch = Date.now();
                        AlarmStore.update(u, noop);
                        break;
                    }
                }
            });
        }
        // A replayed request has no cap of its own: make sure the app closes.
        scheduleTerminate('ack', PEER_FLASH_MS + TERMINATE_AFTER_ACK_MS);
        Ticker.set('peerFlash', PEER_FLASH_MS, function () {
            self.screen = 'list';
            self.updateScrim();
            self.refresh();
            expediteTerminate(action.t === 'd' ? 'dismiss' : 'snooze');
        });
    },

    // Phone reported the alarm dismissed/snoozed on its side — the
    // headline Bug-1 indicator. Flash the ring screen green, then close.
    onPeerEnded: function (message, type) {
        var alarmId = message && message.alarmId;
        var firedAt = message && typeof message.firedAt === 'number' ? message.firedAt : 0;
        if (this.screen === 'ring' && alarmId !== _alarmId) return false;
        if (this.screen === 'ring' && _ringSupportsActionAck && firedAt !== _ringFiredAt) return false;
        if (this.screen === 'ring' && alarmId === _alarmId && firedAt > 0 &&
                _ringFiredAt > 0 && firedAt !== _ringFiredAt) return false;
        // Already ended (success flash running): nothing to redo, just confirm.
        if (this.screen === 'ring' && this.ended) return true;
        _resumeGen++;
        if (_pendingAction && _pendingAction.a === alarmId &&
                (!firedAt || _pendingAction.f === firedAt)) clearPendingAction();
        var self = this;
        this.stopVibrate();
        this._disarmCrownGesture();
        if (this.screen !== 'ring') {
            // The phone ended this ring before ours was on screen (cold
            // start still loading). Remember when, so a re-sent alarm_fired
            // for that same ring (firedAt earlier than now) is ignored; a
            // snooze re-fire starts later and still rings.
            _peerEndedAlarmId = alarmId;
            _peerEndedAt = firedAt || Date.now();
            // No ring up — nothing to flash; just make sure we are home.
            this.screen = 'list';
            this.updateScrim();
            return true;
        }
        this.ended = true;
        this._onRingEnded();
        // On the Ticker: the terminate is armed inside this callback, so a
        // dropped one-shot timer would leave the app open indefinitely.
        Ticker.set('peerFlash', PEER_FLASH_MS, function () {
            self.screen = 'list';
            self.updateScrim();
            self.refresh();
            scheduleTerminate('peer-ended');
        });
        return true;
    },
};
