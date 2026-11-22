package com.fangyi.classpp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程编辑面板的纯函数口径：
 * - [buildCourseEntry] 面板外字段（备注）从原条目保留、id 沿用、文本统一去空白；
 * - [prunedForCourse] 周规律改小后失效调课的清理规则（编辑会话与面板预校验共用）。
 */
class CourseEntryDraftTest {

    private fun existing(
        id: String = "c1",
        note: String = "带课本，前两排签到",
        weeks: WeekPattern = WeekPattern.everyWeek(16),
    ) = CourseEntry(
        id = id,
        name = "微积分",
        teacher = "XX老师",
        location = "@学武楼 C201",
        dayOfWeek = 3,
        startSlot = 1,
        span = 2,
        weeks = weeks,
        color = CourseColor.Green,
        note = note,
    )

    @Test
    fun `editing keeps the note of the existing entry`() {
        val entry = buildCourseEntry(
            existing = existing(),
            name = "微积分(改色)",
            teacher = "XX老师",
            location = "@学武楼 C201",
            dayOfWeek = 3,
            startSlot = 1,
            span = 2,
            weeks = WeekPattern.everyWeek(16),
            color = CourseColor.Blue,
        )

        assertEquals("c1", entry.id)
        // 面板不编辑备注：原备注必须保留，否则编辑改色后详情备注被清空
        assertEquals("带课本，前两排签到", entry.note)
    }

    @Test
    fun `creating gives a fresh id and an empty note`() {
        val entry = buildCourseEntry(
            existing = null,
            name = " 线性代数 ",
            teacher = " 李四 ",
            location = " A101 ",
            dayOfWeek = 1,
            startSlot = 1,
            span = 1,
            weeks = WeekPattern.everyWeek(16),
            color = CourseColor.Blue,
        )

        assertTrue("新建应生成新 id", entry.id.isNotBlank())
        assertNotEquals("c1", entry.id)
        assertEquals("", entry.note)
        // 文本统一去首尾空白
        assertEquals("线性代数", entry.name)
        assertEquals("李四", entry.teacher)
        assertEquals("A101", entry.location)
    }

    @Test
    fun `shrinking weeks prunes only the stale adjustments of that course`() {
        val course = existing(weeks = WeekPattern(listOf(WeekSegment(1, 4)))) // 1-4 周
        val adjustments = listOf(
            CourseAdjustment(id = "a1", courseId = "c1", fromWeek = 5, toWeek = 6, toDayOfWeek = 4, toStartSlot = 1, toSpan = 2),
            CourseAdjustment(id = "a2", courseId = "c1", fromWeek = 2, toWeek = 6, toDayOfWeek = 5, toStartSlot = 1, toSpan = 2),
            // 别的课的调课不受影响
            CourseAdjustment(id = "a3", courseId = "c2", fromWeek = 5, toWeek = 6, toDayOfWeek = 4, toStartSlot = 3, toSpan = 2),
        )

        val pruned = adjustments.prunedForCourse("c1", course)

        // a1 的源周（5）已不在新周规律里 → 清理；a2（源周 2 仍在）与 a3（别门课）保留
        assertEquals(listOf("a2", "a3"), pruned.map { it.id })
    }
}
