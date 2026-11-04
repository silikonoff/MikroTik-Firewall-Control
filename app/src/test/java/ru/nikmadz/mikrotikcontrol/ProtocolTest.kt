package ru.nikmadz.mikrotikcontrol

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.io.Closeable
import java.net.URL
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test
import ru.nikmadz.mikrotikcontrol.core.*
import ru.nikmadz.mikrotikcontrol.net.HttpRestTransport
import ru.nikmadz.mikrotikcontrol.security.ProfileJson

class ProtocolTest {
    private data class Reply(val code: Int = 200, val body: String = "", val location: String? = null)

    /** Real loopback HTTP fixture using only the java.net APIs available to Android unit compilation. */
    private class LocalServer(private val reply: (String, Map<String, String>) -> Reply) : Closeable {
        private val socket = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val port: Int get() = socket.localPort
        private val failure = AtomicReference<Throwable>()
        private val worker = kotlin.concurrent.thread(isDaemon = true) {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { client ->
                        client.soTimeout = 3000
                        val reader = client.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val response = reply(path, headers)
                        val body = response.body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 ${response.code} Fixture\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" +
                            (response.location?.let { "Location: $it\r\n" } ?: "") + "\r\n"
                        client.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                    }
                }
            } catch (e: SocketException) { if (!socket.isClosed) failure.set(e) }
            catch (e: Throwable) { failure.set(e) }
        }
        override fun close() {
            socket.close(); worker.join(4000)
            check(!worker.isAlive) { "HTTP fixture did not terminate" }
            failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
        }
    }
    private fun profile() = RouterProfile("id", "Test", "127.0.0.1", "fixture", "fixture-only")

    @Test fun oldProfilesStayHttpsWithoutDowngrade() {
        val old = """[{"id":"id","name":"Test","host":"192.168.88.1","username":"u","password":"fixture","restPort":8443}]"""
        val p = ProfileJson.decode(old).single()
        assertEquals(RestProtocol.HTTPS, p.protocol)
        assertEquals(8443, p.restPort)
    }

    @Test fun bothProtocolsSurviveSaveAndReload() {
        for (protocol in RestProtocol.values()) {
            val p = profile().copy(protocol = protocol, restPort = protocol.defaultPort)
            assertEquals(listOf(p), ProfileJson.decode(ProfileJson.encode(listOf(p))))
        }
    }

    @Test fun httpProfileWithoutPortUses80() {
        val json = """[{"id":"id","name":"Test","host":"192.168.88.1","username":"u","password":"fixture","protocol":"HTTP","connectionRoute":"ETHERNET"}]"""
        assertEquals(80, ProfileJson.decode(json).single().restPort)
    }

    @Test(expected = IllegalArgumentException::class) fun unknownProtocolFailsClosed() {
        ProfileJson.decode(ProfileJson.encode(listOf(profile())).replace("HTTPS", "AUTO"))
    }

    @Test fun bothProtocolsAreValidOnEveryRouteAndSurviveReload() {
        for (route in ConnectionRoute.values()) for (protocol in RestProtocol.values()) {
            val p = profile().copy(protocol = protocol, restPort = protocol.defaultPort, connectionRoute = route)
            ProfileValidation.requireValid(p)
            assertEquals(p, ProfileJson.decode(ProfileJson.encode(listOf(p))).single())
        }
    }

    @Test fun newProfileDefaultsToWifiButLegacyRouteRemainsSystem() {
        assertEquals(ConnectionRoute.WIFI, profile().connectionRoute)
        val legacy = """[{"id":"id","name":"Test","host":"192.168.88.1","username":"u","password":"fixture"}]"""
        assertEquals(ConnectionRoute.SYSTEM, ProfileJson.decode(legacy).single().connectionRoute)
    }

    @Test fun httpWorksOverExplicitDirectRoutes() {
        for (route in listOf(ConnectionRoute.ETHERNET, ConnectionRoute.WIFI)) {
            ProfileValidation.requireValid(profile().copy(protocol = RestProtocol.HTTP, restPort = 80, connectionRoute = route))
        }
    }

    @Test fun realLocalHttpRestRoundTrip() {
        val authorization = AtomicReference<String>()
        LocalServer { path, headers ->
            assertEquals("/rest/system/resource", path)
            authorization.set(headers["authorization"])
            Reply(body = """{"version":"7.23.8"}""")
        }.use { server ->
            for (route in ConnectionRoute.values()) {
                val p = profile().copy(protocol = RestProtocol.HTTP, restPort = server.port, connectionRoute = route)
                assertEquals("""{"version":"7.23.8"}""", HttpRestTransport(p).request("GET", "system/resource", null))
            }
            assertTrue(authorization.get().orEmpty().startsWith("Basic "))
        }
    }

    @Test fun tlsFailureNeverRetriesHttp() {
        val opened = mutableListOf<URL>()
        try {
            HttpRestTransport(profile()) { url -> opened += url; throw SSLHandshakeException("fixture") }
                .request("GET", "system/resource", null)
            fail("Expected TLS failure")
        } catch (_: IOException) { }
        assertEquals(listOf("https"), opened.map { it.protocol })
    }

    @Test fun httpRedirectNeverReachesOtherEndpoint() {
        val targetRequests = AtomicInteger()
        LocalServer { path, _ ->
            if (path == "/rest/system/resource") Reply(302, location = "/other")
            else { targetRequests.incrementAndGet(); Reply() }
        }.use { server ->
            val p = profile().copy(protocol = RestProtocol.HTTP, restPort = server.port)
            try { HttpRestTransport(p).request("GET", "system/resource", null); fail("Expected redirect rejection") }
            catch (e: IOException) { assertTrue(e.message.orEmpty().contains("302")) }
            assertEquals(0, targetRequests.get())
        }
    }
}
