package com.kirkouski.gtwake.companion.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.ui.list.forceSyncToast
import com.kirkouski.gtwake.companion.wear.ForceSyncResult

/**
 * "Show alarm list on watch". Off by default because every list update has to
 * launch the watch app (Huawei's Wear Engine only delivers to a running app);
 * AppGallery review read that as the watch popping a sync page on every edit.
 * Turning it on asks for consent first. Turning it off needs none: it only
 * removes the list from the watch.
 */
@Composable
internal fun WatchListSyncSection(
    listSyncOn: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var askConsent by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_watch),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(end = 12.dp).size(24.dp),
            )
            Text(
                text = stringResource(R.string.settings_watch_list_sync),
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = listSyncOn,
                onCheckedChange = { turnOn ->
                    if (turnOn) askConsent = true else onChange(false)
                },
            )
        }
        Text(
            text = stringResource(R.string.settings_watch_list_sync_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (askConsent) {
        WatchWakeConsentDialog(
            titleRes = R.string.watch_list_sync_confirm_title,
            bodyRes = R.string.watch_list_sync_confirm_body,
            confirmRes = R.string.watch_list_sync_confirm_ok,
            onConfirm = {
                askConsent = false
                onChange(true)
            },
            onDismiss = { askConsent = false },
        )
    }
}

/** What the user asked to do with the watch picture, pending consent. */
internal enum class WatchPictureAction { CHANGE, CLEAR }

/**
 * Consent before any watch-picture change: sending or removing the picture
 * launches the watch app briefly, the same visible wake as a list sync.
 */
@Composable
internal fun WatchPictureConsent(
    pending: WatchPictureAction?,
    onConfirm: (WatchPictureAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val action = pending ?: return
    WatchWakeConsentDialog(
        titleRes = if (action == WatchPictureAction.CHANGE) {
            R.string.watch_picture_confirm_title
        } else {
            R.string.watch_picture_clear_confirm_title
        },
        bodyRes = if (action == WatchPictureAction.CHANGE) {
            R.string.watch_picture_confirm_body
        } else {
            R.string.watch_picture_clear_confirm_body
        },
        confirmRes = R.string.watch_picture_confirm_ok,
        onConfirm = { onConfirm(action) },
        onDismiss = onDismiss,
    )
}

/**
 * Toast after the list-sync switch changed. Success wording depends on the
 * direction; any failure reuses the Sync-now wording so the remedy matches.
 */
internal fun watchListSyncToast(
    context: Context,
    enabled: Boolean,
    result: ForceSyncResult,
): String {
    val ok = result is ForceSyncResult.Ok || result is ForceSyncResult.AlreadyInSync
    return when {
        ok && enabled -> context.getString(R.string.watch_list_sync_on_done)
        ok -> context.getString(R.string.watch_list_sync_off_done)
        else -> forceSyncToast(context, result)
    }
}

@Composable
private fun WatchWakeConsentDialog(
    titleRes: Int,
    bodyRes: Int,
    confirmRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(bodyRes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(confirmRes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
