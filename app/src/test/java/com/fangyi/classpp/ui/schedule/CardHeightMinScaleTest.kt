package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滑条可调下限 [cardHeightMinScale] = 当前课表最短一节把卡片高度压到当前最低高度的倍率，
 * 向下取到 0.01，并钳在可存储范围 0.1–1.5 内。最低高度随「显示教师」开关变
 * （默认字号行高 60/44dp），因此下限也随开关重算；按该倍率渲染时最短节恰好落在行高下限。
 */
class CardHeightMinScaleTest {

    @Test
    fun `100 minute periods use the base row threshold`() {
        assertEquals(0.40f, cardHeightMinScale(100, 5, 1f, showTeacher = true), 0.0001f)
        assertEquals(0.37f, cardHeightMinScale(100, 7, 1f, showTeacher = true), 0.0001f)
        assertEquals(0.29f, cardHeightMinScale(100, 5, 1f, showTeacher = false), 0.0001f)
        assertEquals(0.27f, cardHeightMinScale(100, 7, 1f, showTeacher = false), 0.0001f)
    }

    @Test
    fun `shorter periods need a higher minimum scale`() {
        assertEquals(0.88f, cardHeightMinScale(45, 5, 1f, showTeacher = true), 0.0001f)
        assertEquals(0.83f, cardHeightMinScale(45, 7, 1f, showTeacher = true), 0.0001f)
        assertEquals(1.33f, cardHeightMinScale(30, 5, 1f, showTeacher = true), 0.0001f)
        assertEquals(1.25f, cardHeightMinScale(30, 7, 1f, showTeacher = true), 0.0001f)
        assertEquals(1.50f, cardHeightMinScale(20, 5, 1f, showTeacher = true), 0.0001f)

        assertEquals(0.65f, cardHeightMinScale(45, 5, 1f, showTeacher = false), 0.0001f)
        assertEquals(0.61f, cardHeightMinScale(45, 7, 1f, showTeacher = false), 0.0001f)
        assertEquals(0.97f, cardHeightMinScale(30, 5, 1f, showTeacher = false), 0.0001f)
        assertEquals(0.91f, cardHeightMinScale(30, 7, 1f, showTeacher = false), 0.0001f)
        assertEquals(1.46f, cardHeightMinScale(20, 5, 1f, showTeacher = false), 0.0001f)
    }

    @Test
    fun `minimum scale follows font scale`() {
        assertEquals(0.49f, cardHeightMinScale(100, 5, 1.3f, showTeacher = true), 0.0001f)
        assertEquals(0.46f, cardHeightMinScale(100, 7, 1.3f, showTeacher = true), 0.0001f)
        assertEquals(0.35f, cardHeightMinScale(100, 5, 1.3f, showTeacher = false), 0.0001f)
        assertEquals(0.33f, cardHeightMinScale(100, 7, 1.3f, showTeacher = false), 0.0001f)
    }

    @Test
    fun `minimum scale is clamped to the storage range`() {
        // 10 分钟节需要约 4.0× / 2.9×，超过滑条上限 → 封顶 1.5
        assertEquals(1.50f, cardHeightMinScale(10, 5, 1f, showTeacher = true), 0.0001f)
        assertEquals(1.50f, cardHeightMinScale(10, 5, 1f, showTeacher = false), 0.0001f)
        // 200 分钟节只需 0.19× / 0.14×，但不低于可存储下限 0.1
        assertEquals(0.18f, cardHeightMinScale(200, 7, 1f, showTeacher = true), 0.0001f)
        assertEquals(0.13f, cardHeightMinScale(200, 7, 1f, showTeacher = false), 0.0001f)
    }

    @Test
    fun `shortest duration scans all slots and falls back for empty list`() {
        val slots = listOf(
            TimeSlot(1, "8:00", "9:40"),
            TimeSlot(2, "10:05", "10:50"),
            TimeSlot(3, "11:20", "13:00"),
        )
        assertEquals(45, slots.shortestDurationMinutes())
        assertEquals(100, emptyList<TimeSlot>().shortestDurationMinutes())
    }

    @Test
    fun `rendering at the minimum scale lands exactly on the row floor`() {
        data class Case(val duration: Int, val days: Int, val fontScale: Float, val teacher: Boolean)
        val cases = listOf(
            Case(100, 5, 1f, true),
            Case(100, 5, 1f, false),
            Case(100, 7, 1f, true),
            Case(100, 7, 1f, false),
            Case(45, 5, 1f, true),
            Case(45, 5, 1f, false),
            Case(30, 5, 1f, true),
            Case(30, 5, 1f, false),
            Case(20, 5, 1f, true),
            Case(20, 5, 1f, false),
            Case(10, 5, 1f, true),
            Case(10, 5, 1f, false),
            Case(200, 7, 1f, true),
            Case(200, 7, 1f, false),
            Case(100, 5, 1.3f, true),
            Case(100, 5, 1.3f, false),
        )
        for ((duration, days, fontScale, teacher) in cases) {
            val floor = minCardRowHeight(teacher, fontScale)
            val scale = cardHeightMinScale(duration, days, fontScale, teacher)
            val row = slotRowHeight(duration, days, scale, floor).value
            assertEquals(
                "duration=$duration days=$days fontScale=$fontScale teacher=$teacher scale=$scale",
                floor.value,
                row,
                0.05f,
            )
        }
    }
}
