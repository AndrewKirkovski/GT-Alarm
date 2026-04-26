# GT Alarm — Phone ↔ Watch Sync Architecture

**Status:** in-progress · **Updated:** 2026-04-25 · **Owner:** Andrei Kirkouski

Companion document to `docs/acceptance-criteria.md`. This is the design-of-record for how the Android phone app and HarmonyOS watch app stay in sync, who fires the alarm, who plays sound, what happens when the user dismisses on one device, and how snooze-then-re-fire propagates.

> **Pre-decision read:** Before changing any of the call sites listed under "Code touch points" or the JSON wire format, re-read this document end-to-end.

---

## 1. Layered model

```
                Android phone (Kotlin / Compose)         |   HarmonyOS watch (ArkTS / ArkUI, GT 6)
                                                         |
  ┌─────────────────────────────────┐                    |    ┌─────────────────────────────────┐
  │ AlarmRepository (Room v1)       │                    |    │ AlarmStore (preferences, JSON)  │
  │  ↕ Hilt-injected                │                    |    │  ↕ static class                 │
  │ AlarmScheduler.setAlarmClock()  │                    |    │ ReminderService.publishReminder │
  │  ↕                              │                    |    │  ↕                              │
  │ AlarmRingService (FG service)   │                    |    │ EntryAbility + AlarmRingPage    │
  │  ↕ DISMISS / SNOOZE intents     |                    |    │  ↕ Dismiss / Snooze taps        │
  └─────────────┬───────────────────┘                    |    └─────────────┬───────────────────┘
                │                                        |                  │
                │ WearBridgeService (Hilt, single binding)                  │ WearBridgeStub (single class)
                │ ──────────────────────────  P2P channel  ─────────────── │
                │      JSON over Wear Engine HiWear P2pClient (post-vendor-approval)
                │      No-op Logger today
```

**Today (pre-Wear-Engine):**
- Android side: `WearBridgeService` interface, `NoOpWearBridge` Hilt-bound. Every call logs to `Log.d(TAG="WearBridge")`.
- Watch side: `WearBridgeStub.ets` (singleton with static methods). Every call logs to hilog domain `0xA1A1` tag `GTAlarm`.
- The two devices are **not actually talking**. Each runs an independent alarm chain.

**Post-approval (~2 weeks out per project plan):**
- Android: drop in `HuaweiWearBridge` implementing `WearBridgeService`, swap one `@Binds` line in `WearModule`, add `implementation("com.huawei.hms:wearengine:<latest>")` to `app/build.gradle.kts`.
- Watch: replace `WearBridge.send()` body with `wearEngine.getP2pClient().send(peerDevice, JSON.stringify(msg))`. Nothing else changes.
- Wire format below is the contract — already used by both sides today.

---

## 2. Wire format

JSON over P2P. Every message is a single line, single object, no nesting:

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
| `rescheduleEpoch` | number  | `alarm_snoozed` only — wall-clock epoch ms of next ring       |
| `enabled`         | boolean | `alarm_toggled` only                                          |
| `alarm`           | object  | `alarm_added` / `alarm_updated` only — full record (see §5.1) |

**Alarm-id consistency:** the phone's Room PK (`Long`) and the watch's `AlarmStore` numeric id MUST refer to the same alarm. Implementation: phone is **id-allocator** (assigns the canonical id on first add); after that both devices are equal-rank for mutations.

