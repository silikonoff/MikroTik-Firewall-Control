package ru.nikmadz.mikrotikcontrol

import org.junit.Assert.*
import org.junit.Test
import ru.nikmadz.mikrotikcontrol.core.*

class GroupRenderCacheTest {
    private val rule = FirewallRule("*1", FirewallTable.FILTER, "APP_STOP_TEST", true, "drop", "forward")
    private fun group(vararg rules: FirewallRule) = FirewallGroup("TEST", rules.toList())

    @Test fun identicalResponsesDoNotReplaceCards() {
        val cache = GroupRenderCache()
        assertTrue(cache.update(listOf(group(rule))).single().changed)
        repeat(20) { assertFalse(cache.update(listOf(group(rule.copy()))).single().changed) }
    }
    @Test fun restRowOrderDoesNotReplaceCard() {
        val cache = GroupRenderCache()
        val other = rule.copy(id = "*2")
        cache.update(listOf(group(rule, other)))
        assertFalse(cache.update(listOf(group(other, rule))).single().changed)
    }
    @Test fun onlyChangedGroupIsReplaced() {
        val cache = GroupRenderCache()
        val vpn = FirewallGroup("VPN", listOf(rule.copy(id = "*2", comment = "APP_STOP_VPN")))
        cache.update(listOf(group(rule), vpn))
        val next = cache.update(listOf(group(rule.copy(disabled = false)), vpn))
        assertTrue(next[0].changed)
        assertFalse(next[1].changed)
    }
    @Test fun safetyMetadataChangesAreNotHiddenByEqualGroupState() {
        for (changed in listOf(rule.copy(invalid = true), rule.copy(dynamic = true),
            rule.copy(action = "accept"), rule.copy(id = "*9"), rule.copy(chain = "input"),
            rule.copy(comment = "APP_STOP_TEST | changed"), rule.copy(table = FirewallTable.RAW))) {
            val cache = GroupRenderCache()
            cache.update(listOf(group(rule)))
            assertTrue(cache.update(listOf(group(changed))).single().changed)
        }
    }
    @Test fun removedThenRestoredGroupNeedsNewCard() {
        val cache = GroupRenderCache()
        cache.update(listOf(group(rule)))
        assertTrue(cache.update(emptyList()).isEmpty())
        assertTrue(cache.update(listOf(group(rule))).single().changed)
    }
    @Test fun membershipChangeDetectedEvenWithSameCountsAndState() {
        val cache = GroupRenderCache()
        cache.update(listOf(group(rule, rule.copy(id = "*2"))))
        assertTrue(cache.update(listOf(group(rule, rule.copy(id = "*3")))).single().changed)
    }
    @Test fun reorderingGroupsKeepsExistingCards() {
        val cache = GroupRenderCache()
        val vpn = FirewallGroup("VPN", listOf(rule.copy(comment = "APP_STOP_VPN")))
        cache.update(listOf(group(rule), vpn))
        assertTrue(cache.update(listOf(vpn, group(rule))).none { it.changed })
    }
}
