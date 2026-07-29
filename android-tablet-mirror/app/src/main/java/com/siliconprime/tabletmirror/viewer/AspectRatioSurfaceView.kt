package com.siliconprime.tabletmirror.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import com.siliconprime.tabletmirror.util.Geometry
import kotlin.math.roundToInt

/**
 * A [SurfaceView] that measures itself to the mirrored screen's aspect ratio.
 *
 * Sizing the view to the video rather than letterboxing inside a larger surface
 * means the view's own bounds *are* the picture, so a touch maps to the host with
 * a plain division — no offset bookkeeping, and no way for a tap on a black bar
 * to be mistaken for a tap on the host's screen.
 */
class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    private var videoWidth = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        if (width == videoWidth && height == videoHeight) return
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (videoWidth <= 0 || videoHeight <= 0) {
            setMeasuredDimension(availableWidth, availableHeight)
            return
        }

        val rect = Geometry.fit(availableWidth, availableHeight, videoWidth, videoHeight)
        setMeasuredDimension(
            rect.width.roundToInt().coerceAtLeast(1),
            rect.height.roundToInt().coerceAtLeast(1),
        )
    }
}
