package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.model.TodoGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分组页排序规则：页签键映射、覆盖/回落、重命名/删除维护、prefs 编解码 */
class TodoSortRuleTest {

    private val groups = listOf(TodoGroup("作业"), TodoGroup("日常琐事"))
    private val fallback = TodoSortRule(TodoSort.Urgency, descending = false)

    @Test
    fun `page keys map all ungrouped and groups`() {
        assertEquals(AllSortRuleKey, sortRuleKey(0, groups))
        assertEquals(UngroupedSortRuleKey, sortRuleKey(1, groups))
        assertEquals(groupSortRuleKey("作业"), sortRuleKey(2, groups))
        assertEquals(groupSortRuleKey("日常琐事"), sortRuleKey(3, groups))
    }

    @Test
    fun `out of range page has no key`() {
        assertNull(sortRuleKey(4, groups))
        assertNull(sortRuleKey(-1, groups))
    }

    @Test
    fun `group names colliding with sentinels stay distinct`() {
        val colliding = listOf(TodoGroup("all"), TodoGroup("ungrouped"))
        assertEquals("group:all", sortRuleKey(2, colliding))
        assertEquals("group:ungrouped", sortRuleKey(3, colliding))
        assertTrue(AllSortRuleKey != sortRuleKey(2, colliding))
        assertTrue(UngroupedSortRuleKey != sortRuleKey(3, colliding))
    }

    @Test
    fun `rule for page prefers override and falls back to global`() {
        val rules = mapOf(groupSortRuleKey("作业") to TodoSortRule(TodoSort.Name, true))
        assertEquals(TodoSortRule(TodoSort.Name, true), rules.sortRuleFor(2, groups, fallback))
        assertEquals(fallback, rules.sortRuleFor(0, groups, fallback))
        assertEquals(fallback, rules.sortRuleFor(3, groups, fallback))
        assertEquals(fallback, rules.sortRuleFor(9, groups, fallback))
    }

    @Test
    fun `remap moves renamed group and drops deleted group`() {
        val rules = mapOf(
            AllSortRuleKey to TodoSortRule(TodoSort.Created, true),
            groupSortRuleKey("作业") to TodoSortRule(TodoSort.Urgency, false),
            groupSortRuleKey("旧组") to TodoSortRule(TodoSort.Name, true),
        )
        val remapped = remapSortRules(
            rules = rules,
            renames = mapOf("作业" to "课程作业"),
            removedNames = setOf("旧组"),
        )
        assertEquals(
            mapOf(
                AllSortRuleKey to TodoSortRule(TodoSort.Created, true),
                groupSortRuleKey("课程作业") to TodoSortRule(TodoSort.Urgency, false),
            ),
            remapped,
        )
    }

    @Test
    fun `remap without changes keeps the same map`() {
        val rules = mapOf(groupSortRuleKey("作业") to TodoSortRule(TodoSort.Name, false))
        assertSame(rules, remapSortRules(rules, emptyMap(), emptySet()))
    }

    @Test
    fun `renamed rule wins over stale target rule`() {
        val rules = mapOf(
            groupSortRuleKey("A") to TodoSortRule(TodoSort.Urgency, false),
            groupSortRuleKey("B") to TodoSortRule(TodoSort.Name, true),
        )
        val remapped = remapSortRules(rules, mapOf("A" to "B"), emptySet())
        assertEquals(
            mapOf(groupSortRuleKey("B") to TodoSortRule(TodoSort.Urgency, false)),
            remapped,
        )
    }

    @Test
    fun `encode decode round trip`() {
        val rules = mapOf(
            AllSortRuleKey to TodoSortRule(TodoSort.Deadline, false),
            groupSortRuleKey("作业:数学,一") to TodoSortRule(TodoSort.Urgency, true),
        )
        assertEquals(rules, decodeSortRules(encodeSortRules(rules)))
    }

    @Test
    fun `unknown sort name falls back to deadline`() {
        val decoded = decodeSortRules("""{"all":{"sort":"FutureSort","descending":false}}""")
        assertEquals(mapOf(AllSortRuleKey to TodoSortRule(TodoSort.Deadline, false)), decoded)
    }

    @Test
    fun `missing or corrupt record decodes to empty map`() {
        assertTrue(decodeSortRules(null).isEmpty())
        assertTrue(decodeSortRules("").isEmpty())
        assertTrue(decodeSortRules("not json").isEmpty())
    }
}
