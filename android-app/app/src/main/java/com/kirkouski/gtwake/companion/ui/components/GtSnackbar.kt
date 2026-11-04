package com.kirkouski.gtwake.companion.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's one snackbar look. Place it outside the fading viewport, so the
 * fade can't hide it, and above the bottom pill.
 */
@Composable
fun GtSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(snackbarData = data, shape = RoundedCornerShape(16.dp))
    }
}

/** Shows [message] with an Undo action. A newer message replaces an older one. */
suspend fun SnackbarHostState.showUndo(message: String, undoLabel: String, onUndo: () -> Unit) {
    currentSnackbarData?.dismiss()
    val result = showSnackbar(message = message, actionLabel = undoLabel, duration = SnackbarDuration.Long)
    if (result == SnackbarResult.ActionPerformed) onUndo()
}
