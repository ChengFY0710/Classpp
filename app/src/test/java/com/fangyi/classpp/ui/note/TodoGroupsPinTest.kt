package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.model.TodoGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分组置顶/重排纯函数的契约：
 *
 * - **置顶前缀不变式**（[applyPin] 的核心职责）：pinned 项恒为列表连续前缀——新置顶项落前缀
 *   尾部（同批保持原相对序，前缀整体顺序即置顶操作顺序），取消置顶项按原相对序落回非置顶区。
 *   GroupCard 的锁序（置顶项不可拖、落点排除置顶区）全靠这条不变式成立；
 * - [pinAction] 四态判定：空选择 / 混合选择 = Disabled，全未置顶 = Pin，全已置顶 = Unpin；
 * - [move]：GroupCard onMove 契约的承载端，越界与原地为无操作。
 *
 * 断言直接引用 TodoGroupsSheet.kt 的 internal 纯函数，调参时这里同步生效。
 */
class TodoGroupsPinTest {

    /** A、B 已置顶，C、D 未置顶（合法前缀形态） */
    private val list = listOf(
        TodoGroup("A", pinned = true),
        TodoGroup("B", pinned = true),
        TodoGroup("C"),
        TodoGroup("D"),
    )

    private fun names(groups: List<TodoGroup>) = groups.map { it.name }

    /** 前缀不变式：一旦出现未置顶项，其后不得再有置顶项 */
    private fun assertPrefixInvariant(groups: List<TodoGroup>) {
        val firstUnpinned = groups.indexOfFirst { !it.pinned }
        if (firstUnpinned < 0) return
        groups.forEachIndexed { index, group ->
            if (index > firstUnpinned) {
                assertEquals(
                    "前缀不变式被打破：${group.name} 出现在非置顶区之后",
                    false,
                    group.pinned,
                )
            }
        }
    }

    /** 置顶：新置顶项落前缀尾部，未置顶项保持相对序（同批置顶也保持原相对序） */
    @Test
    fun pinAppendsToPrefixTailKeepingRelativeOrder() {
        // 单个置顶 C：前缀 A、B 不动，C 接在前缀尾部，D 保持在未置顶区
        val pinnedC = applyPin(list, setOf("C"), pinned = true)
        assertEquals(listOf("A", "B", "C", "D"), names(pinnedC))
        assertEquals(listOf(true, true, true, false), pinnedC.map { it.pinned })
        assertPrefixInvariant(pinnedC)

        // 同批置顶 D、C（选中集无序）：按原相对序 C < D 落入前缀尾部
        val pinnedBoth = applyPin(list, setOf("C", "D"), pinned = true)
        assertEquals(listOf("A", "B", "C", "D"), names(pinnedBoth))
        assertPrefixInvariant(pinnedBoth)

        // 全未置顶列表置顶任意项：前缀从空开始构建
        val unpinned = listOf(TodoGroup("A"), TodoGroup("B"), TodoGroup("C"))
        val rebuilt = applyPin(unpinned, setOf("C"), pinned = true)
        assertEquals(listOf("C", "A", "B"), names(rebuilt))
        assertPrefixInvariant(rebuilt)
    }

    /** 取消置顶：按原相对序落回非置顶区，剩余置顶项仍构成前缀 */
    @Test
    fun unpinFallsBackKeepingRelativeOrder() {
        // 取消 B（前缀中部）：B 落在非置顶区头部（C、D 之前），A 仍是前缀
        val unpinnedB = applyPin(list, setOf("B"), pinned = false)
        assertEquals(listOf("A", "B", "C", "D"), names(unpinnedB))
        assertEquals(listOf(true, false, false, false), unpinnedB.map { it.pinned })
        assertPrefixInvariant(unpinnedB)

        // 取消前缀头部 A：剩余 B 顶上成为前缀，A 按原相对序落在 B 之后、C 之前
        val unpinnedA = applyPin(list, setOf("A"), pinned = false)
        assertEquals(listOf("B", "A", "C", "D"), names(unpinnedA))
        assertPrefixInvariant(unpinnedA)

        // 全部取消：前缀清空，整体保持原相对序
        val allCleared = applyPin(list, setOf("A", "B"), pinned = false)
        assertEquals(listOf("A", "B", "C", "D"), names(allCleared))
        assertPrefixInvariant(allCleared)
    }

    /** 幂等：对已是目标状态的列表重复执行，结果不变 */
    @Test
    fun applyPinIsIdempotent() {
        assertEquals(list, applyPin(list, setOf("A", "B"), pinned = true))
        val unpinned = listOf(TodoGroup("C"), TodoGroup("D"))
        assertEquals(unpinned, applyPin(unpinned, setOf("C"), pinned = false))
    }

    /** pinAction 四态：空选择 / 混合选择置灰，全未置顶可置顶，全已置顶可取消 */
    @Test
    fun pinActionCoversFourStates() {
        assertEquals(PinAction.Disabled, pinAction(list, emptySet()))
        // 混合：选中已置顶 B 与未置顶 C
        assertEquals(PinAction.Disabled, pinAction(list, setOf("B", "C")))
        assertEquals(PinAction.Disabled, pinAction(list, setOf("A", "C", "D")))
        // 只选已置顶 → 取消置顶
        assertEquals(PinAction.Unpin, pinAction(list, setOf("A")))
        assertEquals(PinAction.Unpin, pinAction(list, setOf("A", "B")))
        // 只选未置顶 → 置顶
        assertEquals(PinAction.Pin, pinAction(list, setOf("C")))
        assertEquals(PinAction.Pin, pinAction(list, setOf("C", "D")))
    }

    /** move：显示顺序重排（GroupCard onMove 的承载端），越界与原地为无操作 */
    @Test
    fun moveReordersAndIgnoresInvalidIndices() {
        assertEquals(listOf("B", "C", "A", "D"), names(list.move(0, 2)))
        assertEquals(listOf("D", "A", "B", "C"), names(list.move(3, 0)))
        assertEquals(list, list.move(1, 1))
        assertEquals(list, list.move(-1, 2))
        assertEquals(list, list.move(0, 4))
        assertEquals(list, list.move(2, 99))
    }
}
