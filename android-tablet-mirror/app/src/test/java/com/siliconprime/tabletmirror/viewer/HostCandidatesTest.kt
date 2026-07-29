package com.siliconprime.tabletmirror.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCandidatesTest {

    private val saved = Endpoint("192.168.1.20", 45123)

    @Test
    fun `the remembered address is tried first`() {
        // It is nearly always still correct, so it should be the first thing tried.
        val discovered = listOf(Endpoint("192.168.1.55", 45123))
        assertEquals(listOf(saved) + discovered, HostCandidates.order(saved, discovered))
    }

    @Test
    fun `discovery fills in when nothing is remembered`() {
        val discovered = listOf(Endpoint("192.168.1.55", 45123))
        assertEquals(discovered, HostCandidates.order(null, discovered))
    }

    @Test
    fun `a discovered duplicate of the remembered address is not tried twice`() {
        val discovered = listOf(saved, Endpoint("192.168.1.99", 45123))
        val ordered = HostCandidates.order(saved, discovered)
        assertEquals(2, ordered.size)
        assertEquals(saved, ordered.first())
    }

    @Test
    fun `the same address on a different port is a separate candidate`() {
        val other = Endpoint(saved.address, 45124)
        assertEquals(listOf(saved, other), HostCandidates.order(saved, listOf(other)))
    }

    @Test
    fun `an empty network yields just the remembered address`() {
        assertEquals(listOf(saved), HostCandidates.order(saved, emptyList()))
    }

    @Test
    fun `nothing known yields nothing to try`() {
        assertTrue(HostCandidates.order(null, emptyList()).isEmpty())
    }

    @Test
    fun `the candidate list is capped`() {
        // A chatty or hostile network must not make one retry pass unbounded.
        val many = (1..50).map { Endpoint("192.168.1.$it", 45123) }
        assertEquals(HostCandidates.MAX_CANDIDATES, HostCandidates.order(saved, many).size)
    }

    @Test
    fun `the remembered address survives the cap`() {
        val many = (1..50).map { Endpoint("10.0.0.$it", 45123) }
        assertEquals(saved, HostCandidates.order(saved, many).first())
    }
}
