package com.kirkouski.gtwake.companion.di

import com.kirkouski.gtwake.companion.wear.HuaweiWearBridge
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WearModule {

    @Binds
    @Singleton
    abstract fun bindWearBridge(impl: HuaweiWearBridge): WearBridgeService
}
