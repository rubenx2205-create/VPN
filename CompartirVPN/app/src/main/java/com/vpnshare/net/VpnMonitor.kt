package com.vpnshare.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/**
 * Detecta cuándo hay un VPN activo (el de Psiphon) y obtiene su interfaz (`tun0`) y su DNS.
 * Esta app debe pasar por el VPN (no estar excluida en Psiphon) para poder verlo.
 */
class VpnMonitor(context: Context, private val onChange: () -> Unit) {

    data class VpnInfo(val network: Network, val iface: String?, val dns: String?)

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val vpns = ConcurrentHashMap<Network, VpnInfo>()

    val current: VpnInfo? get() = vpns.values.firstOrNull()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            update(network, cm.getLinkProperties(network))
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            update(network, lp)
        }

        override fun onLost(network: Network) {
            vpns.remove(network)
            onChange()
        }
    }

    private fun update(network: Network, lp: LinkProperties?) {
        val dns = lp?.dnsServers?.firstOrNull { it is Inet4Address }?.hostAddress
        vpns[network] = VpnInfo(network, lp?.interfaceName, dns)
        onChange()
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        vpns.clear()
    }
}
