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
import storage from '@system.storage';
import AlarmStore from './alarmStore.js';
import Tombstones from './tombstones.js';
import Lww from './lwwResolver.js';
import Logger from './logger.js';
import AlarmHash from './alarmHash.js';
import WearBridge from './wearBridge.js';

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
            AlarmStore.getAll(function (items) {
                var hash = AlarmHash.compute(items);
                Logger.i('incoming.sync_check responding hash=' + hash + ' n=' + items.length);
                WearBridge.sendSyncHash(hash);
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
            applyAddOrUpdate(msg);
        } else if (type === 'alarm_deleted') {
            applyDelete(msg);
        } else if (type === 'alarm_toggled') {
            applyToggle(msg);
        } else if (type === 'alarm_fired') {
            if (onAlarmFired) onAlarmFired(msg.alarmId);
        } else if (type === 'alarm_dismissed' || type === 'alarm_snoozed') {
            Logger.i('incoming.peer-ended type=' + type + ' id=' + msg.alarmId);
            // If our ring page is showing for this alarm, close it. The
            // callback is a no-op when no ring is active (see app.js).
            if (onPeerEndedRing) onPeerEndedRing(msg.alarmId);
        } else {
            Logger.w('incoming.unknown-type ' + type);
        }
    },
};
