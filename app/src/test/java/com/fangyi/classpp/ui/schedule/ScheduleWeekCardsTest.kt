package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交替课程的每周解析：一格只画当周那张卡，非本周的组员只剩底部 1/4 色条 */
class ScheduleWeekCardsTest {

    private fun course(
        id: String,
        day: Int = 3,
        slot: Int = 1,
        color: CourseColor = CourseColor.Green,
        active: Boolean = true,
        span: Int = 1,
    ) = Course(
        name = "课-$id",
        teacher = "XX老师",
        location = "@学武楼 C201",
        dayOfWeek = day,
        slotId = slot,
        color = color,
        active = active,
        span = span,
        id = id,
    )

    @Test
    fun singleCourseKeepsNoAlternateBar() {
        val alone = course("a", color = CourseColor.Orange)
        assertEquals(listOf(alone), listOf(alone).resolveWeekCards())
    }

    @Test
    fun nonCurrentCellMateOnlyLeavesItsColorAsBar() {
        // 第 1 周：单周课在课（橙），双周课不上（绿）→ 只画橙卡，底部色条取绿的
        val odd = course("odd", color = CourseColor.Orange, active = true)
        val even = course("even", color = CourseColor.Green, active = false)
        val cards = listOf(odd, even).resolveWeekCards()

        assertEquals(listOf(odd.copy(alternateBar = CourseColor.Green)), cards)
        assertEquals("odd", cards.findAt(3, 1)?.id)
    }

    @Test
    fun cardsSwapBetweenOddAndEvenWeeks() {
        fun cards(oddWeek: Boolean) = listOf(
            course("odd", color = CourseColor.Orange, active = oddWeek),
            course("even", color = CourseColor.Green, active = !oddWeek),
        ).resolveWeekCards()

        val oddWeek = cards(oddWeek = true).single()
        assertEquals("odd", oddWeek.id)
        assertEquals(CourseColor.Green, oddWeek.alternateBar)

        val evenWeek = cards(oddWeek = false).single()
        assertEquals("even", evenWeek.id)
        assertEquals(CourseColor.Orange, evenWeek.alternateBar)
    }

    @Test
    fun firstNonCurrentMemberDecidesTheBarWhenThereAreThree() {
        // 三门：当周课之后的顺序即添加顺序，色条取非当周里最先添加的那门（三门也只占 1/4）
        val first = course("first", color = CourseColor.Orange, active = true)
        val second = course("second", color = CourseColor.Green, active = false)
        val third = course("third", color = CourseColor.Pink, active = false)
        assertEquals(
            CourseColor.Green,
            listOf(first, second, third).resolveWeekCards().single().alternateBar,
        )
        // 换一门当周：非当周里靠前的仍是父课，色条取父课的颜色
        assertEquals(
            CourseColor.Orange,
            listOf(
                first.copy(active = false),
                second.copy(active = true),
                third.copy(active = false),
            ).resolveWeekCards().single().alternateBar,
        )
    }

    @Test
    fun weekWhereNoMemberAttendsFallsBackToFirstAndDrawsNoBar() {
        // 三门覆盖不到第 4 周：退回列表首项（照旧置灰），且不掺彩色
        val first = course("first", color = CourseColor.Orange, active = false)
        val second = course("second", color = CourseColor.Green, active = false)
        val cards = listOf(first, second).resolveWeekCards()

        assertEquals(listOf(first), cards)
        assertNull(cards.single().alternateBar)
    }

    @Test
    fun otherCellsAreUntouched() {
        val cel1 = course("c1", day = 1, slot = 1, color = CourseColor.Yellow, active = false)
        val cel2 = course("c2", day = 2, slot = 3, color = CourseColor.Blue, active = true)
        val cards = listOf(cel1, cel2).resolveWeekCards()

        assertEquals(2, cards.size)
        assertEquals(cel1, cards.findAt(1, 1))
        assertNull(cards.findAt(1, 1)!!.alternateBar)
        assertEquals(cel2, cards.findAt(2, 3))
    }

    @Test
    fun continuationCellFreesUpInTheAlternateWeek() {
        // 同格两门：跨两节的 A 与单节的 B
        val spanning = course("span", slot = 1, color = CourseColor.Green, active = true, span = 2)
        val single = course("single", slot = 1, color = CourseColor.Orange, active = false)

        val aWeek = listOf(spanning, single).resolveWeekCards()
        assertEquals(spanning.copy(alternateBar = CourseColor.Orange), aWeek.single())
        assertTrue(aWeek.isContinuationAt(3, 2))

        // 轮到 B 上课：它只占一格，下面那格不再被跨节卡覆盖（编辑态可以正常加课）
        val bWeek = listOf(spanning.copy(active = false), single.copy(active = true)).resolveWeekCards()
        assertEquals(single.copy(active = true, alternateBar = CourseColor.Green), bWeek.single())
        assertFalse(bWeek.isContinuationAt(3, 2))
        assertNull(bWeek.findAt(3, 2))
    }
}
