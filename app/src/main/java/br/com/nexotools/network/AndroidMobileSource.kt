package br.com.nexotools.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import br.com.nexotools.domain.MobileReading
import br.com.nexotools.domain.MobileSource
import br.com.nexotools.domain.RadioMetrics
import br.com.nexotools.domain.RadioMath
import br.com.nexotools.domain.SimOption
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

@Singleton
class AndroidMobileSource @Inject constructor(@ApplicationContext private val context: Context) : MobileSource {
    override fun subscriptions(): List<SimOption> = try {
        context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().map {
            SimOption(it.subscriptionId, "SIM ${it.simSlotIndex + 1} · ${it.carrierName}")
        }
    } catch (_: SecurityException) { emptyList() }
    catch (_: RuntimeException) { emptyList() }

    @Suppress("DEPRECATION")
    override fun readings(simId: Int?): Flow<MobileReading> = callbackFlow {
        val sourceScope = this
        val base = context.getSystemService(TelephonyManager::class.java)
        if (base == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            trySend(MobileReading(status = "Rede móvel indisponível neste aparelho")); close(); return@callbackFlow
        }
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            trySend(MobileReading(status = "Permissão de telefone necessária")); close(); return@callbackFlow
        }
        val selected = simId ?: SubscriptionManager.getDefaultDataSubscriptionId().takeIf { it >= 0 && it != Int.MAX_VALUE }
        val manager = selected?.let { base.createForSubscriptionId(it) } ?: base
        data class Snapshot(val signal: SignalStrength? = null, val displayOverride: Int? = null, val cells: List<CellInfo> = emptyList())
        val snapshot = AtomicReference(Snapshot())
        fun valid(value: Int): Int? = value.takeIf { it != Int.MAX_VALUE }
        fun lte(strength: CellSignalStrengthLte?, cell: CellInfoLte?): RadioMetrics? {
            val s = strength ?: cell?.cellSignalStrength ?: return null
            return RadioMetrics(valid(s.rsrp), valid(s.rsrq), RadioMath.lteSinr(s.rssnr, Build.VERSION.SDK_INT), valid(s.dbm),
                cell?.cellIdentity?.pci?.let(::valid),
                if (Build.VERSION.SDK_INT >= 30) cell?.cellIdentity?.bands?.firstOrNull() else null)
        }
        fun nr(strength: CellSignalStrengthNr?, cell: CellInfoNr?): RadioMetrics? {
            if (Build.VERSION.SDK_INT < 29) return null
            val s = strength ?: cell?.cellSignalStrength as? CellSignalStrengthNr ?: return null
            val identity = cell?.cellIdentity as? android.telephony.CellIdentityNr
            return RadioMetrics(valid(s.ssRsrp), valid(s.ssRsrq), valid(s.ssSinr)?.toDouble(), valid(s.dbm),
                identity?.pci?.let(::valid), if (Build.VERSION.SDK_INT >= 30) identity?.bands?.firstOrNull() else null)
        }
        fun publish() {
            try {
                val state = snapshot.get()
                val displayOverride = state.displayOverride
                val signal = state.signal
                val cells = state.cells
                val type = manager.dataNetworkType
                val technology = when {
                    type == TelephonyManager.NETWORK_TYPE_NR -> "5G NR · modo não informado"
                    type == TelephonyManager.NETWORK_TYPE_LTE && Build.VERSION.SDK_INT >= 30 &&
                        (displayOverride == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                        displayOverride == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE ||
                        (Build.VERSION.SDK_INT >= 31 && displayOverride == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)) -> "5G disponível (NSA) · âncora LTE"
                    type == TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                    type == TelephonyManager.NETWORK_TYPE_HSPAP || type == TelephonyManager.NETWORK_TYPE_UMTS ||
                        type == TelephonyManager.NETWORK_TYPE_HSDPA || type == TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
                    type == TelephonyManager.NETWORK_TYPE_EDGE || type == TelephonyManager.NETWORK_TYPE_GPRS || type == TelephonyManager.NETWORK_TYPE_GSM -> "2G"
                    else -> null
                }
                val registered = cells.filter { it.isRegistered }
                val lteCell = registered.filterIsInstance<CellInfoLte>().firstOrNull()
                val nrCell = if (Build.VERSION.SDK_INT >= 29) registered.filterIsInstance<CellInfoNr>().firstOrNull() else null
                val lteStrength = if (Build.VERSION.SDK_INT >= 29) signal?.getCellSignalStrengths(CellSignalStrengthLte::class.java)?.firstOrNull() else null
                val nrStrength = if (Build.VERSION.SDK_INT >= 29) signal?.getCellSignalStrengths(CellSignalStrengthNr::class.java)?.firstOrNull() else null
                trySend(MobileReading(if (technology == null) "Sem dados de rede móvel" else "Dados fornecidos pelo modem",
                    technology, manager.networkOperatorName?.takeIf { it.isNotBlank() },
                    subscriptions().firstOrNull { it.id == selected }?.label,
                    signal?.level?.takeIf { it in 0..4 }, lte(lteStrength, lteCell),
                    if (Build.VERSION.SDK_INT >= 29) nr(nrStrength, nrCell) else null))
            } catch (_: SecurityException) { trySend(MobileReading(status = "Permissão necessária para consultar o modem")) }
            catch (_: RuntimeException) { trySend(MobileReading(status = "Dados do modem não disponíveis")) }
        }
        val poll = launch {
            while (isActive) {
                try {
                    if (Build.VERSION.SDK_INT >= 28) manager.signalStrength?.let { s -> snapshot.updateAndGet { it.copy(signal = s) } }
                    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                        manager.allCellInfo.orEmpty().let { cells -> snapshot.updateAndGet { it.copy(cells = cells) } }
                    else snapshot.updateAndGet { it.copy(cells = emptyList()) }
                } catch (_: SecurityException) { /* Signal information can still be available without cell location. */ }
                catch (_: RuntimeException) { /* Some modems do not provide a cell list. */ }
                publish(); delay(3000)
            }
        }
        var modern: TelephonyCallback? = null
        var legacy: PhoneStateListener? = null
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener, TelephonyCallback.DisplayInfoListener {
                    override fun onSignalStrengthsChanged(value: SignalStrength) { snapshot.updateAndGet { it.copy(signal = value) }; sourceScope.launch { publish() } }
                    override fun onDisplayInfoChanged(value: TelephonyDisplayInfo) { if (Build.VERSION.SDK_INT >= 30) { snapshot.updateAndGet { it.copy(displayOverride = value.overrideNetworkType) }; sourceScope.launch { publish() } } }
                }
                manager.registerTelephonyCallback(context.mainExecutor, callback); modern = callback
            } else {
                // The public Looper constructor does not exist. Create the legacy listener on
                // Main, while keeping registration, polling and Binder reads on IO.
                val callback = withContext(Dispatchers.Main.immediate) {
                    if (Build.VERSION.SDK_INT >= 30) object : PhoneStateListener() {
                        override fun onSignalStrengthsChanged(value: SignalStrength?) { snapshot.updateAndGet { it.copy(signal = value) }; sourceScope.launch { publish() } }
                        override fun onDisplayInfoChanged(value: TelephonyDisplayInfo) { snapshot.updateAndGet { it.copy(displayOverride = value.overrideNetworkType) }; sourceScope.launch { publish() } }
                    } else object : PhoneStateListener() {
                        override fun onSignalStrengthsChanged(value: SignalStrength?) { snapshot.updateAndGet { it.copy(signal = value) }; sourceScope.launch { publish() } }
                    }
                }
                val mask = PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                    (if (Build.VERSION.SDK_INT >= 30) PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED else 0)
                manager.listen(callback, mask); legacy = callback
            }
        } catch (_: SecurityException) { publish() }
        catch (_: RuntimeException) { publish() }
        awaitClose {
            poll.cancel()
            if (Build.VERSION.SDK_INT >= 31) modern?.let { runCatching { manager.unregisterTelephonyCallback(it) } }
            legacy?.let { runCatching { manager.listen(it, PhoneStateListener.LISTEN_NONE) } }
        }
    }.flowOn(Dispatchers.IO)
}
