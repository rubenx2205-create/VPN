package com.vpnshare.proxy

/** Lógica pura de los protocolos del proxy (sin sockets), para poder probarla en la JVM. */
object ProxyProtocol {

    data class HttpRequestHead(val method: String, val target: String, val version: String, val headers: List<String>)

    /** Destino al que hay que conectarse y, si aplica, la cabecera HTTP reescrita a reenviar. */
    data class Route(val host: String, val port: Int, val forwardHead: ByteArray?)

    fun parseHead(raw: String): HttpRequestHead? {
        val lines = raw.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        if (parts.size != 3) return null
        return HttpRequestHead(parts[0].uppercase(), parts[1], parts[2], lines.drop(1))
    }

    /** host:port, con soporte para IPv6 entre corchetes. */
    fun splitHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        if (authority.isEmpty()) return null
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val host = authority.substring(1, end)
            val rest = authority.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else defaultPort
            return port?.takeIf { it in 1..65535 }?.let { host to it }
        }
        val idx = authority.lastIndexOf(':')
        if (idx < 0) return authority to defaultPort
        val port = authority.substring(idx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return authority.substring(0, idx) to port
    }

    /**
     * Para `CONNECT host:port` no hay nada que reenviar (se responde 200 y se hace túnel).
     * Para `GET http://host/ruta` se reescribe a forma de origen (`GET /ruta`) y se fuerza
     * `Connection: close` para que cada conexión vaya a un único servidor.
     */
    fun route(head: HttpRequestHead): Route? {
        if (head.method == "CONNECT") {
            val (h, p) = splitHostPort(head.target, 443) ?: return null
            return Route(h, p, null)
        }
        val lower = head.target.lowercase()
        if (!lower.startsWith("http://")) return null
        val afterScheme = head.target.substring(7)
        val slash = afterScheme.indexOf('/')
        val authority = if (slash < 0) afterScheme else afterScheme.substring(0, slash)
        val path = if (slash < 0) "/" else afterScheme.substring(slash)
        val (h, p) = splitHostPort(authority, 80) ?: return null
        val sb = StringBuilder()
        sb.append(head.method).append(' ').append(path).append(' ').append(head.version).append("\r\n")
        var hasHost = false
        for (header in head.headers) {
            val name = header.substringBefore(':').trim().lowercase()
            if (name == "proxy-connection" || name == "connection" || name == "keep-alive" || name == "proxy-authorization") continue
            if (name == "host") hasHost = true
            sb.append(header).append("\r\n")
        }
        if (!hasHost) sb.append("Host: ").append(authority).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        return Route(h, p, sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    /** Archivo PAC: todo va por el proxy, salvo nombres locales. Sin `DIRECT` como respaldo, para no filtrar. */
    fun pacFile(proxyIp: String, port: Int): String = """
        function FindProxyForURL(url, host) {
          if (isPlainHostName(host) || host === "$proxyIp" || shExpMatch(host, "*.local")) return "DIRECT";
          return "PROXY $proxyIp:$port; SOCKS5 $proxyIp:$port; SOCKS $proxyIp:$port";
        }
    """.trimIndent() + "\n"

    fun httpResponse(status: String, contentType: String, body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + bytes
    }
}
