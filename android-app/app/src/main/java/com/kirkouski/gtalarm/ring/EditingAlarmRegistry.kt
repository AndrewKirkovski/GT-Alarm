package com.kirkouski.gtalarm.ring

import java.util.Collections

/**
 * Process-singleton set of alarm IDs currently being edited on the AlarmEditScreen.
 *
 * Purpose: prevent an alarm from ringing while its own edit screen is open.
 * Without this guard, an "in 1 minute" relative alarm being edited would fire
 * on the original schedule even after the user has navigated into the edit
 * screen and started changing the time — interrupting their edit with a
 * full-screen alarm UI. The user's intent is "I'm shaping this alarm; don't
 * fire it until I'm done."
 *
 * Contract:
 *   - AlarmEditViewModel calls [setEditing] in `load()` (existing alarm) and
 *     in `applyLocal` once a new draft is assigned a row id.
 *   - AlarmEditViewModel calls [clearEditing] in `flushPendingToWatch()`,
 *     `delete()`, `discardNewDraft()`, and `onCleared()`. flushPendingToWatch
 *     is the central exit hook (every back/save/X path routes through it).
 *   - AlarmRingService.handleRing calls [isEditing] and bails (without
 *     ringing) if true. The reschedule path on edit-screen exit kicks the
 *     alarm back into the queue if its computed next-fire is still in the
 *     future, or fires it immediately if it has passed.
 *
 * Singleton lifetime is fine for our needs: a process-death wipes the set,
 * which is correct (no ViewModels are alive, no edit screens are open).
 */
object EditingAlarmRegistry {
    private val ids: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    fun setEditing(id: Long) {
        if (id <= 0L) return
        ids.add(id)
    }

    fun clearEditing(id: Long) {
        if (id <= 0L) return
        ids.remove(id)
    }

    fun isEditing(id: Long): Boolean = ids.contains(id)
}
