package ru.nikmadz.mikrotikcontrol.net

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import ru.nikmadz.mikrotikcontrol.core.*
import java.io.IOException

class RestBackend(private val transport: RestTransport) : RouterBackend {
    override fun version(): String {
        val value = JSONTokener(transport.request("GET", "system/resource", null)).nextValue()
        // RouterOS versions/commands may serialize singleton menus as an object or a one-item array.
        val resource = when (value) {
            is JSONObject -> value
            is JSONArray -> if (value.length() == 1) value.getJSONObject(0)
                else throw IOException("REST system/resource: ожидалась одна запись")
            else -> throw IOException("REST system/resource: неверный формат ответа")
        }
        val version = resource.optString("version", "").trim()
        RouterOs.requireVersion7(version)
        return version
    }

    private fun flag(item: JSONObject, field: String, default: Boolean? = null): Boolean {
        if (!item.has(field) || item.isNull(field)) {
            return default ?: throw IOException("В правиле отсутствует состояние $field")
        }
        return when (item.get(field).toString().lowercase(java.util.Locale.ROOT)) {
            "true", "yes" -> true
            "false", "no" -> false
            else -> throw IOException("Неизвестное состояние поля $field")
        }
    }

    override fun readRules(): List<FirewallRule> {
        val result = mutableListOf<FirewallRule>()
        for (table in FirewallTable.values()) {
            val items = JSONArray(transport.request("GET", table.path, null))
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val comment = item.optString("comment", "")
                if (FirewallGroups.nameOf(comment) == null) continue
                result += FirewallRule(
                    id = item.getString(".id"), table = table, comment = comment,
                    disabled = flag(item, "disabled"),
                    action = item.getString("action"), chain = item.getString("chain"),
                    dynamic = flag(item, "dynamic", false), invalid = flag(item, "invalid", false)
                )
            }
        }
        return result
    }

    override fun setEnabled(rule: FirewallRule, enabled: Boolean) {
        require(FirewallGroups.safeId(rule.id)) { "Недопустимый ID правила" }
        require(FirewallGroups.nameOf(rule.comment) != null && !rule.dynamic) { "Это правило не управляется приложением" }
        require(!enabled || !rule.invalid) { "Нельзя включить invalid-правило" }
        // Official REST POST /set, equivalent to changing 'disabled' via PATCH.
        // HttpURLConnection's allowed methods do not include PATCH on all supported Android versions.
        val body = JSONObject().put(".id", rule.id).put("disabled", (!enabled).toString())
        transport.request("POST", "${rule.table.path}/set", body.toString())
    }
}
