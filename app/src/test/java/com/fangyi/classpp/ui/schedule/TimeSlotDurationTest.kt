package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 节次时长解析（[TimeSlot.durationMinutes]）：网格行高按它伸缩（见 [slotRowHeight]）。
 * 合法起止取差值；非法文本或差值非正回退默认 100 分钟，与默认作息各节时长一致。
 */
class TimeSlotDurationTest {

    @Test
    fun `duration is the difference between end and start`() {
        assertEquals(100, TimeSlot(1, "8:00", "9:40").durationMinutes())
        assertEquals(45, TimeSlot(2, "10:05", "10:50").durationMinutes())
    }

    @Test
    fun `accepts zero padded hours`() {
        assertEquals(100, TimeSlot(1, "08:00", "09:40").durationMinutes())
    }

    @Test
    fun `invalid or negative durations fall back to the default`() {
        assertEquals(100, TimeSlot(1, "abc", "9:40").durationMinutes())
        assertEquals(100, TimeSlot(1, "", "").durationMinutes())
        assertEquals(100, TimeSlot(1, "9:40", "8:00").durationMinutes())
    }
}
