package ru.nikmadz.mikrotikcontrol

import org.junit.Assert.*
import org.junit.Test
import ru.nikmadz.mikrotikcontrol.core.*
import ru.nikmadz.mikrotikcontrol.net.*
import ru.nikmadz.mikrotikcontrol.security.ProfileJson
import java.io.*
import java.net.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

class ApiTest {
    private val profile = RouterProfile("fixture", "Test", "127.0.0.1", "fixture-user", "fixture-password", access = RouterAccess.API)
    private val rule = FirewallRule("*A", FirewallTable.FILTER, "APP_STOP_TEST | Тест", true, "drop", "forward")

    private fun failure(block: () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("Expected rejection")
    }

    /** Independent fixture framing, intentionally does not use production ApiWire. */
    private class Server(private val login: List<String> = listOf("!done"),
                         private val reply: (List<String>, OutputStream) -> Unit) : Closeable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val commands = Collections.synchronizedList(mutableListOf<List<String>>())
        val logins = Collections.synchronizedList(mutableListOf<List<String>>())
        private val error = AtomicReference<Throwable>()
        private val worker = kotlin.concurrent.thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = DataInputStream(socket.getInputStream())
                    val auth = receive(input) ?: return@use
                    logins += auth
                    send(socket.getOutputStream(), login)
                    val command = receive(input) ?: return@use
                    commands += command
                    reply(command, socket.getOutputStream())
                }
            } catch (e: SocketException) { if (!server.isClosed) error.set(e) }
            catch (e: Throwable) { error.set(e) }
        }
        override fun close() {
            server.close(); worker.join(4000)
            check(!worker.isAlive)
            error.get()?.let { throw AssertionError("API fixture failed", it) }
        }
        companion object {
            fun send(output: OutputStream, words: List<String>) {
                for (word in words) {
                    val bytes = word.toByteArray(Charsets.UTF_8)
                    val size = bytes.size
                    when {
                        size < 128 -> output.write(size)
                        size < 16384 -> { output.write(0x80 or (size shr 8)); output.write(size and 255) }
                        else -> { output.write(0xC0 or (size shr 16)); output.write((size shr 8) and 255); output.write(size and 255) }
                    }
                    output.write(bytes)
                }
                output.write(0); output.flush()
            }
            private fun receive(input: DataInputStream): List<String>? {
                val result = mutableListOf<String>()
                while (true) {
                    val first = input.read()
                    if (first < 0) return null
                    val size = when {
                        first < 128 -> first
                        first < 192 -> ((first and 63) shl 8) or input.readUnsignedByte()
                        else -> ((first and 31) shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                    }
                    if (size == 0) return result
                    val bytes = ByteArray(size); input.readFully(bytes)
                    result += String(bytes, Charsets.UTF_8)
                }
            }
        }
    }

    private fun backend(server: Server, guard: () -> Unit = {}) = ApiBackend(profile.copy(nativeApiPort = server.port), guard) {
        Socket().apply { connect(InetSocketAddress("127.0.0.1", server.port), 1000) }
    }

    private fun row(output: OutputStream, rule: FirewallRule) = Server.send(output, listOf("!re", "=.id=${rule.id}",
        "=comment=${rule.comment}", "=disabled=${rule.disabled}", "=action=${rule.action}", "=chain=${rule.chain}",
        "=dynamic=${rule.dynamic}", "=invalid=${rule.invalid}"))

    @Test fun lengthGoldenVectorsCoverAllFiveForms() {
        val cases = listOf(0L to "00", 127L to "7f", 128L to "8080", 16383L to "bfff", 16384L to "c04000",
            2097151L to "dfffff", 2097152L to "e0200000", 268435455L to "efffffff",
            268435456L to "f010000000", 4294967295L to "f0ffffffff")
        for ((length, hex) in cases) {
            val output = ByteArrayOutputStream(); ApiWire.writeLength(output, length)
            assertEquals(hex, output.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
            val input = output.toByteArray().inputStream()
            assertEquals(length, ApiWire.readLength { input.read() })
        }
    }

    @Test fun unicodeEqualsAndFragmentedReadsPreserveWords() {
        val words = listOf("!re", "=comment=APP_STOP_VPN | Тест=значение " + "я".repeat(100))
        val output = ByteArrayOutputStream(); Server.send(output, words)
        val fragmented = object : ByteArrayInputStream(output.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        assertEquals(words, ApiWire(fragmented, ByteArrayOutputStream()).readSentence())
    }

    @Test fun rejectsReservedOversizedTruncatedAndMalformedUtf8() {
        for (bytes in listOf(byteArrayOf(0xF8.toByte()), byteArrayOf(0xF0.toByte(), 0x7f, -1, -1, -1),
            byteArrayOf(3, 65), byteArrayOf(1, 0xFF.toByte(), 0))) {
            assertTrue(failure { ApiWire(bytes.inputStream(), ByteArrayOutputStream()).readSentence() } is IOException)
        }
    }

    @Test fun versionLoginAndClosedSessionUseExpectedProtocol() {
        Server { command, output ->
            assertEquals(listOf("/system/resource/print", "=.proplist=version"), command)
            Server.send(output, listOf("!re", "=version=7.23.8 (stable)")); Server.send(output, listOf("!done"))
        }.use { server ->
            assertEquals("7.23.8 (stable)", backend(server).version())
            assertEquals(listOf("/login", "=name=fixture-user", "=password=fixture-password"), server.logins.single())
            assertEquals(1, server.commands.size)
        }
    }

    @Test fun failedLoginOrLegacyChallengeNeverSendsCommandOrLeaksPassword() {
        for (login in listOf(listOf("!trap", "=message=fixture-password"), listOf("!fatal"), listOf("!done", "=ret=oldchallenge"))) {
            Server(login) { _, _ -> fail("No command after failed login") }.use { server ->
                val error = failure { backend(server).version() }
                assertFalse(error.message.orEmpty().contains(profile.password))
                assertTrue(server.commands.isEmpty())
            }
        }
    }

    @Test fun rejectsRouterOs6BeforeAnyFirewallReadOrWrite() {
        Server { _, output -> Server.send(output, listOf("!re", "=version=6.49.19")); Server.send(output, listOf("!done")) }.use { server ->
            failure { RouterFirewallService { backend(server) }.scan(profile) }
            assertEquals(1, server.commands.size)
        }
    }

    @Test fun scansAllTablesAndPreservesFlagsAndUnicode() {
        Server { command, output ->
            assertTrue(command[1].contains(".id,comment,disabled,action,chain,dynamic,invalid"))
            row(output, rule.copy(disabled = false, dynamic = true, invalid = true))
            row(output, rule.copy(comment = "unmanaged"))
            Server.send(output, listOf("!done"))
        }.use { server ->
            val rules = backend(server).readRules()
            assertEquals(4, rules.size)
            assertEquals(FirewallTable.values().toSet(), rules.map { it.table }.toSet())
            assertTrue(rules.all { !it.disabled && it.dynamic && it.invalid && it.comment == rule.comment })
        }
    }

    @Test fun laterTableFailureRejectsWholeScan() {
        Server { command, output ->
            if (command.first().contains("/raw/")) Server.send(output, listOf("!trap", "=message=not permitted"))
            else { row(output, rule); Server.send(output, listOf("!done")) }
        }.use { server ->
            failure { backend(server).readRules() }
            assertEquals(4, server.commands.size)
        }
    }

    @Test fun missingOrUnknownStateIsNotGuessed() {
        for (state in listOf(emptyList(), listOf("=disabled=unknown"), listOf("=disabled=false", "=disabled=true"))) {
            Server { _, output ->
                Server.send(output, listOf("!re", "=.id=*1", "=comment=APP_STOP_TEST", "=action=drop", "=chain=forward") + state)
                Server.send(output, listOf("!done"))
            }.use { server -> failure { backend(server).readRules() } }
        }
    }

    @Test fun explicitSetWorksInBothDirectionsAndAllTables() {
        Server { _, output -> Server.send(output, listOf("!empty")); Server.send(output, listOf("!done")) }.use { server ->
            for (table in FirewallTable.values()) for (enabled in listOf(true, false)) {
                backend(server).setEnabled(rule.copy(table = table), enabled)
                assertEquals(listOf("/${table.path}/set", "=.id=*A", "=disabled=${!enabled}"), server.commands.last())
            }
            assertEquals(8, server.commands.size)
        }
    }

    @Test fun unsafeRulesNeverOpenSocket() {
        val backend = ApiBackend(profile) { fail("No network for forbidden rule"); Socket() }
        for (bad in listOf(rule.copy(id = "*A/set"), rule.copy(dynamic = true), rule.copy(invalid = true), rule.copy(comment = "other"))) {
            failure { backend.setEnabled(bad, true) }
        }
    }

    @Test fun trapFatalOrLostAckNeverRetriesWrite() {
        for (response in listOf(listOf("!trap", "=message=fixture-password"), listOf("!fatal"), emptyList())) {
            Server { _, output -> if (response.isNotEmpty()) Server.send(output, response) }.use { server ->
                val error = failure { backend(server).setEnabled(rule, true) }
                assertFalse(error.message.orEmpty().contains(profile.password))
                assertEquals(1, server.commands.size)
            }
        }
    }

    @Test fun revokedGrantAfterLoginSendsNoSet() {
        Server { _, _ -> fail("No command with revoked grant") }.use { server ->
            failure { backend(server) { throw IOException("Access revoked") }.setEnabled(rule, false) }
            assertTrue(server.commands.isEmpty())
        }
    }

    @Test fun lostWriteResponseTimesOutWithoutReplayAndClosesSocket() {
        val output = ByteArrayOutputStream()
        val loginReply = ByteArrayOutputStream().also { Server.send(it, listOf("!done")) }.toByteArray()
        val input = object : InputStream() {
            private var offset = 0
            override fun read(): Int {
                if (offset == loginReply.size) throw SocketTimeoutException("fixture timeout")
                return loginReply[offset++].toInt() and 255
            }
            // Do not prefetch beyond the login frame in BufferedInputStream.
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                bytes[off] = read().toByte()
                return 1
            }
        }
        val socket = object : Socket() {
            override fun getInputStream() = input
            override fun getOutputStream() = output
        }
        var opens = 0
        val backend = ApiBackend(profile) { opens++; socket }
        val error = failure { backend.setEnabled(rule, true) }
        assertTrue(error.message.orEmpty().contains("вовремя"))
        assertTrue(socket.isClosed)
        assertEquals(1, opens)
        val wire = ApiWire(output.toByteArray().inputStream(), ByteArrayOutputStream())
        assertEquals("/login", wire.readSentence().first())
        assertEquals(listOf("/ip/firewall/filter/set", "=.id=*A", "=disabled=false"), wire.readSentence())
        assertTrue(failure { wire.readSentence() } is EOFException)
    }

    @Test fun interruptedOperationDoesNotOpenSocket() {
        val backend = ApiBackend(profile) { fail("No network after cancellation"); Socket() }
        Thread.currentThread().interrupt()
        try { failure { backend.version() } } finally { Thread.interrupted() }
    }

    @Test fun realLoopbackServicePreflightSetAndReadback() {
        var enabled = false
        Server { command, output ->
            when {
                command.first() == "/system/resource/print" -> Server.send(output, listOf("!re", "=version=7.23.8"))
                command.first().endsWith("/filter/print") -> row(output, rule.copy(disabled = !enabled))
                command.first().endsWith("/set") -> { enabled = command.contains("=disabled=false") }
            }
            Server.send(output, listOf("!done"))
        }.use { server ->
            val service = RouterFirewallService { backend(server) }
            val group = service.scan(profile).groups.single()
            assertEquals(GroupState.ALL_ENABLED, service.switchGroup(profile, group, true).state)
            val active = service.scan(profile).groups.single()
            assertEquals(GroupState.ALL_DISABLED, service.switchGroup(profile, active, false) { true }.state)
            assertEquals(2, server.commands.count { it.first().endsWith("/set") })
        }
    }

    @Test fun apiProfilesKeepIndependentRestSettingsOnEveryRoute() {
        for (route in ConnectionRoute.values()) for (protocol in ApiProtocol.values()) {
            val p = profile.copy(connectionRoute = route, nativeApiProtocol = protocol, nativeApiPort = 18728, restPort = 8443)
            ProfileValidation.requireValid(p)
            assertEquals(p, ProfileJson.decode(ProfileJson.encode(listOf(p))).single())
            assertEquals(18728, p.endpointPort)
            assertEquals(8443, p.copy(access = RouterAccess.REST).endpointPort)
            assertFalse(p.toString().contains(p.password))
        }
    }

    @Test fun oldProfilesStayRestAndDoNotAdoptLegacyApiPort() {
        val old = """[{"id":"x","name":"x","host":"router","username":"u","password":"fixture","protocol":"HTTP","restPort":8080,"connectionRoute":"WIFI","apiPort":1234}]"""
        val p = ProfileJson.decode(old).single()
        assertEquals(RouterAccess.REST, p.access); assertEquals(8080, p.endpointPort)
        assertEquals(8728, p.nativeApiPort); assertEquals(RestProtocol.HTTP, p.protocol)
    }

    @Test fun unknownAccessOrApiSecurityFailsClosed() {
        val json = ProfileJson.encode(listOf(profile))
        failure { ProfileJson.decode(json.replace("\"access\":\"API\"", "\"access\":\"AUTO\"")) }
        failure { ProfileJson.decode(json.replace("\"nativeApiProtocol\":\"API\"", "\"nativeApiProtocol\":\"AUTO\"")) }
    }

    @Test fun tlsApiDefaultsTo8729AndInvalidPortsAreRejected() {
        val json = """[{"id":"x","name":"x","host":"router","username":"u","password":"fixture","access":"API","nativeApiProtocol":"API_SSL"}]"""
        assertEquals(8729, ProfileJson.decode(json).single().endpointPort)
        for (port in listOf(0, 65536)) failure { ProfileValidation.requireValid(profile.copy(nativeApiPort = port)) }
    }

    @Test fun socketCreationAndDnsUseOnlySuppliedRoute() {
        Server { _, output -> Server.send(output, listOf("!re", "=version=7.23.8")); Server.send(output, listOf("!done")) }.use { server ->
            var created = 0; var resolved = 0
            val p = profile.copy(host = "fixture.invalid", nativeApiPort = server.port)
            val backend = ApiBackend(p) { ApiSocketConnection.open(p, { created++; Socket() }, { host ->
                assertEquals("fixture.invalid", host); resolved++; arrayOf(InetAddress.getByName("127.0.0.1"))
            }) }
            backend.version()
            assertEquals(1, created); assertEquals(1, resolved)
        }
    }

    @Test fun failedResolutionClosesBoundSocketWithoutFallback() {
        val socket = Socket()
        var attempts = 0
        failure { ApiSocketConnection.open(profile, { attempts++; socket }, { throw UnknownHostException() }) }
        assertTrue(socket.isClosed); assertEquals(1, attempts)
    }
}
