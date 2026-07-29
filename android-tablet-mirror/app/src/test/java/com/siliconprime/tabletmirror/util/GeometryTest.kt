package com.siliconprime.tabletmirror.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    @Test
    fun `a wider view letterboxes on the sides`() {
        // 1600x1000 video inside a 2000x1000 view: scale 1, bars left and right.
        val rect = Geometry.fit(2000, 1000, 1600, 1000)
        assertEquals(200f, rect.left, TOLERANCE)
        assertEquals(0f, rect.top, TOLERANCE)
        assertEquals(1600f, rect.width, TOLERANCE)
        assertEquals(1000f, rect.height, TOLERANCE)
    }

    @Test
    fun `a taller view letterboxes above and below`() {
        val rect = Geometry.fit(1600, 1400, 1600, 1000)
        assertEquals(0f, rect.left, TOLERANCE)
        assertEquals(200f, rect.top, TOLERANCE)
        assertEquals(1600f, rect.width, TOLERANCE)
        assertEquals(1000f, rect.height, TOLERANCE)
    }

    @Test
    fun `fitting preserves aspect ratio when scaling down`() {
        val rect = Geometry.fit(800, 800, 1600, 1000)
        assertEquals(800f, rect.width, TOLERANCE)
        assertEquals(500f, rect.height, TOLERANCE)
        assertEquals(1600f / 1000f, rect.width / rect.height, 0.001f)
    }

    @Test
    fun `degenerate sizes do not blow up`() {
        val rect = Geometry.fit(0, 0, 1600, 1000)
        assertEquals(0f, rect.width, TOLERANCE)
        assertNull(Geometry.normalize(0f, 0f, rect))
    }

    @Test
    fun `a centre touch maps to the centre of the host screen`() {
        val rect = Geometry.fit(2000, 1000, 1600, 1000)
        val (x, y) = Geometry.normalize(1000f, 500f, rect)!!
        assertEquals(0.5f, x, TOLERANCE)
        assertEquals(0.5f, y, TOLERANCE)
    }

    @Test
    fun `corners map to the extremes`() {
        val rect = Geometry.fit(1600, 1000, 1600, 1000)
        val topLeft = Geometry.normalize(0f, 0f, rect)!!
        assertEquals(0f, topLeft.first, TOLERANCE)
        assertEquals(0f, topLeft.second, TOLERANCE)

        val bottomRight = Geometry.normalize(1600f, 1000f, rect)!!
        assertEquals(1f, bottomRight.first, TOLERANCE)
        assertEquals(1f, bottomRight.second, TOLERANCE)
    }

    @Test
    fun `a touch on a letterbox bar is ignored`() {
        val rect = Geometry.fit(2000, 1000, 1600, 1000)
        // x=100 is inside the left bar, not on the mirrored image.
        assertNull(Geometry.normalize(100f, 500f, rect))
        assertNull(Geometry.normalize(1900f, 500f, rect))
        assertNotNull(Geometry.normalize(201f, 500f, rect))
    }

    @Test
    fun `normalised coordinates expand to in-bounds pixels`() {
        val (x, y) = Geometry.toPixels(0.5f, 0.25f, 2560, 1600)
        assertEquals(1280f, x, TOLERANCE)
        assertEquals(400f, y, TOLERANCE)
    }

    @Test
    fun `the far edge clamps inside the screen`() {
        // Gesture dispatch silently fails for out-of-bounds points, so 1.0 must
        // land on the last addressable pixel rather than one past it.
        val (x, y) = Geometry.toPixels(1f, 1f, 2560, 1600)
        assertEquals(2559f, x, TOLERANCE)
        assertEquals(1599f, y, TOLERANCE)
    }

    @Test
    fun `out of range fractions are clamped`() {
        val (x, y) = Geometry.toPixels(-0.5f, 3f, 1000, 1000)
        assertEquals(0f, x, TOLERANCE)
        assertEquals(999f, y, TOLERANCE)
    }

    @Test
    fun `encoder size scales the longest edge and aligns to sixteen`() {
        val (w, h) = Geometry.encoderSize(2560, 1600, 1280)
        assertEquals(1280, w)
        assertEquals(800, h)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
    }

    @Test
    fun `encoder size handles portrait too`() {
        val (w, h) = Geometry.encoderSize(1600, 2560, 1280)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertEquals(1280, h)
        assertTrue(w <= 1280)
    }

    @Test
    fun `a display smaller than the cap is not upscaled`() {
        val (w, h) = Geometry.encoderSize(1024, 768, 1280)
        assertEquals(1024, w)
        assertEquals(768, h)
    }

    @Test
    fun `native mode keeps the resolution and only rounds down to alignment`() {
        // 2000 is already a multiple of 16; 1202 rounds down to 1200.
        val (w, h) = Geometry.encoderSize(2000, 1202, 0)
        assertEquals(2000, w)
        assertEquals(1200, h)
    }

    @Test
    fun `alignment never collapses a dimension to zero`() {
        val (w, h) = Geometry.encoderSize(10, 4, 0)
        assertEquals(16, w)
        assertEquals(16, h)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
