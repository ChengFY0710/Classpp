package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡内文本行数分配：预算装不下满档 (3,3) 时按**课程名先降**的档位收缩
 * （(3,3) → (2,3) → (1,3) → (1,2) → 保底 (1,1)）。
 *
 * 行成本 = lineHeight px（课程名 16、地点 14），各档位总价 90 / 74 / 58 / 44 / 30；
 * 纯浮点比较，JVM 单测直接断言。
 */
class CourseCardLinesTest {
    private val nameLine = 16f
    private val locationLine = 14f

    @Test
    fun excessBudgetGivesFullTiers() {
        assertEquals(CardLines(3, 3), allocateCardLines(1000f, nameLine, locationLine))
        // 恰好等于满档总价
        assertEquals(CardLines(3, 3), allocateCardLines(90f, nameLine, locationLine))
    }

    @Test
    fun degradesNameBeforeLocation() {
        // 满档装不下 → 课程名先降一档 (2,3)=74
        assertEquals(CardLines(2, 3), allocateCardLines(89.9f, nameLine, locationLine))
        assertEquals(CardLines(2, 3), allocateCardLines(74f, nameLine, locationLine))
        // 再降 (1,3)=58
        assertEquals(CardLines(1, 3), allocateCardLines(73.9f, nameLine, locationLine))
        assertEquals(CardLines(1, 3), allocateCardLines(58f, nameLine, locationLine))
        // 课程名到底后才降地点 (1,2)=44
        assertEquals(CardLines(1, 2), allocateCardLines(57.9f, nameLine, locationLine))
        assertEquals(CardLines(1, 2), allocateCardLines(44f, nameLine, locationLine))
    }

    @Test
    fun insufficientBudgetKeepsMinimum() {
        // 保底档 (1,1)=30 也装不下 → 仍返回 (1,1)，溢出交给 maxLines + 省略号
        assertEquals(CardLines(1, 1), allocateCardLines(29.9f, nameLine, locationLine))
        assertEquals(CardLines(1, 1), allocateCardLines(0f, nameLine, locationLine))
        assertEquals(CardLines(1, 1), allocateCardLines(-100f, nameLine, locationLine))
    }
}
