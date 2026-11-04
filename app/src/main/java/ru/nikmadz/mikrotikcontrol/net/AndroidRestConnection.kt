package ru.nikmadz.mikrotikcontrol.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import ru.nikmadz.mikrotikcontrol.core.ConnectionRoute
import ru.nikmadz.mikrotikcontrol.core.DirectNetworkCandidate
import ru.nikmadz.mikrotikcontrol.core.DirectRouteSelector
import ru.nikmadz.mikrotikcontrol.core.RouterProfile
import java.io.IOException
import java.net.HttpURLConnection
import ru.nikmadz.mikrotikcontrol.core.ProfileValidation
import ru.nikmadz.mikrotikcontrol.core.RouterAccess

/** On-device REST/API transports; one captured Network per operation, not process-wide binding. */
object AndroidRestConnection {
    @Suppress("DEPRECATION")
    fun create(context: Context, profile: RouterProfile, beforeWrite: () -> Unit = {}): RouterBackend {
        ProfileValidation.requireValid(profile)
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: throw IOException("Сетевой сервис Android недоступен")
        val network = selectNetwork(manager, profile)
        return if (profile.access == RouterAccess.REST) {
            RestBackend(HttpRestTransport(profile, beforeWrite) { url -> network.openConnection(url) as HttpURLConnection })
        } else {
            ApiBackend(profile, beforeWrite) {
                ApiSocketConnection.open(profile, { network.socketFactory.createSocket() }, { network.getAllByName(it) })
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun selectNetwork(manager: ConnectivityManager, profile: RouterProfile): Network {
        if (profile.connectionRoute == ConnectionRoute.SYSTEM) {
            // Follow Android's selected network (including VPN), but keep this operation on it.
            return manager.activeNetwork ?: throw IOException("Нет активной сети Android. Проверьте Wi-Fi или VPN.")
        }

        val networks: List<Network> = manager.allNetworks.toList()
        val candidates = networks.mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            DirectNetworkCandidate(
                id = network.networkHandle,
                ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
        }
        // No INTERNET/VALIDATED requirement: an isolated LAN without an upstream works.
        val handle = DirectRouteSelector.choose(
            profile.connectionRoute, candidates, manager.activeNetwork?.networkHandle
        ) ?: throw IOException(when (profile.connectionRoute) {
            ConnectionRoute.ETHERNET -> "Android не видит однозначную сеть Ethernet. Проверьте OTG/USB-LAN, кабель, LAN-порт и получение IP от MikroTik. Интернет не нужен."
            ConnectionRoute.WIFI -> "Подключитесь к Wi-Fi роутера. При нескольких Wi-Fi сетях оставьте одну. Интернет не нужен."
            ConnectionRoute.SYSTEM -> "Не удалось определить сеть Android"
        })
        return networks.firstOrNull { it.networkHandle == handle }
            ?: throw IOException("Выбранная сеть исчезла. Проверьте подключение к MikroTik")
    }
}
