package top.maler.motogps.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayConfigurationTest {
    private class MemoryStore : KeyValueStore {
        val values = HashMap<String, String>()
        override fun getString(key: String) = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    @Test
    fun normalizesHostAndTrailingSlashWithoutDroppingPrefix() {
        assertEquals("https://nav.example.com/moto-gps/api/", GatewayConfiguration.normalizedUrl("  https://NAV.example.com/moto-gps/api \n"))
        assertEquals("https://gateway.example.com/", GatewayConfiguration.normalizedUrl("https://gateway.example.com"))
        assertEquals("https://gateway.example.com:8443/custom/", GatewayConfiguration.normalizedUrl("https://gateway.example.com:8443/custom/"))
    }

    @Test
    fun rejectsUnsafeAmbiguousAndEndpointAddresses() {
        for (address in listOf(
            "", "http://nav.example.com", "nav.example.com", "https://", "https://example.invalid/",
            "https://u:p@nav.example.com/", "https://nav.example.com/?key=secret", "https://nav.example.com/#x",
            "https://nav.example.com/a b", "https://nav.example.com/../api", "https://nav.example.com/%2e%2e/api",
            "https://nav.example.com:99999", "https://nav.example.com/healthz", "https://nav.example.com/v1/routes",
        )) {
            assertThrows(address, GatewayAddressException::class.java) { GatewayConfiguration.normalizedUrl(address) }
            assertFalse(address, GatewayConfiguration.isValid(address))
        }
    }

    @Test
    fun savedAddressSurvivesReloadAndInvalidSaveDoesNotReplaceIt() {
        val store = MemoryStore()
        val saved = GatewayConfiguration.save("https://own.example.com/api", store)
        assertEquals(saved, GatewayConfiguration.resolvedUrl(store, "https://other.example.com"))
        assertThrows(GatewayAddressException::class.java) { GatewayConfiguration.save("http://bad.example.com", store) }
        assertEquals(saved, GatewayConfiguration.resolvedUrl(store, null))
    }

    @Test
    fun cleanInstallUsesValidBuildDefaultButNeverPlaceholder() {
        val store = MemoryStore()
        assertNull(GatewayConfiguration.resolvedUrl(store, "https://example.invalid/moto-gps/api/"))
        assertNull(GatewayConfiguration.resolvedUrl(store, ""))
        store.values[GatewayConfiguration.PREFERENCES_KEY] = "not a URL"
        assertEquals("https://nav.example.com/api/", GatewayConfiguration.resolvedUrl(store, "https://nav.example.com/api"))
    }

    @Test
    fun endpointsJoinBelowTheConfiguredPrefix() {
        assertEquals(
            "https://nav.example.com/moto-gps/api/v1/routes",
            GatewayConfiguration.endpoint("https://nav.example.com/moto-gps/api/", "v1/routes"),
        )
    }
}
