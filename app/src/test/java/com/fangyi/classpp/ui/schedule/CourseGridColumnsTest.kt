package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表网格的列几何：竖分隔线由 [columnDividerXs] 按列数等分算出，
 * 5 天视图与 7 天视图必须与 `Modifier.weight(1f)` 的实际列宽落在同一处——
 * 日期带、课程行、页右缘接缝线三处的画线位置都取自这个函数。
 */
class CourseGridColumnsTest {

    private val width = 1080f

    /** 列数 → 内部边界条数：七列六条、五列四条、一列没有 */
    @Test
    fun dividerCountIsOneLessThanColumns() {
        assertEquals(6, columnDividerXs(width, 7).size)
        assertEquals(4, columnDividerXs(width, 5).size)
        assertEquals(0, columnDividerXs(width, 1).size)
    }

    /** 每条边界都恰好落在"第 i 列与第 i+1 列"的交界：宽度 × i / days */
    @Test
    fun dividersLandExactlyOnColumnBoundaries() {
        listOf(5, 7).forEach { days ->
            columnDividerXs(width, days).forEachIndexed { index, x ->
                assertEquals(
                    "days=$days 第 ${index + 1} 条",
                    width * (index + 1) / days,
                    x,
                    0.001f,
                )
            }
        }
    }

    /** 七列比五列多出的两条落在最后两列的交界上（周六/周日两列） */
    @Test
    fun sevenDayViewAddsTheWeekendBoundaries() {
        val five = columnDividerXs(width, 5)
        val seven = columnDividerXs(width, 7)
        // 列宽不同（1080/5 ≠ 1080/7），故只能按值比较中间那几条的走向
        assertTrue(five.size == 4 && seven.size == 6)
        assertTrue(seven[5] > seven[4] && seven[4] > seven[3])
        // 七列的边界都比五列的同序号边界靠左（列更窄）
        five.indices.forEach { i -> assertTrue("i=$i", seven[i] < five[i]) }
        assertEquals(width * 6 / 7, seven.last(), 0.001f)
        assertTrue(seven.last() < width)
    }

    /** 始终严格递增、恒在宽度之内（不会画出屏幕外或重合的线） */
    @Test
    fun dividersAreStrictlyIncreasingAndInsideTheWidth() {
        listOf(5, 7).forEach { days ->
            val xs = columnDividerXs(width, days)
            xs.zipWithNext().forEach { (a, b) -> assertTrue("days=$days $a < $b", a < b) }
            assertTrue(xs.all { it > 0f && it < width })
        }
    }

    /** 极端输入不炸：宽度未测出（0）→ 全 0；列数非法（0/负）→ 空列表 */
    @Test
    fun degenerateInputsProduceNoOutOfRangeLines() {
        assertTrue(columnDividerXs(0f, 7).all { it == 0f })
        assertEquals(emptyList<Float>(), columnDividerXs(width, 0))
        assertEquals(emptyList<Float>(), columnDividerXs(width, -3))
    }
}
