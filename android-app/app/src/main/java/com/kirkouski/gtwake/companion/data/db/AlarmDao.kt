package com.kirkouski.gtwake.companion.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms")
    suspend fun getAll(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AlarmEntity?

    @Upsert
    suspend fun upsert(entity: AlarmEntity): Long

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE alarms SET enabled = :enabled, updatedAtEpoch = :stamp WHERE id = :id")
    suspend fun setEnabledStamped(id: Long, enabled: Boolean, stamp: Long)

    /** Disable and clear any snooze in ONE statement, so a disable is all-or-nothing. */
    @Query("UPDATE alarms SET enabled = 0, snoozedUntilEpoch = NULL, updatedAtEpoch = :stamp WHERE id = :id")
    suspend fun disableStamped(id: Long, stamp: Long)

    @Query("UPDATE alarms SET snoozedUntilEpoch = :until WHERE id = :id")
    suspend fun setSnoozedUntil(id: Long, until: Long?)

    @Query("UPDATE alarms SET consecutiveSnoozeCount = :count WHERE id = :id")
    suspend fun setConsecutiveSnoozeCount(id: Long, count: Int)

    /** Snooze commit in ONE statement, so the trigger and the counter change together or not at all. */
    @Query("UPDATE alarms SET snoozedUntilEpoch = :until, consecutiveSnoozeCount = :count WHERE id = :id")
    suspend fun setSnooze(id: Long, until: Long?, count: Int)
}
