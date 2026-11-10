package br.com.nexotools.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import br.com.nexotools.domain.MacVendorMath
import br.com.nexotools.domain.NearbyAccessPoint
import br.com.nexotools.domain.NearbyScanState
import br.com.nexotools.domain.NearbyWifiSource
import br.com.nexotools.domain.VendorLookup
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil

@Singleton
class AssetVendorLookup @Inject constructor(@ApplicationContext private val context: Context) : VendorLookup {
    private val mutex = Mutex()
    private var registry: Map<String, String>? = null
    override suspend fun lookup(bssid: String?): String? = withContext(Dispatchers.IO) {
        val prefix = MacVendorMath.prefix(bssid) ?: return@withContext null
        mutex.withLock {
            if (registry == null) registry = try {
                context.assets.open("oui-router.csv").bufferedReader().use { MacVendorMath.parseCsv(it.readText()) }
            } catch (_: java.io.IOException) { emptyMap() }
            registry?.get(prefix)
        }
    }
}

@Singleton
class AndroidNearbyWifi @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vendors: VendorLookup,
) : NearbyWifiSource {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val mutex = Mutex()
    private val active = AtomicInteger()
    private val state = MutableStateFlow(NearbyScanState())
    private val acceptedRequests = ArrayDeque<Long>()
    private var lastAttempt = -35_000L
    private var requestStarted: Long? = null

    private fun permissions() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    @Suppress("DEPRECATION")
    private fun locationEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 28) context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        else Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
    } catch (_: RuntimeException) { false }
    private fun refreshGate(now: Long) {
        while (acceptedRequests.isNotEmpty() && now - acceptedRequests.first >= 120_000) acceptedRequests.removeFirst()
        val remaining = maxOf(0L, 35_000L - (now - lastAttempt),
            if (acceptedRequests.size >= 4) 120_000L - (now - acceptedRequests.first) else 0L)
        val permission = permissions()
        val location = locationEnabled()
        val enabled = try { wifi?.isWifiEnabled == true } catch (_: RuntimeException) { false }
        val unavailable = when {
            wifi == null -> "Wi-Fi indisponível neste aparelho"
            !permission -> "Permissão de localização precisa necessária"
            !location -> "Ative a localização do Android para consultar redes"
            !enabled -> "Wi-Fi desativado"
            else -> null
        }
        var current = state.value.copy(permissionGranted = permission, locationEnabled = location, wifiEnabled = enabled,
            nextScanSeconds = ceil(remaining / 1000.0).toInt(), elapsedMillis = now)
        if (unavailable != null) { requestStarted = null; current = current.copy(accessPoints = emptyList(), scanning = false, isCached = true, status = unavailable) }
        else if (active.get() > 0 && (!state.value.permissionGranted || !state.value.locationEnabled || !state.value.wifiEnabled))
            current = current.copy(status = "Acesso disponível; toque em Consultar para atualizar")
        else if (requestStarted?.let { now - it >= 15_000L } == true) {
            requestStarted = null; current = current.copy(scanning = false, isCached = true, status = "Sem novos resultados; exibindo o cache do Android")
        }
        state.value = current
    }
    @SuppressLint("MissingPermission") // Fine location is rechecked here; state/Wi-Fi permissions are normal manifest permissions.
    @Suppress("DEPRECATION")
    private suspend fun readCache(updated: Boolean?) {
        val now = SystemClock.elapsedRealtime(); refreshGate(now)
        val current = state.value
        if (wifi == null || !current.permissionGranted || !current.locationEnabled || !current.wifiEnabled) return
        try {
            val points = wifi.scanResults.orEmpty().map { result ->
                val bssid = result.BSSID?.takeIf { it.isNotBlank() }
                NearbyAccessPoint(result.SSID?.takeIf { it.isNotBlank() }, bssid,
                    result.level.takeIf { it in -126..-1 }, result.frequency.takeIf { it > 0 },
                    result.capabilities?.takeIf { it.isNotBlank() }, result.timestamp.takeIf { it > 0 }, vendors.lookup(bssid))
            }.distinctBy { it.bssid ?: "${it.ssid}|${it.frequencyMhz}" }.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
            if (active.get() == 0) return
            if (updated != null) requestStarted = null
            state.value = state.value.copy(accessPoints = points, scanning = if (updated != null) false else state.value.scanning,
                isCached = updated != true, status = when (updated) {
                    true -> "Resultados atualizados pelo Android"
                    false -> "Consulta sem atualização; exibindo o cache do Android"
                    null -> "Cache do Android · toque em Consultar para atualizar"
                }, elapsedMillis = SystemClock.elapsedRealtime())
        } catch (_: SecurityException) { requestStarted = null; state.value = state.value.copy(accessPoints = emptyList(), scanning = false, isCached = true, status = "O Android não autorizou a leitura das redes") }
        catch (_: RuntimeException) { requestStarted = null; state.value = state.value.copy(scanning = false, isCached = true, status = "Resultados Wi-Fi não disponíveis") }
    }

    @Suppress("DEPRECATION")
    override fun watch(): Flow<NearbyScanState> = callbackFlow {
        val sourceScope = this
        val relay = launch { state.collect { trySend(it) } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                sourceScope.launch { mutex.withLock { readCache(updated) } }
            }
        }
        var registered = false
        try {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            // SCAN_RESULTS is a protected system broadcast; no result payload is trusted/read.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true; active.incrementAndGet()
            mutex.withLock { readCache(null) }
        } catch (_: RuntimeException) { state.value = state.value.copy(status = "Não foi possível receber atualizações Wi-Fi") }
        val ticker = launch { while (isActive) { mutex.withLock { refreshGate(SystemClock.elapsedRealtime()) }; delay(1000) } }
        awaitClose {
            ticker.cancel(); relay.cancel()
            if (registered) { runCatching { context.unregisterReceiver(receiver) }; if (active.decrementAndGet() == 0) state.value = NearbyScanState(status = "Consulta pausada") }
        }
    }.flowOn(Dispatchers.IO)

    @SuppressLint("MissingPermission") // Permission/location gates are checked immediately before startScan.
    @Suppress("DEPRECATION")
    override suspend fun requestScan(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = SystemClock.elapsedRealtime(); refreshGate(now)
            val current = state.value
            if (active.get() == 0 || current.scanning || current.nextScanSeconds > 0 || !current.permissionGranted || !current.locationEnabled || !current.wifiEnabled || wifi == null) return@withLock false
            lastAttempt = now
            val accepted = try { wifi.startScan() } catch (_: SecurityException) { false } catch (_: RuntimeException) { false }
            if (accepted) {
                acceptedRequests.addLast(now); requestStarted = now
                state.value = state.value.copy(scanning = true, nextScanSeconds = 35, status = "Consulta aceita; aguardando resultados do Android")
            } else {
                readCache(false)
                state.value = state.value.copy(status = "O Android recusou a consulta; exibindo o cache", nextScanSeconds = 35, isCached = true)
            }
            accepted
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NearbyModule {
    @Binds abstract fun nearby(source: AndroidNearbyWifi): NearbyWifiSource
    @Binds abstract fun vendors(source: AssetVendorLookup): VendorLookup
}
