package com.kirkouski.gtwake.companion.data.db

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kirkouski.gtwake.companion.domain.Alarm
import com.kirkouski.gtwake.companion.domain.VibrationPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.sqlite.db.SupportSQLiteOpenHelper

private const val TEST_DB_NAME = "migration-test.db"

private val V1_CREATE = """
    CREATE TABLE IF NOT EXISTS `alarms` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `label` TEXT NOT NULL,
        `hour` INTEGER NOT NULL,
        `minute` INTEGER NOT NULL,
        `daysOfWeek` INTEGER NOT NULL,
        `enabled` INTEGER NOT NULL,
        `audioUri` TEXT,
        `audioName` TEXT,
        `isVibrationOnly` INTEGER NOT NULL
    )
""".trimIndent()

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
    fun migrate1To2_preservesExistingRowsAndAddsColumnWithDefaultZero() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase(TEST_DB_NAME)

        // Seed a v1 DB by hand (no exported schema available — exportSchema=false
        // on AlarmDatabase). The CREATE statement above mirrors what Room would
        // have emitted for v1 of AlarmEntity.
        val v1Helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(TEST_DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V1_CREATE)
                    }
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

        v1Helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO alarms (label, hour, minute, daysOfWeek, enabled, audioUri, audioName, isVibrationOnly) " +
                    "VALUES ('Wake', 7, 30, 0, 1, 'content://audio/1', 'Birds', 0)"
            )
            db.execSQL(
                "INSERT INTO alarms (label, hour, minute, daysOfWeek, enabled, audioUri, audioName, isVibrationOnly) " +
                    "VALUES ('Sleep', 22, 0, 62, 0, NULL, NULL, 1)"
            )
        }

        // Apply MIGRATION_1_2 manually against the same DB file.
        val migrationHelper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(TEST_DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V1_CREATE)
                    }
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        if (oldVersion == 1 && newVersion == 2) MIGRATION_1_2.migrate(db)
                    }
                })
                .build()
        )

        migrationHelper.writableDatabase.use { db ->
            // Confirm the column exists with NOT NULL DEFAULT 0.
            db.query("PRAGMA table_info('alarms')").use { c ->
                val nameIdx = c.getColumnIndex("name")
                val notNullIdx = c.getColumnIndex("notnull")
                val dfltIdx = c.getColumnIndex("dflt_value")
                var foundCol = false
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == "updatedAtEpoch") {
                        foundCol = true
                        assertEquals("updatedAtEpoch must be NOT NULL", 1, c.getInt(notNullIdx))
                        assertEquals("updatedAtEpoch DEFAULT must be 0", "0", c.getString(dfltIdx))
                    }
                }
                assertTrue("updatedAtEpoch column is missing after migration", foundCol)
            }

            // Confirm both rows survived with all fields intact and updatedAtEpoch = 0.
            db.query("SELECT id, label, hour, minute, daysOfWeek, enabled, audioUri, audioName, isVibrationOnly, updatedAtEpoch FROM alarms ORDER BY id").use { c ->
                assertEquals(2, c.count)

                c.moveToNext()
                assertEquals(1L, c.getLong(0))
                assertEquals("Wake", c.getString(1))
                assertEquals(7, c.getInt(2))
                assertEquals(30, c.getInt(3))
                assertEquals(0, c.getInt(4))
                assertEquals(1, c.getInt(5))
                assertEquals("content://audio/1", c.getString(6))
                assertEquals("Birds", c.getString(7))
                assertEquals(0, c.getInt(8))
                assertEquals(0L, c.getLong(9))

                c.moveToNext()
                assertEquals(2L, c.getLong(0))
                assertEquals("Sleep", c.getString(1))
                assertEquals(22, c.getInt(2))
                assertEquals(0, c.getInt(3))
                assertEquals(62, c.getInt(4))
                assertEquals(0, c.getInt(5))
                assertTrue(c.isNull(6))
                assertTrue(c.isNull(7))
                assertEquals(1, c.getInt(8))
                assertEquals(0L, c.getLong(9))
            }
        }

        ctx.deleteDatabase(TEST_DB_NAME)
    }

    @Test
    fun migrate2To3_addsSnoozeColumnWithDefault10() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase(TEST_DB_NAME)

        // Seed a v2 DB by hand. CREATE mirrors what Room would emit for v2.
        val v2Create = """
            CREATE TABLE IF NOT EXISTS `alarms` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `label` TEXT NOT NULL,
                `hour` INTEGER NOT NULL,
                `minute` INTEGER NOT NULL,
                `daysOfWeek` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                `audioUri` TEXT,
                `audioName` TEXT,
                `isVibrationOnly` INTEGER NOT NULL,
                `updatedAtEpoch` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        val v2Helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(TEST_DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(v2Create)
                    }
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

        v2Helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO alarms (label, hour, minute, daysOfWeek, enabled, audioUri, audioName, isVibrationOnly, updatedAtEpoch) " +
                    "VALUES ('Pre-snooze', 6, 30, 0, 1, NULL, NULL, 0, 1700000000000)"
            )
        }

        val migrationHelper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(TEST_DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(v2Create)
                    }
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        if (oldVersion <= 2 && newVersion >= 3) MIGRATION_2_3.migrate(db)
                    }
                })
                .build()
        )

        migrationHelper.writableDatabase.use { db ->
            db.query("PRAGMA table_info('alarms')").use { c ->
                val nameIdx = c.getColumnIndex("name")
                val notNullIdx = c.getColumnIndex("notnull")
                val dfltIdx = c.getColumnIndex("dflt_value")
                var foundCol = false
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == "snoozeMinutes") {
                        foundCol = true
                        assertEquals("snoozeMinutes must be NOT NULL", 1, c.getInt(notNullIdx))
                        assertEquals(
                            "snoozeMinutes DEFAULT must match domain default",
                            Alarm.DEFAULT_SNOOZE_MINUTES.toString(),
                            c.getString(dfltIdx),
                        )
                    }
                }
                assertTrue("snoozeMinutes column is missing after migration", foundCol)
            }

            db.query("SELECT label, snoozeMinutes FROM alarms").use { c ->
                assertTrue(c.moveToNext())
                assertEquals("Pre-snooze", c.getString(0))
                assertEquals(Alarm.DEFAULT_SNOOZE_MINUTES, c.getInt(1))
            }
        }

        ctx.deleteDatabase(TEST_DB_NAME)
    }

    @Test
    fun migrate3To4_addsRelativeMinutesNullableAndSelfDestructDefaultFalse() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase(TEST_DB_NAME)

        val v3Create = """
            CREATE TABLE IF NOT EXISTS `alarms` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `label` TEXT NOT NULL,
                `hour` INTEGER NOT NULL,
                `minute` INTEGER NOT NULL,
                `daysOfWeek` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                `audioUri` TEXT,
                `audioName` TEXT,
                `isVibrationOnly` INTEGER NOT NULL,
                `updatedAtEpoch` INTEGER NOT NULL DEFAULT 0,
                `snoozeMinutes` INTEGER NOT NULL DEFAULT ${Alarm.DEFAULT_SNOOZE_MINUTES}
            )
        """.trimIndent()

        val v3Helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(TEST_DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(v3Create)
                    }
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

        v3Helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO alarms (label, hour, minute, daysOfWeek, enabled, audioUri, audioName, isVibrationOnly, updatedAtEpoch, snoozeMinutes) " +
                    "VALUES ('Pre-relative', 8, 0, 0, 1, NULL, NULL, 0, 1700000000000, 15)"
            )
        }

        val migrationHelper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(TEST_DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(v3Create)
                    }
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        if (oldVersion <= 3 && newVersion >= 4) MIGRATION_3_4.migrate(db)
                    }
                })
                .build()
        )

        migrationHelper.writableDatabase.use { db ->
            db.query("PRAGMA table_info('alarms')").use { c ->
                val nameIdx = c.getColumnIndex("name")
                val typeIdx = c.getColumnIndex("type")
                val notNullIdx = c.getColumnIndex("notnull")
                val dfltIdx = c.getColumnIndex("dflt_value")
                var sawRelative = false
                var sawSelfDestruct = false
                while (c.moveToNext()) {
                    when (c.getString(nameIdx)) {
                        "relativeMinutes" -> {
                            sawRelative = true
                            // Explicit column-type assertion: an accidental
                            // TEXT (e.g. typo'd `INT` → schema parsed as a
                            // string-affinity column) would pass the nullable
                            // check and the isNull check below — getInt would
                            // crash at runtime on a real non-null integer.
                            assertEquals(
                                "relativeMinutes must be INTEGER",
                                "INTEGER",
                                c.getString(typeIdx),
                            )
                            assertEquals(
                                "relativeMinutes must be nullable",
                                0,
                                c.getInt(notNullIdx),
                            )
                        }
                        "selfDestruct" -> {
                            sawSelfDestruct = true
                            // INTEGER column with NOT NULL DEFAULT 0 — Room
                            // serializes Kotlin Boolean as INTEGER (0/1).
                            assertEquals(
                                "selfDestruct must be INTEGER",
                                "INTEGER",
                                c.getString(typeIdx),
                            )
                            assertEquals(
                                "selfDestruct must be NOT NULL",
                                1,
                                c.getInt(notNullIdx),
                            )
                            assertEquals(
                                "selfDestruct DEFAULT must be 0",
                                "0",
                                c.getString(dfltIdx),
                            )
                        }
                    }
                }
                assertTrue("relativeMinutes column missing after migration", sawRelative)
                assertTrue("selfDestruct column missing after migration", sawSelfDestruct)
            }

            db.query("SELECT label, relativeMinutes, selfDestruct FROM alarms").use { c ->
                assertTrue(c.moveToNext())
                assertEquals("Pre-relative", c.getString(0))
                assertTrue("relativeMinutes should be NULL for upgraded rows", c.isNull(1))
                assertEquals(0, c.getInt(2))
            }

            // Round-trip a non-null integer relativeMinutes through the
            // migrated schema. If the migration accidentally created the
            // column as TEXT (Room parses int affinity wrong), getInt would
            // return 0 for a row containing the string "15" — this catches
            // that even though the PRAGMA-type check above also covers it.
            db.execSQL(
                "INSERT INTO alarms (label, hour, minute, daysOfWeek, enabled, audioUri, " +
                    "audioName, isVibrationOnly, updatedAtEpoch, snoozeMinutes, " +
                    "relativeMinutes, selfDestruct) VALUES " +
                    "('Timer', 0, 0, 0, 1, NULL, NULL, 0, 1, 10, 15, 1)",
            )
            db.query(
                "SELECT relativeMinutes, selfDestruct FROM alarms WHERE label = 'Timer'",
            ).use { c ->
                assertTrue(c.moveToNext())
                assertEquals(
                    "relativeMinutes round-trip as integer must be 15",
                    15,
                    c.getInt(0),
                )
                assertEquals(
                    "selfDestruct round-trip must be 1",
                    1,
                    c.getInt(1),
                )
            }
        }

        ctx.deleteDatabase(TEST_DB_NAME)
    }

    @Test
    fun migrate9To10_addsPerDeviceVibEnablesAndMigratesLegacyOff() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase(TEST_DB_NAME)

        // v9 schema by hand (exportSchema=false). Mirrors the shipped v9
        // AlarmEntity — everything the v10 entity has EXCEPT the two columns
        // MIGRATION_9_10 adds (phoneVibrationEnabled / watchVibrationEnabled).
        // This is the ONLY migration real users (all on v9 = 1.0.5) will run.
        val v9Create = """
            CREATE TABLE IF NOT EXISTS `alarms` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `label` TEXT NOT NULL,
                `hour` INTEGER NOT NULL,
                `minute` INTEGER NOT NULL,
                `daysOfWeek` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                `audioUri` TEXT,
                `audioName` TEXT,
                `isVibrationOnly` INTEGER NOT NULL,
                `updatedAtEpoch` INTEGER NOT NULL DEFAULT 0,
                `snoozeMinutes` INTEGER NOT NULL DEFAULT ${Alarm.DEFAULT_SNOOZE_MINUTES},
                `relativeMinutes` INTEGER,
                `selfDestruct` INTEGER NOT NULL DEFAULT 0,
                `snoozedUntilEpoch` INTEGER,
                `backgroundImageUri` TEXT,
                `vibrationPatternName` TEXT NOT NULL DEFAULT '${VibrationPattern.DEFAULT.name}',
                `volumeRampSeconds` INTEGER NOT NULL DEFAULT 0,
                `maxSnoozeCount` INTEGER NOT NULL DEFAULT 0,
                `consecutiveSnoozeCount` INTEGER NOT NULL DEFAULT 0,
                `skipNextEpoch` INTEGER
            )
        """.trimIndent()

        val v9Helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(TEST_DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(v9Create)
                    }
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

        v9Helper.writableDatabase.use { db ->
            // Legacy "no vibration" row — vibrationPatternName == 'OFF'.
            db.execSQL(
                "INSERT INTO alarms (label, hour, minute, daysOfWeek, enabled, isVibrationOnly, vibrationPatternName) " +
                    "VALUES ('Legacy off', 7, 0, 0, 1, 0, 'OFF')",
            )
            // Normal row with a real pattern that must survive untouched.
            db.execSQL(
                "INSERT INTO alarms (label, hour, minute, daysOfWeek, enabled, isVibrationOnly, vibrationPatternName) " +
                    "VALUES ('Buzzes', 8, 30, 0, 1, 0, 'HEARTBEAT')",
            )
        }

        val migrationHelper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(TEST_DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(v9Create)
                    }
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        if (oldVersion <= 9 && newVersion >= 10) MIGRATION_9_10.migrate(db)
                    }
                })
                .build()
        )

        migrationHelper.writableDatabase.use { db ->
            // Both new columns must exist as INTEGER NOT NULL DEFAULT 1.
            db.query("PRAGMA table_info('alarms')").use { c ->
                val nameIdx = c.getColumnIndex("name")
                val typeIdx = c.getColumnIndex("type")
                val notNullIdx = c.getColumnIndex("notnull")
                val dfltIdx = c.getColumnIndex("dflt_value")
                var sawPhone = false
                var sawWatch = false
                while (c.moveToNext()) {
                    when (c.getString(nameIdx)) {
                        "phoneVibrationEnabled" -> {
                            sawPhone = true
                            assertEquals("phoneVibrationEnabled must be INTEGER", "INTEGER", c.getString(typeIdx))
                            assertEquals("phoneVibrationEnabled must be NOT NULL", 1, c.getInt(notNullIdx))
                            assertEquals("phoneVibrationEnabled DEFAULT must be 1", "1", c.getString(dfltIdx))
                        }
                        "watchVibrationEnabled" -> {
                            sawWatch = true
                            assertEquals("watchVibrationEnabled must be INTEGER", "INTEGER", c.getString(typeIdx))
                            assertEquals("watchVibrationEnabled must be NOT NULL", 1, c.getInt(notNullIdx))
                            assertEquals("watchVibrationEnabled DEFAULT must be 1", "1", c.getString(dfltIdx))
                        }
                    }
                }
                assertTrue("phoneVibrationEnabled column missing after migration", sawPhone)
                assertTrue("watchVibrationEnabled column missing after migration", sawWatch)
            }

            // Legacy OFF row → both enables 0 + pattern reset to the real default.
            db.query(
                "SELECT phoneVibrationEnabled, watchVibrationEnabled, vibrationPatternName " +
                    "FROM alarms WHERE label = 'Legacy off'",
            ).use { c ->
                assertTrue(c.moveToNext())
                assertEquals("legacy OFF row → phone vibration off", 0, c.getInt(0))
                assertEquals("legacy OFF row → watch vibration off", 0, c.getInt(1))
                assertEquals(
                    "legacy OFF row → pattern reset to DEFAULT",
                    VibrationPattern.DEFAULT.name,
                    c.getString(2),
                )
            }

            // Normal row → enables default to 1 (enabled) + pattern untouched.
            db.query(
                "SELECT phoneVibrationEnabled, watchVibrationEnabled, vibrationPatternName " +
                    "FROM alarms WHERE label = 'Buzzes'",
            ).use { c ->
                assertTrue(c.moveToNext())
                assertEquals("normal row → phone vibration stays enabled", 1, c.getInt(0))
                assertEquals("normal row → watch vibration stays enabled", 1, c.getInt(1))
                assertEquals("normal row → pattern unchanged", "HEARTBEAT", c.getString(2))
            }
        }

        ctx.deleteDatabase(TEST_DB_NAME)
    }
}
