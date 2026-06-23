// Shared shell for the watch + phone background croppers. Both surfaces have
// an identical scaffold (pick image → pan/zoom in a shaped viewport → save a
// cropped bitmap); they differ ONLY in crop shape, viewport aspect, the
// preview overlay, and the save target. Those four differences are the
// parameters/slots below — everything else (state hoist, image loader, SAF
// launcher, header, viewport + transform math, button row, empty frame) lives
// here once. See WatchBackgroundPicker.kt / PhoneBackgroundPicker.kt for the
// thin per-target callers.
@file:Suppress("MatchingDeclarationName")

package com.kirkouski.gtwake.companion.ui.edit

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.ui.components.GtAccentButton
import com.kirkouski.gtwake.companion.ui.components.GtFloatingButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen modal cropper shell. The caller supplies:
 * - [cropShape] / [cropAspect]: the viewport shape + width:height ratio
 *   (CircleShape + 1.0 for a round watch; RoundedCornerShape + W:H for rect /
 *   phone).
 * - [overlay]: drawn on top of the image inside the crop box (watch UI mockup,
 *   phone ring overlay, or nothing) — receives the live viewport width for
 *   scaling.
 * - [onSave]: materializes the crop. Runs on Dispatchers.IO; returns the saved
 *   file (or null on failure). [onSaved] then fires with its `file://` URI.
 *
 * The pan/zoom math (cover-scale base + uniform user scale/translate, clamped
 * so image edges never enter the viewport) is identical for both surfaces and
 * lives in ImageCropper.kt (computeBaseScale / clampCropOffset / persistCroppedPng).
 */
