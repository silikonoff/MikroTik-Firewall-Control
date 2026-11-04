package ru.nikmadz.mikrotikcontrol

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import ru.nikmadz.mikrotikcontrol.core.*
import ru.nikmadz.mikrotikcontrol.net.RouterBackend

class ReadbackTest {
    private val profile = RouterProfile("p", "Fixture", "192.168.88.1", "u", "fixture-only")
    private val rule = FirewallRule("*A", FirewallTable.FILTER, "APP_STOP_TEST", true, "drop", "forward")

    private class Scripted(private val before: FirewallRule, private val after: (Int) -> FirewallRule) : RouterBackend {
        var reads = 0
        var writes = 0
        override fun version() = "7.23.8"
        override fun readRules(): List<FirewallRule> {
            reads++
            return listOf(if (reads == 1) before else after(reads - 1))
        }
        override fun setEnabled(rule: FirewallRule, enabled: Boolean) { writes++ }
    }

    private fun failure(block: () -> Unit) {
        try { block(); fail("Expected verification failure") } catch (_: IOException) { }
    }

    @Test fun transientInvalidAfterEnableIsReadAgainWithoutRewriting() {
        val b = Scripted(rule) { n -> rule.copy(disabled = false, invalid = n == 1) }
        val result = RouterFirewallService { b }.switchGroup(profile, FirewallGroup("TEST", listOf(rule)), true)
        assertEquals(GroupState.ALL_ENABLED, result.state)
        assertEquals(1, b.writes)
        assertEquals(3, b.reads)
    }

    @Test fun disablingMayClearInvalidFlag() {
        val before = rule.copy(disabled = false, invalid = true)
        val b = Scripted(before) { rule.copy(disabled = true, invalid = false) }
        val result = RouterFirewallService { b }.switchGroup(profile, FirewallGroup("TEST", listOf(before)), false) { true }
        assertEquals(GroupState.ALL_DISABLED, result.state)
        assertEquals(1, b.writes)
    }

    @Test fun brieflyStaleDisabledReadIsPolledWithoutAnotherWrite() {
        val b = Scripted(rule) { n -> rule.copy(disabled = n == 1) }
        val result = RouterFirewallService { b }.switchGroup(profile, FirewallGroup("TEST", listOf(rule)), true)
        assertEquals(GroupState.ALL_ENABLED, result.state)
        assertEquals(1, b.writes)
        assertEquals(3, b.reads)
    }

    @Test fun permanentlyInvalidEnabledRuleDoesNotReportSuccess() {
        val b = Scripted(rule) { rule.copy(disabled = false, invalid = true) }
        failure { RouterFirewallService { b }.switchGroup(profile, FirewallGroup("TEST", listOf(rule)), true) }
        assertEquals(1, b.writes)
        assertTrue(b.reads in 2..5)
    }

    @Test fun changedActionStillFailsImmediately() {
        val b = Scripted(rule) { rule.copy(disabled = false, action = "accept") }
        failure { RouterFirewallService { b }.switchGroup(profile, FirewallGroup("TEST", listOf(rule)), true) }
        assertEquals(2, b.reads)
        assertEquals(1, b.writes)
    }

    @Test fun dynamicStatusChangeStillFailsImmediately() {
        val b = Scripted(rule) { rule.copy(disabled = false, dynamic = true) }
        failure { RouterFirewallService { b }.switchGroup(profile, FirewallGroup("TEST", listOf(rule)), true) }
        assertEquals(2, b.reads)
        assertEquals(1, b.writes)
    }
}
