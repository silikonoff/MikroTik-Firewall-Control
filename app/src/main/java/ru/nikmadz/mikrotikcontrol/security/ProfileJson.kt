package ru.nikmadz.mikrotikcontrol.security

import org.json.JSONArray
import org.json.JSONObject
import ru.nikmadz.mikrotikcontrol.core.ConnectionRoute
import ru.nikmadz.mikrotikcontrol.core.RouterProfile
import ru.nikmadz.mikrotikcontrol.core.RestProtocol
import ru.nikmadz.mikrotikcontrol.core.RouterAccess
import ru.nikmadz.mikrotikcontrol.core.ApiProtocol

object ProfileJson {
    fun decode(json: String): List<RouterProfile> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val connectionRoute = if (obj.has("connectionRoute")) {
                // Unknown values fail closed: do not unexpectedly route control traffic via mobile/VPN.
                ConnectionRoute.valueOf(obj.getString("connectionRoute"))
            } else if (obj.optBoolean("wifiOnly", false)) {
                ConnectionRoute.WIFI // Preserve v0.2.0 profiles.
            } else {
                ConnectionRoute.SYSTEM // Preserve v0.1.0 and v0.2.0 (wifiOnly=false) profiles.
            }
            val protocol = if (obj.has("protocol")) RestProtocol.valueOf(obj.getString("protocol")) else RestProtocol.HTTPS
            val access = if (obj.has("access")) RouterAccess.valueOf(obj.getString("access")) else RouterAccess.REST
            val apiProtocol = if (obj.has("nativeApiProtocol")) ApiProtocol.valueOf(obj.getString("nativeApiProtocol")) else ApiProtocol.API
            RouterProfile(
                id = obj.getString("id"), name = obj.getString("name"),
                host = obj.getString("host"), username = obj.getString("username"),
                password = obj.getString("password"), restPort = obj.optInt("restPort", protocol.defaultPort),
                connectionRoute = connectionRoute,
                protocol = protocol, access = access,
                nativeApiProtocol = apiProtocol,
                nativeApiPort = obj.optInt("nativeApiPort", apiProtocol.defaultPort)
            )
        }
    }

    fun encode(profiles: List<RouterProfile>): String {
        val array = JSONArray()
        for (p in profiles) {
            array.put(JSONObject().put("schemaVersion", 5)
                .put("id", p.id).put("name", p.name).put("host", p.host)
                .put("username", p.username).put("password", p.password)
                .put("restPort", p.restPort).put("connectionRoute", p.connectionRoute.name)
                .put("protocol", p.protocol.name).put("access", p.access.name)
                .put("nativeApiPort", p.nativeApiPort).put("nativeApiProtocol", p.nativeApiProtocol.name))
        }
        return array.toString()
    }
}
