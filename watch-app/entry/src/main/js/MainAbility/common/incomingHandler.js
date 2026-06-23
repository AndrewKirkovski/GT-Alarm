// Receive-side dispatch for inbound P2P messages. The watch is a thin
// client — phone is sole scheduler — so we only apply state mutations
// for ADD / UPDATE / DELETE / TOGGLE, and route FIRED to the ring page.
//
// Wire format (from phone WearBridgeService):
//   { type: 'alarm_added',     alarmId, alarm:{id,...,updatedAtEpoch} }
//   { type: 'alarm_updated',   alarmId, alarm:{...} }
//   { type: 'alarm_deleted',   alarmId, updatedAtEpoch }
//   { type: 'alarm_toggled',   alarmId, enabled, updatedAtEpoch }
//   { type: 'alarm_fired',     alarmId, updatedAtEpoch }
//   { type: 'alarm_dismissed', alarmId, updatedAtEpoch }  // closes our ring page if active
//   { type: 'alarm_snoozed',   alarmId, updatedAtEpoch }  // closes our ring page if active
//
// All apply paths are tombstone-aware and LWW-resolved.
import app from '@system.app';
import storage from '@system.storage';
import AlarmStore from './alarmStore.js';
import SettingsStore from './settingsStore.js';
import Tombstones from './tombstones.js';
import Lww from './lwwResolver.js';
import Logger from './logger.js';
import AlarmHash from './alarmHash.js';
import WearBridge from './wearBridge.js';
import Screen from './screen.js';
import ConnectionStore from './connectionStore.js';

// Wake-reason tracking. When the phone wakes the watch app via Wear
// Engine auto-launch for a sync push, the first state-mutation envelope
// arrives within ~1 s of app.onCreate (per memory:wear_engine_wake_protocol).
// We route to a transient "Syncing alarms" page on that first envelope,
// then call app.terminate() after the burst quiesces so the app doesn't
// hang around occupying the watch face when the user didn't actually
// open it.
//
// When the user opens the app themselves, the first envelope (if any)
// arrives well after WAKE_SYNC_GRACE_MS — we skip the sync screen and
// the terminate, applying mutations silently to the index page they're
// already looking at.
//
// Module-level state lives in app.js's bundle (Lite gotcha #11: page
// bundles are isolated, but app.js + common/* share one). The ring page
// has its own auto-close path on dismiss/snooze; this tracker is only
// for sync wakes.
var WAKE_SYNC_GRACE_MS = 2500;
// F4: @system.storage key holding the content hash of the default background
// this watch currently holds (parsed from the received file name
// `bgd_<hash>.bin`). MUST match BG_HASH_KEY in pages/index/index.js — the two
// bundles share @system.storage but each defines its own copy of the key
// string. Reported back to the phone in the watch_screen reply (`bg` field).
var BG_HASH_KEY = 'bg_hash';
// Drain fallback. Bumped 1500 → 3000 (2026-05-17): with the additive
// alarm_added burst replaced by a single sync_replace message, the
// data push lands ~2 s after sync_check; 1500 ms could fire the drain
// (→ app.terminate) mid-sync (`forceSync done sent=0 of=1`). The
// explicit `sync_done` envelope is now the primary terminate signal;
// this timer only fires if `sync_done` is dropped.
var DRAIN_QUIESCENCE_MS = 3000;

var _appStartMs = 0;
var _wokenForSync = false;
var _drainTimer = null;
// Once an alarm_fired routes the user to the ring page, the ring page
// owns the rest of the process lifetime (its own onDismiss/onSnooze/onHide
// schedule app.terminate after the action settles). Any later envelope
// (sync_check piggybacking on the fire wake, alarm_added for a different
// row, etc.) MUST NOT router.replace to the syncing page or arm a drain
// timer — both would tear down the ring screen mid-alarm.
var _ringActive = false;

