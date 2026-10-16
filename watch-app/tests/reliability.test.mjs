import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import test from 'node:test'

const common = new URL('../entry/src/main/js/MainAbility/common/', import.meta.url)
const logger = { i() {}, w() {}, err() {} }

function load(name, bindings) {
  const source = fs.readFileSync(new URL(name, common), 'utf8')
  assert.equal((source.match(/export default/g) || []).length, 1)
  const context = { module: { exports: {} }, ...bindings }
  vm.runInNewContext(source.replace(/^import .*;\r?$/gm, '').replace('export default', 'module.exports ='),
    context, { timeout: 1000 })
  return context.module.exports
}

function fixture({ initial = [], fail = false, delayed = false, asyncRead = false } = {}) {
  let disk = JSON.stringify(initial)
  let now = 1000
  let terminated = 0
  const writes = []
  const reads = []
  const acks = []
  const timers = new Map()
  class Clock extends Date { static now() { return now } }
  const ticker = {
    set(name, delay, callback) { timers.set(name, callback) },
    clear(name) { timers.delete(name) },
    has(name) { return timers.has(name) },
    poke() {},
  }
  const file = {
    readText(options) {
      const read = () => options.success({ text: disk })
      if (asyncRead) reads.push(read)
      else read()
    },
    writeText(options) {
      const complete = () => {
        if (fail) options.fail({}, 99)
        else { disk = options.text; options.success() }
      }
      if (delayed) writes.push(complete)
      else complete()
    },
  }
  const store = load('alarmStore.js', { file, Logger: logger, Ticker: ticker })
  const hash = load('alarmHash.js', {})
  const handler = load('incomingHandler.js', {
    Date: Clock, app: { terminate() { terminated++ } }, storage: {}, AlarmStore: store,
    SettingsStore: {}, Logger: logger,
    AlarmHash: hash, WearBridge: { sendAlarmsReceived(...args) { acks.push(args) } },
    Screen: {}, Ticker: ticker,
  })
  return {
    store, handler, acks, timers,
    disk: () => JSON.parse(disk),
    finishWrite: () => { assert.ok(writes.length); writes.shift()() },
    flush: () => { while (writes.length) writes.shift()() },
    pending: () => writes.length,
    pendingReads: () => reads.length,
    finishRead: () => { assert.ok(reads.length); reads.shift()() },
    advance(ms) { now += ms },
    terminated: () => terminated,
    fire(name) { const fn = timers.get(name); assert.ok(fn, name); timers.delete(name); fn() },
  }
}

const alarm = id => ({ id, hour: 7, minute: 0, daysOfWeek: 0, enabled: true, updatedAtEpoch: id })

test('store replacement preserves successful persistence behavior', () => {
  const f = fixture()
  let result
  f.store.replaceAll([alarm(1)], ok => { result = ok })
  assert.equal(result, true)
  assert.deepEqual(f.disk().map(a => a.id), [1])
})

test('failed replacement reports failure and retains prior persisted list', () => {
  const f = fixture({ initial: [alarm(1)], fail: true })
  let result
  f.store.replaceAll([alarm(2)], ok => { result = ok })
  assert.equal(result, false)
  assert.deepEqual(f.disk().map(a => a.id), [1])
  assert.equal(f.acks.length, 0)
})

test('file replacement cannot confirm a failed store write', () => {
  const f = fixture({ initial: [alarm(1)], fail: true })
  let result
  f.handler.applyReplaceFromFile({ type: 'sync_replace', alarms: [alarm(2)] }, ok => { result = ok })
  assert.equal(result, false)
  assert.deepEqual(f.disk().map(a => a.id), [1])
})

test('sync_done waits for a delayed replacement before closing', () => {
  const f = fixture({ delayed: true })
  let closed = false
  f.handler.setOnSyncDone(() => { closed = true })
  f.handler.handle({ type: 'sync_replace', updatedAtEpoch: 10, alarms: [alarm(1)] })
  f.handler.handle({ type: 'sync_done', updatedAtEpoch: 11 })
  assert.equal(closed, false)
  f.flush()
  assert.equal(closed, true)
  assert.deepEqual(f.disk().map(a => a.id), [1])
})

test('overlapping additive messages retain both updates with delayed writes', () => {
  const f = fixture({ delayed: true })
  for (const id of [1, 2]) {
    f.handler.handle({ type: 'alarm_added', alarmId: id, updatedAtEpoch: id, alarm: alarm(id) })
  }
  f.flush()
  assert.deepEqual(f.disk().map(a => a.id).sort(), [1, 2])
})

test('replacement acknowledges the saved hash and exact request only after storage completes', () => {
  const f = fixture({ delayed: true })
  f.handler.handle({ type: 'sync_replace', updatedAtEpoch: 12345, alarms: [alarm(1)] })
  assert.equal(f.acks.length, 0)
  f.flush()
  const hash = load('alarmHash.js', {}).compute([alarm(1)])
  assert.deepEqual(f.acks, [[hash, 12345]])
})

test('a write callback that never fires fails after the deadline and never acknowledges', () => {
  const f = fixture({ delayed: true })
  f.handler.handle({ type: 'sync_replace', updatedAtEpoch: 7, alarms: [alarm(1)] })
  let added
  f.store.add(alarm(2), ok => { added = ok })
  assert.equal(f.pending(), 1)
  f.fire('storeJob')
  assert.equal(f.acks.length, 0, 'a timeout must not acknowledge')
  assert.equal(f.handler.hasPendingWrites(), false, 'the failed write is released')
  assert.equal(f.pending(), 1, 'the next write waits for the late native write')
  assert.equal(added, undefined)
  // The late native write finishes: only now may the queue continue.
  f.finishWrite()
  assert.equal(f.acks.length, 0, 'the late callback must not acknowledge either')
  assert.equal(f.pending(), 1, 'the queued write starts after the late callback')
  f.flush()
  assert.equal(added, true)
  assert.deepEqual(f.disk().map(a => a.id).sort(), [1, 2])
  assert.equal(f.acks.length, 0)
})

test('a write that never calls back at all lets the queue continue at a second deadline', () => {
  const f = fixture({ delayed: true })
  f.store.replaceAll([alarm(1)], () => {})
  let added
  f.store.add(alarm(2), ok => { added = ok })
  f.fire('storeJob')
  assert.equal(f.pending(), 1)
  f.fire('storeHold')
  assert.equal(f.pending(), 2, 'the queued write starts after the second deadline')
  f.flush()
  assert.equal(added, true)
})

