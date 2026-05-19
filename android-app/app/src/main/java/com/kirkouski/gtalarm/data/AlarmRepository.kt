package com.kirkouski.gtalarm.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kirkouski.gtalarm.data.db.AlarmDao
import com.kirkouski.gtalarm.data.db.toDomain
import com.kirkouski.gtalarm.data.db.toEntity
import com.kirkouski.gtalarm.di.IoDispatcher
import com.kirkouski.gtalarm.domain.Alarm
import com.kirkouski.gtalarm.ring.AlarmNotifications
import com.kirkouski.gtalarm.scheduler.AlarmScheduler
import com.kirkouski.gtalarm.wear.WearBridgeService
import com.kirkouski.gtalarm.widget.WidgetRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject
import javax.inject.Singleton

// reason: TooManyFunctions — the repository exposes the full alarm CRUD
// surface (save / setEnabled / setEnabledLocalOnly / delete / snooze /
// snoozeAt / rescheduleAll / rescheduleAllOnBoot / observeAlarms / getById /
// getAll + the three-phase edit-screen save: saveLocalOnly + rescheduleAlarm
// + pushAlarmToWatch + the default-watch-bg helpers + debug helpers). Each
// maps to a distinct repository capability over the same DAO/scheduler/
// wear-bridge collaborators; splitting would smear the shared dependency
// surface across more files.
// reason: LongParameterList — 8 collaborators (DAO, scheduler, wear bridge,
// tombstones, widget refresher, settings, context, dispatcher). Splitting
// would smear the same DI graph across more files without removing params.
@Suppress("TooManyFunctions", "LongParameterList")
@Singleton
class AlarmRepository @Inject constructor(
    private val dao: AlarmDao,
    private val scheduler: AlarmScheduler,
    private val wearBridge: WearBridgeService,
    private val tombstones: Tombstones,
    private val widgetRefresher: WidgetRefresher,
    private val settingsStore: SettingsStore,
    @param:ApplicationContext private val appContext: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    // Singleton-scoped scope for fire-and-forget watch pushes from
    // sources whose own scope is about to be cancelled (e.g. AlarmEditViewModel
    // flushing on onCleared). SupervisorJob so one push failure doesn't kill
    // sibling pushes.
    private val appScope: CoroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())

    fun observeAlarms(): Flow<List<Alarm>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: Long): Alarm? = dao.getById(id)?.toDomain()

    suspend fun getAll(): List<Alarm> = dao.getAll().map { it.toDomain() }

    suspend fun save(alarm: Alarm): Long {
        val now = System.currentTimeMillis()
        val isNew = alarm.id == 0L
        // An edit invalidates any active snooze — the user is reconfiguring,
        // so the previously-deferred fire time no longer makes sense.
        // Resolve default ringtone from Settings for new alarms with no URI.
        val resolved = applyDefaultRingtoneIfNeeded(alarm, isNew)
        val stamped = resolved.copy(updatedAtEpoch = now, snoozedUntilEpoch = null)
        val newId = dao.upsert(stamped.toEntity())
        val saved = stamped.copy(id = if (isNew) newId else stamped.id)
        if (saved.enabled) {
            scheduler.schedule(saved)
        } else {
            scheduler.cancel(saved.id)
        }
        if (isNew) wearBridge.sendAlarmAdded(saved) else wearBridge.sendAlarmUpdated(saved)
        refreshWidgets()
        Log.i(
            TAG,
            "save kind=${if (isNew) "added" else "updated"} id=${saved.id} " +
                "enabled=${saved.enabled} dow=${saved.daysOfWeek} stamp=$now",
        )
        return saved.id
    }

    /**
     * Persist to local DB + widget WITHOUT touching the scheduler or pushing
     * to the watch. The edit screen calls this from inside its save()
     * NonCancellable block; [rescheduleAlarm] + [pushAlarmToWatch] then
     * commit the remaining side-effects so all three can be wrapped in the
     * same NonCancellable to survive caller-VM cancellation.
     *
     * For new alarms (id=0), inserts and returns the assigned id. For
     * existing ids, upserts in place.
     */
    suspend fun saveLocalOnly(alarm: Alarm): Long {
        val now = System.currentTimeMillis()
        val isNew = alarm.id == 0L
        // Pre-apply settings-derived default ringtone for brand-new rows
        // that haven't picked an audio URI yet. Existing alarms keep their
        // own audio (including explicit null = system default).
        val resolved = applyDefaultRingtoneIfNeeded(alarm, isNew)
        val stamped = resolved.copy(updatedAtEpoch = now, snoozedUntilEpoch = null)
        val newId = dao.upsert(stamped.toEntity())
        val saved = stamped.copy(id = if (isNew) newId else stamped.id)
        refreshWidgets()
        Log.i(
            TAG,
            "saveLocalOnly kind=${if (isNew) "added" else "updated"} id=${saved.id} " +
                "enabled=${saved.enabled} dow=${saved.daysOfWeek} stamp=$now",
        )
        return saved.id
    }

    /**
     * Reads the alarm by id and schedules or cancels it based on its current
     * enabled state. Idempotent. Paired with [saveLocalOnly] so the edit
     * screen's NonCancellable save block commits Room first, scheduler
     * second. No-op (logs) if the row is missing.
     */
    suspend fun rescheduleAlarm(id: Long) {
        val alarm = dao.getById(id)?.toDomain()
        if (alarm == null) {
            Log.d(TAG, "rescheduleAlarm id=$id — row missing, skipping")
            return
        }
        if (alarm.enabled) scheduler.schedule(alarm) else scheduler.cancel(id)
        Log.i(TAG, "rescheduleAlarm id=$id enabled=${alarm.enabled}")
    }

    /**
     * Fire-and-forget broadcast of the alarm's current state to the watch.
     * Paired with [saveLocalOnly] + [rescheduleAlarm] as the third leg of
     * the edit-screen save chain. Uses the singleton-scoped [appScope] so
     * it survives the caller's VM cancellation. Idempotent: if the alarm
     * row is missing (deleted between local-save and flush), silently
     * no-ops.
     */
    fun pushAlarmToWatch(id: Long) {
        appScope.launch {
            val alarm = dao.getById(id)?.toDomain() ?: run {
                Log.d(TAG, "pushAlarmToWatch id=$id — row missing, skipping")
                return@launch
            }
            wearBridge.sendAlarmUpdated(alarm)
            Log.i(TAG, "pushAlarmToWatch id=$id stamp=${alarm.updatedAtEpoch}")
        }
    }

    /**
     * Upload the shared watch-side default background to the paired watch.
     * The picker writes `watch_bg_<DEFAULT_WATCH_BG_ID>.png` in
     * [Context.getCacheDir]; [com.kirkouski.gtalarm.wear.WatchBgTestEncoder]
     * derives the actual file to send (a hardware experiment over JPG/PNG/
     * BGRA formats — see that class). If the source PNG is missing (user
     * cleared the default or never picked one) we send the watch a
     * clearance envelope instead.
     */
    fun uploadDefaultWatchBackground() {
        appScope.launch {
            val srcPng = java.io.File(appContext.cacheDir, "watch_bg_${DEFAULT_WATCH_BG_ID}.png")
            if (!srcPng.exists()) {
                Log.i(TAG, "uploadDefaultWatchBackground — source png missing, sending cleared envelope")
                wearBridge.sendDefaultWatchBackgroundCleared()
                return@launch
            }
            val bgFile = com.kirkouski.gtalarm.wear.WatchBgTestEncoder.prepare(srcPng, appContext.cacheDir)
            if (bgFile == null) {
                Log.w(TAG, "uploadDefaultWatchBackground — test encode failed, skipping")
                return@launch
            }
            val ok = wearBridge.uploadDefaultWatchBackground(bgFile)
            Log.i(TAG, "uploadDefaultWatchBackground ok=$ok file=${bgFile.name} size=${bgFile.length()}")
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val now = System.currentTimeMillis()
        dao.setEnabledStamped(id, enabled, now)
        // Disabling clears any active snooze — the alarm is off, the
        // deferred fire shouldn't display. Re-enabling later re-computes
        // next-trigger from hour/minute, which is what the user expects.
        if (!enabled) dao.setSnoozedUntil(id, null)
        val alarm = dao.getById(id)?.toDomain() ?: return
        if (enabled) scheduler.schedule(alarm) else scheduler.cancel(id)
        wearBridge.sendAlarmToggled(alarm)
        refreshWidgets()
        Log.i(TAG, "setEnabled id=$id enabled=$enabled stamp=$now")
    }

    suspend fun setEnabledLocalOnly(id: Long, enabled: Boolean) {
        val now = System.currentTimeMillis()
        dao.setEnabledStamped(id, enabled, now)
        if (!enabled) dao.setSnoozedUntil(id, null)
        val alarm = dao.getById(id)?.toDomain() ?: return
        if (enabled) scheduler.schedule(alarm) else scheduler.cancel(id)
        refreshWidgets()
        Log.i(TAG, "setEnabledLocalOnly id=$id enabled=$enabled stamp=$now")
    }

    suspend fun delete(id: Long) {
        scheduler.cancel(id)
        val now = System.currentTimeMillis()
        dao.deleteById(id)
        tombstones.add(id, now, now)
        wearBridge.sendAlarmDeleted(id, now)
        refreshWidgets()
        Log.i(TAG, "delete id=$id stamp=$now")
    }

    // Snooze using the alarm's own configured duration. Override is for
    // tests / one-off explicit-minutes callers; production callers should
    // pass null so the per-alarm value is honored.
    suspend fun snooze(id: Long, minutesOverride: Int? = null): Long? {
        val alarm = dao.getById(id)?.toDomain() ?: return null
        val minutes = minutesOverride ?: alarm.snoozeMinutes
        if (minutes <= Alarm.SNOOZE_DISABLED) {
            // Guards against a 0-minute re-fire when the alarm has snooze
            // disabled and the caller forgot to supply an override. Without
            // this, scheduleAt(now) would fire the alarm immediately again.
            Log.w(TAG, "snooze id=$id refused — minutes=$minutes (alarm has snooze disabled and no override)")
            return null
        }
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        scheduler.scheduleAt(alarm, trigger)
        // Persist the snooze trigger so the list UI shows the actual next-
        // fire time. Cleared by AlarmRingService when the alarm fires, or
        // by save/setEnabled(false) if the user reconfigures the alarm.
        dao.setSnoozedUntil(id, trigger)
        wearBridge.sendAlarmSnoozed(id, trigger)
        refreshWidgets()
        Log.i(TAG, "snooze id=$id +${minutes}min trigger=$trigger")
        return trigger
    }

    suspend fun snoozeAt(id: Long, triggerEpoch: Long): Long? {
        val alarm = dao.getById(id)?.toDomain() ?: return null
        scheduler.scheduleAt(alarm, triggerEpoch)
        dao.setSnoozedUntil(id, triggerEpoch)
        refreshWidgets()
        Log.i(TAG, "snoozeAt id=$id trigger=$triggerEpoch (from-peer)")
        return triggerEpoch
    }

    /**
     * Clear an alarm's snooze override. Called by AlarmRingService when the
     * alarm fires (the snooze trigger has been consumed) so the list UI
     * goes back to displaying the next recurrence of its clock time.
     */
    suspend fun clearSnoozedUntil(id: Long) {
        dao.setSnoozedUntil(id, null)
        refreshWidgets()
    }

    suspend fun rescheduleAll() {
        val alarms = getAll().filter { it.enabled }
        Log.i(TAG, "rescheduleAll enabledCount=${alarms.size}")
        scheduler.rescheduleAll(alarms)
    }

    // Boot-time variant that applies the relative-alarm past-due rule
    // (memory:litewearable_storage_limits + binary-wire-format spec):
    //   - A relative alarm whose computed fire time is in the past gets
    //     classified by whether it fell during downtime or during current
    //     uptime.
    //   - Past during current uptime (target >= bootCompleteAt): schedule
    //     normally; AlarmManager will fire it immediately (or near-so).
    //   - Past during downtime (target < bootCompleteAt): post a passive
    //     "missed" notification and delete the row (tombstones + propagate
    //     to watch). The user gets one informational notification per
    //     missed reminder.
    suspend fun rescheduleAllOnBoot() {
        val now = System.currentTimeMillis()
        // bootCompleteAt = wall-clock - elapsed-realtime-since-boot. Normally
        // monotonic and < now. If the user rolled the wall clock BACKWARDS
        // since boot (NTP correction, manual change), bootCompleteAt would
        // land in the future and we'd misclassify EVERY relative alarm
        // (including ones that haven't fired yet) as "missed during downtime"
        // and delete them. Clamp to now to fail safe — at worst we treat a
        // legitimately-missed relative alarm as if it can still fire and
        // AlarmManager fires it immediately.
        val bootCompleteAt = (now - SystemClock.elapsedRealtime()).coerceAtMost(now)
        val alarms = getAll().filter { it.enabled }
        val missed = mutableListOf<Alarm>()
        val keep = mutableListOf<Alarm>()
        alarms.forEach { alarm ->
            if (alarm.isRelative && alarm.computedFireEpoch() < bootCompleteAt) {
                missed += alarm
            } else {
                keep += alarm
            }
        }
        Log.i(
            TAG,
            "rescheduleAllOnBoot enabled=${alarms.size} keep=${keep.size} " +
                "missedDuringDowntime=${missed.size} bootCompleteAt=$bootCompleteAt",
        )
        scheduler.rescheduleAll(keep)
        missed.forEach { handleMissedDuringDowntime(it) }
    }

    private suspend fun handleMissedDuringDowntime(alarm: Alarm) {
        // labelLen instead of label content: alarm labels can be PII
        // ("Doctor's appointment", "Job interview"); logcat is readable by
        // certain system contexts. Length + has-label flag is enough for
        // diagnostics without leaking the string.
        Log.i(
            TAG,
            "missedDuringDowntime id=${alarm.id} target=${alarm.computedFireEpoch()} " +
                "labelLen=${alarm.label.length}",
        )
        AlarmNotifications.postMissedNotification(appContext, alarm)
        // Self-destruct semantics: relative alarms with a downtime miss are
        // dead — they couldn't have served their purpose. Delete the row
        // (tombstones + propagate to watch) regardless of selfDestruct flag
        // value on the alarm; a missed-during-downtime row is non-recoverable.
        delete(alarm.id)
    }

    // Debug-only: returns the id of an alarm we can fire RIGHT NOW.
    // Picks the first existing enabled alarm; if none, inserts one with
    // hour/minute set to current time so the row exists for the ring
    // service to look up. Returns the alarm id.
    suspend fun ensureDebugAlarmId(): Long {
        val existing = dao.getAll().firstOrNull { it.enabled }
        if (existing != null) return existing.id
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val draft = Alarm(
            label = "Debug instant fire",
            hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            minute = cal.get(java.util.Calendar.MINUTE),
            daysOfWeek = 0,
            enabled = true,
            updatedAtEpoch = now,
            // 1-min snooze + self-destruct so the debug fire-now loop
            // iterates fast and the row self-clears after dismiss instead
            // of accumulating disabled "Debug instant fire" rows.
            snoozeMinutes = Alarm.MIN_SNOOZE_MINUTES,
            selfDestruct = true,
        )
        val newId = dao.upsert(draft.toEntity())
        wearBridge.sendAlarmAdded(draft.copy(id = newId))
        refreshWidgets()
        Log.i(TAG, "ensureDebugAlarmId created id=$newId")
        return newId
    }

    private suspend fun refreshWidgets() = widgetRefresher.refresh()

    /**
     * For a brand-new alarm (`isNew && audioUri == null`) consult
     * [SettingsStore] for the user's chosen default ringtone — the relative-
     * vs-absolute distinction picks the right slot. If neither slot has a
     * user-set default, the alarm keeps its null URI which downstream
     * (AlarmAudioPlayer) treats as RingtoneManager.TYPE_ALARM.
     *
     * Edits to an existing alarm (`!isNew`) and explicit user choices on a
     * new alarm (`audioUri != null`) flow through unchanged — we never
     * silently overwrite a deliberate selection.
     */
    private suspend fun applyDefaultRingtoneIfNeeded(alarm: Alarm, isNew: Boolean): Alarm {
        if (!isNew || alarm.audioUri != null) return alarm
        val s = settingsStore.snapshot()
        val (uri, name) = if (alarm.isRelative) {
            s.defaultRelativeRingtoneUri to s.defaultRelativeRingtoneName
        } else {
            s.defaultAbsoluteRingtoneUri to s.defaultAbsoluteRingtoneName
        }
        return if (uri == null) alarm else alarm.copy(audioUri = uri, audioName = name)
    }

    private companion object {
        const val TAG = "AlarmRepo"
        // Sentinel "alarm id" used as the cache-file key for the shared
        // watch-default background (`watch_bg_-1.png` / `watch_bg_-1.bin`).
        // Matches the constant the Settings picker passes to
        // WatchBackgroundPickerDialog. Room ids are auto-generated positive
        // longs, so -1 can never collide with a real alarm.
        const val DEFAULT_WATCH_BG_ID = -1L
    }
}
