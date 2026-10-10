package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡内文本行数分配：单条偏好序 first-fit、无默认值分支——
 * 增长段地点先涨 ((3,4)…(6,6)，封顶 6/6) → 默认段 (3,3) → 降级段课程名先降 ((2,3)…(1,1))。
 *
 * 行成本 = lineHeight px（课程名 16、地点 14）；默认行高预算（5 天 107 / 7 天 117）
 * 自然落在 (3,4)。纯浮点比较，JVM 单测直接断言。
 */
class CourseCardLinesTest {
    private val nameLine = 16f
    private val locationLine = 14f

    private fun allocate(budget: Float) =
        allocateCardLines(budget, nameLine, locationLine)

    @Test
    fun excessBudgetCapsAtSixLines() {
        // 超额预算封顶 6/6；恰好等于 6/6 总价 (180) 也成立
        assertEquals(CardLines(6, 6), allocate(1000f))
        assertEquals(CardLines(6, 6), allocate(180f))
    }

    @Test
    fun defaultHeightLandsOnThreeNameFourLocation() {
        // 默认行高预算 5 天 107 / 7 天 117 → (3,4)=104；(3,4) 装不下时回落 (3,3)
        assertEquals(CardLines(3, 4), allocate(107f))
        assertEquals(CardLines(3, 4), allocate(117f))
        assertEquals(CardLines(3, 3), allocate(103.9f))
        assertEquals(CardLines(3, 3), allocate(90f))
    }

    @Test
    fun growsLocationBeforeName() {
        // 地点先涨：(3,4)=104 → (3,5)=118 → (3,6)=132
        assertEquals(CardLines(3, 4), allocate(104f))
        assertEquals(CardLines(3, 5), allocate(118f))
        assertEquals(CardLines(3, 6), allocate(132f))
        // 地点到 6 行封顶后才轮到课程名：(4,6)=148 → (5,6)=164
        assertEquals(CardLines(4, 6), allocate(148f))
        assertEquals(CardLines(5, 6), allocate(164f))
    }

    @Test
    fun degradesNameBeforeLocation() {
        // 满档 (3,3)=90 装不下 → 课程名先降一档 (2,3)=74
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
    fun insufficientBudgetKeepsMinimum() {
        // 保底档 (1,1)=30 也装不下 → 仍返回 (1,1)，溢出交给 maxLines + 省略号
        assertEquals(CardLines(1, 1), allocate(29.9f))
        assertEquals(CardLines(1, 1), allocate(0f))
        assertEquals(CardLines(1, 1), allocate(-100f))
    }
}
