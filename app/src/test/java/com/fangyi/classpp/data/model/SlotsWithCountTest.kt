package com.fangyi.classpp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调整总节数（[slotsWithCount]）：减少 = 前缀裁剪，增加 = 三级补足——
 * ①末尾追加（课间 30 分钟，现有节全不动）→ ②新增节塞进末节结束~23:59（现有节仍不动）
 * → ③全天按每节课时长重排（首节开始不变、课间统一压缩、允许压到 0）。
 * 只有「总节数 × 每节课时长 > 第 1 节开始~23:59」才返回 null。
 */
class SlotsWithCountTest {

    private fun assertDayOrder(result: List<TimeSlotDef>) {
        result.forEach { slot ->
            val start = TimeText.parseMinutes(slot.startTime)
            val end = TimeText.parseMinutes(slot.endTime)
            assertTrue("非法时间文本：$slot", start != null && end != null)
            val startMinute = start ?: 0
            val endMinute = end ?: 0
            assertTrue("节次须 start < end：$slot", startMinute < endMinute)
            assertTrue("不得越过 23:59：$slot", endMinute <= 1439)
        }
        result.zipWithNext().forEach { (prev, next) ->
            val prevEnd = TimeText.parseMinutes(prev.endTime) ?: 0
            val nextStart = TimeText.parseMinutes(next.startTime) ?: 0
            assertTrue("节次须先后有序不重叠：$prev -> $next", prevEnd <= nextStart)
        }
    }

    @Test
    fun `shrinking keeps the prefix and drops tail slots`() {
        val result = slotsWithCount(DEFAULT_SLOTS, 3, 100)!!

        assertEquals(DEFAULT_SLOTS.take(3), result)
    }

    @Test
    fun `same count returns input unchanged`() {
        assertEquals(DEFAULT_SLOTS, slotsWithCount(DEFAULT_SLOTS, 5, 100))
    }

    @Test
    fun `growing within the default 30 minute break keeps every existing slot`() {
        // 第 6 节 21:20–23:00 仍放得下：第 1 级末尾追加，前 5 节原样
        val result = slotsWithCount(DEFAULT_SLOTS, 6, 100)!!

        assertEquals(6, result.size)
        assertEquals(DEFAULT_SLOTS, result.take(5))
        assertEquals(TimeSlotDef("21:20", "23:00"), result.last())
    }

    @Test
    fun `overflowing append squeezes new slots into the remaining window`() {
        // 12 节 × 45 分钟（课间 10），末节 18:05–18:50；加到 15 节、每节 100 分钟：
        // 末尾追加放不下（第 3 个新节 23:40–25:20 溢出），但剩余 309 分钟够 3 × 100，
        // 课间 (309-300)/3 = 3 分钟，现有 12 节完全不动
        val small = (0 until 12).map { i ->
            val start = 8 * 60 + i * 55
            TimeSlotDef(TimeText.format(start), TimeText.format(start + 45))
        }

        val result = slotsWithCount(small, 15, 100)!!

        assertEquals(15, result.size)
        assertEquals(small, result.take(12))
        assertEquals(TimeSlotDef("18:53", "20:33"), result[12])
        assertEquals(TimeSlotDef("20:36", "22:16"), result[13])
        assertEquals(TimeSlotDef("22:19", "23:59"), result[14])
        assertDayOrder(result)
    }

    @Test
    fun `zero break is allowed when the window fits exactly`() {
        // 单节 8:00–13:59：剩余窗口恰好 600 分钟 = 6 节 × 100 分钟 → 课间压到 0
        val slots = listOf(TimeSlotDef("8:00", "13:59"))

        val result = slotsWithCount(slots, 7, 100)!!

        assertEquals(7, result.size)
        assertEquals(slots, result.take(1))
        assertEquals(TimeSlotDef("13:59", "15:39"), result[1])
        assertEquals(TimeSlotDef("22:19", "23:59"), result.last())
        result.zipWithNext().forEach { (prev, next) ->
            assertEquals(prev.endTime, next.startTime)
        }
        assertDayOrder(result)
    }

    @Test
    fun `falls back to whole day relayout when the remaining window is too short`() {
        // 默认 5 节（末节 19:10–20:50）加到 7 节：剩余 189 < 2 × 100，全天重排；
        // 7 × 100 = 700 ≤ 959（8:00–23:59），课间 (959-700)/6 = 43 → 取上限 30
        val result = slotsWithCount(DEFAULT_SLOTS, 7, 100)!!

        assertEquals(7, result.size)
        assertEquals("8:00", result.first().startTime)
        assertEquals(TimeSlotDef("21:00", "22:40"), result.last())
        result.forEach { slot ->
            val start = TimeText.parseMinutes(slot.startTime)!!
            val end = TimeText.parseMinutes(slot.endTime)!!
            assertEquals(100, end - start)
        }
        result.zipWithNext().forEach { (prev, next) ->
            val gap = TimeText.parseMinutes(next.startTime)!! - TimeText.parseMinutes(prev.endTime)!!
            assertEquals(30, gap)
        }
        assertDayOrder(result)
    }

    @Test
    fun `whole day relayout compresses the break when needed`() {
        // 默认 5 节加到 9 节：9 × 100 = 900 ≤ 959，课间 (959-900)/8 = 7 分钟
        val result = slotsWithCount(DEFAULT_SLOTS, 9, 100)!!

        assertEquals(9, result.size)
        assertEquals("8:00", result.first().startTime)
        assertEquals(TimeSlotDef("22:16", "23:56"), result.last())
        result.zipWithNext().forEach { (prev, next) ->
            val gap = TimeText.parseMinutes(next.startTime)!! - TimeText.parseMinutes(prev.endTime)!!
            assertEquals(7, gap)
        }
        assertDayOrder(result)
    }

    @Test
    fun `null only when the day cannot hold count times duration`() {
        // 10 × 100 = 1000 > 959：加节/剩余窗口/全天重排都排不下
        assertNull(slotsWithCount(DEFAULT_SLOTS, 10, 100))
        // 同一时刻 9 节仍可（900 ≤ 959），说明拦截点正是"每节课时长也放不下"
        assertEquals(9, slotsWithCount(DEFAULT_SLOTS, 9, 100)!!.size)
    }

    @Test
    fun `empty slot list cannot be grown`() {
        assertNull(slotsWithCount(emptyList(), 2, 100))
    }
}
