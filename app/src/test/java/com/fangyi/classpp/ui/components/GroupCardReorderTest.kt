package com.fangyi.classpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分组卡拖拽排序的落位几何：
 *
 * - **落位预览 == 提交后的布局**（核心不变式）：拖拽中的画面由「各行静态顶 + [groupCardShift]
 *   让位位移」与「浮层落在 [groupCardLandingTop]」拼成，松手提交后调用方重排 items、位移归零。
 *   两者必须逐像素相等，否则落位瞬间会跳一下——下面用「把 from 挪到 target 后的新顺序」独立
 *   算一遍提交后各行的位置，与预览逐行比对；
 * - 均高与变高（含行距）两种列表都成立：变高列表向下拖时落点要补上两者高度差，才不会留缝或压住邻行；
 * - [groupCardTarget] 的槽位翻转点：相邻两槽的中点即为翻转点，且随浮层中心单调不减；
 * - **置顶锁序**：落点下限（[groupCardTarget] 的 minTargetIndex）把置顶前缀排除在候选之外，
 *   目标钳在置顶区之后时置顶行让位恒 0——普通行怎么拖都动不了置顶区。
 *
 * 断言直接引用 GroupCard.kt 里的 internal 纯函数，调参时这里同步生效。
 */
class GroupCardReorderTest {

    /** 均高列表：4 行 × 60px，无缝堆叠 */
    private val uniformTops = listOf(0, 60, 120, 180)
    private val uniformHeights = listOf(60, 60, 60, 60)

    /** 变高列表：60 / 40 / 52 / 80，行距 12px */
    private val mixedHeights = listOf(60, 40, 52, 80)
    private val mixedSpacing = 12
    private val mixedTops = mixedHeights.runningFold(0) { y, height -> y + height + mixedSpacing }
        .dropLast(1)

    /** 把 from 挪到 target 之后的新显示顺序（与调用方 `add(to, removeAt(from))` 同义） */
    private fun reordered(size: Int, from: Int, target: Int): List<Int> =
        (0 until size).filter { it != from }.toMutableList().apply { add(target, from) }

    /** 独立算一遍「提交后」各行的顶：新顺序依次铺开 */
    private fun committedTops(heights: List<Int>, from: Int, target: Int, spacingPx: Int): List<Int> {
        val result = MutableList(heights.size) { 0 }
        var y = 0
        reordered(heights.size, from, target).forEach { item ->
            result[item] = y
            y += heights[item] + spacingPx
        }
        return result
    }

    /**
     * 核心不变式：落位预览与提交后的布局逐行相等——除被拖条目本体（它留在原位、由浮层顶上去，
     * 落点单独断言）外，各行「静态顶 + 让位位移」都落在提交后该条目的位置上。
     */
    private fun assertPreviewMatchesCommitted(
        tops: List<Int>,
        heights: List<Int>,
        from: Int,
        target: Int,
        spacingPx: Int,
    ) {
        val committed = committedTops(heights, from, target, spacingPx)
        heights.indices.forEach { index ->
            if (index == from) return@forEach
            val shift = groupCardShift(index, from, target, heights[from], spacingPx)
            assertEquals(
                "变高/均高列表 from=$from target=$target 第 $index 行错位",
                committed[index].toFloat(),
                (tops[index] + shift).toFloat(),
                0.001f,
            )
        }
        // 被拖条目本体原地不动，浮层落点才是提交后该条目的位置
        assertEquals(
            "from=$from target=$target 被拖条目本体不该挪窝",
            tops[from].toFloat(),
            (tops[from] + groupCardShift(from, from, target, heights[from], spacingPx)).toFloat(),
            0.001f,
        )
        assertEquals(
            "from=$from target=$target 浮层落点与提交后不符",
            committed[from].toFloat(),
            groupCardLandingTop(tops, heights, from, target).toFloat(),
            0.001f,
        )
    }

    /** 均高列表：所有 from/target 组合的落位预览都与提交后一致 */
    @Test
    fun uniformListPreviewMatchesCommittedForEveryMove() {
        val size = uniformHeights.size
        for (from in 0 until size) {
            for (target in 0 until size) {
                assertPreviewMatchesCommitted(uniformTops, uniformHeights, from, target, 0)
            }
        }
    }

    /** 变高列表 + 行距：同样逐组合成立（落点补高度差，不留缝也不压邻行） */
    @Test
    fun mixedHeightListPreviewMatchesCommittedForEveryMove() {
        val size = mixedHeights.size
        for (from in 0 until size) {
            for (target in 0 until size) {
                assertPreviewMatchesCommitted(mixedTops, mixedHeights, from, target, mixedSpacing)
            }
        }
    }

    /** 均高列表的具体取值：向下拖时上方各行让位一格，浮层落在目标槽的静态顶 */
    @Test
    fun uniformShiftAndLandingTakeExplicitValues() {
        // 第 0 行拖到第 2 槽：1、2 行各上移 60，浮层落到 tops[2]
        assertEquals(-60, groupCardShift(1, from = 0, target = 2, draggedHeight = 60, spacingPx = 0))
        assertEquals(-60, groupCardShift(2, from = 0, target = 2, draggedHeight = 60, spacingPx = 0))
        assertEquals(0, groupCardShift(3, from = 0, target = 2, draggedHeight = 60, spacingPx = 0))
        assertEquals(120, groupCardLandingTop(uniformTops, uniformHeights, from = 0, target = 2))
        // 第 3 行拖到第 0 槽：0、1、2 行各下移 60，浮层落到 tops[0]
        assertEquals(60, groupCardShift(0, from = 3, target = 0, draggedHeight = 60, spacingPx = 0))
        assertEquals(60, groupCardShift(2, from = 3, target = 0, draggedHeight = 60, spacingPx = 0))
        assertEquals(0, groupCardLandingTop(uniformTops, uniformHeights, from = 3, target = 0))
    }

