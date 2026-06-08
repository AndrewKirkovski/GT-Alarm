package com.kirkouski.gtwake.companion.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

// reason: This module is THE dependency-injection point for coroutine
// dispatchers. The whole purpose of detekt's InjectDispatcher rule is to
// push every site away from `Dispatchers.IO` / `Dispatchers.Main` into a
// single qualified provider — this file. Suppressing here is the correct
// expression of the rule's intent, not a workaround.
@Suppress("InjectDispatcher")
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}