test('the replacement hash is read back inside the write queue and a mismatch is not acknowledged', () => {
  const f = fixture({ asyncRead: true })
  f.handler.handle({ type: 'sync_replace', updatedAtEpoch: 9, alarms: [alarm(1)] })
  let added
  f.store.add(alarm(2), ok => { added = ok })
  assert.equal(f.pendingReads(), 1, 'no other write may start before the read-back')
  f.finishRead()
  const hash = load('alarmHash.js', {}).compute([alarm(1)])
  assert.deepEqual(f.acks, [[hash, 9]])
  while (f.pendingReads()) f.finishRead()
  assert.equal(added, true)

  const g = fixture()
  g.handler.handle({ type: 'sync_replace', updatedAtEpoch: 9, hash: 'not-the-hash', alarms: [alarm(1)] })
  assert.equal(g.acks.length, 0)
})

test('sync_done held for a stuck write still runs at its absolute deadline', () => {
  const f = fixture({ delayed: true })
  let closed = 0
  f.handler.setOnSyncDone(() => { closed++ })
  f.handler.handle({ type: 'sync_replace', updatedAtEpoch: 10, alarms: [alarm(1)] })
  f.handler.handle({ type: 'sync_done', updatedAtEpoch: 11 })
  assert.equal(closed, 0)
  f.fire('syncDoneHold')
  assert.equal(closed, 1)
  f.flush()
  assert.equal(closed, 1, 'it must not run twice')
})

test('the drain timer stops waiting for pending writes after the absolute deadline', () => {
  const f = fixture({ delayed: true })
  f.handler.markAppStart()
  f.handler.handle({ type: 'sync_replace', updatedAtEpoch: 10, alarms: [alarm(1)] })
  f.fire('drain')
  assert.equal(f.terminated(), 0)
  assert.ok(f.timers.has('drain'), 'it re-arms while writes are pending')
  f.advance(21000)
  f.fire('drain')
  assert.equal(f.terminated(), 1)
})

const ACTION_KEY = 'gt_pending_action'

function ringFixture({ transportOk = true, setMode = 'now', deleteMode = 'now', getMode = 'now' } = {}) {
  let now = 5000
  let terminated = 0
  const timers = new Map()
  const delays = new Map()
  const counts = new Map()
  const values = new Map()
  const sets = []
  const deferred = []
  const gets = []
  const deletes = []
  const sent = []
  const holds = []
  const ringing = []
  const ticker = {
    set(name, delay, callback) {
      timers.set(name, callback); delays.set(name, delay)
      counts.set(name, (counts.get(name) || 0) + 1)
    },
    clear(name) { timers.delete(name) },
    has(name) { return timers.has(name) },
    poke() {},
  }
  const storage = {
    set(o) {
      sets.push(o.key)
      // 'never': the native write never calls back. 'later': it lands on demand.
      const land = () => { values.set(o.key, o.value); o.success?.() }
      if (setMode === 'later') deferred.push(land)
      else if (setMode === 'now') land()
    },
    get(o) {
      // 'later': the read captures the value now and answers on demand.
      const value = values.get(o.key) ?? o.default
      if (getMode === 'later') gets.push(() => o.success(value))
      else o.success(value)
    },
    delete(o) {
      const land = () => { values.delete(o.key); o.success?.() }
      if (deleteMode === 'later') deletes.push(land)
      else land()
    },
  }
  const lives = []
  const timeouts = []
  const send = type => (id, firedAt, stamp, done, isLive) => {
    sent.push({ type, alarmId: id, firedAt, updatedAtEpoch: stamp })
    lives.push(isLive)
    done(transportOk, 'transport')
  }
  class Clock extends Date { static now() { return now } }
  const page = load('../pages/index/index.js', {
    Date: Clock, Logger: { ...logger, flushNow() {} }, Ticker: ticker, storage,
    app: { terminate() { terminated++ } }, file: {}, vibrator: { vibrate() {} },
    brightness: { setKeepScreenOn(o) { holds.push(o.keepScreenOn); o.success() } },
    WearBridge: { sendRinging(...args) { ringing.push(args) },
      setPageTag() {}, setFileHandler() {}, setIncomingHandler() {},
      notifyDismissed: send('alarm_dismissed'), notifySnoozed: send('alarm_snoozed') },
    AlarmStore: { getAll(done) { done([]) }, replaceAll(items, done) { done?.(true) },
      deleteById(id, done) { done?.(true) }, setOnChange() {} },
    SettingsStore: { get(done) { done({ use24Hour: true, firstDayOfWeek: 2 }) } },
    Screen: {}, PrivacyStore: {}, IncomingHandler: {},
    // One-shot timers never fire on their own here (a dropped cold-start timer);
    // a test runs them from `timeouts` when it wants a healthy queue.
    setTimeout(fn) { timeouts.push(fn); return 1 }, clearTimeout() {},
    setInterval() { return 1 }, clearInterval() {},
  })
  Object.assign(page, page.data, { $t: key => key })
  return { page, timers, delays, counts, values, sets, deferred, sent, holds, ringing, lives, deletes, gets, timeouts,
    terminated: () => terminated,
    setNow(value) { now = value },
    tick(name) { const fn = timers.get(name); assert.ok(fn, name); timers.delete(name); now += 1000; fn() },
    ring(id = 1, firedAt = 1000, extra = {}) {
      page.enterRing(id, { firedAt, updatedAtEpoch: 2000, supportsActionAck: true, ...extra })
    },
    ack(extra = {}) { page._handleActionAck({ ...sent.at(-1), type: 'alarm_dismissed_ack', ok: true, ...extra }) },
  }
}

test('Bluetooth delivery alone does not end a ring on a capable phone', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  assert.equal(f.page.ended, false)
  assert.equal(f.holds.at(-1), true)
  f.ack()
  assert.equal(f.page.ended, true)
  assert.equal(f.holds.at(-1), false)
})

test('action retries retain the original request and occurrence', () => {
  const f = ringFixture({ transportOk: false })
  f.ring()
  f.page.onDismiss()
  f.tick('actionRetry')
  assert.deepEqual(f.sent[1], f.sent[0])
  assert.equal(f.page.ended, false)
})

test('a rejected action does not show a success confirmation', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  f.ack({ ok: false })
  assert.equal(f.page.ended, false)
  f.page.onDismiss()
  assert.equal(f.sent.length, 2)
  assert.ok(f.sent[1].updatedAtEpoch > f.sent[0].updatedAtEpoch)
})

test('a lost old action reply cannot end the replacement ring', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  const reply = { ...f.sent[0], type: 'alarm_dismissed_ack', ok: true }
  f.ring(2, 3000)
  f.page._handleActionAck(reply)
  assert.equal(f.page.screen, 'ring')
  assert.equal(f.page.ended, false)
  assert.equal(f.holds.at(-1), true)
})

test('capable action replies require the expected occurrence and action type', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  f.ack({ type: 'alarm_snoozed_ack' })
  assert.equal(f.page.ended, false)
  f.ack({ firedAt: undefined })
  assert.equal(f.page.ended, false)
})