var onAlarmFired = null;
var onPeerEndedRing = null;
var onBgCleared = null;
// Injected by the single page. onSyncDone → terminate when a sync wake
// finishes; onSyncWake → switch the page to its "Syncing…" screen on the
// first envelope of a wake-by-sync.
var onSyncDone = null;
var onSyncWake = null;

// Called once from app.onCreate so we know when the app process started.
// Anything before WAKE_SYNC_GRACE_MS from now is treated as part of the
// wake-from-sync cold-launch window.
function markAppStart() {
    _appStartMs = Date.now();
    _wokenForSync = false;
    _ringActive = false;
}

// Called from the alarm_fired branch when the ring page takes over the
// process. Disarms any pending drain timer and latches _ringActive so
// later sync envelopes don't try to route us back to the syncing page
// or terminate while the user is mid-alarm.
function markRingActive() {
    _ringActive = true;
    if (_drainTimer !== null) {
        clearTimeout(_drainTimer);
        _drainTimer = null;
    }
}

// Arms / re-arms the drain quiescence timer. Burst of N envelopes
// resets to the last + DRAIN_QUIESCENCE_MS. Pulled out of
// noteIncomingEnvelope so alarm_dismissed / alarm_snoozed (which never
// route to the syncing page) can still arm the drain.
function armDrain() {
    if (_ringActive) return;
    // Only the app.js bootstrap bundle copy auto-terminates: it is the
    // one that handles the FIRST envelope on a cold wake and owns the
    // "watch woken purely for a sync / peer-ended push" lifetime. Page
    // bundle copies (gotcha #11) never call markAppStart, so _appStartMs
    // stays 0 here — and a page that the user (or the ring/sync flow) is
    // actively showing must NOT be torn down by this timer. Per-page
    // lifetime is owned by the page itself (ring.scheduleTerminate,
    // syncing's onSyncDone terminate, index never auto-closes).
    if (_appStartMs === 0) return;
    if (_drainTimer !== null) clearTimeout(_drainTimer);
    _drainTimer = setTimeout(function () {
        Logger.i('incoming.drain-quiesced — app.terminate()');
        try {
            app.terminate();
        } catch (e) {
            // Per local SDK research, app.terminate is void with no
            // documented throw path; if it ever does, the process is
            // dying anyway. Log for diagnostic completeness.
            Logger.err('incoming.app.terminate threw', e);
        }
    }, DRAIN_QUIESCENCE_MS);
}

// Routes to the syncing page on the first state-mutation envelope within
// the grace window, and arms / re-arms the drain timer so the burst can
// settle before app.terminate(). No-op when the user is already using
// the app (envelope arrived after grace) or when the ring page owns the
// foreground (markRingActive was called).
function noteIncomingEnvelope() {
    if (_appStartMs === 0) return;
    if (_ringActive) return;
    var elapsed = Date.now() - _appStartMs;
    if (!_wokenForSync) {
        if (elapsed > WAKE_SYNC_GRACE_MS) {
            // User has been using the app long enough that this envelope
            // is a live sync, not a wake-by-sync. Don't interrupt them.
            return;
        }
        _wokenForSync = true;
        Logger.i('incoming.wake-by-sync elapsed=' + elapsed);
        // Single page: switch to the "Syncing…" screen. armDrain() below
        // owns the terminate-after-quiescence; sync_done terminates fast.
    }
    armDrain();
    if (onSyncWake) onSyncWake();
}

// Stamp the AlarmStore's last-sync-epoch whenever any peer-driven mutation
// actually takes effect locally. Drives the "Last sync: N min ago" line on
// the index page.
function bumpLastSync() {
    AlarmStore.setLastSyncEpoch(Date.now());
}

// Records that the watch has connected to the phone companion (drives the
// onboarding "install the companion" screen vs. a plain empty list). ANY
// well-formed inbound envelope proves the phone is present — sync_check is
// typically the first one on connect. Guarded so we touch storage at most
// once per process, except to UPGRADE the recorded companion version if a
// `phoneAppVersion` field appears (a future phone build will add it; absent
// today → ConnectionStore defaults to LEGACY_VERSION '1.0.0').
var _connRecorded = false;
function noteConnection(msg) {
    var ver = (msg && typeof msg.phoneAppVersion === 'string' && msg.phoneAppVersion.length > 0)
        ? msg.phoneAppVersion : null;
    if (_connRecorded && ver === null) return;
    _connRecorded = true;
    try {
        ConnectionStore.recordConnection(ver, function () {});
    } catch (e) {
        Logger.err('incoming.noteConnection', e);
    }
}

