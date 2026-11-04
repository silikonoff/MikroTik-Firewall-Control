package ru.nikmadz.mikrotikcontrol.net

import ru.nikmadz.mikrotikcontrol.core.*
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale

/** One authenticated, closed session per command; no write retries or protocol fallback. */
class ApiBackend(private val profile: RouterProfile, private val beforeWrite: () -> Unit = {},
                 private val open: () -> Socket) : RouterBackend {
    init { ProfileValidation.requireValid(profile) }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw IOException("Операция отменена")
    }

    private fun request(command: String, vararg attributes: String): List<Map<String, String>> {
        try {
            checkCancellation()
            open().use { socket ->
                val deadline = System.nanoTime() + 30_000_000_000L
                val wire = ApiWire(socket.getInputStream().buffered(), socket.getOutputStream().buffered()) {
                    checkCancellation()
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) throw SocketTimeoutException("API: время ожидания ответа истекло")
                    socket.soTimeout = minOf(10_000L, remaining).toInt().coerceAtLeast(1)
                }
                checkCancellation()
                wire.writeSentence(listOf("/login", "=name=${profile.username}", "=password=${profile.password}"))
                readReply(wire, login = true)
                if (command.endsWith("/set")) beforeWrite()
                checkCancellation()
                wire.writeSentence(listOf(command) + attributes)
                return readReply(wire, login = false)
            }
        } catch (e: SocketTimeoutException) {
            throw IOException("API: нет ответа вовремя. Проверьте связь, порт и службу ${serviceName()}. Команда не повторялась.", e)
        }
    }

    private fun serviceName() = if (profile.nativeApiProtocol == ApiProtocol.API_SSL) "api-ssl" else "api"

    private fun readReply(wire: ApiWire, login: Boolean): List<Map<String, String>> {
        val rows = mutableListOf<Map<String, String>>()
        repeat(25_000) {
            val sentence = wire.readSentence()
            if (sentence.isEmpty()) return@repeat
            val attributes = linkedMapOf<String, String>()
            for (word in sentence.drop(1)) {
                if (word.startsWith("=")) {
                    val separator = word.indexOf('=', 1)
                    if (separator < 2) throw IOException("API: неверное поле ответа")
                    val key = word.substring(1, separator)
                    if (attributes.put(key, word.substring(separator + 1)) != null) {
                        throw IOException("API: повторяющееся поле ответа")
                    }
                } else throw IOException("API: неожиданное поле ответа")
            }
            when (sentence.first()) {
                "!done" -> {
                    if (login && (attributes.isNotEmpty() || rows.isNotEmpty())) {
                        throw IOException("API: требуется обычная авторизация RouterOS 7")
                    }
                    return rows
                }
                "!re" -> {
                    if (login) throw IOException("API: неверный ответ авторизации")
                    rows += attributes
                }
                "!empty" -> if (attributes.isNotEmpty()) throw IOException("API: неверный пустой ответ")
                "!trap", "!fatal" -> {
                    if (login) throw IOException("API: вход отклонён. Проверьте логин, пароль, право api и разрешённую сеть пользователя.")
                    // Avoid reflecting credentials if the peer includes them in an error message.
                    val message = attributes["message"].orEmpty().replace(profile.password, "***")
                        .filterNot { it.isISOControl() }.take(180)
                    throw IOException("API: команда отклонена. Проверьте права read/write/api. $message")
                }
                else -> throw IOException("API: неизвестный тип ответа")
            }
        }
        throw IOException("API: слишком много записей в ответе")
    }

    override fun version(): String {
        val rows = request("/system/resource/print", "=.proplist=version")
        if (rows.size != 1) throw IOException("API system/resource: ожидалась одна запись")
        val version = rows.single()["version"].orEmpty().trim()
        RouterOs.requireVersion7(version)
        return version
    }

    private fun required(row: Map<String, String>, field: String): String =
        row[field] ?: throw IOException("API: в правиле отсутствует $field")

    private fun flag(row: Map<String, String>, field: String, default: Boolean? = null): Boolean =
        when (row[field]?.lowercase(Locale.ROOT)) {
            "true", "yes" -> true
            "false", "no" -> false
            null -> default ?: throw IOException("API: в правиле отсутствует $field")
            else -> throw IOException("API: неизвестное состояние $field")
        }

    override fun readRules(): List<FirewallRule> {
        val result = mutableListOf<FirewallRule>()
        for (table in FirewallTable.values()) {
            val rows = request("/${table.path}/print", "=.proplist=.id,comment,disabled,action,chain,dynamic,invalid")
            for (row in rows) {
                val comment = row["comment"].orEmpty()
                if (FirewallGroups.nameOf(comment) == null) continue
                result += FirewallRule(required(row, ".id"), table, comment, flag(row, "disabled"),
                    required(row, "action"), required(row, "chain"), flag(row, "dynamic", false), flag(row, "invalid", false))
            }
        }
        return result
    }

    override fun setEnabled(rule: FirewallRule, enabled: Boolean) {
        require(FirewallGroups.safeId(rule.id)) { "Недопустимый ID правила" }
        require(FirewallGroups.nameOf(rule.comment) != null && !rule.dynamic) { "Это правило не управляется приложением" }
        require(!enabled || !rule.invalid) { "Нельзя включить invalid-правило" }
        request("/${rule.table.path}/set", "=.id=${rule.id}", "=disabled=${!enabled}")
    }
}
