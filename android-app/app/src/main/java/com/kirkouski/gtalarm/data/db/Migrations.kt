package com.kirkouski.gtalarm.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Adds updatedAtEpoch column for cross-device LWW conflict resolution.
// Existing rows default to 0; the next mutation in AlarmRepository will
// stamp them. Receivers treat 0 as "older than any wall-clock timestamp".
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN updatedAtEpoch INTEGER NOT NULL DEFAULT 0")
    }
}
