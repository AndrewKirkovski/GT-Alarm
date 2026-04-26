package com.kirkouski.gtalarm

import android.app.Application
import com.kirkouski.gtalarm.ring.AlarmNotifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GtAlarmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AlarmNotifications.ensureChannel(this)
    }
}
