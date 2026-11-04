package ru.nikmadz.mikrotikcontrol

import org.junit.Assert.*
import org.junit.Test
import ru.nikmadz.mikrotikcontrol.core.*
import ru.nikmadz.mikrotikcontrol.net.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RuleAccessTest {
    private val profile = RouterProfile("id", "Test", "router", "fixture", "fixture-only")
    private fun rule(id: String = "*1", disabled: Boolean = false) = FirewallRule(id, FirewallTable.FILTER, "APP_STOP_TEST", disabled, "drop", "forward")
    private class Backend(var rules: List<FirewallRule>, val afterWrite: () -> Unit = {}) : RouterBackend {
        var writes = 0
        override fun version() = "7.23.8"
        override fun readRules() = rules.toList()
        override fun setEnabled(rule: FirewallRule, enabled: Boolean) {
            writes++
            rules = rules.map { if (it.key == rule.key) it.copy(disabled = !enabled) else it }
            afterWrite()
        }
    }
    private fun reject(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        fail("Expected denial")
    }

    @Test fun operatorCannotDisableEvenWithoutUiOrViaMixedGroup() {
        for (rules in listOf(listOf(rule()), listOf(rule(), rule("*2", true)))) {
            var opens = 0
            val service = RouterFirewallService { opens++; Backend(rules) }
            reject { service.switchGroup(profile, FirewallGroup("TEST", rules), false) }
            assertEquals(0, opens)
        }
    }

    @Test fun operatorCanEnableDisabledOrMixedGroups() {
        for (rules in listOf(listOf(rule(disabled = true)), listOf(rule(), rule("*2", true)))) {
            val backend = Backend(rules)
            val result = RouterFirewallService { backend }.switchGroup(profile, FirewallGroup("TEST", rules), true)
            assertEquals(GroupState.ALL_ENABLED, result.state)
            assertEquals(1, backend.writes)
        }
    }

    @Test fun authenticatedAdministratorCanDisableWithReadback() {
        val backend = Backend(listOf(rule(), rule("*2")))
        val group = FirewallGroup("TEST", backend.rules)
        val result = RouterFirewallService { backend }.switchGroup(profile, group, false) { true }
        assertEquals(2, backend.writes)
        assertEquals(GroupState.ALL_DISABLED, result.state)
    }

    @Test fun revocationBetweenRulesStopsFurtherWritesAndReportsPartialState() {
        var granted = true
        val backend = Backend(listOf(rule(), rule("*2"))) { granted = false }
        val group = FirewallGroup("TEST", backend.rules)
        reject { RouterFirewallService { backend }.switchGroup(profile, group, false) { granted } }
        assertEquals(1, backend.writes)
        assertEquals(listOf(true, false), backend.rules.map { it.disabled })
    }

    @Test fun httpRevocationBeforeOpenSendsNothing() {
        var opened = false
        val transport = HttpRestTransport(profile, { throw IOException("Access revoked") }) { opened = true; throw AssertionError() }
        reject { transport.request("POST", "ip/firewall/filter/set", "{}") }
        assertFalse(opened)
    }

    @Test fun httpRevocationAfterConnectSendsNoCommandBody() {
        var granted = true
        val bytes = ByteArrayOutputStream()
        val connection = object : HttpURLConnection(URL("http://router")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream(): ByteArrayOutputStream { granted = false; return bytes }
        }
        val transport = HttpRestTransport(profile.copy(protocol = RestProtocol.HTTP), {
            if (!granted) throw IOException("Access revoked")
        }) { connection }
        reject { transport.request("POST", "ip/firewall/filter/set", "{\"disabled\":\"true\"}") }
        assertEquals(0, bytes.size())
    }
}
