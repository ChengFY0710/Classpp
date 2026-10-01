package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周数弹窗的水平几何：六列、正方形、列间距与卡片内边距都不变，
 * 方格边长由可用宽度反推 —— 于是不论机型宽窄，弹窗（含四周投影留白）距屏幕左右两侧恒为 25dp。
 */
class WeekPickerGeometryTest {

    // 与 ScheduleHeader.kt 中的常量一一对应
    private val edgeMargin = 25f
    private val shadowPadding = 16f
    private val cardPadding = 8f
    private val cellSpace = 8f
    private val columns = 6

    private fun cell(availableWidthDp: Float) = weekCellSizeDp(
        availableWidthDp = availableWidthDp,
        edgeMarginDp = edgeMargin,
        shadowPaddingDp = shadowPadding,
        cardPaddingDp = cardPadding,
        cellSpaceDp = cellSpace,
        columns = columns,
    )

    /** 各屏宽下：弹窗窗口（卡片＋投影留白）到屏幕边缘的距离都应是 edgeMargin */
    @Test
    fun popupKeepsEdgeMarginOnEveryScreenWidth() {
        listOf(320f, 360f, 393f, 411f, 427f, 480f, 600f).forEach { width ->
            val side = cell(width)
            val cardWidth = columns * side + (columns - 1) * cellSpace + 2 * cardPadding
            val windowWidth = cardWidth + 2 * shadowPadding
            assertEquals(
                "availableWidth=$width",
                edgeMargin,
                (width - windowWidth) / 2,
                0.001f,
            )
        }
    }

    /** 427dp（Pixel 10 Pro AVD）上边长≈48dp：与改动前的固定 48dp 观感一致 */
    @Test
    fun phoneWidthKeepsThePreviousLook() {
        assertEquals(48.1f, cell(426.7f), 0.2f)
    }

    /** 窄屏变小、宽屏变大；极端窄屏也不会算出 0 或负数 */
    @Test
    fun cellSizeFollowsWidthAndStaysPositive() {
        assertTrue(cell(320f) > 0f)
        assertTrue(cell(320f) < cell(360f))
        assertTrue(cell(360f) < cell(426.7f))
        assertTrue(cell(426.7f) < cell(600f))
        assertEquals(0f, cell(100f), 0.001f)
    }
}