test('repeat fire envelopes for one occurrence do not restart vibration', () => {
  const f = ringFixture()
  let starts = 0
  f.page.startVibrate = () => { starts++ }
  f.ring()
  f.ring(1, 1000, { updatedAtEpoch: 2500 })
  assert.equal(starts, 1)
  assert.equal(f.ringing.at(-1)[2], 2500)
})

test('an old phone end message does not close a newer occurrence', () => {
  const f = ringFixture()
  f.ring(1, 3000)
  assert.equal(f.page.onPeerEnded({ alarmId: 1, firedAt: 1000, updatedAtEpoch: 3500 }, 'alarm_dismissed'), false)
  assert.equal(f.page.ended, false)
  assert.equal(f.page.onPeerEnded({ alarmId: 1, updatedAtEpoch: 3500 }, 'alarm_dismissed'), false)
})

test('legacy phone action delivery retains the old completion behavior', () => {
  const f = ringFixture()
  f.ring(1, 1000, { supportsActionAck: false })
  f.page.onDismiss()
  assert.equal(f.page.ended, true)
})

function countVibration(page) {
  const count = { starts: 0, crown: 0 }
  page.startVibrate = () => { count.starts++ }
  page._armCrownGesture = () => { count.crown++ }
  return count
}

test('a phone that sends no ack flag completes on delivery and saves no record', () => {
  const f = ringFixture()
  f.ring(1, 1000, { supportsActionAck: false })
  f.page.onDismiss()
  assert.equal(f.page.ended, true)
  assert.ok(!f.sets.includes(ACTION_KEY), 'no record for a phone that never replies')
  assert.equal(f.values.has(ACTION_KEY), false)
})

test('a released 1.0.11 ring completes the action on delivery and saves no record', () => {
  const f = wiredFixture()
  f.handler.handle({ ...released.phoneToWatch_1_0_11.alarm_fired })
  f.page.onDismiss()
  assert.equal(f.page.ended, true)
  assert.equal(f.sent.length, 1)
  assert.ok(!f.sets.includes(ACTION_KEY))
  assert.equal(f.values.has(ACTION_KEY), false)
})

// A released 1.0.11 phone puts firedAt in alarm_fired but not in its end
// messages. A phone Stop or Snooze must still end the ring on an updated watch;
// a stricter firedAt check in onPeerEnded would leave the watch buzzing.
for (const type of ['alarm_dismissed', 'alarm_snoozed']) {
  test(`a released 1.0.11 phone ${type} without firedAt ends a ring that has one`, () => {
    const f = wiredFixture()
    f.handler.handle({ ...released.phoneToWatch_1_0_11.alarm_fired })
    assert.equal(f.page.screen, 'ring')
    assert.ok(f.timers.has('vibrate'), 'the ring buzzes')
    f.handler.handle({ ...released.phoneToWatch_1_0_11[type] })
    assert.equal(f.page.ended, true)
    assert.equal(f.timers.has('vibrate'), false, 'the buzz stops')
    assert.equal(f.peerAcks.length, 1, 'the phone gets its confirmation')
  })
}

test('a 1.0.10 phone resending alarm_fired without firedAt does not restart the ring', () => {
  const f = wiredFixture()
  const count = countVibration(f.page)
  const fired = released.phoneToWatch_1_0_10.alarm_fired
  f.handler.handle({ ...fired })
  f.handler.handle({ ...fired, updatedAtEpoch: fired.updatedAtEpoch + 700 })
  assert.equal(count.starts, 1)
  assert.equal(f.ringing.length, 2, 'every copy is still confirmed')
})

test('a refused action rings the watch again and a new tap works', () => {
  const f = ringFixture()
  const count = countVibration(f.page)
  f.ring()
  const before = count.starts
  f.page.onDismiss()
  assert.equal(f.values.has(ACTION_KEY), true)
  assert.ok(f.timers.has('terminate'))
  f.ack({ ok: false })
  assert.equal(count.starts, before + 1, 'the buzz restarts')
  assert.equal(f.values.has(ACTION_KEY), false, 'the record is deleted')
  assert.equal(f.page.ended, false)
  assert.equal(f.holds.at(-1), true, 'the screen is held')
  assert.equal(f.timers.has('terminate'), false, 'the action cap is cancelled')
  assert.equal(f.delays.get('ringCap'), 360000)
  f.tick('crownArm')
  assert.equal(count.crown, 1, 'the crown is armed again')
  assert.equal(f.terminated(), 0)
  f.page.onSnooze()
  assert.equal(f.sent.length, 2, 'the controls work again')
  assert.equal(f.timers.has('ringCap'), false, 'the new request cap replaces the ring cap')
  assert.ok(f.timers.has('terminate'))
})

test('the six minute cap of a refused request closes the app', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  f.ack({ ok: false })
  f.tick('ringCap')
  assert.equal(f.terminated(), 1)
})

test('a matching phone end message ends the ring and deletes the record', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  assert.equal(f.values.has(ACTION_KEY), true)
  assert.equal(f.page.onPeerEnded({ alarmId: 1, firedAt: 1000, updatedAtEpoch: 9000 }, 'alarm_snoozed'), true)
  assert.equal(f.page.ended, true)
  assert.equal(f.values.has(ACTION_KEY), false)
  f.ack()
  assert.equal(f.page.ended, true)
})

test('an end message for another occurrence keeps the pending request', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  assert.equal(f.page.onPeerEnded({ alarmId: 1, firedAt: 999, updatedAtEpoch: 9000 }, 'alarm_dismissed'), false)
  assert.equal(f.page.ended, false)
  assert.equal(f.values.has(ACTION_KEY), true)
})

test('with no reply after every try the request is released and the app still closes', () => {
  const f = ringFixture()
  const count = countVibration(f.page)
  f.ring()
  const before = count.starts
  f.page.onDismiss()
  for (let round = 0; round < 3; round++) {
    f.tick('actionAckWait')
    if (round < 2) f.tick('actionRetry')
  }
  assert.equal(f.sent.length, 3)
  assert.equal(f.values.has(ACTION_KEY), false, 'the record is deleted')
  assert.equal(f.page.ended, false)
  assert.equal(f.timers.has('peerFlash'), false, 'no success flash')
  assert.equal(count.starts, before, 'the buzz stays off')
  assert.ok(f.timers.has('terminate'), 'the action cap still closes the app')
  f.page.onDismiss()
  assert.equal(f.sent.length, 4, 'a new tap is accepted')
  assert.ok(f.sent[3].updatedAtEpoch > f.sent[2].updatedAtEpoch)
})

