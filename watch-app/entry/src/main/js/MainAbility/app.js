import app from '@system.app';
import router from '@system.router';
import storage from '@system.storage';
import WearBridge from './common/wearBridge.js';
import IncomingHandler from './common/incomingHandler.js';
import Logger from './common/logger.js';

// MUST stay byte-identical to PEER_END_KEY in pages/ring/ring.js.
// Bumping this without bumping the reader silently disables the
// implicit-snooze guard — ring.js would then echo a snooze back to
// the phone right after the phone already dismissed/snoozed.
var PEER_END_KEY = 'ring_peer_ended_at_ms';

// Build tag — bump when the HAP is rebuilt for a fresh hardware test.
// Lite Wearable has no equivalent of PackageInfo.versionName at runtime,
// so this is the cheapest way to know which HAP is on the watch.
var BUILD_TAG = '0.1.0/2026-05-17-perpage-rx';

export default {
    onCreate: function () {
        // Wire the remote log sink FIRST so every subsequent Logger.i goes
        // both to HiLog AND over P2P to the phone (visible in adb logcat
        // under the WatchLog tag). Helps debug watch flow without DevEco
        // HiLog access.
        Logger.setRemoteSink(function (lines) {
            try {
                WearBridge.sendLogBatch(lines);
            } catch (e) {
                // Best-effort relay; never let it break the on-device log.
            }
        });
        WearBridge.setPageTag('app');
        Logger.i('app.onCreate build=' + BUILD_TAG);

        // Anchor the wake-reason clock BEFORE we install the incoming
        // handler. If we wired the handler first and an envelope arrived
        // before this call, the handler would see _appStartMs=0 and
        // skip the syncing-page route. Order matters.
        IncomingHandler.markAppStart();

        IncomingHandler.setOnAlarmFiredNavigator(function (alarmId) {
            Logger.i('app.onAlarmFired routing to ring id=' + alarmId);
            try {
                // Lite Wearable supports only router.replace — push/back
                // silently no-op.
                router.replace({
                    uri: 'pages/ring/ring',
                    params: { alarmId: alarmId },
                });
            } catch (e) {
                Logger.err('app.onAlarmFired router.replace', e);
            }
        });

        // Called when phone reports alarm_dismissed/alarm_snoozed.
        // ALWAYS route back to index — no active-id tracking needed.
        // (Earlier impl used a shared RingState module to skip the
        // navigation when the user already dismissed locally, but
        // LiteWearable per-page-bundle module isolation made the shared
        // state un-shareable: app.js's copy and ring.js's copy were
        // different singletons. See gotcha #11 in
        // memory:litewearable_rendering_gotchas.md.)
        //
        // The navigation is idempotent: if the user already tapped
        // dismiss locally and ring.js navigated to index, `router.replace`
        // to index again is a no-op visually. If the ring page is still
        // showing, this closes it.
        IncomingHandler.setOnPeerEndedRing(function (alarmId) {
            Logger.i('app.peer-ended routing to index for alarmId=' + alarmId);
            // Write the peer-ended marker BEFORE router.replace so that
            // ring.js's onHide can read it and skip the implicit-snooze
            // (task #85). The marker is a small ASCII number — well
            // under @system.storage's 128 B cap. Storage.set is async,
            // so we navigate inside its success callback to guarantee
            // ordering. If storage fails we still navigate; the worst
            // case is ring.js sees no flag and sends a redundant snooze
            // notify, which the phone's IncomingMessageHandler treats
            // as a no-op since the alarm is already dismissed/snoozed
            // on its side (idempotent per docs/sync-architecture §4.1).
            var goIndex = function () {
                try {
                    router.replace({ uri: 'pages/index/index' });
                    Logger.i('app.peer-ended router.replace returned');
                } catch (e) {
                    Logger.err('app.peer-ended router.replace', e);
                }
            };
            try {
                storage.set({
                    key: PEER_END_KEY,
                    value: '' + Date.now(),
                    success: goIndex,
                    fail: function (data, code) {
                        Logger.w('app.peer-ended marker write fail code=' + code);
                        goIndex();
                    },
                });
            } catch (e) {
                Logger.err('app.peer-ended marker write threw', e);
                goIndex();
            }
        });

        // sync_done while app.js's bootstrap receiver is still active
        // means the watch was woken purely for a sync and no page took
        // over — terminate. (The syncing page wires its own onSyncDone;
        // index/ring deliberately leave it unset.)
        IncomingHandler.setOnSyncDone(function () {
            Logger.i('app.sync_done — app.terminate()');
            try {
                app.terminate();
            } catch (e) {
                Logger.err('app.sync_done terminate threw', e);
            }
        });

        WearBridge.setIncomingHandler(function (msg) {
            try {
                IncomingHandler.handle(msg);
            } catch (e) {
                Logger.err('app.incomingHandler', e);
            }
        });
    },
    onDestroy: function () {
        Logger.i('app.onDestroy');
        WearBridge.setIncomingHandler(null);
        // Drain the batched log relay — the flush timer is foreground-only
        // and the process is about to die, so any queued lines would be
        // lost otherwise.
        Logger.flushNow();
    },
};
