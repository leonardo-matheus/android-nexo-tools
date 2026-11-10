package br.com.nexotools.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import br.com.nexotools.MainActivity
import br.com.nexotools.R
import br.com.nexotools.domain.CapturedPacket
import br.com.nexotools.domain.PacketDecoder
import br.com.nexotools.domain.PacketProtocol
import br.com.nexotools.domain.PcapCodec
import br.com.nexotools.infra.LocalSocksProxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class PacketCaptureService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private var sessionJob: Job? = null
    @Volatile private var nativeToken = 0L
    @Volatile private var failure: String? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { requestStop(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START || sessionJob?.isCompleted == false) return START_NOT_STICKY
        stopRequested.set(false); failure = null
        val app = intent.getStringExtra(EXTRA_APP)?.takeIf { it.isNotBlank() }
        PacketCaptureStore.begin(app)
        try {
            startForeground(NOTIFICATION_ID, notification())
            nativeToken = NativeTunnel.prepare()
            check(nativeToken > 0)
        } catch (_: Exception) {
            PacketCaptureStore.finish("Não foi possível iniciar a captura."); stopSelf(); return START_NOT_STICKY
        } catch (_: LinkageError) {
            PacketCaptureStore.finish("O motor de captura não está disponível neste aparelho."); stopSelf(); return START_NOT_STICKY
        }
        sessionJob = scope.launch { capture(app, nativeToken) }
        return START_NOT_STICKY
    }

    private fun notification(): android.app.Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Monitor de tráfego", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 4201,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("nexo_open_packets", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 4202, Intent(this, PacketCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_radar)
            .setContentTitle("NEXO · captura de tráfego").setContentText("VPN local ativa")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Parar", stop).build()
    }

    private suspend fun capture(app: String?, token: Long) {
        var tun: ParcelFileDescriptor? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        var proxy: LocalSocksProxy? = null
        var reader: Job? = null
        var publisher: Job? = null
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            currentCoroutineContext().ensureActive()
            val underlying = requireNotNull(connectivity.activeNetwork)
            val capabilities = requireNotNull(connectivity.getNetworkCapabilities(underlying))
            check(!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            val links = requireNotNull(connectivity.getLinkProperties(underlying))
            val credentials = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
            proxy = LocalSocksProxy(
                protectTcp = { socket -> protect(socket).also { if (it) underlying.bindSocket(socket) } },
                protectUdp = { socket -> protect(socket).also { if (it) underlying.bindSocket(socket) } },
                resolve = { host -> underlying.getAllByName(host) },
            )
            val port = proxy.start("nexo", credentials)
            val builder = Builder().setSession("NEXO · Monitor de tráfego").setMtu(1500)
                .addAddress(IPV4, 32).addRoute("0.0.0.0", 0).setBlocking(false)
                .addAddress(IPV6, 128).addRoute("::", 0)
                .setUnderlyingNetworks(arrayOf(underlying))
            links.dnsServers.forEach { builder.addDnsServer(it) }
            if (app != null) builder.addAllowedApplication(app)
            currentCoroutineContext().ensureActive()
            check(!stopRequested.get())
            tun = requireNotNull(builder.establish())
            pipe = ParcelFileDescriptor.createPipe()
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    if (network == underlying) { failure = "A conexão de rede mudou. Inicie uma nova captura."; requestStop() }
                }
            }
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
            val input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
            reader = scope.launch {
                try {
                    var number = 0L
                    val apps = mutableMapOf<Int, String?>()
                    val owners = LinkedHashMap<String, Pair<Long, Pair<Int, String?>?>>()
                    input.use { PcapCodec.read(it) { time, length, data ->
                        val parsed = PacketDecoder.decode(++number, time, length, data)
                        PacketCaptureStore.received(parsed?.let { identify(it, app, apps, owners) })
                    } }
                } catch (_: Exception) {
                    if (!stopRequested.get()) { failure = "A leitura dos pacotes foi interrompida."; requestStop() }
                }
            }
            publisher = scope.launch {
                while (isActive) { PacketCaptureStore.publish(NativeTunnel.dropped()); delay(300) }
            }
            currentCoroutineContext().ensureActive()
            check(!stopRequested.get())
            PacketCaptureStore.started()
            val result = NativeTunnel.run(configuration(port, credentials), tun.fd, pipe[1].fd, token)
            if (!stopRequested.get() && result != 0) failure = "O motor de captura não conseguiu manter a VPN."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!stopRequested.get()) failure = "Não foi possível iniciar a VPN. Confira a conexão e feche outras VPNs."
        } catch (_: LinkageError) {
            failure = "O motor nativo não está disponível neste aparelho."
        } finally {
            withContext(NonCancellable) {
                stopRequested.set(true)
                runCatching { NativeTunnel.stop(token) }
                publisher?.cancelAndJoin()
                runCatching { proxy?.close() }
                runCatching { tun?.close() }
                runCatching { pipe?.get(1)?.close() }
                runCatching { pipe?.get(0)?.close() }
                reader?.cancelAndJoin()
                callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
                runCatching { PacketCaptureStore.publish(NativeTunnel.dropped()) }
                PacketCaptureStore.finish(failure)
                nativeToken = 0
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun identify(packet: CapturedPacket, selected: String?, apps: MutableMap<Int, String?>,
        owners: LinkedHashMap<String, Pair<Long, Pair<Int, String?>?>>): CapturedPacket {
        if (selected != null) return packet.copy(app = selected)
        if (Build.VERSION.SDK_INT < 29 || packet.protocol !in setOf(PacketProtocol.TCP, PacketProtocol.UDP) || packet.sourcePort == null || packet.destinationPort == null) return packet
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = owners[packet.connectionKey]
        val owner = if (cached != null && now - cached.first < 3000) cached.second else runCatching {
            val outbound = packet.source == IPV4 || InetAddress.getByName(packet.source) == InetAddress.getByName(IPV6)
            val local = InetSocketAddress(if (outbound) packet.source else packet.destination, if (outbound) packet.sourcePort else packet.destinationPort)
            val remote = InetSocketAddress(if (outbound) packet.destination else packet.source, if (outbound) packet.destinationPort else packet.sourcePort)
            val uid = connectivity.getConnectionOwnerUid(if (packet.protocol == PacketProtocol.TCP) OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP, local, remote)
            if (uid < 0) null else uid to apps.getOrPut(uid) { packageManager.getPackagesForUid(uid)?.joinToString(", ") }
        }.getOrNull().also {
            if (owners.size >= 4096) owners.remove(owners.keys.first())
            owners[packet.connectionKey] = now to it
        }
        return if (owner == null) packet else packet.copy(uid = owner.first, app = owner.second)
    }

    private fun configuration(port: Int, password: String) = """
        tunnel:
          name: nexo0
          mtu: 1500
          ipv4: $IPV4
          ipv6: '$IPV6'
          icmp: 'off'
        socks5:
          port: $port
          address: 127.0.0.1
          udp: 'udp'
          username: 'nexo'
          password: '$password'
        misc:
          max-session-count: 64
          task-stack-size: 86016
          tcp-buffer-size: 65536
          connect-timeout: 10000
          tcp-read-write-timeout: 300000
          udp-read-write-timeout: 60000
          log-file: '/dev/null'
          log-level: error
    """.trimIndent()

    private fun requestStop() {
        stopRequested.set(true)
        PacketCaptureStore.stopping()
        val token = nativeToken
        if (token > 0) runCatching { NativeTunnel.stop(token) }
        sessionJob?.cancel()
        if (sessionJob == null) stopSelf()
    }

    override fun onRevoke() { requestStop(); super.onRevoke() }
    override fun onDestroy() { requestStop(); scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_START = "br.com.nexotools.capture.START"
        const val ACTION_STOP = "br.com.nexotools.capture.STOP"
        const val EXTRA_APP = "capture_app"
        private const val CHANNEL = "nexo_capture"
        private const val NOTIFICATION_ID = 4200
        private const val IPV4 = "198.18.0.1"
        private const val IPV6 = "fc00::1"
    }
}
