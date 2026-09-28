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
}
