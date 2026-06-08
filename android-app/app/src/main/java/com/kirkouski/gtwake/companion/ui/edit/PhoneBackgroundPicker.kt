// reason: mirrors WatchBackgroundPickerDialog but with a phone-shaped viewport
// and shared crop helpers. Keeping the phone-specific shell separate makes
// the two target surfaces obvious while the pan/zoom math stays centralized.
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
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
import com.kirkouski.gtwake.companion.ui.components.PhoneRingOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Suppress("LongMethod", "CyclomaticComplexMethod")
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
    val scope = rememberCoroutineScope()
    var sourceUriString by remember { mutableStateOf(initialUri) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var userScale by remember(sourceUriString) { mutableFloatStateOf(1f) }
    var offsetX by remember(sourceUriString) { mutableFloatStateOf(0f) }
    var offsetY by remember(sourceUriString) { mutableFloatStateOf(0f) }
    var viewportState by remember { mutableStateOf(CropViewport(0f, 0f)) }

    LaunchedEffect(sourceUriString) {
        sourceBitmap?.recycle()
        sourceBitmap = null
        val uri = sourceUriString ?: return@LaunchedEffect
        @Suppress("InjectDispatcher")
        sourceBitmap = withContext(Dispatchers.IO) {
            loadSampledBitmap(context = context, uriString = uri, tag = TAG)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            persistImagePermission(context = context, uri = uri, tag = TAG)
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
                    text = stringResource(R.string.phone_bg_picker_title),
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
                text = stringResource(R.string.phone_bg_picker_help),
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
                val screenAspect = remember(windowInfo.containerSize) {
                    val size = windowInfo.containerSize
                    (size.width.toFloat() / size.height.toFloat())
                        .takeIf { it.isFinite() && it > 0f }
                        ?.coerceIn(PHONE_ASPECT_MIN, PHONE_ASPECT_MAX)
                        ?: PHONE_ASPECT_DEFAULT
                }
                val viewportWidthDp = minOf(maxWidth, maxHeight * screenAspect)
                val viewportHeightDp = viewportWidthDp / screenAspect
                val density = LocalDensity.current
                val viewport = CropViewport(
                    widthPx = with(density) { viewportWidthDp.toPx() },
                    heightPx = with(density) { viewportHeightDp.toPx() },
                )
                LaunchedEffect(viewport) { viewportState = viewport }
                val bmp = sourceBitmap
                if (bmp != null) {
                    PhoneCropViewport(
                        bitmap = bmp,
                        viewportWidth = viewportWidthDp,
                        viewportHeight = viewportHeightDp,
                        viewport = viewport,
                        timeText = timeText,
                        labelText = labelText,
                        userScale = userScale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        onTransform = { newScale, newOffsetX, newOffsetY ->
                            userScale = newScale
                            offsetX = newOffsetX
                            offsetY = newOffsetY
                        },
                    )
                } else {
                    EmptyPhoneFrame(
                        viewportWidth = viewportWidthDp,
                        viewportHeight = viewportHeightDp,
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
                    Text(stringResource(R.string.watch_bg_picker_pick))
                }
                GtAccentButton(
                    onClick = {
                        val bmp = sourceBitmap ?: return@GtAccentButton
                        if (saving || viewportState.widthPx <= 0f || viewportState.heightPx <= 0f) {
                            return@GtAccentButton
                        }
                        saving = true
                        val capturedViewport = viewportState
                        val outputHeight = (PHONE_OUTPUT_WIDTH_PX / capturedViewport.aspect()).roundToInt()
                        scope.launch {
                            @Suppress("InjectDispatcher")
                            val out = withContext(Dispatchers.IO) {
                                persistCroppedPng(
                                    dest = File(context.cacheDir, phoneCropFileName(cacheKey)),
                                    src = bmp,
                                    viewport = capturedViewport,
                                    userScale = userScale,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                    outWidth = PHONE_OUTPUT_WIDTH_PX,
                                    outHeight = outputHeight,
                                    tag = TAG,
                                )
                            }
                            saving = false
                            if (out != null) onSaved(Uri.fromFile(out).toString())
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

@Suppress("LongParameterList")
@Composable
private fun PhoneCropViewport(
    bitmap: Bitmap,
    viewportWidth: Dp,
    viewportHeight: Dp,
    viewport: CropViewport,
    timeText: String,
    labelText: String,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransform: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    @Suppress("DEPRECATION")
    val transformState: TransformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (userScale * zoomChange).coerceIn(USER_SCALE_MIN, USER_SCALE_MAX)
        val (clampedX, clampedY) = clampCropOffset(
            srcW = bitmap.width,
            srcH = bitmap.height,
            viewport = viewport,
            userScale = newScale,
            offsetX = offsetX + panChange.x,
            offsetY = offsetY + panChange.y,
        )
        onTransform(newScale, clampedX, clampedY)
    }
    Box(
        modifier = Modifier
            .size(width = viewportWidth, height = viewportHeight)
            .clip(shape)
            .background(Color.Black)
            .transformable(transformState),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = viewportWidth, height = viewportHeight)
                .graphicsLayer {
                    scaleX = userScale
                    scaleY = userScale
                    translationX = offsetX
                    translationY = offsetY
                },
        )
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
    }
}

@Composable
private fun EmptyPhoneFrame(
    viewportWidth: Dp,
    viewportHeight: Dp,
    onPick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
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
private const val USER_SCALE_MIN = 1.0f
private const val USER_SCALE_MAX = 4.0f
private const val TAG = "PhoneBgPicker"
