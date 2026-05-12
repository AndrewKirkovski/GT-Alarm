/**
 * Cross-component signal keys backed by AppStorage. Using AppStorage instead
 * of @Provide/@Consume is deliberate: signals need to survive Navigation
 * push/pop boundaries and the EntryAbility-triggered cold start, where
 * @Consume parents may not exist at write time.
 */
export const REFRESH_KEY = 'gtalarm.refresh';

/**
 * Fired-alarm signal. The value is a monotonic counter, NOT the alarm id.
 * The id is published separately under ALARM_FIRED_ID_KEY and read alongside
 * by the @Watch handler. We use a counter so that two consecutive fires of
 * the same alarm id still trigger @Watch (AppStorage suppresses change
 * notifications when the new value equals the old).
 */
export const ALARM_FIRED_TICK_KEY = 'gtalarm.firedAlarmTick';
export const ALARM_FIRED_ID_KEY = 'gtalarm.firedAlarmId';

/**
 * Safe-area inset keys. Populated by EntryAbility.onWindowStageCreate from
 * the system avoid area (status bar + gesture bar). Pages read these via
 * @StorageProp and apply them as padding so content never sits under
 * system chrome.
 *
 * Values are stored in **px** (not vp). Pages convert via px2vp before use.
 */
export const SAFE_TOP_PX = 'gtalarm.safeTopPx';
export const SAFE_BOTTOM_PX = 'gtalarm.safeBottomPx';

/**
 * Round-display horizontal safe margin. There is no dynamic system API for
 * circular safe area, so we use a fixed design inset that keeps content
 * inside the visible arc on a 466px screen. 20vp per side means horizontal
 * content span is 426px — enough clearance from the bezel curve while not
 * wasting space.
 */
export const SAFE_HORIZONTAL_VP = 20;

/**
 * Increment the refresh counter so any @StorageLink listener with @Watch
 * re-runs. Call this after any create/update/delete in AlarmStore.
 */
export function bumpRefresh(): void {
  const current = AppStorage.get<number>(REFRESH_KEY) ?? 0;
  AppStorage.setOrCreate<number>(REFRESH_KEY, current + 1);
}

/**
 * Signal that an alarm has fired. Sets the alarm id and bumps the tick so
 * @Watch handlers fire even on duplicate ids (rare: same alarm fires twice
 * within a session, e.g. user dismisses and reminder re-fires).
 */
export function signalAlarmFired(alarmId: number): void {
  AppStorage.setOrCreate<number>(ALARM_FIRED_ID_KEY, alarmId);
  const current = AppStorage.get<number>(ALARM_FIRED_TICK_KEY) ?? 0;
  AppStorage.setOrCreate<number>(ALARM_FIRED_TICK_KEY, current + 1);
}

/**
 * Read and clear the firing alarm id. Returns 0 if no fire is pending.
 */
export function consumeFiredAlarmId(): number {
  const id = AppStorage.get<number>(ALARM_FIRED_ID_KEY) ?? 0;
  if (id !== 0) {
    AppStorage.setOrCreate<number>(ALARM_FIRED_ID_KEY, 0);
  }
  return id;
}
