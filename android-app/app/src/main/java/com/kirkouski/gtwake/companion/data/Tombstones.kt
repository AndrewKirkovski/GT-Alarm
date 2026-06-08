package com.kirkouski.gtwake.companion.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent ring buffer of recently deleted alarm IDs with their LWW
 * timestamps. Used to suppress resurrection when a stale `alarm_update`
 * arrives from the peer device after a local delete.
 *
 * Capacity: [MAX_ENTRIES] entries; retention: [RETENTION_MS] from
 * `updatedAtEpoch`. Older entries are pruned on every read/write.
 *
 * Backed by SharedPreferences (single-key JSON blob) — small enough that
 * DataStore would be overkill and doesn't justify the proto-buf migration.
 */
@Singleton
class Tombstones @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    data class Entry(val id: Long, val updatedAtEpoch: Long)

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    // Synchronizes the read-modify-write triplet against concurrent callers.
    // SharedPreferences is its own write-side serialized but read+write here
    // is NOT atomic: two `add` coroutines could each readRaw, both compute
    // pruneAndAdd from the same baseline, and the later write would clobber
    // the earlier one — losing a tombstone and letting a peer resurrect a
    // deleted alarm. The intrinsic lock is fine because all ops are short
    // (small JSON, in-memory transforms).
    private val lock = Any()

    fun add(id: Long, updatedAtEpoch: Long, now: Long = System.currentTimeMillis()) {
        // Defensive: a stamp <= 0 would auto-prune on the next read (cutoff is
        // now - 7d, so 0 < cutoff for any wall-clock now > epoch+7d), silently
        // losing the tombstone and allowing peer resurrection. Reject loudly
        // so any future receiver path that mistakenly forwards a 0-stamp
        // delete from the watch fails fast at this boundary.
        require(updatedAtEpoch > 0L) { "Tombstones.add: updatedAtEpoch must be > 0 (got $updatedAtEpoch)" }
        synchronized(lock) {
            val entries = pruneAndAdd(readRaw(), Entry(id, updatedAtEpoch), now)
            write(entries)
        }
    }

    fun all(now: Long = System.currentTimeMillis()): List<Entry> = synchronized(lock) {
        val raw = readRaw()
        val pruned = prune(raw, now)
        if (pruned.size != raw.size) write(pruned)
        pruned
    }

    fun isTombstoned(id: Long, incomingEpoch: Long, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) { isTombstoned(readPruned(now), id, incomingEpoch) }

    private fun readPruned(now: Long): List<Entry> = prune(readRaw(), now)

    private fun readRaw(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(Entry(obj.getLong("id"), obj.getLong("updatedAtEpoch")))
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun write(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(JSONObject().apply {
                put("id", entry.id)
                put("updatedAtEpoch", entry.updatedAtEpoch)
            })
        }
        prefs.edit { putString(KEY_ENTRIES, arr.toString()) }
    }

    companion object {
        private const val PREFS_FILE = "tombstones_v1"
        private const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 256
        const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L // 7 days

        // Pure helpers — extracted for unit testing without Android Context.
        fun prune(entries: List<Entry>, now: Long): List<Entry> {
            val cutoff = now - RETENTION_MS
            return entries.filter { it.updatedAtEpoch >= cutoff }
        }

        fun pruneAndAdd(existing: List<Entry>, incoming: Entry, now: Long): List<Entry> {
            val pruned = prune(existing, now).filter { it.id != incoming.id }.toMutableList()
            pruned.add(incoming)
            return if (pruned.size > MAX_ENTRIES) {
                pruned.subList(pruned.size - MAX_ENTRIES, pruned.size).toList()
            } else {
                pruned
            }
        }

        fun isTombstoned(entries: List<Entry>, id: Long, incomingEpoch: Long): Boolean {
            val entry = entries.firstOrNull { it.id == id } ?: return false
            // Tombstone wins iff its stamp is >= incoming (LWW with ties to local).
            return entry.updatedAtEpoch >= incomingEpoch
        }
    }
}
