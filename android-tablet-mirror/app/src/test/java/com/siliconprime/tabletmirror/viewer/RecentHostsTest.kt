package com.siliconprime.tabletmirror.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentHostsTest {

    private fun host(
        address: String,
        port: Int = 7345,
        name: String = "Till",
        fingerprint: String = address,
    ) = RecentHost(address, port, name, fingerprint)

    @Test
    fun `the newest host leads the list`() {
        val list = RecentHosts.merge(listOf(host("10.0.0.2")), host("10.0.0.3"))
        assertEquals(listOf("10.0.0.3", "10.0.0.2"), list.map(RecentHost::address))
    }

    @Test
    fun `reconnecting to the same place promotes rather than duplicates`() {
        var list = listOf(host("10.0.0.2"), host("10.0.0.3"))
        list = RecentHosts.merge(list, host("10.0.0.3"))
        assertEquals(listOf("10.0.0.3", "10.0.0.2"), list.map(RecentHost::address))
    }

    @Test
    fun `a host that moved address replaces its old entry`() {
        // Same tablet, new DHCP lease. Two rows for one till would be a trap: the
        // stale one no longer works and looks identical.
        val old = RecentHost("10.0.0.2", 7345, "Till", "AB:CD")
        val moved = RecentHost("10.0.0.9", 7345, "Till", "AB:CD")
        val list = RecentHosts.merge(listOf(old), moved)
        assertEquals(listOf(moved), list)
    }

    @Test
    fun `two tablets behind one address on different ports stay separate`() {
        val list = RecentHosts.merge(
            listOf(host("10.0.0.2", port = 7345, fingerprint = "AB")),
            host("10.0.0.2", port = 7346, fingerprint = "CD"),
        )
        assertEquals(2, list.size)
    }

    @Test
    fun `one tablet that changed port keeps a single row`() {
        val list = RecentHosts.merge(
            listOf(host("10.0.0.2", port = 7345, fingerprint = "AB")),
            host("10.0.0.2", port = 7346, fingerprint = "AB"),
        )
        assertEquals(listOf(7346), list.map(RecentHost::port))
    }

    @Test
    fun `the list stops growing`() {
        var list = emptyList<RecentHost>()
        repeat(RecentHosts.MAX + 3) { list = RecentHosts.merge(list, host("10.0.0.$it")) }
        assertEquals(RecentHosts.MAX, list.size)
        assertEquals("10.0.0.${RecentHosts.MAX + 2}", list.first().address)
    }

    @Test
    fun `a stored list survives the round trip`() {
        val list = listOf(host("10.0.0.2", name = "Front till"), host("10.0.0.3", name = "Back till"))
        assertEquals(list, RecentHosts.decode(RecentHosts.encode(list)))
    }

    @Test
    fun `a device name containing a delimiter cannot split the record`() {
        val awkward = host("10.0.0.2", name = "Till\tone\ntwo")
        val decoded = RecentHosts.decode(RecentHosts.encode(listOf(awkward)))
        assertEquals(1, decoded.size)
        assertEquals("Till one two", decoded.first().name)
        assertEquals("10.0.0.2", decoded.first().address)
    }

    @Test
    fun `nothing stored decodes to nothing`() {
        assertTrue(RecentHosts.decode(null).isEmpty())
        assertTrue(RecentHosts.decode("").isEmpty())
    }

    @Test
    fun `damaged records are dropped rather than crashing`() {
        val text = "10.0.0.2\t7345\tTill\tAB:CD\nrubbish\n10.0.0.3\tnotaport\tTill\tEF"
        assertEquals(listOf("10.0.0.2"), RecentHosts.decode(text).map(RecentHost::address))
    }

    @Test
    fun `an out of range port is rejected`() {
        assertTrue(RecentHosts.decode("10.0.0.2\t0\tTill\tAB").isEmpty())
        assertTrue(RecentHosts.decode("10.0.0.2\t70000\tTill\tAB").isEmpty())
    }

    @Test
    fun `an endpoint can be looked up to recover its fingerprint`() {
        val list = listOf(host("10.0.0.2", fingerprint = "AB:CD"), host("10.0.0.3"))
        assertEquals("AB:CD", RecentHosts.forEndpoint(list, Endpoint("10.0.0.2", 7345))?.fingerprint)
        assertNull(RecentHosts.forEndpoint(list, Endpoint("10.0.0.4", 7345)))
    }
}
