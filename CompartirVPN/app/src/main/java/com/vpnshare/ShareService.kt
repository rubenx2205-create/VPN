package com.vpnshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vpnshare.net.TetherMonitor
import com.vpnshare.net.VpnMonitor
import com.vpnshare.proxy.ProxyServer
import com.vpnshare.root.RootRouter
import com.vpnshare.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Servicio en primer plano que vigila el VPN y el punto de acceso. En cuanto Psiphon conecta,
 * empieza a compartir; si se desconecta, deja de hacerlo (y bloquea a los clientes si el kill
 * switch está activo). Todo se recalcula en un único bucle de "reconciliación".
 */
class ShareService : Service() {

    data class Status(
        val running: Boolean = false,
        val mode: ShareMode? = null,
        val vpnActive: Boolean = false,
        val vpnIface: String? = null,
        val downstreams: List<String> = emptyList(),
        val clients: Int = 0,
        val proxyAddresses: List<String> = emptyList(),
        val port: Int = 0,
        val killSwitch: Boolean = true,
        val error: String? = null,
    )

    companion object {
        private const val TAG = "ShareService"
        private const val CHANNEL_ID = "share"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.vpnshare.STOP"
        private const val LOOP_INTERVAL_MS = 5_000L

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status

        fun start(context: Context) {
            Prefs(context).enabled = true
            ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java))
        }

        fun stop(context: Context) {
            Prefs(context).enabled = false
            context.startService(Intent(context, ShareService::class.java).setAction(ACTION_STOP))
        }
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private lateinit var prefs: Prefs
    private lateinit var vpnMonitor: VpnMonitor
    private lateinit var tetherMonitor: TetherMonitor
    @Volatile private var rootRouter: RootRouter? = null
    @Volatile private var proxy: ProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        vpnMonitor = VpnMonitor(this) { wakeups.trySend(Unit) }
        tetherMonitor = TetherMonitor(this) { wakeups.trySend(Unit) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        goForeground(getString(R.string.notif_starting))
        if (!started) {
            started = true
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            vpnMonitor.start()
            tetherMonitor.start()
            acquireWakeLock()
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val mode = when (prefs.mode) {
            ShareMode.ROOT -> ShareMode.ROOT
            ShareMode.PROXY -> ShareMode.PROXY
            ShareMode.AUTO -> if (RootShell.isAvailable()) ShareMode.ROOT else ShareMode.PROXY
        }
        Log.i(TAG, "Modo elegido: $mode")
        if (mode == ShareMode.ROOT) {
            if (!RootShell.isAvailable()) {
                publish(Status(running = true, mode = mode, error = getString(R.string.err_no_root)))
                return
            }
            rootRouter = RootRouter().also { it.disableTetherOffload() }
        } else {
            proxy = ProxyServer(prefs.port, { host, port -> ProxyServer.connectViaVpn(vpnMonitor.current?.network, host, port) }) { s ->
                s.localAddress.hostAddress ?: "192.168.43.1"
            }
        }

        while (true) {
            try {
                publish(if (mode == ShareMode.ROOT) reconcileRoot() else reconcileProxy())
            } catch (e: Exception) {
                Log.w(TAG, "Error en el bucle", e)
                publish(_status.value.copy(running = true, mode = mode, error = e.message ?: e.toString()))
            }
            // Espera al siguiente evento (VPN/punto de acceso) o al intervalo, lo que ocurra antes.
            withTimeoutOrNull(LOOP_INTERVAL_MS) { wakeups.receive() }
        }
    }

    private fun reconcileRoot(): Status {
        val vpn = vpnMonitor.current
        val killSwitch = prefs.killSwitch
        val snap = rootRouter!!.reconcile(vpn?.iface, vpn?.dns, tetherMonitor::isDownstream, killSwitch)
        return Status(
            running = true,
            mode = ShareMode.ROOT,
            vpnActive = snap.upstream != null,
            vpnIface = snap.upstream?.iface,
            downstreams = snap.downstreams.map { "${it.iface} (${it.cidr})" },
            clients = snap.clients,
            killSwitch = killSwitch,
            error = snap.error,
        )
    }

    private fun reconcileProxy(): Status {
        val p = proxy!!
        var error: String? = null
        if (!p.isRunning) {
            try {
                p.start()
            } catch (e: Exception) {
                error = getString(R.string.err_port, prefs.port, e.message ?: "")
            }
        }
        val vpn = vpnMonitor.current
        val addrs = tetherMonitor.localAddresses()
        return Status(
            running = true,
            mode = ShareMode.PROXY,
            vpnActive = vpn != null,
            vpnIface = vpn?.iface,
            downstreams = addrs.filter { tetherMonitor.isDownstream(it.iface) }.map { "${it.iface} (${it.cidr})" },
            clients = p.activeConnections.get(),
            proxyAddresses = addrs.map { it.address }.distinct(),
            port = prefs.port,
            killSwitch = true,
            error = error,
        )
    }

    private fun publish(s: Status) {
        _status.value = s
        goForeground(notificationText(s))
    }

    private fun notificationText(s: Status): String = when {
        s.error != null -> getString(R.string.notif_error, s.error)
        !s.vpnActive -> getString(if (s.mode == ShareMode.ROOT && s.killSwitch) R.string.notif_waiting_blocked else R.string.notif_waiting)
        s.mode == ShareMode.ROOT -> if (s.downstreams.isEmpty()) getString(R.string.notif_no_hotspot)
            else getString(R.string.notif_root_sharing, s.clients)
        else -> getString(R.string.notif_proxy_sharing, s.proxyAddresses.firstOrNull() ?: "?", s.port, s.clients)
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun acquireWakeLock() {
        // Sin esto, con la pantalla apagada el procesador se duerme y el proxy deja de responder.
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CompartirVPN:share").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun shutdown() {
        Prefs(this).enabled = false
        val router = rootRouter
        runBlocking(Dispatchers.IO) {
            // Esperar a que termine el bucle, para que no vuelva a poner reglas tras limpiarlas.
            loopJob?.cancelAndJoin()
            proxy?.stop()
            // Quitar las reglas antes de salir, para devolver la compartición normal de Android.
            router?.teardown()
            if (router != null) RootShell.close()
        }
        scope.cancel()
        loopJob = null
        vpnMonitor.stop()
        tetherMonitor.stop()
        proxy = null
        rootRouter = null
        wakeLock?.release()
        wakeLock = null
        started = false
        _status.value = Status()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (started) shutdown()
        super.onDestroy()
    }
}
