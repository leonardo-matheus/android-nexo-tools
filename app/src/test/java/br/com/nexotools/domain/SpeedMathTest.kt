package br.com.nexotools.domain

import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class SpeedMathTest {
    @Test fun chunksAdaptToMeasuredSpeedAndRemainingTime() {
        assertEquals(250_000L, SpeedMath.nextChunkBytes(125_000, 500_000_000, 12_000_000_000))
        assertEquals(8_000_000L, SpeedMath.nextChunkBytes(125_000, 1_000_000, 12_000_000_000))
        assertTrue(SpeedMath.nextChunkBytes(125_000, 500_000_000, 300_000_000) < 62_500)
        assertEquals(0L, SpeedMath.nextChunkBytes(16_384, 1_000_000_000, 100_000_000))
    }
    @Test fun parallelStreamsShareOneWindow() {
        assertEquals(48.0, SpeedMath.aggregateMbps(listOf(1_000_000, 2_000_000, 3_000_000), 1_000_000_000)!!, 1e-9)
        assertNull(SpeedMath.mbps(1, 0)); assertNull(SpeedMath.mbps(-1, 100))
        assertEquals(0.0, SpeedMath.mbps(0, 1_000_000_000)!!, 0.0)
    }
    @Test fun reservationsCannotOverspendAcrossThreeWorkers() {
        val budget = TransferBudget(1_000_003)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val futures = List(3) { pool.submit<Long> {
                var sum = 0L
                while (true) { val next = budget.claim(125_000); if (next == 0L) break; sum += next }
                sum
            } }
            assertEquals(1_000_003, futures.sumOf { it.get() })
            assertEquals(1_000_003, budget.reservedBytes())
            assertEquals(0, budget.claim(1))
        } finally { pool.shutdownNow() }
    }
    @Test fun medianMeanAndJitterDoNotBridgeFailedProbes() {
        val stats = SpeedMath.latency(listOf(10.0, 30.0, null, 20.0, 40.0, Double.NaN))
        assertEquals(25.0, stats.medianMs!!, 0.0)
        assertEquals(25.0, stats.meanMs!!, 0.0)
        assertEquals(20.0, stats.jitterMs!!, 0.0)
        assertEquals(4, stats.successful); assertEquals(6, stats.attempts)
        assertNull(SpeedMath.latency(listOf(null, Double.NaN)).medianMs)
    }
    @Test fun profilesHaveExactVisiblePayloadLimits() {
        assertEquals(24_000_000, SpeedProfile.ECONOMY.downloadLimit + SpeedProfile.ECONOMY.uploadLimit)
        assertEquals(201_326_592, SpeedProfile.BALANCED.downloadLimit + SpeedProfile.BALANCED.uploadLimit)
        assertEquals(805_306_368, SpeedProfile.COMPLETE.downloadLimit + SpeedProfile.COMPLETE.uploadLimit)
    }
}
