package com.kirkouski.gtalarm.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the BFU cache file (overridable in tests). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BfuCacheFile

@Module
@InstallIn(SingletonComponent::class)
object BfuModule {

    /** DPS-backed file; readable pre-unlock. */
    @Provides
    @Singleton
    @BfuCacheFile
    fun provideBfuCacheFile(@ApplicationContext context: Context): File =
        File(context.createDeviceProtectedStorageContext().filesDir, "bfu_alarms.json")
}
