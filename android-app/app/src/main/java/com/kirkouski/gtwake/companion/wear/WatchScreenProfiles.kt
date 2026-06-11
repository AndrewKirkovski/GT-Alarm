package com.kirkouski.gtwake.companion.wear

/**
 * How the watch-background cropper + encoder should size and shape an image
 * for a given watch panel. Derived from the connected watch's reported screen
 * (the `watch_screen` reply persisted in SettingsStore) via [WatchScreenProfiles.resolve].
 *
 * @property outWidth  output bitmap width  (px) — what the encoder/PNG produces
 * @property outHeight output bitmap height (px)
 * @property round     circular crop (square output, hardware-masked) vs rectangular
 * @property showOverlay draw the round watch-UI preview overlay (recognized round only)
 * @property known     the reported resolution matched a recognized profile
 */
data class WatchCropProfile(
    val outWidth: Int,
    val outHeight: Int,
    val round: Boolean,
    val showOverlay: Boolean,
    val known: Boolean,
)

/**
 * Canonical map of supported Lite Wearable panels → crop behaviour. The full
 * device matrix + the cropper-gating rule live in `docs/watch-resolutions.md`;
 * keep the two in lock-step.
 *
 * The on-watch layout itself is resolution-agnostic (it reads `getInfo()` and
 * computes px); this table is only used phone-side to decide the cropper
 * viewport/overlay. An UNRECOGNIZED runtime resolution still crops to the
 * reported aspect but draws NO overlay — we can't promise an accurate preview
 * for a panel we haven't validated.
 */
object WatchScreenProfiles {

    /** GT-series round panel (GT 3/4/5/6, Runner, Cyber, Collector). */
    const val GT_ROUND_PX = 466

    private data class Known(val w: Int, val h: Int, val shape: String)

    // Recognized panels. Every current AppGallery round watch is 466; legacy
    // round sizes are listed so small-round still maps as "known". FIT is
    // portrait (W < H). See docs/watch-resolutions.md.
    private val KNOWN = listOf(
        Known(GT_ROUND_PX, GT_ROUND_PX, SHAPE_CIRCLE), // GT 3/4/5/6, Runner, Cyber, Collector
        Known(454, 454, SHAPE_CIRCLE),                 // GT 2 / GT 2 Pro (legacy)
        Known(390, 390, SHAPE_CIRCLE),                 // GT / GT 2 42 mm (legacy)
        Known(408, 480, SHAPE_RECT),                   // FIT 3 / FIT 4 / FIT 5 / FIT 5 Pro
        Known(336, 480, SHAPE_RECT),                   // FIT 2
    )

    /** No watch has reported yet → GT6 round (the safe, overlay-on default). */
    val DEFAULT = WatchCropProfile(
        outWidth = GT_ROUND_PX,
        outHeight = GT_ROUND_PX,
        round = true,
        showOverlay = true,
        known = true,
    )

    /**
     * Resolve the cropper profile for a reported watch screen.
     * - `width <= 0` or `height <= 0` (never reported / garbage) → [DEFAULT].
     * - recognized **round** → circular crop, square output, overlay ON.
     * - recognized **rect** → rounded-rect crop, W×H output, overlay OFF (no rect asset).
     * - **unrecognized** → crop to the reported aspect, overlay OFF (per the
     *   cropper rule — no preview we can't vouch for).
     */
    fun resolve(width: Int, height: Int, shape: String): WatchCropProfile {
        if (width <= 0 || height <= 0) return DEFAULT
        val known = KNOWN.any { it.w == width && it.h == height && it.shape == shape }
        return if (shape != SHAPE_RECT) {
            // Circle crop → square output at the panel's smaller side. Overlay
            // only for a recognized round size.
            val side = minOf(width, height)
            WatchCropProfile(side, side, round = true, showOverlay = known, known = known)
        } else {
            // Rect crop → full W×H, no overlay (we have only a round overlay asset).
            WatchCropProfile(width, height, round = false, showOverlay = false, known = known)
        }
    }

    private const val SHAPE_CIRCLE = "circle"
    private const val SHAPE_RECT = "rect"
}
