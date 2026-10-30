package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠态周数胶囊的水平定位：终点由胶囊实测宽度反推，
 * 胶囊「结束侧」边缘距屏幕同侧边缘恒为固定内边距，不随周数文字长短漂移。
 *
 * 被修复的行为：旧实现 `width - pillWidth + 110dp` 用固定偏移量代替宽度参与计算，
 * 胶囊右缘会跑到屏幕右侧之外 110dp，露出的部分却随文字宽度变化。
 */
class ScheduleHeaderGeometryTest {

    private val screenWidth = 1080
    private val endPad = 63 // ≈ PillEndPadding(24dp) @ 2.625x

    /** 「第 9 周」→「第 10 周」文字变宽时，距屏幕右侧的距离必须保持不变 */
    @Test
    fun collapsedPillKeepsConstantGapFromTheEndEdge() {
        listOf(248, 262, 276, 330, 412).forEach { pillWidth ->
            val x = collapsedPillX(screenWidth, pillWidth, endPad, isRtl = false)
            assertEquals(
                "pillWidth=$pillWidth",
                endPad.toFloat(),
                screenWidth - (x + pillWidth),
                0.001f,
            )
        }
    }

    @Test
    fun collapsedPillHugsTheEndEdgeInBothDirections() {
        assertEquals(
            (screenWidth - 276 - endPad).toFloat(),
            collapsedPillX(screenWidth, 276, endPad, isRtl = false),
            0f,
        )
        // RTL 下结束侧在左，轴对称：左缘即内边距
        assertEquals(
            endPad.toFloat(),
            collapsedPillX(screenWidth, 276, endPad, isRtl = true),
            0f,
        )
    }

    /** 折叠到位后胶囊整体留在屏幕内（旧实现在任何字宽下都越界） */
    @Test
    fun collapsedPillStaysInsideTheScreen() {
        listOf(248, 276, 412).forEach { pillWidth ->
            val x = collapsedPillX(screenWidth, pillWidth, endPad, isRtl = false)
            assertTrue("pillWidth=$pillWidth x=$x", x >= 0f && x + pillWidth <= screenWidth)
        }
    }
}
