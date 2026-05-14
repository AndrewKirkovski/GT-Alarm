import Logger from '../../common/logger.js';

// Shown briefly when the phone wakes the watch app via Wear Engine
// auto-launch to push a sync burst. IncomingHandler routes here on the
// first state-mutation envelope and calls app.terminate() after the
// burst quiesces. No interactive elements — the page is purely a
// visual placeholder so the user sees "Syncing alarms…" instead of an
// empty/stale index page during the ~1–2 s burst.

export default {
    data: {
        title: '',
        subtitle: '',
    },

    onInit: function () {
        Logger.i('syncing.onInit');
        this.title = this.$t('strings.syncing_title');
        this.subtitle = this.$t('strings.syncing_subtitle');
    },

    onShow: function () {
        Logger.i('syncing.onShow');
    },

    onHide: function () {
        Logger.i('syncing.onHide');
    },
};
