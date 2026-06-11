// Phone-background cropper. A thin wrapper over the shared CropperScaffold:
// it supplies a rounded-rect viewport at the live screen aspect, the phone
// ring-screen overlay (time + Dismiss/Snooze preview), and the phone save
// target (a 1080-wide PNG at the screen aspect). The pan/zoom + crop math is
// shared (ImageCropper.kt / CropperScaffold.kt).
@file:Suppress("MatchingDeclarationName")

package com.kirkouski.gtwake.companion.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.ui.components.PhoneRingOverlay
import java.io.File
import kotlin.math.roundToInt

@Composable
fun PhoneBackgroundPickerDialog(
    cacheKey: String,
    initialUri: String?,
    timeText: String,
    labelText: String,
    onDismiss: () -> Unit,
    onSaved: (uri: String) -> Unit,
) {
    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current
    // Crop to the live screen aspect (clamped to a sane phone range) so the
    // preview matches how the image will fill the ring screen.
    val screenAspect = remember(windowInfo.containerSize) {
        val size = windowInfo.containerSize
        (size.width.toFloat() / size.height.toFloat())
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(PHONE_ASPECT_MIN, PHONE_ASPECT_MAX)
            ?: PHONE_ASPECT_DEFAULT
    }
    CropperScaffold(
        titleRes = R.string.phone_bg_picker_title,
        helpRes = R.string.phone_bg_picker_help,
        initialUri = initialUri,
        cropShape = RoundedCornerShape(28.dp),
        cropAspect = screenAspect,
        tag = TAG,
        onDismiss = onDismiss,
        onSaved = onSaved,
        onSave = { src, viewport, userScale, offsetX, offsetY ->
            val outputHeight = (PHONE_OUTPUT_WIDTH_PX / viewport.aspect()).roundToInt()
            persistCroppedPng(
                dest = File(context.cacheDir, phoneCropFileName(cacheKey)),
                src = src,
                viewport = viewport,
                userScale = userScale,
                offsetX = offsetX,
                offsetY = offsetY,
                outWidth = PHONE_OUTPUT_WIDTH_PX,
                outHeight = outputHeight,
                tag = TAG,
            )
        },
        overlay = { viewportWidth ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = PHONE_DIM_ALPHA)),
            )
            PhoneRingOverlay(
                timeText = timeText,
                labelText = labelText,
                awaitingWatch = false,
                onDismiss = null,
                onSnooze = null,
                showSnooze = true,
                scale = viewportWidth.value / PHONE_REFERENCE_WIDTH_DP,
                modifier = Modifier.align(Alignment.Center),
            )
        },
    )
}

private fun CropViewport.aspect(): Float = widthPx / heightPx

private fun phoneCropFileName(cacheKey: String): String {
    val safeKey = cacheKey.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "default" }
    return "phone_bg_${safeKey}_${System.currentTimeMillis()}.png"
}

private const val PHONE_REFERENCE_WIDTH_DP = 360f
private const val PHONE_OUTPUT_WIDTH_PX = 1080
private const val PHONE_DIM_ALPHA = 0.45f
private const val PHONE_ASPECT_DEFAULT = 9f / 16f
private const val PHONE_ASPECT_MIN = 0.45f
private const val PHONE_ASPECT_MAX = 0.75f
private const val TAG = "PhoneBgPicker"
