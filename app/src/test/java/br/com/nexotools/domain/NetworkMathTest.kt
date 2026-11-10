package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class NetworkMathTest {
    @Test fun ipv4LiteralIsParsedWithoutDnsOrHostnameCoercion() {
        assertArrayEquals(byteArrayOf(203.toByte(),0,113,7),PingMath.ipv4Bytes("203.0.113.7"))
        assertNull(PingMath.ipv4Bytes("203.0.113.256"))
        assertNull(PingMath.ipv4Bytes("203.0.113"))
        assertNull(PingMath.ipv4Bytes("203.0.113.-1"))
        assertNull(PingMath.ipv4Bytes("example.invalid"))
        assertNull(PingMath.ipv4Bytes("203.0.113.7 "))
    }
    @Test fun signalQualityHasDefinedEndpointsAndClamp() {
        assertEquals(0, WifiMath.quality(-90)); assertEquals(50, WifiMath.quality(-60))
        assertEquals(100, WifiMath.quality(-30)); assertEquals(0, WifiMath.quality(-120))
        assertEquals(100, WifiMath.quality(-10))
    }
    @Test fun channelsRespectBandAndSpecialFrequencies() {
        assertEquals(1, WifiMath.channel(2412)); assertEquals(14, WifiMath.channel(2484))
        assertEquals(36, WifiMath.channel(5180)); assertEquals(2, WifiMath.channel(5935))
        assertEquals(1, WifiMath.channel(5955)); assertEquals(233, WifiMath.channel(7115))
        assertNull(WifiMath.channel(5956)); assertNull(WifiMath.channel(9000))
    }
    @Test fun distanceUsesReferenceAtOneMetreAndExponent() {
        assertEquals(1.0, WifiMath.distance(-40)!!, 1e-9)
        assertEquals(10.0, WifiMath.distance(-60)!!, 1e-9)
        assertEquals(kotlin.math.sqrt(10.0), WifiMath.distance(-60, exponent = 4.0)!!, 1e-9)
        assertNull(WifiMath.distance(-127)); assertNull(WifiMath.distance(-60, exponent = 0.0))
        assertNull(WifiMath.distance(-60, referenceDbm = Double.NaN))
    }
    @Test fun statisticsDifferentiateLossAndJitter() {
        val s = PingMath.statistics(listOf(PingSample(1, 10.0), PingSample(2, 30.0), PingSample(3, null), PingSample(4, 20.0)))
        assertEquals(10.0, s.minimumMs!!, 0.0); assertEquals(30.0, s.maximumMs!!, 0.0)
        assertEquals(20.0, s.meanMs!!, 0.0); assertEquals(20.0, s.jitterMs!!, 0.0)
        assertEquals(25.0, s.lossPercent, 0.0); assertEquals(4, s.sent); assertEquals(3, s.received)
    }
    @Test fun lteSinrChangesUnitAtAndroid11() {
        assertEquals(13.0, RadioMath.lteSinr(130, 29)!!, 0.0)
        assertEquals(13.0, RadioMath.lteSinr(13, 30)!!, 0.0)
        assertEquals(-20.0, RadioMath.lteSinr(-200, 26)!!, 0.0)
        assertNull(RadioMath.lteSinr(Int.MAX_VALUE, 36)); assertNull(RadioMath.lteSinr(130, 30))
    }
    @Test fun emptyAndFailedTestsDoNotInventLatency() {
        val empty = PingMath.statistics(emptyList()); assertNull(empty.meanMs); assertEquals(0, empty.sent)
        val failed = PingMath.statistics(listOf(PingSample(1, null), PingSample(2, Double.NaN)))
        assertNull(failed.meanMs); assertNull(failed.jitterMs); assertEquals(100.0, failed.lossPercent, 0.0)
    }
}
