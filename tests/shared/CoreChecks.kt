package ru.nikmadz.mikrotikcontrol.tests

import ru.nikmadz.mikrotikcontrol.core.*
import ru.nikmadz.mikrotikcontrol.net.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

object CoreChecks {
    private fun profile() = RouterProfile("p1", "Test", "192.168.88.1", "app_control", "test-only-not-a-real-password")
    private fun rule(id: String = "*1", comment: String = "APP_STOP_TEST", disabled: Boolean = true,
                     table: FirewallTable = FirewallTable.FILTER) =
        FirewallRule(id, table, comment, disabled, "drop", "forward")
    private fun group(rules: List<FirewallRule>) = FirewallGroups.assemble(rules).first { it.name == "TEST" }
    private fun failure(action: () -> Unit): Exception {
        try { action() } catch (e: Exception) { return e }
        error("Expected an exception")
    }

    private class FakeBackend(initial: List<FirewallRule>) : RouterBackend {
        var rules = initial
        var version = "7.20.1 (stable)"
        var reads = 0
        var failRead = -1
        var failWrite = -1
        var applyFailedWrite = false
        var ignoreWrites = false
        var beforeRead: ((Int) -> Unit)? = null
        val writes = mutableListOf<Pair<String, Boolean>>()
        override fun version() = version
        override fun readRules(): List<FirewallRule> {
            reads++
            beforeRead?.invoke(reads)
            if (reads == failRead) throw IOException("Read failed")
            return rules.toList()
        }
        override fun setEnabled(rule: FirewallRule, enabled: Boolean) {
            writes += rule.key to enabled
            val fails = writes.size == failWrite
            if (!ignoreWrites && (!fails || applyFailedWrite)) {
                rules = rules.map { if (it.key == rule.key) it.copy(disabled = !enabled) else it }
            }
            if (fails) throw IOException("Write response lost")
        }
    }

