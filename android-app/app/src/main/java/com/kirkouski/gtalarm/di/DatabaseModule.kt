package com.kirkouski.gtalarm.di

import android.content.Context
import androidx.room.Room
import com.kirkouski.gtalarm.data.db.AlarmDao
import com.kirkouski.gtalarm.data.db.AlarmDatabase
import com.kirkouski.gtalarm.data.db.MIGRATION_1_2
import com.kirkouski.gtalarm.data.db.MIGRATION_2_3
import com.kirkouski.gtalarm.data.db.MIGRATION_3_4
import com.kirkouski.gtalarm.data.db.MIGRATION_4_5
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
    fun provideDatabase(@ApplicationContext context: Context): AlarmDatabase =
        Room.databaseBuilder(context, AlarmDatabase::class.java, "gt_alarm.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            // Pre-release: v5 → v6 added Alarm.backgroundImageUri; v6 → v7
            // added Alarm.watchBackgroundImageUri; v7 → v8 dropped that
            // watch-bg field in favor of a single settings-level default.
            // No installs in the wild to preserve, so destructive migration
            // on each new bump is acceptable (per project memory note
            // `phase_3_4_landings.md` and explicit task scope). Drops the
            // alarms table on schema mismatch; users re-create alarms on
            // first launch after upgrade. Remove this once a real release
            // ships and we need to preserve installed data.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideDao(db: AlarmDatabase): AlarmDao = db.alarmDao()
}
