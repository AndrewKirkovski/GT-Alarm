package com.kirkouski.gtalarm.ui.edit

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight async loader for a SAF-backed image URI. Returns a State
 * whose value is null until the bitmap is decoded, then the decoded
 * [ImageBitmap]. On any failure (revoked permission, unreadable file,
 * decode error) the value stays null and the failure is logged.
 *
 * Coil would normally be the right answer here, but it's not in the
 * dependency graph and adding it just for this single call site isn't
 * worth the AGP-config + KSP-config churn. BitmapFactory.decodeStream is
 * sufficient for full-screen ring-screen rendering — the bitmap is
 * decoded once on Activity create and held for the whole ring session.
 */
@Composable
fun rememberBackgroundBitmap(uriString: String?): State<ImageBitmap?> {
    val context = LocalContext.current
    val bitmapState = remember(uriString) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uriString, context) {
        bitmapState.value = uriString?.let { loadImageBitmap(context, it) }
    }
    return bitmapState
}

/**
 * Decode the URI off the main thread. Returns null on any failure
 * (logged with the URI prefix so a tester knows whether the persistable-
 * permission grant was lost vs. a real decode failure).
 *
 * reason: TooGenericExceptionCaught — ContentResolver.openInputStream
 * raises SecurityException (revoked grant), FileNotFoundException
 * (deleted), and IOException (read error) under the same `RuntimeException`
 * umbrella in practice; narrower catches would miss real failures and
 * a blank ring screen is worse than a logged null fallback.
 */
@Suppress("TooGenericExceptionCaught", "InjectDispatcher")
// reason: InjectDispatcher — this is a top-level Compose helper, not a Hilt
// entry point. Threading an @IoDispatcher through every caller (which would
// have to add a parameter to rememberBackgroundBitmap and propagate up the
// composable tree) is not worth it for a single off-main bitmap decode.
private suspend fun loadImageBitmap(context: Context, uriString: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val uri = uriString.toUri()
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)?.asImageBitmap()
            }
        }.onFailure { e ->
            Log.w(TAG, "loadImageBitmap failed for $uriString: ${e::class.simpleName}: ${e.message}")
        }.getOrNull()
    }

private const val TAG = "BgImageLoad"
