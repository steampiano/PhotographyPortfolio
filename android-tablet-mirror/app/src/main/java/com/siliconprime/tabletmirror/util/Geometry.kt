package com.siliconprime.tabletmirror.util

/**
 * Coordinate mapping between the viewer's on-screen video rectangle and the
 * host's screen, expressed as fractions so the two tablets need not share a
 * resolution, density or aspect ratio.
 *
 * Pure arithmetic, no Android types, so it is covered by JVM unit tests.
 */
object Geometry {

    /** Where the video lands inside a view, letterboxed and centred. */
    data class ContentRect(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    /** Fits [videoW]x[videoH] inside [viewW]x[viewH] preserving aspect ratio. */
    fun fit(viewW: Int, viewH: Int, videoW: Int, videoH: Int): ContentRect {
        if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) {
            return ContentRect(0f, 0f, viewW.toFloat().coerceAtLeast(0f), viewH.toFloat().coerceAtLeast(0f))
        }
        val scale = minOf(viewW.toFloat() / videoW, viewH.toFloat() / videoH)
        val w = videoW * scale
        val h = videoH * scale
        return ContentRect((viewW - w) / 2f, (viewH - h) / 2f, w, h)
    }

    /**
     * Converts a view-space touch to a 0..1 fraction of the host screen, or null
     * when the touch lands on a letterbox bar rather than on the mirrored image.
     */
    fun normalize(x: Float, y: Float, rect: ContentRect): Pair<Float, Float>? {
        if (rect.width <= 0f || rect.height <= 0f) return null
        if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) return null
        val nx = (x - rect.left) / rect.width
        val ny = (y - rect.top) / rect.height
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    /**
     * Expands a 0..1 fraction back to host pixels, clamped to the last addressable
     * pixel. Gesture dispatch silently fails for out-of-bounds coordinates, so the
     * clamp matters.
     */
    fun toPixels(nx: Float, ny: Float, screenW: Int, screenH: Int): Pair<Float, Float> {
        val px = (nx * screenW).coerceIn(0f, (screenW - 1).toFloat().coerceAtLeast(0f))
        val py = (ny * screenH).coerceIn(0f, (screenH - 1).toFloat().coerceAtLeast(0f))
        return px to py
    }

    /**
     * Chooses an encoder surface size: scales the display down so its longest edge
     * is at most [maxDimension], then rounds both edges to a multiple of 16.
     *
     * Hardware H.264 encoders want 16-aligned dimensions; feeding them odd sizes
     * produces green edges or outright configuration failures on some chipsets.
     * [maxDimension] of 0 means "keep native resolution, only align".
     */
    fun encoderSize(displayW: Int, displayH: Int, maxDimension: Int): Pair<Int, Int> {
        require(displayW > 0 && displayH > 0) { "display must be positive" }
        val longest = maxOf(displayW, displayH)
        val scale = if (maxDimension <= 0 || longest <= maxDimension) {
            1f
        } else {
            maxDimension.toFloat() / longest
        }
        val w = align16((displayW * scale).toInt())
        val h = align16((displayH * scale).toInt())
        return w to h
    }

    private fun align16(value: Int): Int {
        val aligned = (value / 16) * 16
        return if (aligned < 16) 16 else aligned
    }
}
