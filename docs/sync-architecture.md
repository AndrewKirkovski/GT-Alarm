# GT Alarm — Phone ↔ Watch Sync Architecture

**Status:** Phase 0b architecture pivot · **Updated:** 2026-04-27 · **Owner:** Andrei Kirkouski

Companion document to `docs/acceptance-criteria.md`. This is the design-of-record for how the Android phone app and HarmonyOS LiteWearable watch app stay in sync, who fires the alarm, who plays sound, what happens when the user dismisses on one device, and how snooze-then-re-fire propagates.

> **Pre-decision read:** Before changing any of the call sites listed under "Code touch points" or the JSON wire format, re-read this document end-to-end.

> **2026-04-27 architecture pivot — read first:** Empirical install testing on the GT 6 confirmed the watch runs **HarmonyOS LiteWearable, NOT full HarmonyOS NEXT**. LiteWearable lacks `reminderAgentManager` and any scheduling primitive that survives device sleep — only `setTimeout` / `setInterval`, foreground-only. **Phone is now the sole scheduler.** The watch is an online-armed thin client: it receives "ring now" pings from the phone via Wear Engine and renders the ring UI. It does not compute or persist fire times for offline operation. The legacy NEXT-targeted watch app lives in `watch-app.old/` for reference; the live `watch-app/` is being rewritten as a LiteWearable JS project (Phase 0b). All watch-side details below describe the new design.

---

## 1. Layered model

```
                Android phone (Kotlin / Compose, Samsung)        |   HarmonyOS LiteWearable (JS / HML / CSS, GT 6)
                                                                 |
  ┌─────────────────────────────────────────┐                    |    ┌─────────────────────────────────────┐
  │ AlarmRepository (Room v2, +updatedAtEpoch)                   |    │ AlarmStore (@system.storage KV)     │
  │  ↕ Hilt-injected                                             |    │  receive-only mirror for display    │
  │ AlarmScheduler.setAlarmClock()  ← SOLE SCHEDULER             |    │  ↕                                  │
  │  ↕                                                           |    │ ring page (HML/CSS/JS)              │
  │ AlarmRingService (FG service)                                |    │  ↕ Dismiss / Snooze taps            │
  │  ↕ DISMISS / SNOOZE intents             |                    |    └─────────────────────┬───────────────┘
  │ HuaweiWearBridge (post-AGConnect approval)                   |                          │
  └─────────────────────┬───────────────────┘                    |                          │
                        │                                        |                          │ @system.wearengine
                        │                                                                   │ (P2pClient)
                        │ ─────────────────  Wear Engine P2P (Bluetooth via Huawei Health)  │
                        │                          JSON, single line per message
```

**Today (pre-AGConnect-approval, Phase 5a complete on phone, Phase 0b in flight on watch):**
- Android side: `WearBridgeService` interface, `NoOpWearBridge` Hilt-bound. Receive-side `IncomingMessageHandler` + `LwwResolver` + `Tombstones` already landed. Send-side wrapped through `AlarmRepository`, `AlarmRingService`, `EditAlarmViewModel`. Every call logs to `Log.d(TAG="WearBridge")`.
- Watch side: new `watch-app/` is LiteWearable target (`apiType: "faMode"`, `srcLanguage: "js"`, `deviceTypes: ["liteWearable"]`). Wear Engine P2P seam at `js/default/services/wearBridge.js`. Today the seam logs only; the `P2pClient` calls go live once AGConnect Wear Engine approval lands.
- The two devices are **not actually talking** yet. The phone schedules and rings independently; the watch is unarmed.

**Post-AGConnect approval (~2 weeks out, gated on Wear Engine SDK approval):**
- Android: drop in `HuaweiWearBridge` implementing `WearBridgeService`, swap one `@Binds` line in `WearModule`, add `implementation("com.huawei.hms:wearengine:<latest>")` to `app/build.gradle.kts`.
- Watch: replace the seam's no-op `send()` with `wearengine.getP2pClient().send(peerDevice, JSON.stringify(msg))`, register `onMessage` callback that routes through the watch-side `IncomingMessageHandler`. Wire format below is the contract — it's already coded on the phone side and being built into the LiteWearable rewrite.

---

## 2. Wire format

JSON over P2P. Every message is a single line, single object, no nesting.

**LWW-carrying messages** (the 7 types that participate in last-write-wins conflict resolution):

```json
{
  "type": "alarm_fired" | "alarm_dismissed" | "alarm_snoozed"
        | "alarm_added" | "alarm_updated" | "alarm_deleted" | "alarm_toggled",
  "alarmId": <number>,
  "updatedAtEpoch": <number>,
  "rescheduleEpoch": <number?>,
  "enabled": <boolean?>,
  "alarm": <Alarm?>
}
```

| field             | type    | when set                                                      |
| ----------------- | ------- | ------------------------------------------------------------- |
| `type`            | string  | always                                                        |
| `alarmId`         | number  | always — same id across both devices (more on this below)     |
| `updatedAtEpoch`  | number  | always — wall-clock ms of the local mutation; LWW rule below  |
| `rescheduleEpoch` | number  | **phone→watch `alarm_snoozed` only** — wall-clock epoch ms of next ring; the watch direction OMITS this field (phone owns per-alarm duration) |
| `enabled`         | boolean | `alarm_toggled` only                                          |
| `alarm`           | object  | `alarm_added` / `alarm_updated` only — full record (see §5.1) |

**Meta-protocol messages** (NOT routed through the LWW handler — handled directly by the wear bridges):

