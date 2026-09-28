package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.cellCourses
import com.fangyi.classpp.data.model.firstUnusedColor
import com.fangyi.classpp.data.model.weeksTakenByOthers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交替课程的同格分组与周数占用：同一天 + 同一起始节互为交替课，周数互不重合 */
class AlternationTest {

    private val odd = testCourse(id = "odd", name = "微积分", day = 1, weeks = oddWeeks())
    private val even = testCourse(id = "even", name = "电路原理", day = 1, weeks = evenWeeks())
    private val otherDay = testCourse(id = "otherDay", name = "体育", day = 2, weeks = evenWeeks())
    private val otherSlot = testCourse(id = "otherSlot", name = "英语", day = 1, startSlot = 3)

    private val courses = listOf(odd, even, otherDay, otherSlot)

    @Test
    fun cellCoursesKeepsSameDayAndStartSlotInListOrder() {
        assertEquals(listOf(odd, even), courses.cellCourses(dayOfWeek = 1, startSlot = 1))
        assertEquals(listOf(otherDay), courses.cellCourses(dayOfWeek = 2, startSlot = 1))
        assertEquals(listOf(otherSlot), courses.cellCourses(dayOfWeek = 1, startSlot = 3))
        assertTrue(courses.cellCourses(dayOfWeek = 3, startSlot = 1).isEmpty())
    }

    @Test
    fun weeksTakenByOthersExcludesSelf() {
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), courses.weeksTakenByOthers("odd", 1, 1, 16))
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), courses.weeksTakenByOthers("even", 1, 1, 16))
    }

    @Test
    fun blankSelfIdCountsWholeGroup() {
        assertEquals(
            (1..16).toSet(),
            courses.weeksTakenByOthers(selfId = "", dayOfWeek = 1, startSlot = 1, totalWeeks = 16),
        )
    }

    @Test
    fun takenWeeksAreClippedToTermLength() {
        // 学期只剩 10 周：第 11 周以后不算占用（学期总周数之外本来就没有课）
        assertEquals(setOf(2, 4, 6, 8, 10), courses.weeksTakenByOthers("odd", 1, 1, 10))
    }

    @Test
    fun firstUnusedColorSkipsColorsAlreadyInTheCell() {
        val group = listOf(
            testCourse(id = "a", day = 1, startSlot = 2, color = CourseColor.Blue),
            testCourse(id = "b", day = 1, startSlot = 2, color = CourseColor.Green),
        )
        assertEquals(CourseColor.Greentwo, group.firstUnusedColor(1, 2))
        // 只按同格判断：别的天/别的节次用了什么配色不影响
        assertEquals(CourseColor.Blue, group.firstUnusedColor(1, 4))
    }

    @Test
    fun firstUnusedColorFallsBackWhenPaletteIsExhausted() {
        val full = CourseColor.entries.mapIndexed { index, color ->
            testCourse(id = "c$index", day = 1, startSlot = 5, color = color)
        }
        assertEquals(CourseColor.entries.first(), full.firstUnusedColor(1, 5))
    }
}
