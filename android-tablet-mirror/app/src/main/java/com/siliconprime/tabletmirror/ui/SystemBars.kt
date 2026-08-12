package com.siliconprime.tabletmirror.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

/**
 * Keeps content out from under the system bars.
 *
 * From Android 15 an app targeting a current SDK is drawn edge to edge whether it
 * asked to be or not, so a layout that simply fills the window now runs underneath
 * the status bar, the navigation bar and — on One UI — the task bar sitting along
 * the bottom edge. On these tablets that hid the last line or two of every screen,
 * and rotating to portrait made it worse, because the same content has less height
 * to fit into.
 *
 * The fix is to ask the system how much room the bars need and pad by that, rather
 * than guessing at a margin that would be wrong on the next device. Padding is
 * added to whatever the layout already declares, and applied every time the insets
 * change, so it survives rotation and the task bar appearing and disappearing.
 */
object SystemBars {

    /**
     * Pads [view] clear of the bars. [top] is left to the caller because a screen
     * with an action bar already has its top handled by AppCompat, and padding it
     * again would leave a visible gap.
     */
    fun pad(view: View, top: Boolean) {
        val left = view.paddingLeft
        val topPad = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom

        // Scrolling containers must keep drawing into the padded strip, or the
        // bottom inset becomes a dead band the content cannot travel through.
        if (view is ViewGroup) view.clipToPadding = false

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            target.setPadding(
                left + bars.left,
                topPad + if (top) bars.top else 0,
                right + bars.right,
                bottom + bars.bottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Moves [view] clear of the bars with margins instead of padding, for overlays
     * that sit on top of full-bleed content — the viewer's control bar, which must
     * not be pushed under a gesture handle, while the video behind it still fills
     * the screen.
     */
    fun margin(view: View) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val left = params.leftMargin
        val top = params.topMargin
        val right = params.rightMargin
        val bottom = params.bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            target.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = left + bars.left
                topMargin = top + bars.top
                rightMargin = right + bars.right
                bottomMargin = bottom + bars.bottom
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
