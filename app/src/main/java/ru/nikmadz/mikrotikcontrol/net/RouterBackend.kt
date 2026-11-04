package ru.nikmadz.mikrotikcontrol.net

import ru.nikmadz.mikrotikcontrol.core.FirewallRule

/** Shared interface for REST/API and deterministic offline tests. */
interface RouterBackend {
    fun version(): String
    /** Every table must be read successfully; never return a silently partial scan. */
    fun readRules(): List<FirewallRule>
    /** A single non-transactional write. Caller verifies the result by reading back. */
    fun setEnabled(rule: FirewallRule, enabled: Boolean)
}
