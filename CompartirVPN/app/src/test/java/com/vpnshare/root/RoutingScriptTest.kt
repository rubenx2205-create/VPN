package com.vpnshare.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingScriptTest {
    private val up = Upstream("tun0", "198.18.0.2")
    private val ap = Downstream("ap0", "192.168.43.0/24")

    @Test
    fun applyRoutesHotspotThroughTunnel() {
        val s = RoutingScript.apply(up, listOf(ap), killSwitch = true)
        assertTrue(s.contains("ip rule add iif ap0 lookup 7777 pref 17800"))
        assertTrue(s.contains("ip route add default dev tun0 table 7777"))
        assertTrue(s.contains("ip rule add iif tun0 lookup 7778 pref 17801"))
        assertTrue(s.contains("ip route add 192.168.43.0/24 dev ap0 table 7778"))
        assertTrue(s.contains("-o tun0 -j MASQUERADE"))
        assertTrue(s.contains("-i ap0 -p udp --dport 53 -j DNAT --to-destination 198.18.0.2"))
        assertTrue(s.contains("ip6tables -w -A vpnshare_fwd6 -i ap0 -j REJECT"))
        // El ACCEPT hacia el túnel debe ir antes que el REJECT del kill switch.
        assertTrue(s.indexOf("-i ap0 -o tun0 -j ACCEPT") < s.indexOf("-A vpnshare_fwd -i ap0 -j REJECT"))
    }

    @Test
    fun vpnDownWithKillSwitchBlocksClients() {
        val s = RoutingScript.apply(null, listOf(ap), killSwitch = true)
        assertTrue(s.contains("ip route add unreachable default metric 9999 table 7777"))
        assertTrue(s.contains("ip rule add iif ap0 lookup 7777 pref 17800"))
        assertTrue(s.contains("-A vpnshare_fwd -i ap0 -j REJECT"))
        assertTrue(s.contains("-A vpnshare_in -i ap0 -p udp --dport 53 -j REJECT"))
        assertFalse(s.contains("MASQUERADE"))
    }

    @Test
    fun vpnDownWithoutKillSwitchOnlyCleansUp() {
        assertEquals(RoutingScript.teardown(), RoutingScript.apply(null, listOf(ap), killSwitch = false))
        assertEquals(RoutingScript.teardown(), RoutingScript.apply(up, emptyList(), killSwitch = true))
    }

    @Test
    fun teardownRemovesEverything() {
        val s = RoutingScript.teardown()
        assertTrue(s.contains("ip rule del pref 17800"))
        assertTrue(s.contains("ip route flush table 7777"))
        assertTrue(s.contains("-D FORWARD -j vpnshare_fwd"))
        assertTrue(s.contains("-t nat -X vpnshare_post"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInjectedInterfaceNames() {
        RoutingScript.apply(Upstream("tun0; reboot", null), listOf(ap), killSwitch = false)
    }
}