// F4: reply to a phone screen_request, threading the watch's current default-
// bg hash into the watch_screen envelope so the phone can reconcile (auto-
// restore a lost image / never send a stale one). Reads BG_HASH_KEY from
// @system.storage; an empty/missing value reports '' (no bg). Module-level (not
// a nested named fn inside handle()) — nested named declarations break the
// ACELite JS snapshot (install error 10).
function respondScreenRequest() {
    Screen.load(function (m) {
        storage.get({
            key: BG_HASH_KEY,
            default: '',
            success: function (v) {
                var bg = (typeof v === 'string') ? v : '';
                Logger.i('incoming.screen_request reply ' + m.W + 'x' + m.H + ' ' + m.shape + ' bg=' + bg);
                WearBridge.sendWatchScreen(m.W, m.H, m.shape, bg);
            },
            fail: function () {
                Logger.i('incoming.screen_request reply ' + m.W + 'x' + m.H + ' ' + m.shape + ' bg=<none>');
                WearBridge.sendWatchScreen(m.W, m.H, m.shape, '');
            },
        });
    });
}

function rejectMalformed(msg) {
    if (!msg || typeof msg !== 'object') return true;
    if (typeof msg.type !== 'string') return true;
    if (typeof msg.alarmId !== 'number' || !isFinite(msg.alarmId) || msg.alarmId <= 0) {
        return true;
    }
    var stamp = msg.updatedAtEpoch;
    if (msg.alarm && typeof msg.alarm.updatedAtEpoch === 'number') {
        stamp = msg.alarm.updatedAtEpoch;
    }
    if (typeof stamp !== 'number' || !isFinite(stamp) || stamp <= 0) return true;
    if (msg.alarm && msg.alarm.id !== msg.alarmId) return true;
    return false;
}

function applyAddOrUpdate(msg) {
    var stamp = msg.alarm.updatedAtEpoch;
    Tombstones.lookupEpoch(msg.alarmId, function (tEpoch) {
        if (Lww.shouldSuppressForTombstone(stamp, tEpoch)) {
            Logger.i('incoming.suppressed-by-tombstone id=' + msg.alarmId);
            return;
        }
        AlarmStore.getAll(function (items) {
            var localEpoch = null;
            var existsLocally = false;
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === msg.alarmId) {
                    localEpoch = items[i].updatedAtEpoch;
                    existsLocally = true;
                    break;
                }
            }
            if (!Lww.shouldApply(stamp, localEpoch)) {
                Logger.i('incoming.lww-rejected id=' + msg.alarmId);
                return;
            }
            if (existsLocally) {
                AlarmStore.update(msg.alarm, function () {
                    Logger.i('incoming.applied-update id=' + msg.alarmId);
                    bumpLastSync();
                });
            } else {
                AlarmStore.add(msg.alarm, function () {
                    Logger.i('incoming.applied-add id=' + msg.alarmId);
                    bumpLastSync();
                });
            }
        });
    });
}

function applyDelete(msg) {
    var stamp = msg.updatedAtEpoch;
    Tombstones.add(msg.alarmId, stamp, function () {
        AlarmStore.deleteById(msg.alarmId, function () {
            Logger.i('incoming.applied-delete id=' + msg.alarmId);
            bumpLastSync();
        });
    });
}

