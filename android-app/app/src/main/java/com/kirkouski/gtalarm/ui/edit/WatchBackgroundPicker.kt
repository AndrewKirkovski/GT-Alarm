// reason: file groups the picker dialog Composable + small helpers
// (loadSampledBitmap, computeCroppedBitmap, persistCrop, drawWatchOverlay).
// Detekt's MatchingDeclarationName checks the FIRST top-level declaration,
// which here is `WatchBackgroundPickerDialog` (the public Composable).
// Splitting per-declaration would push tightly-coupled crop math across
// more files for no readability gain.
@file:Suppress("MatchingDeclarationName")

package com.kirkouski.gtalarm.ui.edit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.wear.WatchBackgroundEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Full-screen modal cropper for the per-alarm watch background. The user
 * pinch-zooms / pans an image inside a circular viewport with a watch-UI
 * overlay (time + Dismiss/Snooze buttons) drawn on top so they can preview
 * how the crop will look on the watch face.
 *
 * Output: 466 × 466 PNG written to `cacheDir/watch_bg_<alarmId>.png` plus
 * the parallel BGRA `.bin` (`watch_bg_<alarmId>.bin`) consumed by
 * [WatchBackgroundEncoder]. The PNG is re-loaded next time the picker opens
 * so the user sees their prior crop (the .bin is one-way).
 *
 * Caller passes [alarmId] and a callback that receives the resulting PNG
 * `file://` URI on Save (no callback fires on cancel). Upload to the watch
 * is **not** done here — it flows via the same flushPendingToWatch path
 * as every other field edit.
 *
 * # Cropping math
 *
 * The picker's transformable surface is a square of side `viewportPx`. The
 * source bitmap is laid out at "cover" scale (the shorter image edge
 * equals `viewportPx`); the user pinch-zooms (multiplier `userScale` ≥ 1)
 * and pans (`offsetX`, `offsetY` in view-space pixels), both stored in
 * MutableState so recomposition reflects the live transform.
 *
 * On Save, we materialize the visible viewport square into a 466 × 466
 * output bitmap. The encoder masks the inscribed circle internally.
 *
 * The output dimension is independent of `viewportPx` because the
 * src→view transform is dimensionally homogeneous: any view-space
 * position scales to the same destination-space position when we rebase
 * by (outputDim / viewportPx). We pass `viewportPx` from the Composable's
 * BoxWithConstraints scope into [persistCrop] so the inverse-map uses
 * the same reference frame the user saw.
 *
 * Sampling: the source bitmap is decoded with `inSampleSize` tuned for a
 * 1024 px max edge so a 50 MP photo doesn't OOM the picker viewport.
 */