test('a failing delivery path also releases the request after the last try', () => {
  const f = ringFixture({ transportOk: false })
  f.ring(1, 1000, { supportsActionAck: false })
  f.page.onDismiss()
  f.tick('actionRetry')
  f.tick('actionRetry')
  assert.equal(f.sent.length, 3)
  assert.equal(f.page.ended, false)
  f.page.onDismiss()
  assert.equal(f.sent.length, 4)
})

test('the action is sent even when the record write never calls back', () => {
  const f = ringFixture({ setMode: 'never' })
  f.ring()
  f.page.onDismiss()
  assert.equal(f.sent.length, 1)
  f.ack()
  assert.equal(f.page.ended, true)
})

test('a record write that lands after the request ended is deleted again', () => {
  const f = ringFixture({ setMode: 'later' })
  f.ring()
  f.page.onDismiss()
  assert.equal(f.sent.length, 1)
  f.ack()
  assert.equal(f.page.ended, true)
  assert.ok(f.deferred.length >= 1)
  while (f.deferred.length) f.deferred.shift()()
  assert.equal(f.values.has(ACTION_KEY), false)
})

function resumeFixture(record, { ring = false, now = 500000 } = {}) {
  const f = ringFixture()
  f.setNow(now)
  if (ring) f.ring(ring.id, ring.firedAt)
  const before = f.sent.length
  f.values.set(ACTION_KEY, JSON.stringify(record))
  f.page._resumePendingAction()
  return { f, sentNow: f.sent.length - before }
}

const goodRecord = { t: 'd', a: 1, f: 1000, s: 499000, v: 1 }

test('a fresh record from a replying phone is replayed with its original request', () => {
  const { f, sentNow } = resumeFixture(goodRecord)
  assert.equal(sentNow, 1)
  assert.deepEqual(f.sent.at(-1), { type: 'alarm_dismissed', alarmId: 1, firedAt: 1000, updatedAtEpoch: 499000 })
  assert.equal(f.values.has(ACTION_KEY), true)
  const same = resumeFixture(goodRecord, { ring: { id: 1, firedAt: 1000 } })
  assert.equal(same.sentNow, 1)
})

test('stale, flagless, occurrence-less and mismatched records are deleted and not replayed', () => {
  const cases = [
    ['older than two minutes', { ...goodRecord, s: 300000 }, undefined],
    ['from a phone that never replies', { ...goodRecord, v: 0 }, undefined],
    ['without an occurrence', { ...goodRecord, f: 0 }, undefined],
    ['for another occurrence of the ring on screen', goodRecord, { id: 1, firedAt: 2000 }],
    ['for another alarm on screen', goodRecord, { id: 2, firedAt: 1000 }],
  ]
  for (const [name, record, ring] of cases) {
    const { f, sentNow } = resumeFixture(record, { ring })
    assert.equal(sentNow, 0, name)
    assert.equal(f.values.has(ACTION_KEY), false, name)
  }
})

test('a ring for a different occurrence deletes a stored record that is not in memory', () => {
  const f = ringFixture()
  f.values.set(ACTION_KEY, JSON.stringify(goodRecord))
  f.ring(1, 5000)
  assert.equal(f.values.has(ACTION_KEY), false)
  f.values.set(ACTION_KEY, JSON.stringify(goodRecord))
  f.ring(1, 5000, { updatedAtEpoch: 2500 })
  assert.equal(f.values.has(ACTION_KEY), true, 'a copy of the same ring keeps it')
})

test('the sync fallback stops waiting for pending writes after its absolute deadline', () => {
  const f = wiredFixture({ holdWrites: true })
  f.handler.handle({ type: 'sync_replace', updatedAtEpoch: 10, alarms: [{ id: 1, hour: 7, minute: 0 }] })
  assert.equal(f.page.screen, 'sync')
  f.fire('syncFallback')
  assert.equal(f.terminated(), 0)
  assert.ok(f.timers.has('syncFallback'), 'it waits while a write is pending')
  f.advance(21000)
  f.fire('syncFallback')
  assert.equal(f.terminated(), 1)
})

test('a replayed request for the ringing occurrence shows the ring without buzzing and closes the app', () => {
  const f = wiredFixture()
  const record = { t: 'd', a: 3, f: 900000, s: f.now() - 1000, v: 1 }
  f.values.set(ACTION_KEY, JSON.stringify(record))
  f.page._resumePendingAction()
  f.handler.handle({ type: 'alarm_fired', alarmId: 3, updatedAtEpoch: f.now(), firedAt: 900000,
    supportsActionAck: true })
  assert.equal(f.page.screen, 'ring')
  assert.equal(f.timers.has('vibrate'), false, 'no buzz while the request is in flight')
  assert.ok(f.timers.has('terminate'), 'the app must be able to close')
  f.handler.handle({ type: 'alarm_dismissed_ack', alarmId: 3, updatedAtEpoch: record.s, firedAt: 900000, ok: true })
  assert.equal(f.page.ended, true)
  assert.equal(f.timers.has('vibrate'), false)
  f.fire('peerFlash')
  assert.equal(f.timers.has('vibrate'), false)
  assert.ok(f.timers.has('terminate'))
})

test('completing a replayed request stops the buzz and closes the app after the flash', () => {
  const f = ringFixture()
  f.ring(1, 1000)
  assert.ok(f.timers.has('vibrate'))
  f.values.set(ACTION_KEY, JSON.stringify({ t: 'd', a: 1, f: 1000, s: 4000, v: 1 }))
  f.page._resumePendingAction()
  f.ack()
  assert.equal(f.page.ended, true)
  assert.equal(f.timers.has('vibrate'), false, 'the buzz stops')
  f.tick('peerFlash')
  assert.equal(f.delays.get('terminate'), 1400, 'the app closes after the flash')
})

test('a stray action reply that wakes a cold app closes it again', () => {
  const f = wiredFixture()
  f.handler.handle({ type: 'alarm_dismissed_ack', alarmId: 3, updatedAtEpoch: 990000, firedAt: 900000, ok: true })
  assert.ok(f.timers.has('terminate'))

  const late = wiredFixture()
  late.advance(11000)
  late.handler.handle({ type: 'alarm_dismissed_ack', alarmId: 3, updatedAtEpoch: 990000, firedAt: 900000, ok: true })
  assert.equal(late.timers.has('terminate'), false, 'an app that has been open a while stays')

  const ringing = wiredFixture()
  ringing.handler.handle({ type: 'alarm_fired', alarmId: 3, updatedAtEpoch: 1, firedAt: 900000, supportsActionAck: true })
  ringing.handler.handle({ type: 'alarm_dismissed_ack', alarmId: 3, updatedAtEpoch: 990000, firedAt: 900000, ok: true })
  assert.equal(ringing.timers.has('terminate'), false, 'a ringing watch is never closed by a stray reply')
})

