package com.siliconprime.tabletmirror.viewer

import com.siliconprime.tabletmirror.viewer.FrameGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {

    private val key = true
    private val inter = false

    /** Feeds a gate, tracking the queue the way the decoder would. */
    private class Sink(private val gate: FrameGate) {
        var queued = 0
            private set
        var decoded = 0
            private set

        fun offer(isKeyFrame: Boolean): Verdict {
            val verdict = gate.offer(isKeyFrame, queued)
            when (verdict) {
                Verdict.DECODE -> {
                    queued++
                    decoded++
                }
                Verdict.DROP -> Unit
                Verdict.DROP_ALL -> queued = 0
                Verdict.FLUSH_AND_DECODE -> {
                    queued = 1
                    decoded++
                }
            }
            return verdict
        }

        /** The decoder consuming what it was given. */
        fun drain() {
            queued = 0
        }
    }

    // -----------------------------------------------------------------------
    // Starting up
    // -----------------------------------------------------------------------

    @Test
    fun `nothing is decoded before the first keyframe`() {
        val gate = FrameGate()
        assertEquals(Verdict.DROP, gate.offer(inter, 0))
        assertEquals(Verdict.DROP, gate.offer(inter, 0))
        assertTrue(gate.stalled)
    }

    @Test
    fun `the first keyframe starts the stream`() {
        val gate = FrameGate()
        assertEquals(Verdict.DECODE, gate.offer(key, 0))
        assertFalse(gate.stalled)
        assertEquals(Verdict.DECODE, gate.offer(inter, 0))
    }

    // -----------------------------------------------------------------------
    // Falling behind — the freeze this class exists to prevent
    // -----------------------------------------------------------------------

    @Test
    fun `a backlog discards the whole chain rather than a single frame`() {
        // Dropping just the oldest frame is what froze the picture: everything left
        // in the queue refers to the frame that was thrown away.
        val gate = FrameGate(capacity = 3)
        assertEquals(Verdict.DECODE, gate.offer(key, 0))
        assertEquals(Verdict.DROP_ALL, gate.offer(inter, 3))
        assertTrue(gate.stalled)
    }

    @Test
    fun `after a backlog nothing is decoded until the next keyframe`() {
        val gate = FrameGate(capacity = 2)
        gate.offer(key, 0)
        assertEquals(Verdict.DROP_ALL, gate.offer(inter, 2))
        repeat(5) { assertEquals(Verdict.DROP, gate.offer(inter, 0)) }
        assertEquals(Verdict.DECODE, gate.offer(key, 0))
        assertFalse(gate.stalled)
    }

    @Test
    fun `a keyframe arriving during a backlog recovers with no wait at all`() {
        val gate = FrameGate(capacity = 2)
        gate.offer(key, 0)
        assertEquals(Verdict.FLUSH_AND_DECODE, gate.offer(key, 2))
        assertFalse(gate.stalled)
    }

    @Test
    fun `recovery is complete, so a later backlog behaves like the first`() {
        val gate = FrameGate(capacity = 2)
        gate.offer(key, 0)
        gate.offer(inter, 2)
        gate.offer(key, 0)
        assertEquals(Verdict.DROP_ALL, gate.offer(inter, 2))
        assertEquals(Verdict.DECODE, gate.offer(key, 0))
    }

    // -----------------------------------------------------------------------
    // Against a running queue
    // -----------------------------------------------------------------------

    @Test
    fun `a steady stream that keeps up is never dropped`() {
        val sink = Sink(FrameGate(capacity = 4))
        sink.offer(key)
        repeat(50) {
            sink.offer(inter)
            sink.drain()
        }
        assertEquals(51, sink.decoded)
    }

    @Test
    fun `a stall recovers on the next keyframe and loses nothing after it`() {
        val gate = FrameGate(capacity = 4)
        val sink = Sink(gate)
        sink.offer(key)

        // The decoder stops consuming: frames pile up until the gate gives up.
        repeat(20) { sink.offer(inter) }
        assertTrue(gate.stalled)

        // Consumption resumes, but only a keyframe can restart the picture.
        sink.drain()
        val beforeKey = sink.decoded
        repeat(10) {
            sink.offer(inter)
            sink.drain()
        }
        assertEquals("interframes must not be decoded while stalled", beforeKey, sink.decoded)

        sink.offer(key)
        sink.drain()
        repeat(10) {
            sink.offer(inter)
            sink.drain()
        }
        assertEquals(beforeKey + 11, sink.decoded)
        assertFalse(gate.stalled)
    }

    @Test
    fun `a queue at capacity minus one is still accepted`() {
        val gate = FrameGate(capacity = 4)
        gate.offer(key, 0)
        assertEquals(Verdict.DECODE, gate.offer(inter, 3))
    }

    @Test
    fun `a capacity of one still makes progress on a draining queue`() {
        val gate = FrameGate(capacity = 1)
        assertEquals(Verdict.DECODE, gate.offer(key, 0))
        assertEquals(Verdict.DECODE, gate.offer(inter, 0))
        assertEquals(Verdict.DROP_ALL, gate.offer(inter, 1))
    }
}