// reason: top-level Composable that owns a full-screen picker UI is
// inherently long because it routes 4 user actions (Pick, Save, Cancel,
// pan/zoom), 3 derived state pieces (scale, offset, source bitmap), and
// the cropping pipeline. Splitting would just smear state hoisting across
// more files. Mirrors the suppression on AlarmEditScreen.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun WatchBackgroundPickerDialog(
    alarmId: Long,
    initialUri: String?,
    onDismiss: () -> Unit,
    onSaved: (uri: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUriString by remember { mutableStateOf(initialUri) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Pan/zoom state — additive on top of the base "cover" scale. Initial
    // values: 1× zoom and 0,0 offset = exactly cover. Offset is clamped on
    // every transformable callback so the image can never reveal the
    // cropped-out region.
    var userScale by remember(sourceUriString) { mutableFloatStateOf(1f) }
    var offsetX by remember(sourceUriString) { mutableFloatStateOf(0f) }
    var offsetY by remember(sourceUriString) { mutableFloatStateOf(0f) }
    // Captured viewport in px. The BoxWithConstraints below writes this
    // when laid out; the Save handler reads it from outside that scope.
    var viewportPxState by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(sourceUriString) {
        sourceBitmap?.recycle()
        sourceBitmap = null
        val uri = sourceUriString ?: return@LaunchedEffect
        @Suppress("InjectDispatcher")
        sourceBitmap = withContext(Dispatchers.IO) {
            loadSampledBitmap(context, uri)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            persistImagePermission(context, uri)
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
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.watch_bg_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.watch_bg_picker_help),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentAlignment = Alignment.Center,
            ) {
                val viewportDp = minOf(maxWidth, maxHeight)
                val density = LocalDensity.current
                val viewportPx = with(density) { viewportDp.toPx() }
                // Push the viewport-px upward so the Save handler (which
                // lives outside this BoxWithConstraints scope) can use the
                // exact value the user is seeing.
                LaunchedEffect(viewportPx) { viewportPxState = viewportPx }
                val bmp = sourceBitmap
                if (bmp != null) {
                    val baseScale = computeBaseScale(bmp.width.toFloat(), bmp.height.toFloat(), viewportPx)
                    val totalScale = baseScale * userScale
                    @Suppress("DEPRECATION")
                    // reason: the new 4-arg `rememberTransformableState` (with
                    // centroid) is a UX upgrade for "zoom around point" but
                    // we always zoom around viewport center, so the centroid
                    // parameter would be ignored. Stick with the 3-arg
                    // overload until the Compose version makes it disappear.
                    val transformState: TransformableState = rememberTransformableState { zoomChange, panChange, _ ->
                        val newScale = (userScale * zoomChange).coerceIn(USER_SCALE_MIN, USER_SCALE_MAX)
                        userScale = newScale
                        val nextOffsetX = offsetX + panChange.x
                        val nextOffsetY = offsetY + panChange.y
                        // Clamp so the image edges never enter the
                        // viewport. With base "cover" scale + user zoom,
                        // the half-overflow on each axis is
                        // (imageOnScreen - viewport) / 2.
                        val totalScaleNext = baseScale * newScale
                        val overflowX = (bmp.width * totalScaleNext - viewportPx) / 2f
                        val overflowY = (bmp.height * totalScaleNext - viewportPx) / 2f
                        offsetX = nextOffsetX.coerceIn(-overflowX.coerceAtLeast(0f), overflowX.coerceAtLeast(0f))
                        offsetY = nextOffsetY.coerceIn(-overflowY.coerceAtLeast(0f), overflowY.coerceAtLeast(0f))
                    }
                    Box(
                        modifier = Modifier
                            .size(viewportDp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .transformable(transformState),
                    ) {
                        // Bottom: the image, scaled + translated under
                        // pinch-zoom. We use Image with explicit pixel
                        // bounds via graphicsLayer scaleX/scaleY rather
                        // than ContentScale so the geometry matches
                        // exactly what persistCrop's inverse-map sees.
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(viewportDp)
                                .graphicsLayer {
                                    // base scale fills the viewport; user
                                    // zoom is multiplicative on top. The
                                    // composable shows the source bitmap
                                    // sized to viewportPx, so its draw
                                    // dimensions match bmp.width *
                                    // viewportPx/bmp.width = viewportPx
                                    // for the x axis at baseScale. The
                                    // additional scaling = totalScale /
                                    // (viewportPx / bmpDim) = userScale *
                                    // baseScale * bmpDim / viewportPx.
                                    val drawScaleX = totalScale * bmp.width / viewportPx
                                    val drawScaleY = totalScale * bmp.height / viewportPx
                                    scaleX = drawScaleX
                                    scaleY = drawScaleY
                                    translationX = offsetX
                                    translationY = offsetY
                                },
                        )
                        CircularMaskCanvas(modifier = Modifier.fillMaxSize())
                        WatchRingOverlayCanvas(modifier = Modifier.fillMaxSize())
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(viewportDp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.watch_bg_picker_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                OutlinedButton(
                    onClick = { pickLauncher.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.watch_bg_picker_pick))
                }
                Button(
                    onClick = {
                        val bmp = sourceBitmap ?: return@Button
                        if (saving) return@Button
                        val capturedViewport = viewportPxState
                        if (capturedViewport <= 0f) return@Button
                        saving = true
                        scope.launch {
                            @Suppress("InjectDispatcher")
                            val out = withContext(Dispatchers.IO) {
                                persistCrop(
                                    context = context,
                                    alarmId = alarmId,
                                    src = bmp,
                                    viewportPx = capturedViewport,
                                    userScale = userScale,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                )
                            }
                            saving = false
                            if (out != null) onSaved(Uri.fromFile(out).toString())
                        }
                    },
                    enabled = sourceBitmap != null && !saving && viewportPxState > 0f,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
            Spacer(modifier = Modifier.size(4.dp))
        }
    }
}

/**
 * Canvas overlay drawing a semitransparent darkened region OUTSIDE the
 * inscribed circle. Achieves the "punch a circular hole through a dark
 * scrim" look using BlendMode.Clear after filling.
 */
@Composable
private fun CircularMaskCanvas(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val side = minOf(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = side / 2f
        drawRect(color = Color.Black.copy(alpha = SCRIM_ALPHA))
        drawCircle(
            color = Color.Transparent,
            radius = r,
            center = Offset(cx, cy),
            blendMode = BlendMode.Clear,
        )
        drawCircle(
            color = Color.White.copy(alpha = RING_ALPHA),
            radius = r - 1f,
            center = Offset(cx, cy),
            style = Stroke(width = 2f),
        )
    }
}

/**
 * Mimics the watch's ring screen UI (`watch-app/.../pages/ring/ring.hml`)
 * drawn at semi-transparent so the underlying image shows through.
 * Proportions mirror ring.css:
 *   - time: 88 px in CSS, scaled to viewport
 *   - buttons: 180 × 88 px, gap 28 px (scaled)
 */
// reason: single linear Canvas DSL with measured text + 2 button rects;
// extracting helpers would scatter the geometry constants across files.
@Suppress("LongMethod")
@Composable
private fun WatchRingOverlayCanvas(modifier: Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val side = minOf(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val titleStyle = TextStyle(
            color = Color.White.copy(alpha = OVERLAY_ALPHA),
            fontSize = TextUnit(side * TITLE_FONT_RATIO, TextUnitType.Sp),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        val timeStyle = TextStyle(
            color = Color.White.copy(alpha = OVERLAY_ALPHA),
            fontSize = TextUnit(side * TIME_FONT_RATIO, TextUnitType.Sp),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        val btnTextStyle = TextStyle(
            color = Color.White.copy(alpha = OVERLAY_ALPHA),
            fontSize = TextUnit(side * BTN_TEXT_FONT_RATIO, TextUnitType.Sp),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        val title = tm.measure(AnnotatedString(PREVIEW_TITLE), titleStyle)
        drawText(
            textLayoutResult = title,
            topLeft = Offset(cx - title.size.width / 2f, cy - side * TITLE_OFFSET_RATIO),
        )
        val timeLayout = tm.measure(AnnotatedString(PREVIEW_TIME), timeStyle)
        drawText(
            textLayoutResult = timeLayout,
            topLeft = Offset(cx - timeLayout.size.width / 2f, cy - timeLayout.size.height / 2f),
        )
        val btnWidth = side * BTN_WIDTH_RATIO
        val btnHeight = side * BTN_HEIGHT_RATIO
        val btnGap = side * BTN_GAP_RATIO
        val btnRowY = cy + side * BTN_ROW_OFFSET_RATIO
        val totalRowWidth = btnWidth * 2 + btnGap
        val leftX = cx - totalRowWidth / 2f
        drawRoundRect(
            color = Color(SNOOZE_BG_COLOR).copy(alpha = OVERLAY_BTN_ALPHA),
            topLeft = Offset(leftX, btnRowY),
            size = Size(btnWidth, btnHeight),
            cornerRadius = CornerRadius(btnHeight / 2f),
        )
        val snoozeLayout = tm.measure(AnnotatedString(PREVIEW_SNOOZE), btnTextStyle)
        drawText(
            textLayoutResult = snoozeLayout,
            topLeft = Offset(
                leftX + btnWidth / 2f - snoozeLayout.size.width / 2f,
                btnRowY + btnHeight / 2f - snoozeLayout.size.height / 2f,
            ),
        )
        val rightX = leftX + btnWidth + btnGap
        drawRoundRect(
            color = Color(DISMISS_BG_COLOR).copy(alpha = OVERLAY_BTN_ALPHA),
            topLeft = Offset(rightX, btnRowY),
            size = Size(btnWidth, btnHeight),
            cornerRadius = CornerRadius(btnHeight / 2f),
        )
        val dismissLayout = tm.measure(AnnotatedString(PREVIEW_DISMISS), btnTextStyle)
        drawText(
            textLayoutResult = dismissLayout,
            topLeft = Offset(
                rightX + btnWidth / 2f - dismissLayout.size.width / 2f,
                btnRowY + btnHeight / 2f - dismissLayout.size.height / 2f,
            ),
        )
    }
}

/**
 * Decode the URI's bitmap with `inSampleSize` tuned for a 1024 px max
 * edge. Returns null on any failure (logged with URI prefix). The picker
 * never displays the full original; downsampling first avoids OOM on
 * modern 50 MP camera photos.
 */
@Suppress("ReturnCount")
private fun loadSampledBitmap(context: Context, uriString: String): Bitmap? {
    return runCatching {
        val uri = uriString.toUri()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        var halfW = opts.outWidth
        var halfH = opts.outHeight
        while (halfW / 2 >= MAX_DECODE_EDGE && halfH / 2 >= MAX_DECODE_EDGE) {
            halfW /= 2
            halfH /= 2
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOpts)
        }
    }.onFailure { e ->
        Log.w(TAG, "loadSampledBitmap failed for $uriString: ${e::class.simpleName}: ${e.message}")
    }.getOrNull()
}

/**
 * Materialize the user's crop into a 466 × 466 PNG + parallel BGRA `.bin`
 * in [Context.getCacheDir]. Returns the PNG file on success, null on
 * failure.
 *
 * Math: the view-space transform places source pixel `s` at view-space
 * position
 *   v = (viewportPx - bmpDim * totalScale) / 2 + offset + s * totalScale
 * Inverting:
 *   s = (v - imgOrigin_view) / totalScale
 * The OUTPUT bitmap is at scale (outDim / viewportPx) of the viewport:
 *   v = outV * (viewportPx / outDim)
 * So
 *   s = (outV * (viewportPx / outDim) - imgOrigin_view) / totalScale
 * Evaluating at outV = 0 and outV = outDim gives srcLeft, srcRight (same
 * for Y). We coerce to the bitmap bounds.
 */
// reason: 7 params for an internal helper that captures the picker's full
// crop transform (bitmap + viewport + user scale + 2D offset) plus the
// destination context + alarm id. Bundling into a `CropParams` data class
// would just push the same field count behind a wrapper — the file is
// the only caller.
@Suppress("LongParameterList")
private fun persistCrop(
    context: Context,
    alarmId: Long,
    src: Bitmap,
    viewportPx: Float,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
): File? {
    return runCatching {
        val outDim = WatchBackgroundEncoder.WATCH_BG_NATIVE_PX
        val output = createBitmap(outDim, outDim)
        val canvas = AndroidCanvas(output)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val srcRect = computeSourceRect(
            srcW = src.width,
            srcH = src.height,
            viewportPx = viewportPx,
            userScale = userScale,
            offsetX = offsetX,
            offsetY = offsetY,
        )
        canvas.drawBitmap(src, srcRect, Rect(0, 0, outDim, outDim), paint)
        val pngFile = File(context.cacheDir, "watch_bg_$alarmId.png")
        FileOutputStream(pngFile).use { out ->
            output.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        }
        val binFile = File(context.cacheDir, "watch_bg_$alarmId.bin")
        val binOk = WatchBackgroundEncoder.encodeFromCroppedBitmap(output, binFile)
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

/**
 * Closed-form: given the source bitmap dimensions, the viewport-px the
 * user saw, plus their `userScale`/`offsetX`/`offsetY`, compute the
 * rectangle in source-pixel coordinates that maps to the visible
 * viewport square.
 */
private fun computeSourceRect(
    srcW: Int,
    srcH: Int,
    viewportPx: Float,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
): Rect {
    val baseScale = computeBaseScale(srcW.toFloat(), srcH.toFloat(), viewportPx)
    val totalScale = baseScale * userScale
    val imgWView = srcW * totalScale
    val imgHView = srcH * totalScale
    // Image top-left in VIEW space (centered, then translated by user pan).
    val imgOriginXView = (viewportPx - imgWView) / 2f + offsetX
    val imgOriginYView = (viewportPx - imgHView) / 2f + offsetY
    // Inverse map view (0..viewportPx) to source pixels.
    val srcLeft = ((-imgOriginXView) / totalScale).coerceIn(0f, srcW.toFloat())
    val srcTop = ((-imgOriginYView) / totalScale).coerceIn(0f, srcH.toFloat())
    val srcRight = ((viewportPx - imgOriginXView) / totalScale).coerceIn(0f, srcW.toFloat())
    val srcBottom = ((viewportPx - imgOriginYView) / totalScale).coerceIn(0f, srcH.toFloat())
    return Rect(srcLeft.toInt(), srcTop.toInt(), srcRight.toInt(), srcBottom.toInt())
}

/**
 * Cover-scale: the multiplier that makes the image fill `viewportPx`
 * such that the shorter edge equals `viewportPx`. The longer edge
 * overflows symmetrically. Same math as ContentScale.Crop.
 */
private fun computeBaseScale(srcW: Float, srcH: Float, viewportPx: Float): Float {
    val sx = viewportPx / srcW
    val sy = viewportPx / srcH
    return maxOf(sx, sy)
}

/**
 * Take persistable read permission on the picked URI. Wrapped in
 * `runCatching` because some SAF providers throw SecurityException even
 * though the grant was just issued — the URI is still readable
 * immediately and we only lose the cross-restart guarantee. The cached
 * `cacheDir/watch_bg_<id>.png` is the load-bearing artifact across
 * sessions; the original SAF URI is consulted only during the picker
 * session.
 */
private fun persistImagePermission(context: Context, uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
}

// Sampling cap for the picker viewport. 1024 px max edge: enough for a
// smooth pan/zoom preview without OOM on 50 MP photos. Final crop is
// resampled to 466 × 466 anyway, so further detail isn't useful.
private const val MAX_DECODE_EDGE = 1024
private const val PNG_QUALITY = 95
// User pinch-zoom range. 1× = cover (image edges just touch the viewport
// edge). 4× = max useful crop-in. Clamped at the transformable callback
// so we never store an out-of-range value.
private const val USER_SCALE_MIN = 1.0f
private const val USER_SCALE_MAX = 4.0f
// Overlay tunings (proportional to viewport side).
private const val SCRIM_ALPHA = 0.55f
private const val RING_ALPHA = 0.6f
private const val OVERLAY_ALPHA = 0.6f
private const val OVERLAY_BTN_ALPHA = 0.45f
private const val TIME_FONT_RATIO = 0.18f
private const val TITLE_FONT_RATIO = 0.06f
private const val BTN_TEXT_FONT_RATIO = 0.06f
private const val TITLE_OFFSET_RATIO = 0.22f
private const val BTN_WIDTH_RATIO = 0.32f
private const val BTN_HEIGHT_RATIO = 0.15f
private const val BTN_GAP_RATIO = 0.06f
private const val BTN_ROW_OFFSET_RATIO = 0.18f
// Snooze pill = #374151; Dismiss pill = #B91C1C (matches ring.css).
private const val SNOOZE_BG_COLOR = 0xFF374151L
private const val DISMISS_BG_COLOR = 0xFFB91C1CL
private const val PREVIEW_TIME = "07:23"
private const val PREVIEW_TITLE = "Alarm"
private const val PREVIEW_SNOOZE = "Snooze"
private const val PREVIEW_DISMISS = "Dismiss"
private const val TAG = "WatchBgPicker"
