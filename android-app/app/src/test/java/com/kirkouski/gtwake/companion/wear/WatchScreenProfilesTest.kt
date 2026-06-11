package com.kirkouski.gtwake.companion.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the cropper-gating rule of record (docs/watch-resolutions.md):
 * recognized round → circle crop + overlay; recognized rect → rounded-rect, no
 * overlay; unrecognized → crop to aspect, NO overlay; never-reported → GT6
 * round default.
 */
class WatchScreenProfilesTest {

    @Test
    fun `no report falls back to GT6 round default with overlay`() {
        assertEquals(WatchScreenProfiles.DEFAULT, WatchScreenProfiles.resolve(0, 0, ""))
        assertEquals(WatchScreenProfiles.DEFAULT, WatchScreenProfiles.resolve(-1, -1, "circle"))
        // height<=0 is equally "garbage / never reported".
        assertEquals(WatchScreenProfiles.DEFAULT, WatchScreenProfiles.resolve(466, 0, "circle"))
        assertTrue(WatchScreenProfiles.DEFAULT.round)
        assertTrue(WatchScreenProfiles.DEFAULT.showOverlay)
        assertEquals(466, WatchScreenProfiles.DEFAULT.outWidth)
        assertEquals(466, WatchScreenProfiles.DEFAULT.outHeight)
    }

    @Test
    fun `recognized round GT panel gets circle crop square output and overlay`() {
        val p = WatchScreenProfiles.resolve(466, 466, "circle")
        assertTrue(p.round)
        assertTrue(p.known)
        assertTrue(p.showOverlay)
        assertEquals(466, p.outWidth)
        assertEquals(466, p.outHeight)
    }

    @Test
    fun `legacy round panels are recognized`() {
        assertTrue(WatchScreenProfiles.resolve(454, 454, "circle").known)
        assertTrue(WatchScreenProfiles.resolve(390, 390, "circle").known)
        assertTrue(WatchScreenProfiles.resolve(390, 390, "circle").showOverlay)
    }

    @Test
    fun `recognized rect FIT panel gets rounded-rect full output and NO overlay`() {
        val fit = WatchScreenProfiles.resolve(408, 480, "rect")
        assertFalse(fit.round)
        assertTrue(fit.known)
        assertFalse(fit.showOverlay) // no rect overlay asset
        assertEquals(408, fit.outWidth)
        assertEquals(480, fit.outHeight)

        val fit2 = WatchScreenProfiles.resolve(336, 480, "rect")
        assertTrue(fit2.known)
        assertFalse(fit2.showOverlay)
        assertEquals(336, fit2.outWidth)
        assertEquals(480, fit2.outHeight)
    }

    @Test
    fun `unrecognized rect resolution crops to aspect with NO overlay`() {
        val p = WatchScreenProfiles.resolve(400, 400, "rect")
        assertFalse(p.known)
        assertFalse(p.showOverlay)
        assertFalse(p.round)
        assertEquals(400, p.outWidth)
        assertEquals(400, p.outHeight)
    }

    @Test
    fun `unrecognized round resolution gets circle crop but NO overlay`() {
        // A future round size we haven't validated: circle crop is safe, but
        // the overlay is withheld because we can't vouch for the preview.
        val p = WatchScreenProfiles.resolve(500, 500, "circle")
        assertTrue(p.round)
        assertFalse(p.known)
        assertFalse(p.showOverlay)
        assertEquals(500, p.outWidth)
        assertEquals(500, p.outHeight)
    }

    @Test
    fun `circle shape with mismatched dims is unknown and outputs the smaller square`() {
        // Quirky report (portrait circle) — unrecognized, no overlay, square
        // output at the smaller side; degrades gracefully (never crashes).
        val p = WatchScreenProfiles.resolve(480, 336, "circle")
        assertTrue(p.round)
        assertFalse(p.known)
        assertFalse(p.showOverlay)
        assertEquals(336, p.outWidth)
        assertEquals(336, p.outHeight)
    }
}
