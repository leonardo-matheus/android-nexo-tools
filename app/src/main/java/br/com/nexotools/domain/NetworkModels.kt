package br.com.nexotools.domain

import kotlinx.coroutines.flow.Flow
import kotlin.math.pow
import kotlin.math.roundToInt

data class WifiReading(
    val connected: Boolean = false,
    val status: String = "Aguardando conexão",
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val frequencyMhz: Int? = null,
    val linkMbps: Int? = null,
    val standard: String? = null,
    val ip: String? = null,
    val gateway: String? = null,
    val dns: List<String> = emptyList(),
)

interface WifiSource { fun readings(): Flow<WifiReading> }

/** RSSI is mapped linearly: -90 dBm = 0, -30 dBm = 100. This is signal quality, not throughput. */
object WifiMath {
    fun quality(rssi: Int): Int = (((rssi.toDouble() + 90) / 60.0) * 100).coerceIn(0.0, 100.0).roundToInt()
    fun label(score: Int): String = when {
        score >= 80 -> "Excelente"
        score >= 60 -> "Bom"
        score >= 35 -> "Regular"
        else -> "Ruim"
    }
    fun band(frequency: Int): String? = when (frequency) {
        in 2400..2500 -> "2,4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> null
    }
    fun channel(frequency: Int): Int? = when {
        frequency == 2484 -> 14
        frequency in 2412..2472 && (frequency - 2407) % 5 == 0 -> (frequency - 2407) / 5
        frequency in 4910..4980 && (frequency - 4000) % 5 == 0 -> (frequency - 4000) / 5
        frequency in 5005..5895 && (frequency - 5000) % 5 == 0 -> (frequency - 5000) / 5
        frequency == 5935 -> 2
        frequency in 5955..7115 && (frequency - 5950) % 5 == 0 -> (frequency - 5950) / 5
        else -> null
    }
    /** Log-distance model, referenced at one metre. Always a low-confidence estimate. */
    fun distance(rssi: Int, referenceDbm: Double = -40.0, exponent: Double = 2.0): Double? {
        if (rssi !in -126..-1 || !referenceDbm.isFinite() || !exponent.isFinite() || exponent <= 0) return null
        return 10.0.pow((referenceDbm - rssi) / (10.0 * exponent)).takeIf { it.isFinite() && it > 0 }
    }
}

data class RadioMetrics(
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Double? = null,
    val dbm: Int? = null,
    val pci: Int? = null,
    val band: Int? = null,
)
object RadioMath {
    /** AOSP changed LTE RSSNR from tenths of dB to dB in Android 11 (API 30). */
    fun lteSinr(raw: Int, apiLevel: Int): Double? = if (apiLevel >= 30)
        raw.takeIf { it in -20..30 }?.toDouble() else raw.takeIf { it in -200..300 }?.div(10.0)
}
data class MobileReading(
    val status: String = "Aguardando modem",
    val technology: String? = null,
    val operator: String? = null,
    val simLabel: String? = null,
    val level: Int? = null,
    val lte: RadioMetrics? = null,
    val nr: RadioMetrics? = null,
)
data class SimOption(val id: Int, val label: String)
interface MobileSource {
    fun subscriptions(): List<SimOption>
    fun readings(simId: Int? = null): Flow<MobileReading>
}

data class PingSample(val sequence: Int, val latencyMs: Double?, val timestamp: Long = System.currentTimeMillis())
data class PingStatistics(
    val minimumMs: Double? = null,
    val maximumMs: Double? = null,
    val meanMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPercent: Double = 0.0,
    val sent: Int = 0,
    val received: Int = 0,
)
interface PingSource {
    fun run(host: String, port: Int = 443, count: Int = 10, timeoutMs: Int = 3000): Flow<PingSample>
}
object PingMath {
    /** Dotted decimal literals bypass DNS; hostnames are resolved separately. */
    fun ipv4Bytes(host: String): ByteArray? {
        val parts=host.split('.')
        if(parts.size!=4 || parts.any { it.length !in 1..3 || it.any { c->c !in '0'..'9' } }) return null
        val values=parts.map { it.toInt() }
        if(values.any { it !in 0..255 }) return null
        return ByteArray(4) { values[it].toByte() }
    }
    /** Jitter is mean absolute difference of adjacent successful probes, without bridging losses. */
    fun statistics(samples: List<PingSample>): PingStatistics {
        val values = samples.mapNotNull { it.latencyMs?.takeIf { n -> n.isFinite() && n >= 0 } }
        val differences = samples.zipWithNext().mapNotNull { (a, b) ->
            val first = a.latencyMs; val second = b.latencyMs
            if (first != null && second != null && first.isFinite() && second.isFinite() && first >= 0 && second >= 0)
                kotlin.math.abs(second - first) else null
        }
        return PingStatistics(values.minOrNull(), values.maxOrNull(), values.takeIf { it.isNotEmpty() }?.average(),
            differences.takeIf { it.isNotEmpty() }?.average(),
            if (samples.isEmpty()) 0.0 else (samples.size - values.size) * 100.0 / samples.size,
            samples.size, values.size)
    }
}
