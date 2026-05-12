package com.kirkouski.gtalarm.data.db

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kirkouski.gtalarm.domain.Alarm
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
                val notNullIdx = c.getColumnIndex("notnull")
                val dfltIdx = c.getColumnIndex("dflt_value")
                var sawRelative = false
                var sawSelfDestruct = false
                while (c.moveToNext()) {
                    when (c.getString(nameIdx)) {
                        "relativeMinutes" -> {
                            sawRelative = true
                            assertEquals(
                                "relativeMinutes must be nullable",
                                0,
                                c.getInt(notNullIdx),
                            )
                        }
                        "selfDestruct" -> {
                            sawSelfDestruct = true
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
        }

        ctx.deleteDatabase(TEST_DB_NAME)
    }
}
