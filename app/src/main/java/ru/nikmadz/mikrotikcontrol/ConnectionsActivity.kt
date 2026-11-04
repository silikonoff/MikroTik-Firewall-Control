package ru.nikmadz.mikrotikcontrol

import android.app.AlertDialog
import android.content.Intent
import android.widget.ScrollView
import ru.nikmadz.mikrotikcontrol.security.ProfileStore

/** Profile administration is intentionally outside the operator screen. */
class ConnectionsActivity : AdminActivity() {
    private lateinit var store: ProfileStore

    override fun onAdminCreate() {
        store = ProfileStore(this)
    }

    override fun onAdminResume() {
        render()
    }

    private fun render(): Unit = with(UiKit) {
        val root = vertical().apply {
            setBackgroundColor(BG); setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        setContentView(ScrollView(this@ConnectionsActivity).apply { addView(root) })
        root.addView(brandHeader("Настройки администратора"))
        val back = button("← Рабочий экран", CARD)
        back.setOnClickListener { finish() }
        columnWithMargin(root, back, 12)
        columnWithMargin(root, label("Подключения", 23f, WHITE, true), 24)
        columnWithMargin(root, label("Выбранный объект отображается у оператора. При выходе из настроек доступ снова закрывается.", 14f, MUTED), 8)
        try {
            val profiles = store.load()
            val active = store.selectedProfileId()
            for (profile in profiles) {
                val card = vertical().apply {
                    background = bg(CARD, dp(16).toFloat(), BORDER)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                }
                card.addView(label(profile.name, 20f, WHITE, true))
                columnWithMargin(card, label(
                    "${profile.host}:${profile.endpointPort} · ${profile.transportLabel}\n${profile.connectionRoute.description}", 13f, MUTED
                ), 6)
                val select = button(if (profile.id == active) "✓ Выбран для работы" else "Выбрать для работы", GREEN)
                select.isEnabled = profile.id != active
                select.setOnClickListener {
                    try { store.selectProfile(profile.id); render() }
                    catch (_: Exception) { showError() }
                }
                columnWithMargin(card, select, 12)
                val edit = button("Изменить подключение", BORDER)
                edit.setOnClickListener {
                    startActivity(Intent(this@ConnectionsActivity, SettingsActivity::class.java).putExtra("profileId", profile.id))
                }
                columnWithMargin(card, edit, 8)
                columnWithMargin(root, card, 14)
            }
            if (profiles.isEmpty()) columnWithMargin(root, label("Добавьте подключение к MikroTik.", 15f, MUTED), 18)
            val add = button("+ Добавить подключение", BLUE)
            add.setOnClickListener { startActivity(Intent(this@ConnectionsActivity, SettingsActivity::class.java)) }
            columnWithMargin(root, add, 20)
        } catch (_: Exception) { showError() }
        columnWithMargin(root, label("Доступ администратора", 23f, WHITE, true), 28)
        val changePin = button("Изменить PIN-код", BLUE)
        changePin.setOnClickListener { startActivity(Intent(this@ConnectionsActivity, ChangePinActivity::class.java)) }
        columnWithMargin(root, changePin, 12)
        columnWithMargin(root, label("Обслуживание", 23f, WHITE, true), 28)
        val restore = button("Выключить правила / восстановить трафик", GREEN)
        restore.setOnClickListener { startActivity(Intent(this@ConnectionsActivity, AdminRulesActivity::class.java)) }
        columnWithMargin(root, restore, 12)
        columnWithMargin(root, label("Сведения загружаются с выбранного оборудования после возврата на рабочий экран.", 14f, MUTED), 8)
        for ((title, action) in listOf("Диагностика подключения" to "diagnostics", "Состав правил по группам" to "groups", "Ярлык группы на рабочем столе" to "shortcuts")) {
            val tool = button(title, CARD)
            tool.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra("adminAction", action))
                finish()
            }
            columnWithMargin(root, tool, 10)
        }
        columnWithMargin(root, label("MFC · v0.2.11\nRouterOS 7 · MikroTik Firewall Control", 12f, MUTED), 24)
    }

    private fun showError() {
        showAdminDialog(AlertDialog.Builder(this).setTitle("Ошибка хранилища")
            .setMessage("Не удалось прочитать или сохранить настройки. Существующие данные не сбрасывались.")
            .setPositiveButton("ОК", null))
    }
}
