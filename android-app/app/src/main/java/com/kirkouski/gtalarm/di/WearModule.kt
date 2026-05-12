package com.kirkouski.gtalarm.di

import com.kirkouski.gtalarm.wear.HuaweiWearBridge
import com.kirkouski.gtalarm.wear.WearBridgeService
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
