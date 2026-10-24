package com.vpnshare.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Sigue qué interfaces está compartiendo Android (punto de acceso, USB, Bluetooth) mediante la
 * difusión persistente `TETHER_STATE_CHANGED`, que el sistema envía al activar o desactivar cada una.
 */
class TetherMonitor(private val context: Context, private val onChange: () -> Unit) {
    companion object {
        private const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        private const val EXTRA_ACTIVE_TETHER = "tetherArray"
    }

    @Volatile var tethered: Set<String> = emptySet()
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val list = intent.getStringArrayListExtra(EXTRA_ACTIVE_TETHER) ?: arrayListOf()
            tethered = list.toSet()
            onChange()
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_TETHER_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    fun isDownstream(iface: String) = iface in tethered || IfaceParser.isDownstreamName(iface)

    /**
     * Direcciones IPv4 locales por las que otros dispositivos pueden llegar a este teléfono
     * (sin root). Incluye el punto de acceso y también la Wi‑Fi a la que esté conectado.
     */
    fun localAddresses(): List<IfaceAddr> {
        val result = ArrayList<IfaceAddr>()
        val ifaces = try { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        for (ni in ifaces) {
            if (!ni.isUp || ni.isLoopback || IfaceParser.isUpstreamName(ni.name)) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address && a.isSiteLocalAddress) {
                    result.add(IfaceAddr(ni.name, a.hostAddress!!, ia.networkPrefixLength.toInt()))
                }
            }
        }
        // Primero las interfaces compartidas (punto de acceso), luego el resto.
        return result.sortedBy { if (isDownstream(it.iface)) 0 else 1 }
    }
}
