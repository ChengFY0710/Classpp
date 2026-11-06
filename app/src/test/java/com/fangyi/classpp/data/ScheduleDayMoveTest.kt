package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整天调休（把某周某天真正要上的全部课次整体平移到另一天，节次不变）：
 * 候选记录生成、调课卡跟随/还原与冲突点名的口径。
 */
class ScheduleDayMoveTest {

    private val termStart = IsoDate.parse("2026-03-02") // 周一
    private val termEnd = IsoDate.parse("2026-06-19") // 16 周

    private fun course(
        id: String = "c1",
        name: String = "微积分",
        day: Int = 3,
        startSlot: Int = 1,
        span: Int = 2,
        weeks: WeekPattern = WeekPattern.everyWeek(16),
    ) = CourseEntry(
        id = id,
        name = name,
        teacher = "XX老师",
        location = "@学武楼 C201",
        dayOfWeek = day,
        startSlot = startSlot,
        span = span,
        weeks = weeks,
        color = CourseColor.Green,
    )

    private fun adjust(
        id: String = "a1",
        courseId: String = "c1",
        fromWeek: Int = 2,
        toWeek: Int = 2,
        toDay: Int = 5,
        toStartSlot: Int = 1,
        toSpan: Int = 2,
    ) = CourseAdjustment(
        id = id,
        courseId = courseId,
        fromWeek = fromWeek,
        toWeek = toWeek,
        toDayOfWeek = toDay,
        toStartSlot = toStartSlot,
        toSpan = toSpan,
    )

    private fun schedule(
        courses: List<CourseEntry>,
        adjustments: List<CourseAdjustment> = emptyList(),
        end: IsoDate = termEnd,
    ) = Schedule(
        id = "s1",
        name = "2026春",
        termStart = termStart,
        termEnd = end,
        courses = courses,
        adjustments = adjustments,
    )

    @Test
    fun `moves all active courses of the source day keeping slots`() {
        val s = schedule(
            listOf(
                course(id = "c1", day = 3, startSlot = 1, span = 2),
                course(id = "c2", name = "大学英语", day = 3, startSlot = 3, span = 2),
            ),
        )

        val plan = buildDayMovePlan(s, sourceWeek = 2, sourceDay = 3, targetWeek = 2, targetDay = 6)

        assertEquals(2, plan.occurrences.size)
        assertEquals(2, plan.upserts.size)
        assertTrue("no errors expected", plan.errors.isEmpty())
        plan.upserts.forEach { adjustment ->
            assertEquals(2, adjustment.fromWeek)
            assertEquals(2, adjustment.toWeek)
            assertEquals(6, adjustment.toDayOfWeek)
            assertEquals(2, adjustment.toSpan)
        }
        assertEquals(setOf(1, 3), plan.upserts.map { it.toStartSlot }.toSet())
    }

    @Test
    fun `courses not active in the source week are ignored`() {
        val oddOnly = WeekPattern(listOf(WeekSegment(1, 16, Parity.ODD)))
        val s = schedule(listOf(course(weeks = oddOnly)))

        assertTrue(buildDayMovePlan(s, 2, 3, 2, 6).occurrences.isEmpty())
        assertEquals(1, buildDayMovePlan(s, 3, 3, 3, 6).upserts.size)
    }

    @Test
    fun `course already moved away from the source day is not moved twice`() {
        val s = schedule(
            courses = listOf(course(day = 3)),
            adjustments = listOf(adjust(fromWeek = 2, toWeek = 2, toDay = 5)),
        )

        val plan = buildDayMovePlan(s, 2, 3, 2, 6)

        assertTrue(plan.occurrences.isEmpty())
        assertTrue(plan.upserts.isEmpty())
    }

    @Test
    fun `adjusted card landing on the source day follows the day move`() {
        val existing = adjust(
            id = "a1",
            fromWeek = 2,
            toWeek = 2,
            toDay = 5,
            toStartSlot = 3,
            toSpan = 2,
        )
        val s = schedule(
            courses = listOf(course(day = 3, startSlot = 1, span = 2)),
            adjustments = listOf(existing),
        )

        val plan = buildDayMovePlan(s, 2, 5, 2, 6)

        assertEquals(1, plan.occurrences.size)
        assertEquals(1, plan.upserts.size)
        val moved = plan.upserts.single()
        assertEquals("a1", moved.id)
        assertEquals(2, moved.fromWeek)
        assertEquals(2, moved.toWeek)
        assertEquals(6, moved.toDayOfWeek)
        assertEquals(3, moved.toStartSlot)
        assertEquals(2, moved.toSpan)
        assertTrue(plan.errors.isEmpty())
    }

