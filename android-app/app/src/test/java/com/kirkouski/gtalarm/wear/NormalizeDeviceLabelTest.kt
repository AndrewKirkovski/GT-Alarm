package com.kirkouski.gtalarm.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeDeviceLabelTest {

    @Test
    fun `huawei name with BT serial collapses to brand + model`() {
        assertEquals(
            "Huawei GT 6 Pro",
            normalizeDeviceLabel(rawName = "HUAWEI WATCH GT 6 Pro-AB1", rawModel = "FRD-BX9"),
        )
    }

    @Test
    fun `lowercase brand still normalizes to title case`() {
        assertEquals(
            "Huawei GT 6 Pro",
            normalizeDeviceLabel(rawName = "huawei watch GT 6 Pro-XX9", rawModel = null),
        )
    }

    @Test
    fun `falls through to model when name is blank`() {
        assertEquals(
            "GT 6 Pro",
            normalizeDeviceLabel(rawName = "  ", rawModel = "GT 6 Pro-46"),
        )
    }

    @Test
    fun `empty inputs return empty string`() {
        assertEquals("", normalizeDeviceLabel(null, null))
        assertEquals("", normalizeDeviceLabel("", ""))
        assertEquals("", normalizeDeviceLabel("  ", null))
    }

    @Test
    fun `chained suffixes are all peeled off`() {
        assertEquals(
            "Huawei Band 9",
            normalizeDeviceLabel(rawName = "HUAWEI Band 9-AA1-XX2", rawModel = null),
        )
    }

    @Test
    fun `user-renamed device keeps the custom string but strips serial`() {
        // Some users rename their watch in Health to e.g. "Andrei's GT 6 Pro-AB1".
        // We still strip the BT suffix but leave the rest verbatim. Apostrophes
        // / non-ASCII letters must survive.
        assertEquals(
            "Andrei's GT 6 Pro",
            normalizeDeviceLabel(rawName = "Andrei's GT 6 Pro-AB1", rawModel = null),
        )
    }

    @Test
    fun `prefers name when both populated`() {
        // model carries an SKU code; name carries the human-readable label.
        assertEquals(
            "Huawei GT 4",
            normalizeDeviceLabel(rawName = "HUAWEI WATCH GT 4-1A2", rawModel = "PNX-B19"),
        )
    }

    @Test
    fun `single unseparated token is left intact`() {
        // A model code with no separators (no dash/underscore/space) doesn't
        // look like a suffix that needs peeling — leave it alone so the user
        // still sees something rather than a blank card. The suffix regex
        // requires a leading separator before the alnum run, which guards
        // against over-trimming this kind of input.
        assertEquals("FRDBX9", normalizeDeviceLabel(rawName = "FRDBX9", rawModel = null))
    }
}
