package com.siliconprime.tabletmirror.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    private val policy = ReconnectPolicy(baseDelayMs = 1_000L, maxDelayMs = 30_000L)

    @Test
    fun `the first retry is prompt`() {
        // Much the commonest failure is a blip that has already cleared, so the
        // first retry should not make anyone wait.
        assertEquals(1_000L, policy.delayFor(1))
    }

    @Test
    fun `backoff doubles then settles at the ceiling`() {
        assertEquals(2_000L, policy.delayFor(2))
        assertEquals(4_000L, policy.delayFor(3))
        assertEquals(8_000L, policy.delayFor(4))
        assertEquals(16_000L, policy.delayFor(5))
        assertEquals(30_000L, policy.delayFor(6))
        assertEquals(30_000L, policy.delayFor(7))
    }

    @Test
    fun `the delay stays bounded for a tablet left running for days`() {
        // No overflow and no runaway: this loop may legitimately run all week.
        for (attempt in intArrayOf(20, 100, 1_000, 100_000, Int.MAX_VALUE)) {
            assertEquals("attempt $attempt", 30_000L, policy.delayFor(attempt))
        }
    }

    @Test
    fun `a zero or negative attempt is treated as the first`() {
        assertEquals(1_000L, policy.delayFor(0))
        assertEquals(1_000L, policy.delayFor(-5))
    }

    @Test
    fun `transient failures always retry`() {
        // Wi-Fi drops, host restarts and a busy host must all recover unattended.
        assertFalse(policy.isFatal(SessionEnd.TRANSIENT, hasPairedHost = true))
        assertFalse(policy.isFatal(SessionEnd.TRANSIENT, hasPairedHost = false))
    }

    @Test
    fun `leaving the screen stops the loop`() {
        assertTrue(policy.isFatal(SessionEnd.LOCAL, hasPairedHost = true))
    }

    @Test
    fun `definite misconfiguration stops the loop`() {
        assertTrue(policy.isFatal(SessionEnd.FATAL, hasPairedHost = true))
    }

    @Test
    fun `an unpaired answer keeps retrying only when something is paired`() {
        // Already paired: we probably reached the wrong device, e.g. a recycled
        // DHCP address, so keep looking.
        assertFalse(policy.isFatal(SessionEnd.NOT_PAIRED, hasPairedHost = true))
        // Nothing paired at all: no amount of retrying can help.
        assertTrue(policy.isFatal(SessionEnd.NOT_PAIRED, hasPairedHost = false))
    }

    @Test
    fun `custom bounds are respected`() {
        val fast = ReconnectPolicy(baseDelayMs = 250L, maxDelayMs = 1_000L)
        assertEquals(250L, fast.delayFor(1))
        assertEquals(500L, fast.delayFor(2))
        assertEquals(1_000L, fast.delayFor(3))
        assertEquals(1_000L, fast.delayFor(9))
    }
}
