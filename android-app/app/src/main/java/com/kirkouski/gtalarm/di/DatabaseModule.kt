package com.kirkouski.gtalarm.di

import android.content.Context
import androidx.room.Room
import com.kirkouski.gtalarm.data.db.AlarmDao
import com.kirkouski.gtalarm.data.db.AlarmDatabase
import com.kirkouski.gtalarm.data.db.MIGRATION_1_2
import com.kirkouski.gtalarm.data.db.MIGRATION_2_3
import com.kirkouski.gtalarm.data.db.MIGRATION_3_4
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides
    fun provideDao(db: AlarmDatabase): AlarmDao = db.alarmDao()
}
