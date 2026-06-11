package com.kirkouski.gtwake.companion.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes the watch-side background image into the custom BGRA `.bin`
 * format the GT 6 Pro `<image>` element accepts. The HarmonyOS Lite
 * Wearable `<image>` element does **not** decode PNG/JPEG at runtime —
 * see memory:litewearable_images_and_files.md. The watch only consumes a
 * raw BGRA blob with an 8-byte header.
 *
 * **File layout** (little-endian):
 * ```
 *   offset  size  field
 *   0       2     magic "GA"           (0x47 0x41)
 *   2       2     version u16 = 1
 *   4       2     width  u16
 *   6       2     height u16
 *   8       w*h*4 BGRA pixel data, row-major, top-down
 * ```
 *
 * Total payload = 8 + w * h * 4 bytes. For the GT 6 Pro native
 * resolution (466 × 466) this is 868 KB — well within Wear Engine's
 * file-transfer limits (the storage cutoff documented at 128 B applies
 * only to `@system.storage`; `@system.file` accepts arbitrary blobs).
 *
 * The watch-side reader must:
 *   1. Validate magic + version (drop file on mismatch — could be a
 *      corrupted transfer from an old phone build).
 *   2. Read width/height. Reject anything outside [1, 512] as defense
 *      against malformed input.
 *   3. memcpy the remaining `w * h * 4` bytes straight into the image
 *      element's buffer.
 */
object WatchBackgroundEncoder {

    /** GT 6 Pro panel native resolution. See memory:gt6_hardware_constraints. */
    const val WATCH_BG_NATIVE_PX = 466

    /**
     * Encode the input bitmap to BGRA `.bin` at the target watch resolution.
     * The source is scaled into a [targetWidth] × [targetHeight] canvas. On a
     * [round] panel a circular alpha-clip fills the off-circle corners black
     * (the round hardware masks them anyway); a rectangular panel is encoded
     * full-bleed. Defaults reproduce the legacy GT6 behaviour (466 round).
     *
     * Returns true on success, false on any I/O failure. Logs the failure
     * reason; caller surfaces a Snackbar if needed.
     */
    @Suppress("TooGenericExceptionCaught")
    fun encodeFromCroppedBitmap(
        src: Bitmap,
        dest: File,
        targetWidth: Int = WATCH_BG_NATIVE_PX,
        targetHeight: Int = WATCH_BG_NATIVE_PX,
        round: Boolean = true,
    ): Boolean {
        return try {
            // The picker should already produce exactly targetWidth×targetHeight;
            // scale defensively if a caller hands us something else.
            val scaled = if (src.width == targetWidth && src.height == targetHeight) {
                src
            } else {
                src.scale(targetWidth, targetHeight)
            }
            writeBgraBin(scaled, dest, round)
            if (scaled !== src) scaled.recycle()
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "encodeFromCroppedBitmap failed: ${e.message}", e)
            false
        } catch (e: java.io.IOException) {
            Log.w(TAG, "encodeFromCroppedBitmap I/O failed: ${e.message}", e)
            false
        }
    }

    private fun writeBgraBin(src: Bitmap, dest: File, round: Boolean) {
        val w = src.width
        val h = src.height
        require(w in 1..MAX_DIM) { "width=$w out of range" }
        require(h in 1..MAX_DIM) { "height=$h out of range" }

        // Round panels get a circular alpha-clip — pixels outside the circle
        // become opaque black (the round hardware masks them anyway, but we
        // want predictable bytes so the file hashes stably). Rect panels are
        // encoded full-bleed (no mask).
        val masked = if (round) applyCircularMask(src, w, h) else src

        val totalBytes = HEADER_BYTES + w * h * BYTES_PER_PIXEL
        val buffer = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        // Magic "GA" = GT-Alarm.
        buffer.put('G'.code.toByte()).put('A'.code.toByte())
        buffer.putShort(VERSION.toShort())
        buffer.putShort(w.toShort())
        buffer.putShort(h.toShort())

        // Stream pixel rows into the buffer in BGRA order. getPixels gives
        // us ARGB ints; rearrange in place.
        val rowPixels = IntArray(w)
        for (y in 0 until h) {
            masked.getPixels(rowPixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val argb = rowPixels[x]
                val a = (argb ushr ALPHA_SHIFT) and BYTE_MASK
                val r = (argb ushr RED_SHIFT) and BYTE_MASK
                val g = (argb ushr GREEN_SHIFT) and BYTE_MASK
                val b = argb and BYTE_MASK
                buffer.put(b.toByte()).put(g.toByte()).put(r.toByte()).put(a.toByte())
            }
        }

        FileOutputStream(dest).use { it.write(buffer.array()) }
        if (masked !== src) masked.recycle()
    }

    /**
     * Returns a bitmap with the inscribed circle preserved + outside
     * pixels replaced with opaque black. Operates on a copy so the
     * caller's bitmap is not mutated.
     */
    private fun applyCircularMask(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = createBitmap(w, h)
        val canvas = Canvas(out)
        // Fill black baseline so the corners-outside-circle are predictable.
        canvas.drawColor(android.graphics.Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Draw the circle mask. Anything we paint AFTER this with
        // SRC_IN replaces only the circular interior.
        canvas.drawCircle(w / 2f, h / 2f, w.coerceAtMost(h) / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), paint)
        paint.xfermode = null
        return out
    }

    /**
     * Convenience: decode a PNG file at [pngPath] then encode the BGRA `.bin`
     * to [destBinPath] **at the default 466 round** target. This is the legacy
     * BGRA test path (`WatchBgTestEncoder` BIN format); for a panel-adaptive
     * `.bin`, call [encodeFromCroppedBitmap] with explicit targetWidth/
     * targetHeight/round (as the picker's persistCrop does).
     */
    fun encodePngToBin(pngPath: File, destBinPath: File): Boolean {
        val bitmap = BitmapFactory.decodeFile(pngPath.absolutePath)
        if (bitmap == null) {
            Log.w(TAG, "encodePngToBin: failed to decode $pngPath")
            return false
        }
        val ok = encodeFromCroppedBitmap(bitmap, destBinPath)
        bitmap.recycle()
        return ok
    }

    private const val TAG = "WatchBgEncoder"
    private const val HEADER_BYTES = 8
    private const val BYTES_PER_PIXEL = 4
    private const val VERSION = 1
    private const val MAX_DIM = 512
    private const val BYTE_MASK = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
