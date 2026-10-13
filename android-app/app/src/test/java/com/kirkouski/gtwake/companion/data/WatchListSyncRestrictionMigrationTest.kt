package com.kirkouski.gtwake.companion.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchListSyncRestrictionMigrationTest {
    private val syncKey = booleanPreferencesKey("watch_list_sync")
    private val unlockedKey = booleanPreferencesKey("watch_list_sync_unlocked")
    private val migration = WatchListSyncRestrictionMigration(syncKey, unlockedKey)

    // A real-DataStore round trip (seed an enabled file, reopen with the
    // migration) cannot run in this repo's JVM tests: DataStore's JVM file
    // storage replaces the file with File.renameTo, which fails on Windows when
    // the target already exists. That the app's DataStore runs this migration
    // on upgrade is a device check (docs/acceptance-criteria.md, October 2026).

    @Test
    fun `legacy enabled preference is silently disabled and unrelated settings survive`() = runTest {
        val ringtoneKey = stringPreferencesKey("default_abs_ringtone_uri")
        val legacy = mutablePreferencesOf(syncKey to true, ringtoneKey to "content://audio/1")
        assertTrue(migration.shouldMigrate(legacy))

        val migrated = migration.migrate(legacy)

        assertEquals(false, migrated[syncKey])
        assertEquals(false, migrated[unlockedKey])
        assertEquals("content://audio/1", migrated[ringtoneKey])
        assertFalse(migration.shouldMigrate(migrated))
    }

    @Test
    fun `migration does not reset an already unlocked preference`() = runTest {
        assertFalse(migration.shouldMigrate(mutablePreferencesOf(syncKey to true, unlockedKey to true)))
        assertTrue(SettingsState(watchListSync = true, watchListSyncUnlocked = true).effectiveWatchListSync)
        assertFalse(SettingsState(watchListSync = true).effectiveWatchListSync)
    }
}
