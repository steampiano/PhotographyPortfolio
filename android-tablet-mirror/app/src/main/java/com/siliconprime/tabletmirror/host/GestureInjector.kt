package com.siliconprime.tabletmirror.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.ViewConfiguration

/**
 * Platform half of remote touch injection: owns the injector thread, turns
 * [GestureStateMachine.Segment]s into real [StrokeDescription]s and feeds
 * dispatch results back into the machine.
 *
 * The scheduling logic all lives in [GestureStateMachine], which is unit tested.
 * Everything here is the Android translation layer.
 */
class GestureInjector(private val service: AccessibilityService) {

    private val thread = HandlerThread("gesture-injector").apply { start() }
    private val handler = Handler(thread.looper)

    // Take slop from the platform rather than assuming a density: it decides
    // whether a tap's wobble reads as a drag, so it has to match what the host's own
    // views use.
    private val machine = GestureStateMachine(
        clock = SystemClock::uptimeMillis,
        touchSlopPx = runCatching {
            ViewConfiguration.get(service).scaledTouchSlop.toFloat()
        }.getOrDefault(GestureStateMachine.DEFAULT_TOUCH_SLOP_PX),
    )

    /**
     * The live stroke per pointer. `continueStroke` must be called on the exact
     * instance returned by the previous dispatch, so the objects themselves have
     * to be carried across gestures.
     */
    private val strokes = HashMap<Int, StrokeDescription>()

    /** Submits a batch of samples already converted to host pixel coordinates. */
    fun submit(action: Int, ids: List<Int>, points: List<GestureStateMachine.Point>) {
        handler.post {
            machine.submit(action, ids, points)
            pump()
        }
    }

    /** Releases anything still held, e.g. when the controlling viewer disconnects. */
    fun reset() {
        handler.post {
            machine.releaseAll()
            pump()
        }
    }

    fun shutdown() {
        handler.removeCallbacks(wakeUp)
        handler.post {
            machine.clear()
            strokes.clear()
        }
        thread.quitSafely()
    }

    private val wakeUp = Runnable { pump() }

    /**
     * Re-polls when a press is waiting to see whether it becomes a tap. Without
     * this a finger held perfectly still would never be dispatched at all.
     */
    private fun scheduleWakeUp() {
        val delay = machine.pendingWakeUpMs() ?: return
        handler.removeCallbacks(wakeUp)
        handler.postDelayed(wakeUp, delay.coerceAtLeast(1L))
    }

    private fun pump() {
        val segments = machine.poll()
        if (segments.isEmpty()) {
            pruneStrokes()
            scheduleWakeUp()
            return
        }

        val builder = GestureDescription.Builder()
        var added = 0
        for (segment in segments) {
            val stroke = try {
                buildStroke(segment)
            } catch (e: RuntimeException) {
                // A rejected path or an illegal continuation would otherwise kill
                // the injector thread; skip this stroke and keep the rest.
                Log.w(TAG, "skipping stroke for pointer ${segment.pointerId}: ${e.message}")
                strokes.remove(segment.pointerId)
                null
            }
            if (stroke == null) continue
            builder.addStroke(stroke)
            strokes[segment.pointerId] = stroke
            added++
        }

        if (added == 0) {
            machine.onDispatchFailed()
            strokes.clear()
            return
        }

        val gesture = try {
            builder.build()
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not build gesture: ${e.message}")
            machine.onDispatchFailed()
            strokes.clear()
            return
        }

        if (!service.dispatchGesture(gesture, callback, handler)) {
            // Happens when the service has lost its connection; reset so we do not
            // wedge waiting for a callback that will never arrive.
            Log.w(TAG, "dispatchGesture refused")
            machine.onDispatchFailed()
            strokes.clear()
        }
        // A second pointer may still be waiting on the tap deadline.
        scheduleWakeUp()
    }

    private fun buildStroke(segment: GestureStateMachine.Segment): StrokeDescription {
        val path = Path()
        path.moveTo(segment.anchor.x, segment.anchor.y)
        if (segment.moves.isEmpty()) {
            // A zero-length path is rejected, so nudge a sub-pixel amount from the
            // anchor. The anchor itself never moves, so a long press cannot creep.
            path.lineTo(segment.anchor.x, segment.anchor.y + IDLE_NUDGE_PX)
        } else {
            for (move in segment.moves) {
                path.lineTo(move.x, move.y)
            }
        }

        val previous = strokes[segment.pointerId]
        return if (segment.continuation && previous != null) {
            previous.continueStroke(path, 0L, segment.durationMs, segment.willContinue)
        } else {
            StrokeDescription(path, 0L, segment.durationMs, segment.willContinue)
        }
    }

    /** Drops stroke objects for pointers the machine has retired. */
    private fun pruneStrokes() {
        if (strokes.isEmpty()) return
        val live = machine.livePointerIds
        strokes.keys.retainAll(live)
    }

    private val callback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            handler.post {
                machine.onGestureFinished(cancelled = false)
                pruneStrokes()
                pump()
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            handler.post {
                machine.onGestureFinished(cancelled = true)
                strokes.clear()
            }
        }
    }

    private companion object {
        const val TAG = "GestureInjector"
        const val IDLE_NUDGE_PX = 0.1f
    }
}
