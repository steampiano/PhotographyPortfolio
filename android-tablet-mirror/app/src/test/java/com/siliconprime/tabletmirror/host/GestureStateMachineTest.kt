package com.siliconprime.tabletmirror.host

import com.siliconprime.tabletmirror.net.TouchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureStateMachineTest {

    private var now = 1_000L
    private val machine = GestureStateMachine(clock = { now })

    private fun p(x: Float, y: Float) = GestureStateMachine.Point(x, y)

    private fun down(id: Int, x: Float, y: Float) =
        machine.submit(TouchAction.DOWN, listOf(id), listOf(p(x, y)))

    private fun move(id: Int, x: Float, y: Float) =
        machine.submit(TouchAction.MOVE, listOf(id), listOf(p(x, y)))

    private fun up(id: Int, x: Float, y: Float) =
        machine.submit(TouchAction.UP, listOf(id), listOf(p(x, y)))

    /** Dispatches whatever is pending and reports it completed, as the glue does. */
    private fun runGesture(): List<GestureStateMachine.Segment> {
        val segments = machine.poll()
        if (segments.isNotEmpty()) machine.onGestureFinished(cancelled = false)
        return segments
    }

    // -----------------------------------------------------------------------
    // Taps
    // -----------------------------------------------------------------------

    @Test
    fun `a tap opens a stroke and then closes it`() {
        down(0, 100f, 200f)
        val first = machine.poll()
        assertEquals(1, first.size)
        assertEquals(p(100f, 200f), first[0].anchor)
        assertTrue("must stay open awaiting the release", first[0].willContinue)
        assertFalse(first[0].continuation)

        // The release arrives while the first segment is still in flight.
        up(0, 100f, 200f)
        assertTrue("nothing may dispatch while a gesture is in flight", machine.poll().isEmpty())

        machine.onGestureFinished(cancelled = false)
        val terminal = machine.poll()
        assertEquals(1, terminal.size)
        assertFalse("the terminal segment must close the stroke", terminal[0].willContinue)
        assertTrue(terminal[0].continuation)

        machine.onGestureFinished(cancelled = false)
        assertTrue("the pointer is only forgotten after its terminal segment", machine.isIdle)
    }

    @Test
    fun `a pointer is not retired before its terminal segment is dispatched`() {
        down(0, 10f, 10f)
        machine.poll()
        up(0, 10f, 10f)
        machine.onGestureFinished(cancelled = false)
        // Regression guard: retiring on release would drop the stroke and lose the
        // tap entirely.
        assertEquals(setOf(0), machine.livePointerIds)
        assertEquals(1, machine.poll().size)
    }

    @Test
    fun `a release before anything was dispatched leaves no pointer behind`() {
        down(0, 5f, 5f)
        machine.releaseAll()
        // Nothing was ever dispatched, so there is no stroke to close.
        assertTrue(machine.poll().isEmpty())
        assertTrue(machine.isIdle)
    }

    // -----------------------------------------------------------------------
    // Drags
    // -----------------------------------------------------------------------

    @Test
    fun `moves arriving mid-flight are coalesced into one segment`() {
        down(0, 0f, 0f)
        machine.poll()

        move(0, 10f, 0f)
        move(0, 20f, 0f)
        move(0, 30f, 0f)
        machine.onGestureFinished(cancelled = false)

        val segment = machine.poll().single()
        assertEquals(listOf(p(10f, 0f), p(20f, 0f), p(30f, 0f)), segment.moves)
        assertTrue(segment.continuation)
        assertTrue(segment.willContinue)
    }

    @Test
    fun `the anchor follows the last delivered position`() {
        down(0, 0f, 0f)
        runGesture()

        move(0, 40f, 50f)
        runGesture()

        // The next segment must start where the previous one ended, otherwise the
        // path would jump back to the original touch-down point.
        move(0, 60f, 70f)
        val segment = machine.poll().single()
        assertEquals(p(40f, 50f), segment.anchor)
        assertEquals(listOf(p(60f, 70f)), segment.moves)
    }

    @Test
    fun `segment duration scales with sample count and stays bounded`() {
        down(0, 0f, 0f)
        machine.poll()
        repeat(60) { move(0, it.toFloat(), 0f) }
        machine.onGestureFinished(cancelled = false)

        val segment = machine.poll().single()
        assertEquals(GestureStateMachine.MAX_SEGMENT_MS, segment.durationMs)
    }

    @Test
    fun `a single sample still gets the minimum duration`() {
        down(0, 0f, 0f)
        machine.poll()
        move(0, 1f, 1f)
        machine.onGestureFinished(cancelled = false)
        assertEquals(GestureStateMachine.SEGMENT_MS, machine.poll().single().durationMs)
    }

    // -----------------------------------------------------------------------
    // Holding still
    // -----------------------------------------------------------------------

    @Test
    fun `a held pointer keeps producing idle segments so the touch is not released`() {
        down(0, 50f, 60f)
        machine.poll()

        repeat(5) {
            machine.onGestureFinished(cancelled = false)
            val segment = machine.poll().single()
            assertTrue("idle segments carry no movement", segment.moves.isEmpty())
            assertTrue("the stroke must stay open", segment.willContinue)
            // The anchor must not drift, or a long press would slide across the UI.
            assertEquals(p(50f, 60f), segment.anchor)
        }
    }

    @Test
    fun `a pointer held with no input at all is eventually released`() {
        down(0, 50f, 60f)
        machine.poll()
        machine.onGestureFinished(cancelled = false)

        // Simulates a viewer that vanished mid-hold without sending a release.
        now += GestureStateMachine.MAX_HOLD_MS + 1
        val segment = machine.poll().single()
        assertFalse("the safety net must close the stroke", segment.willContinue)

        machine.onGestureFinished(cancelled = false)
        assertTrue(machine.isIdle)
    }

    // -----------------------------------------------------------------------
    // Multiple pointers
    // -----------------------------------------------------------------------

    @Test
    fun `two pointers are dispatched together in a stable order`() {
        down(0, 0f, 0f)
        down(1, 100f, 100f)

        val segments = machine.poll()
        assertEquals(2, segments.size)
        assertEquals(listOf(0, 1), segments.map { it.pointerId })
        machine.onGestureFinished(cancelled = false)

        move(0, 10f, 10f)
        move(1, 90f, 90f)
        val next = machine.poll()
        assertEquals(listOf(0, 1), next.map { it.pointerId })
        assertTrue(next.all { it.continuation })
    }

    @Test
    fun `one pointer lifting leaves the other still open`() {
        down(0, 0f, 0f)
        down(1, 100f, 100f)
        runGesture()

        up(0, 0f, 0f)
        val segments = machine.poll().associateBy { it.pointerId }
        assertFalse("the released pointer closes", segments.getValue(0).willContinue)
        assertTrue("the held pointer stays open", segments.getValue(1).willContinue)

        machine.onGestureFinished(cancelled = false)
        assertEquals(setOf(1), machine.livePointerIds)
    }

    @Test
    fun `a pointer added mid-gesture starts a new stroke rather than continuing one`() {
        down(0, 0f, 0f)
        runGesture()

        down(1, 200f, 200f)
        val segments = machine.poll().associateBy { it.pointerId }
        assertTrue("the existing pointer continues", segments.getValue(0).continuation)
        assertFalse("the new pointer must not continue a stroke it never had", segments.getValue(1).continuation)
    }

    @Test
    fun `the pointer count is capped`() {
        val cap = GestureStateMachine.MAX_POINTERS
        repeat(cap + 5) { down(it, it.toFloat(), it.toFloat()) }
        assertEquals(cap, machine.poll().size)
    }

    @Test
    fun `a duplicate down does not strand the live pointer`() {
        down(0, 10f, 10f)
        runGesture()
        down(0, 999f, 999f)

        val segment = machine.poll().single()
        // Re-seeding would have produced a fresh, non-continuing stroke and
        // abandoned the open one.
        assertTrue(segment.continuation)
        assertEquals(p(10f, 10f), segment.anchor)
    }

    // -----------------------------------------------------------------------
    // Cancellation and failure
    // -----------------------------------------------------------------------

    @Test
    fun `cancel releases every live pointer instead of dropping them`() {
        down(0, 0f, 0f)
        down(1, 50f, 50f)
        runGesture()

        machine.submit(TouchAction.CANCEL, emptyList(), emptyList())
        val segments = machine.poll()
        assertEquals(2, segments.size)
        assertTrue("open strokes must be closed, not abandoned", segments.none { it.willContinue })

        machine.onGestureFinished(cancelled = false)
        assertTrue(machine.isIdle)
    }

    @Test
    fun `a cancelled gesture discards all state because strokes are invalid`() {
        down(0, 0f, 0f)
        machine.poll()
        machine.onGestureFinished(cancelled = true)
        // Continuing a cancelled stroke is illegal, so nothing may be carried over.
        assertTrue(machine.isIdle)
        assertTrue(machine.livePointerIds.isEmpty())
    }

    @Test
    fun `a refused dispatch clears state rather than wedging in flight`() {
        down(0, 0f, 0f)
        machine.poll()
        machine.onDispatchFailed()
        assertTrue(machine.isIdle)
        // A stuck inFlight flag would silently kill all further input.
        down(1, 5f, 5f)
        assertEquals(1, machine.poll().size)
    }

    @Test
    fun `moves for an unknown pointer are ignored`() {
        move(7, 10f, 10f)
        up(7, 10f, 10f)
        assertTrue(machine.poll().isEmpty())
        assertTrue(machine.isIdle)
    }

    @Test
    fun `a move after release is ignored`() {
        down(0, 0f, 0f)
        machine.poll()
        up(0, 1f, 1f)
        move(0, 500f, 500f)
        machine.onGestureFinished(cancelled = false)

        val terminal = machine.poll().single()
        assertFalse(terminal.willContinue)
        // The late move must not extend a stroke that is already closing.
        assertEquals(listOf(p(1f, 1f)), terminal.moves)
    }

    @Test
    fun `a full tap drag release cycle drains completely`() {
        down(0, 0f, 0f)
        runGesture()
        repeat(4) { i ->
            move(0, i * 10f, i * 10f)
            runGesture()
        }
        up(0, 40f, 40f)
        runGesture()
        assertTrue("no pointer may be left behind", machine.isIdle)
        assertTrue(machine.poll().isEmpty())
    }
}