// reason: LongMethod / CyclomaticComplexMethod / LongParameterList — this is
// the single shared cropper shell. It owns 4 user actions (pick/save/cancel/
// pan-zoom) + the hoisted transform state + 2 caller slots; the whole point of
// the extraction is to host that complexity ONCE rather than duplicate it
// across the watch + phone pickers. The 10 params are the genuine variation
// points (shape/aspect/overlay/save/strings/callbacks) — bundling them behind
// a data class would just move the field count, not reduce it.
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
internal fun CropperScaffold(
    titleRes: Int,
    helpRes: Int,
    initialUri: String?,
    cropShape: Shape,
    cropAspect: Float,
    tag: String,
    onDismiss: () -> Unit,
    onSaved: (uri: String) -> Unit,
    onSave: suspend (src: Bitmap, viewport: CropViewport, userScale: Float, offsetX: Float, offsetY: Float) -> File?,
    overlay: @Composable BoxScope.(viewportWidth: Dp) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUriString by remember { mutableStateOf(initialUri) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Pan/zoom state — additive on top of the base "cover" scale; reset when a
    // new image is picked. Offset is clamped on every transform so the image
    // can never reveal the cropped-out region.
    var userScale by remember(sourceUriString) { mutableFloatStateOf(1f) }
    var offsetX by remember(sourceUriString) { mutableFloatStateOf(0f) }
    var offsetY by remember(sourceUriString) { mutableFloatStateOf(0f) }
    // Captured viewport in px (may be non-square). Written by BoxWithConstraints
    // below; the Save handler reads it from outside that scope.
    var viewportState by remember { mutableStateOf(CropViewport(0f, 0f)) }

    LaunchedEffect(sourceUriString) {
        sourceBitmap?.recycle()
        sourceBitmap = null
        val uri = sourceUriString ?: return@LaunchedEffect
        // reason: InjectDispatcher — Compose helper, not a Hilt entry point;
        // threading @IoDispatcher through the picker tree would be cargo-cult.
        @Suppress("InjectDispatcher")
        sourceBitmap = withContext(Dispatchers.IO) {
            loadSampledBitmap(context = context, uriString = uri, tag = tag)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            persistImagePermission(context = context, uri = uri, tag = tag)
            sourceUriString = uri.toString()
            userScale = 1f
            offsetX = 0f
            offsetY = 0f
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.cancel),
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                text = stringResource(helpRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                val viewportWidthDp = minOf(maxWidth, maxHeight * cropAspect)
                val viewportHeightDp = viewportWidthDp / cropAspect
                val density = LocalDensity.current
                val viewport = CropViewport(
                    widthPx = with(density) { viewportWidthDp.toPx() },
                    heightPx = with(density) { viewportHeightDp.toPx() },
                )
                LaunchedEffect(viewport) { viewportState = viewport }
                val bmp = sourceBitmap
                if (bmp != null) {
                    @Suppress("DEPRECATION")
                    // reason: the 4-arg rememberTransformableState (with centroid)
                    // is a "zoom around point" UX upgrade, but we always zoom
                    // around viewport center so the centroid arg is ignored.
                    val transformState: TransformableState = rememberTransformableState { zoomChange, panChange, _ ->
                        val newScale = (userScale * zoomChange).coerceIn(USER_SCALE_MIN, USER_SCALE_MAX)
                        userScale = newScale
                        val (clampedX, clampedY) = clampCropOffset(
                            srcW = bmp.width,
                            srcH = bmp.height,
                            viewport = viewport,
                            userScale = newScale,
                            offsetX = offsetX + panChange.x,
                            offsetY = offsetY + panChange.y,
                        )
                        offsetX = clampedX
                        offsetY = clampedY
                    }
                    // ContentScale.Crop does the base "cover" math with correct
                    // aspect preservation; graphicsLayer applies only UNIFORM
                    // userScale + translate on top, so what the user sees equals
                    // what persistCroppedPng's inverse-map captures.
                    Box(
                        modifier = Modifier
                            .size(width = viewportWidthDp, height = viewportHeightDp)
                            .clip(cropShape)
                            .background(Color.Black)
                            .transformable(transformState),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = viewportWidthDp, height = viewportHeightDp)
                                .graphicsLayer {
                                    scaleX = userScale
                                    scaleY = userScale
                                    translationX = offsetX
                                    translationY = offsetY
                                },
                        )
                        overlay(viewportWidthDp)
                    }
                } else {
                    EmptyCropFrame(
                        viewportWidth = viewportWidthDp,
                        viewportHeight = viewportHeightDp,
                        shape = cropShape,
                        onPick = { pickLauncher.launch(arrayOf("image/*")) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GtFloatingButton(
                    onClick = { pickLauncher.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_image),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.watch_bg_picker_pick),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                GtAccentButton(
                    onClick = {
                        val bmp = sourceBitmap
                        val captured = viewportState
                        val viewportInvalid = captured.widthPx <= 0f || captured.heightPx <= 0f
                        if (bmp == null || saving || viewportInvalid) {
                            android.util.Log.w(
                                tag,
                                "cropper Save no-op: bmp=${bmp != null} saving=$saving " +
                                    "vp=${captured.widthPx}x${captured.heightPx}",
                            )
                            return@GtAccentButton
                        }
                        saving = true
                        scope.launch {
                            @Suppress("InjectDispatcher")
                            val out = withContext(Dispatchers.IO) {
                                onSave(bmp, captured, userScale, offsetX, offsetY)
                            }
                            saving = false
                            android.util.Log.i(tag, "cropper Save onSave returned=${out?.name ?: "null"}")
                            if (out != null) {
                                onSaved(Uri.fromFile(out).toString())
                                android.util.Log.i(tag, "cropper Save onSaved dispatched")
                            }
                        }
                    },
                    enabled = sourceBitmap != null && !saving &&
                        viewportState.widthPx > 0f && viewportState.heightPx > 0f,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/**
 * Empty-state placeholder in the crop [shape] (circle or rounded-rect) with a
 * centered "pick image" affordance, so the user reads the crop shape before
 * choosing an image. The whole frame is the tap target.
 */
@Composable
private fun EmptyCropFrame(
    viewportWidth: Dp,
    viewportHeight: Dp,
    shape: Shape,
    onPick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = viewportWidth, height = viewportHeight)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 2.dp, color = MaterialTheme.colorScheme.outline, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onPick) {
                Icon(
                    painter = painterResource(R.drawable.ic_image),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = Color.Unspecified,
                )
            }
            Text(
                text = stringResource(R.string.watch_bg_picker_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// User pinch-zoom range. 1× = cover (image edges just touch the viewport edge);
// 4× = max useful crop-in. Clamped at the transform callback so we never store
// an out-of-range value.
private const val USER_SCALE_MIN = 1.0f
private const val USER_SCALE_MAX = 4.0f
