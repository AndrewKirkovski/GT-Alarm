// reason: file groups three related declarations — the `PickedAudio`
// data carrier, the `rememberAudioPicker` composable, and helper
// functions `persistPermission`/`resolveName`. The file is named after
// the public composable (rememberAudioPicker → AudioPicker.kt), not the
// first declaration. Detekt's MatchingDeclarationName checks the FIRST
// top-level declaration, which doesn't fit a "factory + carrier" file.
// Suppressing per-file with reason rather than baselining (it would
// re-trigger every time we add another helper).
@file:Suppress("MatchingDeclarationName")

package com.kirkouski.gtalarm.ui.edit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

data class PickedAudio(val uri: String, val name: String?)

@Composable
fun rememberAudioPicker(onPicked: (PickedAudio) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            persistPermission(context, uri)
            val name = resolveName(context, uri)
            onPicked(PickedAudio(uri.toString(), name))
        }
    }
    return { launcher.launch(arrayOf("audio/*")) }
}

private fun persistPermission(context: Context, uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
}

private fun resolveName(context: Context, uri: Uri): String? {
    return context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
