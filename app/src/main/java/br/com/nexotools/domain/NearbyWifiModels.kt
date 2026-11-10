package br.com.nexotools.domain

import java.util.Locale
import kotlinx.coroutines.flow.Flow

data class NearbyAccessPoint(
    val ssid: String?, val bssid: String?, val rssi: Int?, val frequencyMhz: Int?,
    val capabilities: String?, val timestampMicros: Long?, val manufacturer: String? = null,
)
data class NearbyScanState(
    val accessPoints: List<NearbyAccessPoint> = emptyList(),
    val scanning: Boolean = false,
    val isCached: Boolean = true,
    val status: String = "Aguardando dados do Android",
    val nextScanSeconds: Int = 0,
    val permissionGranted: Boolean = false,
    val locationEnabled: Boolean = false,
    val wifiEnabled: Boolean = false,
    val elapsedMillis: Long = 0,
)
interface NearbyWifiSource {
    fun watch(): Flow<NearbyScanState>
    /** True means Android accepted the request, not that a fresh scan completed. */
    suspend fun requestScan(): Boolean
}
interface VendorLookup { suspend fun lookup(bssid: String?): String? }

data class WifiSecurityAssessment(
    val label: String,
    val wpsAdvertised: Boolean,
    val concern: String?,
    val recommendation: String,
)

object NearbyWifiMath {
    /** Broadcast capability strings describe advertised configuration, not proven vulnerabilities. */
    fun security(capabilities: String?): WifiSecurityAssessment {
        val cap = capabilities?.uppercase(Locale.ROOT).orEmpty()
        val wps = Regex("\\[WPS(?:[-+][^]]*)?]").containsMatchIn(cap)
        val wep = cap.contains("WEP")
        val wpa1 = cap.contains("[WPA-") || cap.contains("WPA1")
        val tkip = cap.contains("TKIP")
        val wpa3 = cap.contains("SAE") || cap.contains("WPA3") || cap.contains("EAP_SUITE_B_192") || cap.contains("EAP-SUITE-B-192")
        val wpa2 = cap.contains("WPA2") || cap.contains("RSN") &&
            (cap.contains("PSK") || cap.contains("EAP") && !cap.contains("SUITE_B_192") && !cap.contains("SUITE-B-192"))
        val owe = cap.contains("OWE")
        val unknownProtected = cap.contains("WAPI") || cap.contains("RSN") || cap.contains("PRIVACY") || tkip
        val label = when {
            wep -> "WEP anunciado"
            owe && cap.contains("OWE_TRANSITION") -> "OWE em transição"
            owe -> "OWE anunciado"
            wpa3 && wpa2 -> "WPA2 / WPA3 anunciado"
            wpa3 -> "WPA3 anunciado"
            wpa1 && wpa2 -> "WPA1 / WPA2 anunciado"
            wpa1 -> "WPA1 anunciado"
            wpa2 -> "WPA2 / RSN anunciado"
            unknownProtected -> "Proteção não identificada"
            cap.contains("ESS") || cap.contains("IBSS") -> "Rede aberta anunciada"
            else -> "Segurança não disponível"
        }
        val concern = when {
            wep -> "WEP é um protocolo obsoleto."
            wpa1 || tkip -> "Há suporte anunciado a WPA1 ou TKIP, tecnologias antigas."
            owe && cap.contains("OWE_TRANSITION") -> "O modo de transição pode atender clientes sem criptografia."
            !owe && !wpa3 && !wpa2 && !unknownProtected && (cap.contains("ESS") || cap.contains("IBSS")) -> "Não há criptografia Wi-Fi anunciada."
            else -> null
        }
        val recommendation = when {
            wep || wpa1 || tkip -> "No seu roteador, prefira WPA3 ou WPA2 com AES/CCMP e desative os modos antigos."
            owe -> "OWE criptografa o enlace sem autenticar por senha. Confira o modo de transição na configuração."
            concern != null -> "No seu roteador, configure WPA3 ou WPA2-AES e uma senha forte."
            wpa3 || wpa2 -> "Mantenha firmware atualizado e senha forte. O anúncio não comprova a segurança completa."
            else -> "Consulte a configuração do seu roteador; os dados anunciados são insuficientes."
        } + if (wps) " Se WPS não for necessário, desative-o na configuração." else ""
        return WifiSecurityAssessment(label, wps, concern, recommendation)
    }
    fun ageSeconds(nowMillis: Long, timestampMicros: Long?): Long? {
        if (timestampMicros == null || timestampMicros <= 0 || timestampMicros > nowMillis * 1000L) return null
        return (nowMillis * 1000L - timestampMicros) / 1_000_000L
    }
}

object MacVendorMath {
    private fun bytes(bssid: String?): List<Int>? {
        if (bssid == null || !bssid.matches(Regex("(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}"))) return null
        return bssid.split(':', '-').map { it.toInt(16) }.takeIf { it.any { value -> value != 0 } }
    }
    fun isLocal(bssid: String?): Boolean = bytes(bssid)?.first()?.let { it and 0x02 != 0 } ?: false
    fun prefix(bssid: String?): String? {
        val bytes = bytes(bssid) ?: return null
        if (bytes.first() and 0x03 != 0) return null // locally administered or multicast
        return bytes.take(3).joinToString("") { "%02X".format(Locale.ROOT, it) }
    }
    fun parseCsv(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        if (line.trimStart().startsWith('#')) return@mapNotNull null
        val fields = line.split(',', limit = 2)
        val prefix = fields.getOrNull(0)?.trim()?.uppercase(Locale.ROOT) ?: return@mapNotNull null
        val company = fields.getOrNull(1)?.trim()?.removeSurrounding("\"")?.replace("\"\"", "\"")
        if (!prefix.matches(Regex("[0-9A-F]{6}")) || company.isNullOrBlank()) null else prefix to company
    }.toMap()
}