```json
// phone → watch: ask watch to compute and report its alarm-set hash
{ "type": "sync_check" }

// watch → phone: response to sync_check
{ "type": "sync_hash", "hash": "<8-char lowercase hex>" }

// watch → phone: dev-only BATCHED log relay (carries no alarmId). The
// watch coalesces Logger lines and flushes ONE envelope per ~700 ms or
// per N queued lines, so the relay stops adding per-line P2P channel
// pressure. Each entry keeps its own `ts` so logcat ordering survives.
{ "type": "watch_log_batch",
  "lines": [ { "level": "I" | "W" | "E", "msg": "<string>", "ts": <number> }, ... ],
  "ts": <number> }

// phone → watch: authoritative full-state replace — used by Force sync.
// NOT LWW. The watch replaces its entire AlarmStore with `alarms` and
// tombstones every id dropped by the replace. This is what prunes
// watch-only rows that an additive `alarm_added` push can never remove.
{ "type": "sync_replace",
  "alarms": [ <Alarm>, ... ], "updatedAtEpoch": <number> }

// phone → watch: end-of-sync marker, sent as the LAST message of every
// forceSync (success, failure, or already-in-sync). The watch
// terminates promptly on this instead of waiting out the drain-
// quiescence timer — closes the sync→self-terminate race.
{ "type": "sync_done", "updatedAtEpoch": <number> }

// phone → watch: push display preferences (12/24h + first-day-of-week).
// Not LWW. Phone is the only place these are edited; watch overwrites
// its local copy unconditionally. `use24Hour` null = follow system locale.
// `firstDayOfWeek` is 1..7 = SUNDAY..SATURDAY per java.util.Calendar; null
// = follow system locale.
{ "type": "settings_changed", "use24Hour": <bool|null>,
  "firstDayOfWeek": <int|null>, "updatedAtEpoch": <number> }

// watch → phone: confirms the watch's ring page is up and vibrating.
// Phone awaits this before starting its own audio so the two devices
// ring in lock-step instead of phone-first / watch-late.
{ "type": "alarm_ringing", "alarmId": <number>, "updatedAtEpoch": <number> }

// watch → phone: application-level ack that the watch's JS receiver
// processed a phone-originated `alarm_dismissed` (or `alarm_snoozed`).
// Sent from incomingHandler.js after onPeerEndedRing(alarmId) executes.
// Phone's sendAlarmDismissedAwaiting/sendAlarmSnoozedAwaiting blocks on
// this reply — proves the JS receiver actually ran our handler, NOT
// just that Wear Engine's transport ACK'd at 207 (the transport ACK
// can succeed while the JS receiver was in a transient state and
// silently dropped the envelope; without this loop the user has to
// manually dismiss on the watch too).
{ "type": "alarm_dismissed_ack", "alarmId": <number>, "updatedAtEpoch": <number> }
{ "type": "alarm_snoozed_ack",   "alarmId": <number>, "updatedAtEpoch": <number> }

// phone → watch: ask the watch to report its physical screen so the phone
// can size + shape the uploaded background image to the real panel. Carries
// no alarmId (meta-protocol). ALL comms are phone-initiated — the watch
// never pushes its screen unprompted.
{ "type": "screen_request" }

// watch → phone: reply to screen_request. `shape` = "circle" | "rect"
// (mirrors @system.device.getInfo screenShape). Phone persists this keyed on
// the bonded watch model and only re-requests when that model changes.
{ "type": "watch_screen", "width": <number>, "height": <number>,
  "shape": "circle" | "rect", "updatedAtEpoch": <number> }
```

`sync_check` / `sync_hash` implement the force-sync precheck (§6). Phone sends `sync_check`, watch replies `sync_hash` with the result of `AlarmHash.compute(localAlarms)`. If the phone's local hash matches, it skips the per-alarm `alarm_added` push and surfaces `ForceSyncResult.AlreadyInSync`. Hash format is locked to 8 lowercase hex chars; receivers reject anything else.

`watch_log_batch` is opt-in diagnostic; the phone receiver expands it to one `adb logcat` line per entry under the `WatchLog` tag, ts-ordered, and exits — never reaches the LWW handler. It replaces the former per-line `watch_log` envelope (one P2P send per log line was a real channel-pressure confound while diagnosing 206 failures).

`screen_request` / `watch_screen` let the phone size the watch-background cropper + encoder to the real panel (round GT → circle-masked square; rectangular FIT → full-bleed W×H). **Phone-initiated only**, folded into `forceSync` right after the watch app is confirmed running (no extra wake), and **gated on the bonded model** — the phone caches the reply (`watchScreen{Width,Height,Shape,Model}` in `SettingsStore`) and re-asks only when the connected model differs, so a watch we've already measured is never re-pinged. The reply is intercepted in `HuaweiWearBridge` (it owns the bonded-model knowledge), not routed to the LWW handler. Fire-and-forget: if the reply is lost (watch app closed before it replied), the cropper falls back to the cached/default size and the next `forceSync` re-asks. The supported-panel matrix + the "unrecognized resolution → no crop overlay" rule live in `docs/watch-resolutions.md`.

`sync_replace` is **authoritative, not LWW** — it is a deliberate user-initiated "Force sync", so the watch applies the whole list wholesale rather than per-row last-write-wins. Before writing, the watch diffs against its current store and **tombstones every id that the replace drops**, so a stale `alarm_updated` arriving afterward cannot resurrect a pruned row. `sync_done` is a meta-protocol terminate marker; the watch calls `app.terminate()` on it unless the ring page is active or the user opened the app themselves.

`alarm_ringing` / `alarm_dismissed_ack` / `alarm_snoozed_ack` are intercepted by `HuaweiWearBridge` directly (NOT routed through `IncomingMessageHandler`) so they can satisfy the pending `CompletableDeferred<Unit>` reservations made by the phone's `sendAlarmFiredAwaiting` / `sendAlarmDismissedAwaiting` / `sendAlarmSnoozedAwaiting`. The corresponding `Awaiting` methods also force a fresh peer-running ping (bypass the 30 s wake-cache) before send, so the wake protocol re-establishes the channel even if the OS process is still marked alive but the JS receiver has degraded since the last confirmed running event.

### 2.1 Payload-size budget

**Constraint status:** open until measured on real GT 6 hardware. A Chinese-language / LiteWearable-specific research pass did not find a Huawei-published maximum byte size for `@system.wearengine` P2P message payloads. Huawei's public Wear Engine samples demonstrate both custom messages and file transfer, but they do not state a max payload size or the LiteWearable rejection/error behavior for oversized messages. Treat the transport as a small control-message pipe until proven otherwise.

**Project rule:** every JSON control payload MUST stay comfortably small. Target ≤ 1 KiB, hard cap 4 KiB UTF-8 encoded. Current expected sizes:

| message | expected UTF-8 size |
| --- | ---: |
| `alarm_fired` / `alarm_deleted` / `alarm_dismissed` | < 128 B |
| `alarm_snoozed` | < 160 B |
| `alarm_toggled` | < 160 B |
| `alarm_ringing` / `alarm_dismissed_ack` / `alarm_snoozed_ack` | < 128 B |
| `sync_done` | < 64 B |
| `alarm_added` / `alarm_updated` with audio URI | < 1 KiB |
| `watch_log_batch` (≤ 8 lines) | < 1 KiB |
| `sync_replace` (full list, ~12 alarms) | < 2 KiB — guarded against the 4 KiB cap |

