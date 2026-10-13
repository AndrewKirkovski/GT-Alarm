package com.kirkouski.gtwake.companion.ui.help

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAuditChannelTest {

    @Test
    fun `importance below HIGH blocks full screen`() {
        assertTrue(PermissionAudit.channelImportanceBlocksFullScreen(NotificationManager.IMPORTANCE_DEFAULT))
        assertTrue(PermissionAudit.channelImportanceBlocksFullScreen(NotificationManager.IMPORTANCE_LOW))
    }

    @Test
    fun `blocked channel blocks full screen`() {
        assertTrue(PermissionAudit.channelImportanceBlocksFullScreen(NotificationManager.IMPORTANCE_NONE))
    }

    @Test
    fun `HIGH and above do not block`() {
        assertFalse(PermissionAudit.channelImportanceBlocksFullScreen(NotificationManager.IMPORTANCE_HIGH))
        assertFalse(PermissionAudit.channelImportanceBlocksFullScreen(NotificationManager.IMPORTANCE_MAX))
    }

    @Test
    fun `missing channel does not block`() {
        assertFalse(PermissionAudit.channelImportanceBlocksFullScreen(null))
    }
}
