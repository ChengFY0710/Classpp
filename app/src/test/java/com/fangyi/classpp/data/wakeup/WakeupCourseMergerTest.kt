package com.fangyi.classpp.data.wakeup

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeupCourseMergerTest {

    private fun course(
        name: String = "高等数学",
        teacher: String? = "张老师",
        position: String? = "A101",
        day: Int = 1,
        weeks: List<Int>,
        start: Int,
        end: Int,
    ) = WakeupCourse(
        name = name,
        teacher = teacher,
        position = position,
        day = day,
        weeks = weeks,
        startSection = start,
        endSection = end,
    )

    @Test
    fun `same section entries union their weeks`() {
        val merged = WakeupCourseMerger.merge(
            listOf(
                course(weeks = listOf(1, 2, 3), start = 1, end = 2),
                course(weeks = listOf(3, 4), start = 1, end = 2),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(listOf(1, 2, 3, 4), merged.single().weeks)
        assertEquals(1, merged.single().startSection)
        assertEquals(2, merged.single().endSection)
    }

    @Test
    fun `contiguous sections with same weeks merge into one span`() {
        val merged = WakeupCourseMerger.merge(
            listOf(
                course(weeks = listOf(1, 2, 3, 4), start = 1, end = 2),
                course(weeks = listOf(1, 2, 3, 4), start = 3, end = 4),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(1, merged.single().startSection)
        assertEquals(4, merged.single().endSection)
    }

    @Test
    fun `three contiguous chunks merge to fixpoint`() {
        // Wakeup 常把一门连堂拆成多条：1-2 + 3-4 + 5-6 需要两轮合并
        val merged = WakeupCourseMerger.merge(
            listOf(
                course(weeks = listOf(1, 2, 3, 4, 5, 6), start = 1, end = 2),
                course(weeks = listOf(1, 2, 3, 4, 5, 6), start = 3, end = 4),
                course(weeks = listOf(1, 2, 3, 4, 5, 6), start = 5, end = 6),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(1, merged.single().startSection)
        assertEquals(6, merged.single().endSection)
    }

    @Test
    fun `overlapping sections with same weeks merge to prevent grid conflict`() {
        val merged = WakeupCourseMerger.merge(
            listOf(
                course(weeks = listOf(1, 2, 3), start = 1, end = 3),
                course(weeks = listOf(1, 2, 3), start = 2, end = 4),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(1, merged.single().startSection)
        assertEquals(4, merged.single().endSection)
    }

    @Test
    fun `gap between sections prevents merge`() {
        // 1-2 与 4-5 中间隔着第 3 节：不连续，不合
        val merged = WakeupCourseMerger.merge(
            listOf(
                course(weeks = listOf(1, 2, 3), start = 1, end = 2),
                course(weeks = listOf(1, 2, 3), start = 4, end = 5),
            ),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `different weeks keep entries apart`() {
        val merged = WakeupCourseMerger.merge(
            listOf(
                course(weeks = listOf(1, 2, 3, 4), start = 1, end = 2),
                course(weeks = listOf(5, 6, 7, 8), start = 3, end = 4),
            ),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `different teacher or room keeps entries apart`() {
        val merged = WakeupCourseMerger.merge(
            listOf(
                course(teacher = "张老师", position = "A101", weeks = listOf(1, 2), start = 1, end = 2),
                course(teacher = "李老师", position = "A101", weeks = listOf(1, 2), start = 3, end = 4),
                course(teacher = "张老师", position = "B202", weeks = listOf(1, 2), start = 3, end = 4),
            ),
        )
        assertEquals(3, merged.size)
    }

    @Test
    fun `exact duplicates are removed`() {
        val merged = WakeupCourseMerger.merge(
            listOf(
                course(weeks = listOf(1, 2), start = 1, end = 2),
                course(weeks = listOf(1, 2), start = 1, end = 2),
            ),
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `different days and names stay separate and output is sorted`() {
        val merged = WakeupCourseMerger.merge(
            listOf(
                course(name = "英语", day = 3, weeks = listOf(1, 2), start = 1, end = 2),
                course(name = "数学", day = 1, weeks = listOf(1, 2), start = 3, end = 4),
                course(name = "物理", day = 1, weeks = listOf(1, 2), start = 1, end = 2),
            ),
        )
        assertEquals(3, merged.size)
        // 输出按 星期 → 起始节次 排序
        assertEquals(listOf(1, 1, 3), merged.map { it.day })
        assertEquals(listOf(1, 3, 1), merged.map { it.startSection })
    }

    @Test
    fun `invalid weeks are filtered and deduplicated`() {
        val merged = WakeupCourseMerger.merge(
            listOf(course(weeks = listOf(3, 3, 1, 0, -2, 2), start = 1, end = 2)),
        )
        assertEquals(listOf(1, 2, 3), merged.single().weeks)
    }
}
