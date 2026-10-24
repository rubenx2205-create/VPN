package com.vpnshare.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyProtocolTest {
    @Test
    fun connectRequest() {
        val head = ProxyProtocol.parseHead("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")!!
        val r = ProxyProtocol.route(head)!!
        assertEquals("example.com", r.host)
        assertEquals(443, r.port)
        assertNull(r.forwardHead)
    }

    @Test
    fun absoluteUriIsRewritten() {
        val head = ProxyProtocol.parseHead(
            "GET http://example.com:8081/a/b?c=1 HTTP/1.1\r\nHost: example.com:8081\r\nProxy-Connection: keep-alive\r\nAccept: */*\r\n\r\n",
        )!!
        val r = ProxyProtocol.route(head)!!
        assertEquals("example.com", r.host)
        assertEquals(8081, r.port)
        val fwd = String(r.forwardHead!!, Charsets.ISO_8859_1)
        assertTrue(fwd.startsWith("GET /a/b?c=1 HTTP/1.1\r\n"))
        assertTrue(fwd.contains("Host: example.com:8081\r\n"))
        assertTrue(fwd.contains("Accept: */*\r\n"))
        assertFalse(fwd.contains("Proxy-Connection"))
        assertTrue(fwd.endsWith("Connection: close\r\n\r\n"))
    }

    @Test
    fun hostHeaderAddedWhenMissing() {
        val head = ProxyProtocol.parseHead("GET http://example.com HTTP/1.0\r\n\r\n")!!
        val fwd = String(ProxyProtocol.route(head)!!.forwardHead!!, Charsets.ISO_8859_1)
        assertTrue(fwd.startsWith("GET / HTTP/1.0\r\nHost: example.com\r\n"))
    }

    @Test
    fun hostPortParsing() {
        assertEquals("::1" to 443, ProxyProtocol.splitHostPort("[::1]:443", 80))
        assertEquals("::1" to 80, ProxyProtocol.splitHostPort("[::1]", 80))
        assertNull(ProxyProtocol.splitHostPort("host:99999", 80))
        assertNull(ProxyProtocol.route(ProxyProtocol.parseHead("GET ftp://x/ HTTP/1.1\r\n\r\n")!!))
    }

    @Test
    fun pacHasNoDirectFallbackForInternet() {
        val pac = ProxyProtocol.pacFile("192.168.43.1", 8080)
        assertTrue(pac.contains("return \"PROXY 192.168.43.1:8080; SOCKS5 192.168.43.1:8080; SOCKS 192.168.43.1:8080\";"))
        assertFalse(pac.substringAfterLast("return").contains("DIRECT"))
    }
}
