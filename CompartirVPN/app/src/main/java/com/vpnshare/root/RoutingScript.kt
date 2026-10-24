package com.vpnshare.root

/** Interfaz de subida: el túnel del VPN (p. ej. `tun0` de Psiphon). */
data class Upstream(val iface: String, val dns: String?)

/** Interfaz de bajada: punto de acceso Wi‑Fi, USB, Bluetooth o Wi‑Fi Direct. */
data class Downstream(val iface: String, val cidr: String)

/**
 * Genera los scripts de shell (iptables + ip rule) que desvían todo el tráfico de los clientes
 * del punto de acceso hacia el túnel del VPN. No ejecuta nada: es lógica pura para poder probarla.
 *
 * Esquema:
 *  - `ip rule iif <bajada> lookup 7777`  → tabla con `default dev tun0` (y `unreachable` si hay kill switch).
 *  - `ip rule iif <tun> lookup 7778`     → tabla con las subredes de los clientes, para las respuestas.
 *  - NAT (MASQUERADE) al salir por el túnel, DNS de los clientes redirigido al DNS del VPN,
 *    IPv6 bloqueado (el túnel es solo IPv4) y MSS ajustado.
 */
object RoutingScript {
    const val TABLE_DOWN = 7777
    const val TABLE_UP = 7778
    const val PREF_DOWN = 17800
    const val PREF_UP = 17801

    private const val FWD = "vpnshare_fwd"
    private const val FWD6 = "vpnshare_fwd6"
    private const val INPUT = "vpnshare_in"
    private const val NAT_PRE = "vpnshare_pre"
    private const val NAT_POST = "vpnshare_post"
    private const val MSS = "vpnshare_mss"

    private val SAFE_IFACE = Regex("^[A-Za-z0-9_.\\-]{1,15}$")
    private val SAFE_IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val SAFE_CIDR = Regex("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$")

    private fun iface(name: String): String {
        require(SAFE_IFACE.matches(name)) { "Nombre de interfaz no válido: $name" }
        return name
    }

    /** Elimina todo lo que haya puesto la app. Idempotente: se puede ejecutar aunque no haya nada. */
    fun teardown(): String = buildString {
        appendLine("while ip rule del pref $PREF_DOWN 2>/dev/null; do :; done")
        appendLine("while ip rule del pref $PREF_UP 2>/dev/null; do :; done")
        appendLine("ip route flush table $TABLE_DOWN 2>/dev/null")
        appendLine("ip route flush table $TABLE_UP 2>/dev/null")
        removeChain("iptables", "filter", "FORWARD", FWD)
        removeChain("iptables", "filter", "INPUT", INPUT)
        removeChain("iptables", "nat", "PREROUTING", NAT_PRE)
        removeChain("iptables", "nat", "POSTROUTING", NAT_POST)
        removeChain("iptables", "mangle", "FORWARD", MSS)
        removeChain("ip6tables", "filter", "FORWARD", FWD6)
        appendLine("true")
    }

    /**
     * Configuración completa. Si [upstream] es null (VPN caído) y [killSwitch] está activo, los
     * clientes se quedan sin internet en lugar de salir por la conexión normal sin VPN.
     * Si [upstream] es null y no hay kill switch, solo se limpia (compartición normal de Android).
     */
    fun apply(upstream: Upstream?, downstreams: List<Downstream>, killSwitch: Boolean): String {
        if (downstreams.isEmpty() || (upstream == null && !killSwitch)) return teardown()
        val up = upstream?.let { iface(it.iface) }
        val dns = upstream?.dns?.takeIf { SAFE_IPV4.matches(it) }
        return buildString {
            append(teardown())
            createChain("iptables", "filter", "FORWARD", FWD)
            createChain("iptables", "filter", "INPUT", INPUT)
            createChain("iptables", "nat", "PREROUTING", NAT_PRE)
            createChain("iptables", "nat", "POSTROUTING", NAT_POST)
            createChain("iptables", "mangle", "FORWARD", MSS)
            createChain("ip6tables", "filter", "FORWARD", FWD6)
            appendLine("echo 1 > /proc/sys/net/ipv4/ip_forward")

            if (up != null) {
                appendLine("ip route add default dev $up table $TABLE_DOWN")
                appendLine("ip rule add iif $up lookup $TABLE_UP pref $PREF_UP")
                appendLine("echo 0 > /proc/sys/net/ipv4/conf/$up/rp_filter 2>/dev/null")
                appendLine("iptables -w -t nat -A $NAT_POST -o $up -j MASQUERADE")
                appendLine("iptables -w -t mangle -A $MSS -o $up -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu")
            }
            if (killSwitch) {
                appendLine("ip route add unreachable default metric 9999 table $TABLE_DOWN")
            }

            for (d in downstreams) {
                val down = iface(d.iface)
                require(SAFE_CIDR.matches(d.cidr)) { "Subred no válida: ${d.cidr}" }
                appendLine("ip rule add iif $down lookup $TABLE_DOWN pref $PREF_DOWN")
                appendLine("ip route add ${d.cidr} dev $down table $TABLE_UP")
                if (up != null) {
                    appendLine("iptables -w -A $FWD -i $down -o $up -j ACCEPT")
                    appendLine("iptables -w -A $FWD -i $up -o $down -m state --state RELATED,ESTABLISHED -j ACCEPT")
                    if (dns != null) {
                        appendLine("iptables -w -t nat -A $NAT_PRE -i $down -p udp --dport 53 -j DNAT --to-destination $dns")
                        appendLine("iptables -w -t nat -A $NAT_PRE -i $down -p tcp --dport 53 -j DNAT --to-destination $dns")
                    }
                }
                if (killSwitch) {
                    // Cualquier otra salida (la red móvil o Wi‑Fi sin VPN) queda bloqueada.
                    appendLine("iptables -w -A $FWD -i $down -j REJECT")
                    // Y también el DNS del propio teléfono, que resolvería por fuera del VPN.
                    // Con el VPN activo no se llega aquí: el DNS ya se redirigió al del túnel.
                    appendLine("iptables -w -A $INPUT -i $down -p udp --dport 53 -j REJECT")
                    appendLine("iptables -w -A $INPUT -i $down -p tcp --dport 53 -j REJECT")
                }
                // El túnel es solo IPv4: IPv6 por fuera del VPN filtraría la IP real.
                appendLine("ip6tables -w -A $FWD6 -i $down -j REJECT")
            }
            appendLine("true")
        }
    }

    private fun StringBuilder.createChain(bin: String, table: String, parent: String, chain: String) {
        appendLine("$bin -w -t $table -N $chain 2>/dev/null; $bin -w -t $table -F $chain")
        appendLine("$bin -w -t $table -C $parent -j $chain 2>/dev/null || $bin -w -t $table -I $parent 1 -j $chain")
    }

    private fun StringBuilder.removeChain(bin: String, table: String, parent: String, chain: String) {
        appendLine("while $bin -w -t $table -D $parent -j $chain 2>/dev/null; do :; done")
        appendLine("$bin -w -t $table -F $chain 2>/dev/null; $bin -w -t $table -X $chain 2>/dev/null")
    }
}
