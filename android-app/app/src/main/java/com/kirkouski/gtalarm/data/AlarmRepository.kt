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

// reason: TooManyFunctions — function count grew past 15 with the reverse-save
// edit model (saveLocalOnly + deleteLocalOnly + pushAlarmToWatch on top of
// save / setEnabled / setEnabledLocalOnly / delete / snooze / snoozeAt /
// rescheduleAll / rescheduleAllOnBoot / observeAlarms / getById / getAll /
// debug helpers). Each maps to a distinct repository capability over the same
// DAO/scheduler/wear-bridge collaborators; splitting would smear the shared
// dependency surface across more files.
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
     * Persist the alarm to local DB + scheduler + widget WITHOUT pushing to
     * the watch. Used by the edit screen's reverse-save UX: every keystroke
     * commits locally, but the watch only learns about it on exit (see
     * [pushAlarmToWatch]). Keeps the watch's notification feed quiet while
     * the user is mid-edit.
     *
     * For new alarms (id=0), inserts and returns the assigned id. For
     * existing ids, upserts in place.
     */
    suspend fun saveLocalOnly(alarm: Alarm): Long {
        val now = System.currentTimeMillis()
        val isNew = alarm.id == 0L
        // Snooze invalidated on edit — see save() for rationale.
        // Pre-apply settings-derived default ringtone for brand-new draft rows
        // that haven't picked an audio URI yet. Existing alarms keep their
        // own audio (including explicit null = system default).
        val resolved = applyDefaultRingtoneIfNeeded(alarm, isNew)
        val stamped = resolved.copy(updatedAtEpoch = now, snoozedUntilEpoch = null)
        val newId = dao.upsert(stamped.toEntity())
        val saved = stamped.copy(id = if (isNew) newId else stamped.id)
        // INTENTIONALLY does NOT call scheduler.schedule / scheduler.cancel.
        // The edit-screen reverse-save model writes this on every keystroke;
        // if we rescheduled here, a relative "in 1 min" alarm would have its
        // fire time bumped forward by 60 s on every edit (updatedAtEpoch=now
        // is part of computedFireEpoch for relative alarms) — the user could
        // never finish editing it before fire. Edit-screen exit calls
        // [rescheduleAlarm] which performs the schedule/cancel once based
        // on the final state.
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
     * enabled state. Idempotent. Called by AlarmEditViewModel on screen exit
     * to commit the schedule once after a series of [saveLocalOnly] writes.
     * No-op (logs) if the row is missing.
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
     * Discard an in-progress new alarm row that was created via
     * [saveLocalOnly] but never committed (user backed out of the edit
     * screen before completing the alarm). Skips the watch broadcast +
     * tombstone since the watch never learned the alarm existed.
     */
    suspend fun deleteLocalOnly(id: Long) {
        scheduler.cancel(id)
        dao.deleteById(id)
        refreshWidgets()
        Log.i(TAG, "deleteLocalOnly id=$id")
    }

    /**
     * Fire-and-forget broadcast of the alarm's current state to the watch.
     * Called by AlarmEditViewModel on screen exit to flush pending changes
     * after a series of [saveLocalOnly] writes. Uses the singleton-scoped
     * [appScope] so it survives the caller's VM cancellation. Idempotent:
     * if the alarm row is missing (deleted between local-save and flush),
     * silently no-ops.
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
     * Upload the shared watch-side default background `.bin` to the paired
     * watch. The picker writes `watch_bg_<DEFAULT_WATCH_BG_ID>.bin` next to
     * the PNG in [Context.getCacheDir]; we look it up by that sentinel id.
     * If the file is missing (user cleared the default or never picked one)
     * we send the watch a clearance envelope instead so it deletes its own
     * cached default. Caller is responsible for updating the SettingsStore
     * URI first; this method only deals with the on-disk cache + the wire
     * transfer of the BGRA blob.
     */
    fun uploadDefaultWatchBackground() {
        appScope.launch {
            val binFile = java.io.File(appContext.cacheDir, "watch_bg_${DEFAULT_WATCH_BG_ID}.bin")
            if (!binFile.exists()) {
                Log.i(TAG, "uploadDefaultWatchBackground — bin missing, sending cleared envelope")
                wearBridge.sendDefaultWatchBackgroundCleared()
                return@launch
            }
            val ok = wearBridge.uploadDefaultWatchBackground(binFile)
            Log.i(TAG, "uploadDefaultWatchBackground ok=$ok size=${binFile.length()}")
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
        Log.i(
            TAG,
            "missedDuringDowntime id=${alarm.id} target=${alarm.computedFireEpoch()} " +
                "label='${alarm.label}'",
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
            // Match scheduleTestFireInOneMinute: 1-min snooze + self-destruct so
            // the dev loop iterates at the same fast tempo and the row doesn't
            // linger in the list after dismiss. Default 10-min snooze made
            // verification useless on the instant-fire path.
            snoozeMinutes = Alarm.MIN_SNOOZE_MINUTES,
            selfDestruct = true,
        )
        val newId = dao.upsert(draft.toEntity())
        wearBridge.sendAlarmAdded(draft.copy(id = newId))
        refreshWidgets()
        Log.i(TAG, "ensureDebugAlarmId created id=$newId")
        return newId
    }

    // Debug-only: inserts a one-shot alarm and overrides the scheduler's
    // computed hour/minute trigger with an exact `now + 60_000` epoch so a
    // tester doesn't have to wait until the next minute boundary. Returns
    // the trigger epoch in ms so the caller can surface "fires at 14:33:42"
    // in a Toast. The row stays in the DB after the fire — same lifecycle
    // as a real one-shot (auto-disabled by AlarmRingService.handleDismiss).
    suspend fun scheduleTestFireInOneMinute(): Long {
        val now = System.currentTimeMillis()
        val trigger = now + ONE_MINUTE_MS
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = trigger }
        val draft = Alarm(
            label = "Debug 1-min fire",
            hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            minute = cal.get(java.util.Calendar.MINUTE),
            daysOfWeek = 0,
            enabled = true,
            updatedAtEpoch = now,
            // Match the 1-minute fire cadence so test snooze loops at the
            // same fast tempo. Default 10-min snooze makes the dev loop
            // unusable — user would tap snooze and wait 10 min to verify.
            snoozeMinutes = Alarm.MIN_SNOOZE_MINUTES,
            // Self-destruct so the row vanishes after dismiss instead of
            // accumulating disabled "Debug 1-min fire" rows in the list.
            selfDestruct = true,
        )
        val newId = dao.upsert(draft.toEntity())
        val saved = draft.copy(id = newId)
        scheduler.scheduleAt(saved, trigger)
        wearBridge.sendAlarmAdded(saved)
        refreshWidgets()
        Log.i(TAG, "scheduleTestFireInOneMinute id=$newId trigger=$trigger")
        return trigger
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
        const val ONE_MINUTE_MS = 60_000L
        // Sentinel "alarm id" used as the cache-file key for the shared
        // watch-default background (`watch_bg_-1.png` / `watch_bg_-1.bin`).
        // Matches the constant the Settings picker passes to
        // WatchBackgroundPickerDialog. Room ids are auto-generated positive
        // longs, so -1 can never collide with a real alarm.
        const val DEFAULT_WATCH_BG_ID = -1L
    }
}
