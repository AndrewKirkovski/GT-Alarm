// Watch-background cropper. A thin wrapper over the shared CropperScaffold:
// it supplies the watch crop shape (circle for round panels, rounded-rect for
// FIT), the aspect from the connected watch's profile, the round watch-UI
// overlay (shown only for recognized round panels), and the watch save target
// (a PNG sized to the panel + a parallel BGRA .bin). The pan/zoom + crop math
// is shared (ImageCropper.kt / CropperScaffold.kt).
@file:Suppress("MatchingDeclarationName")

package com.kirkouski.gtwake.companion.ui.edit

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.wear.WatchBackgroundEncoder
import com.kirkouski.gtwake.companion.wear.WatchCropProfile
import com.kirkouski.gtwake.companion.wear.WatchScreenProfiles
import java.io.File

/**
 * Full-screen modal cropper for the watch ring-screen background, shaped to
 * the connected watch ([profile]): a circular viewport + watch-UI overlay for
 * a round GT panel, a rounded-rect viewport (no overlay) for a rectangular FIT
 * panel, or — for an unrecognized runtime resolution — the reported aspect
 * with no overlay (per the cropper rule in docs/watch-resolutions.md).
 *
 * Output: a PNG sized to [WatchCropProfile.outWidth] × outHeight written to
 * `cacheDir/watch_bg_<alarmId>.png` plus the parallel BGRA `.bin`
 * (`watch_bg_<alarmId>.bin`). The PNG is the production artifact (the watch
 * renders runtime PNG); the `.bin` is the legacy BGRA path. Upload to the
 * watch happens on save of the owning screen, not here.
 */
@Composable
fun WatchBackgroundPickerDialog(
    alarmId: Long,
    initialUri: String?,
    onDismiss: () -> Unit,
    onSaved: (uri: String) -> Unit,
    profile: WatchCropProfile = WatchScreenProfiles.DEFAULT,
) {
    val context = LocalContext.current
    CropperScaffold(
        titleRes = R.string.watch_bg_picker_title,
        helpRes = R.string.watch_bg_picker_help,
        initialUri = initialUri,
        cropShape = if (profile.round) CircleShape else RoundedCornerShape(28.dp),
        cropAspect = profile.outWidth.toFloat() / profile.outHeight.toFloat(),
        tag = TAG,
        onDismiss = onDismiss,
        onSaved = onSaved,
        onSave = { src, viewport, userScale, offsetX, offsetY ->
            persistCrop(context, alarmId, src, viewport, userScale, offsetX, offsetY, profile)
        },
        overlay = {
            // Round watch-UI preview overlay — recognized round panels only (we
            // have a single round overlay asset; rect + unrecognized panels show
            // none). Single source-of-truth PNG from .local/watch-ring-overlay.svg
            // — see memory:watch_overlay_sync.md for the regen discipline. The
            // photo shows through the asset's transparent regions, so full alpha
            // renders the watch UI exactly as on-device.
            if (profile.showOverlay) {
                Image(
                    painter = painterResource(R.drawable.watch_overlay),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alpha = OVERLAY_ASSET_ALPHA,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    )
}

/**
 * Materialize the user's crop into a PNG sized to the connected watch
 * ([profile].outWidth × outHeight) + parallel BGRA `.bin` in
 * [Context.getCacheDir]. Returns the PNG file on success, null on failure. The
 * `.bin` is encoded shape-aware (circle-masked only on round panels).
 */
// reason: 8 params for an internal helper that captures the picker's full crop
// transform (bitmap + viewport + user scale + 2D offset) plus the destination
// context + alarm id + watch profile. Bundling into a `CropParams` data class
// would just push the same field count behind a wrapper — the file is the only
// caller.
@Suppress("LongParameterList")
private fun persistCrop(
    context: Context,
    alarmId: Long,
    src: Bitmap,
    viewport: CropViewport,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
    profile: WatchCropProfile,
): File? {
    return runCatching {
        val pngFile = File(context.cacheDir, "watch_bg_$alarmId.png")
        val output = persistCroppedPng(
            dest = pngFile,
            src = src,
            viewport = viewport,
            userScale = userScale,
            offsetX = offsetX,
            offsetY = offsetY,
            outWidth = profile.outWidth,
            outHeight = profile.outHeight,
            tag = TAG,
        )?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
            ?: return@runCatching null
        val binFile = File(context.cacheDir, "watch_bg_$alarmId.bin")
        val binOk = WatchBackgroundEncoder.encodeFromCroppedBitmap(
            src = output,
            dest = binFile,
            targetWidth = profile.outWidth,
            targetHeight = profile.outHeight,
            round = profile.round,
        )
        output.recycle()
        if (!binOk) {
            Log.w(TAG, "persistCrop: encodeFromCroppedBitmap returned false for id=$alarmId")
            return@runCatching null
        }
        pngFile
    }.onFailure { e ->
        Log.w(TAG, "persistCrop failed id=$alarmId: ${e::class.simpleName}: ${e.message}")
    }.getOrNull()
}

// Picker overlay PNG alpha. Full 1.0 = the watch UI renders exactly as on the
// actual watch (the arc/time/buttons sit opaquely on the wallpaper; the asset's
// background is already transparent, so the photo shows through where the watch
// UI isn't). 2026-05-13: earlier 0.28/0.4/0.85 attempts were all wrong.
private const val OVERLAY_ASSET_ALPHA = 1.0f
private const val TAG = "WatchBgPicker"
