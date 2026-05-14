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
import router from '@system.router';
import storage from '@system.storage';
import AlarmStore from './alarmStore.js';
import SettingsStore from './settingsStore.js';
import Tombstones from './tombstones.js';
import Lww from './lwwResolver.js';
import Logger from './logger.js';
import AlarmHash from './alarmHash.js';
import WearBridge from './wearBridge.js';

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
var DRAIN_QUIESCENCE_MS = 1500;

var _appStartMs = 0;
var _wokenForSync = false;
var _drainTimer = null;
var _navigatedToSyncing = false;
// Once an alarm_fired routes the user to the ring page, the ring page
// owns the rest of the process lifetime (its own onDismiss/onSnooze/onHide
// schedule app.terminate after the action settles). Any later envelope
// (sync_check piggybacking on the fire wake, alarm_added for a different
// row, etc.) MUST NOT router.replace to the syncing page or arm a drain
// timer — both would tear down the ring screen mid-alarm.
var _ringActive = false;

// Cross-bundle-shared diagnostic counter for inbound P2P messages. Per
// gotcha #11, app.js and pages/*.js have isolated module state, so a
// plain `var inboundCount = 0` here would not be visible from index.js's
// bundle. Persisting to @system.storage lets the index page read it.
function bumpInboundDiag(type) {
    storage.get({
        key: 'diag_inbound',
        default: '{"total":0,"byType":{}}',
        success: function (data) {
            var obj;
            try {
                obj = JSON.parse(data);
            } catch (e) {
                obj = { total: 0, byType: {} };
            }
            obj.total = (obj.total || 0) + 1;
            obj.lastType = type;
            obj.lastTs = Date.now();
            obj.byType = obj.byType || {};
            obj.byType[type] = (obj.byType[type] || 0) + 1;
            storage.set({
                key: 'diag_inbound',
                value: JSON.stringify(obj),
                success: function () {},
                fail: function (data2, code) {
                    Logger.err('diag_inbound.write fail code=' + code, null);
                },
            });
        },
        fail: function () {},
    });
}

var onAlarmFired = null;
var onPeerEndedRing = null;

// Called once from app.onCreate so we know when the app process started.
// Anything before WAKE_SYNC_GRACE_MS from now is treated as part of the
// wake-from-sync cold-launch window.
function markAppStart() {
    _appStartMs = Date.now();
    _wokenForSync = false;
    _navigatedToSyncing = false;
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
        Logger.i('incoming.wake-by-sync (elapsed=' + elapsed + 'ms) — routing to syncing');
        try {
            router.replace({ uri: 'pages/syncing/syncing' });
            _navigatedToSyncing = true;
        } catch (e) {
            Logger.err('incoming.route-syncing failed', e);
        }
    }
    armDrain();
}

// Stamp the AlarmStore's last-sync-epoch whenever any peer-driven mutation
// actually takes effect locally. Drives the "Last sync: N min ago" line on
// the index page.
function bumpLastSync() {
    AlarmStore.setLastSyncEpoch(Date.now());
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

export default {
    markAppStart: markAppStart,
    setOnAlarmFiredNavigator: function (fn) {
        onAlarmFired = fn;
    },
    // Called by app.js with a function (alarmId) => void that should route
    // back to the index page if the ring page is currently showing for
    // alarmId. Lets us close the ring screen when the phone reports the
    // alarm was dismissed/snoozed on its side (so the user doesn't have to
    // tap dismiss on both devices).
    setOnPeerEndedRing: function (fn) {
        onPeerEndedRing = fn;
    },
    handle: function (msg) {
        // Meta-protocol envelopes (no alarmId / no updatedAtEpoch) MUST
        // bypass rejectMalformed, which requires both. sync_check is a
        // phone-originated request asking us to reply with our current
        // AlarmHash so it can skip a redundant force-sync.
        if (msg && msg.type === 'sync_check') {
            bumpInboundDiag('sync_check');
            noteIncomingEnvelope();
            AlarmStore.getAll(function (items) {
                var hash = AlarmHash.compute(items);
                Logger.i('incoming.sync_check responding hash=' + hash + ' n=' + items.length);
                WearBridge.sendSyncHash(hash);
            });
            return;
        }
        // settings_changed: phone is the sole editor of display prefs;
        // watch overwrites unconditionally (no LWW). Envelope carries
        // `use24Hour` (bool|null) and `firstDayOfWeek` (int|null where
        // 1..7 = SUNDAY..SATURDAY per java.util.Calendar). null on either
        // means "follow watch system locale".
        if (msg && msg.type === 'settings_changed') {
            bumpInboundDiag('settings_changed');
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
        if (rejectMalformed(msg)) {
            Logger.w('incoming.rejected-malformed');
            // Still count the inbound bytes so the diag UI shows "we got
            // SOMETHING from phone even if it was unparseable" — useful
            // when fingerprint validation lets a message through but the
            // schema doesn't match our wire format.
            bumpInboundDiag('malformed');
            return;
        }
        var type = msg.type;
        bumpInboundDiag(type);
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
            if (onAlarmFired) onAlarmFired(msg.alarmId);
        } else if (type === 'alarm_dismissed' || type === 'alarm_snoozed') {
            Logger.i('incoming.peer-ended type=' + type + ' id=' + msg.alarmId);
            // If our ring page is showing for this alarm, close it. The
            // callback is a no-op when no ring is active (see app.js).
            if (onPeerEndedRing) onPeerEndedRing(msg.alarmId);
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
