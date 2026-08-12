package com.siliconprime.tabletmirror.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateSweepTest {

    @Test
    fun `a fresh sweep concludes nothing`() {
        assertFalse(CandidateSweep().allNotPaired)
    }

    @Test
    fun `the only host refusing us is conclusive`() {
        val sweep = CandidateSweep()
        sweep.record(SessionEnd.NOT_PAIRED)
        assertTrue(sweep.allNotPaired)
    }

    @Test
    fun `one refusal among reachable hosts is not conclusive`() {
        // The classic false positive: some other device answered on an address the
        // host used to hold. Somewhere else in the list may still be the real one.
        val sweep = CandidateSweep()
        sweep.record(SessionEnd.NOT_PAIRED)
        sweep.record(SessionEnd.TRANSIENT)
        assertFalse(sweep.allNotPaired)
    }

    @Test
    fun `every address refusing us is conclusive however many there are`() {
        val sweep = CandidateSweep()
        repeat(4) { sweep.record(SessionEnd.NOT_PAIRED) }
        assertTrue(sweep.allNotPaired)
    }

    @Test
    fun `a transient failure anywhere in the pass withholds the verdict`() {
        val sweep = CandidateSweep()
        sweep.record(SessionEnd.NOT_PAIRED)
        sweep.record(SessionEnd.NOT_PAIRED)
        sweep.record(SessionEnd.TRANSIENT)
        assertFalse(sweep.allNotPaired)
    }

    @Test
    fun `resetting clears a previous verdict`() {
        val sweep = CandidateSweep()
        sweep.record(SessionEnd.NOT_PAIRED)
        sweep.reset()
        assertFalse(sweep.allNotPaired)
    }

    @Test
    fun `a later pass can reach the verdict the previous one missed`() {
        val sweep = CandidateSweep()
        sweep.record(SessionEnd.TRANSIENT)
        assertFalse(sweep.allNotPaired)
        sweep.reset()
        sweep.record(SessionEnd.NOT_PAIRED)
        assertTrue(sweep.allNotPaired)
    }
}
