package com.fangyi.classpp.data.model

import com.fangyi.classpp.data.ScheduleValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合并相邻两小节：固定两两配对 + 30 分钟间隙阈值、课程精确对齐、span≥2 门控、时长众数 */
class SlotMergeTest {

    private val smallSlots = listOf(
        TimeSlotDef("8:00", "8:45"),
        TimeSlotDef("8:55", "9:40"),
        TimeSlotDef("10:10", "10:55"),
        TimeSlotDef("11:05", "11:50"),
    )

    private fun course(
        id: String,
        startSlot: Int,
        span: Int,
        dayOfWeek: Int = 1,
    ) = CourseEntry(
        id = id,
        name = id,
        teacher = "",
        location = "",
        dayOfWeek = dayOfWeek,
        startSlot = startSlot,
        span = span,
        weeks = WeekPattern.everyWeek(16),
        color = CourseColor.Green,
    )

    @Test
    fun `merges adjacent small periods into big ones`() {
        // 至少一门课真正占用 ≥2 节才允许合并（空课表没有可重映射的课程）
        val plan = previewSlotMerge(smallSlots, listOf(course("a", startSlot = 1, span = 2)), 5)

        assertEquals(
            listOf(TimeSlotDef("8:00", "9:40"), TimeSlotDef("10:10", "11:50")),
            plan.newSlots,
        )
        assertEquals(2, plan.mergedPairCount)
        assertEquals(100, plan.newDurationMinutes)
        assertEquals(listOf(course("a", startSlot = 1, span = 1)), plan.newCourses)
        assertTrue(plan.canMerge)
    }

    @Test
    fun `merged duration is computed from actual times`() {
        val slots = listOf(
            TimeSlotDef("8:00", "8:40"),
            TimeSlotDef("8:45", "9:25"),
            TimeSlotDef("10:00", "10:40"),
            TimeSlotDef("10:45", "11:25"),
            TimeSlotDef("12:00", "13:40"),
        )

        val plan = previewSlotMerge(slots, emptyList(), 5)

        assertEquals(
            listOf(
                TimeSlotDef("8:00", "9:25"),
                TimeSlotDef("10:00", "11:25"),
                TimeSlotDef("12:00", "13:40"),
            ),
            plan.newSlots,
        )
        assertEquals(85, plan.newDurationMinutes)
    }

    @Test
    fun `gap over threshold keeps the pair apart`() {
        val slots = listOf(TimeSlotDef("8:00", "8:45"), TimeSlotDef("9:16", "10:01"))

        val plan = previewSlotMerge(slots, emptyList(), 5)

        assertEquals(0, plan.mergedPairCount)
        assertEquals(slots, plan.newSlots)
        assertFalse(plan.canMerge)
    }

    @Test
    fun `gap exactly at threshold still merges`() {
        val slots = listOf(TimeSlotDef("8:00", "8:45"), TimeSlotDef("9:15", "10:00"))

        val plan = previewSlotMerge(slots, emptyList(), 5)

        assertEquals(listOf(TimeSlotDef("8:00", "10:00")), plan.newSlots)
        assertEquals(1, plan.mergedPairCount)
    }

    @Test
    fun `overlapping malformed slots are not merged`() {
        val slots = listOf(TimeSlotDef("8:00", "9:00"), TimeSlotDef("8:30", "9:30"))

        val plan = previewSlotMerge(slots, emptyList(), 5)

        assertEquals(0, plan.mergedPairCount)
    }

    @Test
    fun `odd tail slot is kept as is`() {
        val slots = smallSlots + TimeSlotDef("14:30", "15:15")

        val plan = previewSlotMerge(slots, emptyList(), 5)

        assertEquals(3, plan.newSlots.size)
        assertEquals(TimeSlotDef("14:30", "15:15"), plan.newSlots.last())
    }

    @Test
    fun `span two and span four shrink correctly across pairs`() {
        val courses = listOf(
            course("two", startSlot = 1, span = 2, dayOfWeek = 1),
            course("four", startSlot = 1, span = 4, dayOfWeek = 2),
            course("second-pair", startSlot = 3, span = 2, dayOfWeek = 3),
        )

        val plan = previewSlotMerge(smallSlots, courses, 5)

        assertTrue(plan.unalignedCourses.isEmpty())
        assertEquals(
            listOf(1 to 1, 1 to 2, 2 to 1),
            plan.newCourses.map { it.startSlot to it.span },
        )
    }

    @Test
    fun `course in skipped pair keeps its slots`() {
        val slots = listOf(
            TimeSlotDef("8:00", "8:45"),
            TimeSlotDef("8:55", "9:40"),
            TimeSlotDef("14:30", "15:15"),
            TimeSlotDef("16:00", "16:45"),
        )

        val plan = previewSlotMerge(slots, listOf(course("pm", startSlot = 3, span = 2)), 5)

        assertTrue(plan.canMerge)
        val mapped = plan.newCourses.single()
        assertEquals(2, mapped.startSlot)
        assertEquals(2, mapped.span)
    }

    @Test
    fun `course starting on second slot of a pair cannot align`() {
        val plan = previewSlotMerge(smallSlots, listOf(course("c", startSlot = 2, span = 2)), 5)

        assertFalse(plan.canMerge)
        assertEquals(listOf("c"), plan.unalignedCourses.map { it.id })
    }

    @Test
    fun `course ending on first slot of a pair cannot align`() {
        val plan = previewSlotMerge(smallSlots, listOf(course("c", startSlot = 1, span = 3)), 5)

        assertFalse(plan.canMerge)
        assertEquals(listOf("c"), plan.unalignedCourses.map { it.id })
    }

