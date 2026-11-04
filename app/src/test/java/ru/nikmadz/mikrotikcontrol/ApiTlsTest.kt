package ru.nikmadz.mikrotikcontrol

import org.junit.Assert.*
import org.junit.Test
import ru.nikmadz.mikrotikcontrol.core.*
import ru.nikmadz.mikrotikcontrol.net.ApiSocketConnection
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class ApiTlsTest {
    @Test fun tlsChecksTrustAndHostAndNeverFallsBackToPlainSocket() {
        // Throwaway test identity generated locally; never shipped or saved in project history.
        val directory = Files.createTempDirectory("mst-api-tls-")
        try {
            val identity = directory.resolve("fixture.p12").toFile()
            val keytool = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "keytool.exe" else "keytool"
            val process = ProcessBuilder(java.io.File(System.getProperty("java.home"), "bin/$keytool").absolutePath,
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore",
                identity.absolutePath, "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start()
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "TLS fixture generation failed" }
            val keys = KeyStore.getInstance("PKCS12").apply { identity.inputStream().use { load(it, "fixture-only".toCharArray()) } }
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-only".toCharArray()) }
            val serverContext = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
            val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", keys.getCertificate("fixture")) }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
            val trustedClient = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }.socketFactory
            val untrustedClient = SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
            for ((host, factory, success) in listOf(Triple("localhost", trustedClient, true),
                Triple("wrong.invalid", trustedClient, false), Triple("localhost", untrustedClient, false))) {
                val server = serverContext.serverSocketFactory.createServerSocket(0, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
                val worker = kotlin.concurrent.thread(isDaemon = true) {
                    runCatching { server.accept().use { (it as SSLSocket).soTimeout = 3000; it.startHandshake() } }
                }
                var creations = 0
                val raw = Socket()
                try {
                    val profile = RouterProfile("id", "TLS fixture", host, "fixture", "fixture-only", access = RouterAccess.API,
                        nativeApiProtocol = ApiProtocol.API_SSL, nativeApiPort = server.localPort)
                    try {
                        ApiSocketConnection.open(profile, { creations++; raw }, { arrayOf(InetAddress.getByName("127.0.0.1")) }, factory).use { }
                        assertTrue("Untrusted/wrong-name certificate accepted", success)
                    } catch (e: IOException) { if (success) throw e; assertTrue(raw.isClosed) }
                    assertEquals(1, creations)
                } finally { server.close(); worker.join(4000); assertFalse(worker.isAlive); raw.close() }
            }
        } finally {
            Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
