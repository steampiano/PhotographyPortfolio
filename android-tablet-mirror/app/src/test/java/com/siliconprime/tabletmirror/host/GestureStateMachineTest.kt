package com.siliconprime.tabletmirror.host

import com.siliconprime.tabletmirror.net.TouchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureStateMachineTest {

    private var now = 1_000L
    private val machine = GestureStateMachine(clock = { now }, touchSlopPx = 16f)

    private fun p(x: Float, y: Float) = GestureStateMachine.Point(x, y)

    private fun down(id: Int, x: Float, y: Float) =
        machine.submit(TouchAction.DOWN, listOf(id), listOf(p(x, y)))

    private fun move(id: Int, x: Float, y: Float) =
        machine.submit(TouchAction.MOVE, listOf(id), listOf(p(x, y)))

    private fun up(id: Int, x: Float, y: Float) =
        machine.submit(TouchAction.UP, listOf(id), listOf(p(x, y)))

    /** Presses and pushes past the tap deadline, so the pointer is a hold/drag. */
    private fun downAndHold(id: Int, x: Float, y: Float) {
        down(id, x, y)
        now += GestureStateMachine.TAP_DEADLINE_MS + 1
    }

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
    fun `a tap is one self-contained stroke, not a continued one`() {
        down(0, 100f, 200f)
        // Nothing goes out yet: this might still become a tap.
        assertTrue("a press must not dispatch before the release", machine.poll().isEmpty())

        up(0, 100f, 200f)
        val segment = machine.poll().single()
        assertEquals(p(100f, 200f), segment.anchor)
        assertFalse("a tap must not continue into another stroke", segment.continuation)
        assertFalse("a tap must close within its own gesture", segment.willContinue)
        // Splitting a tap across two dispatches leaves the touch held open between
        // them, which the host's UI reads as a press rather than a click.

        machine.onGestureFinished(cancelled = false)
        assertTrue(machine.isIdle)
    }

    @Test
    fun `a tap dispatches as soon as the release arrives, without waiting out the deadline`() {
        down(0, 5f, 5f)
        now += 20
        assertTrue(machine.poll().isEmpty())
        up(0, 5f, 5f)
        // Latency for a tap is the network only; the deadline is a ceiling, not a wait.
        assertEquals(1, machine.poll().size)
    }

    @Test
    fun `a slow but ordinary tap still goes out as a single stroke`() {
        down(0, 40f, 40f)
        // 300ms is a perfectly normal deliberate tap, and it must not be mistaken
        // for a hold: that is what makes home screen icons offer to uninstall.
        now += 300
        assertTrue(machine.poll().isEmpty())

        up(0, 40f, 40f)
        val segment = machine.poll().single()
        assertFalse("a 300ms press is a tap, not a hold", segment.willContinue)
        assertFalse(segment.continuation)
    }

    @Test
    fun `the tap deadline stays clear of Android's long-press threshold`() {
        // Above 500ms the platform would call it a long press anyway, so deciding
        // later than that could never produce a click.
        assertTrue(
            "deadline must leave room before the 500ms long-press timeout",
            GestureStateMachine.TAP_DEADLINE_MS in 250L..450L,
        )
    }

    @Test
    fun `a press held past the deadline becomes a continued stroke`() {
        down(0, 10f, 10f)
        assertTrue(machine.poll().isEmpty())

        now += GestureStateMachine.TAP_DEADLINE_MS + 1
        val segment = machine.poll().single()
        assertTrue("a hold must stay open", segment.willContinue)
        assertFalse(segment.continuation)
    }

    @Test
    fun `the machine asks to be re-polled while a press is undecided`() {
        down(0, 1f, 1f)
        machine.poll()
        val wake = machine.pendingWakeUpMs()
        assertNotNull("a still finger would otherwise never dispatch", wake)
        assertTrue(wake!! in 1..GestureStateMachine.TAP_DEADLINE_MS)

        now += GestureStateMachine.TAP_DEADLINE_MS
        assertEquals(0L, machine.pendingWakeUpMs())

        machine.poll()
        machine.onGestureFinished(cancelled = false)
        // The press has been committed, so nothing is waiting on the tap deadline
        // any more.
        assertNull(machine.pendingWakeUpMs())
    }

    @Test
    fun `movement before the deadline starts a drag immediately`() {
        down(0, 0f, 0f)
        move(0, 30f, 0f)
        // A drag must not pay the tap deadline: the movement already settles it.
        val segment = machine.poll().single()
        assertTrue(segment.willContinue)
        assertEquals(listOf(p(30f, 0f)), segment.moves)

        machine.onGestureFinished(cancelled = false)
        // Nothing is waiting on the tap deadline: the movement already settled it.
        assertNull(machine.pendingWakeUpMs())
    }

    @Test
    fun `jitter within slop does not turn a tap into a held stroke`() {
        down(0, 100f, 100f)
        // A real finger emits a MOVE or two of roll before it lifts. Treating that
        // as the start of a drag is what made taps fail at random: whether jitter
        // beat the release to the machine decided whether the tap worked.
        move(0, 102f, 101f)
        move(0, 103f, 100f)
        assertTrue("jitter must not commit the press", machine.poll().isEmpty())

        up(0, 102f, 101f)
        val segment = machine.poll().single()
        assertFalse("must still be a tap", segment.willContinue)
        assertFalse(segment.continuation)
        assertTrue("and must not travel", segment.moves.isEmpty())
        assertEquals(p(100f, 100f), segment.anchor)
    }

    @Test
    fun `a jittering press still gets re-polled so a held finger is dispatched`() {
        down(0, 100f, 100f)
        move(0, 101f, 101f)
        // Without this the wake-up filter would skip a press that had any sample,
        // and a finger held still after a wobble would never dispatch at all.
        assertNotNull(machine.pendingWakeUpMs())
    }

    @Test
    fun `movement beyond slop still starts a drag at once`() {
        down(0, 100f, 100f)
        move(0, 100f, 140f)
        val segment = machine.poll().single()
        assertTrue("a real drag must not wait for the tap deadline", segment.willContinue)
        assertEquals(listOf(p(100f, 140f)), segment.moves)
    }

    @Test
    fun `a tap that wobbles within slop is dispatched without moving`() {
        down(0, 100f, 100f)
        // A finger always drifts a little between press and release, and that drift
        // is magnified when the viewer's video is smaller than the host's screen.
        up(0, 106f, 104f)

        val segment = machine.poll().single()
        assertEquals(p(100f, 100f), segment.anchor)
        // Carrying the drift into the stroke lets a scrollable ancestor claim the
        // gesture as a scroll, which cancels the click: the button ripples and then
        // does nothing.
        assertTrue("a tap must not travel", segment.moves.isEmpty())
        assertFalse(segment.willContinue)
    }

    @Test
    fun `a release beyond slop keeps its movement`() {
        down(0, 100f, 100f)
        up(0, 400f, 100f)
        // That far is a real flick, not wobble, so the path is preserved.
        assertEquals(listOf(p(400f, 100f)), machine.poll().single().moves)
    }

    @Test
    fun `slop is measured from the press point, not between samples`() {
        down(0, 0f, 0f)
        // Each step is small, but the total is well beyond slop.
        machine.submit(TouchAction.MOVE, listOf(0), listOf(p(10f, 0f)))
        machine.submit(TouchAction.MOVE, listOf(0), listOf(p(20f, 0f)))
        machine.submit(TouchAction.UP, listOf(0), listOf(p(30f, 0f)))
        assertEquals(3, machine.poll().single().moves.size)
    }

    @Test
    fun `a quick flick is delivered as one stroke including its movement`() {
        down(0, 0f, 0f)
        machine.submit(TouchAction.MOVE, listOf(0), listOf(p(10f, 0f)))
        machine.submit(TouchAction.UP, listOf(0), listOf(p(20f, 0f)))
        val segment = machine.poll().single()
        assertFalse(segment.willContinue)
        assertEquals(listOf(p(10f, 0f), p(20f, 0f)), segment.moves)
    }

    @Test
    fun `a tap starting before the previous result arrives is not swallowed`() {
        // Tapping at any normal pace means the next press begins before the platform
        // has reported the last gesture. Both taps must land.
        down(0, 10f, 10f)
        up(0, 10f, 10f)
        assertEquals(1, machine.poll().size)

        // Same pointer id, as every single-finger tap uses. No callback yet.
        down(0, 50f, 50f)
        up(0, 50f, 50f)

        machine.onGestureFinished(cancelled = false)
        val second = machine.poll().single()
        assertEquals("the second tap must land where it was pressed", p(50f, 50f), second.anchor)
        assertFalse(second.willContinue)
    }

    @Test
    fun `a finished pointer is retired as its terminal segment goes out`() {
        down(0, 1f, 1f)
        up(0, 1f, 1f)
        machine.poll()
        // Holding the slot until the callback is what lost the next touch: a new
        // DOWN for this id would be rejected as a duplicate.
        assertTrue(machine.livePointerIds.isEmpty())
    }

    @Test
    fun `a run of taps all land`() {
        repeat(5) { i ->
            val at = 10f + i * 20f
            down(0, at, at)
            up(0, at, at)
            val segment = machine.poll().single()
            assertEquals("tap $i", p(at, at), segment.anchor)
            machine.onGestureFinished(cancelled = false)
        }
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
        downAndHold(0, 0f, 0f)
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
        downAndHold(0, 0f, 0f)
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
        downAndHold(0, 0f, 0f)
        machine.poll()
        repeat(60) { move(0, it.toFloat(), 0f) }
        machine.onGestureFinished(cancelled = false)

        val segment = machine.poll().single()
        assertEquals(GestureStateMachine.MAX_SEGMENT_MS, segment.durationMs)
    }

    @Test
    fun `a single sample still gets the minimum duration`() {
        downAndHold(0, 0f, 0f)
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
        downAndHold(0, 50f, 60f)
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
        downAndHold(0, 50f, 60f)
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
        now += GestureStateMachine.TAP_DEADLINE_MS + 1

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
        now += GestureStateMachine.TAP_DEADLINE_MS + 1
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
        downAndHold(0, 0f, 0f)
        runGesture()

        down(1, 200f, 200f)
        now += GestureStateMachine.TAP_DEADLINE_MS + 1
        val segments = machine.poll().associateBy { it.pointerId }
        assertTrue("the existing pointer continues", segments.getValue(0).continuation)
        assertFalse("the new pointer must not continue a stroke it never had", segments.getValue(1).continuation)
    }

    @Test
    fun `the pointer count is capped`() {
        val cap = GestureStateMachine.MAX_POINTERS
        repeat(cap + 5) { down(it, it.toFloat(), it.toFloat()) }
        now += GestureStateMachine.TAP_DEADLINE_MS + 1
        assertEquals(cap, machine.poll().size)
    }

    @Test
    fun `a duplicate down does not strand the live pointer`() {
        downAndHold(0, 10f, 10f)
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
        now += GestureStateMachine.TAP_DEADLINE_MS + 1
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
        downAndHold(0, 0f, 0f)
        machine.poll()
        machine.onGestureFinished(cancelled = true)
        // Continuing a cancelled stroke is illegal, so nothing may be carried over.
        assertTrue(machine.isIdle)
        assertTrue(machine.livePointerIds.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Recovering from a lost dispatch result
    // -----------------------------------------------------------------------

    @Test
    fun `a gesture whose result never arrives does not wedge input forever`() {
        down(0, 10f, 10f)
        up(0, 10f, 10f)
        assertEquals(1, machine.poll().size)

        // The platform never calls back. Without a watchdog this is terminal:
        // inFlight stays set and every later tap is dropped in silence, which looks
        // like "it worked once or twice and then stopped".
        assertTrue(machine.poll().isEmpty())

        now += GestureStateMachine.DISPATCH_TIMEOUT_MS + 1
        assertTrue("the stuck gesture must be abandoned", machine.expireStuckGesture())

        down(1, 20f, 20f)
        up(1, 20f, 20f)
        assertEquals("input must work again afterwards", 1, machine.poll().size)
    }

    @Test
    fun `a gesture still within the timeout is left alone`() {
        downAndHold(0, 0f, 0f)
        machine.poll()
        now += GestureStateMachine.DISPATCH_TIMEOUT_MS - 1
        assertFalse("a merely slow device must not lose its gesture", machine.expireStuckGesture())
    }

    @Test
    fun `expiring is a no-op when nothing is in flight`() {
        assertFalse(machine.expireStuckGesture())
        now += GestureStateMachine.DISPATCH_TIMEOUT_MS * 10
        assertFalse(machine.expireStuckGesture())
    }

    @Test
    fun `the watchdog is what the machine waits on while a gesture is out`() {
        downAndHold(0, 0f, 0f)
        machine.poll()
        val wake = machine.pendingWakeUpMs()
        assertNotNull("something must re-poll, or the watchdog never runs", wake)
        assertTrue(wake!! <= GestureStateMachine.DISPATCH_TIMEOUT_MS)
    }

    @Test
    fun `the timeout leaves room for the longest segment we ever send`() {
        assertTrue(
            "must not fire on a slow device mid-gesture",
            GestureStateMachine.DISPATCH_TIMEOUT_MS > GestureStateMachine.MAX_SEGMENT_MS * 5,
        )
    }

    @Test
    fun `a refused dispatch clears state rather than wedging in flight`() {
        downAndHold(0, 0f, 0f)
        machine.poll()
        machine.onDispatchFailed()
        assertTrue(machine.isIdle)
        // A stuck inFlight flag would silently kill all further input.
        downAndHold(1, 5f, 5f)
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
        downAndHold(0, 0f, 0f)
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
        downAndHold(0, 0f, 0f)
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
