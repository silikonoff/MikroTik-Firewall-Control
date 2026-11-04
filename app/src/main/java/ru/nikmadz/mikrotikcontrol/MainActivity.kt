package ru.nikmadz.mikrotikcontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import ru.nikmadz.mikrotikcontrol.core.*
import ru.nikmadz.mikrotikcontrol.net.AndroidRestConnection
import ru.nikmadz.mikrotikcontrol.security.ProfileStore
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: ProfileStore
    private val worker = Executors.newSingleThreadExecutor()
    private val service = RouterFirewallService { AndroidRestConnection.create(applicationContext, it) }
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var busy = false
    private var refreshing = false
    private var connected = false
    private var confirming = false
    private var profiles = listOf<RouterProfile>()
    private var selectedId: String? = null
    private var scan: RouterFirewallService.Scan? = null
    private lateinit var menuButton: View
    private lateinit var routerName: TextView
    private var lastError: String? = null
    private lateinit var groupsContainer: LinearLayout
    private lateinit var status: TextView
    private lateinit var updated: TextView
    private data class GroupViews(val root: LinearLayout, val buttons: List<Pair<View, Boolean>>)
    private val groupViews = mutableMapOf<String, GroupViews>()
    private val renderCache = GroupRenderCache()
    private var emptyMessage: String? = null
    private var pendingAdminAction: String? = null
    private var lastCheckedAt: String? = null
    private var shortcutGroup: String? = null
    private var shortcutProfile: String? = null
    private val periodic = object : Runnable {
        override fun run() {
            if (resumed) {
                if (!busy && !refreshing && !confirming && currentProfile() != null) refresh(false)
                handler.postDelayed(this, 15_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        selectedId = savedInstanceState?.getString("selectedProfile")
        extractShortcut(intent)
        renderShell()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractShortcut(intent)
        loadProfiles()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshPinnedIcons()
        loadProfiles()
        handler.removeCallbacks(periodic)
        handler.postDelayed(periodic, 15_000L)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(periodic)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedProfile", selectedId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun extractShortcut(intent: Intent?) {
        shortcutGroup = intent?.getStringExtra("groupName")
        shortcutProfile = intent?.getStringExtra("profileId")
        intent?.removeExtra("groupName")
        intent?.removeExtra("profileId")
    }

    private fun currentProfile(): RouterProfile? = profiles.firstOrNull { it.id == selectedId }

    private fun renderShell() = with(UiKit) {
        val root = vertical().apply { setBackgroundColor(BG); setPadding(dp(20), dp(8), dp(20), dp(12)) }
        val header = brandHeader("Рабочий экран оператора")
        menuButton = button("⋮", CARD).apply {
            contentDescription = "Меню: обновление, помощь, настройки администратора"
            textSize = 24f
            setOnClickListener { showMenu(it) }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(56)))
        root.addView(header)
        val connectionCard = vertical().apply {
            background = bg(CARD, dp(18).toFloat(), BORDER)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        routerName = label("", 18f, WHITE, true)
        connectionCard.addView(routerName)
        status = label("Проверяем связь…", 14f, YELLOW).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        columnWithMargin(connectionCard, status, 6)
        columnWithMargin(root, connectionCard, 12)
        val scroll = ScrollView(this@MainActivity).apply { isFillViewport = false }
        groupsContainer = vertical()
        scroll.addView(groupsContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
        updated = label("Состояние проверяется автоматически", 12f, MUTED)
        columnWithMargin(root, updated, 8)
        setContentView(root)
    }

    private fun showMenu(anchor: View) {
        if (busy || confirming) return
        PopupMenu(this, anchor).apply {
            menu.add(0, 2, 0, "Обновить состояние")
            menu.add(0, 6, 1, "Помощь оператору")
            menu.add(0, 1, 2, "Настройки администратора")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(this@MainActivity, ConnectionsActivity::class.java), 100)
                    }
                    2 -> refresh(true)
                    6 -> AlertDialog.Builder(this@MainActivity).setTitle("Как управлять группами")
                        .setMessage("1. Убедитесь, что связь установлена.\n\n2. Найдите нужную группу и нажмите «Остановить трафик». Выключение правил и восстановление трафика доступны только в настройках администратора по PIN.\n\n3. Проверьте название в подтверждении и дождитесь результата.\n\nНет связи или группа недоступна? Проверьте кабель и обратитесь к администратору.\n\nПриложение показывает состояние правил на оборудовании. Фактическое прохождение трафика зависит от его настройки.")
                        .setPositiveButton("Понятно", null).show()
                }
                true
            }
            show()
        }
    }

    @Deprecated("Platform callback retained for the existing Activity architecture")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            pendingAdminAction = data?.getStringExtra("adminAction")
        }
    }

    private fun runAdminAction() {
        val action = pendingAdminAction ?: return
        pendingAdminAction = null
        when (action) {
            "groups" -> chooseGroup(false)
            "shortcuts" -> chooseGroup(true)
            "diagnostics" -> {
                val p = currentProfile()
                showError(if (p == null) "Подключение не настроено" else
                    "${p.name} · ${p.host}:${p.endpointPort} · ${p.transportLabel}\n" +
                    "${p.connectionRoute.description}\nRouterOS: ${scan?.version ?: "не определена"}\n" +
                    "Последняя успешная проверка: ${lastCheckedAt ?: "нет"}\n\n" +
                    (lastError ?: "Ошибок подключения нет"), "Диагностика подключения")
            }
        }
    }

    private fun chooseGroup(shortcut: Boolean) {
        val groups = scan?.groups.orEmpty()
        if (groups.isEmpty()) { showError("Нет доступных групп"); return }
        AlertDialog.Builder(this).setTitle(if (shortcut) "Ярлык группы" else "Состав группы")
            .setItems(groups.map { it.name }.toTypedArray()) { _, index ->
                if (shortcut) addShortcut(groups[index]) else showGroupDetails(groups[index])
            }.show()
    }

    private fun loadProfiles() {
        if (busy) return
        val previousProfile = currentProfile()
        try {
            profiles = store.load()
            selectedId = if (shortcutProfile != null && profiles.any { it.id == shortcutProfile }) {
                shortcutProfile
            } else store.selectedProfileId()
        }
        catch (e: Exception) {
            profiles = emptyList()
            selectedId = null
            routerName.text = ""
            scan = null
            connected = false
            renderGroups(emptyList())
            status.text = "Ошибка чтения защищённого хранилища"
            lastError = "Не удалось прочитать защищённые настройки. Данные не сбрасывались."
            updated.text = "Требуется помощь администратора"
            runAdminAction()
            return
        }
        routerName.text = currentProfile()?.let { "Объект: ${it.name}" } ?: "Объект не выбран"
        if (previousProfile != currentProfile()) {
            lastError = null
            scan = null
            connected = false
            lastCheckedAt = null
            updated.text = "Состояние ещё не проверено"
            renderGroups(emptyList())
        }
        if (profiles.isEmpty()) {
            status.text = "Требуется настройка администратором"
            scan = null
            renderGroups(emptyList())
            runAdminAction()
        } else {
            refresh(false)
        }
    }

    private fun refresh(showErrors: Boolean) {
        val p = currentProfile() ?: return
        if (busy || refreshing || confirming) return
        refreshing = true
        if (scan == null && lastError == null) {
            status.setTextColor(UiKit.YELLOW)
            status.text = "Проверяем связь…"
            renderGroups(emptyList())
        }
        worker.execute {
            try {
                val info = service.scan(p)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshing = false
                    if (busy) return@runOnUiThread // A write queued after this read owns the UI now.
                    if (currentProfile() != p) { if (resumed) refresh(false); return@runOnUiThread }
                    scan = info
                    connected = true
                    lastError = null
                    status.text = "●  Связь установлена"
                    status.setTextColor(UiKit.GREEN)
                    lastCheckedAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    updated.text = getString(R.string.last_checked, lastCheckedAt)
                    renderGroups(info.groups)
                    runAdminAction()
                    val target = shortcutGroup
                    if (resumed && shortcutProfile == p.id && target != null) {
                        shortcutGroup = null; shortcutProfile = null
                        info.groups.firstOrNull { it.name == target }?.let { if (it.state != GroupState.ALL_ENABLED) confirmToggle(it, true) else showError("Правила уже включены. Выключение доступно только в настройках администратора.") }
                            ?: AlertDialog.Builder(this).setMessage("Группа $target на роутере не найдена")
                                .setPositiveButton("ОК", null).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshing = false
                    if (busy) return@runOnUiThread
                    if (currentProfile() != p) { if (resumed) refresh(false); return@runOnUiThread }
                    lastError = e.message ?: "Ошибка подключения"
                    connected = false
                    status.text = "!  Нет связи с оборудованием"
                    status.setTextColor(UiKit.RED)
                    updated.text = if (scan == null) "Состояние неизвестно · действия недоступны"
                        else "Данные последней проверки ($lastCheckedAt) · действия недоступны"
                    renderGroups(scan?.groups.orEmpty())
                    if (showErrors) showError("Проверьте кабель или подключение Wi-Fi. Если связь не появилась, обратитесь к администратору. Технические сведения доступны в настройках → Диагностика.", "Нет связи")
                    runAdminAction()
                }
            }
        }
    }

    private fun renderGroups(groups: List<FirewallGroup>) = with(UiKit) {
        if (groups.isEmpty()) {
            val message =
                if (profiles.isEmpty()) "Передайте смартфон администратору для настройки подключения."
                else if (lastError != null) "Проверьте кабель и подключение к оборудованию. После восстановления связи группы появятся автоматически."
                else if (refreshing || busy) "Загружаем группы…"
                else "Группы пока не настроены. Обратитесь к администратору."
            if (emptyMessage != message) {
                groupsContainer.removeAllViews()
                groupViews.clear()
                renderCache.update(emptyList())
                emptyMessage = message
                columnWithMargin(groupsContainer, label(message, 16f, MUTED), 18)
            }
            return@with
        }
        if (emptyMessage != null || groupsContainer.childCount == 0) {
            groupsContainer.removeAllViews()
            emptyMessage = null
            columnWithMargin(groupsContainer, label("Группы управления", 19f, WHITE, true), 0, 12)
        }
        val items = renderCache.update(groups)
        val names = items.map { it.group.name }.toSet()
        groupViews.keys.filter { it !in names }.forEach { name ->
            groupViews.remove(name)?.let { groupsContainer.removeView(it.root) }
        }
        for ((index, item) in items.withIndex()) {
            val group = item.group
            val existing = groupViews[group.name]
            if (!item.changed && existing != null) {
                if (groupsContainer.getChildAt(index + 1) !== existing.root) {
                    groupsContainer.removeView(existing.root)
                    groupsContainer.addView(existing.root, index + 1)
                }
                continue
            }
            val actions = mutableListOf<Pair<View, Boolean>>()
            val card = vertical().apply {
                background = bg(CARD, dp(18).toFloat(), BORDER)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            val titleLine = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            titleLine.addView(label(group.name, 24f, WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            val stateColor = when (group.state) {
                GroupState.ALL_ENABLED -> RED
                GroupState.ALL_DISABLED -> GREEN
                GroupState.MIXED -> YELLOW
            }
            card.addView(titleLine)
            val stateText = when (group.state) {
                GroupState.ALL_ENABLED -> "■  Остановка включена"
                GroupState.ALL_DISABLED -> "✓  Остановка выключена"
                GroupState.MIXED -> "!  Группа включена частично"
            }
            val pale = when (group.state) {
                GroupState.ALL_ENABLED -> PALE_RED
                GroupState.ALL_DISABLED -> PALE_GREEN
                GroupState.MIXED -> PALE_YELLOW
            }
            columnWithMargin(card, label(stateText, 15f, stateColor, true).apply {
                background = bg(pale, dp(10).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }, 12)
            val description = group.rules.mapNotNull { it.comment.substringAfter('|', "").trim().takeIf(String::isNotEmpty) }.distinct()
            if (description.size == 1) columnWithMargin(card, label(description.single(), 15f, MUTED), 10)
            val main = button(
                if (group.state == GroupState.ALL_ENABLED) "Остановка включена" else "Остановить трафик",
                if (group.state == GroupState.ALL_ENABLED) CARD else RED
            ).apply {
                minimumHeight = dp(64)
                textSize = 18f
                contentDescription = "$text, группа ${group.name}"
            }
            actions += main to (group.state != GroupState.ALL_ENABLED && group.dynamicCount == 0 && group.invalidCount == 0)
            if (group.dynamicCount > 0) {
                columnWithMargin(card, label("Изменение недоступно. Обратитесь к администратору.", 14f, YELLOW), 8)
            }
            if (group.invalidCount > 0) {
                columnWithMargin(card, label("Включение недоступно: группа требует проверки администратором.", 14f, YELLOW), 8)
            }
            columnWithMargin(card, main, 16)
            main.setOnClickListener {
                if (connected && !busy && !confirming) confirmToggle(group, true)
            }
            columnWithMargin(card, label("Выключение правил — в настройках администратора", 13f, MUTED), 8)
            existing?.let { groupsContainer.removeView(it.root) }
            groupsContainer.addView(card, index + 1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            groupViews[group.name] = GroupViews(card, actions)
        }
        updateActions()
    }

    private fun updateActions() {
        groupViews.values.forEach { card -> card.buttons.forEach { (view, allowed) ->
            view.isEnabled = connected && !busy && allowed
        } }
    }

    private fun showGroupDetails(group: FirewallGroup) {
        val description = group.rules.joinToString("\n\n") { rule ->
            "${if (rule.disabled) "○ выкл" else "● вкл"}  ${rule.table.label} / ${rule.chain} " +
                "· action=${rule.action}\nID: ${rule.id}\n${rule.comment}"
        }
        AlertDialog.Builder(this).setTitle("APP_STOP_${group.name} (${group.rules.size})")
            .setMessage(description).setPositiveButton("Закрыть", null).show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        menuButton.isEnabled = !value
        updateActions()
    }

    private fun confirmToggle(group: FirewallGroup, enable: Boolean) {
        if (!enable || busy || confirming || !resumed || !connected) return
        val p = currentProfile() ?: return
        if (group.dynamicCount > 0 || (enable && group.invalidCount > 0)) {
            showError("Эта группа сейчас недоступна для выбранного действия. Обратитесь к администратору.")
            return
        }
        val nonBlocking = group.rules.count { it.action.lowercase(java.util.Locale.ROOT) !in setOf("drop", "reject") }
        val warning = if (enable && nonBlocking > 0)
            "\n\nВ этой группе есть правила, которые не блокируют трафик. Уточните назначение группы у администратора."
        else ""
        val risk = if (enable && group.rules.any { it.chain.equals("input", true) })
            "\n\nВнимание: действие может отключить связь с оборудованием. Автоматического восстановления нет."
        else ""
        confirming = true
        AlertDialog.Builder(this)
            .setTitle(if (enable) "Остановить трафик: ${group.name}?" else "Восстановить трафик: ${group.name}?")
            .setMessage("Объект: ${p.name}\nГруппа: ${group.name}\n\n" +
                if (enable) "Будет включена остановка для этой группы. Остальные группы не изменятся.$warning$risk"
                else "Остановка для этой группы будет выключена. Остальные группы не изменятся.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton(if (enable) "Остановить" else "Восстановить") { _, _ ->
                if (connected && currentProfile() == p) switchGroup(p, group, enable)
            }
            .setOnDismissListener { confirming = false }
            .show()
    }

    private fun switchGroup(p: RouterProfile, group: FirewallGroup, enable: Boolean) {
        if (!enable || busy) return
        setBusy(true)
        status.text = getString(R.string.switching_group, group.name)
        status.setTextColor(UiKit.YELLOW)
        worker.execute {
            try {
                val result = service.switchGroup(p, group, enable)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    connected = false // Keep cards, but require the follow-up scan before another command.
                    setBusy(false)
                    Toast.makeText(this, "${group.name}: ${if (enable) "остановка включена" else "остановка выключена"}. Подтверждено оборудованием (${result.total}).", Toast.LENGTH_LONG).show()
                    if (resumed) refresh(true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    lastError = e.message ?: "Операция не подтверждена"
                    connected = false
                    setBusy(false)
                    status.text = "●  Состояние группы требует повторной проверки"
                    status.setTextColor(UiKit.YELLOW)
                    updated.text = "Результат команды пока не подтверждён"
                    if (resumed) {
                        showError("Оборудование не подтвердило нужное состояние. Часть группы могла переключиться. Дождитесь обновления и проверьте карточку. Если ошибка повторяется, обратитесь к администратору.", "Проверьте состояние группы")
                        refresh(false)
                    }
                }
            }
        }
    }

    private fun addShortcut(group: FirewallGroup) {
        val p = currentProfile() ?: return
        val manager = getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) {
            Toast.makeText(this, "Лаунчер не поддерживает ярлыки", Toast.LENGTH_LONG).show()
            return
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("profileId", p.id)
            putExtra("groupName", group.name)
            data = android.net.Uri.parse("mikrotik-control://group/${p.id}/${group.name}")
        }
        val info = ShortcutInfo.Builder(this, "${p.id}_${group.name}")
            .setShortLabel("STOP ${group.name}".take(25))
            .setLongLabel("Управление ${group.name} — ${p.name}")
            .setIcon(Icon.createWithResource(this, R.drawable.ic_firewall))
            .setIntent(openIntent)
            .build()
        manager.requestPinShortcut(info, null)
        Toast.makeText(this, "Подтвердите добавление ярлыка на экран", Toast.LENGTH_SHORT).show()
    }

    private fun refreshPinnedIcons() {
        // Refresh only our mutable pinned shortcuts; keep their names, intents and profile IDs.
        val metadata = getSharedPreferences("launcher_metadata", MODE_PRIVATE)
        if (metadata.getBoolean("mst_icons_v1", false)) return
        runCatching {
            val manager = getSystemService(ShortcutManager::class.java) ?: return
            val updates = manager.pinnedShortcuts.filterNot { it.isImmutable }.map {
                ShortcutInfo.Builder(this, it.id).setIcon(Icon.createWithResource(this, R.drawable.ic_firewall)).build()
            }
            if (updates.isEmpty() || manager.updateShortcuts(updates)) {
                metadata.edit().putBoolean("mst_icons_v1", true).apply()
            }
        } // A launcher restriction must never prevent startup or firewall control.
    }

    private fun showError(text: String, title: String = "MST") {
        if (resumed && !isFinishing && !isDestroyed) AlertDialog.Builder(this)
            .setTitle(title).setMessage(text)
            .setPositiveButton("ОК", null).show()
    }
}