**Conflict-resolution rule: last-write-wins (LWW).** Every mutation on either device stamps `updatedAtEpoch = Date.now()` before broadcasting. When a `alarm_added/updated/deleted/toggled` message arrives:
- If `incoming.updatedAtEpoch > local.updatedAtEpoch` (or no local record exists) → apply the incoming write, do NOT re-broadcast (avoid loop).
- If `incoming.updatedAtEpoch < local.updatedAtEpoch` → ignore the incoming message and re-broadcast our newer local state so the sender catches up.
- Tie (`==`) on a live row → incoming wins (deterministic tie-break; common-case is the user's most recent action propagating).
- Tie (`==`) involving a local tombstone → **local tombstone wins** (delete is sticky, see §5.2 below). Both Tombstones impls (`Tombstones.kt` and `Tombstones.ets`) implement `tombstone.epoch >= incoming.epoch ⇒ suppress`.

**Caveats:**
- Clock skew between phone and watch can flip the winner the wrong way. Accepted for MVP. If it becomes a problem, escalate to a Lamport counter ahead of `updatedAtEpoch`.
- A delete is also stamped — a tombstone with `updatedAtEpoch` shippped via `alarm_deleted` MUST beat an older `alarm_updated`. Implementation requirement: the local store keeps a deletion tombstone (or at least the `updatedAtEpoch` of the deletion) for some retention window so a stale `alarm_updated` arriving later doesn't resurrect the row.

---

## 3. Where the alarm fires (design decision)

**Both devices fire independently.** Each side has its own scheduling agent (`AlarmManager.setAlarmClock` on phone, `reminderAgentManager.publishReminder` on watch), each maintains its own scheduled state, each runs its own ring service. The P2P channel is for **state sync**, not for triggering.

Why both, not one:
- Watch must work with phone in another room / out of BT range. The Reminder Agent persists across reboot and runs in a system service — that's why we use it instead of waking up on a phone-driven trigger.
- Phone must work without the watch. Same reason mirrored.
- The redundancy cost: a bit of double-ring at the moment of fire, before the dismiss propagates. We accept it (alarm clocks should err on the side of *more* attention, not less).

**Audio plays on the device whose user-facing UI is currently visible.** If the user is wearing the watch, the watch vibrates + plays the system alarm tone (HarmonyOS Reminder Agent does this — we cannot suppress it; see §6). Phone simultaneously plays its own alarm via `AlarmRingService` with `USAGE_ALARM`. First Dismiss wins (see §4).

---

## 4. Flow diagrams

### 4.1 — Alarm fires on both devices, user dismisses on watch

```
T=0s      Both system schedulers fire simultaneously.
          Phone:                                     Watch:
            AlarmBroadcastReceiver fires               Reminder Agent renders system card
            → AlarmRingService starts FG               → maxScreenWantAgent launches
            → notification w/ fullScreenIntent         → EntryAbility.handleIncomingWant
            → AlarmActivity over keyguard              → reads gtalarm.alarmId from params
            → MediaPlayer(USAGE_ALARM).start()         → AlarmRingPage on Navigation stack
            → Vibrator + screen wake                   → vibrator.startVibration loop
                                                       → system alarm tone (we cannot mute)

T+1s      WearBridge: phone sends { type: alarm_fired, alarmId: 7 }   ─→  watch logs (no-op today)
          WearBridge: watch sends { type: alarm_fired, alarmId: 7 }   ─→  phone logs (no-op today)
          (Today both fire signals are dropped. Post-WearEngine they let
           each device know the other has a live ring in progress.)

T+8s      User taps Dismiss on watch.
          AlarmRingPage.onDismiss():
            – stopVibration()
            – if (one-shot) cancelReminder + AlarmStore.update(enabled=false)
            – pathStack.pop()
            – WearBridgeStub.notifyDismissed(7)        ─→  phone

T+8.1s    Phone receives { type: alarm_dismissed, alarmId: 7 }
          AlarmRingService.handleDismiss():
            – MediaPlayer.stop()
            – Vibrator.cancel()
            – stopForegroundAndSelf()
            – wearBridge.sendDismiss is NOT re-broadcast (avoid loop)
          AlarmActivity.finish()
```

**Idempotence rule:** receiving a dismiss for an `alarmId` whose ring service is already stopped is a no-op. Receiving a dismiss for an alarm we never fired (skewed clocks, P2P delivery delay) is also a no-op — the local side will time out on its own at `ringDuration: 60s`.

### 4.2 — Snooze on phone, re-fire after 10 min, both devices ring again

```
T=0s    Both fire (as above).

T+5s    User taps Snooze on phone.
        AlarmRingService.handleSnooze():
          – computes newTrigger = now + SNOOZE_MINUTES (10) * 60_000
          – AlarmManager.setAlarmClock(newTrigger, PI) — REUSES alarm.id as requestCode
          – wearBridge.sendSnooze(7, newTrigger) ─→ watch
          – stopForegroundAndSelf()
          – AlarmActivity.finish()

T+5.1s  Watch receives { type: alarm_snoozed, alarmId: 7, rescheduleEpoch: T+10min }
        WearBridge listener (post-WearEngine path):
          – AlarmStore.getById(7)
          – cancelReminder(item.reminderId)              ← cancel the watch's own re-fire chain
          – publishReminder with hour/minute derived from rescheduleEpoch
            (HarmonyOS Reminder doesn't accept absolute epoch — we set explicit
             hour:minute and clear daysOfWeek for a one-shot)
          – AlarmStore.update(item) with new reminderId
          – AlarmRingPage.pop() if it's still on the stack

T+10min Both system schedulers fire again. Loop returns to §4.1.
```

**Snooze-chain cap:** the watch's `reminderAgentManager` is configured with `snoozeTimes: 3, timeInterval: 600` at original publish time. The system will auto-snooze up to 3 times if the user taps the SNOOZE action button on the system card (NOT our in-app Snooze button). Our in-app Snooze button explicitly reschedules and exits — this is the path that propagates cross-device.

**Phone side has no equivalent built-in snooze chain** — every snooze re-runs `AlarmManager.setAlarmClock` with a fresh trigger. Phone can snooze indefinitely until the user dismisses.

### 4.3 — Edit on phone, mirror to watch

```
User opens AlarmEditScreen on phone, changes 07:00 → 06:30, saves.

AlarmRepository.update(alarm):
  – Room UPDATE
  – AlarmScheduler.cancel(alarm.id)
  – AlarmScheduler.scheduleNext(alarm)   ← computes nextTriggerEpoch
  – widget.update(ctx, glanceId)
  – wearBridge.sendAlarmUpdate(alarm)    ─→ watch

Watch receives { type: alarm_updated, alarmId: 7 }
WearBridge listener:
  – fetch the FULL alarm payload (separate request? or carry in the message?)
       see §5 "Open question"
  – AlarmStore.update(item)
  – cancelReminder(old.reminderId)
  – publishReminder(new alarm)
  – AlarmStore.update with new reminderId
  – bumpRefresh() → list re-renders
```

### 4.4 — Reboot survival (no P2P involved)

```
Watch reboot:
  reminderAgentManager persists across reboot — no app code runs at boot.
  When the alarm time arrives, the system fires our maxScreenWantAgent.
  EntryAbility.onCreate runs reconcileReminders() to clean up any drift
  between local store and OS reminder list.

Phone reboot:
  BootReceiver fires on BOOT_COMPLETED (post-unlock) and
  LOCKED_BOOT_COMPLETED (direct-boot — but we defer Room reads).
  rescheduleAll() iterates AlarmRepository, re-runs setAlarmClock on every
  enabled alarm.
```

Reboot does NOT trigger any cross-device sync — both sides recover from their own persistent state. P2P comes back online only when both devices are awake and paired.

---

## 5. Open questions / known gaps

### 5.1 — Decided: full payload travels with `alarm_added` / `alarm_updated`
The message body carries the full `Alarm` record (hour, minute, daysOfWeek, label, enabled, audioUri, isVibrationOnly, updatedAtEpoch). Stateless — the receiver applies it directly without a round-trip. Schema-drift risk handled by including a `schemaVersion` field if/when the contract grows; today both sides agree on the v1 shape implicit in `domain/Alarm.kt` and `model/AlarmItem.ets`.

### 5.2 — Decided: last-write-wins on offline-edit conflicts (2026-04-25)
Per §2's conflict-resolution rule. Both sides MUST stamp `updatedAtEpoch = Date.now()` on every local mutation (Room insert/update/delete on phone; `AlarmStore.add/update/delete` on watch) BEFORE broadcasting. Tracking items:
- ❌ Add `updatedAtEpoch: Long` (non-null) to `domain/Alarm.kt` + `data/db/AlarmEntity.kt` (Room migration v1→v2 required, **not** `fallbackToDestructiveMigration`).
- ❌ Add `updatedAtEpoch: number` to `model/AlarmItem.ts` interface + `AlarmStore.validate` to require it.
- ❌ Stamp on every mutation: phone `AlarmRepository.{insert,update,delete,setEnabled}`; watch `EditPage.onSave`, `Index.onToggle`, `Index.performPendingDelete`, `AlarmRingPage.onDismiss` (when it flips enabled=false on one-shot).
- ❌ Apply LWW comparison in receiver paths once `WearBridge` listeners exist (Phase 3 hardware test).
- ❌ Deletion tombstones — keep deleted ids + their `updatedAtEpoch` in a small ring buffer (suggest 7-day retention, max 256 entries) so a stale `alarm_updated` arriving after a delete cannot resurrect.

### 5.3 — Snooze on watch propagating to phone reschedule
Symmetric to §4.2 but with phone as receiver. Implementation matches; no new design.

### 5.4 — First-fire jitter
Both system schedulers may fire 0–2s apart even when scheduled identically (phone `setAlarmClock` precision vs HarmonyOS Reminder Agent precision). Acceptable; first dismiss wins anyway.

### 5.5 — One-shot timeout regression
Already tracked in AC item 12 / `memory/watch_pending_after_i18n.md`. If the system `ringDuration: 60s` lapses on the watch without a Dismiss tap, `enabled` stays `true` until the next cold-start `reconcileReminders()`. Same gap exists on phone-side auto-dismiss. Under LWW: a stale ring that times out without flipping `enabled=false` will be overwritten by any subsequent edit on either device, so this gap is bounded by the next user action — not corruption-class.

---

## 6. Customization — what we can and cannot style

Researched 2026-04-25 against developer.huawei.com + developer.android.com + SDK `.d.ts` + community write-ups.

### 6.1 — HarmonyOS watch (Reminder Agent)

| Surface                     | Customizable? | Mechanism / constraint |
|-----------------------------|---------------|------------------------|
| System reminder card colors / fonts | ❌ | System-rendered. We control text + icon assets, not styling. `slotType` is a slot-type enum, not a theme. |
| Action button **icons**     | ❌            | `actionButton[].title` is text only. No icon field. |
| Card vs fullscreen path     | ⚠️ partial    | OS shows the card briefly, then auto-launches `maxScreenWantAgent`. Cannot suppress card. |
| **Fullscreen ring page** (our `AlarmRingPage.ets`) | ✅ | We own every pixel. `setWindowLayoutFullScreen(true)` + `setWindowSystemBarProperties` for status bar. Round display: own background `Image`/`Color`. |
| System alarm ringtone       | ❌            | `REMINDER_TYPE_ALARM` always plays the system tone. We cannot suppress it. We can layer additional audio via `AVPlayer` in our page, but the OS ringtone plays alongside — risk of cacophony. Prefer NOT layering. |
| Vibration pattern           | ✅            | `vibrationPattern: number[]` in `ReminderRequest` (publish-time), or `vibrator.startVibration({type:'pattern', pattern: [...]})` from the page (runtime). We use the runtime path today — see `AlarmRingPage.startVibration`. |

**Bottom line — watch:** background and design freedom is on the **fullscreen page only**. The card before it and the alarm tone behind it are system-owned.

### 6.2 — Android phone

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

**Bottom line — phone:** like the watch, we own the fullscreen activity completely. The notification card before it is constrained by Material You + system layout templates.

**Recommended approach for design freedom:** treat the system notification card on both platforms as a "you have a notification" handoff, not as the actual alarm UI. Keep both notification cards minimal (system defaults + label). Spend design effort on `AlarmActivity` (phone) and `AlarmRingPage` (watch), where we control the entire surface.

---

## 7. Code touch points

When changing the sync contract, update **all** of these in the same change:

**Wire format** (must stay in lock-step):
- `android-app/app/src/main/java/com/kirkouski/gtalarm/wear/WearBridgeService.kt` — interface
- `watch-app/entry/src/main/ets/util/WearBridgeStub.ets` — `BridgeMessage` interface + send

**Phone-side call sites** (mutations + ring lifecycle):
- `wear/NoOpWearBridge.kt` — replace with `HuaweiWearBridge` post-approval
- `data/AlarmRepository.kt` — `sendAlarmUpdate` on every mutation
- `ring/AlarmRingService.kt` — `sendDismiss` / `sendSnooze` on action handlers
- `di/WearModule.kt` — single `@Binds` swap

**Watch-side call sites:**
- `service/ReminderService.publishAlarm` and `cancelAlarm`
- `pages/EditPage.onSave` (notifyAdded/notifyUpdated)
- `pages/Index.performPendingDelete` (notifyDeleted)
- `pages/Index.onToggle` (notifyToggled)
- `pages/AlarmRingPage.onDismiss` / `onSnooze` (notifyDismissed/notifySnoozed)
- `ability/EntryAbility.fireAlarm` (notifyFired) + `reconcileReminders`

---

## 8. Local testing strategy

Researched 2026-04-25. **Verdict: each side is fully testable on emulators in isolation, but the cross-device sync chain (scenarios in §4) is NOT testable on emulators — Wear Engine is physical-device-only.** Plan accordingly.

### 8.1 — What works on emulators

**Android Studio AVD (stock-Android pixel image, API 35):** ✅ confirmed
- `AlarmManager.setAlarmClock` + `setShowWhenLocked` + full-screen-intent → `AlarmActivity` fires reliably. Verify with `adb shell dumpsys alarm | grep com.kirkouski.gtalarm`.
- `BootReceiver` triggered via `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.kirkouski.gtalarm/.scheduler.BootReceiver`.
- `setAlarmClock` is whitelisted from Doze; `adb shell dumpsys deviceidle force-idle` then wait for fire — confirmed working on AVD.
- `MediaPlayer(USAGE_ALARM)` plays through emulator audio output.
- Force-fire without waiting: `adb shell am start-foreground-service -a com.kirkouski.gtalarm.ACTION_RING --el alarm_id 1 -n com.kirkouski.gtalarm/.ring.AlarmRingService`.

**DevEco Studio HarmonyOS NEXT wearable emulator:** 🟡 partial
- `reminderAgentManager.publishReminder` API surface compiles + accepts publish calls.
- `maxScreenWantAgent` fullscreen launch behavior is **not officially confirmed** by Huawei docs to fire end-to-end on the emulator. Community examples exist (DEV Community, Medium Huawei Developers) but none state "fullscreen wantAgent fires on the emulator." Treat as 🟡 until verified locally.
- HiLog inspection works: `hdc hilog | grep GTAlarm`.
- Reboot survival of reminders on the emulator: undocumented; assume untestable until verified on real GT 6.

### 8.2 — What does NOT work on emulators (hard gates)

❌ **Wear Engine is physical-device-only.** Per Huawei Wear Engine Codelab (`developer.huawei.com/consumer/en/codelab/WearEngine/`), requirements explicitly list "A Huawei phone (USB cable)" + "A Huawei watch" — zero mention of emulator support. The SDK couples to HMS system bindings that don't exist on emulator images.

❌ **No DevEco "emulator pair" mode.** Unlike Google's Wear OS pairing assistant (Android Studio + Wear OS AVD), Huawei does not document any phone-emulator ↔ watch-emulator pairing path. Physical devices pair via the "Super Device radar" UI; there is no emulator equivalent.

❌ **Local socket / loopback Wear Engine mock — DO NOT BUILD ONE.** The agent research explicitly warns: a local socket adapter would replicate the API surface but not the actual HMS system behavior, giving false confidence. Bugs that depend on real BT/HMS pairing semantics, message ordering, channel restart, peer-disconnected, etc. will all be invisible. **Skip mocking the transport entirely.**

### 8.3 — Coverage matrix per §4 scenario

| §4 scenario | Phone (AVD) | Watch (DevEco emulator) | Cross-device | Verdict |
|---|---|---|---|---|
| 4.1 Alarm fires both sides + dismiss on watch | ✅ phone-only fire testable | 🟡 watch-only fire (fullscreen unconfirmed) | ❌ Wear Engine | **Half-testable.** Each side fires in its own emulator; the cross-device dismiss propagation is hardware-only. |
| 4.2 Snooze on phone → watch reschedule | ✅ phone snooze-and-reschedule | 🟡 watch republish | ❌ | Hardware-only end-to-end. |
| 4.3 Edit on phone → watch mirror | ✅ phone Repository mutation + bridge log | 🟡 watch upsert + bridge log | ❌ | Hardware-only end-to-end. |
| 4.4 Reboot survival | ✅ AVD reboot via `adb reboot`; BootReceiver fires | 🟡 unverified on emulator | N/A (no P2P) | Phone is testable on AVD; watch needs real GT 6. |

### 8.4 — Recommended test plan

**Phase 1 — emulator (now, no hardware):**
- Run AC test matrix items A1–A6 on AVD. Mark ✅ as each verifies.
- Run AC test matrix items W1–W3 on DevEco wearable emulator (alarm publish + dismiss/snooze tap inside the watch UI; not cross-device).
- Code review the bridge call sites (this doc §7) to confirm every mutation routes through `WearBridge`/`WearBridgeStub`. The no-op stubs make the contract testable via HiLog/Logcat without any P2P.

**Phase 2 — single-device hardware (after GT 6 access):**
- Real GT 6 over Wi-Fi `hdc`: re-verify W1–W3 scenarios. Confirm fullscreen `maxScreenWantAgent` fires + survives reboot.
- Real Samsung phone: verify One UI 7 alarm icon-removal does not break our `setAlarmClock` flow; verify Sleeping/Deep-sleeping app battery menu doesn't deny our foreground service.

**Phase 3 — cross-device hardware (after Wear Engine approval, ~2 weeks out):**
- Drop in `HuaweiWearBridge.kt` + replace `WearBridge.send` body on watch (this doc §7).
- Pair real Samsung phone + real GT 6 via Huawei Health app.
- Run §4 scenarios end-to-end: dismiss propagation, snooze loop, edit mirroring.
- This is the **only** point at which the inter-device contract becomes verifiable. Plan the AC flip from 🟡 → ✅ on cross-device items to happen here, not earlier.

### 8.5 — Implication for design iteration speed

Because the cross-device contract is hardware-gated and the hardware is gated on vendor approval, **any churn in the wire format (§2) or flow (§4) before Phase 3 lands is expensive**: you can't catch the bugs locally. Keep the contract minimal, lock it now, and resist adding new message types until Phase 3 is running. The seven message types defined today are enough to cover §4.1–§4.4 without new fields.
