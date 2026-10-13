package com.kirkouski.gtwake.companion.ring

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmNotificationsChannelTest {

    @Test
    fun `v2 importance wins over legacy`() {
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            AlarmNotifications.resolveV3Importance(
                v2Importance = NotificationManager.IMPORTANCE_DEFAULT,
                legacyImportance = NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    @Test
    fun `legacy importance is used when v2 is missing`() {
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            AlarmNotifications.resolveV3Importance(null, NotificationManager.IMPORTANCE_LOW),
        )
    }

    @Test
    fun `defaults to HIGH when no older channel exists`() {
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            AlarmNotifications.resolveV3Importance(null, null),
        )
    }
}
