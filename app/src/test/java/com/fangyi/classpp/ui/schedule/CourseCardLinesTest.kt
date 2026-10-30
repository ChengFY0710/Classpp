package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡内文本行数分配：**实测自然行数封顶 + 单条偏好序 first-fit**。
 *
 * 偏好序：增长段地点 3→6 → 课名 3→6 → 地点 6→8 → 课名 6→8（封顶 8/8）→ 默认段 (3,3) →
 * 降级段课程名先降 ((2,3)…(1,1))；每档字段行数不超过内容自然行数，
 * 短内容不再为空行预留空间、长内容才被截断。默认 wanted = 6/6 时与旧行为逐值一致（回归基线）。
 *
 * 行成本 = lineHeight px。偏好序测试用课程名 16 / 地点 14；生产值两者均 16，
 * 默认预算约 95（5 天）/ 105（7 天）。
 */
class CourseCardLinesTest {
    private val nameLine = 16f
    private val locationLine = 14f

    private fun allocate(
        budget: Float,
        name: Int = 6,
        location: Int = 6,
    ) = allocateCardLines(budget, nameLine, locationLine, name, location)

    private fun allocateProduction(
        budget: Float,
        name: Int = 6,
        location: Int = 6,
    ) = allocateCardLines(budget, nameLinePx = 16f, locationLinePx = 16f, name, location)

    @Test
    fun excessBudgetCapsAtEightLines() {
        // 超额预算封顶 8/8；实际行数超过 8 也封顶在 8/8（(8,8)=240）
        assertEquals(CardLines(8, 8), allocate(1000f, name = 20, location = 20))
        assertEquals(CardLines(8, 8), allocate(240f, name = 20, location = 20))
        // 差一点放不下 8/8 → 课程名先让一档 (7,8)=224
        assertEquals(CardLines(7, 8), allocate(239.9f, name = 20, location = 20))
        // 封顶 6/6 的旧默认 wanted 不受影响
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
    fun growthFollowsPreferenceOrder() {
        // 地点 3→6：(3,4)=104 → (3,5)=118 → (3,6)=132
        assertEquals(CardLines(3, 4), allocate(104f))
        assertEquals(CardLines(3, 5), allocate(118f))
        assertEquals(CardLines(3, 6), allocate(132f))
        // 课名 3→6：(4,6)=148 → (5,6)=164 → (6,6)=180（实测 6/6 上限下到此为止）
        assertEquals(CardLines(4, 6), allocate(148f))
        assertEquals(CardLines(5, 6), allocate(164f))
        assertEquals(CardLines(6, 6), allocate(180f))
        // 地点 6→8 后再课名 6→8：(6,7)=194 → (6,8)=208 → (7,8)=224 → (8,8)=240
        assertEquals(CardLines(6, 7), allocate(194f, name = 20, location = 20))
        assertEquals(CardLines(6, 8), allocate(208f, name = 20, location = 20))
        assertEquals(CardLines(7, 8), allocate(224f, name = 20, location = 20))
        assertEquals(CardLines(8, 8), allocate(240f, name = 20, location = 20))
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
    fun productionBudgetsAfterStage3() {
        // 阶段 3：课程名/地点/元信息行高均为 16sp，实测 3/3 时与旧档位逐值一致
        // 5 天行高 150dp 扣 7dp 与 3×16dp → 95
        assertEquals(CardLines(2, 3), allocateProduction(95f, name = 3, location = 3))
        // 7 天行高 160dp → 105，刚好放下 3 行课程名 + 3 行地点
        assertEquals(CardLines(3, 3), allocateProduction(105f, name = 3, location = 3))
    }

    @Test
    fun shortContentDoesNotReserveEmptyLines() {
        // 实测 1/1：预算再大也只给 (1,1)——不再为不存在的行预留空间
        assertEquals(CardLines(1, 1), allocate(1000f, name = 1, location = 1))
        assertEquals(CardLines(1, 2), allocate(1000f, name = 1, location = 2))
        assertEquals(CardLines(2, 1), allocate(1000f, name = 2, location = 1))
        // 实测 0/0（空文本）同样按保底 1 行处理
        assertEquals(CardLines(1, 1), allocate(1000f, name = 0, location = 0))
    }

    @Test
    fun shortNameFreesBudgetForMoreLocation() {
        // 生产预算 95：实测课名 1 行时，地点可以放到 4 行（(1,4)=80 ≤ 95，(1,5)=96 超）
        // 旧档位不看实际内容，同样预算只会给 (2,3) 并截掉第 4 行地点
        assertEquals(CardLines(1, 4), allocateProduction(95f, name = 1, location = 6))
        assertEquals(CardLines(1, 4), allocateProduction(95f, name = 1, location = 4))
    }

    @Test
    fun longNameUsesBudgetFreedByShortLocation() {
        // 实测课名 4 行 + 地点 1 行 = 5 行，预算 80 恰好放下——旧档位会先把课名截到 2 行
        assertEquals(CardLines(4, 1), allocateProduction(80f, name = 4, location = 1))
    }

    @Test
    fun insufficientBudgetKeepsMinimum() {
        // 保底档 (1,1)=30 也装不下 → 仍返回 (1,1)，溢出交给 maxLines + 省略号
        assertEquals(CardLines(1, 1), allocate(29.9f))
        assertEquals(CardLines(1, 1), allocate(0f))
        assertEquals(CardLines(1, 1), allocate(-100f))
        assertEquals(CardLines(1, 1), allocateProduction(0f, name = 1, location = 1))
    }
}
