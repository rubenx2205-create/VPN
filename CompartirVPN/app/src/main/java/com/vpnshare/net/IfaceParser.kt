package com.vpnshare.net

/** Una dirección IPv4 asignada a una interfaz, tal como la muestra `ip -o -4 addr show`. */
data class IfaceAddr(val iface: String, val address: String, val prefix: Int) {
    /** Subred en notación CIDR, p. ej. 192.168.43.0/24. */
    val cidr: String get() = networkOf(address, prefix) + "/" + prefix
}

object IfaceParser {
    // 21: ap0    inet 192.168.43.1/24 brd 192.168.43.255 scope global ap0\       valid_lft forever ...
    private val LINE = Regex("^\\d+:\\s+([^\\s:@]+)(?:@\\S+)?\\s+inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)/(\\d+)")

    /**
     * Nombres que Android usa casi siempre para compartir conexión (punto de acceso, USB, Bluetooth,
     * Wi‑Fi Direct). Otros nombres (wlan1, eth0...) solo se aceptan si Android los anuncia como
     * compartidos, porque en algunos equipos son conexiones de subida.
     */
    private val DOWNSTREAM = Regex("^(ap\\d+|swlan\\d+|softap\\d+|rndis\\d+|ncm\\d+|bt-pan|p2p-.*)$")

    /** Interfaces típicas de un VPN (Psiphon usa tunN). */
    private val UPSTREAM = Regex("^(tun\\d+|ppp\\d+|tap\\d+)$")

    fun parse(output: List<String>): List<IfaceAddr> = output.mapNotNull { line ->
        LINE.find(line.trim())?.let { m ->
            IfaceAddr(m.groupValues[1], m.groupValues[2], m.groupValues[3].toInt())
        }
    }

    fun isDownstreamName(name: String) = DOWNSTREAM.matches(name)

    fun isUpstreamName(name: String) = UPSTREAM.matches(name)

    /** Cuenta los clientes vistos en la salida de `ip neigh show`. */
    fun countNeighbors(output: List<String>): Int = output.count { line ->
        val l = line.trim()
        l.isNotEmpty() && " lladdr " in l && !l.endsWith("FAILED") && !l.endsWith("INCOMPLETE")
    }
}

fun networkOf(address: String, prefix: Int): String {
    val parts = address.split(".").map { it.toInt() }
    val ip = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
    val net = ip and mask
    return listOf(net ushr 24, (net ushr 16) and 255, (net ushr 8) and 255, net and 255).joinToString(".")
}
