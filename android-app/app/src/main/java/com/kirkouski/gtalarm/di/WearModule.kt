package com.kirkouski.gtalarm.di

import com.kirkouski.gtalarm.wear.NoOpWearBridge
import com.kirkouski.gtalarm.wear.WearBridgeService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Swap point for Huawei Wear Engine. When the vendor SDK arrives, change this
 * binding from [NoOpWearBridge] to a real `HuaweiWearBridge` implementation —
 * nothing else in the app needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WearModule {

    @Binds
    @Singleton
    abstract fun bindWearBridge(impl: NoOpWearBridge): WearBridgeService
}
