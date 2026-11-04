package ru.nikmadz.mikrotikcontrol

import ru.nikmadz.mikrotikcontrol.core.FirewallGroup

/** UI diff independent of transport objects and REST row order; includes all safety metadata. */
class GroupRenderCache {
    private var previous = emptyMap<String, FirewallGroup>()
    data class Item(val group: FirewallGroup, val changed: Boolean)
    fun update(groups: List<FirewallGroup>): List<Item> {
        val normalized = groups.map { it.copy(rules = it.rules.sortedBy { rule -> rule.key }) }
        val result = normalized.map { Item(it, previous[it.name] != it) }
        previous = normalized.associateBy { it.name }
        return result
    }
}
