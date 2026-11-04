package ru.nikmadz.mikrotikcontrol

import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.Toast
import ru.nikmadz.mikrotikcontrol.core.*
import ru.nikmadz.mikrotikcontrol.net.AndroidRestConnection
import ru.nikmadz.mikrotikcontrol.security.AdminAccess
import ru.nikmadz.mikrotikcontrol.security.ProfileStore
import java.io.IOException
import java.util.concurrent.Executors

/** Disabling is never dispatched back to the operator Activity or through Intent grants. */
class AdminRulesActivity : AdminActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: ProfileStore
    @Volatile private var generation = 0
    private var working = false
    private var profile: RouterProfile? = null

    override fun onAdminCreate() { store = ProfileStore(this) }

    override fun onAdminResume() {
        if (working) renderMessage("Ожидаем завершения предыдущей операции…") else load()
    }

    override fun onPause() {
        generation++ // Revoke every pending confirmation/worker grant, including configuration changes.
        super.onPause()
    }

    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun valid(token: Int) = token == generation && adminResumed && AdminAccess.session.unlocked

    private fun root() = with(UiKit) {
        val layout = vertical().apply {
            setBackgroundColor(BG)
            setPadding(dp(20), dp(12), dp(20), dp(24))
            addView(brandHeader("Восстановление трафика · администратор"))
            columnWithMargin(this, button("← Настройки администратора", CARD).apply {
                setOnClickListener { finish() }
            }, 12)
            columnWithMargin(this, label(profile?.let { "Объект: ${it.name}" } ?: "Объект не выбран", 20f, WHITE, true), 20)
        }
        setContentView(ScrollView(this@AdminRulesActivity).apply { addView(layout) })
        layout
    }

    private fun renderMessage(message: String) = with(UiKit) {
        val root = root()
        columnWithMargin(root, label(message, 16f, MUTED), 18)
        if (!working) columnWithMargin(root, button("Обновить состояние", BLUE).apply {
            setOnClickListener { if (adminResumed && AdminAccess.session.unlocked) load() }
        }, 18)
    }

    private fun load() {
        if (working || !adminResumed || !AdminAccess.session.unlocked) return
        val token = ++generation
        try {
            val selected = store.selectedProfileId()
            profile = store.load().firstOrNull { it.id == selected }
        } catch (_: Exception) { renderMessage("Не удалось прочитать настройки. Данные не сбрасывались."); return }
        val p = profile ?: run { renderMessage("Сначала выберите подключение в настройках администратора."); return }
        working = true
        renderMessage("Проверяем состояние правил…")
        worker.execute {
            val result = runCatching { RouterFirewallService { AndroidRestConnection.create(applicationContext, it) }.scan(p) }
            runOnUiThread {
                working = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!valid(token)) { if (adminResumed && AdminAccess.session.unlocked) load(); return@runOnUiThread }
                result.fold({ renderGroups(it.groups, p, token) }, { renderMessage(it.message ?: "Не удалось прочитать правила") })
            }
        }
    }

    private fun renderGroups(groups: List<FirewallGroup>, p: RouterProfile, token: Int) = with(UiKit) {
        val root = root()
        columnWithMargin(root, label("Выберите группу, чтобы выключить её правила и восстановить трафик.", 15f, MUTED), 12)
        if (groups.isEmpty()) columnWithMargin(root, label("Нет доступных групп", 16f, MUTED), 18)
        for (group in groups) {
            val card = vertical().apply {
                background = bg(CARD, dp(18).toFloat(), BORDER)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            card.addView(label(group.name, 24f, WHITE, true))
            columnWithMargin(card, label("${group.state.label} · ${group.enabledCount} из ${group.rules.size}", 15f, MUTED), 10)
            val restoreButton = button("Выключить правила / восстановить трафик", GREEN).apply {
                minimumHeight = dp(64)
                isEnabled = group.enabledCount > 0 && group.dynamicCount == 0
                setOnClickListener {
                    if (!valid(token) || working) return@setOnClickListener
                    showAdminDialog(AlertDialog.Builder(this@AdminRulesActivity)
                        .setTitle("Восстановить трафик: ${group.name}?")
                        .setMessage("Объект: ${p.name}\n\nБудут выключены правила группы ${group.name}. Остальные группы не изменятся.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Восстановить") { _, _ -> if (valid(token) && !working) restore(p, group, token) })
                }
            }
            columnWithMargin(card, restoreButton, 14)
            if (group.dynamicCount > 0) columnWithMargin(card, label("Есть динамические правила: переключение запрещено", 14f, YELLOW), 8)
            columnWithMargin(root, card, 16)
        }
        columnWithMargin(root, button("Обновить состояние", BLUE).apply {
            setOnClickListener { if (valid(token) && !working) load() }
        }, 18)
    }

    private fun restore(p: RouterProfile, group: FirewallGroup, token: Int) {
        val guard = {
            if (!valid(token) || profile != p || Thread.currentThread().isInterrupted) {
                throw IOException("Доступ администратора закрыт. Новые команды не отправлены; проверьте состояние группы.")
            }
        }
        if (!valid(token) || profile != p) return
        working = true
        renderMessage("Выключаем правила ${group.name}. Ожидайте подтверждения…")
        worker.execute {
            val result = runCatching {
                guard()
                val service = RouterFirewallService { AndroidRestConnection.create(applicationContext, it, guard) }
                service.switchGroup(p, group, false) { valid(token) && profile == p }
            }
            runOnUiThread {
                working = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (valid(token)) {
                    result.fold({ Toast.makeText(this, "${group.name}: правила выключены, подтверждено оборудованием (${it.total})", Toast.LENGTH_LONG).show() },
                        { showAdminDialog(AlertDialog.Builder(this).setTitle("Проверьте состояние группы")
                            .setMessage("${it.message}\n\nВозможно частичное переключение. Выполняется повторная проверка.")
                            .setPositiveButton("ОК", null)) })
                }
                if (adminResumed && AdminAccess.session.unlocked) load()
            }
        }
    }
}
