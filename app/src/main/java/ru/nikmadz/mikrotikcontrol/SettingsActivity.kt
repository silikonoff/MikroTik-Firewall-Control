package ru.nikmadz.mikrotikcontrol

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import ru.nikmadz.mikrotikcontrol.core.ConnectionRoute
import ru.nikmadz.mikrotikcontrol.core.ProfileValidation
import ru.nikmadz.mikrotikcontrol.core.RouterProfile
import ru.nikmadz.mikrotikcontrol.core.RestProtocol
import ru.nikmadz.mikrotikcontrol.core.RouterAccess
import ru.nikmadz.mikrotikcontrol.core.ApiProtocol
import android.view.View
import android.widget.AdapterView
import ru.nikmadz.mikrotikcontrol.security.ProfileStore
import ru.nikmadz.mikrotikcontrol.security.AdminAccess
import java.util.UUID

class SettingsActivity : AdminActivity() {
    private lateinit var store: ProfileStore
    private var existing: RouterProfile? = null

    override fun onAdminCreate() {
        store = ProfileStore(this)
        try {
            val id = intent.getStringExtra("profileId")
            existing = store.load().firstOrNull { it.id == id }
            render()
        } catch (e: Exception) {
            showAdminDialog(AlertDialog.Builder(this).setTitle("Ошибка хранилища")
                .setMessage("Не удалось прочитать сохранённые настройки. Данные не перезаписаны.")
                .setCancelable(false).setPositiveButton("Закрыть") { _, _ -> finish() })
        }
    }