function applyToggle(msg) {
    var stamp = msg.updatedAtEpoch;
    Tombstones.lookupEpoch(msg.alarmId, function (tEpoch) {
        if (Lww.shouldSuppressForTombstone(stamp, tEpoch)) {
            Logger.i('incoming.toggle-suppressed-by-tombstone id=' + msg.alarmId);
            return;
        }
        AlarmStore.getAll(function (items) {
            var found = null;
            for (var i = 0; i < items.length; i++) {
                if (items[i].id === msg.alarmId) {
                    found = items[i];
                    break;
                }
            }
            if (found === null) {
                Logger.i('incoming.toggle-no-local id=' + msg.alarmId);
                return;
            }
            if (!Lww.shouldApply(stamp, found.updatedAtEpoch)) {
                Logger.i('incoming.toggle-lww-rejected id=' + msg.alarmId);
                return;
            }
            // Clone all fields from the existing alarm so we don't drop
            // snoozeMinutes / audioUri / relativeMinutes / selfDestruct /
            // etc. on a toggle. ACE Lite's older JS engine may not have
            // Object.assign — enumerate via for-in with hasOwnProperty
            // for safety across engines.
            var updated = {};
            for (var k in found) {
                if (found.hasOwnProperty(k)) updated[k] = found[k];
            }
            updated.enabled = !!msg.enabled;
            updated.updatedAtEpoch = stamp;
            AlarmStore.update(updated, function () {
                Logger.i('incoming.applied-toggle id=' + msg.alarmId);
                bumpLastSync();
            });
        });
    });
}

// Light plausibility check for a sync_replace alarm entry. The phone
// builds sync_replace from already-validated domain Alarm objects, so
// this is just defense against a corrupt payload — id/hour/minute in
// range. Bad entries are dropped individually, not the whole replace.
function isPlausibleAlarm(a) {
    return (
        a &&
        typeof a.id === 'number' && isFinite(a.id) && a.id > 0 &&
        typeof a.hour === 'number' && a.hour >= 0 && a.hour <= 23 &&
        typeof a.minute === 'number' && a.minute >= 0 && a.minute <= 59
    );
}

// Sequentially tombstone each id dropped by a sync_replace, so a stale
// alarm_updated arriving later can't resurrect a pruned row. Sequential
// (not parallel) to avoid racing Tombstones' read-modify-write storage.
function tombstonePruned(ids, stamp, idx) {
    if (idx >= ids.length) return;
    Tombstones.add(ids[idx], stamp, function () {
        tombstonePruned(ids, stamp, idx + 1);
    });
}

// Authoritative full-state replace (phone-originated Force sync). NOT
// per-row LWW — the watch's AlarmStore is replaced wholesale so rows the
// phone deleted (which an additive alarm_added push can never remove)
// are pruned. Every dropped id is tombstoned first.
function applyReplace(msg, done) {
    var incoming = msg && msg.alarms;
    if (!incoming || incoming.length === undefined) {
        Logger.w('incoming.sync_replace missing alarms array');
        if (done) done(false);
        return;
    }
    var valid = [];
    var dropped = 0;
    for (var i = 0; i < incoming.length; i++) {
        if (isPlausibleAlarm(incoming[i])) {
            valid.push(incoming[i]);
        } else {
            dropped++;
        }
    }
    var stamp = (typeof msg.updatedAtEpoch === 'number' && msg.updatedAtEpoch > 0)
        ? msg.updatedAtEpoch : Date.now();
    AlarmStore.getAll(function (oldItems) {
        var newIds = {};
        for (var j = 0; j < valid.length; j++) {
            newIds['' + valid[j].id] = true;
        }
        var prunedIds = [];
        for (var k = 0; k < oldItems.length; k++) {
            if (!newIds['' + oldItems[k].id]) {
                prunedIds.push(oldItems[k].id);
            }
        }
        AlarmStore.replaceAll(valid, function () {
            Logger.i('incoming.sync_replace applied n=' + valid.length +
                ' dropped=' + dropped + ' pruned=' + prunedIds.length);
            bumpLastSync();
            tombstonePruned(prunedIds, stamp, 0);
            // done fires AFTER the store write commits (replaceAll success) so a
            // file-delivered replace acks only once persisted — same ordering
            // guarantee as the bg file ack. The text-message path passes no done.
            if (done) done(true);
        });
    });
}

