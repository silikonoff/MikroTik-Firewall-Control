package ru.nikmadz.mikrotikcontrol.core

import ru.nikmadz.mikrotikcontrol.net.RouterBackend
import java.io.IOException

class RouterFirewallService(private val backendFor: (RouterProfile) -> RouterBackend) {
    data class Scan(val version: String, val groups: List<FirewallGroup>)
    data class Result(val changed: Int, val total: Int, val state: GroupState)

    private fun connect(profile: RouterProfile): Pair<RouterBackend, String> {
        ProfileValidation.requireValid(profile)
        val backend = backendFor(profile)
        val version = backend.version()
        RouterOs.requireVersion7(version)
        return backend to version
    }

    fun scan(profile: RouterProfile): Scan {
        val (backend, version) = connect(profile)
        return Scan(version, FirewallGroups.assemble(backend.readRules()))
    }

    private fun findGroup(rules: List<FirewallRule>, groupName: String): FirewallGroup =
        FirewallGroups.assemble(rules).singleOrNull { it.name == groupName }
            ?: throw IOException("Группа $groupName не найдена. Обновите список.")

    /** Preflight is strict. Post-write invalid is runtime status, checked separately below. */
    private fun sameDefinition(a: FirewallGroup, b: FirewallGroup, compareInvalid: Boolean = true): Boolean {
        fun normalized(g: FirewallGroup) = g.rules.map {
            it.copy(disabled = false, invalid = compareInvalid && it.invalid)
        }.toSet()
        return a.rules.size == b.rules.size && normalized(a) == normalized(b)
    }

    /**
     * Always set an explicit desired state; never blindly toggle a cached state.
     * RouterOS has no transaction here. Stop on the first failed write, do not retry or roll back.
     */
    fun switchGroup(profile: RouterProfile, expected: FirewallGroup, enable: Boolean,
                    allowDisable: () -> Boolean = { false }): Result {
        fun authorize() {
            if (!enable && !allowDisable()) throw IOException("Выключение правил доступно только администратору")
        }
        authorize()
        val (backend, _) = connect(profile)
        val group = findGroup(backend.readRules(), expected.name)
        if (!sameDefinition(expected, group)) {
            throw IOException("Состав или параметры группы изменились после показа. Обновите список и подтвердите заново.")
        }
        if (group.rules.any { it.dynamic }) {
            throw IOException("Группа содержит динамические правила RouterOS: переключение запрещено")
        }
        if (enable && group.rules.any { it.invalid }) {
            throw IOException("В группе есть invalid-правила. Сначала исправьте их на MikroTik; выключение разрешено.")
        }
        if (group.rules.any { !FirewallGroups.safeId(it.id) } ||
            group.rules.map { it.key }.distinct().size != group.rules.size) {
            throw IOException("Некорректные или повторяющиеся ID правил: переключение отменено")
        }
        val changes = group.rules.filter { it.disabled == enable }
        var acknowledged = 0
        for (rule in changes) {
            try {
                authorize()
                backend.setEnabled(rule, enable)
                acknowledged++
            } catch (e: Exception) {
                throw IOException(
                    "Получено подтверждений: $acknowledged из ${changes.size}. " +
                    "Ответ для ${rule.table.label} ${rule.id} не получен или содержит ошибку; " +
                    "его состояние неизвестно. Остальные команды не отправлены. ${e.message.orEmpty()}", e
                )
            }
        }
        // RouterOS can briefly expose stale disabled or transient invalid after a write.
        // Poll only successful reads; never replay writes or retry a transport/read failure.
        repeat(4) { attempt ->
            if (attempt > 0) Thread.sleep(250L)
            val after = try {
                findGroup(backend.readRules(), expected.name)
            } catch (e: Exception) {
                throw IOException("Команды подтверждены ($acknowledged), но проверка состояния не удалась. ${e.message.orEmpty()}", e)
            }
            if (!sameDefinition(group, after, compareInvalid = false)) {
                throw IOException("Проверка не пройдена: состав или параметры группы изменились. Обновите список.")
            }
            if (after.rules.all { it.disabled != enable } && (!enable || after.invalidCount == 0)) {
                return Result(acknowledged, after.rules.size, after.state)
            }
        }
        throw IOException("Проверка не пройдена: после повторного чтения не все правила имеют заданное состояние либо остаются invalid. Команды повторно не отправлялись.")
    }
}
