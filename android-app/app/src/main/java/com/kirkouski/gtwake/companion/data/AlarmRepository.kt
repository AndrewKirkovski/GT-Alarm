package com.kirkouski.gtwake.companion.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kirkouski.gtwake.companion.data.bfu.BfuAlarmCache
import com.kirkouski.gtwake.companion.data.db.AlarmDao
import com.kirkouski.gtwake.companion.data.db.toDomain
import com.kirkouski.gtwake.companion.data.db.toEntity
import com.kirkouski.gtwake.companion.di.IoDispatcher
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.NextTriggerCalculator
import com.kirkouski.gtwake.companion.ring.AlarmNotifications
import com.kirkouski.gtwake.companion.scheduler.AlarmScheduler
import com.kirkouski.gtwake.companion.wear.ForceSyncResult
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import com.kirkouski.gtwake.companion.widget.WidgetRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// reason: TooManyFunctions — the repository exposes the full alarm CRUD
// surface (save / setEnabled / setEnabledLocalOnly / delete / snooze /
// snoozeAt / rescheduleAll / rescheduleAllOnBoot / observeAlarms / getById /
// getAll + the two-phase edit-screen save: saveLocalOnly + rescheduleAlarm
// + scheduleWatchSync / syncWatchNow + the default-watch-bg helpers + debug
// helpers). Each maps to a distinct repository capability over the same
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
    private val bfuCache: BfuAlarmCache,
    @param:ApplicationContext private val appContext: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    // Singleton-scoped scope for fire-and-forget watch pushes from
    // sources whose own scope is about to be cancelled (e.g. AlarmEditViewModel
    // flushing on onCleared). SupervisorJob so one push failure doesn't kill
    // sibling pushes.
    private val appScope: CoroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())

    // Debounced watch reconcile. The watch CANNOT wake the phone (Wear
    // Engine asymmetry — see memory wear_engine_requires_activity), so it
    // can never pull: the phone drives ALL sync. Every alarm/settings
    // mutation calls scheduleWatchSync(); the debounce coalesces a burst
    // of edits into a single watch wake.
    private val watchSyncMutex = Mutex()
    // Serializes the watch-bg reconcile/upload path (F4). The bridge dispatches
    // every inbound message on its own coroutine, so two watch_screen replies
    // can run reconcileWatchBackground concurrently; without this a second
    // reconcile reads the pre-upload lastUploadedWatchBgHash and re-sends the
    // same image. Distinct from watchSyncMutex so an alarm force-sync and a bg
    // reconcile never block each other.
    private val watchBgMutex = Mutex()
    private var pendingSyncJob: Job? = null

    fun observeAlarms(): Flow<List<Alarm>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: Long): Alarm? = dao.getById(id)?.toDomain()

    suspend fun getAll(): List<Alarm> = dao.getAll().map { it.toDomain() }

    /**
     * Debounced watch reconcile — the single phone→watch convergence path.
     * Any alarm or settings mutation calls this; [WATCH_SYNC_DEBOUNCE_MS]
     * coalesces a burst of edits into one watch wake. Runs in [appScope]
     * so it survives the caller VM's cancellation.
     */
    @Synchronized
    fun scheduleWatchSync() {
        pendingSyncJob?.cancel()
        pendingSyncJob = appScope.launch {
            delay(WATCH_SYNC_DEBOUNCE_MS)
            syncWatchNow()
        }
    }

    /**
     * Push display settings + the full alarm list to the watch via the
     * reliable [WearBridgeService.forceSync] path (force-wake + transport
     * retry + hash precheck). Mutex-serialized so a debounced sync and the
     * manual Force-sync button never run two pushes at once.
     */
    suspend fun syncWatchNow(): ForceSyncResult = watchSyncMutex.withLock {
        val settings = settingsStore.snapshot()
        wearBridge.sendSettingsChanged(settings.use24Hour, settings.firstDayOfWeek)
        val result = wearBridge.forceSync { getAll() }
        Log.i(TAG, "syncWatchNow result=$result")
        result
    }

    suspend fun save(alarm: Alarm): Long {
        val now = System.currentTimeMillis()
        val isNew = alarm.id == 0L
        // An edit invalidates any active snooze — the user is reconfiguring,
        // so the previously-deferred fire time no longer makes sense.
        // Resolve default ringtone from Settings for new alarms with no URI.
        val resolved = applyDefaultRingtoneIfNeeded(alarm, isNew)
        // Editing clears transient runtime state: snooze deferral, skip-
        // next mark, and the consecutive-snooze counter all belong to a
        // previous fire cycle that's no longer authoritative.
        val stamped = resolved.copy(
            updatedAtEpoch = now,
            snoozedUntilEpoch = null,
            skipNextEpoch = null,
            consecutiveSnoozeCount = 0,
        )
        val newId = dao.upsert(stamped.toEntity())
        val saved = stamped.copy(id = if (isNew) newId else stamped.id)
        // Room first — autoGenerated id is needed for the cache write.
        // Process-kill window between the two is ~tens of ms; next
        // BOOT_COMPLETED reconcile rebuilds cache from Room.
        bfuCache.upsert(saved)
        if (saved.enabled) {
            scheduler.schedule(saved)
        } else {
            scheduler.cancel(saved.id)
        }
        scheduleWatchSync()
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
     * NonCancellable block; [rescheduleAlarm] then commits the remaining
     * scheduler side-effect under the same NonCancellable. Watch sync is
     * armed separately via [scheduleWatchSync] (debounced, survives on its
     * own scope).
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
        val stamped = resolved.copy(
            updatedAtEpoch = now,
            snoozedUntilEpoch = null,
            skipNextEpoch = null,
            consecutiveSnoozeCount = 0,
        )
        val newId = dao.upsert(stamped.toEntity())
        val saved = stamped.copy(id = if (isNew) newId else stamped.id)
        bfuCache.upsert(saved)
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
     * Upload the shared watch-side default background to the paired watch.
     * The picker writes `watch_bg_<DEFAULT_WATCH_BG_ID>.png` in
     * [Context.getCacheDir]; [com.kirkouski.gtwake.companion.wear.WatchBgTestEncoder]
     * derives the actual file to send (a hardware experiment over JPG/PNG/
     * BGRA formats — see that class). If the source PNG is missing (user
     * cleared the default or never picked one) we send the watch a
     * clearance envelope instead.
     */
    fun uploadDefaultWatchBackground() {
        // Same lock as the F4 reconcile path so a Settings-save upload and a
        // watch_screen-driven reconcile can't double-send the same bytes.
        Log.i(TAG, "uploadDefaultWatchBackground() requested")
        appScope.launch {
            Log.i(TAG, "uploadDefaultWatchBackground acquiring watchBgMutex")
            watchBgMutex.withLock {
                Log.i(TAG, "uploadDefaultWatchBackground lock acquired — uploading")
                uploadDefaultWatchBackgroundNow()
            }
        }
    }

    /**
     * Re-encode the CURRENT default-watch-bg source, name the `.bin`/raster
     * after a stable content hash (`bgd_<hash>.<ext>`), persist that hash as
     * [SettingsStore.lastUploadedWatchBgHash], and upload. Returns the hash on
     * success, or null when there is no source / encode failed. Suspending so
     * the [WearBridgeService] reconciler can drive it from a `watch_screen`
     * reply (F4 auto-restore) as well as the Settings-save path.
     *
     * The re-upload is always derived from the live source PNG — never a stale
     * cached `.bin` — so a watch that lost its image (reset/reinstall) gets the
     * image that matches the phone's current selection.
     */
    private suspend fun uploadDefaultWatchBackgroundNow(): String? {
        val srcPng = java.io.File(appContext.cacheDir, "watch_bg_${DEFAULT_WATCH_BG_ID}.png")
        if (!srcPng.exists()) {
            Log.i(TAG, "uploadDefaultWatchBackground — source png missing, sending cleared envelope")
            wearBridge.sendDefaultWatchBackgroundCleared()
            settingsStore.setLastUploadedWatchBgHash(null)
            return null
        }
        val encoded = com.kirkouski.gtwake.companion.wear.WatchBgTestEncoder.prepare(srcPng, appContext.cacheDir)
        if (encoded == null) {
            Log.w(TAG, "uploadDefaultWatchBackground — test encode failed, skipping")
            return null
        }
        val hash = watchBgContentHash(encoded)
        // Carry the hash in the Wear Engine file NAME (the bridge sends
        // bgFile.name as the message description). The watch parses it out and
        // reports it back in watch_screen.bg so the phone can tell whether the
        // watch already holds THIS image.
        val ext = encoded.extension.ifEmpty { "bin" }
        val bgFile = java.io.File(appContext.cacheDir, "bgd_$hash.$ext")
        encoded.copyTo(bgFile, overwrite = true)
        // Prune stale bgd_<otherhash>.* so the cache doesn't accumulate one file
        // per wallpaper change — only the current-hash artifact is ever needed.
        appContext.cacheDir
            .listFiles { f -> f.name.startsWith("bgd_") && f.name != bgFile.name }
            ?.forEach { it.delete() }
        val ok = wearBridge.uploadDefaultWatchBackground(bgFile)
        Log.i(TAG, "uploadDefaultWatchBackground ok=$ok file=${bgFile.name} size=${bgFile.length()} hash=$hash")
        if (ok) {
            settingsStore.setLastUploadedWatchBgHash(hash)
            // Record on the bridge (which owns connection lifecycle) that THIS
            // image was delivered (207) this connection, so the reconcile won't
            // re-send the same bytes on every sync.
            wearBridge.markBgDelivered(hash)
        }
        return if (ok) hash else null
    }

    /**
     * Reconcile the watch's reported default-bg marker (from `watch_screen.bg`)
     * against the phone's current default-watch-bg state. Registered on the
     * [WearBridgeService] by [GtAlarmApp]; invoked from the bridge when a
     * `watch_screen` reply lands (the bridge holds no AlarmRepository, so this
     * callback wiring keeps DI acyclic).
     *
     * - phone HAS a default bg AND (`reportedMarker` empty OR != last uploaded)
     *   → re-upload the CURRENT bg (auto-restore + never-send-mismatch).
     * - phone has NO default bg AND `reportedMarker` non-empty
     *   → tell the watch to clear (it's holding an image we no longer have).
     */
    suspend fun reconcileWatchBackground(reportedMarker: String) = watchBgMutex.withLock {
        // Re-read the snapshot INSIDE the lock so a serialized second reconcile
        // sees the hash the first one just persisted (and skips a duplicate send).
        val settings = settingsStore.snapshot()
        val hasBg = settings.defaultWatchBackgroundUri != null
        if (hasBg) {
            val lastHash = settings.lastUploadedWatchBgHash
            if (lastHash != null && wearBridge.bgAlreadyDeliveredThisConnection(lastHash)) {
                // The watch already sent its bg_received ACK for this exact image
                // this connection — that is RELIABLE proof it stored the file.
                // The watch_screen `bg` report is NOT reliable (it often stays ''
                // across the watch's terminate/relaunch even after persisting),
                // so trust the ack and skip the redundant re-upload. The guard
                // resets on disconnect, so a genuine reset still restores once.
                Log.d(TAG, "reconcileWatchBackground already acked $lastHash this connection — skip")
            } else if (reportedMarker != lastHash) {
                // Re-upload when the watch hasn't confirmed the current hash —
                // covers a dropped first upload and a reset/reconnect.
                Log.i(
                    TAG,
                    "reconcileWatchBackground re-upload (reported='$reportedMarker' last='$lastHash')",
                )
                uploadDefaultWatchBackgroundNow()
            } else {
                Log.d(TAG, "reconcileWatchBackground in sync (hash=$reportedMarker)")
            }
        } else if (reportedMarker.isNotEmpty()) {
            Log.i(TAG, "reconcileWatchBackground clear (watch holds '$reportedMarker', phone has none)")
            wearBridge.sendDefaultWatchBackgroundCleared()
            settingsStore.setLastUploadedWatchBgHash(null)
        }
    }

    /**
     * Stable short content hash of the encoded bg file: first 8 hex chars of
     * its SHA-256. Used as the wire marker carried in the file name so both
     * sides can detect "watch holds a different image than the phone sent".
     */
    private fun watchBgContentHash(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(file.readBytes())
        val sb = StringBuilder(WATCH_BG_HASH_LEN)
        var i = 0
        while (i < WATCH_BG_HASH_BYTES) {
            val v = digest[i].toInt() and 0xFF
            sb.append(HEX_CHARS[v shr 4])
            sb.append(HEX_CHARS[v and 0xF])
            i++
        }
        return sb.toString()
    }

    /**
     * Clear the shared watch default background. Driven directly off the
     * null URI from Settings — NOT inferred from a missing cache file, which
     * silently re-uploaded the stale crop (the clear-never-clears bug): the
     * picker leaves `watch_bg_<id>.png` on disk after the user clears, so the
     * file-presence check in [uploadDefaultWatchBackground] always re-sent it.
     * Here we delete the cached crop and every derived artifact so nothing can
     * resurrect it, then tell the watch to drop its copy.
     */
    fun clearDefaultWatchBackground() {
        appScope.launch {
            val cache = appContext.cacheDir
            val artifacts = listOf(
                java.io.File(cache, "watch_bg_${DEFAULT_WATCH_BG_ID}.png"),
                java.io.File(cache, "watch_bg_${DEFAULT_WATCH_BG_ID}.bin"),
                com.kirkouski.gtwake.companion.wear.WatchBgTestEncoder.outputFile(cache),
            )
            val deleted = artifacts.count { it.exists() && it.delete() }
            // Also drop every bgd_<hash>.* upload artifact so nothing can
            // resurrect the image (the KDoc's "every derived artifact" promise).
            val bgdDeleted = cache.listFiles { f -> f.name.startsWith("bgd_") }
                ?.count { it.delete() } ?: 0
            Log.i(
                TAG,
                "clearDefaultWatchBackground deleted=$deleted/${artifacts.size} bgd=$bgdDeleted, " +
                    "sending cleared envelope",
            )
            wearBridge.sendDefaultWatchBackgroundCleared()
            settingsStore.setLastUploadedWatchBgHash(null)
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
        bfuCache.upsert(alarm)
        if (enabled) scheduler.schedule(alarm) else scheduler.cancel(id)
        scheduleWatchSync()
        refreshWidgets()
        Log.i(TAG, "setEnabled id=$id enabled=$enabled stamp=$now")
    }

    suspend fun setEnabledLocalOnly(id: Long, enabled: Boolean) {
        val now = System.currentTimeMillis()
        dao.setEnabledStamped(id, enabled, now)
        if (!enabled) dao.setSnoozedUntil(id, null)
        val alarm = dao.getById(id)?.toDomain() ?: return
        bfuCache.upsert(alarm)
        if (enabled) scheduler.schedule(alarm) else scheduler.cancel(id)
        refreshWidgets()
        Log.i(TAG, "setEnabledLocalOnly id=$id enabled=$enabled stamp=$now")
    }

    suspend fun delete(id: Long) {
        scheduler.cancel(id)
        val now = System.currentTimeMillis()
        dao.deleteById(id)
        bfuCache.remove(id)
        tombstones.add(id, now, now)
        scheduleWatchSync()
        refreshWidgets()
        Log.i(TAG, "delete id=$id stamp=$now")
    }

    // Snooze using the alarm's own configured duration. Override is for
    // tests / one-off explicit-minutes callers; production callers should
    // pass null so the per-alarm value is honored.
    // reason: ReturnCount=4 — three guards (missing alarm, snooze-disabled-or-
    // capped, minutes<=DISABLED) each surface a distinct refusal reason in
    // logs + a final success return; collapsing them would smear the diagnostic.
    @Suppress("ReturnCount")
    suspend fun snooze(id: Long, minutesOverride: Int? = null): Long? {
        val alarm = dao.getById(id)?.toDomain() ?: return null
        if (!alarm.isSnoozeEnabled && minutesOverride == null) {
            // Refuses both snoozeMinutes==0 AND the consecutive-cap-hit case.
            Log.w(TAG, "snooze id=$id refused — disabled or max-count reached")
            return null
        }
        val minutes = minutesOverride ?: alarm.snoozeMinutes
        if (minutes <= Alarm.SNOOZE_DISABLED) {
            Log.w(TAG, "snooze id=$id refused — minutes=$minutes")
            return null
        }
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        val newCount = alarm.consecutiveSnoozeCount + 1
        scheduler.scheduleAt(alarm, trigger)
        dao.setSnoozedUntil(id, trigger)
        dao.setConsecutiveSnoozeCount(id, newCount)
        // BFU mirror must track the counter too — pre-unlock fire after
        // a post-unlock snooze reads BFU; without this the pre-unlock
        // cap evaluation uses a stale count and the user blows past the
        // limit (Tier 2D spec line 499).
        bfuCache.upsert(alarm.copy(consecutiveSnoozeCount = newCount))
        scheduleWatchSync()
        refreshWidgets()
        Log.i(TAG, "snooze id=$id +${minutes}min trigger=$trigger count=$newCount")
        return trigger
    }

    /**
     * Mark the next firing as skipped (swipe-left). Computes the current
     * next-trigger and stores it as `skipNextEpoch`; NextTriggerCalculator
     * rolls past it. Reschedules AlarmManager so the alarm fires at the
     * occurrence AFTER the skipped one. Recurring alarms only — one-shots
     * are simpler to just disable.
     */
    suspend fun skipNext(id: Long): Long? {
        val alarm = dao.getById(id)?.toDomain() ?: return null
        if (alarm.daysOfWeek == 0) return null
        // If currently snoozed, drop the snooze override first. Without
        // this, NextTriggerCalculator returns snoozedUntilEpoch and we'd
        // "skip the snooze" instead of "skip the next clock occurrence" —
        // the user-facing intent of the swipe gesture. Treat skip-next on
        // a snoozed alarm as "cancel the snooze AND skip the next clock
        // recurrence".
        val base = if (alarm.snoozedUntilEpoch != null) {
            dao.setSnoozedUntil(id, null)
            alarm.copy(snoozedUntilEpoch = null)
        } else {
            alarm
        }
        val skipped = NextTriggerCalculator.nextTriggerEpochMillis(base)
        val updated = base.copy(
            skipNextEpoch = skipped,
            updatedAtEpoch = System.currentTimeMillis(),
        )
        dao.upsert(updated.toEntity())
        bfuCache.upsert(updated)
        scheduler.schedule(updated)
        scheduleWatchSync()
        refreshWidgets()
        Log.i(TAG, "skipNext id=$id skippedEpoch=$skipped clearedSnooze=${alarm.snoozedUntilEpoch != null}")
        return skipped
    }

    /**
     * Toggle the right-to-left recurring-row gesture: mark the next
     * occurrence skipped, or clear a future skip if one is already active.
     */
    suspend fun toggleSkipNext(id: Long): Long? {
        val alarm = dao.getById(id)?.toDomain() ?: return null
        if (alarm.daysOfWeek == 0) return null
        val skip = alarm.skipNextEpoch
        return if (skip != null && skip > System.currentTimeMillis()) {
            clearSkipNext(alarm)
            null
        } else {
            skipNext(id)
        }
    }

    private suspend fun clearSkipNext(alarm: Alarm) {
        val updated = alarm.copy(
            skipNextEpoch = null,
            updatedAtEpoch = System.currentTimeMillis(),
        )
        dao.upsert(updated.toEntity())
        bfuCache.upsert(updated)
        scheduler.schedule(updated)
        scheduleWatchSync()
        refreshWidgets()
        Log.i(TAG, "clearSkipNext id=${alarm.id}")
    }

    /** Reset consecutive-snooze counter on dismiss/fire so the next ring
     *  cycle starts from zero. Mirrors the change into BFU so the next
     *  pre-unlock fire reads the reset value. */
    suspend fun resetSnoozeCounter(id: Long) {
        dao.setConsecutiveSnoozeCount(id, 0)
        val alarm = dao.getById(id)?.toDomain() ?: return
        bfuCache.upsert(alarm.copy(consecutiveSnoozeCount = 0))
    }

    /**
     * Drop `skipNextEpoch` if the skipped instant is in the past — the skip
     * has been honoured (NextTriggerCalculator advanced past it; the alarm
     * now firing is the post-skip occurrence). Per `docs/acceptance-criteria.md`
     * Tier 2C: "advances past it when the skipped instant arrives, **then
     * clears the field**". Without this, the row holds a permanent stale
     * epoch that the calculator just keeps stepping past — functionally
     * harmless (the equality check never matches a future trigger) but it
     * violates the spec letter and leaks ambiguous state into sync/hash.
     *
     * Called from `AlarmRingService.handleRing` after the alarm row is
     * resolved for the fire. No-op when `skipNextEpoch` is null or still
     * in the future.
     */
    suspend fun consumePastSkip(id: Long) {
        val alarm = dao.getById(id)?.toDomain() ?: return
        val skip = alarm.skipNextEpoch ?: return
        if (skip > System.currentTimeMillis()) return
        val updated = alarm.copy(
            skipNextEpoch = null,
            updatedAtEpoch = System.currentTimeMillis(),
        )
        dao.upsert(updated.toEntity())
        bfuCache.upsert(updated)
        // No scheduleWatchSync() here — this runs inside the alarm-fire
        // path (handleRing); a sync_replace during ring is wasteful, and
        // the cleared skip will ride the next user-driven sync. Note the
        // watch's incomingHandler armDrain IS gated by _ringActive so
        // there's no actual yank, but burning radio while the user is
        // mid-ring still has no upside.
        Log.i(TAG, "consumePastSkip id=$id cleared elapsedSkip=$skip")
    }

    suspend fun snoozeAt(id: Long, triggerEpoch: Long, fromPeer: Boolean = true): Long? {
        val alarm = dao.getById(id)?.toDomain() ?: return null
        val newCount = alarm.consecutiveSnoozeCount + 1
        scheduler.scheduleAt(alarm, triggerEpoch)
        dao.setSnoozedUntil(id, triggerEpoch)
        // Counter increments on EVERY snooze, local or peer-driven — per
        // spec line 499. The watch surfaces the cap via the next fire's
        // `alarm_fired.snoozeAllowed` (built from `alarm.isSnoozeEnabled`),
        // so to keep the cap honest across both surfaces, the same counter
        // backs both. Peer-driven snoozes that race the cap evaluate the
        // refreshed value on the NEXT fire — watch sees Snooze disabled and
        // can't push another envelope through.
        dao.setConsecutiveSnoozeCount(id, newCount)
        bfuCache.upsert(alarm.copy(consecutiveSnoozeCount = newCount))
        refreshWidgets()
        Log.i(TAG, "snoozeAt id=$id trigger=$triggerEpoch fromPeer=$fromPeer count=$newCount")
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
        bfuCache.replaceAll(alarms)
    }

    /** Pre-unlock re-arm — recurring alarms only; one-shots are handled at
     *  post-unlock reconcile via [intendedFireForOneShot]. */
    fun rescheduleFromBfu() {
        // relative alarms invariant-require daysOfWeek==0 (Alarm.init),
        // so the filter catches both.
        val cached = bfuCache.getAll()
        val recurring = cached.filter { it.daysOfWeek != 0 }
        Log.i(
            TAG,
            "rescheduleFromBfu cached=${cached.size} recurringRearmed=${recurring.size} " +
                "skippedOneShots=${cached.size - recurring.size}",
        )
        scheduler.rescheduleAll(recurring)
    }

    /** Reconcile from Room post-unlock; one-shots whose intended fire predates
     *  boot are notified + disabled/deleted (recurring excluded — they refire
     *  tomorrow on their own). Pre-unlock lock-screen dismissals (BFU
     *  pendingDismissals) are applied first so they don't get re-classified
     *  as missed-during-downtime. */
    suspend fun rescheduleAllOnBoot() {
        applyPendingDismissals()
        applyPendingSnoozeBumps()
        val now = System.currentTimeMillis()
        // Clamp: wall-clock can move backwards (NTP/manual); a future
        // bootCompleteAt would misclassify every past-due alarm as missed.
        val bootCompleteAt = (now - SystemClock.elapsedRealtime()).coerceAtMost(now)
        val alarms = getAll().filter { it.enabled }
        val missed = mutableListOf<Alarm>()
        val keep = mutableListOf<Alarm>()
        alarms.forEach { alarm ->
            val intendedFire = intendedFireForOneShot(alarm)
            if (intendedFire != null && intendedFire < bootCompleteAt) {
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
        bfuCache.replaceAll(keep)
        missed.forEach { handleMissedDuringDowntime(it) }
    }

    /** Drain BFU pendingDismissals and apply the same action the live ring
     *  path would (`AlarmRingService.dismissAction`): DELETE for relative or
     *  self-destruct, DISABLE for absolute non-self-destruct, KEEP for
     *  recurring (no-op — recurring fire-and-dismiss leaves the row armed
     *  for tomorrow). Uses Local-Only variants — the watch already saw the
     *  dismiss envelope at lock-screen tap time. */
    private suspend fun applyPendingDismissals() {
        val pending = bfuCache.drainPendingDismissals()
        if (pending.isEmpty()) return
        Log.i(TAG, "applyPendingDismissals count=${pending.size}")
        pending.forEach { id ->
            val alarm = dao.getById(id)?.toDomain() ?: return@forEach
            when (com.kirkouski.gtwake.companion.ring.AlarmRingService.dismissAction(alarm)) {
                com.kirkouski.gtwake.companion.ring.AlarmRingService.DismissAction.DELETE -> delete(id)
                com.kirkouski.gtwake.companion.ring.AlarmRingService.DismissAction.DISABLE ->
                    setEnabledLocalOnly(id, false)
                com.kirkouski.gtwake.companion.ring.AlarmRingService.DismissAction.KEEP -> Unit
            }
        }
    }

    /** Drain BFU pendingSnoozeBumps and apply each to Room. Pre-unlock
     *  snoozes can't bump `consecutiveSnoozeCount` directly (Room locked);
     *  we record them in BFU and reconcile here so the max-snooze cap
     *  applies uniformly across the unlock boundary. */
    private suspend fun applyPendingSnoozeBumps() {
        val bumps = bfuCache.drainPendingSnoozeBumps()
        if (bumps.isEmpty()) return
        Log.i(TAG, "applyPendingSnoozeBumps count=${bumps.size}")
        bumps.forEach { (id, bump) ->
            val alarm = dao.getById(id)?.toDomain() ?: return@forEach
            dao.setConsecutiveSnoozeCount(id, alarm.consecutiveSnoozeCount + bump)
        }
    }

    /** Intended fire as scheduled at last user action (edit/snooze).
     *  null for recurring (they refire tomorrow on their own). */
    private fun intendedFireForOneShot(alarm: Alarm): Long? {
        if (alarm.isRelative) return alarm.computedFireEpoch()
        if (alarm.daysOfWeek != 0) return null
        // As-of edit time — recomputing now would silently roll a missed
        // 7am to tomorrow.
        val editAt = java.time.ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(alarm.updatedAtEpoch),
            java.time.ZoneId.systemDefault(),
        )
        return NextTriggerCalculator.nextTriggerEpochMillis(alarm, editAt)
    }

    private suspend fun handleMissedDuringDowntime(alarm: Alarm) {
        // labelLen not label — alarm labels can be PII.
        val target = if (alarm.isRelative) alarm.computedFireEpoch() else 0L
        Log.i(
            TAG,
            "missedDuringDowntime id=${alarm.id} target=$target labelLen=${alarm.label.length}",
        )
        AlarmNotifications.postMissedNotification(appContext, alarm)
        // Delegate to the SAME decision function the live ring path and
        // applyPendingDismissals use, so all three dismiss routes agree.
        // Previously this branched on `alarm.isRelative || alarm.selfDestruct`,
        // which deleted every missed timer regardless of the flag — silently
        // overriding "Don't delete after firing" whenever the fire was missed
        // during downtime rather than dismissed live.
        when (com.kirkouski.gtwake.companion.ring.AlarmRingService.dismissAction(alarm)) {
            com.kirkouski.gtwake.companion.ring.AlarmRingService.DismissAction.DELETE ->
                delete(alarm.id)
            com.kirkouski.gtwake.companion.ring.AlarmRingService.DismissAction.DISABLE ->
                setEnabled(alarm.id, false)
            // Unreachable in practice — callers filter to enabled one-shots —
            // but KEEP is the correct no-op for a recurring alarm.
            com.kirkouski.gtwake.companion.ring.AlarmRingService.DismissAction.KEEP -> Unit
        }
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
        bfuCache.upsert(draft.copy(id = newId))
        scheduleWatchSync()
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
        // Debounce before a mutation triggers the watch sync — short
        // enough to feel instant, long enough to coalesce an edit burst.
        const val WATCH_SYNC_DEBOUNCE_MS = 800L
        // Sentinel "alarm id" used as the cache-file key for the shared
        // watch-default background (`watch_bg_-1.png` / `watch_bg_-1.bin`).
        // Matches the constant the Settings picker passes to
        // WatchBackgroundPickerDialog. Room ids are auto-generated positive
        // longs, so -1 can never collide with a real alarm.
        const val DEFAULT_WATCH_BG_ID = -1L

        // Watch-bg content-hash marker: first 8 hex chars (4 bytes) of the
        // encoded file's SHA-256. Short enough to ride in the Wear Engine
        // file name + the watch_screen reply, stable across re-encodes of
        // the same source.
        const val WATCH_BG_HASH_BYTES = 4
        const val WATCH_BG_HASH_LEN = WATCH_BG_HASH_BYTES * 2
        val HEX_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
        )
    }
}
