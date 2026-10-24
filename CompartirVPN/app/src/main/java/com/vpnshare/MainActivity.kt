package com.vpnshare

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vpnshare.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var updatingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.modeGroup.check(
            when (prefs.mode) {
                ShareMode.AUTO -> R.id.modeAuto
                ShareMode.ROOT -> R.id.modeRoot
                ShareMode.PROXY -> R.id.modeProxy
            },
        )
        b.killSwitch.isChecked = prefs.killSwitch
        b.bootSwitch.isChecked = prefs.startOnBoot
        b.portInput.setText(prefs.port.toString())

        b.shareSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) {
                requestNotificationPermission()
                ShareService.start(this)
            } else {
                ShareService.stop(this)
            }
        }
        b.modeGroup.setOnCheckedChangeListener { _, id ->
            prefs.mode = when (id) {
                R.id.modeRoot -> ShareMode.ROOT
                R.id.modeProxy -> ShareMode.PROXY
                else -> ShareMode.AUTO
            }
            restartIfRunning()
        }
        b.killSwitch.setOnCheckedChangeListener { _, checked -> prefs.killSwitch = checked }
        b.bootSwitch.setOnCheckedChangeListener { _, checked -> prefs.startOnBoot = checked }
        b.portInput.doAfterTextChanged { text ->
            val port = text?.toString()?.toIntOrNull()
            if (port != null && port in 1024..65535 && port != prefs.port) {
                prefs.port = port
            }
        }
        b.hotspotButton.setOnClickListener { openHotspotSettings() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ShareService.status.collect { render(it) }
            }
        }
    }

    private fun restartIfRunning() {
        if (ShareService.status.value.running) {
            ShareService.stop(this)
            ShareService.start(this)
        }
    }

    private fun render(s: ShareService.Status) {
        updatingUi = true
        b.shareSwitch.isChecked = s.running || prefs.enabled
        updatingUi = false

        if (!s.running) {
            b.statusText.text = getString(R.string.status_stopped)
            return
        }
        val lines = ArrayList<String>()
        s.mode?.let {
            lines += getString(R.string.status_mode, getString(if (it == ShareMode.ROOT) R.string.mode_name_root else R.string.mode_name_proxy))
        }
        lines += when {
            s.vpnActive -> getString(R.string.status_vpn_on, s.vpnIface ?: "?")
            s.mode == ShareMode.ROOT && s.killSwitch -> getString(R.string.status_vpn_off_blocked)
            else -> getString(R.string.status_vpn_off)
        }
        lines += if (s.downstreams.isEmpty()) getString(R.string.status_no_hotspot)
        else getString(R.string.status_downstreams, s.downstreams.joinToString(", "))
        if (s.mode == ShareMode.ROOT) {
            lines += getString(R.string.status_clients, s.clients)
        } else if (s.mode == ShareMode.PROXY) {
            lines += getString(R.string.status_connections, s.clients)
            val ip = s.proxyAddresses.firstOrNull()
            if (ip != null) {
                lines += ""
                lines += getString(R.string.status_proxy, "http://$ip:${s.port}/proxy.pac", ip, s.port)
            }
        }
        s.error?.let { lines += ""; lines += getString(R.string.status_error, it) }
        b.statusText.text = lines.joinToString("\n")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun openHotspotSettings() {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }
}
