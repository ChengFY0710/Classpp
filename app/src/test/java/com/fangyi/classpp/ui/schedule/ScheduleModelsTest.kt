package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 渲染层跨节查找：findAt 只命中起始格，isContinuationAt 只命中续格 */
class ScheduleModelsTest {

    private val spanning = Course(
        name = "微积分",
        teacher = "XX老师",
        location = "@学武楼 C201",
        dayOfWeek = 3,
        slotId = 1,
        color = CourseColor.Green,
        span = 2,
    )
    private val single = Course(
        name = "电路原理",
        teacher = "XX老师",
        location = "@学武楼 C404",
        dayOfWeek = 3,
        slotId = 3,
        color = CourseColor.Yellow,
    )
    private val courses = listOf(spanning, single)

    @Test
    fun findAtHitsOnlyStartCellOfSpanningCourse() {
        assertEquals(spanning, courses.findAt(3, 1))
        assertNull(courses.findAt(3, 2))
        assertEquals(single, courses.findAt(3, 3))
    }

    @Test
    fun findAtIgnoresOtherDays() {
        assertNull(courses.findAt(2, 1))
        assertNull(courses.findAt(2, 3))
    }

    @Test
    fun continuationCoversOnlyInnerCellsOfSpan() {
        assertFalse(courses.isContinuationAt(3, 1))
        assertTrue(courses.isContinuationAt(3, 2))
        assertFalse(courses.isContinuationAt(3, 3))
    }

    @Test
    fun singlePeriodCourseHasNoContinuation() {
        assertFalse(courses.isContinuationAt(3, 4))
        assertFalse(courses.isContinuationAt(4, 3))
    }

    @Test
    fun defaultSpanIsOne() {
        assertEquals(1, single.span)
    }

    /**
     * 每周日期按天数生成：5 天视图周一~周五、7 天视图周一~周日，
     * 日期带 / 网格列数直接取这个列表的长度，故它必须与 daysPerWeek 严格一致。
     */
    @Test
    fun datesForWeekFollowsTheDayCount() {
        val five = datesForWeek(week = 1, daysPerWeek = 5)
        assertEquals(5, five.size)
        assertEquals("2026-03-02", five.first().toIsoText())
        assertEquals("2026-03-06", five.last().toIsoText())

        val seven = datesForWeek(week = 1, daysPerWeek = 7)
        assertEquals(7, seven.size)
        assertEquals("2026-03-02", seven.first().toIsoText())
        assertEquals("2026-03-08", seven.last().toIsoText())

        // 缺省仍是 5 天（旧调用点不变）
        assertEquals(5, datesForWeek(week = 3).size)
        // 第 2 周顺延整周：周一是 3-09
        assertEquals("2026-03-09", datesForWeek(week = 2, daysPerWeek = 7).first().toIsoText())
    }
}

/** 测试用：`yyyy-MM-dd` 文本，只做断言比较，避免依赖格式化实现 */
private fun java.util.Date.toIsoText(): String {
    val cal = java.util.Calendar.getInstance().apply { time = this@toIsoText }
    return "%04d-%02d-%02d".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
    )
}
