package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.firstUnusedColor
import com.fangyi.classpp.data.model.overlappingCourses
import com.fangyi.classpp.data.model.weeksTakenByOthers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交替课程的分组与周数占用：同一天 span 相交（占用节次有重合）互为交替课，周数互不重合 */
class AlternationTest {

    private val odd = testCourse(id = "odd", name = "微积分", day = 1, weeks = oddWeeks())
    private val even = testCourse(id = "even", name = "电路原理", day = 1, weeks = evenWeeks())
    private val otherDay = testCourse(id = "otherDay", name = "体育", day = 2, weeks = evenWeeks())
    private val otherSlot = testCourse(id = "otherSlot", name = "英语", day = 1, startSlot = 3)

    private val courses = listOf(odd, even, otherDay, otherSlot)

    @Test
    fun overlappingCoursesKeepsSameDaySpanIntersectingInListOrder() {
        assertEquals(
            listOf(odd, even),
            courses.overlappingCourses(dayOfWeek = 1, startSlot = 1, endSlot = 1),
        )
        assertEquals(
            listOf(otherDay),
            courses.overlappingCourses(dayOfWeek = 2, startSlot = 1, endSlot = 1),
        )
        assertEquals(
            listOf(otherSlot),
            courses.overlappingCourses(dayOfWeek = 1, startSlot = 3, endSlot = 3),
        )
        assertTrue(courses.overlappingCourses(dayOfWeek = 3, startSlot = 1, endSlot = 1).isEmpty())
    }

    @Test
    fun crossPeriodCourseGroupsContinuationCellMates() {
        // 跨节课程占 2-3 节：续格（第 3 节）上的单节课也进组——组员的起止节次不必一致
        val spanning = testCourse(id = "span", day = 1, startSlot = 2, span = 2, weeks = oddWeeks())
        val continuation = testCourse(id = "cont", day = 1, startSlot = 3, weeks = evenWeeks())
        val group = listOf(spanning, continuation, odd)
        assertEquals(
            listOf(spanning, continuation),
            group.overlappingCourses(dayOfWeek = 1, startSlot = 2, endSlot = 3),
        )
        // 从续格反查同样命中跨节卡（编辑选择弹窗按被点课的范围反查候选）
        assertEquals(
            listOf(spanning, continuation),
            group.overlappingCourses(dayOfWeek = 1, startSlot = 3, endSlot = 3),
        )
    }

    @Test
    fun weeksTakenByOthersExcludesSelf() {
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), courses.weeksTakenByOthers("odd", 1, 1, 1, 16))
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), courses.weeksTakenByOthers("even", 1, 1, 1, 16))
    }

    @Test
    fun takenWeeksSpanTheWholeDraftRange() {
        // 跨节草稿 [1..2]：第 2 节上的课也算占用（按同起始节查会漏掉它，见 R13 的 span 相交）
        val early = testCourse(id = "early", day = 1, startSlot = 1, weeks = oddWeeks())
        val late = testCourse(id = "late", day = 1, startSlot = 2, weeks = evenWeeks())
        val draft = listOf(early, late)
        assertEquals(
            (1..16).toSet(),
            draft.weeksTakenByOthers(
                selfId = "",
                dayOfWeek = 1,
                startSlot = 1,
                endSlot = 2,
                totalWeeks = 16,
            ),
        )
        // 范围缩回只碰第 1 节 → 只剩 early 的周
        assertEquals(
            setOf(1, 3, 5, 7, 9, 11, 13, 15),
            draft.weeksTakenByOthers(
                selfId = "",
                dayOfWeek = 1,
                startSlot = 1,
                endSlot = 1,
                totalWeeks = 16,
            ),
        )
    }

    @Test
    fun blankSelfIdCountsWholeGroup() {
        assertEquals(
            (1..16).toSet(),
            courses.weeksTakenByOthers(
                selfId = "",
                dayOfWeek = 1,
                startSlot = 1,
                endSlot = 1,
                totalWeeks = 16,
            ),
        )
    }

    @Test
    fun takenWeeksAreClippedToTermLength() {
        // 学期只剩 10 周：第 11 周以后不算占用（学期总周数之外本来就没有课）
        assertEquals(setOf(2, 4, 6, 8, 10), courses.weeksTakenByOthers("odd", 1, 1, 1, 10))
    }

    @Test
    fun firstUnusedColorSkipsColorsAlreadyInTheGroup() {
        val group = listOf(
            testCourse(id = "a", day = 1, startSlot = 2, color = CourseColor.Blue),
            testCourse(id = "b", day = 1, startSlot = 2, color = CourseColor.Green),
        )
        assertEquals(CourseColor.Greentwo, group.firstUnusedColor(1, 2, 2))
        // 只按同组判断：别的天/别的节次用了什么配色不影响
        assertEquals(CourseColor.Blue, group.firstUnusedColor(1, 4, 4))
    }

    @Test
    fun firstUnusedColorCountsSpanIntersectingNeighbours() {
        // 上方有一门跨节蓝课压到第 2 节：第 2 节的新交替课要避开蓝色，否则色条分不出来
        val spanning = testCourse(id = "span", day = 1, startSlot = 1, span = 2, color = CourseColor.Blue)
        assertEquals(CourseColor.Green, listOf(spanning).firstUnusedColor(1, 2, 2))
    }

    @Test
    fun firstUnusedColorFallsBackWhenPaletteIsExhausted() {
        val full = CourseColor.entries.mapIndexed { index, color ->
            testCourse(id = "c$index", day = 1, startSlot = 5, color = color)
        }
        assertEquals(CourseColor.entries.first(), full.firstUnusedColor(1, 5, 5))
    }
}