    @Test
    fun `moving an adjusted card back to its original position removes the record`() {
        // 原位置：第 2 周周三第 1-2 节；调课卡把它挪到了第 3 周周五
        val existing = adjust(
            id = "a1",
            fromWeek = 2,
            toWeek = 3,
            toDay = 5,
            toStartSlot = 1,
            toSpan = 2,
        )
        val s = schedule(courses = listOf(course()), adjustments = listOf(existing))

        val plan = buildDayMovePlan(s, sourceWeek = 3, sourceDay = 5, targetWeek = 2, targetDay = 3)

        assertEquals(listOf("a1"), plan.removals)
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.errors.isEmpty())
    }

    @Test
    fun `conflicting course on the target day is reported by name`() {
        val s = schedule(
            listOf(
                course(id = "c1", day = 3, startSlot = 1, span = 2),
                course(id = "c2", name = "线性代数", day = 6, startSlot = 1, span = 2),
            ),
        )

        val plan = buildDayMovePlan(s, 2, 3, 2, 6)

        assertEquals(1, plan.upserts.size)
        val conflict = plan.errors.filterIsInstance<ScheduleError.AdjustmentConflict>().single()
        assertEquals("线性代数", conflict.conflictName)
    }

    @Test
    fun `target day after term end is rejected`() {
        // 最后一周只上到周三，目标周六落在学期外
        val endMidWeek = IsoDate.parse("2026-06-17")
        val s = schedule(courses = listOf(course()), end = endMidWeek)

        val plan = buildDayMovePlan(s, sourceWeek = 16, sourceDay = 3, targetWeek = 16, targetDay = 6)

        assertEquals(1, plan.upserts.size)
        assertTrue(plan.errors.any { it is ScheduleError.AdjustmentTargetInvalid })
    }

    @Test
    fun `same source and target day is reported as unchanged`() {
        val s = schedule(listOf(course()))

        val plan = buildDayMovePlan(s, 2, 3, 2, 3)

        assertEquals(1, plan.upserts.size)
        assertTrue(plan.errors.any { it is ScheduleError.AdjustmentUnchanged })
    }

    @Test
    fun `weekend target is allowed in a five day schedule`() {
        val s = schedule(listOf(course()))

        val plan = buildDayMovePlan(s, 2, 3, 2, 7)

        assertEquals(1, plan.upserts.size)
        assertEquals(7, plan.upserts.single().toDayOfWeek)
        assertTrue(plan.errors.isEmpty())
    }

    @Test
    fun `empty source day produces no records`() {
        val s = schedule(listOf(course(day = 1)))

        val plan = buildDayMovePlan(s, 2, 3, 2, 6)

        assertTrue(plan.occurrences.isEmpty())
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.removals.isEmpty())
        assertTrue(plan.errors.isEmpty())
    }

    @Test
    fun `regular and adjusted cards on the same source day move together`() {
        // c1 第 1 周周三被调到第 2 周周五；c2 是周五的常规课
        val existing = adjust(
            id = "a1",
            courseId = "c1",
            fromWeek = 1,
            toWeek = 2,
            toDay = 5,
            toStartSlot = 1,
            toSpan = 2,
        )
        val s = schedule(
            courses = listOf(
                course(id = "c1", day = 3, startSlot = 1, span = 2),
                course(id = "c2", name = "大学英语", day = 5, startSlot = 3, span = 2),
            ),
            adjustments = listOf(existing),
        )

        val plan = buildDayMovePlan(s, 2, 5, 2, 6)

        assertEquals(2, plan.occurrences.size)
        assertEquals(2, plan.upserts.size)
        assertTrue(plan.upserts.any { it.id == "a1" && it.toDayOfWeek == 6 })
        assertTrue(
            plan.upserts.any {
                it.courseId == "c2" && it.fromWeek == 2 && it.toDayOfWeek == 6
            },
        )
        assertTrue(plan.errors.isEmpty())
    }
}
