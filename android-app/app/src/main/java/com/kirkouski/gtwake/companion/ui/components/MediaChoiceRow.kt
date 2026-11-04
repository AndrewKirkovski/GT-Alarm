package com.kirkouski.gtwake.companion.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kirkouski.gtwake.companion.R

/**
 * The one layout for every sound and picture choice, in the alarm editor and
 * in Settings. See docs/ui-design-rules.md.
 *
 * [value] always names what will really be used. The button line stays in
 * place, so nothing jumps when Remove appears or goes away. Remove is neutral,
 * not red: it drops a choice, not a file.
 */
@Composable
fun MediaChoiceRow(
    @DrawableRes iconRes: Int,
    title: String,
    value: String,
    removable: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    preview: @Composable (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(end = 12.dp).size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            trailing?.invoke()
        }
        MediaChoiceButtons(
            title = title,
            removable = removable,
            enabled = enabled,
            onChoose = onChoose,
            onRemove = onRemove,
        )
        preview?.invoke()
    }
}

@Composable
private fun MediaChoiceButtons(
    title: String,
    removable: Boolean,
    enabled: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GtAccentButton(
            onClick = onChoose,
            enabled = enabled,
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            Text(
                text = stringResource(if (removable) R.string.action_change else R.string.field_audio_pick),
                maxLines = 1,
                softWrap = false,
            )
        }
        if (removable) {
            val description = stringResource(R.string.action_remove_named, title)
            GtFloatingButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = description },
            ) {
                Text(
                    text = stringResource(R.string.action_remove),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** Play or stop a sound preview. Used as the `trailing` slot of a sound row. */
@Composable
fun SoundPreviewButton(previewing: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(if (previewing) R.drawable.ic_stop else R.drawable.ic_play),
            contentDescription = stringResource(
                if (previewing) R.string.action_stop_preview else R.string.action_play_preview,
            ),
            tint = Color.Unspecified,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
fun soundChoiceText(choice: SoundChoice): String = when (choice) {
    is SoundChoice.Own -> choice.name ?: stringResource(R.string.field_audio_unknown)
    is SoundChoice.Default -> choice.name?.let { stringResource(R.string.field_audio_default_named, it) }
        ?: stringResource(R.string.field_audio_app_default)
    SoundChoice.System -> stringResource(R.string.field_audio_default)
}

@Composable
fun pictureChoiceText(choice: PictureChoice): String = stringResource(
    when (choice) {
        PictureChoice.Own -> R.string.picture_own
        PictureChoice.Default -> R.string.picture_default
        PictureChoice.Set -> R.string.picture_set
        PictureChoice.None -> R.string.picture_none
    },
)
