package ru.nikmadz.mikrotikcontrol.net

import ru.nikmadz.mikrotikcontrol.core.ProfileValidation
import ru.nikmadz.mikrotikcontrol.core.RouterProfile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URI
import java.net.UnknownHostException
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

fun interface RestTransport {
    fun request(method: String, path: String, body: String?): String
}

/** TLS trust and hostname validation are provided by Android; never replace them with trust-all. */
class HttpRestTransport(
    private val profile: RouterProfile,
    private val beforeWrite: () -> Unit = {},
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : RestTransport {
    init { ProfileValidation.requireValid(profile) }

    override fun request(method: String, path: String, body: String?): String {
        check(!Thread.currentThread().isInterrupted) { "Операция отменена" }
        require(method == "GET" || method == "POST")
        require(path.matches(Regex("[a-z/]+")) && "//" !in path && !path.startsWith('/'))
        val host = if (':' in profile.host) "[${profile.host}]" else profile.host
        var connection: HttpURLConnection? = null
        try {
            if (method == "POST") beforeWrite()
            connection = open(URI("${profile.protocol.scheme}://$host:${profile.restPort}/rest/$path").toURL())
            require(profile.protocol.scheme != "https" || connection is HttpsURLConnection) { "Для HTTPS требуется TLS-соединение" }
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            val auth = "${profile.username}:${profile.password}".toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Authorization", "Basic ${Base64.getEncoder().encodeToString(auth)}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                // Streaming prevents transparent automatic replays of a configuration write.
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { beforeWrite(); it.write(bytes) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val reason = when (code) {
                    401 -> "Проверьте логин, пароль, rest-api и разрешённую сеть пользователя"
                    403 -> "REST запрещён: проверьте права и доступ к REST через www/www-ssl"
                    404 -> "REST-ресурс не найден: проверьте адрес, порт и RouterOS 7"
                    in 300..399 -> "Перенаправление отклонено: укажите прямой адрес MikroTik"
                    500 -> "Проверьте права read, write, api, rest-api и журнал MikroTik"
                    else -> "Команда отклонена: проверьте права и состояние правила на роутере"
                }
                // Never echo arbitrary server bodies: they may contain private configuration.
                throw IOException("REST $code: $reason ($path)")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Операция отменена")
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (output.size() + read > 4 * 1024 * 1024) throw IOException("Ответ REST превышает 4 МБ")
                    output.write(buffer, 0, read)
                }
            }
            return output.toString("UTF-8")
        } catch (e: SSLException) {
            throw IOException("Ошибка TLS. Проверьте доверие к CA, срок сертификата, дату телефона и SAN для IP/DNS роутера", e)
        } catch (e: SocketTimeoutException) {
            throw IOException("MikroTik не ответил вовремя. Проверьте Ethernet/Wi-Fi, IP, порт REST и firewall; состояние записи могло измениться", e)
        } catch (e: ConnectException) {
            throw IOException("Нет соединения с ${profile.host}:${profile.restPort} (${profile.protocol.name}). Проверьте порт и службу ${if (profile.protocol.scheme == "https") "www-ssl" else "www"} на MikroTik", e)
        } catch (e: UnknownHostException) {
            throw IOException("Имя роутера не найдено. Для автономного теста используйте IP, например 192.168.88.1", e)
        } finally {
            connection?.disconnect()
        }
    }
}