test('a replayed request that completes on a cold list screen closes the app', () => {
  const f = wiredFixture()
  const record = { t: 's', a: 3, f: 900000, s: f.now() - 1000, v: 1 }
  f.values.set(ACTION_KEY, JSON.stringify(record))
  f.page._resumePendingAction()
  f.handler.handle({ type: 'alarm_snoozed_ack', alarmId: 3, updatedAtEpoch: record.s, firedAt: 900000, ok: true })
  assert.ok(f.timers.has('terminate'))
  assert.equal(f.values.has(ACTION_KEY), false)
})

test('a second tap after the retries ran out gets its own full action cap', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  for (let round = 0; round < 3; round++) {
    f.tick('actionAckWait')
    if (round < 2) f.tick('actionRetry')
  }
  const capSets = f.counts.get('terminate')
  f.page.onDismiss()
  assert.equal(f.counts.get('terminate'), capSets + 1)
  assert.equal(f.delays.get('terminate'), 11000)
})

test('a phone end message during the success flash does not redo the ring end', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  f.ack()
  assert.equal(f.page.onPeerEnded({ alarmId: 1, firedAt: 1000, updatedAtEpoch: 9000 }, 'alarm_dismissed'), true)
  assert.equal(f.sets.filter(key => key === 'gt_last_ring').length, 1)
  f.tick('peerFlash')
  assert.equal(f.delays.get('terminate'), 1400, 'the close is still sped up')
})

test('a ring without firedAt never waits for a reply, even if the flag is set', () => {
  const f = ringFixture()
  f.ring(1, 0)
  f.page.onDismiss()
  assert.equal(f.page.ended, true)
  assert.equal(f.sets.includes(ACTION_KEY), false)
})

test('the screen hold is released on hide and taken again on show while a request is open', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  f.page.onHide()
  assert.equal(f.holds.at(-1), false)
  f.page.onShow()
  assert.equal(f.holds.at(-1), true)
  f.ack()
  f.page.onHide()
  const holdsNow = f.holds.length
  f.page.onShow()
  assert.equal(f.holds.length, holdsNow, 'an ended ring takes no hold')
})

test('an end message for another occurrence is confirmed to the phone without ending the ring', () => {
  const f = wiredFixture()
  f.handler.handle({ type: 'alarm_fired', alarmId: 3, updatedAtEpoch: 5, firedAt: 2000, supportsActionAck: true })
  f.handler.handle({ type: 'alarm_dismissed', alarmId: 3, updatedAtEpoch: 6, firedAt: 1000 })
  assert.equal(f.page.ended, false)
  assert.equal(f.page.screen, 'ring')
  assert.equal(f.peerAcks.length, 1)
})

test('a late ok:true for a request that ran out of tries still ends the ring', () => {
  const f = ringFixture()
  f.ring()
  f.page.onDismiss()
  for (let round = 0; round < 3; round++) {
    f.tick('actionAckWait')
    if (round < 2) f.tick('actionRetry')
  }
  assert.equal(f.page.ended, false)
  f.page._handleActionAck({ ...f.sent[0], type: 'alarm_dismissed_ack', ok: true })
  assert.equal(f.page.ended, true)
  assert.equal(f.sets.filter(key => key === 'gt_last_ring').length, 1)
})


test('a request the page gave up on is not re-sent by the transport retry chain', () => {
  const f = transportFixture()
  let live = true
  let result = 'none'
  f.bridge.notifyDismissed(1, 1000, 5000, ok => { result = ok }, () => live)
  assert.equal(f.sent.length, 1)
  f.tick() // the missing SDK callback expires; a retry is scheduled
  assert.equal(f.sent.length, 1)
  live = false
  while (f.timers.size) f.tick()
  assert.equal(f.sent.length, 1, 'no further send of that request')
  assert.equal(result, 'none')
})

test('a refused, accepted or exhausted request is no longer live for the transport retries', () => {
  const refused = ringFixture()
  refused.ring()
  refused.page.onDismiss()
  assert.equal(refused.lives[0](), true)
  refused.ack({ ok: false })
  assert.equal(refused.lives[0](), false, 'ok:false stops the chain')
  refused.page.onSnooze()
  assert.equal(refused.lives[1](), true)
  assert.equal(refused.lives[0](), false, 'an old request never becomes live again')

  const accepted = ringFixture()
  accepted.ring()
  accepted.page.onDismiss()
  accepted.ack()
  assert.equal(accepted.lives[0](), false)

  const peer = ringFixture()
  peer.ring()
  peer.page.onDismiss()
  peer.page.onPeerEnded({ alarmId: 1, firedAt: 1000, updatedAtEpoch: 9000 }, 'alarm_dismissed')
  assert.equal(peer.lives[0](), false)

  const spent = ringFixture()
  spent.ring()
  spent.page.onDismiss()
  for (let round = 0; round < 3; round++) {
    spent.tick('actionAckWait')
    if (round < 2) spent.tick('actionRetry')
  }
  assert.ok(spent.lives.every(live => live() === false))
})

test('the page waits for the reply only after the transport chain ended, so retries never overlap', () => {
  const f = ringFixture({ transportOk: false })
  f.ring()
  f.page.onDismiss()
  assert.equal(f.timers.has('actionAckWait'), false)
  assert.ok(f.timers.has('actionRetry'))
})

test('a record write that lands after a newer request cannot leave the older request saved', () => {
  const f = ringFixture({ setMode: 'later' })
  f.ring()
  f.page.onDismiss() // request A: its record write is slow
  f.ack({ ok: false })
  f.page.onSnooze() // request B
  const stampB = f.sent[1].updatedAtEpoch
  assert.equal(f.deferred.length, 2)
  const [writeA, writeB] = f.deferred.splice(0, 2)
  writeB() // B's write lands first ...
  writeA() // ... then the late write of A
  while (f.deferred.length) f.deferred.shift()()
  assert.equal(JSON.parse(f.values.get(ACTION_KEY)).s, stampB, 'the latest request stays saved')
})

test('a late delete cannot remove the record of a newer request', () => {
  const f = ringFixture({ deleteMode: 'later' })
  f.ring()
  f.page.onDismiss() // request A saved
  f.ack({ ok: false }) // its delete is slow
  f.page.onSnooze() // request B saved
  const stampB = f.sent[1].updatedAtEpoch
  assert.ok(f.deletes.length >= 1)
  while (f.deletes.length) f.deletes.shift()()
  assert.equal(JSON.parse(f.values.get(ACTION_KEY)).s, stampB)
})

