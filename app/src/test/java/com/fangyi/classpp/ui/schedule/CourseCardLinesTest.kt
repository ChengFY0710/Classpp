package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡内文本行数分配：
 * - 增长关闭（卡高 ≤ 该模式默认）：预算装不下满档 (3,3) 时按**课程名先降**收缩
 *   （(3,3) → (2,3) → (1,3) → (1,2) → 保底 (1,1)），再高的预算也封顶 3/3；
 * - 增长开启（卡高 > 默认）：按**地点先涨**增长，封顶 6/6
 *   （(3,4) → (3,5) → (3,6) → (4,6) → (5,6) → (6,6)），预算不足时回落默认/降级段。
 *
 * 行成本 = lineHeight px（课程名 16、地点 14）；纯浮点比较，JVM 单测直接断言。
 */
class CourseCardLinesTest {
    private val nameLine = 16f
    private val locationLine = 14f

    private fun allocate(budget: Float, growth: Boolean = false) =
        allocateCardLines(budget, nameLine, locationLine, allowGrowth = growth)

    @Test
    fun excessBudgetCapsAtDefaultWithoutGrowth() {
        // 增长关闭时预算再多也封顶 3/3（恰好等于满档总价也成立）
        assertEquals(CardLines(3, 3), allocate(1000f))
        assertEquals(CardLines(3, 3), allocate(90f))
    }

    @Test
    fun degradesNameBeforeLocation() {
        // 满档装不下 → 课程名先降一档 (2,3)=74
        assertEquals(CardLines(2, 3), allocate(89.9f))
        assertEquals(CardLines(2, 3), allocate(74f))
        // 再降 (1,3)=58
        assertEquals(CardLines(1, 3), allocate(73.9f))
        assertEquals(CardLines(1, 3), allocate(58f))
        // 课程名到底后才降地点 (1,2)=44
        assertEquals(CardLines(1, 2), allocate(57.9f))
        assertEquals(CardLines(1, 2), allocate(44f))
    }

    @Test
    fun growsLocationBeforeName() {
        // 增长开启且预算够 → 地点先涨：(3,4)=104 → (3,5)=118 → (3,6)=132
        assertEquals(CardLines(3, 4), allocate(104f, growth = true))
        assertEquals(CardLines(3, 5), allocate(118f, growth = true))
        assertEquals(CardLines(3, 6), allocate(132f, growth = true))
        // 地点到 6 行封顶后才轮到课程名：(4,6)=148 → (5,6)=164 → (6,6)=180
        assertEquals(CardLines(4, 6), allocate(148f, growth = true))
        assertEquals(CardLines(5, 6), allocate(164f, growth = true))
        assertEquals(CardLines(6, 6), allocate(1000f, growth = true))
        // 增长开启但预算装不下 (3,4) → 回落默认段
        assertEquals(CardLines(3, 3), allocate(103.9f, growth = true))
    }

    @Test
    fun insufficientBudgetKeepsMinimum() {
        // 保底档 (1,1)=30 也装不下 → 仍返回 (1,1)，溢出交给 maxLines + 省略号
        assertEquals(CardLines(1, 1), allocate(29.9f))
        assertEquals(CardLines(1, 1), allocate(0f))
        assertEquals(CardLines(1, 1), allocate(-100f))
    }
}
