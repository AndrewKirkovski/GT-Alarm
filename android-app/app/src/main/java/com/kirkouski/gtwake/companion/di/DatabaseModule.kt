package com.kirkouski.gtwake.companion.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kirkouski.gtwake.companion.BuildConfig
import com.kirkouski.gtwake.companion.data.db.AlarmDao
import com.kirkouski.gtwake.companion.data.db.AlarmDatabase
import com.kirkouski.gtwake.companion.data.db.MIGRATION_1_2
import com.kirkouski.gtwake.companion.data.db.MIGRATION_2_3
import com.kirkouski.gtwake.companion.data.db.MIGRATION_3_4
import com.kirkouski.gtwake.companion.data.db.MIGRATION_4_5
import com.kirkouski.gtwake.companion.data.db.MIGRATION_9_10
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AlarmDatabase {
        val builder: RoomDatabase.Builder<AlarmDatabase> =
            Room.databaseBuilder(context, AlarmDatabase::class.java, "gt_alarm.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_9_10,
                )
        // Versions 5-8 only ever existed in interim DEBUG builds — they NEVER
        // shipped. Every released build is at v9 (1.0.5) → v10 runs MIGRATION_9_10
        // with user data preserved (v9 is NOT in this list, so it is untouched).
        // Allow a destructive fallback ONLY for the never-shipped 5-8 starting
        // points so a stray interim DB can't crash a RELEASE build with an
        // unrecoverable "A migration from N to 10 was required but not found".
        @Suppress("DEPRECATION")
        // reason: Room flags the destructive-migration helpers deprecated in some
        // 2.7+ branches; the @ConstructedBy path doesn't cover destructive yet.
        builder.fallbackToDestructiveMigrationFrom(dropAllTables = true, 5, 6, 7, 8)
        if (BuildConfig.DEBUG) {
            // Pre-release: v5 → v6 added Alarm.backgroundImageUri; v6 → v7
            // added Alarm.watchBackgroundImageUri; v7 → v8 dropped that
            // watch-bg field in favor of a single settings-level default.
            // No installs in the wild to preserve, so destructive migration
            // on each new bump is acceptable in DEBUG. Drops the alarms
            // table on schema mismatch; users re-create alarms on first
            // launch after upgrade.
            //
            // Release builds intentionally do NOT have this fallback —
            // shipping it would silently wipe every user's alarms on the
            // first post-release schema bump. When a real release ships,
            // add proper Migration objects for each new schema version.
            @Suppress("DEPRECATION")
            // reason: Room's destructive-migration helpers are flagged as
            // deprecated-in-favor-of-Schema in some 2.7+ branches; we use
            // the runtime helper because the @ConstructedBy approach
            // doesn't yet cover destructive-migration. Debug-only path.
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }
        return builder.build()
    }

    @Provides
    fun provideDao(db: AlarmDatabase): AlarmDao = db.alarmDao()
}
