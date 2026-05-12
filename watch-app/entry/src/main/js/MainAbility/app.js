import router from '@system.router';
import WearBridge from './common/wearBridge.js';
import IncomingHandler from './common/incomingHandler.js';
import Logger from './common/logger.js';

// Build tag — bump when the HAP is rebuilt for a fresh hardware test.
// Lite Wearable has no equivalent of PackageInfo.versionName at runtime,
// so this is the cheapest way to know which HAP is on the watch.
var BUILD_TAG = '0.1.0/2026-05-12-oneoff';

export default {
    onCreate: function () {
        // Wire the remote log sink FIRST so every subsequent Logger.i goes
        // both to HiLog AND over P2P to the phone (visible in adb logcat
        // under the WatchLog tag). Helps debug watch flow without DevEco
        // HiLog access.
        Logger.setRemoteSink(function (level, msg) {
            try {
                WearBridge.sendLog(level, msg);
            } catch (e) {
                // Best-effort relay; never let it break the on-device log.
            }
        });
        Logger.i('app.onCreate build=' + BUILD_TAG);

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
            // Belt-and-suspenders: hit the vibrator with a no-arg call OR
            // a tiny pulse to flush the current buzz. The ring page's
            // setInterval lives on `this._vibrateTimer` and clears on
            // onHide — but if onHide fires after the next buzz tick, the
            // user sees one extra pulse. There's no documented
            // vibrator.stop() on Lite Wearable; the safest signal we can
            // send is to NOT call vibrate again (the page's clearInterval
            // handles that). This block is currently a placeholder so a
            // future @system.vibrator API addition has an obvious home.
            // For now we rely on the page lifecycle.
            try {
                router.replace({ uri: 'pages/index/index' });
                Logger.i('app.peer-ended router.replace returned');
            } catch (e) {
                Logger.err('app.peer-ended router.replace', e);
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
    },
};
