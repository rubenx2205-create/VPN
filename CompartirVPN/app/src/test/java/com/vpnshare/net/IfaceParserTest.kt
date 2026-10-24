package com.vpnshare.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IfaceParserTest {
    @Test
    fun parsesIpAddrOutput() {
        val out = listOf(
            "1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever",
            "30: wlan0    inet 192.168.1.34/24 brd 192.168.1.255 scope global wlan0\\       valid_lft forever",
            "41: ap0    inet 192.168.43.1/24 brd 192.168.43.255 scope global ap0\\       valid_lft forever",
            "52: tun0    inet 198.18.0.1/24 scope global tun0\\       valid_lft forever",
            "60: rmnet_data2@rmnet_ipa0    inet 10.12.3.4/30 scope global rmnet_data2",
        )
        val addrs = IfaceParser.parse(out)
        assertEquals(listOf("lo", "wlan0", "ap0", "tun0", "rmnet_data2"), addrs.map { it.iface })
        assertEquals("192.168.43.0/24", addrs[2].cidr)
        assertEquals("10.12.3.4/30", addrs[4].cidr)
    }

    @Test
    fun classifiesInterfaces() {
        assertTrue(IfaceParser.isDownstreamName("ap0"))
        assertTrue(IfaceParser.isDownstreamName("swlan0"))
        assertTrue(IfaceParser.isDownstreamName("rndis0"))
        assertTrue(IfaceParser.isDownstreamName("p2p-wlan0-0"))
        assertFalse(IfaceParser.isDownstreamName("wlan0"))
        assertFalse(IfaceParser.isDownstreamName("rmnet_data0"))
        assertTrue(IfaceParser.isUpstreamName("tun0"))
        assertFalse(IfaceParser.isUpstreamName("wlan0"))
    }

    @Test
    fun computesNetwork() {
        assertEquals("172.20.10.0", networkOf("172.20.10.1", 28))
        assertEquals("0.0.0.0", networkOf("8.8.8.8", 0))
    }

    @Test
    fun countsNeighbors() {
        val out = listOf(
            "192.168.43.10 lladdr aa:bb:cc:dd:ee:ff REACHABLE",
            "192.168.43.11 lladdr aa:bb:cc:dd:ee:00 STALE",
            "192.168.43.12  FAILED",
            "",
        )
        assertEquals(2, IfaceParser.countNeighbors(out))
    }
}
