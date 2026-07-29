package com.siliconprime.tabletmirror.host

import com.siliconprime.tabletmirror.net.TouchAction

/**
 * Decides which gesture segments to dispatch for a live stream of remote pointer
 * samples. Deliberately free of Android types so it can be unit tested — the
 * platform half lives in [GestureInjector].
 *
 * The shape of this class is dictated by `AccessibilityService.dispatchGesture`,
 * which describes whole gestures rather than individual events:
 *
 *  - A touch is kept open by dispatching short segments back to back, each one
 *    continuing the previous segment for that pointer.
 *  - Only one gesture may be in flight at a time, and a continuation is only
 *    legal after the previous dispatch completes, so samples that arrive mid
 *    flight are coalesced into the next segment rather than dropped.
 *  - A pointer held still must still be given segments, or its stroke lapses and
 *    the touch is released. Those idle segments carry no movement.
 *  - A pointer is only forgotten once its *terminal* segment (`willContinue =
 *    false`) has been dispatched. Forgetting it the moment a release arrives
 *    would abandon an open stroke and lose the tap entirely.
 *
 * Not thread safe: the caller is expected to confine it to one thread.
 */
class GestureStateMachine(
    private val clock: () -> Long,
    private val maxPointers: Int = MAX_POINTERS,
) {
    data class Point(val x: Float, val y: Float)

    /**
     * One pointer's contribution to the next gesture.
     *
     * [moves] empty means an idle segment: hold at [anchor] without moving. A
     * [continuation] segment extends the pointer's previous stroke; otherwise it
     * begins a new one.
     */
    data class Segment(
        val pointerId: Int,
        val anchor: Point,
        val moves: List<Point>,
        val durationMs: Long,
        val willContinue: Boolean,
        val continuation: Boolean,
    )

    private class Pointer(var x: Float, var y: Float, var lastInputAt: Long) {
        val queued = ArrayList<Point>()
        var started = false

        /** Release received; the terminal segment still has to go out. */
        var lifting = false

        /** Terminal segment dispatched; safe to forget once the gesture returns. */
        var finished = false
    }

    /** Insertion ordered so stroke order stays stable between dispatches. */
    private val pointers = LinkedHashMap<Int, Pointer>()
    private var inFlight = false

    val livePointerIds: Set<Int> get() = pointers.keys.toSet()

    val isIdle: Boolean get() = pointers.isEmpty() && !inFlight

    fun submit(action: Int, ids: List<Int>, points: List<Point>) {
        val now = clock()
        when (action) {
            TouchAction.DOWN -> ids.forEachIndexed { i, id ->
                // Ignore a duplicate DOWN: re-seeding a live pointer would strand
                // its open stroke.
                if (pointers.size < maxPointers && !pointers.containsKey(id)) {
                    pointers[id] = Pointer(points[i].x, points[i].y, now)
                }
            }

            TouchAction.MOVE -> ids.forEachIndexed { i, id ->
                pointers[id]?.takeIf { !it.lifting }?.let {
                    it.queued.add(points[i])
                    it.lastInputAt = now
                }
            }

            TouchAction.UP -> ids.forEachIndexed { i, id ->
                pointers[id]?.takeIf { !it.lifting }?.let {
                    it.queued.add(points[i])
                    it.lifting = true
                    it.lastInputAt = now
                }
            }

            TouchAction.CANCEL -> releaseAll()
        }
    }

    /**
     * Every live pointer must be released rather than dropped, so that open
     * strokes are closed properly instead of being abandoned.
     */
    fun releaseAll() {
        val now = clock()
        for (pointer in pointers.values) {
            if (!pointer.lifting) {
                pointer.lifting = true
                pointer.lastInputAt = now
            }
        }
    }

    /**
     * Takes the next gesture to dispatch, marking it in flight. An empty result
     * means there is nothing to send right now.
     */
    fun poll(): List<Segment> {
        if (inFlight || pointers.isEmpty()) return emptyList()

        val segments = ArrayList<Segment>(pointers.size)
        for ((id, pointer) in pointers) {
            if (segments.size >= maxPointers) break
            segments.add(segmentFor(id, pointer) ?: continue)
        }

        if (segments.isEmpty()) {
            // Nothing dispatchable: retire anything that wanted to end.
            pointers.values.removeAll { it.lifting || it.finished }
            return emptyList()
        }
        inFlight = true
        return segments
    }

    /** Reports the outcome of the gesture returned by the last [poll]. */
    fun onGestureFinished(cancelled: Boolean) {
        inFlight = false
        if (cancelled) {
            // A cancelled gesture invalidates its strokes, so none can be
            // continued. Start clean on the next DOWN.
            pointers.clear()
        } else {
            pointers.values.removeAll { it.finished }
        }
    }

    /** The platform refused the gesture outright; there will be no callback. */
    fun onDispatchFailed() {
        inFlight = false
        pointers.clear()
    }

    fun clear() {
        inFlight = false
        pointers.clear()
    }

    private fun segmentFor(id: Int, pointer: Pointer): Segment? {
        if (pointer.finished) return null

        val moves = pointer.queued.toList()
        pointer.queued.clear()

        // Released before anything was ever dispatched: no stroke to close, and no
        // position worth tapping.
        if (moves.isEmpty() && pointer.lifting && !pointer.started) return null

        // Safety net against a viewer that vanishes mid-hold without releasing:
        // otherwise idle segments would be dispatched forever.
        if (moves.isEmpty() && !pointer.lifting &&
            clock() - pointer.lastInputAt > MAX_HOLD_MS
        ) {
            pointer.lifting = true
        }

        val anchor = Point(pointer.x, pointer.y)
        if (moves.isNotEmpty()) {
            val last = moves.last()
            pointer.x = last.x
            pointer.y = last.y
        }

        val duration = if (moves.isEmpty()) {
            SEGMENT_MS
        } else {
            (moves.size * PER_SAMPLE_MS).coerceIn(SEGMENT_MS, MAX_SEGMENT_MS)
        }

        val willContinue = !pointer.lifting
        val segment = Segment(
            pointerId = id,
            anchor = anchor,
            moves = moves,
            durationMs = duration,
            willContinue = willContinue,
            continuation = pointer.started,
        )
        pointer.started = true
        // Once the terminal segment is out the pointer may be forgotten.
        if (!willContinue) pointer.finished = true
        return segment
    }

    companion object {
        /** Matches GestureDescription.getMaxStrokeCount() on current platforms. */
        const val MAX_POINTERS = 10

        /** Segment length. Short keeps drags responsive; too short gets coalesced. */
        const val SEGMENT_MS = 32L
        const val PER_SAMPLE_MS = 8L
        const val MAX_SEGMENT_MS = 120L

        /** Longest a pointer may stay down with no fresh input from the viewer. */
        const val MAX_HOLD_MS = 30_000L
    }
}