    private class FakeHttps(url: URL, private val code: Int = 200, private val text: String = "[]") : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var closed = false
        var timeout = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getCipherSuite(): String = "FAKE_FOR_UNIT_TEST_ONLY"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { if (timeout) throw SocketTimeoutException(); return code }
        override fun getInputStream() = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        override fun getOutputStream() = sent
    }

    fun runAll(): Int {
        var count = 0
        fun test(name: String, action: () -> Unit) {
            action(); count++; println("PASS $count: $name")
        }
        test("new profiles default to direct Wi-Fi mode") {
            check(profile().connectionRoute == ConnectionRoute.WIFI)
        }
        test("Ethernet selected even when the system defaults to cellular") {
            val candidates = listOf(
                DirectNetworkCandidate(1, ethernet=false, wifi=false, vpn=false, cellular=true),
                DirectNetworkCandidate(2, ethernet=true, wifi=false, vpn=false, cellular=false)
            )
            check(DirectRouteSelector.choose(ConnectionRoute.ETHERNET, candidates, activeId=1) == 2L)
        }
        test("Ethernet preferred explicitly even with Wi-Fi available") {
            val candidates = listOf(
                DirectNetworkCandidate(10, ethernet=false, wifi=true, vpn=false, cellular=false),
                DirectNetworkCandidate(11, ethernet=true, wifi=false, vpn=false, cellular=false)
            )
            check(DirectRouteSelector.choose(ConnectionRoute.ETHERNET, candidates, activeId=10) == 11L)
        }
        test("Wi-Fi route finds direct Wi-Fi and rejects cellular fallback") {
            val cellular = DirectNetworkCandidate(1,false,false,false,true)
            val wifi = DirectNetworkCandidate(2,false,true,false,false)
            check(DirectRouteSelector.choose(ConnectionRoute.WIFI, listOf(cellular,wifi), 1) == 2L)
            check(DirectRouteSelector.choose(ConnectionRoute.WIFI, listOf(cellular), 1) == null)
        }
        test("VPN transport never masquerades as a direct LAN") {
            val vpnEthernet = DirectNetworkCandidate(1,true,false,true,false)
            val vpnWifi = DirectNetworkCandidate(2,false,true,true,false)
            check(DirectRouteSelector.choose(ConnectionRoute.ETHERNET,listOf(vpnEthernet),1) == null)
            check(DirectRouteSelector.choose(ConnectionRoute.WIFI,listOf(vpnWifi),2) == null)
        }
        test("ambiguous wired interfaces fail unless active one matches") {
            val eth1 = DirectNetworkCandidate(101,true,false,false,false)
            val eth2 = DirectNetworkCandidate(202,true,false,false,false)
            val both = listOf(eth1,eth2)
            check(DirectRouteSelector.choose(ConnectionRoute.ETHERNET,both,activeId=101) == 101L)
            check(DirectRouteSelector.choose(ConnectionRoute.ETHERNET,both,activeId=909) == null)
        }
        test("system or VPN mode does not override Android routing") {
            val ethernet = DirectNetworkCandidate(7,true,false,false,false)
            check(DirectRouteSelector.choose(ConnectionRoute.SYSTEM,listOf(ethernet),activeId=7) == null)
        }
        test("tag prefix, case, whitespace and description") {
            check(FirewallGroups.nameOf("APP_STOP_VPN") == "VPN")
            check(FirewallGroups.nameOf(" app_stop_user1 | Описание ") == "USER1")
            check(FirewallGroups.nameOf("APP_STOP_CAMERA-2") == "CAMERA-2")
        }
        test("unrelated, malformed and oversized comments ignored") {
            for (s in listOf("OTHER_APP_STOP_VPN", "APP_STOP_", "APP_STOP_VPN text", "APP_STOP_A.B", "APP_STOP_" + "X".repeat(65))) {
                check(FirewallGroups.nameOf(s) == null) { s }
            }
        }
        test("USER1 and USER10 are different groups") {
            check(FirewallGroups.assemble(listOf(rule(comment="APP_STOP_USER1"),rule("*2","APP_STOP_USER10"))).size == 2)
        }
        test("different tables form one group") {
            val g = group(listOf(rule(), rule(table=FirewallTable.NAT)))
            check(g.rules.size == 2)
        }
        test("group states and counts") {
            check(group(listOf(rule())).state == GroupState.ALL_DISABLED)
            check(group(listOf(rule(disabled=false))).state == GroupState.ALL_ENABLED)
            check(group(listOf(rule(), rule("*2",disabled=false))).state == GroupState.MIXED)
            check(group(listOf(rule(), rule("*2",disabled=false))).enabledCount == 1)
        }
        test("empty group rejected") { failure { FirewallGroup("X", emptyList()) } }
        test("REST IDs cannot inject a path or multiple IDs") {
            check(FirewallGroups.safeId("*1Af"))
            for (id in listOf("*1,*2", "*1/../../", "*", "1", "*G")) check(!FirewallGroups.safeId(id))
        }
        test("valid IPv4, DNS and IPv6 hosts") {
            for (host in listOf("192.168.88.1", "router.lan", "router", "fd00::1")) check(ProfileValidation.validHost(host))
        }
        test("URLs, credentials, ports and bad addresses are rejected as host") {
            for (host in listOf("https://r", "r/x", "a@b", "r:443", "999.1.2.3", "-router", "a..b", "r\n", "")) {
                check(!ProfileValidation.validHost(host)) { host }
            }
        }
        test("profile validates port, login and password") {
            ProfileValidation.requireValid(profile())
            failure { ProfileValidation.requireValid(profile().copy(restPort=0)) }
            failure { ProfileValidation.requireValid(profile().copy(restPort=65536)) }
            failure { ProfileValidation.requireValid(profile().copy(username="bad:user")) }
            failure { ProfileValidation.requireValid(profile().copy(password="")) }
        }
        test("password omitted from profile diagnostics") { check(profile().password !in profile().toString()) }
        test("RouterOS 7 stable and prerelease accepted") {
            for (v in listOf("7.1", "7.20.1 (stable)", "7.21rc1", "7.22beta2 (testing)")) RouterOs.requireVersion7(v)
        }
        test("other RouterOS versions and unknown version rejected") {
            for (v in listOf("6.49.19", "8.1", "", "RouterOS 7", "70.1")) failure { RouterOs.requireVersion7(v) }
        }
        test("scan reports actual RouterOS version and groups") {
            val f = FakeBackend(listOf(rule(), rule("*2", "UNRELATED")))
            val result = RouterFirewallService { f }.scan(profile())
            check(result.groups.single().rules.size == 1 && result.version == f.version)
            check(f.writes.isEmpty())
        }
        test("wrong firmware is rejected before reading or writing rules") {
            val f = FakeBackend(listOf(rule())).apply { version = "6.49.19" }
            failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
            check(f.reads == 0 && f.writes.isEmpty())
        }
        test("enable all group rules; unrelated rules untouched") {
            val f = FakeBackend(listOf(rule(), rule("*2"), rule("*3", "OTHER")))
            val result = RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true)
            check(result.changed == 2 && result.state == GroupState.ALL_ENABLED && f.reads == 2)
            check(f.rules.last().disabled)
        }
        test("disable all group rules") {
            val f = FakeBackend(listOf(rule(disabled=false), rule("*2", disabled=false)))
            val result = RouterFirewallService { f }.switchGroup(profile(),group(f.rules),false) { true }
            check(result.changed == 2 && f.rules.all { it.disabled })
        }
        test("already desired state does not issue writes") {
            val f = FakeBackend(listOf(rule(disabled=false)))
            check(RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true).changed == 0)
            check(f.writes.isEmpty() && f.reads == 2)
        }
        test("mixed group changes only necessary rules") {
            val f = FakeBackend(listOf(rule(), rule("*2",disabled=false)))
            check(RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true).changed == 1)
        }
        test("same numeric ID in different tables remains independent") {
            val f = FakeBackend(listOf(rule(), rule(table=FirewallTable.RAW)))
            check(RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true).changed == 2)
        }
        test("group membership changed since confirmation prevents writes") {
            val expected = group(listOf(rule()))
            val f = FakeBackend(listOf(rule(), rule("*2")))
            failure { RouterFirewallService { f }.switchGroup(profile(),expected,true) }
            check(f.writes.isEmpty())
        }
        test("changed action since confirmation prevents writes") {
            val expected = group(listOf(rule()))
            val f = FakeBackend(listOf(rule().copy(action="accept")))
            failure { RouterFirewallService { f }.switchGroup(profile(),expected,true) }
            check(f.writes.isEmpty())
        }
        test("dynamic rule prevents every write in group") {
            val f = FakeBackend(listOf(rule(), rule("*2").copy(dynamic=true)))
            failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
            check(f.writes.isEmpty())
        }
        test("invalid rule cannot be enabled") {
            val f = FakeBackend(listOf(rule(), rule("*2").copy(invalid=true)))
            failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
            check(f.writes.isEmpty())
        }
        test("invalid static rule can be disabled") {
            val f = FakeBackend(listOf(rule(disabled=false).copy(invalid=true)))
            check(RouterFirewallService { f }.switchGroup(profile(),group(f.rules),false) { true }.changed == 1)
        }
        test("bad ID detected before any writes") {
            val f = FakeBackend(listOf(rule(), rule("*2,*3")))
            failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
            check(f.writes.isEmpty())
        }
        test("duplicate table/ID rejected") {
            val f = FakeBackend(listOf(rule(), rule()))
            failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
            check(f.writes.isEmpty())
        }
        test("group missing on preflight prevents writes") {
            val f = FakeBackend(emptyList())
            failure { RouterFirewallService { f }.switchGroup(profile(),group(listOf(rule())),true) }
            check(f.writes.isEmpty())
        }
        test("read failure prevents writes") {
            val f = FakeBackend(listOf(rule())).apply { failRead = 1 }
            failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
            check(f.writes.isEmpty())
        }
        test("first write failure stops remaining writes without rollback") {
            val f = FakeBackend(listOf(rule(),rule("*2"),rule("*3"))).apply { failWrite = 2 }
            val e = failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
            check(f.writes.size == 2 && !f.rules[0].disabled && f.rules[1].disabled && f.rules[2].disabled)
            check(e.message.orEmpty().contains("1 из 3"))
        }
        test("lost write response is never treated as success") {
            val f = FakeBackend(listOf(rule())).apply { failWrite = 1; applyFailedWrite = true }
            failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
            check(!f.rules.single().disabled && f.writes.size == 1)
        }
        test("failed final read does not report success") {
            val f = FakeBackend(listOf(rule())).apply { failRead = 2 }
            failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
            check(f.writes.size == 1)
        }
        test("ignored write detected by readback") {
            val f = FakeBackend(listOf(rule())).apply { ignoreWrites = true }
            failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
        }
        test("replacement ID with equal rule count detected on readback") {
            val f = FakeBackend(listOf(rule()))
            f.beforeRead = { n -> if (n == 2) f.rules = listOf(rule("*9",disabled=false)) }
            failure { RouterFirewallService { f }.switchGroup(profile(),group(f.rules),true) }
        }
        test("HTTPS URL, Basic authentication, finite timeouts and no redirects") {
            lateinit var conn: FakeHttps
            val transport = HttpRestTransport(profile()) { url -> FakeHttps(url).also { conn = it } }
            check(transport.request("GET", "system/resource", null) == "[]")
            check(conn.url.toString() == "https://192.168.88.1:443/rest/system/resource")
            check(conn.getRequestProperty("Authorization").startsWith("Basic "))
            check(conn.connectTimeout > 0 && conn.readTimeout > 0 && !conn.instanceFollowRedirects && conn.closed)
        }
        test("IPv6 URL is bracketed with custom HTTPS port") {
            lateinit var conn: FakeHttps
            HttpRestTransport(profile().copy(host="fd00::1", restPort=8443)) { url -> FakeHttps(url).also { conn=it } }
                .request("GET", "system/resource", null)
            check(conn.url.toString() == "https://[fd00::1]:8443/rest/system/resource")
        }
        test("POST sends JSON body verbatim") {
            lateinit var conn: FakeHttps
            val body = "{\".id\":\"*1\",\"disabled\":\"false\"}"
            HttpRestTransport(profile()) { url -> FakeHttps(url).also { conn=it } }
                .request("POST", "ip/firewall/filter/set", body)
            check(conn.requestMethod == "POST" && conn.sent.toString("UTF-8") == body && conn.closed)
        }
        test("redirects and API errors fail without disclosing server body") {
            for (code in listOf(301, 401, 403, 404, 500)) {
                val e = failure {
                    HttpRestTransport(profile()) { url -> FakeHttps(url, code, "secret-server-body") }
                        .request("GET", "system/resource", null)
                }
                check(e.message.orEmpty().contains(code.toString()) && !e.message.orEmpty().contains("secret-server-body"))
            }
        }
        test("timeout closes connection and returns actionable error") {
            lateinit var conn: FakeHttps
            val e = failure {
                HttpRestTransport(profile()) { url -> FakeHttps(url).also { conn=it; it.timeout=true } }
                    .request("GET", "system/resource", null)
            }
            check(conn.closed && e.message.orEmpty().contains("не ответил вовремя"))
        }
        test("response size is bounded") {
            failure {
                HttpRestTransport(profile()) { url -> FakeHttps(url,200,"x".repeat(4*1024*1024+1)) }
                    .request("GET", "system/resource", null)
            }
        }
        test("transport refuses unsafe paths and HTTP verbs") {
            val t = HttpRestTransport(profile()) { error("Must not open connection") }
            failure { t.request("DELETE", "system/resource", null) }
            failure { t.request("GET", "../x", null) }
            failure { t.request("GET", "/system/resource", null) }
        }
        println("TOTAL: $count offline tests passed")
        return count
    }
}