test('a record read that returns after the ring started puts the ring in the waiting state', () => {
  const f = ringFixture({ getMode: 'later' })
  f.values.set(ACTION_KEY, JSON.stringify({ t: 'd', a: 1, f: 1000, s: 4000, v: 1 }))
  f.page._resumePendingAction() // the read is still out
  f.ring(1, 1000) // the matching alarm_fired arrives first and starts the buzz
  assert.ok(f.timers.has('vibrate'))
  assert.equal(f.sent.length, 0)
  f.gets.shift()() // the read returns the captured record
  assert.equal(f.sent.length, 1, 'the saved request is sent')
  assert.equal(f.timers.has('vibrate'), false, 'the buzz stops')
  assert.equal(f.holds.at(-1), true, 'the screen stays held')
  assert.equal(f.delays.get('terminate'), 11000, 'the app can still close')
  f.ack()
  assert.equal(f.page.ended, true)
})

test('a record read that returns after the ring ended is dropped, not sent', () => {
  const f = ringFixture({ getMode: 'later' })
  f.values.set(ACTION_KEY, JSON.stringify({ t: 'd', a: 1, f: 1000, s: 4000, v: 1 }))
  f.page._resumePendingAction()
  f.ring(1, 1000)
  f.page.onPeerEnded({ alarmId: 1, firedAt: 1000, updatedAtEpoch: 9000 }, 'alarm_dismissed')
  f.gets.shift()()
  assert.equal(f.sent.length, 0)
})

test('a late repair delete cannot remove the record of a newer request', () => {
  const f = ringFixture({ setMode: 'later', deleteMode: 'later' })
  f.ring(1, 1000)
  f.page.onDismiss() // request A: record write out
  f.ack() // A completes; its delete is out
  while (f.deletes.length) f.deletes.shift()()
  while (f.deferred.length) f.deferred.shift()() // A's write lands late, so a repair delete goes out
  assert.equal(f.deletes.length, 1)
  f.ring(2, 3000)
  f.page.onSnooze() // request B saved
  const stampB = f.sent.at(-1).updatedAtEpoch
  while (f.deferred.length) f.deferred.shift()()
  assert.equal(JSON.parse(f.values.get(ACTION_KEY)).s, stampB)
  f.deletes.shift()() // the repair delete lands now and removes B's record ...
  while (f.deferred.length || f.deletes.length) {
    if (f.deferred.length) f.deferred.shift()()
    else f.deletes.shift()()
  }
  assert.equal(JSON.parse(f.values.get(ACTION_KEY)).s, stampB, '... and it is written back')
})

test('a read continuation that arrives after the job timed out writes nothing', () => {
  const f = fixture({ asyncRead: true })
  let added = 'none'
  f.store.add(alarm(5), ok => { added = ok })
  f.fire('storeJob')
  assert.equal(added, false)
  f.finishRead()
  assert.deepEqual(f.disk(), [], 'the stale continuation must not write')

  const g = fixture({ asyncRead: true, initial: [alarm(1)] })
  g.store.update({ ...alarm(1), hour: 9 }, () => {})
  g.fire('storeJob')
  g.finishRead()
  assert.equal(g.disk()[0].hour, 7)
})

test('a late record read cannot revive an older request after a newer one was settled', () => {
  const f = ringFixture({ getMode: 'later' })
  f.values.set(ACTION_KEY, JSON.stringify({ t: 'd', a: 1, f: 1000, s: 4000, v: 1 }))
  f.page._resumePendingAction() // the read captures the saved Dismiss
  f.ring(1, 1000) // a matching alarm_fired, controls are usable
  f.page.onSnooze() // the user chooses Snooze ...
  f.ack({ type: 'alarm_snoozed_ack', ok: false }) // ... the phone refuses it
  assert.equal(f.sent.length, 1)
  f.gets.shift()() // the old read returns
  assert.equal(f.sent.length, 1, 'the old Dismiss is not sent')
  assert.equal(f.values.has(ACTION_KEY), false)
})

test('a late record read is dropped after a different ring replaced the first one', () => {
  const f = ringFixture({ getMode: 'later' })
  f.ring(1, 1000)
  f.values.set(ACTION_KEY, JSON.stringify({ t: 'd', a: 1, f: 1000, s: 4000, v: 1 }))
  f.page._resumePendingAction()
  f.ring(2, 3000)
  f.gets.shift()()
  assert.equal(f.sent.length, 0)
})

test('a record for the ring this watch already ended is not replayed, on any screen', () => {
  const f = ringFixture()
  f.ring(1, 1000)
  f.page.onDismiss()
  f.ack()
  f.tick('peerFlash') // back on the list screen
  assert.equal(f.page.screen, 'list')
  f.values.set(ACTION_KEY, JSON.stringify({ t: 'd', a: 1, f: 1000, s: f.sent[0].updatedAtEpoch, v: 1 }))
  f.page._resumePendingAction()
  assert.equal(f.sent.length, 1)
  assert.equal(f.values.has(ACTION_KEY), false)
})

test('a late record read cannot revive an older request after a newer one ran out of tries', () => {
  const f = ringFixture({ getMode: 'later' })
  f.values.set(ACTION_KEY, JSON.stringify({ t: 'd', a: 1, f: 1000, s: 4000, v: 1 }))
  f.page._resumePendingAction()
  f.ring(1, 1000)
  f.page.onSnooze()
  for (let round = 0; round < 3; round++) {
    f.tick('actionAckWait')
    if (round < 2) f.tick('actionRetry')
  }
  assert.equal(f.sent.length, 3)
  f.gets.shift()()
  assert.equal(f.sent.length, 3, 'the old Dismiss is not sent')
})

test('a late record read is dropped when the phone ended that ring before it was shown', () => {
  const f = ringFixture({ getMode: 'later' })
  f.values.set(ACTION_KEY, JSON.stringify({ t: 'd', a: 1, f: 1000, s: 4000, v: 1 }))
  f.page._resumePendingAction()
  assert.equal(f.page.onPeerEnded({ alarmId: 1, firedAt: 1000, updatedAtEpoch: 9000 }, 'alarm_dismissed'), true)
  f.gets.shift()()
  assert.equal(f.sent.length, 0)
})

test('a second record read cannot resend a request the phone refused after the first read sent it', () => {
  const f = ringFixture({ getMode: 'later' })
  f.ring(1, 1000)
  f.values.set(ACTION_KEY, JSON.stringify({ t: 'd', a: 1, f: 1000, s: 4000, v: 1 }))
  f.page._resumePendingAction() // read 1
  f.page._resumePendingAction() // read 2 starts before read 1 returns
  assert.equal(f.gets.length, 2)
  f.gets.shift()() // read 1 accepts and sends
  assert.equal(f.sent.length, 1)
  f.ack({ ok: false }) // the phone refuses; the ring is usable again
  f.gets.shift()() // read 2 returns late
  assert.equal(f.sent.length, 1, 'the refused request is not sent again')
  while (f.deletes.length) f.deletes.shift()()
  while (f.deferred.length) f.deferred.shift()()
  assert.equal(f.values.has(ACTION_KEY), false)
})

