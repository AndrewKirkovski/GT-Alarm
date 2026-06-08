package com.kirkouski.gtwake.companion.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream

/**
 * TEST-ONLY. Hardware experiment to find which image format the GT 6 Pro
 * Lite Wearable `<image>` element can render from a P2P-received *runtime*
 * file. memory:litewearable_images_and_files claims only the custom BGRA
 * `.bin` works; this probes whether a plain JPG/PNG renders — which would
 * let us drop the whole [WatchBackgroundEncoder] pipeline.
 *
 * Escalation order, one build per step — flip [ACTIVE] and rebuild:
 *   JPG_FULL -> JPG_SMALL -> PNG_FULL -> PNG_SMALL -> BIN
 *
 * Delete this file (and revert [AlarmRepository.uploadDefaultWatchBackground])
 * once the answer is known.
 */
object WatchBgTestEncoder {

    enum class Format { JPG_FULL, JPG_SMALL, PNG_FULL, PNG_SMALL, BIN }

    /** Current hardware-test format. Bump per build per the order above. */
    val ACTIVE = Format.PNG_FULL

    /**
     * Produce the file to send for [ACTIVE], derived from the picker's
     * 466x466 source PNG [srcPng]. The file name embeds the format name
     * (e.g. `watch_bg_jpg_small.jpg`) so the watch's `bg:` diag line
     * shows which build is live. Returns null on decode/encode failure.
     */
    fun prepare(srcPng: File, cacheDir: File): File? {
        if (ACTIVE == Format.BIN) {
            val bin = File(cacheDir, "watch_bg_bin.bin")
            return if (WatchBackgroundEncoder.encodePngToBin(srcPng, bin)) bin else null
        }
        return encodeRaster(srcPng, cacheDir)
    }

    private fun destName(): String =
        "watch_bg_" + ACTIVE.name.lowercase() + "." + extension()

    @Suppress("TooGenericExceptionCaught")
    // reason: TooGenericExceptionCaught — Bitmap.compress / FileOutputStream
    //   surface OutOfMemoryError-adjacent RuntimeExceptions plus IOException
    //   across OEM image codecs; this is throwaway test code and any failure
    //   should degrade to "no background", never crash the upload coroutine.
    private fun encodeRaster(srcPng: File, cacheDir: File): File? {
        val bmp = BitmapFactory.decodeFile(srcPng.absolutePath) ?: run {
            Log.w(TAG, "encodeRaster: decode failed for ${srcPng.name}")
            return null
        }
        return try {
            val scaled = if (isSmall()) bmp.scale(SMALL_PX, SMALL_PX) else bmp
            val dest = File(cacheDir, destName())
            FileOutputStream(dest).use { scaled.compress(compressFormat(), JPEG_QUALITY, it) }
            if (scaled !== bmp) scaled.recycle()
            Log.i(TAG, "encodeRaster fmt=$ACTIVE -> ${dest.name} ${dest.length()}B")
            dest
        } catch (e: RuntimeException) {
            Log.w(TAG, "encodeRaster failed: ${e.message}", e)
            null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "encodeRaster I/O failed: ${e.message}", e)
            null
        } finally {
            bmp.recycle()
        }
    }

    private fun isSmall(): Boolean =
        ACTIVE == Format.JPG_SMALL || ACTIVE == Format.PNG_SMALL

    private fun extension(): String =
        if (ACTIVE == Format.JPG_FULL || ACTIVE == Format.JPG_SMALL) "jpg" else "png"

    private fun compressFormat(): Bitmap.CompressFormat =
        if (ACTIVE == Format.JPG_FULL || ACTIVE == Format.JPG_SMALL) {
            Bitmap.CompressFormat.JPEG
        } else {
            Bitmap.CompressFormat.PNG
        }

    private const val TAG = "WatchBgTest"
    private const val SMALL_PX = 200
    private const val JPEG_QUALITY = 90
}
