package com.fangyi.classpp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 调整每节课时间（[slotsWithDuration]）：各节开始时间不动、结束按新时长重算；
 * 上一节变长压到下一节时下一节开始顺延并级联；越过 23:59 返回 null。
 */
class SlotsWithDurationTest {

    @Test
    fun `shorter duration recalculates every end and keeps starts`() {
        val result = slotsWithDuration(DEFAULT_SLOTS, 90)!!

        assertEquals(
            listOf(
                TimeSlotDef("8:00", "9:30"),
                TimeSlotDef("10:10", "11:40"),
                TimeSlotDef("14:30", "16:00"),
                TimeSlotDef("16:40", "18:10"),
                TimeSlotDef("19:10", "20:40"),
            ),
            result,
        )
    }

    @Test
    fun `longer duration keeps starts that still leave a break`() {
        // 100 → 120：第 1 节 8:00–10:00 仍早于第 2 节原开始 10:10
        val result = slotsWithDuration(DEFAULT_SLOTS, 120)!!

        assertEquals(TimeSlotDef("8:00", "10:00"), result[0])
        assertEquals(TimeSlotDef("10:10", "12:10"), result[1])
        assertEquals(TimeSlotDef("14:30", "16:30"), result[2])
    }

    @Test
    fun `overlapping next slot is pushed to the previous end and cascades`() {
        // 三节 45 分钟、课间 10；改成 60 分钟：
        // 第 1 节 8:00–9:00 压到第 2 节原开始 8:55 → 顺延 9:00–10:00，
        // 又压到第 3 节原开始 9:50 → 10:00–11:00
        val slots = listOf(
            TimeSlotDef("8:00", "8:45"),
            TimeSlotDef("8:55", "9:40"),
            TimeSlotDef("9:50", "10:35"),
        )

        val result = slotsWithDuration(slots, 60)!!

        assertEquals(
            listOf(
                TimeSlotDef("8:00", "9:00"),
                TimeSlotDef("9:00", "10:00"),
                TimeSlotDef("10:00", "11:00"),
            ),
            result,
        )
    }

    @Test
    fun `same duration keeps the layout unchanged`() {
        assertEquals(DEFAULT_SLOTS, slotsWithDuration(DEFAULT_SLOTS, 100))
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<TimeSlotDef>(), slotsWithDuration(emptyList(), 45))
    }

    @Test
    fun `duration pushing past end of day returns null`() {
        val slots = listOf(TimeSlotDef("8:00", "8:45"), TimeSlotDef("22:30", "23:15"))

        assertNull(slotsWithDuration(slots, 200))
    }
}
