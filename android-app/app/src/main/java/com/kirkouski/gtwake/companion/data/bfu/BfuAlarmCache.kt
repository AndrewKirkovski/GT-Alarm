package com.kirkouski.gtwake.companion.data.bfu

import android.util.Log
import com.kirkouski.gtwake.companion.di.BfuCacheFile
import com.kirkouski.gtwake.companion.domain.Alarm
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Pre-unlock-readable cache of enabled alarms in device-protected storage. */
@Singleton
class BfuAlarmCache @Inject constructor(
    @param:BfuCacheFile private val file: File,
) {
    /** In-memory state container. Alarms = enabled-only mirror of Room.
     *
     *  pendingDismissals = ids the user dismissed on the lock screen
     *  pre-unlock; drained by AlarmRepository at first post-unlock reconcile.
     *
     *  pendingSnoozeBumps = ids → count of pre-unlock snoozes since unlock.
     *  Room is locked pre-unlock so `consecutiveSnoozeCount` cannot be
     *  bumped at the source; we record the bumps here and drain them on
     *  post-unlock reconcile. Without this, a chronic lockscreen-snoozer
     *  would NEVER trip the max-snooze cap until the first unlock — the
     *  spec says the cap applies uniformly.
     */
    data class BfuState(
        val alarms: List<Alarm>,
        val pendingDismissals: Set<Long>,
        val pendingSnoozeBumps: Map<Long, Int>,
    ) {
        companion object {
            val EMPTY = BfuState(emptyList(), emptySet(), emptyMap())
        }
    }

    private val mutex = Mutex()

    // Lazy first-load so the @Singleton graph build doesn't block on disk I/O.
    private val stateRef = AtomicReference<BfuState?>(null)

    /** Lock-free read; safe from main thread inside `runBlocking`.
     *
     *  Synthesises pending pre-unlock snooze bumps into each alarm's
     *  `consecutiveSnoozeCount` at READ time so callers (e.g. the pre-
     *  unlock ring path) see the cap-correct count without ever needing
     *  to consult the pending map. Persistence is unchanged — the
     *  serialized row still holds the post-unlock-reconciled count; the
     *  bumps live in the map until the next reconcile pass applies them
     *  to Room.
     */
    fun getAll(): List<Alarm> {
        val state = loaded()
        if (state.pendingSnoozeBumps.isEmpty()) return state.alarms
        return state.alarms.map { a ->
            val bump = state.pendingSnoozeBumps[a.id] ?: 0
            if (bump > 0) a.copy(consecutiveSnoozeCount = a.consecutiveSnoozeCount + bump) else a
        }.let { java.util.Collections.unmodifiableList(it) }
    }

    /** Ids the user dismissed pre-unlock — drained by post-unlock reconcile. */
    fun getPendingDismissals(): Set<Long> = loaded().pendingDismissals

    private fun loaded(): BfuState {
        stateRef.get()?.let { return it }
        val fresh = readFromDisk()
        // CAS so concurrent first-loads converge on one survivor.
        return if (stateRef.compareAndSet(null, fresh)) fresh else stateRef.get() ?: fresh
    }

    suspend fun replaceAll(alarms: List<Alarm>) = mutex.withLock {
        val enabled = alarms.filter { it.enabled }.immutable()
        val next = loaded().copy(alarms = enabled)
        stateRef.set(next)
        writeToDisk(next)
    }

    suspend fun upsert(alarm: Alarm) = mutex.withLock {
        val current = loaded()
        val list = current.alarms.toMutableList()
        val idx = list.indexOfFirst { it.id == alarm.id }
        if (alarm.enabled) {
            if (idx >= 0) list[idx] = alarm else list.add(alarm)
        } else if (idx >= 0) {
            list.removeAt(idx)
        }
        val next = current.copy(alarms = list.immutable())
        stateRef.set(next)
        writeToDisk(next)
    }

    suspend fun remove(id: Long) = mutex.withLock {
        val current = loaded()
        val next = current.copy(
            // Implicit dismiss-of-deleted: pending entries for a deleted id
            // are stale by definition; drop them so reconcile doesn't try
            // to dismissAction a missing row.
            alarms = current.alarms.filter { it.id != id }.immutable(),
            pendingDismissals = current.pendingDismissals - id,
            pendingSnoozeBumps = current.pendingSnoozeBumps - id,
        )
        stateRef.set(next)
        writeToDisk(next)
    }

    // Publish defends against caller downcast-and-mutate.
    private fun List<Alarm>.immutable(): List<Alarm> =
        java.util.Collections.unmodifiableList(this.toList())

    /** Record a pre-unlock lock-screen dismiss. Drained at post-unlock reconcile. */
    suspend fun markDismissed(id: Long) = mutex.withLock {
        val current = loaded()
        if (id in current.pendingDismissals) return@withLock
        val next = current.copy(pendingDismissals = current.pendingDismissals + id)
        stateRef.set(next)
        writeToDisk(next)
    }

    /** Returns + clears the pending-dismissal set in one atomic step. */
    suspend fun drainPendingDismissals(): Set<Long> = mutex.withLock {
        val current = loaded()
        if (current.pendingDismissals.isEmpty()) return@withLock emptySet()
        val drained = current.pendingDismissals
        val next = current.copy(pendingDismissals = emptySet())
        stateRef.set(next)
        writeToDisk(next)
        drained
    }

    /** Record a pre-unlock lockscreen snooze of [id]. The counter is
     *  incremented in-place; we use a count so a user who hits Snooze N
     *  times pre-unlock has those bumps applied as a single +N at unlock
     *  (one Room write instead of N) and cannot escape the cap by
     *  hammering snooze faster than the reconcile flush. */
    suspend fun markPendingSnoozeBump(id: Long) = mutex.withLock {
        val current = loaded()
        val prev = current.pendingSnoozeBumps[id] ?: 0
        val next = current.copy(
            pendingSnoozeBumps = current.pendingSnoozeBumps + (id to prev + 1),
        )
        stateRef.set(next)
        writeToDisk(next)
    }

    /** Returns + clears the pending-snooze-bump map in one atomic step. */
    suspend fun drainPendingSnoozeBumps(): Map<Long, Int> = mutex.withLock {
        val current = loaded()
        if (current.pendingSnoozeBumps.isEmpty()) return@withLock emptyMap()
        val drained = current.pendingSnoozeBumps
        val next = current.copy(pendingSnoozeBumps = emptyMap())
        stateRef.set(next)
        writeToDisk(next)
        drained
    }

    private fun readFromDisk(): BfuState {
        if (!file.exists()) return BfuState.EMPTY
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val version = root.optInt("v", -1)
            if (version != SCHEMA_VERSION) {
                Log.w(TAG, "schema v=$version, expected $SCHEMA_VERSION — dropping cache")
                return@runCatching BfuState.EMPTY
            }
            val alarmsArr = root.getJSONArray("alarms")
            val alarms = (0 until alarmsArr.length())
                .map { i -> parseAlarm(alarmsArr.getJSONObject(i)) }
                .immutable()
            val pendingArr = root.optJSONArray("pendingDismissals")
            val pending = if (pendingArr == null) emptySet()
            else (0 until pendingArr.length()).map { pendingArr.getLong(it) }.toSet()
            val snoozeBumpsObj = root.optJSONObject("pendingSnoozeBumps")
            val snoozeBumps = if (snoozeBumpsObj == null) emptyMap()
            else snoozeBumpsObj.keys().asSequence().associate { key ->
                key.toLong() to snoozeBumpsObj.getInt(key)
            }
            BfuState(alarms, pending, snoozeBumps)
        }.getOrElse { e ->
            Log.w(TAG, "read failed (${e.javaClass.simpleName}: ${e.message}) — returning empty")
            BfuState.EMPTY
        }
    }

    // Atomic temp + rename + fsync. On power loss between write and rename,
    // the previous good file is preserved. Avoids android.util.AtomicFile
    // because that class throws "not mocked" in JVM unit tests.
    private fun writeToDisk(state: BfuState) {
        val alarms = JSONArray().apply { state.alarms.forEach { put(serialiseAlarm(it)) } }
        val pending = JSONArray().apply { state.pendingDismissals.forEach { put(it) } }
        val snoozeBumps = JSONObject().apply {
            state.pendingSnoozeBumps.forEach { (id, count) -> put(id.toString(), count) }
        }
        val root = JSONObject().apply {
            put("v", SCHEMA_VERSION)
            put("alarms", alarms)
            put("pendingDismissals", pending)
            put("pendingSnoozeBumps", snoozeBumps)
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            // Files.move with REPLACE_EXISTING — atomic on Linux/Android via
            // rename(2); cross-platform-safe (File.renameTo refuses to
            // overwrite on Windows, which breaks JVM unit tests).
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure { e ->
            Log.w(TAG, "write failed (${e.javaClass.simpleName}: ${e.message}) — cache out of sync")
            tmp.delete()
        }
    }

    private fun serialiseAlarm(a: Alarm): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("hour", a.hour)
        put("minute", a.minute)
        put("daysOfWeek", a.daysOfWeek)
        put("enabled", a.enabled)
        put("isVibrationOnly", a.isVibrationOnly)
        put("snoozeMinutes", a.snoozeMinutes)
        put("updatedAtEpoch", a.updatedAtEpoch)
        put("relativeMinutes", a.relativeMinutes ?: JSONObject.NULL)
        put("selfDestruct", a.selfDestruct)
        put("vibrationPattern", a.vibrationPattern.name)
        put("phoneVibrationEnabled", a.phoneVibrationEnabled)
        put("watchVibrationEnabled", a.watchVibrationEnabled)
        put("volumeRampSeconds", a.volumeRampSeconds)
        put("maxSnoozeCount", a.maxSnoozeCount)
        put("consecutiveSnoozeCount", a.consecutiveSnoozeCount)
        put("skipNextEpoch", a.skipNextEpoch ?: JSONObject.NULL)
    }

    private fun parseAlarm(o: JSONObject): Alarm = Alarm(
        id = o.getLong("id"),
        hour = o.getInt("hour"),
        minute = o.getInt("minute"),
        daysOfWeek = o.getInt("daysOfWeek"),
        enabled = o.getBoolean("enabled"),
        isVibrationOnly = o.getBoolean("isVibrationOnly"),
        snoozeMinutes = o.getInt("snoozeMinutes"),
        updatedAtEpoch = o.getLong("updatedAtEpoch"),
        relativeMinutes = if (o.isNull("relativeMinutes")) null else o.getInt("relativeMinutes"),
        selfDestruct = o.getBoolean("selfDestruct"),
        vibrationPattern = com.kirkouski.gtwake.companion.domain.VibrationPattern.fromWireName(
            if (o.has("vibrationPattern") && !o.isNull("vibrationPattern")) {
                o.getString("vibrationPattern")
            } else {
                null
            },
        ),
        phoneVibrationEnabled = o.optBoolean("phoneVibrationEnabled", true),
        watchVibrationEnabled = o.optBoolean("watchVibrationEnabled", true),
        volumeRampSeconds = o.optInt("volumeRampSeconds", 0),
        maxSnoozeCount = o.optInt("maxSnoozeCount", Alarm.MAX_SNOOZE_COUNT_UNLIMITED),
        consecutiveSnoozeCount = o.optInt("consecutiveSnoozeCount", 0),
        skipNextEpoch = if (o.isNull("skipNextEpoch")) null else o.optLong("skipNextEpoch"),
    )

    private companion object {
        const val TAG = "BfuAlarmCache"
        // v3: added vibrationPattern, volumeRampSeconds, maxSnoozeCount,
        // consecutiveSnoozeCount, skipNextEpoch (Tier 1+2 fields). Older
        // v2 caches are dropped on first read post-upgrade — a single
        // missed alarm at upgrade time is acceptable per the
        // no_migration_until_release policy.
        // v4 (1.0.6): added phoneVibrationEnabled / watchVibrationEnabled.
        // Bumped (not relying on the optBoolean default) BECAUSE the Room v9→10
        // migration sets legacy vibrationPattern==OFF alarms to BOTH enables
        // false — but a stale v3 cache lacks those keys and parseAlarm() would
        // default them to true, silently un-silencing a migrated alarm if it
        // fires pre-unlock before the boot reconcile rebuilds the cache. The
        // version bump drops the stale cache so it's rebuilt from migrated rows.
        const val SCHEMA_VERSION = 4
    }
}
