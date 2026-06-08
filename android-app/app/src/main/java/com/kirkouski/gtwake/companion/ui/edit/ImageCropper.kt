@file:Suppress("MatchingDeclarationName")

package com.kirkouski.gtwake.companion.ui.edit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor

internal data class CropViewport(
    val widthPx: Float,
    val heightPx: Float,
)

internal fun loadSampledBitmap(
    context: Context,
    uriString: String,
    tag: String,
    maxDecodeEdge: Int = DEFAULT_MAX_DECODE_EDGE,
): Bitmap? {
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
        while (halfW / 2 >= maxDecodeEdge && halfH / 2 >= maxDecodeEdge) {
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
        val scheme = runCatching { uriString.toUri().scheme }.getOrNull() ?: "unknown"
        Log.w(tag, "loadSampledBitmap failed scheme=$scheme: ${e::class.simpleName}: ${e.message}")
    }.getOrNull()
}

internal fun persistImagePermission(context: Context, uri: Uri, tag: String) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        .onFailure { Log.w(tag, "takePersistableUriPermission failed: ${it::class.simpleName}: ${it.message}") }
}

internal fun computeBaseScale(
    srcW: Float,
    srcH: Float,
    viewportW: Float,
    viewportH: Float,
): Float = maxOf(viewportW / srcW, viewportH / srcH)

internal fun clampCropOffset(
    srcW: Int,
    srcH: Int,
    viewport: CropViewport,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
): Pair<Float, Float> {
    val totalScale = computeBaseScale(
        srcW = srcW.toFloat(),
        srcH = srcH.toFloat(),
        viewportW = viewport.widthPx,
        viewportH = viewport.heightPx,
    ) * userScale
    val overflowX = (srcW * totalScale - viewport.widthPx) / 2f
    val overflowY = (srcH * totalScale - viewport.heightPx) / 2f
    val clampedX = offsetX.coerceIn(-overflowX.coerceAtLeast(0f), overflowX.coerceAtLeast(0f))
    val clampedY = offsetY.coerceIn(-overflowY.coerceAtLeast(0f), overflowY.coerceAtLeast(0f))
    return clampedX to clampedY
}

@Suppress("LongParameterList")
internal fun persistCroppedPng(
    dest: File,
    src: Bitmap,
    viewport: CropViewport,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
    outWidth: Int,
    outHeight: Int,
    tag: String,
): File? {
    return runCatching {
        val output = createBitmap(outWidth, outHeight)
        val canvas = AndroidCanvas(output)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val srcRect = computeSourceRect(
            srcW = src.width,
            srcH = src.height,
            viewport = viewport,
            userScale = userScale,
            offsetX = offsetX,
            offsetY = offsetY,
        )
        canvas.drawBitmap(src, srcRect, Rect(0, 0, outWidth, outHeight), paint)
        FileOutputStream(dest).use { out ->
            output.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        }
        output.recycle()
        dest
    }.onFailure { e ->
        Log.w(tag, "persistCroppedPng failed ${dest.name}: ${e::class.simpleName}: ${e.message}")
    }.getOrNull()
}

private fun computeSourceRect(
    srcW: Int,
    srcH: Int,
    viewport: CropViewport,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
): Rect {
    val baseScale = computeBaseScale(srcW.toFloat(), srcH.toFloat(), viewport.widthPx, viewport.heightPx)
    val totalScale = baseScale * userScale
    val imgWView = srcW * totalScale
    val imgHView = srcH * totalScale
    val imgOriginXView = (viewport.widthPx - imgWView) / 2f + offsetX
    val imgOriginYView = (viewport.heightPx - imgHView) / 2f + offsetY
    val srcLeft = ((-imgOriginXView) / totalScale).coerceIn(0f, srcW.toFloat())
    val srcTop = ((-imgOriginYView) / totalScale).coerceIn(0f, srcH.toFloat())
    val srcRight = ((viewport.widthPx - imgOriginXView) / totalScale).coerceIn(0f, srcW.toFloat())
    val srcBottom = ((viewport.heightPx - imgOriginYView) / totalScale).coerceIn(0f, srcH.toFloat())
    val left = floor(srcLeft).toInt().coerceIn(0, srcW - 1)
    val top = floor(srcTop).toInt().coerceIn(0, srcH - 1)
    val right = ceil(srcRight).toInt().coerceIn(left + 1, srcW)
    val bottom = ceil(srcBottom).toInt().coerceIn(top + 1, srcH)
    return Rect(left, top, right, bottom)
}

private const val DEFAULT_MAX_DECODE_EDGE = 1024
private const val PNG_QUALITY = 95
