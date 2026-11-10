package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class ProbeInputsTest {
    private fun rejectedHost(value: String) { assertThrows(IllegalArgumentException::class.java) { ProbeInputs.host(value) } }
    private fun rejectedPorts(value: String) { assertThrows(IllegalArgumentException::class.java) { ProbeInputs.ports(value) } }

    @Test fun normalizesIdnAndDnsCaseWithoutResolving() {
        assertEquals("xn--bcher-kva.test", ProbeInputs.host("BÜCHER.test.").value)
        assertNull(ProbeInputs.host("sintetico.test").literalBytes)
    }
    @Test fun rejectsUrlsEmbeddedPortsCredentialsAndWhitespace() {
        listOf("", " ", "sintetico.test:443", "https://sintetico.test", "usuario@sintetico.test", "sintetico.test/caminho", "sintetico.test?x=1", "sintetico.test#parte", "sintetico.test\\caminho", " a.test", "a.test ", "a\u00a0.test", "a\u202e.test").forEach(::rejectedHost)
    }
    @Test fun rejectsMalformedDnsAndExcessiveLabels() {
        listOf("a..test", "-a.test", "a-.test", "a_test", "${"a".repeat(64)}.test", List(5) { "a".repeat(63) }.joinToString(".")).forEach(::rejectedHost)
    }
    @Test fun ipv4IsFourUnambiguousDecimalOctets() {
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 17), ProbeInputs.host("192.0.2.17").literalBytes)
        assertArrayEquals(ProbeInputs.host("192.0.2.17").literalBytes, ProbeInputs.host("192。0。2。17").literalBytes)
        listOf("256.0.2.1", "192.0.2", "192.0.2.1.2", "192..2.1", "0192.0.2.1", "01.0.2.1", "1234").forEach(::rejectedHost)
        rejectedHost("256。0。2。17")
    }
    @Test fun supportsCompressedFullAndMappedIpv6Literals() {
        val compressed = ProbeInputs.host("[2001:db8::17]")
        assertEquals("2001:db8::17", compressed.value)
        assertArrayEquals(ProbeInputs.host("2001:db8:0:0:0:0:0:17").literalBytes, compressed.literalBytes)
        assertEquals(16, ProbeInputs.host("::").literalBytes!!.size)
        assertEquals(16, ProbeInputs.host("::ffff:192.0.2.17").literalBytes!!.size)
        listOf("[2001:db8::17]:443", "2001:db8:::17", "2001::db8::17", "2001:db8:0:0:0:0:17", "2001:db8:0:0:0:0:0:0:17", "2001:db8::gg", "192.0.2.1::17", "[sintetico.test]", "2001:db8::17/64").forEach(::rejectedHost)
    }
    @Test fun acceptsScopedIpv6WithoutLookingUpInterfaces() {
        val scoped = ProbeInputs.host("[fe80::17%wlan0]")
        assertEquals("wlan0", scoped.scope)
        assertEquals("fe80::17", scoped.tlsPeerName)
        assertEquals("3", ProbeInputs.host("fe80::17%3").scope)
        listOf("fe80::17%", "fe80::17%a%b", "fe80::17%999999999999999999", "192.0.2.17%3").forEach(::rejectedHost)
    }
    @Test fun sortsAndDeduplicatesSmallPortRanges() {
        assertEquals(listOf(22, 80, 443, 8000, 8001, 8002), ProbeInputs.ports("443,22,80,8000-8002,22"))
        assertEquals((1..256).toList(), ProbeInputs.ports("1-256"))
        assertTrue(ProbeInputs.commonPorts.size <= ProbeInputs.MAX_PORTS)
    }
    @Test fun rejectsOversizedInvalidAndReversedPortListsBeforeScanning() {
        listOf("", "0", "65536", "100-90", "1-257", "1-65535", "1-256,443", "22,,443", "22 443", "22;443", "-22", "1.5").forEach(::rejectedPorts)
    }
}
