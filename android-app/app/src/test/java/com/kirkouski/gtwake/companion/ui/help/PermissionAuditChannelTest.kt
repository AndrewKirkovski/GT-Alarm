package com.kirkouski.gtwake.companion.ui.help

import android.app.NotificationManager
import com.kirkouski.gtwake.companion.R
import org.junit.Assert.assertEquals
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
    fun `full screen row explains the channel when the channel is the cause`() {
        val fsi = PermissionAudit.Item.FULL_SCREEN_INTENT
        val blocked = PermissionAudit.Check(fsi, PermissionAudit.Status.DENIED, channelBlocked = true)
        val plain = PermissionAudit.Check(fsi, PermissionAudit.Status.DENIED)
        assertEquals(R.string.setup_perm_fsi_channel_body, blocked.bodyRes)
        assertEquals(R.string.setup_perm_fsi_body, plain.bodyRes)
    }

    @Test
    fun `missing channel does not block`() {
        assertFalse(PermissionAudit.channelImportanceBlocksFullScreen(null))
    }
}
