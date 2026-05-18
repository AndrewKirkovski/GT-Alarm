// Single-page watch app. ALL logic — the one Wear Engine P2P receiver,
// every UI screen (list / ring / syncing), lifecycle, the log relay —
// lives in pages/index/index.js, the one and only page.
//
// Why one page: ACELite isolates each page into its own webpack bundle
// with its own module copies, its own P2pClient, its own receiver; and
// router navigation tears down the previous page's JS context. That
// fragmentation is what made phone->watch receive unreliable. A single
// never-navigated page keeps ONE persistent context: one receiver
// registered once, never clobbered, never backgrounded by navigation.
//
// ACELite still requires an app.js; it intentionally does nothing.
export default {
    onCreate: function () {},
    onDestroy: function () {},
};
