package com.kirkouski.gtalarm

import android.app.Application
import com.kirkouski.gtalarm.data.sync.IncomingMessageHandler
import com.kirkouski.gtalarm.ring.AlarmNotifications
import com.kirkouski.gtalarm.wear.WearBridgeService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GtAlarmApp : Application() {

    @Inject lateinit var wearBridge: WearBridgeService
    @Inject lateinit var incomingHandler: IncomingMessageHandler

    override fun onCreate() {
        super.onCreate()
        AlarmNotifications.ensureChannel(this)
        wearBridge.setIncomingHandler(incomingHandler)
    }
}
