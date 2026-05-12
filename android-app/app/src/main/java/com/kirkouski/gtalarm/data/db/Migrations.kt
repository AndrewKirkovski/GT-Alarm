package com.kirkouski.gtalarm.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kirkouski.gtalarm.domain.Alarm

// Adds updatedAtEpoch column for cross-device LWW conflict resolution.
// Existing rows default to 0; the next mutation in AlarmRepository will
// stamp them. Receivers treat 0 as "older than any wall-clock timestamp".
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN updatedAtEpoch INTEGER NOT NULL DEFAULT 0")
    }
}

// Adds per-alarm snooze duration. Existing rows default to 10 minutes
// (the previous app-wide hardcoded value), so users who upgrade see no
// behavior change until they edit an alarm.
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE alarms ADD COLUMN snoozeMinutes INTEGER NOT NULL DEFAULT " +
                Alarm.DEFAULT_SNOOZE_MINUTES,
        )
    }
}

// Adds two columns:
//   - relativeMinutes (nullable Int): null for absolute alarms; otherwise
//     the duration in minutes from updatedAtEpoch to fire time. Existing
//     rows are absolute by definition, so default NULL is correct.
//   - selfDestruct (Boolean, INTEGER 0/1): delete row after final dismiss.
//     Existing rows default to 0 (off) — the upgrade preserves current
//     behavior. New one-shot/relative alarms get default-on via UI logic.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN relativeMinutes INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE alarms ADD COLUMN selfDestruct INTEGER NOT NULL DEFAULT 0")
    }
}

// Adds snoozedUntilEpoch: tracks the actual next-fire moment when an alarm
// is currently snoozed. The UI list reads this in preference to recomputing
// from hour/minute so a snoozed one-shot doesn't display "in 23h" (the
// next absolute occurrence of its original ring time). Cleared on fire,
// edit, and disable. NULL means "not snoozed" — the normal case. Existing
// rows default to NULL — pre-upgrade snoozes are lost, which is acceptable
// since no production deploy has happened and snoozes are transient anyway.
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN snoozedUntilEpoch INTEGER DEFAULT NULL")
    }
}