test('a normal ring is bounded by a nine minute cap that closes the app', () => {
  const f = ringFixture()
  f.ring()
  assert.equal(f.delays.get('ringCap'), 540000)
  assert.ok(f.timers.has('vibrate'), 'the ring buzzes')
  assert.equal(f.terminated(), 0)
  f.tick('ringCap')
  assert.equal(f.terminated(), 1)
})

test('the nine minute cap is re-armed by a new ring and dropped when a request takes over', () => {
  const f = ringFixture()
  f.ring()
  f.ring(2, 3000)
  assert.equal(f.counts.get('ringCap'), 2, 'a new occurrence arms a fresh cap')
  f.page.onDismiss()
  assert.equal(f.timers.has('ringCap'), false, 'the request cap owns the close now')
  assert.ok(f.timers.has('terminate'))
  f.ack({ ok: false })
  assert.equal(f.delays.get('ringCap'), 360000, 'a refused request keeps the six minute cap')
  const r = resumeFixture(goodRecord, { ring: { id: 1, firedAt: 1000 } })
  assert.equal(r.f.timers.has('ringCap'), false, 'a replayed request has its own cap')
})

test('a refused request is not sent again from a record whose delete had not landed', () => {
  const f = ringFixture({ deleteMode: 'later' })
  f.ring(1, 1000)
  f.page.onDismiss()
  assert.equal(f.sent.length, 1)
  f.ack({ ok: false })
  assert.equal(f.values.has(ACTION_KEY), true, 'the delete is still in flight')
  f.page._resumePendingAction()
  assert.equal(f.sent.length, 1, 'the refused request is not replayed')
  f.page.onHide()
  f.page._resumePendingAction()
  assert.equal(f.sent.length, 1)
  while (f.deletes.length) f.deletes.shift()()
  assert.equal(f.values.has(ACTION_KEY), false)
})

test('a released request is not sent again from a record whose delete had not landed', () => {
  const f = ringFixture({ transportOk: false, deleteMode: 'later' })
  f.ring(1, 1000)
  f.page.onDismiss()
  f.tick('actionRetry')
  f.tick('actionRetry')
  assert.equal(f.sent.length, 3)
  assert.equal(f.values.has(ACTION_KEY), true)
  f.page._resumePendingAction()
  assert.equal(f.sent.length, 3, 'the released request is not replayed')
})

test('a record written by an earlier process is still replayed', () => {
  const { f, sentNow } = resumeFixture({ ...goodRecord, s: 1 }, { now: 50000 })
  assert.equal(sentNow, 1)
  assert.equal(f.sent.at(-1).updatedAtEpoch, 1)
})

test('the stored consent load starts from the ticker when the one-shot timer is dropped', () => {
  const f = ringFixture()
  let started = 0
  f.page.startLoadRest = () => { started++ }
  f.page.applyScreen({ W: 466, H: 466, shape: 'round' })
  assert.equal(started, 0)
  f.tick('loadRest')
  assert.equal(started, 1)
})

test('the string load finishes from the ticker when every one-shot timer is dropped', () => {
  const f = ringFixture()
  let reveals = 0
  f.page.maybeReveal = () => { reveals++ }
  f.page.loadStrings()
  let steps = 0
  while (f.timers.has('i18n')) { f.tick('i18n'); assert.ok(++steps < 10) }
  assert.equal(reveals, 1)
  assert.equal(f.page.onbQr, 'strings.onboarding_qr', 'the last chunk ran')
  assert.equal(f.page.privacyScan, 'strings.privacy_consent_scan')
})

test('the string load runs each chunk once when the one-shot timers do fire', () => {
  const f = ringFixture()
  let reveals = 0
  f.page.maybeReveal = () => { reveals++ }
  f.page.loadStrings()
  while (f.timeouts.length) f.timeouts.shift()()
  assert.equal(reveals, 1)
  assert.equal(f.timers.has('i18n'), false, 'the backstop is cleared')
})

test('the crown settle window ends from the ticker when the one-shot timer is dropped', () => {
  const f = ringFixture()
  f.ring()
  f.page._armCrownGesture()
  const before = f.timeouts.length
  f.page.onCrownTick()
  assert.equal(f.timeouts.length, before, 'ticks are ignored while settling')
  f.tick('crownSettle')
  f.page.onCrownTick()
  assert.equal(f.timeouts.length, before + 1, 'ticks count once settled')
})

const pageUrl = new URL('../entry/src/main/js/MainAbility/pages/index/index.js', import.meta.url)

function wiredFixture({ holdWrites = false } = {}) {
  let now = Date.now()
  let terminated = 0
  const timers = new Map()
  const ringing = []
  const sent = []
  const sets = []
  const peerAcks = []
  const values = new Map()
  class Clock extends Date { static now() { return now } }
  const ticker = {
    set(name, delay, callback) { timers.set(name, callback) },
    clear(name) { timers.delete(name) },
    has(name) { return timers.has(name) },
    poke() {},
  }
  const storage = {
    set(o) { sets.push(o.key); values.set(o.key, o.value); o.success?.() },
    get(o) { o.success(values.get(o.key) ?? o.default) },
    delete(o) { values.delete(o.key); o.success?.() },
  }
  const alarmStore = { getAll(done) { done([]) },
    replaceAll(items, done) { if (!holdWrites) done?.(true) }, setOnChange() {} }
  const send = type => (id, firedAt, stamp, done) => {
    sent.push({ type, alarmId: id, firedAt, updatedAtEpoch: stamp })
    done(true, '207')
  }
  const handler = load('incomingHandler.js', {
    Date: Clock, app: { terminate() {} }, storage, AlarmStore: alarmStore, SettingsStore: {},
    Logger: logger, AlarmHash: { compute() { return '0' } }, Screen: {}, Ticker: ticker,
    WearBridge: { sendDismissedAck(m) { peerAcks.push(m) }, sendSnoozedAck(m) { peerAcks.push(m) } },
  })
  const page = load('../pages/index/index.js', {
    Date: Clock, Logger: { ...logger, setRemoteSink() {}, flushNow() {} }, Ticker: ticker, storage,
    app: { terminate() { terminated++ } }, file: {}, vibrator: { vibrate() {} },
    brightness: { setKeepScreenOn(o) { o.success() } },
    WearBridge: { sendRinging(...args) { ringing.push(args) }, sendLogBatch() {},
      notifyDismissed: send('alarm_dismissed'), notifySnoozed: send('alarm_snoozed') },
    AlarmStore: alarmStore,
    SettingsStore: { get(done) { done({ use24Hour: true, firstDayOfWeek: 2 }) } },
    Screen: { load() {} }, PrivacyStore: {}, IncomingHandler: handler,
    setTimeout() { return 1 }, clearTimeout() {}, setInterval() { return 1 }, clearInterval() {},
  })
  Object.assign(page, page.data, { $t: key => key })
  page.onInit()
  return { page, handler, ringing, timers, sent, sets, values, peerAcks,
    terminated: () => terminated,
    now: () => now,
    advance(ms) { now += ms },
    fire(name) { const fn = timers.get(name); assert.ok(fn, name); timers.delete(name); fn() },
  }
}