`sync_replace` is the only payload that scales with alarm count. `HuaweiWearBridge.forceSync` checks the serialized UTF-8 length against the 4 KiB hard cap before sending; if it would exceed, it falls back to the legacy per-alarm `alarm_added` burst (which cannot prune, but stays within budget). For a realistic ≤ 12-alarm list this never triggers.

### 2.2 Per-alarm fields in the `alarm` payload

The `alarm` envelope (sent with `alarm_added` / `alarm_updated`) carries:

| field | type | required | clamp |
| --- | --- | --- | --- |
| `id` | number | yes (envelope `alarmId` wins on disagreement) | — |
| `hour` | number | yes | 0–23 (sender validates) |
| `minute` | number | yes | 0–59 (sender validates) |
| `daysOfWeek` | number | no (default `0`) | 0–127 bitmask |
| `enabled` | boolean | yes | — |
| `audioUri` | string\|null | no | phone-local content URI; watch ignores |
| `isVibrationOnly` | boolean | no (default `false`) | — |
| `snoozeMinutes` | number | no (default `10`) | **`0` = snooze disabled (ring UI hides the button); otherwise clamped to 1–60 on receive; negatives clamp to `0`** |
| `updatedAtEpoch` | number | yes | non-negative |
| `relativeMinutes` | number\|null | no (default `null` = absolute alarm) | **rejected if outside 1–1440 OR if `daysOfWeek != 0`** |
| `selfDestruct` | boolean | no (default `false`) | **rejected if `true` AND `daysOfWeek != 0`** |
| `vibrationPattern` | string | no (default `"PULSE"`) | one of `PULSE`/`HEARTBEAT`/`THREE_TAP`/`LONG_LONG`/`OFF`; unknown coerces to `PULSE` |
| `volumeRampSeconds` | number | no (default `0`) | clamped to 0–60; `0` = no ramp |
| `maxSnoozeCount` | number | no (default `0`) | `0` = unlimited; otherwise clamped to 1–20 |
| `skipNextEpoch` | number\|absent | no | only present when the user has swiped-to-skip-next on a recurring alarm; `<= 0` coerces to absent on receive |

The alarm **label is phone-only** — it is never serialized into the `alarm`
payload, never stored on the watch, and never part of `AlarmHash`. The label
appears only on the phone's ring screen; the watch ring screen shows the time
alone. Dropping it from the wire also keeps payloads smaller.

The receive-side parser on the phone (`WearJsonCodec`) clamps `snoozeMinutes` with a sentinel ladder: `<= 0` collapses to `Alarm.SNOOZE_DISABLED` (0) meaning "snooze is off", otherwise the value is coerced into `[Alarm.MIN_SNOOZE_MINUTES, Alarm.MAX_SNOOZE_MINUTES]`. A `0` flowing through the wire is preserved — it tells the watch's ring page to hide its snooze affordance. The watch's `AlarmStore` stores whatever arrives; the watch's ring page applies the same range + off-sentinel check before using the value for snooze scheduling.

