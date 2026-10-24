package com.vpnshare.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Prueba el proxy con sockets reales en 127.0.0.1 (la conexión de salida no usa el VPN aquí). */
class ProxyServerTest {
    private lateinit var origin: ServerSocket
    private lateinit var proxy: ProxyServer
    private val connected = ArrayList<String>()

    @Before
    fun setUp() {
        // Servidor de origen: si recibe una petición HTTP devuelve su primera línea; si no, hace eco.
        origin = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!origin.isClosed) {
                val s = try { origin.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    s.use {
                        val buf = ByteArray(4096)
                        val n = it.getInputStream().read(buf)
                        if (n <= 0) return@use
                        val text = String(buf, 0, n, Charsets.ISO_8859_1)
                        if (text.startsWith("GET ")) {
                            val body = "linea=" + text.substringBefore("\r\n")
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                        } else {
                            it.getOutputStream().write(buf, 0, n)
                        }
                    }
                }
            }
        }
        proxy = ProxyServer(0, { host, port ->
            synchronized(connected) { connected += "$host:$port" }
            if (host == "falla.invalid") throw IllegalStateException("El VPN no está activo")
            Socket(InetAddress.getLoopbackAddress(), port)
        }) { "192.168.43.1" }
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        origin.close()
    }

    private fun client() = Socket(InetAddress.getLoopbackAddress(), proxy.localPort).apply { soTimeout = 5000 }

    private fun readAll(s: Socket) = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)

    @Test
    fun httpsConnectTunnel() {
        client().use { c ->
            c.getOutputStream().write("CONNECT example.com:${origin.localPort} HTTP/1.1\r\n\r\n".toByteArray())
            val din = DataInputStream(c.getInputStream())
            val head = ByteArray("HTTP/1.1 200 Connection Established\r\n\r\n".length).also { din.readFully(it) }
            assertTrue(String(head).startsWith("HTTP/1.1 200"))
            c.getOutputStream().write("hola".toByteArray())
            val echo = ByteArray(4).also { din.readFully(it) }
            assertEquals("hola", String(echo))
        }
        assertEquals("example.com:${origin.localPort}", connected.single())
    }

    @Test
    fun plainHttpIsRewritten() {
        client().use { c ->
            c.getOutputStream().write("GET http://example.com:${origin.localPort}/ruta?a=1 HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
            assertTrue(readAll(c).endsWith("linea=GET /ruta?a=1 HTTP/1.1"))
        }
    }

    @Test
    fun socks5WithDomain() {
        client().use { c ->
            val out = c.getOutputStream()
            val din = DataInputStream(c.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            assertEquals(5, din.readUnsignedByte()); assertEquals(0, din.readUnsignedByte())
            val host = "ejemplo.org".toByteArray()
            val port = origin.localPort
            out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf((port shr 8).toByte(), port.toByte()))
            val reply = ByteArray(10).also { din.readFully(it) }
            assertEquals(0, reply[1].toInt())
            out.write("socks".toByteArray())
            assertEquals("socks", String(ByteArray(5).also { din.readFully(it) }))
        }
        assertEquals("ejemplo.org:${origin.localPort}", connected.single())
    }

    @Test
    fun socks4a() {
        client().use { c ->
            val port = origin.localPort
            c.getOutputStream().write(byteArrayOf(4, 1, (port shr 8).toByte(), port.toByte(), 0, 0, 0, 1, 0) + "x.org".toByteArray() + byteArrayOf(0))
            val din = DataInputStream(c.getInputStream())
            val reply = ByteArray(8).also { din.readFully(it) }
            assertEquals(0x5A, reply[1].toInt())
        }
        assertEquals("x.org:${origin.localPort}", connected.single())
    }

    @Test
    fun servesPacFile() {
        client().use { c ->
            c.getOutputStream().write("GET /proxy.pac HTTP/1.1\r\nHost: 192.168.43.1\r\n\r\n".toByteArray())
            val r = readAll(c)
            assertTrue(r.startsWith("HTTP/1.1 200 OK"))
            assertTrue(r.contains("PROXY 192.168.43.1:${proxy.localPort}"))
        }
    }

    @Test
    fun noVpnGives502() {
        client().use { c ->
            c.getOutputStream().write("CONNECT falla.invalid:443 HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(readAll(c).startsWith("HTTP/1.1 502"))
        }
    }
}
