package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekPatternTest {

    @Test
    fun `segment contains honours parity`() {
        val all = WeekSegment(1, 16, Parity.ALL)
        val odd = WeekSegment(1, 16, Parity.ODD)
        val even = WeekSegment(1, 16, Parity.EVEN)

        assertTrue(all.contains(1)); assertTrue(all.contains(16))
        assertFalse(all.contains(0)); assertFalse(all.contains(17))

        assertTrue(odd.contains(1)); assertTrue(odd.contains(15))
        assertFalse(odd.contains(2)); assertFalse(odd.contains(16))

        assertTrue(even.contains(2)); assertTrue(even.contains(16))
        assertFalse(even.contains(1)); assertFalse(even.contains(0))
    }

    @Test
    fun `pattern is union of segments`() {
        // 1-8 全选 + 10-16 双周：9 不上课、10 上（双周）、11 不上、12 上
        val p = WeekPattern(
            listOf(
                WeekSegment(1, 8, Parity.ALL),
                WeekSegment(10, 16, Parity.EVEN),
            ),
        )
        assertTrue(p.contains(1)); assertTrue(p.contains(8))
        assertFalse(p.contains(9))
        assertTrue(p.contains(10))
        assertFalse(p.contains(11))
        assertTrue(p.contains(12))
        assertFalse(p.contains(17))
    }

    @Test
    fun `intersect requires overlapping weeks with compatible parity`() {
        // 同奇偶重叠
        assertTrue(
            WeekPattern(listOf(WeekSegment(1, 8, Parity.ODD)))
                .intersects(WeekPattern(listOf(WeekSegment(5, 12, Parity.ODD)))),
        )
        // 单双互斥：区间重叠但没有共同周
        assertFalse(
            WeekPattern(listOf(WeekSegment(1, 16, Parity.ODD)))
                .intersects(WeekPattern(listOf(WeekSegment(1, 16, Parity.EVEN)))),
        )
        // ALL 与 ODD：重叠区含奇数周 → 相交
        assertTrue(
            WeekPattern(listOf(WeekSegment(1, 16, Parity.ALL)))
                .intersects(WeekPattern(listOf(WeekSegment(2, 3, Parity.ODD)))),
        )
        // ALL 与 ODD 但重叠区只有单个偶数周 {2} → 不相交
        assertFalse(
            WeekPattern(listOf(WeekSegment(2, 2, Parity.ALL)))
                .intersects(WeekPattern(listOf(WeekSegment(1, 3, Parity.ODD)))),
        )
        // 区间不相接
        assertFalse(
            WeekPattern(listOf(WeekSegment(1, 4)))
                .intersects(WeekPattern(listOf(WeekSegment(5, 8)))),
        )
        // 同奇偶但重叠区只含偶数周：[1,2] ODD ∩ [2,3] ODD = 空
        assertFalse(
            WeekPattern(listOf(WeekSegment(1, 2, Parity.ODD)))
                .intersects(WeekPattern(listOf(WeekSegment(2, 3, Parity.ODD)))),
        )
    }

    @Test
    fun `empty pattern never intersects and has no max week`() {
        val empty = WeekPattern()
        assertFalse(empty.intersects(WeekPattern(listOf(WeekSegment(1, 16)))))
        assertFalse(
            WeekPattern(listOf(WeekSegment(1, 16))).intersects(empty),
        )
        assertFalse(empty.contains(1))
        assertNull(empty.maxWeek())
    }

    @Test
    fun `maxWeek returns largest segment end`() {
        assertEquals(
            16,
            WeekPattern(listOf(WeekSegment(1, 8), WeekSegment(10, 16))).maxWeek(),
        )
        assertEquals(8, WeekPattern(listOf(WeekSegment(3, 8))).maxWeek())
    }

    @Test
    fun `everyWeek factory covers full term`() {
        val p = WeekPattern.everyWeek(16)
        assertTrue(p.contains(1)); assertTrue(p.contains(16))
        assertFalse(p.contains(17))
    }
}
