package ru.nikmadz.mikrotikcontrol.net

import ru.nikmadz.mikrotikcontrol.core.ApiProtocol
import ru.nikmadz.mikrotikcontrol.core.RouterProfile
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Both socket creation and DNS must belong to the captured Android Network. */
object ApiSocketConnection {
    fun open(profile: RouterProfile, createSocket: () -> Socket, resolve: (String) -> Array<InetAddress>,
             tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory): Socket {
        val raw = createSocket()
        var connection: Socket = raw
        try {
            val address = resolve(profile.host).firstOrNull() ?: throw IOException("API: адрес не найден")
            raw.connect(InetSocketAddress(address, profile.nativeApiPort), 8000)
            raw.soTimeout = 10000
            if (profile.nativeApiProtocol == ApiProtocol.API_SSL) {
                val tls = tlsFactory.createSocket(raw, profile.host, profile.nativeApiPort, true) as SSLSocket
                connection = tls
                tls.soTimeout = 10000
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake() // Default trust chain AND DNS/IP identity; never trust-all or downgrade.
            }
            return connection
        } catch (e: Exception) {
            runCatching { connection.close() }
            if (connection !== raw) runCatching { raw.close() }
            val service = if (profile.nativeApiProtocol == ApiProtocol.API_SSL) "api-ssl (сертификат, доверие CA и IP/DNS)" else "api"
            throw IOException("Нет соединения с ${profile.host}:${profile.nativeApiPort}. Проверьте сеть и службу $service на MikroTik.", e)
        }
    }
}
