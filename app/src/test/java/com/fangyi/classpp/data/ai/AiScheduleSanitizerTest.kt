package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.evenWeeks
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import com.fangyi.classpp.data.oddWeeks
import com.fangyi.classpp.data.testCourse
import com.fangyi.classpp.data.testSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 导入清理：同名平行班合并、周次不相交保留、不同课程不误合并 */
class AiScheduleSanitizerTest {

    @Test
    fun `merges parallel same-name sections with intersecting weeks`() {
        val courses = listOf(
            testCourse(id = "c1", name = "数据结构(03)")
                .copy(teacher = "苏淑文", location = "文宣楼 B312"),
            testCourse(id = "c2", name = "数据结构(03)")
                .copy(teacher = "陈锦秀", location = "文宣楼 A404"),
            testCourse(id = "c3", name = "数据结构(03)")
                .copy(teacher = "张琦", location = "文宣楼 A405"),
        )

        val sanitized = AiScheduleSanitizer.sanitize(testSchedule(courses = courses))

        assertEquals(1, sanitized.courses.size)
        val kept = sanitized.courses.single()
        assertEquals("数据结构(03)", kept.name)
        // 保留字段最完整的一条（这里教师/地点都有，取先出现的）
        assertEquals("苏淑文", kept.teacher)
        assertEquals("文宣楼 B312", kept.location)
        assertTrue(kept.weeks.contains(1))
        assertTrue(kept.weeks.contains(16))
        assertEquals(16, kept.weeks.maxWeek())
    }

    @Test
    fun `keeps odd and even entries as separate courses`() {
        val courses = listOf(
            testCourse(id = "c1", name = "数据结构(03)", weeks = oddWeeks(16)),
            testCourse(id = "c2", name = "数据结构(03)", weeks = evenWeeks(16)),
        )

        val sanitized = AiScheduleSanitizer.sanitize(testSchedule(courses = courses))

        assertEquals(2, sanitized.courses.size)
    }

    @Test
    fun `merges overlapping week splits into one union`() {
        val courses = listOf(
            testCourse(
                id = "c1",
                name = "数据结构(03)",
                weeks = WeekPattern(listOf(WeekSegment(1, 8))),
            ),
            testCourse(
                id = "c2",
                name = "数据结构(03)",
                weeks = WeekPattern(listOf(WeekSegment(1, 16))),
            ),
        )

        val sanitized = AiScheduleSanitizer.sanitize(testSchedule(courses = courses))

        assertEquals(1, sanitized.courses.size)
        assertEquals(16, sanitized.courses.single().weeks.maxWeek())
    }

    @Test
    fun `does not merge different course names`() {
        val courses = listOf(
            testCourse(id = "c1", name = "数据结构(03)"),
            testCourse(id = "c2", name = "离散数学(02)"),
        )

        val sanitized = AiScheduleSanitizer.sanitize(testSchedule(courses = courses))

        assertEquals(2, sanitized.courses.size)
    }

    @Test
    fun `does not merge same course on different slots`() {
        val courses = listOf(
            testCourse(id = "c1", name = "数据结构(03)", startSlot = 1),
            testCourse(id = "c2", name = "数据结构(03)", startSlot = 2),
        )

        val sanitized = AiScheduleSanitizer.sanitize(testSchedule(courses = courses))

        assertEquals(2, sanitized.courses.size)
    }
}
