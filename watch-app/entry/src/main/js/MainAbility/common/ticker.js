// One repeating timer that runs named deadlines. It exists because ACELite
// drops a ONE-SHOT timer silently when the app message queue is full at fire
// time (arkui_ace_engine_lite timer_module.cpp, Task(): DispatchAsyncWork fails
// -> `if (!check && !arg->repeated) DeleteTimer`). A cold wake floods that
// queue, so setTimeout chains died mid-ring: the watch buzzed once and stopped
// (device-proven 2026-09-28). A REPEATING timer survives a failed dispatch: the
// tick is skipped and the next one runs. Deadlines are time-based, so a skipped
// tick only delays them by one tick.
//
// The interval runs only while at least one deadline is pending. Deadlines fire
// when `now` is within half a tick of their due time, so delays round to the
// nearest TICK_MS instead of always rounding up.
//
// Module state is per bundle (Lite gotcha: each page bundle gets its own copy
// of common/*). Everything that uses this lives in the index page bundle.
// ES5 only.
import Logger from './logger.js';

var TICK_MS = 250;
var HALF_TICK_MS = 125;
// A deadline this late means ticks were being dropped; log it once per fire.
var LATE_LOG_MS = 1000;

var tasks = {};
var count = 0;
var handle = null;

function run() {
    var now = Date.now();
    var names = [];
    var k;
    for (k in tasks) {
        if (tasks.hasOwnProperty(k) && now >= tasks[k].due - HALF_TICK_MS) {
            names.push(k);
        }
    }
    for (var i = 0; i < names.length; i++) {
        var t = tasks[names[i]];
        // A callback run earlier in this loop may have cleared or replaced it.
        if (!t || now < t.due - HALF_TICK_MS) continue;
        delete tasks[names[i]];
        count--;
        if (now - t.due > LATE_LOG_MS) {
            Logger.w('ticker late name=' + names[i] + ' by=' + (now - t.due) + 'ms');
        }
        try {
            t.fn();
        } catch (e) {
            Logger.err('ticker ' + names[i], e);
        }
    }
    if (count <= 0) stop();
}

function start() {
    if (handle !== null) return;
    var h = setInterval(run, TICK_MS);
    if (h === undefined) {
        // Could not create the OS timer. poke() from the page's 2 s poll is
        // the backstop; the next set() retries creation.
        Logger.w('ticker setInterval returned undefined');
        return;
    }
    handle = h;
}

function stop() {
    count = 0;
    // Ids can be 0, so compare against null, not truthiness.
    if (handle !== null) {
        clearInterval(handle);
        handle = null;
    }
}

export default {
    // Run fn once, delayMs from now. Re-setting a name replaces its deadline.
    set: function (name, delayMs, fn) {
        if (!tasks.hasOwnProperty(name)) count++;
        tasks[name] = { due: Date.now() + delayMs, fn: fn };
        start();
    },
    clear: function (name) {
        if (!tasks.hasOwnProperty(name)) return;
        delete tasks[name];
        count--;
        if (count <= 0) stop();
    },
    has: function (name) {
        return tasks.hasOwnProperty(name);
    },
    // Backstop for a missing interval: run due deadlines now.
    poke: function () {
        if (count > 0) {
            if (handle === null) start();
            run();
        }
    },
};
