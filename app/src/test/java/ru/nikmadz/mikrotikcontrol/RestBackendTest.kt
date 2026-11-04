package ru.nikmadz.mikrotikcontrol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.nikmadz.mikrotikcontrol.core.*
import ru.nikmadz.mikrotikcontrol.net.*
import java.io.IOException

/** Run with :app:testDebugUnitTest; uses the JVM org.json library, no router required. */
class RestBackendTest {
    private class Scripted : RestTransport {
        data class Call(val method: String, val path: String, val body: String?)
        val calls = mutableListOf<Call>()
        val responses = mutableMapOf<String, String>()
        var failingPath: String? = null
        override fun request(method: String, path: String, body: String?): String {
            calls += Call(method,path,body)
            if (path == failingPath) throw IOException("Fixture failure")
            return responses[path] ?: "[]"
        }
    }
    private fun failure(block: () -> Unit) {
        try { block() } catch (e: Exception) { return }
        fail("An exception was expected")
    }
    private val rule = FirewallRule("*A", FirewallTable.FILTER, "APP_STOP_TEST", true, "drop", "forward")
    private val fixture = """[{".id":"*A","comment":"APP_STOP_TEST | first","disabled":"true","action":"drop","chain":"forward"}]"""

    @Test fun versionFromObject() {
        val t = Scripted().apply { responses["system/resource"] = """{"version":"7.20.1 (stable)"}""" }
        assertEquals("7.20.1 (stable)", RestBackend(t).version())
        assertEquals("GET", t.calls.single().method)
    }
    @Test fun versionFromSingleItemArray() {
        val t = Scripted().apply { responses["system/resource"] = """[{"version":"7.20.1"}]""" }
        assertEquals("7.20.1", RestBackend(t).version())
    }
    @Test fun invalidMissingAndUnsupportedVersionRejected() {
        for (response in listOf("[]", "{}", "null", "[{},{}]", """{"version":"6.49.19"}""")) {
            val t = Scripted().apply { responses["system/resource"] = response }
            failure { RestBackend(t).version() }
        }
    }
    @Test fun allFourTablesAreRead() {
        val t = Scripted().apply { responses["ip/firewall/filter"] = fixture }
        val r = RestBackend(t).readRules().single()
        assertEquals("*A",r.id); assertTrue(r.disabled); assertFalse(r.dynamic)
        assertEquals(FirewallTable.values().map { it.path }, t.calls.map { it.path })
    }
    @Test fun untaggedRecordsIgnored() {
        val t = Scripted().apply { responses["ip/firewall/filter"] = """[{"comment":"UNRELATED"}]""" }
        assertTrue(RestBackend(t).readRules().isEmpty())
    }
    @Test fun booleansAndYesNoAccepted() {
        for ((literal,expected) in listOf("true" to true, "false" to false, "\"yes\"" to true, "\"no\"" to false)) {
            val t = Scripted().apply { responses["ip/firewall/filter"] = fixture.replace("\"disabled\":\"true\"", "\"disabled\":$literal") }
            assertEquals(expected, RestBackend(t).readRules().single().disabled)
        }
    }
    @Test fun missingOrUnknownDisabledStateFailsClosed() {
        for (f in listOf(fixture.replace("\"disabled\":\"true\",", ""),fixture.replace("\"true\"","\"unknown\""))) {
            val t = Scripted().apply { responses["ip/firewall/filter"] = f }
            failure { RestBackend(t).readRules() }
        }
    }
    @Test fun oneUnreadableTableAbortsEntireScan() {
        val t = Scripted().apply { responses["ip/firewall/filter"] = fixture; failingPath="ip/firewall/nat" }
        failure { RestBackend(t).readRules() }
        assertEquals(2,t.calls.size)
    }
    @Test fun enablingUsesPostSetWithOnlyIdAndDisabled() {
        val t = Scripted()
        RestBackend(t).setEnabled(rule,true)
        val call = t.calls.single()
        assertEquals("POST",call.method); assertEquals("ip/firewall/filter/set",call.path)
        val body=JSONObject(call.body!!)
        assertEquals(2,body.length()); assertEquals("*A",body.getString(".id"))
        assertEquals("false",body.getString("disabled"))
    }
    @Test fun disablingUsesTrue() {
        val t=Scripted(); RestBackend(t).setEnabled(rule,false)
        assertEquals("true",JSONObject(t.calls.single().body!!).getString("disabled"))
    }
    @Test fun unsafeRuleRejectedWithoutHttpRequest() {
        val t=Scripted(); val b=RestBackend(t)
        failure { b.setEnabled(rule.copy(id="*A,*B"),true) }
        failure { b.setEnabled(rule.copy(comment="OTHER"),true) }
        failure { b.setEnabled(rule.copy(dynamic=true),false) }
        failure { b.setEnabled(rule.copy(invalid=true),true) }
        assertTrue(t.calls.isEmpty())
    }
}
