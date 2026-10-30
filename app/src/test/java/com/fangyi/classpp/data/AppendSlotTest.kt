package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.appendSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 追加节次：保留原表、末节结束 +30 分钟课间 +100 分钟时长、越过 23:59 返回 null */
class AppendSlotTest {

    @Test
    fun `appending to default slots adds 21 20-23 00 and keeps prefix`() {
        val result = appendSlot(DEFAULT_SLOTS)!!

        assertEquals(6, result.size)
        assertEquals(DEFAULT_SLOTS, result.take(5))
        assertEquals(TimeSlotDef("21:20", "23:00"), result.last())
    }

    @Test
    fun `appended period starts 30 minutes after last end with 100 minute duration`() {
        val result = appendSlot(listOf(TimeSlotDef("8:00", "10:00")))!!

        assertEquals(TimeSlotDef("10:30", "12:10"), result.last())
    }

    @Test
    fun `returns null when new end would pass 23_59`() {
        assertNull(appendSlot(listOf(TimeSlotDef("21:00", "23:30"))))
        // 默认表加到第 7 节（23:30–25:10）同样溢出
        assertNull(appendSlot(appendSlot(DEFAULT_SLOTS)!!))
    }

    @Test
    fun `boundary new end exactly 23_59 is allowed`() {
        val result = appendSlot(listOf(TimeSlotDef("21:00", "21:49")))

        assertEquals(TimeSlotDef("22:19", "23:59"), result!!.last())
    }

    @Test
    fun `null on empty list and malformed last end time`() {
        assertNull(appendSlot(emptyList()))
        assertNull(appendSlot(listOf(TimeSlotDef("8:00", "25:00"))))
        assertNull(appendSlot(listOf(TimeSlotDef("8:00", "abc"))))
    }

    @Test
    fun `appended slot never overlaps the previous one`() {
        val result = appendSlot(DEFAULT_SLOTS)!!
        val prevEnd = DEFAULT_SLOTS.last().endTime.split(':')
        val newStart = result.last().startTime.split(':')
        val prevMinutes = prevEnd[0].toInt() * 60 + prevEnd[1].toInt()
        val startMinutes = newStart[0].toInt() * 60 + newStart[1].toInt()
        assertTrue(startMinutes - prevMinutes == 30)
    }
}
