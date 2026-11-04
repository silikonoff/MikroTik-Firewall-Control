package ru.nikmadz.mikrotikcontrol.core

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/** Transport chosen per saved MikroTik profile, independent of Android's default Internet route. */
enum class ConnectionRoute(val description: String) {
    ETHERNET("Ethernet (USB-адаптер)"),
    WIFI("Wi-Fi напрямую (без VPN)"),
    SYSTEM("Системная сеть / VPN")
}

enum class RestProtocol(val scheme: String, val defaultPort: Int, val description: String) {
    HTTPS("https", 443, "HTTPS — защищённое соединение"),
    HTTP("http", 80, "HTTP — без шифрования")
}

enum class RouterAccess(val description: String) {
    REST("REST API"), API("Обычный MikroTik API")
}

enum class ApiProtocol(val defaultPort: Int, val description: String) {
    API(8728, "API — без шифрования"),
    API_SSL(8729, "API-SSL — защищённое соединение")
}

/** RouterOS 7; transport is explicit and never negotiated by fallback. */
data class RouterProfile(
    val id: String,
    val name: String,
    val host: String,
    val username: String,
    val password: String,
    val restPort: Int = 443,
    val connectionRoute: ConnectionRoute = ConnectionRoute.WIFI,
    val protocol: RestProtocol = RestProtocol.HTTPS,
    val access: RouterAccess = RouterAccess.REST,
    val nativeApiPort: Int = 8728,
    val nativeApiProtocol: ApiProtocol = ApiProtocol.API
) {
    val endpointPort: Int get() = if (access == RouterAccess.REST) restPort else nativeApiPort
    val transportLabel: String get() = if (access == RouterAccess.REST) "REST · ${protocol.name}" else nativeApiProtocol.name.replace('_', '-')
    val unencrypted: Boolean get() = if (access == RouterAccess.REST) protocol == RestProtocol.HTTP else nativeApiProtocol == ApiProtocol.API
    // Never expose the password if a profile is accidentally included in an error/log.
    override fun toString(): String =
        "RouterProfile(id=$id, name=$name, host=$host, port=$endpointPort, connectionRoute=$connectionRoute, transport=$transportLabel)"
}

/** Small platform-free route selector to make explicit routing behavior testable. */
data class DirectNetworkCandidate(
    val id: Long,
    val ethernet: Boolean,
    val wifi: Boolean,
    val vpn: Boolean,
    val cellular: Boolean
)

object DirectRouteSelector {
    /** Returns null if unavailable/ambiguous. Never silently falls back to cellular or VPN. */
    fun choose(route: ConnectionRoute, networks: List<DirectNetworkCandidate>, activeId: Long?): Long? {
        if (route == ConnectionRoute.SYSTEM) return null // let Android handle system / VPN routing
        val matches = networks.filter { network ->
            !network.vpn && !network.cellular && when (route) {
                ConnectionRoute.ETHERNET -> network.ethernet
                ConnectionRoute.WIFI -> network.wifi
                ConnectionRoute.SYSTEM -> false
            }
        }
        return when (matches.size) {
            0 -> null
            1 -> matches.single().id
            else -> matches.firstOrNull { it.id == activeId }?.id
        }
    }
}

object ProfileValidation {
    fun validHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253) return false
        if (':' in host) {
            if (!host.matches(Regex("[A-Fa-f0-9:]+"))) return false
            // Only a numeric IPv6 literal reaches getByName: this never resolves a hostname.
            return runCatching { InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)
        }
        if (host.matches(Regex("[0-9.]+"))) {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.toIntOrNull() in 0..255 }
        }
        return host.split('.').all {
            it.length in 1..63 && it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
        }
    }

    fun requireValid(p: RouterProfile) {
        require(p.id.isNotBlank() && p.name.isNotBlank()) { "Укажите название подключения" }
        require(validHost(p.host)) { "Укажите IP или DNS без http(s)://, пути и порта" }
        require(p.restPort in 1..65535) { "Порт REST должен быть от 1 до 65535" }
        require(p.nativeApiPort in 1..65535) { "Порт API должен быть от 1 до 65535" }
        require(p.username.isNotBlank() && ':' !in p.username && p.username.none { it.isISOControl() }) {
            "Проверьте логин: двоеточие и управляющие символы недопустимы"
        }
        require(p.password.isNotEmpty()) { "Укажите пароль" }
    }
}

object RouterOs {
    fun requireVersion7(version: String) {
        require(Regex("^7\\.\\d+(?:[.A-Za-z0-9+_() -].*)?$").matches(version.trim())) {
            "Нужна RouterOS 7.x. Роутер сообщил версию: ${version.take(60).ifBlank { "неизвестно" }}"
        }
    }
}

enum class FirewallTable(val path: String, val label: String) {
    FILTER("ip/firewall/filter", "Filter"),
    NAT("ip/firewall/nat", "NAT"),
    MANGLE("ip/firewall/mangle", "Mangle"),
    RAW("ip/firewall/raw", "Raw")
}

data class FirewallRule(
    val id: String,
    val table: FirewallTable,
    val comment: String,
    val disabled: Boolean,
    val action: String,
    val chain: String,
    val dynamic: Boolean = false,
    val invalid: Boolean = false
) {
    val key: String get() = "${table.path}/$id"
}

enum class GroupState(val label: String) {
    ALL_ENABLED("Правила включены"),
    ALL_DISABLED("Правила выключены"),
    MIXED("Частично включены")
}

data class FirewallGroup(val name: String, val rules: List<FirewallRule>) {
    init { require(rules.isNotEmpty()) }
    val state: GroupState get() = when {
        rules.all { !it.disabled } -> GroupState.ALL_ENABLED
        rules.all { it.disabled } -> GroupState.ALL_DISABLED
        else -> GroupState.MIXED
    }
    val enabledCount: Int get() = rules.count { !it.disabled }
    val dynamicCount: Int get() = rules.count { it.dynamic }
    val invalidCount: Int get() = rules.count { it.invalid }
}

object FirewallGroups {
    private val pattern = Regex("^\\s*APP_STOP_([A-Za-z0-9][A-Za-z0-9_-]{0,63})\\s*(?:\\|.*)?$", RegexOption.IGNORE_CASE)
    fun nameOf(comment: String): String? = pattern.matchEntire(comment)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
    fun assemble(rules: List<FirewallRule>): List<FirewallGroup> = rules
        .mapNotNull { rule -> nameOf(rule.comment)?.let { it to rule } }
        .groupBy({ it.first }, { it.second })
        .map { (name, items) -> FirewallGroup(name, items) }
        .sortedBy { it.name }
    fun safeId(id: String): Boolean = Regex("^\\*[A-Fa-f0-9]+$").matches(id)
}
