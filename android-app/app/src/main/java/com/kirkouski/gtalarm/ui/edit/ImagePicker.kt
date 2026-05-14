// reason: file groups two related declarations — `rememberBackgroundImagePicker`
// composable + `persistImagePermission` helper. The file is named after the
// public composable (rememberBackgroundImagePicker → ImagePicker.kt), not
// the first declaration. Detekt's MatchingDeclarationName checks the FIRST
// top-level declaration, which doesn't fit a "factory + helper" file. Same
// pattern as AudioPicker.kt; suppressing per-file with reason.
@file:Suppress("MatchingDeclarationName")

package com.kirkouski.gtalarm.ui.edit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Compose-friendly factory for an image picker that opens the system
 * document chooser (SAF) with an image/&#42; MIME filter and persists
 * read access on the picked URI so it stays readable across process
 * restart.
 *
 * Persistable permission is **load-bearing**: the URI is stored in the
 * alarm row and re-resolved hours later when the alarm fires. Without
 * `takePersistableUriPermission` the system revokes read access at the
 * end of the picker activity's grant window and `ContentResolver.openInputStream`
 * throws `SecurityException` from `AlarmActivity` — the user sees a
 * blank/black screen instead of their picked background.
 *
 * Pattern mirrors [rememberAudioPicker]. The callback receives the URI
 * string or null if the user cancelled the picker.
 */
@Composable
fun rememberBackgroundImagePicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            persistImagePermission(context, uri)
            onPicked(uri.toString())
        }
    }
    return { launcher.launch(arrayOf("image/*")) }
}

/**
 * Take persistable read permission on the picked URI. Wrapped in
 * `runCatching` because some SAF providers (e.g. transient cloud
 * documents on cold-start) throw `SecurityException` even though the
 * grant was just issued; the URI is still readable in the foreground
 * tab so we don't want to drop the pick — we only lose the cross-restart
 * guarantee.
 */
private fun persistImagePermission(context: Context, uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        .onFailure { Log.w(TAG, "takePersistableUriPermission failed: ${it::class.simpleName}: ${it.message}") }
}

private const val TAG = "ImagePicker"
