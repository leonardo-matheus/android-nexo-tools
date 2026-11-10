package br.com.nexotools.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import br.com.nexotools.domain.WifiReading
import br.com.nexotools.domain.WifiSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

@Singleton
class AndroidWifiSource @Inject constructor(@ApplicationContext private val context: Context) : WifiSource {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)

    @Suppress("DEPRECATION")
    override fun readings(): Flow<WifiReading> = callbackFlow {
        if (connectivity == null || wifi == null) {
            trySend(WifiReading(status = "Wi-Fi indisponível neste aparelho")); close(); return@callbackFlow
        }
        data class Snapshot(val network: Network?, val capabilities: NetworkCapabilities? = null, val properties: LinkProperties? = null)
        val snapshot = AtomicReference(Snapshot(null))
        fun publish() {
            try {
                val state = snapshot.get()
                val net = state.network
                val freshCapabilities = net?.let { connectivity.getNetworkCapabilities(it) }
                // Only the flagged callback includes location-sensitive WifiInfo on Android 12+.
                // getNetworkCapabilities() redacts that information even if permission is granted.
                val caps = state.capabilities ?: freshCapabilities
                val info = if (Build.VERSION.SDK_INT >= 31) caps?.transportInfo as? WifiInfo else wifi.connectionInfo
                val primary = wifi.connectionInfo
                // A phone can have multiple simultaneous Wi-Fi connections. Never merge the
                // primary connection's RSSI/link speed with a different callback's network.
                val current = if (Build.VERSION.SDK_INT >= 31) (freshCapabilities?.transportInfo as? WifiInfo)
                    ?: primary?.takeIf { it.bssid != "02:00:00:00:00:00" && it.bssid != null && it.bssid == info?.bssid } else info
                val connected = net != null && caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                if (!connected) { trySend(WifiReading(status = "Sem conexão Wi-Fi")); return }
                val links = state.properties ?: net?.let { connectivity.getLinkProperties(it) }
                val ssid = info?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
                val bssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
                val ip = links?.linkAddresses?.firstOrNull { !it.address.isLoopbackAddress && it.address is java.net.Inet4Address }?.address?.hostAddress
                    ?: links?.linkAddresses?.firstOrNull { !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }?.address?.hostAddress
                val standard = if (Build.VERSION.SDK_INT >= 30) when (info?.wifiStandard) {
                    1 -> "802.11 legado"; 4 -> "Wi-Fi 4 · 802.11n"; 5 -> "Wi-Fi 5 · 802.11ac"
                    6 -> "Wi-Fi 6 · 802.11ax"; 7 -> "802.11ad"; 8 -> "Wi-Fi 7 · 802.11be"; else -> null
                } else null
                trySend(WifiReading(connected, if (info == null) "Conectado · dados restritos pelo Android" else "Conectado",
                    ssid, bssid, current?.rssi?.takeIf { it in -126..-1 } ?: info?.rssi?.takeIf { it in -126..-1 },
                    current?.frequency?.takeIf { it > 0 } ?: info?.frequency?.takeIf { it > 0 },
                    current?.linkSpeed?.takeIf { it > 0 } ?: info?.linkSpeed?.takeIf { it > 0 }, standard, ip,
                    links?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null && !it.gateway!!.isAnyLocalAddress }?.gateway?.hostAddress,
                    links?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()))
            } catch (_: SecurityException) { trySend(WifiReading(status = "Permissão necessária para consultar o Wi-Fi")) }
            catch (_: RuntimeException) { trySend(WifiReading(status = "Não foi possível consultar o Wi-Fi")) }
        }
        val flags = if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO else 0
        val callback = if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(flags) {
            override fun onAvailable(n: Network) { snapshot.set(Snapshot(n)); publish() }
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { snapshot.updateAndGet { if (it.network == n) it.copy(capabilities = c) else Snapshot(n, c) }; publish() }
            override fun onLinkPropertiesChanged(n: Network, p: LinkProperties) { snapshot.updateAndGet { if (it.network == n) it.copy(properties = p) else Snapshot(n, properties = p) }; publish() }
            override fun onLost(n: Network) { snapshot.updateAndGet { if (it.network == n) Snapshot(null) else it }; publish() }
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { snapshot.set(Snapshot(n)); publish() }
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { snapshot.updateAndGet { if (it.network == n) it.copy(capabilities = c) else Snapshot(n, c) }; publish() }
            override fun onLinkPropertiesChanged(n: Network, p: LinkProperties) { snapshot.updateAndGet { if (it.network == n) it.copy(properties = p) else Snapshot(n, properties = p) }; publish() }
            override fun onLost(n: Network) { snapshot.updateAndGet { if (it.network == n) Snapshot(null) else it }; publish() }
        }
        var registered = false
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            registered = true
            // Seed an existing connection; subsequent callbacks handle transitions.
            connectivity.allNetworks.firstOrNull { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }?.let { seed -> snapshot.updateAndGet { if (it.network == null) Snapshot(seed) else it } }
            publish()
        } catch (_: SecurityException) { trySend(WifiReading(status = "Permissão necessária para consultar o Wi-Fi")) }
        catch (_: RuntimeException) { trySend(WifiReading(status = "Serviço Wi-Fi indisponível")) }
        val poll = launch { while (isActive) { delay(1500); publish() } }
        awaitClose { poll.cancel(); if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }.flowOn(Dispatchers.IO)
}
