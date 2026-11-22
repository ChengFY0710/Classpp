package com.fangyi.classpp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认作息生成：小节数复用 [DEFAULT_SLOTS] 前缀，大节数在 8:00–22:30 窗口内等时长生成；
 * 窗口放不下（节数过大）返回 null 而非 require 抛异常——公开 helper 不把上限藏在
 * 运行时异常里（此前 30 节直接崩溃，Wakeup mapper 只能特意钳到 29 规避）。
 */
class DefaultSlotsForTest {

    @Test
    fun `small counts reuse the default slots prefix`() {
        assertEquals(DEFAULT_SLOTS.take(1), defaultSlotsFor(1))
        assertEquals(DEFAULT_SLOTS.take(3), defaultSlotsFor(3))
        assertEquals(DEFAULT_SLOTS, defaultSlotsFor(5))
    }

    @Test
    fun `every count from one to thirty neither throws nor breaks shape`() {
        // 1..29 都能生成；30 节 × 课间 30 分钟已占满全天窗口 → null（不再抛 require）
        for (count in 1..30) {
            val slots = defaultSlotsFor(count)
            if (count <= 29) {
                assertNotNull("count=$count 应能生成默认作息", slots)
                assertEquals(count, slots!!.size)
            } else {
                assertNull("30 节超过 8:00–22:30 窗口容量，应返回 null", slots)
            }
        }
    }

    @Test
    fun `twenty nine slots still fit with one minute duration`() {
        val slots = defaultSlotsFor(29)!!
        assertEquals(29, slots.size)
        assertEquals("8:00", slots.first().startTime)
        // 末节结束不越过 22:30（窗口右端）
        val lastEnd = TimeText.parseMinutes(slots.last().endTime)!!
        assertTrue(lastEnd <= 22 * 60 + 30)
    }
}