const released = JSON.parse(fs.readFileSync(new URL('./fixtures/released-1.0.11-envelopes.json',
  import.meta.url), 'utf8'))

test('alarm_fired from the phone reaches the ring screen through the onInit wiring', () => {
  const f = wiredFixture()
  f.handler.handle({ type: 'alarm_fired', alarmId: 1, updatedAtEpoch: 2000, firedAt: 1000,
    supportsActionAck: true })
  assert.equal(f.page.screen, 'ring')
  assert.equal(f.page.ended, false)
  assert.deepEqual(f.ringing.at(-1), [1, 1000, 2000])
  assert.ok(f.timers.has('vibrate'), 'ring vibration must start')
})

test('a released 1.0.11 phone alarm_fired envelope still rings the watch', () => {
  const f = wiredFixture()
  f.handler.handle({ type: 'alarm_fired', alarmId: 3, updatedAtEpoch: 2000, firedAt: 1000,
    vibrationPattern: 'PULSE', snoozeAllowed: true, watchVibrationEnabled: true })
  assert.equal(f.page.screen, 'ring')
  assert.deepEqual(f.ringing.at(-1), [3, 1000, 2000])
})

test('every page method the page calls on itself is defined', () => {
  const source = fs.readFileSync(pageUrl, 'utf8')
  const page = wiredFixture().page
  const called = new Set()
  for (const match of source.matchAll(/\b(?:self|selfAS|this)\.([A-Za-z_$][\w$]*)\s*\(/g)) {
    if (match[1] !== '$t') called.add(match[1])
  }
  assert.ok(called.size > 20, 'the scan must find the page method calls')
  const missing = [...called].filter(name => typeof page[name] !== 'function')
  assert.deepEqual(missing, [])
})

function transportFixture({ throws = false } = {}) {
  const timers = new Map()
  const sent = []
  class P2pClient {
    constructor() { if (throws) throw new Error('SDK unavailable') }
    setPeerPkgName() {}
    setPeerFingerPrint() {}
    send(message) { sent.push(JSON.parse(message.builder.text)) }
  }
  class Builder { setDescription(value) { this.text = value } }
  const bridge = load('wearBridge.js', {
    Logger: logger, P2pClient, Builder, Message: class {},
    Ticker: { set(name, delay, fn) { timers.set(name, fn) }, clear(name) { timers.delete(name) } },
    setTimeout() { assert.fail('critical transport must use the durable ticker') },
  })
  return { bridge, timers, sent,
    tick() { const [name, fn] = timers.entries().next().value; timers.delete(name); fn() },
  }
}

test('missing SDK callbacks expire and retry on the ticker with one stable request', () => {
  const f = transportFixture()
  let result
  f.bridge.notifyDismissed(1, 1000, 5000, ok => { result = ok })
  while (f.timers.size) f.tick()
  assert.equal(result, false)
  assert.equal(f.sent.length, 3)
  assert.deepEqual(f.sent[0], { type: 'alarm_dismissed', alarmId: 1, firedAt: 1000, updatedAtEpoch: 5000 })
  assert.deepEqual(f.sent[1], f.sent[0])
  assert.deepEqual(f.sent[2], f.sent[0])
})

test('SDK setup exceptions report bounded transport failure', () => {
  const f = transportFixture({ throws: true })
  let result
  f.bridge.notifyDismissed(1, 1000, 5000, ok => { result = ok })
  while (f.timers.size) f.tick()
  assert.equal(result, false)
})

test('retained vendor SDK preserves text envelopes, registration, and delivery callbacks', () => {
  let nativeSend
  let subscription
  let packageName
  let fingerprint
  let unsubscribed = 0
  const source = fs.readFileSync(new URL('../entry/src/main/js/MainAbility/wearEngine/wearengine.js',
    import.meta.url), 'utf8')
  const context = {
    module: { exports: {} }, console: { info() {}, error() {} },
    wearengine: {
      getWearEngineVersion(options) { options.complete('5.0.2.401') },
      setPackageName(options) { packageName = options.appName },
      setFingerprint(options) { fingerprint = [options.appName, options.appCert] },
      subscribeMsg(options) { subscription = options },
      unsubscribeMsg() { unsubscribed++ },
      sendMsg(options) { nativeSend = options },
    },
  }
  vm.runInNewContext(source.replace(/^import .*;\r?$/gm, '')
    .replace('export { P2pClient, Message, Builder };', 'module.exports = { P2pClient, Message, Builder };'),
  context, { timeout: 1000 })
  const { P2pClient, Message, Builder } = context.module.exports
  const client = new P2pClient()
  client.setPeerPkgName('example.phone')
  client.setPeerFingerPrint('test-certificate')
  assert.equal(packageName, 'example.phone')
  assert.deepEqual(fingerprint, ['example.phone', 'test-certificate'])

  const received = []
  let registered = 0
  client.registerReceiver({ onSuccess() { registered++ }, onFailure() { assert.fail('registration') },
    onReceiveMessage(message) { received.push(message) } })
  subscription.success({ isRegister: true })
  subscription.success({ message: '{"type":"sync_done"}' })
  assert.equal(registered, 1)
  assert.deepEqual(received, ['{"type":"sync_done"}'])

  const text = '{"type":"alarm_dismissed","alarmId":1,"updatedAtEpoch":5000}'
  const builder = new Builder()
  builder.setDescription(text)
  const message = new Message()
  message.builder = builder
  assert.equal(message.getType(), 0)
  assert.equal(message.getDescription(), text)
  let delivered = 0
  let result
  let progress
  client.send(message, { onSuccess() { delivered++ }, onFailure() { assert.fail('send') },
    onSendResult(value) { result = value.code }, onSendProgress(value) { progress = value } })
  assert.equal(nativeSend.deviceId, 'remote')
  assert.equal(nativeSend.bundleName, 'example.phone')
  assert.equal(nativeSend.abilityName, '')
  assert.equal(nativeSend.message, text)
  nativeSend.success()
  assert.equal(delivered, 1)
  assert.equal(result, 207)
  assert.equal(progress, '100%')
  client.unregisterReceiver({ onSuccess() { registered-- } })
  assert.equal(registered, 0)
  assert.equal(unsubscribed, 2)
})
