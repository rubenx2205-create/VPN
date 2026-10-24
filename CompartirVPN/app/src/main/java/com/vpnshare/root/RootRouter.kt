package com.vpnshare.root

import android.util.Log
import com.vpnshare.net.IfaceParser

/** Aplica con root las reglas de [RoutingScript] y solo las rehace cuando algo cambia. */
class RootRouter {
    companion object {
        private const val TAG = "RootRouter"
        private const val FALLBACK_DNS = "1.1.1.1"
    }

    private data class Applied(val up: Upstream?, val downs: List<Downstream>, val killSwitch: Boolean)

    private var applied: Applied? = null
    private var savedOffload: String? = null
    private var lastError: String? = null

    data class Snapshot(val upstream: Upstream?, val downstreams: List<Downstream>, val clients: Int, val error: String?)

    /**
     * Calcula el estado deseado y lo aplica si es distinto del actual.
     * [vpnIface]/[vpnDns] vienen de Android; si no se ven (app excluida del VPN) se busca un `tunN`.
     */
    fun reconcile(vpnIface: String?, vpnDns: String?, isDownstream: (String) -> Boolean, killSwitch: Boolean): Snapshot {
        val addrs = IfaceParser.parse(RootShell.run("ip -o -4 addr show").output)
        val upIface = vpnIface ?: addrs.firstOrNull { IfaceParser.isUpstreamName(it.iface) }?.iface
        val up = upIface?.let { Upstream(it, vpnDns ?: FALLBACK_DNS) }
        val downs = addrs
            .filter { it.iface != upIface && isDownstream(it.iface) }
            .distinctBy { it.iface }
            .map { Downstream(it.iface, it.cidr) }

        val desired = Applied(up, downs, killSwitch)
        if (desired != applied) {
            // Los comandos que pueden fallar sin importancia ya silencian su salida, así que
            // cualquier texto que aparezca es un error real que conviene mostrar.
            val r = RootShell.run(RoutingScript.apply(up, downs, killSwitch))
            applied = desired
            lastError = null
            val problems = r.output.filter { it.isNotBlank() }
            if (problems.isNotEmpty()) {
                lastError = problems.last()
                Log.w(TAG, "Avisos al aplicar reglas: ${problems.joinToString("\n")}")
            }
            Log.i(TAG, "Reglas aplicadas: subida=$up bajada=$downs killSwitch=$killSwitch")
        }

        var clients = 0
        for (d in downs) {
            clients += IfaceParser.countNeighbors(RootShell.run("ip neigh show dev ${d.iface}").output)
        }
        return Snapshot(up, downs, clients, lastError)
    }

    /**
     * La aceleración por hardware del anclaje puede saltarse iptables y enviar el tráfico fuera del
     * VPN. Se desactiva mientras se comparte y se restaura al terminar.
     */
    fun disableTetherOffload() {
        if (savedOffload != null) return
        val current = RootShell.run("settings get global tether_offload_disabled").output.lastOrNull()?.trim()
        savedOffload = current ?: "null"
        RootShell.run("settings put global tether_offload_disabled 1")
    }

    fun teardown() {
        try {
            RootShell.run(RoutingScript.teardown())
            savedOffload?.let {
                if (it == "null" || it.isEmpty()) {
                    RootShell.run("settings delete global tether_offload_disabled")
                } else if (it.matches(Regex("^\\d+$"))) {
                    RootShell.run("settings put global tether_offload_disabled $it")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error al limpiar: $e")
        } finally {
            savedOffload = null
            applied = null
            lastError = null
        }
    }
}
