package com.siliconprime.tabletmirror.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class PairingGateTest {

    private var now = 10_000L
    private val gate = PairingGate(clock = { now }, confirmTimeoutMs = 500L)
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `pairing is closed until it is deliberately opened`() {
        // The default matters: a tablet going about its business must refuse an
        // unknown device outright rather than prompting anyone.
        assertFalse(gate.isPairingOpen())
        gate.openWindow(60_000L)
        assertTrue(gate.isPairingOpen())
    }

    @Test
    fun `the window expires on its own`() {
        gate.openWindow(60_000L)
        now += 59_999
        assertTrue(gate.isPairingOpen())
        now += 2
        assertFalse("an unattended window must not stay open", gate.isPairingOpen())
    }

    @Test
    fun `closing the window revokes pairing immediately`() {
        gate.openWindow(60_000L)
        gate.closeWindow()
        assertFalse(gate.isPairingOpen())
        assertEquals(0L, gate.remainingWindowMs())
    }

    @Test
    fun `remaining time never goes negative`() {
        gate.openWindow(1_000L)
        now += 10_000
        assertEquals(0L, gate.remainingWindowMs())
    }

    @Test
    fun `a confirmed code is accepted and clears the prompt`() {
        gate.openWindow(60_000L)
        val outcome = ArrayBlockingQueue<Boolean>(1)
        Thread {
            outcome.put(gate.confirmPairing("Kitchen Tablet", key, "482913"))
        }.apply { isDaemon = true }.start()

        val request = awaitRequest()
        assertEquals("482913", request.sas)
        assertEquals("Kitchen Tablet", request.peerName)

        gate.respond(accept = true)
        assertEquals(true, outcome.poll(5, TimeUnit.SECONDS))
        assertNull("the prompt must be dismissed", gate.pending.value)
    }

    @Test
    fun `a rejected code is refused`() {
        gate.openWindow(60_000L)
        val outcome = ArrayBlockingQueue<Boolean>(1)
        Thread { outcome.put(gate.confirmPairing("Kitchen", key, "111111")) }
            .apply { isDaemon = true }.start()
        awaitRequest()
        gate.respond(accept = false)
        assertEquals(false, outcome.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an unanswered prompt times out as a refusal`() {
        gate.openWindow(60_000L)
        // Never responding must not pin a stranger, and must not hold the
        // connection thread open forever.
        assertFalse(gate.confirmPairing("Nobody", key, "000000"))
        assertNull(gate.pending.value)
    }

    @Test
    fun `one window allows only one pairing`() {
        gate.openWindow(60_000L)
        val outcome = ArrayBlockingQueue<Boolean>(1)
        Thread { outcome.put(gate.confirmPairing("First", key, "222222")) }
            .apply { isDaemon = true }.start()
        awaitRequest()
        gate.respond(accept = true)
        assertEquals(true, outcome.poll(5, TimeUnit.SECONDS))
        // A second device must not be able to slip in behind the first.
        assertFalse(gate.isPairingOpen())
    }

    @Test
    fun `closing the window releases a waiting prompt`() {
        gate.openWindow(60_000L)
        val outcome = ArrayBlockingQueue<Boolean>(1)
        Thread { outcome.put(gate.confirmPairing("First", key, "333333")) }
            .apply { isDaemon = true }.start()
        awaitRequest()
        gate.closeWindow()
        assertEquals(false, outcome.poll(5, TimeUnit.SECONDS))
    }

    private fun awaitRequest(): PairingGate.Request {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            gate.pending.value?.let { return it }
            Thread.sleep(5)
        }
        val request = gate.pending.value
        assertNotNull("no pairing prompt appeared", request)
        return request!!
    }
}