    /** 变高列表：向下拖时落点补上「目标行高 − 被拖行高」，仍是提交后该行的位置 */
    @Test
    fun landingCompensatesHeightDifference() {
        // 第 0 行（高 60）拖到第 2 槽（该行高 52）：落点 = tops[2] + 52 − 60
        assertEquals(116, mixedTops[2] + mixedHeights[2] - mixedHeights[0])
        assertEquals(
            116,
            groupCardLandingTop(mixedTops, mixedHeights, from = 0, target = 2),
        )
        // 让位量恒为「被拖行高 + 行距」：目标行高不同也不重叠
        assertEquals(
            -(mixedHeights[0] + mixedSpacing),
            groupCardShift(1, from = 0, target = 2, draggedHeight = mixedHeights[0], spacingPx = mixedSpacing),
        )
    }

    /** 原地松手（target == from）：没有让位，浮层落回原位 */
    @Test
    fun inPlaceDropKeepsEveryRowStill() {
        uniformHeights.indices.forEach { index ->
            assertEquals(0, groupCardShift(index, from = 2, target = 2, draggedHeight = 60, spacingPx = 0))
        }
        assertEquals(120, groupCardLandingTop(uniformTops, uniformHeights, from = 2, target = 2))
    }

    /** 槽位翻转点 = 相邻两槽落点的中点；正落在中点上时取靠前的槽 */
    @Test
    fun targetFlipsAtSlotMidpoints() {
        // 均高 60：槽 0 的中心 30、槽 1 的中心 90 → 中点 60
        assertEquals(0, groupCardTarget(uniformTops, uniformHeights, from = 0, floatCenterY = 59f))
        assertEquals(0, groupCardTarget(uniformTops, uniformHeights, from = 0, floatCenterY = 60f))
        assertEquals(1, groupCardTarget(uniformTops, uniformHeights, from = 0, floatCenterY = 61f))
        // 变高列表：每个槽的翻转点都在两落点中点
        for (target in 0 until mixedHeights.size - 1) {
            val left = groupCardLandingTop(mixedTops, mixedHeights, from = 0, target = target) +
                mixedHeights[0] / 2f
            val right = groupCardLandingTop(mixedTops, mixedHeights, from = 0, target = target + 1) +
                mixedHeights[0] / 2f
            val middle = (left + right) / 2f
            assertEquals(target, groupCardTarget(mixedTops, mixedHeights, from = 0, floatCenterY = middle - 1f))
            assertEquals(target + 1, groupCardTarget(mixedTops, mixedHeights, from = 0, floatCenterY = middle + 1f))
        }
    }

    /** 浮层中心单调下移时目标槽位单调不减（不会跳着越过中间槽） */
    @Test
    fun targetIsMonotonicWhileDraggingDown() {
        var previous = 0
        var center = -200f
        while (center <= 400f) {
            val target = groupCardTarget(mixedTops, mixedHeights, from = 0, floatCenterY = center)
            assertTrue("center=$center target=$target < $previous", target >= previous)
            previous = target
            center += 3f
        }
        assertEquals(mixedHeights.size - 1, previous)
    }

    /** 置顶锁序（让位侧）：落点钳在置顶区（槽 0、1）之后时，置顶前缀让位恒 0 */
    @Test
    fun pinnedPrefixNeverShiftsWhenTargetClampedAfterIt() {
        for (target in 2..3) {
            for (pinnedIndex in 0..1) {
                assertEquals(
                    "置顶行 $pinnedIndex 不该被普通行（from=2）的拖拽影响",
                    0,
                    groupCardShift(pinnedIndex, from = 2, target = target, draggedHeight = 60, spacingPx = 0),
                )
            }
        }
    }

    /** 置顶锁序（落点侧）：minTargetIndex 之后的槽位才有资格；越界（全置顶）退回原槽，默认下限行为不变 */
    @Test
    fun targetExcludesPinnedPrefixSlots() {
        // 浮层中心停在置顶槽 0 的中心：候选只剩槽 2、3，最近的是槽 2
        assertEquals(2, groupCardTarget(uniformTops, uniformHeights, from = 2, floatCenterY = 30f, minTargetIndex = 2))
        // 槽 3 拖起、浮层拖到卡顶：仍钳在置顶区之后的第一槽
        assertEquals(2, groupCardTarget(uniformTops, uniformHeights, from = 3, floatCenterY = 0f, minTargetIndex = 2))
        // 置顶区吞掉整卡（全置顶）：无候选槽，退回原槽不乱跳
        assertEquals(3, groupCardTarget(uniformTops, uniformHeights, from = 3, floatCenterY = 999f, minTargetIndex = 4))
        // 默认下限 0：全槽候选，与原规格一致（浮层在卡顶落槽 0）
        assertEquals(0, groupCardTarget(uniformTops, uniformHeights, from = 2, floatCenterY = 0f))
    }
}