    private fun render() = with(UiKit) {
        val root = vertical().apply {
            setBackgroundColor(BG)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        setContentView(ScrollView(this@SettingsActivity).apply { isFillViewport = true; addView(root) })
        val back = button("← К подключениям", CARD)
        back.setOnClickListener { finish() }
        root.addView(back)
        columnWithMargin(root, label(if (existing == null) "Новое подключение" else "Настройка подключения", 24f, WHITE, true), 22)
        columnWithMargin(root, label("RouterOS 7  •  REST / API", 14f, BLUE, true), 10)
        columnWithMargin(root, label("Сохранённый пароль зашифрован ключом Android Keystore.", 13f, MUTED), 8)

        fun field(title: String, value: String, hint: String = title, viewId: Int,
                  container: android.widget.LinearLayout = root): EditText {
            columnWithMargin(container, label(title, 14f, MUTED), 18)
            return input(hint, value).also { it.id = viewId; columnWithMargin(container, it, 6) }
        }
        val name = field("Название объекта для оператора", existing?.name ?: "", "Например: Диспетчерская", R.id.profile_name)
        val host = field("IP или DNS роутера (без http(s)://)", existing?.host ?: "192.168.88.1", viewId = R.id.profile_host)
        host.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        columnWithMargin(root, label("Интерфейс управления", 14f, MUTED), 18)
        val accessValues = RouterAccess.values().toList()
        val accessSpinner = Spinner(this@SettingsActivity).apply {
            id = R.id.profile_access
            minimumHeight = dp(56)
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                accessValues.map { it.description })
            setSelection(accessValues.indexOf(existing?.access ?: RouterAccess.REST))
        }
        columnWithMargin(root, accessSpinner, 6)
        val restFields = vertical()
        val apiFields = vertical()
        root.addView(restFields)
        root.addView(apiFields)
        fun showTransportFields() {
            val rest = accessValues[accessSpinner.selectedItemPosition] == RouterAccess.REST
            restFields.visibility = if (rest) View.VISIBLE else View.GONE
            apiFields.visibility = if (rest) View.GONE else View.VISIBLE
        }
        showTransportFields()
        accessSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showTransportFields()
        }
        columnWithMargin(restFields, label("Протокол REST", 14f, MUTED), 18)
        val protocolValues = RestProtocol.values().toList()
        var previousProtocol = existing?.protocol ?: RestProtocol.HTTPS
        val protocolSpinner = Spinner(this@SettingsActivity).apply {
            minimumHeight = dp(56)
            id = R.id.profile_protocol
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                protocolValues.map { it.description })
            setSelection(protocolValues.indexOf(previousProtocol))
        }
        columnWithMargin(restFields, protocolSpinner, 6)
        val port = field("Порт REST", (existing?.restPort ?: previousProtocol.defaultPort).toString(), viewId = R.id.profile_port, container = restFields)
        port.inputType = InputType.TYPE_CLASS_NUMBER
        protocolSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val protocol = protocolValues[position]
                if (protocol != previousProtocol && port.text.toString() == previousProtocol.defaultPort.toString()) {
                    port.setText(protocol.defaultPort.toString())
                }
                previousProtocol = protocol
            }
        }
        columnWithMargin(apiFields, label("Протокол API", 14f, MUTED), 18)
        val apiProtocols = ApiProtocol.values().toList()
        var previousApiProtocol = existing?.nativeApiProtocol ?: ApiProtocol.API
        val apiSpinner = Spinner(this@SettingsActivity).apply {
            id = R.id.profile_api_protocol
            minimumHeight = dp(56)
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                apiProtocols.map { it.description })
            setSelection(apiProtocols.indexOf(previousApiProtocol))
        }
        columnWithMargin(apiFields, apiSpinner, 6)
        val apiPort = field("Порт API", (existing?.nativeApiPort ?: previousApiProtocol.defaultPort).toString(),
            viewId = R.id.profile_api_port, container = apiFields)
        apiPort.inputType = InputType.TYPE_CLASS_NUMBER
        apiSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = apiProtocols[position]
                if (next != previousApiProtocol && apiPort.text.toString() == previousApiProtocol.defaultPort.toString()) {
                    apiPort.setText(next.defaultPort.toString())
                }
                previousApiProtocol = next
            }
        }
        columnWithMargin(apiFields, label("На роутере нужна служба api или api-ssl. www и REST не требуются. Права пользователя: read, write, api.", 13f, MUTED), 8)
        val user = field("Логин", existing?.username ?: "app_control", viewId = R.id.profile_username)
        val password = field("Пароль", "", if (existing == null) "Введите пароль" else "Пусто — оставить прежний", R.id.profile_password)
        password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        password.isSaveEnabled = false

        columnWithMargin(root, label("Способ подключения к роутеру", 14f, MUTED), 20)
        val routeValues = listOf(ConnectionRoute.WIFI, ConnectionRoute.SYSTEM, ConnectionRoute.ETHERNET)
        val routeSpinner = Spinner(this@SettingsActivity).apply {
            minimumHeight = dp(56)
            id = R.id.profile_route
            adapter = ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                routeValues.map { it.description }
            )
            setSelection(routeValues.indexOf(existing?.connectionRoute ?: ConnectionRoute.WIFI))
        }
        columnWithMargin(root, routeSpinner, 8)
        val help = button("Справка по подключению и группам", CARD)
        help.setOnClickListener {
            showAdminDialog(AlertDialog.Builder(this@SettingsActivity).setTitle("Администратору")
                .setMessage("Ethernet: телефон → USB/OTG-LAN → LAN MikroTik. Интернет не нужен; мобильного fallback нет. Нужны поддержка адаптера и IP по DHCP/статически.\n\nWi-Fi: прямое соединение без VPN и без перехода на мобильную сеть. Системная сеть/VPN: для доступа через VPN выберите этот режим. Маршрут выбирает Android, включая мобильную сеть, если она активна. Выбранная сеть фиксируется на одну операцию.\n\nHTTP передаёт логин, пароль и команды без шифрования. HTTP доступен через Wi-Fi, VPN, Ethernet и системную сеть. Выбирайте его, если это требуется вашей настройкой. Протокол меняется вручную.\n\nREST: службы www (HTTP) / www-ssl (HTTPS), права read/write/api/rest-api. Обычный API: службы api (8728) / api-ssl (8729), права read/write/api; www не нужен. API без SSL передаёт пароль открыто.\n\nHTTPS и API-SSL: сертификат с SAN для IP/DNS. Собственный публичный CA установите в доверенные CA Android. TLS не отключается.\n\nГруппы: APP_STOP_TEST, APP_STOP_VPN и др. Текст после | — описание карточки; одинаковое описание у всех правил группы. Включение правил не доказывает фактическую блокировку: учитывайте action, порядок, FastTrack и conntrack.\n\nГруппа переключается последовательно, не атомарно. При ошибке часть правил может измениться; автоматического отката нет. Динамические правила не переключаются, invalid нельзя включать.")
                .setPositiveButton("Понятно", null))
        }
        columnWithMargin(root, help, 18)

        val save = button("Сохранить подключение", BLUE)
        columnWithMargin(root, save, 22)
        save.setOnClickListener {
            try {
                val profile = RouterProfile(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name.text.toString().trim(),
                    host = host.text.toString().trim().removeSurrounding("[", "]"),
                    username = user.text.toString().trim(),
                    password = password.text.toString().ifEmpty { existing?.password.orEmpty() },
                    restPort = port.text.toString().toIntOrNull() ?: 0,
                    connectionRoute = routeValues[routeSpinner.selectedItemPosition],
                    protocol = protocolValues[protocolSpinner.selectedItemPosition],
                    access = accessValues[accessSpinner.selectedItemPosition],
                    nativeApiProtocol = apiProtocols[apiSpinner.selectedItemPosition],
                    nativeApiPort = apiPort.text.toString().toIntOrNull() ?: 0
                )
                ProfileValidation.requireValid(profile)
                val persist = persist@{
                    if (!AdminAccess.session.unlocked) return@persist
                    try {
                        store.upsert(profile)
                        store.selectProfile(profile.id)
                        Toast.makeText(this@SettingsActivity, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } catch (_: Exception) { showError("Не удалось сохранить настройки") }
                }
                if (profile.unencrypted) {
                    showAdminDialog(AlertDialog.Builder(this@SettingsActivity).setTitle("${profile.transportLabel} без шифрования")
                        .setMessage("Логин, пароль и команды будут передаваться без TLS. В режиме системной сети Android также может выбрать мобильную сеть.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Использовать ${if (profile.access == RouterAccess.REST) "HTTP" else "API"}") { _, _ -> persist() })
                } else persist()
            } catch (e: Exception) { showError(e.message ?: "Не удалось сохранить настройки") }
        }
        existing?.let { profile ->
            val delete = button("Удалить подключение", CARD)
            columnWithMargin(root, delete, 12)
            delete.setOnClickListener {
                showAdminDialog(AlertDialog.Builder(this@SettingsActivity).setTitle("Удалить профиль?")
                    .setMessage("Подключение и сохранённый пароль будут удалены с телефона. Настройки роутера не изменятся.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ ->
                        if (!AdminAccess.session.unlocked) return@setPositiveButton
                        try {
                            store.remove(profile.id)
                            setResult(RESULT_OK)
                            finish()
                        } catch (e: Exception) { showError("Не удалось удалить подключение") }
                    })
            }
        }
        root.addView(space(30))
    }

    private fun showError(message: String) {
        showAdminDialog(AlertDialog.Builder(this).setTitle("Ошибка").setMessage(message).setPositiveButton("ОК", null))
    }
}