export default {
    markAppStart: markAppStart,
    // Apply a full-state replace parsed from a FILE-delivered envelope (used when
    // the phone sends the list as a file because it exceeds the text-message
    // ceiling). Same path as the text sync_replace, but with a completion
    // callback so the page can ACK only after the store write commits.
    applyReplaceFromFile: function (msg, done) {
        applyReplace(msg, done);
    },
    setOnAlarmFiredNavigator: function (fn) {
        onAlarmFired = fn;
    },
    // Fired on a phone `sync_done` envelope — page terminates the app.
    setOnSyncDone: function (fn) {
        onSyncDone = fn;
    },
    // Fired on the first envelope of a wake-by-sync — page shows its
    // "Syncing…" screen.
    setOnSyncWake: function (fn) {
        onSyncWake = fn;
    },
    // Called by app.js with a function (alarmId) => void that should route
    // back to the index page if the ring page is currently showing for
    // alarmId. Lets us close the ring screen when the phone reports the
    // alarm was dismissed/snoozed on its side (so the user doesn't have to
    // tap dismiss on both devices).
    setOnPeerEndedRing: function (fn) {
        onPeerEndedRing = fn;
    },
    setOnBgCleared: function (fn) {
        onBgCleared = fn;
    },
    handle: function (msg) {
        // Any recognized-shape envelope means the phone companion is
        // present — record the connection (+ companion version if carried)
        // so the onboarding screen can distinguish never-connected from a
        // connected-but-empty watch.
        if (msg && typeof msg.type === 'string') {
            noteConnection(msg);
        }
        // Meta-protocol envelopes (no alarmId / no updatedAtEpoch) MUST
        // bypass rejectMalformed, which requires both. sync_check is a
        // phone-originated request asking us to reply with our current
        // AlarmHash so it can skip a redundant force-sync.
        if (msg && msg.type === 'sync_check') {
            noteIncomingEnvelope();
            AlarmStore.getAll(function (items) {
                var hash = AlarmHash.compute(items);
                Logger.i('incoming.sync_check responding hash=' + hash + ' n=' + items.length);
                WearBridge.sendSyncHash(hash);
            });
            return;
        }
        // screen_request: phone asks for our panel size/shape so it can crop +
        // size the uploaded background to this watch. ALL comms are phone-
        // initiated — we only reply, never push proactively. Meta envelope
        // (no alarmId), so it bypasses rejectMalformed.
        if (msg && msg.type === 'screen_request') {
            noteIncomingEnvelope();
            // F4: thread the watch's current bg hash into the reply so the
            // phone can auto-restore a lost image / avoid re-sending a stale one.
            respondScreenRequest();
            return;
        }
        // settings_changed: phone is the sole editor of display prefs;
        // watch overwrites unconditionally (no LWW). Envelope carries
        // `use24Hour` (bool|null) and `firstDayOfWeek` (int|null where
        // 1..7 = SUNDAY..SATURDAY per java.util.Calendar). null on either
        // means "follow watch system locale".
        if (msg && msg.type === 'settings_changed') {
            noteIncomingEnvelope();
            var u = (msg.use24Hour === true || msg.use24Hour === false) ? msg.use24Hour : null;
            var d = (typeof msg.firstDayOfWeek === 'number' && isFinite(msg.firstDayOfWeek))
                ? msg.firstDayOfWeek : null;
            SettingsStore.apply(u, d, function (ok) {
                Logger.i('incoming.settings_changed applied ok=' + ok +
                    ' use24Hour=' + u + ' firstDow=' + d);
                bumpLastSync();
            });
            return;
        }
        // sync_replace: authoritative full-state replace from Force sync.
        // No top-level alarmId — bypass rejectMalformed.
        if (msg && msg.type === 'sync_replace') {
            noteIncomingEnvelope();
            applyReplace(msg);
            return;
        }
        // sync_done: end-of-sync marker. Terminate per the page's wired
        // onSyncDone (syncing page / app.js → app.terminate; index/ring
        // leave it unset). Do NOT noteIncomingEnvelope — sync_done means
        // "stop", not "more is coming".
        if (msg && msg.type === 'sync_done') {
            Logger.i('incoming.sync_done');
            if (onSyncDone) onSyncDone();
            return;
        }
        // watch_default_bg_cleared: phone cleared the default watch
        // background. No alarmId, so it bypasses rejectMalformed like the
        // other meta envelopes. Drop bgSrc/hasBg, forget the persisted path,
        // and delete the received file so it can't restore on relaunch.
        if (msg && msg.type === 'watch_default_bg_cleared') {
            noteIncomingEnvelope();
            Logger.i('incoming.watch_default_bg_cleared');
            if (onBgCleared) onBgCleared();
            return;
        }
        if (rejectMalformed(msg)) {
            Logger.w('incoming.rejected-malformed');
            return;
        }
        var type = msg.type;
        if (type === 'alarm_added' || type === 'alarm_updated') {
            if (!msg.alarm) {
                Logger.w('incoming.add-or-update missing alarm');
                return;
            }
            noteIncomingEnvelope();
            applyAddOrUpdate(msg);
        } else if (type === 'alarm_deleted') {
            noteIncomingEnvelope();
            applyDelete(msg);
        } else if (type === 'alarm_toggled') {
            noteIncomingEnvelope();
            applyToggle(msg);
        } else if (type === 'alarm_fired') {
            // Ring page takes over: cancel any in-flight drain timer and
            // latch _ringActive so a piggybacked sync envelope can't
            // router.replace us off the ring screen or terminate
            // mid-alarm. The ring page's own onHide/dismiss/snooze paths
            // own the eventual app.terminate.
            markRingActive();
            // Thin-client hints (see AlarmRingHints.kt) — when the phone
            // can resolve the alarm it inlines vibrationPattern + the
            // pre-computed snoozeAllowed flag so the watch rings with the
            // right pattern + Snooze visibility WITHOUT relying on a
            // prior sync_replace. Undefined fields = older phone build /
            // no resolution; the ring page falls back to AlarmStore.
            if (onAlarmFired) {
                onAlarmFired(msg.alarmId, {
                    vibrationPattern: msg.vibrationPattern,
                    snoozeAllowed: msg.snoozeAllowed,
                    watchVibrationEnabled: msg.watchVibrationEnabled,
                });
            }
        } else if (type === 'alarm_dismissed' || type === 'alarm_snoozed') {
            Logger.i('incoming.peer-ended type=' + type + ' id=' + msg.alarmId);
            // If our ring page is showing for this alarm, close it. The
            // callback is a no-op when no ring is active (see app.js).
            // `type` lets the ring page show "PHONE DISMISSED" vs
            // "PHONE SNOOZED" on its green-flash banner.
            if (onPeerEndedRing) onPeerEndedRing(msg.alarmId, type);
            // Application-level ack: tell the phone we actually processed
            // this envelope. Without it, the phone only sees the transport
            // 207 ACK which doesn't prove the JS receiver ran our handler
            // (it can be in a transient state and silently drop). The
            // phone's sendAlarmDismissed/SnoozedAwaiting is blocked on
            // this reply — fire it BEFORE armDrain so app.terminate
            // doesn't kill the send mid-flight.
            try {
                if (type === 'alarm_dismissed') {
                    WearBridge.sendDismissedAck(msg.alarmId);
                } else {
                    WearBridge.sendSnoozedAck(msg.alarmId);
                }
            } catch (e) {
                Logger.err('incoming.peer-ended ack send threw', e);
            }
            // Arm drain (without routing to syncing) so a watch woken
            // purely by a peer-ended envelope — no preceding ring — still
            // self-closes. When ring WAS active, ring.scheduleTerminate
            // covers it; the two terminates dedupe at the process level.
            armDrain();
        } else {
            Logger.w('incoming.unknown-type ' + type);
        }
    },
};