**Peer-snooze collapse-to-dismiss.** When the watch initiates a snooze (e.g., user taps Snooze on the watch ring page) but the local alarm record on the phone has `snoozeMinutes == 0`, the phone treats the incoming `alarm_snoozed` envelope as a dismiss (`IncomingMessageHandler.applySnooze` → `dispatchDismissFromPeer`). This guards against a 0-minute re-fire and keeps the row in a consistent state (self-destruct fires, etc.). The phone does not re-broadcast `alarm_dismissed` back to the originating watch because `fromPeer == true`, so the watch is responsible for its own UI teardown (it already navigates away on the snooze tap; `alarm_fired` would be the next message it'd see).

**Reject-not-coerce** for the v4 fields (`relativeMinutes`, `selfDestruct`): when the peer sends an illegal combination (out-of-range minutes, or either field paired with `daysOfWeek != 0`), `parseAlarm` returns `null` and logs the rejection. Earlier versions silently coerced — that caused permanent divergence because the phone would re-broadcast the coerced state and overwrite the watch's intended value with both sides agreeing on the wrong outcome. Reject means the envelope is dropped and the peer's next push is expected to arrive in a valid shape.

**Field invariants** (enforced by `Alarm.kt`'s init block and mirrored on the watch's `incomingHandler.js`):
- `relativeMinutes != null` ⇒ `daysOfWeek == 0` (relative alarms are one-shot only).
- `selfDestruct == true` ⇒ `daysOfWeek == 0` (recurring + self-destruct is meaningless).
- `relativeMinutes ∈ [1, 1440]` when non-null (1 min to 24 h).

Implementation requirements before real P2P is enabled:
- Never send ringtone/audio bytes over P2P. `audioUri` is a phone-local identifier only; the watch must not dereference it. If the watch needs an audible cue, it uses a bundled watch-side tone/vibration.
- Log outbound payload byte length in `HuaweiWearBridge` and watch-side `wearBridge.js`.
- Add a hardware spike after AGConnect approval that sends 1 KiB, 2 KiB, 4 KiB, 8 KiB, 16 KiB, 32 KiB, and 64 KiB messages to the LiteWearable app and records success, failure code, truncation, latency, and receiver behavior. Do not raise the 4 KiB cap without that measurement.

**Alarm-id consistency:** the phone's Room PK (`Long`) and the watch's `AlarmStore` numeric id MUST refer to the same alarm. The phone is the sole id allocator (it owns scheduling, see §3); the watch only ever receives ids generated by the phone.

**Conflict-resolution rule: last-write-wins (LWW), even though watch-originated mutations are now rare.** Every mutation on either device stamps `updatedAtEpoch = Date.now()` before broadcasting. The watch can still originate mutations (toggle, dismiss-flips-enabled-on-one-shot, snooze acks), so the LWW rules still apply on both sides:
- If `incoming.updatedAtEpoch > local.updatedAtEpoch` (or no local record exists) → apply the incoming write, do NOT re-broadcast (avoid loop).
- If `incoming.updatedAtEpoch < local.updatedAtEpoch` → ignore the incoming message and re-broadcast our newer local state so the sender catches up.
- Tie (`==`) on a live row → incoming wins (deterministic tie-break; common-case is the user's most recent action propagating).
- Tie (`==`) involving a local tombstone → **local tombstone wins** (delete is sticky, see §5.2 below). Both Tombstones impls (`Tombstones.kt` on phone and the Lite-port on watch) implement `tombstone.epoch >= incoming.epoch ⇒ suppress`.

**Caveats:**
- Clock skew between phone and watch can flip the winner the wrong way. Both devices stamp `updatedAtEpoch = Date.now()` (wall clock); skew tolerance budget: 60 s (one minute is the smallest user-visible alarm-time granularity). Larger skew is tracked in Phase 5b under "NTP-anchored clocks before pairing".
- A delete is also stamped — a tombstone with `updatedAtEpoch` shipped via `alarm_deleted` MUST beat an older `alarm_updated`. Implementation requirement: the local store keeps a deletion tombstone (or at least the `updatedAtEpoch` of the deletion) for some retention window so a stale `alarm_updated` arriving later doesn't resurrect the row. Already implemented on the phone (Tombstones, 256-entry / 7-day ring buffer); needs to be re-implemented on the LiteWearable watch in plain JS against `@system.storage`.

---

### 2.4 Default-watch-background auxiliary envelopes (Phase 5a)

The per-alarm `watchBackgroundImageUri` field was removed in db v8 (Phase 5a). The watch now shows a **single shared background image** that the phone manages via two auxiliary wire artifacts, separate from the per-alarm `alarm_added` / `alarm_updated` envelopes:

| direction | kind | name / type | payload |
| --- | --- | --- | --- |
| phone → watch | P2P file (Wear Engine `Builder.setPayload(File)` + `.setDescription("bg_default.bin")`) | `bg_default.bin` | watch-rendered PNG (466 × 466, circular crop); cached on phone at `watch_bg_-1.bin` |
| phone → watch | JSON envelope (`type: "watch_default_bg_cleared"`) | `watch_default_bg_cleared` | `{ "type": "watch_default_bg_cleared", "stamp": <epoch> }` — instructs the watch to delete its cached `bg_default.bin` |

**Phone side** (`HuaweiWearBridge.uploadDefaultWatchBackground` + `sendDefaultWatchBackgroundCleared`): fires on `SettingsStore.defaultWatchBackgroundUri` change, gated by the watch-sync state machine (uses the same wake-and-send protocol as alarm envelopes per §2.3). The `-1L` sentinel `AlarmRepository.DEFAULT_WATCH_BG_ID` distinguishes the default-bg cache file from per-alarm ones in the phone's `WatchBackgroundCache`.

**Watch side (status — KNOWN GAP, deferred to Phase 0b watch rewrite):** the current `watch-app/entry/src/main/js/MainAbility/common/wearBridge.js` **ignores all incoming file messages** (`if (data.isFileType) { Logger.i('... (ignored)'); return; }`) and `incomingHandler.js` has no case for `watch_default_bg_cleared`. The phone-side scaffolding is complete and on-wire-format-stable; the watch handlers will land with the Phase 0b LiteWearable rewrite that's already replacing `watch-app/` wholesale. Until then, the default-watch-bg feature is **phone-side-only** — the file is uploaded but never rendered on the watch, and the clear envelope is silently dropped.

The wire shapes are pinned so they don't churn when the watch rewrite catches up; the phone code stays in place.

---

## 2.3 Wake-and-send protocol (phone → Lite Wearable, REVISED 2026-05-11)

The Lite Wearable JS app dies fast when backgrounded and there is **no** keep-alive primitive (no Service in FA Model, no `setInterval` survives sleep). When the phone needs to push a message, the watch app is usually NOT running. Wear Engine's auto-launch wakes it on first ping/send, but the watch JS chain (`app.onCreate` → `WearBridge.setIncomingHandler` → `P2pClient.registerReceiver`) takes **~1 second** on GT 6 Pro. **Every `send` during that 1 s window returns 206 (COMM_FAIL)** because the watch-side receiver isn't bound yet.

**Result we MUST achieve before any send:** ping returns 202 (APP_RUNNING) — not just 201 (APP_NOT_RUNNING). The phone bridge implements `ensurePeerAppRunning(device)`:

```
loop until ping == 202 OR deadline (5 s):
    code = pingPeer(device)
    if 202: cache "running" for 5 s, return true
    if 201: delay 400 ms, retry
    if 200/203/etc: return false (fatal — app not installed, or P2P error)
```

Cached result lets sync-on-fire and force-sync bursts pay the ping cost ONCE; subsequent sends in the burst skip the poll. A 206 response from any send invalidates the cache so the next attempt re-polls.

Per Huawei Wear Engine `ResultCode`:
- 200 `WATCH_APP_NOT_EXIT` — peer not installed; abort.
- 201 `WATCH_APP_NOT_RUNNING` — installed but launching; wait, do NOT send.
- 202 `WATCH_APP_RUNNING` — receiver bound; send.
- 203 `OTHER_ERROR` — abort with surfaced error.
- 206 / 207 are `SendCallback.onSendResult` codes (fail / success). Truth source for delivery — `Task<Void>` success only means dispatch, not delivery.

Both directions of `ensureReceiverRegistered` matter: the phone keeps its own `Receiver` bound via `setIncomingHandler` — same lifecycle on watch via `wearBridge.registerReceiver` in `app.onCreate`. The 1 s startup delay is on the WATCH side only; phone is always-on.

Wired in `android-app/.../wear/HuaweiWearBridge.kt` (`ensurePeerAppRunning`, `pollUntilRunning`, `wakeMutex`). See memory:wear_engine_wake_protocol.md for tuned constants + rationale.

---

## 3. Where the alarm fires (design decision — REVISED 2026-04-27)

**The phone is the sole scheduler. Both devices ring, but only the phone's `AlarmManager` is responsible for firing on time.** When the alarm time arrives:

1. Phone's `AlarmManager.setAlarmClock` PendingIntent fires.
2. Phone starts `AlarmRingService`, plays audio, shows full-screen `AlarmActivity`.
3. Phone immediately sends `{ type: "alarm_fired", alarmId, updatedAtEpoch }` to the watch via Wear Engine.
4. Watch receives the fire ping, renders its ring page (HML/CSS/JS), and starts vibration.

**Why phone-only scheduling:**
- LiteWearable has no scheduling primitive that survives device sleep. `setTimeout` / `setInterval` are foreground-only and die when the JS engine sleeps. There is no `reminderAgentManager` on Lite.
- Even if we coded around this with a "phone pre-pushes upcoming alarms and watch arms `setTimeout` while paired", the watch would fail to fire when its JS process is paged out — which on GT 6 happens within minutes of inactivity.
- The dependency cost is real: if the watch is out of Bluetooth range when the alarm time arrives, the watch will not ring. The phone still rings on its own schedule. Acceptable trade-off because the phone always has the user-facing wake path; the watch is a convenience surface for tap-to-dismiss when the user is wearing it.

**Audio: phone always plays its own alarm via `AlarmRingService` with `USAGE_ALARM`.** Watch optionally vibrates and plays a short tone. There is no system-level "watch alarm tone" on Lite (no Reminder Agent), so the watch is silent unless we explicitly play audio inside the page. First Dismiss wins — see §4.

**Pre-arming for in-range cases (Phase 5b candidate, not committed):** the phone could send `{ type: "alarm_armed", alarmId, fireAtEpoch }` shortly before the scheduled time so the watch can pre-render the ring UI without a perceptible "fire→render" gap. Optimization, not load-bearing. Defer until end-to-end is working.

---

## 4. Flow diagrams

### 4.1 — Alarm fires, phone+watch both ring, user dismisses on watch

```
T=-Δs     Optional pre-arm (deferred): phone sends alarm_armed to watch.
          Watch caches { alarmId, fireAtEpoch } so when alarm_fired arrives
          the ring page is already preloaded.

T=0s      Phone's AlarmManager fires.
            – AlarmBroadcastReceiver
            – AlarmRingService starts FG
            – notification w/ fullScreenIntent
            – AlarmActivity over keyguard
            – MediaPlayer(USAGE_ALARM).start()
            – Vibrator + screen wake

T+0.1s    Phone sends { type: "alarm_fired", alarmId, updatedAtEpoch } over Wear Engine.

T+0.15s   Sync-on-fire: phone iterates repository.getAll() and pushes one
          { type: "alarm_added", alarm } per row. The watch app is awake for
          the ring window, so the receiver channel is hot; this is a free
          opportunity to drag the watch's AlarmStore into agreement without
          requiring a manual Force-sync trip. Cheap and idempotent — LWW on
          the watch side discards rows it already has at an equal/newer stamp.

T+0.2s    Watch's onMessage handler routes the message.
            – ring page (HML) pushed
            – vibrator.vibrate({ pattern: [...] })
            – optional short audio (NOT system-tone — there's no system tone on Lite)

T+8s      User taps Dismiss on watch.
          ring page onDismiss():
            – stopVibration
            – AlarmStore.update(enabled=false) for one-shots
            – wearBridge.notifyDismissed(alarmId)  ─→  phone

T+8.1s    Phone receives { type: "alarm_dismissed", alarmId, updatedAtEpoch }
          IncomingMessageHandler:
            – AlarmRingService.handleDismiss(alarmId)
            – MediaPlayer.stop / Vibrator.cancel / stopForegroundAndSelf
            – do NOT re-broadcast (avoid loop)
          AlarmActivity.finish()
```

**Idempotence rule:** receiving a dismiss for an `alarmId` whose ring service is already stopped is a no-op. Receiving a dismiss for an alarm we never fired (skewed clocks, P2P delivery delay) is also a no-op — the local side will time out on its own at the configured ring duration.

### 4.2 — Snooze on phone, re-fire after 10 min, watch re-rings on next fire

```
T=0s    Both ring (as in 4.1).

T+5s    User taps Snooze on phone.
        AlarmRingService.handleSnooze:
          – computes newTrigger = now + SNOOZE_MINUTES (10) * 60_000
          – AlarmManager.setAlarmClock(newTrigger, PI) — REUSES alarm.id as requestCode
          – wearBridge.notifySnoozed(alarmId, rescheduleEpoch=newTrigger) ─→ watch
          – stopForegroundAndSelf
          – AlarmActivity.finish

T+5.1s  Watch receives { type: "alarm_snoozed", alarmId, updatedAtEpoch, rescheduleEpoch }.
          – stop ring page (pop the route)
          – stopVibration
          – do NOT try to schedule anything locally (no Lite scheduler exists)
          – the snooze is informational-only on the watch; phone will fire again in 10 min
            and the next "alarm_fired" message will re-render the ring page

T+10min Phone's AlarmManager fires (the rescheduled trigger). Loop returns to §4.1.
```

**Variant — snooze on a snooze-disabled alarm (Phase 5a+):**

```
Watch sends { type: "alarm_snoozed", alarmId, ... }.
Phone IncomingMessageHandler.applySnooze:
  – reads local row → alarm.isSnoozeEnabled == false (snoozeMinutes == 0)
  – DOES NOT schedule a 0-minute re-fire
  – dispatchDismissFromPeer(alarmId): hands a DISMISS intent (with
    EXTRA_FROM_PEER=true) to AlarmRingService
  – AlarmRingService.handleDismiss runs the regular dismiss-side
    transitions (DELETE for selfDestruct, DISABLE for one-shot,
    KEEP for recurring), suppresses the outbound dismiss broadcast
    (fromPeer == true, peer's UI already moved on)

Symmetric variant — user taps stale Snooze action on phone
notification AFTER snooze was disabled in edit:
  AlarmRingService.handleSnooze reads alarm.isSnoozeEnabled == false
  → calls handleDismiss(fromPeer=false). The outbound dismiss broadcast
    DOES fire so the watch stops ringing too.
```

**Process-kill-resilience rule for both handlers:** local Room write
(`repository.snoozeAt` or `dismissAction → setEnabled / delete`) commits
BEFORE the wear broadcast. If the OS reaps the FGS between the two, the
phone DB is consistent and the watch self-heals on the next sync hash
check. Concurrent `handleSnooze` + `handleDismiss` are serialized by
`AlarmRingService.handlerMutex` to prevent contradictory partial commits
(`enabled=false` AND `snoozedUntilEpoch != null`).

**Snooze on watch (asymmetric — phone owns duration):** watch's ring page Snooze tap sends `{ type: "alarm_snoozed", alarmId, updatedAtEpoch }` — no `rescheduleEpoch`. The phone's `IncomingMessageHandler.applySnooze` looks up the local `Alarm.snoozeMinutes` for that id, computes `trigger = now + minutes·60_000`, and dispatches the from-peer snooze intent to `AlarmRingService` (which calls `repository.snoozeAt(trigger)` to schedule without echoing the broadcast). This keeps the per-alarm snooze duration authoritative on the phone — the watch is a thin trigger surface and never picks the reschedule time.

### 4.3 — Edit on phone, mirror to watch (display-only)

```
User opens AlarmEditScreen on phone, changes 07:00 → 06:30, saves.

AlarmRepository.update(alarm):
  – Room UPDATE
  – AlarmScheduler.cancel(alarm.id)
  – AlarmScheduler.scheduleNext(alarm)   ← computes nextTriggerEpoch
  – widget.update(ctx, glanceId)
  – wearBridge.sendAlarmUpdated(alarm)    ─→ watch (full Alarm payload)

Watch receives { type: "alarm_updated", alarmId, alarm: {...}, updatedAtEpoch }
  – AlarmStore.update(item)               ← updates local KV cache
  – list re-renders if visible
  – DOES NOT publish a watch-side reminder; the watch never schedules anything
```

### 4.4 — Reboot survival (asymmetric)

```
Phone reboot:
  BootReceiver fires on BOOT_COMPLETED + LOCKED_BOOT_COMPLETED
  + MY_PACKAGE_REPLACED + TIMEZONE_CHANGED + TIME_SET (5 actions).
  rescheduleAll() iterates AlarmRepository, re-runs setAlarmClock on every
  enabled alarm. Phone is fully self-recovering.

Watch reboot:
  No persistent state to recover for scheduling — the watch was never
  scheduling anything. AlarmStore (the display cache) is restored from
  @system.storage and the list re-renders showing whatever was last
  synced. When the phone fires the next alarm, alarm_fired arrives via
  P2P and the ring page renders; same flow as cold-start.

  If the watch is out of phone-Bluetooth-range at the time of reboot AND
  the phone fires while the watch is offline, the watch never sees the
  fire and silently misses it. The phone still rings on time. This is
  the documented limitation of phone-only scheduling.
```

Reboot does NOT trigger any cross-device sync — the phone recovers from its own persistent state. P2P comes back online only when both devices are awake and paired.

---

## 5. Open questions / known gaps

### 5.1 — Decided: full payload travels with `alarm_added` / `alarm_updated`
The message body carries the `Alarm` record (hour, minute, daysOfWeek, enabled, audioUri, isVibrationOnly, updatedAtEpoch) — minus the phone-only `label`. Stateless — the receiver applies it directly without a round-trip. Schema-drift risk handled by including a `schemaVersion` field if/when the contract grows; today both sides agree on the v1 shape implicit in `domain/Alarm.kt` (phone) and the LiteWearable port's AlarmItem JS object.

### 5.2 — Decided: last-write-wins on offline-edit conflicts (2026-04-25, still applies)
Per §2's conflict-resolution rule. Both sides MUST stamp `updatedAtEpoch = Date.now()` on every local mutation. Phone-side implementation is complete (Phase 4 + Phase 5a). Watch-side implementation is being ported in Phase 0b — same algorithm, JS instead of ArkTS.

### 5.3 — Decided: phone is sole scheduler (2026-04-27)
See §3. Watch is online-armed thin client. Watch-originated edits (toggle, dismiss-flips-enabled, snooze) still propagate via the wire format and still apply LWW, but no scheduling work happens on the watch.

### 5.4 — Open: pre-arm vs reactive ring rendering on watch
Today's design (§3) renders the ring page reactively from `alarm_fired`. Pre-arming via `alarm_armed` (T-Δs before fire) is a candidate optimization once latency measurements come in. Defer.

### 5.5 — Open: watch out-of-range-at-fire UX
When the watch is out of Bluetooth range or paged-out at fire time, the user only gets the phone ring. The watch silently misses. Tracked under Phase 5b: surface a "missed alarm at HH:MM" toast on the watch on next reconnect, sourced from the phone replaying the most-recent `alarm_fired` envelope when `setIncomingHandler` re-attaches.

### 5.6 — Resolved 2026-04-27: GT 6 is LiteWearable, not full NEXT
Empirically confirmed via failed install + Huawei Developer Forum moderator quote on identical case. See `gt6_hardware_constraints.md`. The legacy `watch-app.old/` (NEXT-targeted, will not install on GT 6) is kept in-tree as a porting reference for Phase 0b; new code lives in `watch-app/`.

### 5.7 — Force-sync hash precheck (Phase 5b)

When the user taps **Force sync** on the WatchSyncCard, the phone could naively push every alarm via `alarm_added`. That's N P2P round-trips for an idempotent operation when the watch is usually already in sync. The precheck cuts that to zero pushes on the common path.

**Wire flow** (phone-driven):

```
phone:  ping until 202 (per §2.3)
phone:  { "type": "sync_check" }                     ──►  watch
watch:  alarms = AlarmStore.getAll()
watch:  hash = AlarmHash.compute(alarms)
watch:  { "type": "sync_hash", "hash": "<8-hex>" }   ──►  phone
phone:  alarms = repository.getAll()    // re-snapshot AFTER response (TOCTOU)
phone:  localHash = AlarmHash.compute(alarms)
phone:  if remoteHash == localHash → { sync_done } → ForceSyncResult.AlreadyInSync(n)
phone:  else → { sync_replace, alarms:[...] } → { sync_done } → ForceSyncResult.Ok(n)
```

The phone re-fetches its own alarm list **after** receiving the watch's response so the local hash reflects the latest DB state, not a snapshot taken before the ~2s round-trip. This closes the TOCTOU race where the user adds an alarm mid-sync and `remoteHash` accidentally matches a stale `localHash`.

**On hash mismatch the phone sends ONE `sync_replace`, not a per-alarm `alarm_added` burst.** The earlier additive push could only ADD rows — it never removed watch-only rows the phone had since deleted, so the watch's set monotonically grew and the hash never converged (observed: 12 alarms on the watch vs 1 on the phone). `sync_replace` makes the watch's `AlarmStore` byte-for-byte equal the phone snapshot, so the next `sync_check` provably matches. The phone always sends `sync_done` last (both the matched and replaced paths) so the watch terminates immediately instead of lingering on the drain timer.

**Hash algorithm** — `AlarmHash.kt` on phone, `alarmHash.js` on watch. Byte-equivalent implementations:

1. Sort alarms by `id` ascending.
2. For each alarm, render a single canonical line:
   ```
   id|hour|minute|daysOfWeek|enabled|audioUri|isVibrationOnly|snoozeMinutes|updatedAtEpoch|relativeMinutes|selfDestruct|vibrationPattern|maxSnoozeCount|volumeRampSeconds|skipNextEpoch\n
   ```
   - Booleans: `1` / `0`.
   - Null `audioUri`, null `relativeMinutes`, and null `skipNextEpoch`: empty string between the pipes.
   - Missing `vibrationPattern` (pre-Tier-1 peer): defaults to literal `PULSE` on both sides.
   - Missing `maxSnoozeCount` / `volumeRampSeconds`: default to `0` on both sides.
   - `consecutiveSnoozeCount` is **excluded** — phone-side ring-loop state that would force a hash mismatch on every snooze.
3. Concatenate all lines into a single string (newline after every line, including the last).
4. Apply Java `String.hashCode()` over UTF-16 code units. On JS this is `((h << 5) - h + s.charCodeAt(i)) | 0` iterated.
5. Convert signed 32-bit result to unsigned, render as lowercase hex, left-pad with zeros to 8 chars.

Empty list → `"00000000"` (both sides). Tests in `AlarmHashTest.kt` pin canonical strings + reference hex values for cross-impl verification.

If `sync_check` send fails or no `sync_hash` reply arrives within `SYNC_CHECK_TIMEOUT_MS` (2 s), the phone falls through to a full push — never user-blocking, just slightly less efficient.

`sync_hash` payloads that fail the `^[0-9a-f]{8}$` validation are dropped on the phone side (corrupted or spoofed watch).

---

## 6. Customization — what we can and cannot style

Researched 2026-04-25 + revised 2026-04-27 against developer.huawei.com + developer.android.com + Lite Wearable docs.

### 6.1 — HarmonyOS LiteWearable watch

| Surface                     | Customizable? | Mechanism / constraint |
|-----------------------------|---------------|------------------------|
| System reminder card        | N/A           | LiteWearable has no Reminder Agent. There is no system-rendered alarm card before our ring page — we render the entire alarm UI ourselves. |
| **Fullscreen ring page** (our `js/default/pages/ring/ring.{hml,css,js}`) | ✅ | We own every pixel. Round display: own background `<image>` / `<canvas>`. HML/CSS supports full styling. |
| System alarm ringtone       | N/A           | No system-played alarm tone on Lite. We can play audio via `@system.media` or `@system.audio` from inside the page if we want a tone (off by default — phone audio is the primary). |
| Vibration pattern           | ✅            | `vibrator.vibrate({type:'pattern', pattern: [...]})` from the page. We start vibration when the ring page mounts and stop on dismiss. |
| Lock-screen takeover        | ⚠️ different model | LiteWearable apps don't run "over keyguard" the way Android does. The watch face is what shows when the watch is asleep; an `alarm_fired` ping wakes the JS process and the OS launches our ring page on top of the watch face. This is the Lite equivalent of "fullscreen alarm". |

**Bottom line — watch:** we own everything visible. There is no system-level alarm UI on Lite to compete with — it's all ours.

### 6.2 — Android phone

(Unchanged from prior version — Android-side surfaces are not affected by the watch architecture pivot.)

| Surface                     | Customizable? | Mechanism / constraint |
|-----------------------------|---------------|------------------------|
| Heads-up notification background color | ❌ | `Notification.Builder.setColor(int)` tints only the small icon. Material You overrides notification background system-wide on Android 12+; no opt-out. |
| `RemoteViews` custom layouts | ⚠️ partial    | `setStyle(NotificationCompat.DecoratedCustomViewStyle())` + `setCustomContentView(...)` works on Android 15. Heads-up min height 88 dp; collapsed 48 dp; expanded 252 dp. System wraps with standard chrome — full custom heads-up not possible since Android 12. |
| `Notification.CallStyle`    | ❌ wrong fit  | Designed for telephony only. Inappropriate for alarm clocks; enforces Answer/Decline buttons. |
| Action button **icons**     | ⚠️ tinted     | Material You re-tints to dynamic palette; cannot override. Icon ~24-28 dp per Material 3 spec. |
| **Fullscreen `AlarmActivity`** | ✅          | Our own Compose UI. `enableEdgeToEdge()` + `setShowWhenLocked(true)` + `setTurnScreenOn(true)` works on the lockscreen. Must handle insets manually. No system-imposed chrome. |
| Custom alarm audio          | ✅            | `MediaPlayer` w/ `AudioAttributes(USAGE_ALARM)` from inside `AlarmRingService`. Set notification channel `setSound(null)` so the system doesn't double up. |
| Lock-screen "Next alarm" indicator | ✅       | `AlarmManager.setAlarmClock(AlarmClockInfo, PI)` populates this automatically. `Settings.System.NEXT_ALARM_FORMATTED` is deprecated — use `AlarmManager.getNextAlarmClock()`. |
| Samsung One UI 7 alarm UI override | ✅ ours | `setAlarmClock` PendingIntent reaches our `AlarmActivity` cleanly. One UI 7 removed the alarm icon from the status bar (UI redesign), but does not intercept third-party alarms. |

**Recommended approach:** treat the system notification card as a "you have a notification" handoff, not as the actual alarm UI. Spend design effort on `AlarmActivity` (phone) and `pages/ring/ring.hml` (watch), where we control the entire surface.

---

## 7. Code touch points

When changing the sync contract, update **all** of these in the same change:

**Wire format** (must stay in lock-step):
- `android-app/app/src/main/java/com/kirkouski/gtalarm/data/sync/IncomingMessage.kt` — sealed class
- `android-app/app/src/main/java/com/kirkouski/gtalarm/wear/WearBridgeService.kt` — interface
- `android-app/app/src/main/java/com/kirkouski/gtalarm/wear/WearJsonCodec.kt` — inbound parser (`watch_log_batch` expanded directly in the `HuaweiWearBridge` receiver)
- `watch-app/entry/src/main/js/MainAbility/common/incomingHandler.js` — watch receive dispatch (`sync_replace`, `sync_done`, `sync_check`, `settings_changed`, `alarm_*`)
- `watch-app/entry/src/main/js/MainAbility/common/wearBridge.js` — watch send/receive seam + `sendLogBatch`

**Phone-side call sites** (mutations + ring lifecycle, all already wired through Phase 4 + 5a):
- `wear/NoOpWearBridge.kt` — replace with `HuaweiWearBridge.kt` post-AGConnect-approval
- `data/AlarmRepository.kt` — `sendAlarmAdded/Updated/Deleted/Toggled` on every mutation
- `ring/AlarmRingService.kt` — `sendAlarmFired/Dismissed/Snoozed` on action handlers
- `data/sync/IncomingMessageHandler.kt` — receive-side LWW dispatch (Phase 5a)
- `di/WearModule.kt` — single `@Binds` swap

**Watch-side call sites** (new — being implemented in Phase 0b):
- `js/default/services/alarmStore.js` — `@system.storage`-backed local cache (mirrors phone's Room)
- `js/default/services/lwwResolver.js` — port of `LwwResolver.kt`
- `js/default/services/tombstones.js` — port of `Tombstones.kt` (256-entry / 7-day ring buffer)
- `js/default/services/incomingMessageHandler.js` — port of phone-side handler; routes `alarm_fired` to ring page, `alarm_*` mutations to AlarmStore
- `js/default/services/wearBridge.js` — Wear Engine seam; today no-op, post-approval calls `wearengine.getP2pClient().send/onMessage`
- `js/default/pages/ring/ring.js` — `onDismiss` / `onSnooze` send acks back to phone
- `js/default/app.js` — `onCreate` wires the Wear Engine onMessage callback to `incomingMessageHandler.handle`

---

## 8. Local testing strategy

Researched 2026-04-25 + revised 2026-04-27. **Verdict: each side is partially testable in isolation; the cross-device sync chain is hardware-only.**

### 8.1 — What works on emulators

**Android Studio AVD (stock-Android pixel image, API 35):** ✅ confirmed
- `AlarmManager.setAlarmClock` + `setShowWhenLocked` + full-screen-intent → `AlarmActivity` fires reliably. Verify with `adb shell dumpsys alarm | grep com.kirkouski.gtwake.companion`.
- `BootReceiver` triggered via `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.kirkouski.gtwake.companion/.scheduler.BootReceiver`.
- `setAlarmClock` is whitelisted from Doze; `adb shell dumpsys deviceidle force-idle` then wait for fire — confirmed working on AVD.
- `MediaPlayer(USAGE_ALARM)` plays through emulator audio output.
- Phase 5a `IncomingMessageHandler` unit-tested via fakes (82 tests passing).

**DevEco Studio LiteWearable simulator:** 🟡 partial
- Renders HML pages + executes JS lifecycle. Sufficient for visual iteration on the ring page, list, edit page; no animation/perf guarantees match the GT 6 (CPU and GPU profiles differ).
- `vibrator.vibrate` is a no-op on simulator.
- `@system.wearengine` not available on simulator — there is no peer to talk to. The Wear Engine seam falls back to logging.
- No native unit-test framework on Lite (Hypium is NEXT-only). Pure-logic modules (`lwwResolver.js`, `tombstones.js`, `incomingMessageHandler.js` core) get extracted as plain JS and tested via Node + jest under `.local/jest-lite/`. UI / page-lifecycle is manual-test only.

### 8.2 — What does NOT work on emulators (hard gates)

❌ **Wear Engine is physical-device-only** — confirmed both via Huawei codelab requirements and empirically: simulator instances of HMS / Huawei Health do not exist.

❌ **No DevEco "emulator pair" mode** — Huawei does not document any phone-emulator ↔ watch-emulator pairing path. Real Samsung phone + real GT 6 paired through Huawei Health is the only viable end-to-end test bench.

❌ **Local socket / loopback Wear Engine mock — DO NOT BUILD ONE** — would replicate the API surface but not the actual HMS system behavior, giving false confidence. Bugs that depend on real BT/HMS pairing semantics, message ordering, channel restart, peer-disconnected, etc. will all be invisible. Skip mocking the transport entirely.

### 8.3 — Coverage matrix per §4 scenario

| §4 scenario | Phone (AVD) | Watch (Lite simulator) | Cross-device | Verdict |
|---|---|---|---|---|
| 4.1 Alarm fires + dismiss on watch | ✅ phone-only fire testable | 🟡 ring page renders manually-triggered | ❌ Wear Engine | **Half-testable.** Each side works in isolation; the cross-device dismiss propagation is hardware-only. |
| 4.2 Snooze on phone → watch acks | ✅ phone snooze-and-reschedule | 🟡 watch ring page Snooze tap → log | ❌ | Hardware-only end-to-end. |
| 4.3 Edit on phone → watch mirror | ✅ phone Repository mutation + bridge log | 🟡 AlarmStore.update visible | ❌ | Hardware-only end-to-end. |
| 4.4 Reboot survival (phone) | ✅ AVD reboot via `adb reboot`; BootReceiver fires | N/A — watch doesn't schedule | N/A | Phone-only; watch reboot is trivial since there's no scheduling state to recover. |

### 8.4 — Recommended test plan

**Phase 1 — emulator (now, no hardware):**
- Run AC matrix items A1–A6 on AVD. Mark ✅ as each verifies.
- Phase 0b watch: run pure-logic JS modules under jest; manually exercise pages on Lite simulator.
- Code review the bridge call sites (this doc §7) to confirm every mutation routes through `WearBridge`/`wearBridge.js`. The no-op stubs make the contract testable via Logcat/console.log without any P2P.

**Phase 2 — single-device hardware (now, GT 6 + Samsung phone available):**
- Real GT 6 via `应用调测助手` (App Debug Assistant) install path: re-verify watch-side scenarios. Ring page render, vibration, dismiss tap dispatch, AlarmStore persistence across reboot.
- Real Samsung phone: verify One UI 7 alarm icon-removal does not break our `setAlarmClock` flow; verify Sleeping/Deep-sleeping app battery menu doesn't deny our foreground service.

**Phase 3 — cross-device hardware (after AGConnect Wear Engine approval, ~2 weeks out):**
- Drop in `HuaweiWearBridge.kt` + replace `wearBridge.js` send/onMessage with real Wear Engine calls (this doc §7).
- Verify Wear Engine works on Samsung phone with sideloaded HMS Core (architecturally favourable per Chinese-language research). Owner: Andrei Kirkouski. End-to-end smoke test gated on Phase 5b AGConnect approval.
- Pair real Samsung phone + real GT 6 via Huawei Health app (already confirmed working — UDID readback succeeded).
- Run §4 scenarios end-to-end: dismiss propagation, snooze loop, edit mirroring.
- This is the **only** point at which the inter-device contract becomes verifiable. Plan the AC flip from 🟡 → ✅ on cross-device items to happen here, not earlier.

### 8.5 — Implication for design iteration speed

Because the cross-device contract is hardware-gated and the hardware is gated on AGConnect Wear Engine approval, **any churn in the wire format (§2) or flow (§4) before Phase 3 lands is expensive**: you can't catch the bugs locally. Keep the contract minimal, lock it now, and resist adding new message types until Phase 3 is running. The seven message types defined today are enough to cover §4.1–§4.4 without new fields.
