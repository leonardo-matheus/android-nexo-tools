package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class NearbyWifiMathTest {
    @Test fun advertisedWpsDoesNotCertifyDisabledOrVulnerable() {
        val wps = NearbyWifiMath.security("[WPA2-PSK-CCMP][WPS][ESS]")
        assertTrue(wps.wpsAdvertised); assertEquals("WPA2 / RSN anunciado", wps.label)
        val noWps = NearbyWifiMath.security("[WPA2-PSK-CCMP][ESS]")
        assertFalse(noWps.wpsAdvertised); assertNull(noWps.concern)
    }
    @Test fun openAndLegacyModesHaveConfigurationRecommendations() {
        assertEquals("Rede aberta anunciada", NearbyWifiMath.security("[ESS]").label)
        assertEquals("WEP anunciado", NearbyWifiMath.security("[WEP][ESS]").label)
        assertNotNull(NearbyWifiMath.security("[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]").concern)
        assertFalse(NearbyWifiMath.security(null).label.contains("aberta"))
        assertEquals("Proteção não identificada", NearbyWifiMath.security("[WAPI-PSK-SMS4][ESS]").label)
    }
    @Test fun wpa3AndOweAreNotMisreportedAsOpen() {
        assertEquals("WPA3 anunciado", NearbyWifiMath.security("[RSN-SAE-CCMP][ESS]").label)
        assertEquals("WPA2 / WPA3 anunciado", NearbyWifiMath.security("[RSN-PSK+SAE-CCMP][ESS]").label)
        assertEquals("OWE anunciado", NearbyWifiMath.security("[RSN-OWE-CCMP][ESS]").label)
        assertNotNull(NearbyWifiMath.security("[OWE_TRANSITION][ESS]").concern)
    }
    @Test fun ouiLookupRejectsLocalMulticastAndMissingMacs() {
        assertEquals("102030", MacVendorMath.prefix("10:20:30:40:50:60"))
        assertTrue(MacVendorMath.isLocal("02:AA:BB:CC:DD:EE"))
        assertNull(MacVendorMath.prefix("02:AA:BB:CC:DD:EE"))
        assertNull(MacVendorMath.prefix("01:AA:BB:CC:DD:EE"))
        assertNull(MacVendorMath.prefix("00:00:00:00:00:00")); assertNull(MacVendorMath.prefix("Oculto")); assertNull(MacVendorMath.prefix(null))
    }
    @Test fun localRegistryParsesCompanyCommasWithoutNetworkAccess() {
        val registry = MacVendorMath.parseCsv("prefix,company\n# synthetic fixture\n102030,\"Fabricante fictício, laboratório\"\nBAD,Ignorar")
        assertEquals(mapOf("102030" to "Fabricante fictício, laboratório"), registry)
    }
    @Test fun scanAgeUsesMonotonicMicrosecondsRatherThanWallClock() {
        assertEquals(4L, NearbyWifiMath.ageSeconds(10_000, 6_000_000))
        assertNull(NearbyWifiMath.ageSeconds(10_000, null)); assertNull(NearbyWifiMath.ageSeconds(10_000, 0))
        assertNull(NearbyWifiMath.ageSeconds(10_000, 11_000_000))
    }
}
