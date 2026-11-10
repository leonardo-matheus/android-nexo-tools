package br.com.nexotools.domain

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

enum class SpeedProfile(val title: String, val downloadLimit: Long, val uploadLimit: Long) {
    ECONOMY("Economia · até 24 MB", 16_000_000, 8_000_000),
    BALANCED("Equilibrado · até 202 MB", 128L * 1_048_576, 64L * 1_048_576),
    COMPLETE("Completo · até 806 MB", 512L * 1_048_576, 256L * 1_048_576),
}
data class SpeedConfig(val profile: SpeedProfile = SpeedProfile.BALANCED, val measureLoadedLatency: Boolean = true)
enum class SpeedStage { READY, WARMUP, LATENCY, DOWNLOAD, UPLOAD, COMPLETE, ERROR }
data class HttpLatencyStats(val minimumMs: Double? = null, val medianMs: Double? = null, val meanMs: Double? = null,
    val maximumMs: Double? = null, val jitterMs: Double? = null, val successful: Int = 0, val attempts: Int = 0)
data class SpeedPoint(val elapsedMs: Long, val mbps: Double, val stage: SpeedStage)
data class SpeedState(
    val stage: SpeedStage = SpeedStage.READY,
    val currentMbps: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val unloaded: HttpLatencyStats = HttpLatencyStats(),
    val downLoaded: HttpLatencyStats = HttpLatencyStats(),
    val upLoaded: HttpLatencyStats = HttpLatencyStats(),
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val phaseProgress: Float = 0f,
    val elapsedMs: Long = 0,
    val shortSample: Boolean = false,
    val message: String? = null,
    val trace: List<SpeedPoint> = emptyList(),
)
interface SpeedTestSource { fun run(config: SpeedConfig): Flow<SpeedState> }

object SpeedMath {
    /** Target roughly one second per stream; reserve time for response/variable network latency. */
    fun nextChunkBytes(previousBytes: Long, requestNanos: Long, remainingNanos: Long): Long {
        if (previousBytes <= 0 || requestNanos <= 0 || remainingNanos <= 50_000_000L) return 0
        val bytesPerNano = previousBytes / requestNanos.toDouble()
        val wanted = (bytesPerNano * 1_000_000_000.0).coerceIn(16_384.0, 8_000_000.0)
        val fitting = bytesPerNano * (remainingNanos - 50_000_000L) * 0.85
        val next = minOf(wanted, fitting)
        return if (next < 16_384) 0 else next.toLong()
    }
    fun mbps(bytes: Long, elapsedNanos: Long): Double? = if (bytes < 0 || elapsedNanos <= 0) null else bytes * 8_000.0 / elapsedNanos
    /** Mean aggregate bandwidth uses one shared parallel window, not a sum of stream speeds. */
    fun aggregateMbps(streamBytes: List<Long>, elapsedNanos: Long): Double? {
        if (streamBytes.any { it < 0 }) return null
        return mbps(streamBytes.sum(), elapsedNanos)
    }
    fun latency(samples: List<Double?>): HttpLatencyStats {
        val valid = samples.mapNotNull { it?.takeIf { value -> value.isFinite() && value >= 0 } }
        val sorted = valid.sorted()
        val median = when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
        val differences = samples.zipWithNext().mapNotNull { (a, b) ->
            if (a != null && b != null && a.isFinite() && b.isFinite() && a >= 0 && b >= 0) abs(b - a) else null
        }
        return HttpLatencyStats(valid.minOrNull(), median, valid.takeIf { it.isNotEmpty() }?.average(), valid.maxOrNull(),
            differences.takeIf { it.isNotEmpty() }?.average(), valid.size, samples.size)
    }
}

/** Reservations include failed/in-flight requests; they are never refunded after uncertain traffic. */
class TransferBudget(val limit: Long) {
    private val reserved = AtomicLong(0)
    init { require(limit >= 0) }
    fun claim(wanted: Long): Long {
        require(wanted > 0)
        while (true) {
            val current = reserved.get()
            val amount = minOf(wanted, limit - current)
            if (amount <= 0) return 0
            if (reserved.compareAndSet(current, current + amount)) return amount
        }
    }
    fun reservedBytes(): Long = reserved.get()
}
