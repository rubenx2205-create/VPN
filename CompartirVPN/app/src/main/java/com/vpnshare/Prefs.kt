package com.vpnshare

import android.content.Context

enum class ShareMode { AUTO, ROOT, PROXY }

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: ShareMode
        get() = runCatching { ShareMode.valueOf(sp.getString("mode", null) ?: "") }.getOrDefault(ShareMode.AUTO)
        set(v) = sp.edit().putString("mode", v.name).apply()

    /** Si el VPN cae, los clientes se quedan sin internet en vez de usar la conexión sin VPN. */
    var killSwitch: Boolean
        get() = sp.getBoolean("kill_switch", true)
        set(v) = sp.edit().putBoolean("kill_switch", v).apply()

    var port: Int
        get() = sp.getInt("port", 8080)
        set(v) = sp.edit().putInt("port", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", false)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    /** Si el usuario dejó la compartición encendida (para reanudarla tras reiniciar). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()
}
