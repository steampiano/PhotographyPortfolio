package com.siliconprime.tabletmirror.host

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.siliconprime.tabletmirror.net.RemoteAction
import com.siliconprime.tabletmirror.net.TextInput
import com.siliconprime.tabletmirror.net.TextOp
import com.siliconprime.tabletmirror.net.TouchBatch
import com.siliconprime.tabletmirror.util.Geometry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Applies remote input to this device. Enabled by the user in
 * Settings > Accessibility; the app cannot turn itself on.
 *
 * Mirroring works without this service — only control needs it — so the host
 * reports its availability to the viewer rather than treating it as required.
 */
class MirrorAccessibilityService : AccessibilityService() {

    private var injector: GestureInjector? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        injector = GestureInjector(this)
        instance = this
        available.value = true
        Log.i(TAG, "control service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not interested in observing the UI; the service exists purely to inject.
    }

    override fun onInterrupt() {
        injector?.reset()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) {
            instance = null
            available.value = false
        }
        injector?.shutdown()
        injector = null
    }

    // -----------------------------------------------------------------------
    // Remote input entry points
    // -----------------------------------------------------------------------

    private fun applyTouch(batch: TouchBatch) {
        val injector = injector ?: return
        val (screenW, screenH) = currentScreenSize()
        if (screenW <= 0 || screenH <= 0) return

        val ids = ArrayList<Int>(batch.points.size)
        val points = ArrayList<GestureStateMachine.Point>(batch.points.size)
        for (p in batch.points) {
            val (px, py) = Geometry.toPixels(p.x, p.y, screenW, screenH)
            ids.add(p.pointerId)
            points.add(GestureStateMachine.Point(px, py))
        }
        injector.submit(batch.action, ids, points)
    }

    private fun applyGlobalAction(action: Int) {
        val mapped = when (action) {
            RemoteAction.BACK -> GLOBAL_ACTION_BACK
            RemoteAction.HOME -> GLOBAL_ACTION_HOME
            RemoteAction.RECENTS -> GLOBAL_ACTION_RECENTS
            RemoteAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            RemoteAction.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return
        }
        performGlobalAction(mapped)
    }

    /**
     * Edits the host's focused text field.
     *
     * There is no way to synthesise key presses from an accessibility service, so
     * this replaces the field's contents via ACTION_SET_TEXT. That means it only
     * works on a focused editable node, and it rewrites the whole value rather
     * than typing character by character.
     */
    private fun applyText(input: TextInput) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        try {
            if (!node.isEditable) return
            val existing = node.text?.toString() ?: ""
            val updated = when (input.op) {
                TextOp.INSERT -> existing + input.text
                TextOp.BACKSPACE -> if (existing.isEmpty()) return else existing.dropLast(1)
                else -> return
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.w(TAG, "ACTION_SET_TEXT was refused by the focused node")
                return
            }
            // Put the caret back at the end so the next edit appends.
            val caret = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, updated.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, updated.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, caret)
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    /**
     * Real display size in the current rotation. Gesture coordinates live in this
     * space, and it must match what the encoder captures — both cover the whole
     * display including system bars.
     */
    private fun currentScreenSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return metrics.widthPixels to metrics.heightPixels
            }
        }
        val fallback = resources.displayMetrics
        return fallback.widthPixels to fallback.heightPixels
    }

    companion object {
        private const val TAG = "MirrorA11y"

        @Volatile
        private var instance: MirrorAccessibilityService? = null

        private val available = MutableStateFlow(false)

        /** True while the user has this service enabled and it is bound. */
        val isAvailable: StateFlow<Boolean> = available

        fun deliverTouch(batch: TouchBatch) {
            instance?.applyTouch(batch)
        }

        fun deliverGlobalAction(action: Int) {
            instance?.applyGlobalAction(action)
        }

        fun deliverText(input: TextInput) {
            instance?.applyText(input)
        }

        fun releaseAllPointers() {
            instance?.injector?.reset()
        }

        /**
         * Whether the user has enabled us in Settings. Checked from UI code, which
         * may run before the service has ever been bound, so it reads the setting
         * rather than relying on [isAvailable].
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/${MirrorAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (entry in splitter) {
                if (entry.equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}
