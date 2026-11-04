package ru.nikmadz.mikrotikcontrol

import org.junit.Assert.*
import org.junit.Test
import ru.nikmadz.mikrotikcontrol.core.ConnectionRoute
import ru.nikmadz.mikrotikcontrol.core.RouterProfile
import ru.nikmadz.mikrotikcontrol.security.ProfileJson

class ProfileJsonTest {
    @Test fun roundTripPreservesNewProfiles() {
        for (route in ConnectionRoute.values()) {
            val p = RouterProfile("id", "Test", "192.168.88.1", "app_control", "fixture-only", 8443, route)
            assertEquals(listOf(p), ProfileJson.decode(ProfileJson.encode(listOf(p))))
            assertFalse(ProfileJson.encode(listOf(p)).contains("wifiOnly"))
        }
    }
    @Test fun oldProfilesKeepIdentityCredentialsAndHttpsPort() {
        for (oldMode in listOf("AUTO", "ROS6", "ROS7")) {
            val old = """[{"id":"legacy","name":"Home","host":"192.168.88.1","username":"u","password":"fixture-only","mode":"$oldMode","restPort":8443,"apiPort":8729}]"""
            val p = ProfileJson.decode(old).single()
            assertEquals("legacy", p.id); assertEquals("fixture-only", p.password)
            assertEquals(8443, p.restPort); assertEquals(ConnectionRoute.SYSTEM, p.connectionRoute)
            val converted = ProfileJson.encode(listOf(p))
            assertFalse(converted.contains("apiPort")); assertFalse(converted.contains("\"mode\""))
        }
    }
    @Test fun wifiOnlyV02ProfilesKeepWifiRouting() {
        val old = """[{"id":"x","name":"x","host":"192.168.88.1","username":"u","password":"pw","wifiOnly":true}]"""
        val p = ProfileJson.decode(old).single()
        assertEquals(ConnectionRoute.WIFI, p.connectionRoute)
        val converted = ProfileJson.encode(listOf(p))
        assertFalse(converted.contains("wifiOnly"))
        assertEquals(ConnectionRoute.WIFI, ProfileJson.decode(converted).single().connectionRoute)
    }
    @Test fun v02ProfilesUsingVpnKeepSystemRoute() {
        val old = """[{"id":"x","name":"x","host":"192.168.88.1","username":"u","password":"pw","wifiOnly":false}]"""
        assertEquals(ConnectionRoute.SYSTEM, ProfileJson.decode(old).single().connectionRoute)
    }
    @Test fun unknownV03RouteIsNotSilentlyRoutedViaVpnOrCellular() {
        val old = """[{"id":"x","name":"x","host":"192.168.88.1","username":"u","password":"pw","connectionRoute":"UNKNOWN"}]"""
        try { ProfileJson.decode(old); fail("Expected exception") } catch (_: IllegalArgumentException) { }
    }
    @Test fun defaultHttpsPortForLegacyProfile() {
        val p = ProfileJson.decode("""[{"id":"x","name":"x","host":"router","username":"u","password":"fixture-only"}]""").single()
        assertEquals(443, p.restPort)
    }
    @Test fun emptyProfileListSupported() { assertTrue(ProfileJson.decode("[]").isEmpty()) }
}
