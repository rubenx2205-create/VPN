package com.vpnshare.proxy

import android.net.Network
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Proxy HTTP/HTTPS + SOCKS4/5 en un solo puerto, para el modo sin root. Cada conexión de salida se
 * crea explícitamente sobre la red del VPN ([connectViaVpn]), así que sale por el túnel de Psiphon;
 * si el VPN no está activo se rechaza (nunca se usa la conexión normal).
 *
 * También sirve el archivo PAC en `/proxy.pac` y `/wpad.dat` para la configuración automática.
 */
class ProxyServer(
    private val port: Int,
    /** Abre la conexión de salida (en la app, siempre sobre la red del VPN; ver [connectViaVpn]). */
    private val connect: (host: String, port: Int) -> Socket,
    private val pacHostFor: (Socket) -> String,
) {
    companion object {
        private const val TAG = "ProxyServer"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val MAX_HEAD = 16 * 1024

        /** Conecta por [network] (el VPN), resolviendo también el DNS por él. Sin VPN, falla. */
        fun connectViaVpn(network: Network?, host: String, port: Int): Socket {
            val net = network ?: throw IllegalStateException("El VPN no está activo")
            val address = net.getAllByName(host).let { all -> all.firstOrNull { it is Inet4Address } ?: all.first() }
            val s = net.socketFactory.createSocket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            return s
        }
    }

    val activeConnections = AtomicInteger(0)
    val bytesTransferred = AtomicLong(0)

    @Volatile private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val isRunning get() = server?.isClosed == false

    /** Puerto real en escucha (útil si se pidió el 0). */
    val localPort get() = server?.localPort ?: -1

    @Synchronized
    fun start() {
        if (isRunning) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 128)
        server = ss
        val p = Executors.newCachedThreadPool()
        pool = p
        p.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { break }
                if (!isAllowedClient(client.inetAddress)) {
                    client.close()
                    continue
                }
                try {
                    p.execute { handle(client) }
                } catch (_: Exception) {
                    client.close()
                }
            }
        }
        Log.i(TAG, "Proxy escuchando en el puerto $port")
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool?.shutdownNow()
        pool = null
    }

    /** Solo clientes de redes locales: así el proxy nunca queda abierto a internet por la red móvil. */
    private fun isAllowedClient(a: InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress

    private fun handle(client: Socket) {
        activeConnections.incrementAndGet()
        try {
            client.soTimeout = 60_000
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val first = input.read()
            when (first) {
                -1 -> return
                5 -> socks5(client, input, output)
                4 -> socks4(client, input, output)
                else -> http(client, first, input, output)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Conexión terminada: $e")
        } finally {
            activeConnections.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
        }
    }


    // ---------- HTTP ----------

    private fun http(client: Socket, firstByte: Int, input: InputStream, output: OutputStream) {
        val buf = ByteArrayOutputStream()
        buf.write(firstByte)
        var last4 = firstByte
        while (last4 != 0x0D0A0D0A) {
            val b = input.read()
            if (b == -1 || buf.size() > MAX_HEAD) return
            buf.write(b)
            last4 = (last4 shl 8) or b
        }
        val head = ProxyProtocol.parseHead(buf.toString("ISO-8859-1")) ?: return

        if (head.target.startsWith("/")) {
            servePage(client, head.target, output)
            return
        }
        val route = ProxyProtocol.route(head) ?: run {
            output.write(ProxyProtocol.httpResponse("400 Bad Request", "text/plain", "Solicitud no válida\n"))
            return
        }
        val remote = try {
            connect(route.host, route.port)
        } catch (e: Exception) {
            output.write(ProxyProtocol.httpResponse("502 Bad Gateway", "text/plain", "No se pudo conectar por el VPN: ${e.message}\n"))
            return
        }
        remote.use {
            if (route.forwardHead == null) {
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            } else {
                it.getOutputStream().write(route.forwardHead)
            }
            pipe(client, it)
        }
    }

    private fun servePage(client: Socket, path: String, output: OutputStream) {
        val p = path.substringBefore('?')
        if (p == "/proxy.pac" || p == "/wpad.dat") {
            output.write(ProxyProtocol.httpResponse("200 OK", "application/x-ns-proxy-autoconfig", ProxyProtocol.pacFile(pacHostFor(client), localPort)))
        } else {
            val ip = pacHostFor(client)
            val port = localPort
            val body = """
                <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"></head>
                <body style="font-family:sans-serif">
                <h2>Compartir VPN</h2>
                <p>Proxy activo. En los ajustes Wi‑Fi de este dispositivo elige <b>Proxy → Automático</b> y escribe:</p>
                <p style="font-size:1.3em"><code>http://$ip:$port/proxy.pac</code></p>
                <p>O proxy manual: servidor <code>$ip</code>, puerto <code>$port</code>.</p>
                </body></html>
            """.trimIndent()
            output.write(ProxyProtocol.httpResponse("200 OK", "text/html; charset=utf-8", body))
        }
        output.flush()
    }

    // ---------- SOCKS ----------

    private fun socks5(client: Socket, input: InputStream, output: OutputStream) {
        val din = DataInputStream(input)
        val nMethods = din.readUnsignedByte()
        val methods = ByteArray(nMethods).also { din.readFully(it) }
        if (methods.none { it.toInt() == 0 }) {
            output.write(byteArrayOf(5, 0xFF.toByte()))
            return
        }
        output.write(byteArrayOf(5, 0))
        if (din.readUnsignedByte() != 5) return
        val cmd = din.readUnsignedByte()
        din.readUnsignedByte()
        val host = when (din.readUnsignedByte()) {
            1 -> ByteArray(4).also { din.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
            3 -> ByteArray(din.readUnsignedByte()).also { din.readFully(it) }.toString(Charsets.US_ASCII)
            4 -> ByteArray(16).also { din.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
            else -> { socks5Reply(output, 8); return }
        }
        val port = din.readUnsignedShort()
        if (cmd != 1) { // Solo CONNECT (TCP).
            socks5Reply(output, 7)
            return
        }
        val remote = try {
            connect(host, port)
        } catch (e: Exception) {
            socks5Reply(output, 5)
            return
        }
        remote.use {
            socks5Reply(output, 0)
            pipe(client, it)
        }
    }

    private fun socks5Reply(output: OutputStream, code: Int) {
        output.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun socks4(client: Socket, input: InputStream, output: OutputStream) {
        val din = DataInputStream(input)
        val cmd = din.readUnsignedByte()
        val port = din.readUnsignedShort()
        val ip = ByteArray(4).also { din.readFully(it) }
        readCString(din) // user id
        val host = if (ip[0].toInt() == 0 && ip[1].toInt() == 0 && ip[2].toInt() == 0 && ip[3].toInt() != 0) {
            readCString(din) // SOCKS4a: nombre de dominio
        } else {
            InetAddress.getByAddress(ip).hostAddress!!
        }
        if (cmd != 1) {
            output.write(byteArrayOf(0, 0x5B, 0, 0, 0, 0, 0, 0))
            return
        }
        val remote = try {
            connect(host, port)
        } catch (e: Exception) {
            output.write(byteArrayOf(0, 0x5B, 0, 0, 0, 0, 0, 0))
            return
        }
        remote.use {
            output.write(byteArrayOf(0, 0x5A, 0, 0, 0, 0, 0, 0))
            output.flush()
            pipe(client, it)
        }
    }

    private fun readCString(din: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = din.readUnsignedByte()
            if (b == 0) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > 255) throw IllegalArgumentException("Cadena SOCKS demasiado larga")
        }
    }

    // ---------- Copia bidireccional ----------

    private fun pipe(a: Socket, b: Socket) {
        a.soTimeout = 0
        b.soTimeout = 0
        val t = Thread { copy(b, a) }
        t.start()
        copy(a, b)
        t.join()
    }

    /** Copia de [from] a [to]. Al llegar al fin se cierra solo la escritura; ante un error se cierran ambos. */
    private fun copy(from: Socket, to: Socket) {
        val buffer = ByteArray(32 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                bytesTransferred.addAndGet(n.toLong())
            }
            try { to.shutdownOutput() } catch (_: Exception) {}
        } catch (_: Exception) {
            try { from.close() } catch (_: Exception) {}
            try { to.close() } catch (_: Exception) {}
        }
    }
}