    @Test
    fun `single slot course blocks merge`() {
        // 第 3 节与前一节间隙过大不参与合并，单节课在这里可以精确对齐，
        // 但仍被「所有课程 span≥2」规则阻断
        val slots = listOf(
            TimeSlotDef("8:00", "8:45"),
            TimeSlotDef("8:55", "9:40"),
            TimeSlotDef("14:30", "15:15"),
        )
        val plan = previewSlotMerge(
            slots,
            listOf(course("single", startSlot = 3, span = 1)),
            5,
        )

        assertFalse(plan.canMerge)
        assertTrue(plan.unalignedCourses.isEmpty())
        assertEquals(listOf("single"), plan.singleSlotCourses.map { it.id })
        assertEquals(2, plan.newCourses.single().startSlot)
    }

    @Test
    fun `empty course list cannot merge`() {
        // 没有课程占用 ≥2 节时合并只是把节表对半砍，没有可重映射的课程 → 阻挡
        val plan = previewSlotMerge(smallSlots, emptyList(), 5)

        assertEquals(2, plan.mergedPairCount)
        assertFalse(plan.hasMultiSlotCourse)
        assertFalse(plan.canMerge)
    }

    @Test
    fun `single slot courses only cannot merge`() {
        val courses = listOf(
            course("a", startSlot = 1, span = 1),
            course("b", startSlot = 3, span = 1),
        )

        val plan = previewSlotMerge(smallSlots, courses, 5)

        assertEquals(2, plan.mergedPairCount)
        assertFalse(plan.hasMultiSlotCourse)
        assertEquals(listOf("a", "b"), plan.singleSlotCourses.map { it.id })
        assertFalse(plan.canMerge)
    }

    @Test
    fun `one or zero slots cannot merge`() {
        val one = previewSlotMerge(listOf(TimeSlotDef("8:00", "9:40")), emptyList(), 5)

        assertFalse(one.canMerge)
        assertEquals(1, one.newSlots.size)
        assertEquals(100, one.newDurationMinutes)

        val none = previewSlotMerge(emptyList(), emptyList(), 5)

        assertFalse(none.canMerge)
        assertTrue(none.newSlots.isEmpty())
        assertEquals(DEFAULT_SLOT_DURATION_MINUTES, none.newDurationMinutes)
    }

    @Test
    fun `malformed times are not paired`() {
        val slots = listOf(TimeSlotDef("8:00", "abc"), TimeSlotDef("8:55", "9:40"))

        val plan = previewSlotMerge(slots, emptyList(), 5)

        assertEquals(0, plan.mergedPairCount)
        assertEquals(slots, plan.newSlots)
    }

    @Test
    fun `duration mode tie picks the larger value`() {
        val slots = listOf(
            TimeSlotDef("8:00", "8:45"),
            TimeSlotDef("8:55", "9:50"),
            TimeSlotDef("10:20", "11:50"),
        )

        val plan = previewSlotMerge(slots, emptyList(), 5)

        assertEquals(
            listOf(TimeSlotDef("8:00", "9:50"), TimeSlotDef("10:20", "11:50")),
            plan.newSlots,
        )
        assertEquals(110, plan.newDurationMinutes)
    }

    @Test
    fun `hidden course with out of range slots is preserved and does not block`() {
        val hidden = course("hidden", startSlot = 99, span = 1, dayOfWeek = 6)
        val visible = course("visible", startSlot = 1, span = 2)

        val plan = previewSlotMerge(smallSlots, listOf(visible, hidden), daysPerWeek = 5)

        assertTrue(plan.canMerge)
        assertTrue(plan.hasMultiSlotCourse)
        assertEquals(listOf("visible", "hidden"), plan.newCourses.map { it.id })
        // 越界隐藏课原样保留；可见课按合并对重映射
        assertEquals(hidden, plan.newCourses.single { it.id == "hidden" })
        assertEquals(1, plan.newCourses.single { it.id == "visible" }.span)
        assertTrue(plan.unalignedCourses.isEmpty())
        assertTrue(plan.singleSlotCourses.isEmpty())
    }

    @Test
    fun `hidden course alone cannot enable merge`() {
        // 唯一的多节课程落在越界隐藏格：它不会被重映射，合并没有实际对象
        val hidden = course("hidden", startSlot = 99, span = 2, dayOfWeek = 6)

        val plan = previewSlotMerge(smallSlots, listOf(hidden), daysPerWeek = 5)

        assertFalse(plan.hasMultiSlotCourse)
        assertFalse(plan.canMerge)
        assertEquals(listOf(hidden), plan.newCourses)
    }

    @Test
    fun `hidden valid single slot course still blocks`() {
        val hidden = course("hidden", startSlot = 1, span = 1, dayOfWeek = 6)

        val plan = previewSlotMerge(smallSlots, listOf(hidden), daysPerWeek = 5)

        assertFalse(plan.canMerge)
        assertEquals(listOf("hidden"), plan.singleSlotCourses.map { it.id })
    }

    @Test
    fun `merged result passes schedule validation`() {
        val courses = listOf(
            course("a", startSlot = 1, span = 2, dayOfWeek = 1),
            course("b", startSlot = 3, span = 2, dayOfWeek = 2),
        )
        val plan = previewSlotMerge(smallSlots, courses, 5)
        val schedule = Schedule(
            id = "s",
            name = "test",
            termStart = IsoDate.parse("2026-03-02"),
            termEnd = IsoDate.parse("2026-06-19"),
            slots = plan.newSlots,
            slotDurationMinutes = plan.newDurationMinutes,
            courses = plan.newCourses,
        )

        assertTrue(plan.canMerge)
        assertEquals(emptyList<Any>(), ScheduleValidator.validate(schedule))
    }
}
