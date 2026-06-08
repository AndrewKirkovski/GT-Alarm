package com.kirkouski.gtwake.companion.ui.edit

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
 *
 * **Cache invalidation on file-content change** — the watch-bg picker
 * writes the cropped PNG back to the same path (`watch_bg_<id>.png`),
 * so the URI string stays identical across re-crops. Without keying on
 * file modification time we'd return the previously decoded bitmap and
 * the editor thumbnail would lag behind the user's new crop (user-
 * reported bug 2026-05-13: "Watch bg preview in alarm editor is not
 * respecting how we positioned the image"). For non-file URIs (SAF
 * content://) lastModified is unavailable and the URI-only key is fine
 * because the picker produces a fresh URI for each pick.
 */
@Composable
fun rememberBackgroundBitmap(uriString: String?, reloadKey: Any? = null): State<ImageBitmap?> {
    val context = LocalContext.current
    // [reloadKey] in the remember key forces a re-decode when the caller
    // knows the file content changed but the URI string did NOT — the
    // watch-bg picker rewrites a fixed path (watch_bg_<id>.png), so a
    // re-crop is invisible to both the URI string and computeCacheKey's
    // mtime read (which is itself remembered on uriString).
    val cacheKey = remember(uriString, reloadKey) { computeCacheKey(uriString) }
    val bitmapState = remember(cacheKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cacheKey, context) {
        bitmapState.value = uriString?.let { loadImageBitmap(context, it) }
    }
    return bitmapState
}

/**
 * Build a cache key that combines the URI string with file
 * lastModified when the URI is a file://. lastModified bumps on every
 * picker save, so a re-crop forces re-decode. Falls back to URI-only
 * for content://, http(s)://, and any unparseable path.
 */
private fun computeCacheKey(uriString: String?): String {
    uriString ?: return ""
    val filePath = runCatching { uriString.toUri() }.getOrNull()
        ?.takeIf { it.scheme == "file" }
        ?.path
        ?: return uriString
    val mtime = runCatching { java.io.File(filePath).lastModified() }.getOrDefault(0L)
    return "$uriString#mtime=$mtime"
}

/**
 * Decode the URI off the main thread. Returns null on any failure
 * (logged with the URI prefix so a tester knows whether the persistable-
 * permission grant was lost vs. a real decode failure). `runCatching`
 * covers SecurityException (revoked grant), FileNotFoundException, and
 * IOException — all of which a missing/changed SAF grant can raise.
 */
// reason: InjectDispatcher — this is a top-level Compose helper, not a Hilt
// entry point. Threading an @IoDispatcher through every caller (which would
// have to add a parameter to rememberBackgroundBitmap and propagate up the
// composable tree) is not worth it for a single off-main bitmap decode.
@Suppress("InjectDispatcher")
private suspend fun loadImageBitmap(context: Context, uriString: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val uri = uriString.toUri()
            // Two-pass decode: read the image header to get native dimensions,
            // pick an inSampleSize so the decoded bitmap fits within
            // MAX_DECODE_PX × MAX_DECODE_PX, then decode at the sampled size.
            // Code-review pass 1 #8 (2026-05-13): a 50 MP photo decoded at
            // native resolution is ~200 MB ARGB_8888 — enough to OOM the
            // process on a long alarm list. MAX_DECODE_PX=2048 is generous
            // for the full-screen ring activity while keeping memory bounded.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)?.asImageBitmap()
            }
        }.onFailure { e ->
            // Log only URI scheme, never the full content-URI: SAF URIs
            // may embed user-visible filenames.
            val scheme = runCatching { uriString.toUri().scheme }.getOrNull() ?: "unknown"
            Log.w(TAG, "loadImageBitmap failed scheme=$scheme: ${e::class.simpleName}: ${e.message}")
        }.getOrNull()
    }

private fun computeInSampleSize(srcW: Int, srcH: Int): Int {
    if (srcW <= 0 || srcH <= 0) return 1
    var sampleSize = 1
    while (srcW / sampleSize > MAX_DECODE_PX || srcH / sampleSize > MAX_DECODE_PX) {
        sampleSize *= 2
    }
    return sampleSize
}

private const val TAG = "BgImageLoad"
private const val MAX_DECODE_PX = 2048
