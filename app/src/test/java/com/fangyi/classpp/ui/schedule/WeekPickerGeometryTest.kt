package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周数弹窗的水平几何：六列、正方形、列间距与卡片内边距都不变，方格边长随屏宽伸缩——
 *
 * - 窗口 < [WeekCellSizeCapMinWidth]（600dp）：完全按留白反推，弹窗距屏幕两侧恒为 WeekPickerEdgeMargin；
 * - 窗口 ≥ 600dp（横屏/平板）：边长封顶 [WeekCellSizeMax]（48dp），弹窗保持自然宽度居中。
 *
 * 断言直接引用 ScheduleHeader.kt 里的常量（同为 internal），
 * 调整留白/上限/阈值时这里同步生效，不会各写一份数字而悄悄失配。
 */
class WeekPickerGeometryTest {

    private val edgeMargin = WeekPickerEdgeMargin.value
    private val shadowPadding = CardShadowPadding.value
    private val cardPadding = WeekPickerCellSpace.value
    private val cellSpace = WeekPickerCellSpace.value
    private val maxCell = WeekCellSizeMax.value
    private val capMinWidth = WeekCellSizeCapMinWidth.value
    private val columns = 6

    private fun cell(availableWidthDp: Float) = weekCellSizeDp(
        availableWidthDp = availableWidthDp,
        edgeMarginDp = edgeMargin,
        shadowPaddingDp = shadowPadding,
        cardPaddingDp = cardPadding,
        cellSpaceDp = cellSpace,
        maxCellSizeDp = maxCell,
        capMinWidthDp = capMinWidth,
        columns = columns,
    )

    /** 弹窗窗口（卡片＋投影留白）宽度 */
    private fun windowWidth(availableWidthDp: Float): Float {
        val side = cell(availableWidthDp)
        val cardWidth = columns * side + (columns - 1) * cellSpace + 2 * cardPadding
        return cardWidth + 2 * shadowPadding
    }

    /** 自然宽度（封顶后的弹窗窗口宽度） */
    private val naturalWindowWidth =
        columns * maxCell + (columns - 1) * cellSpace + 2 * cardPadding + 2 * shadowPadding

    /** 阈值以下：弹窗窗口到屏幕两侧的距离恒为 edgeMargin（弹窗几乎贴边） */
    @Test
    fun popupKeepsEdgeMarginBelowTheCapWidth() {
        listOf(320f, 360f, 393f, 426.7f, 480f, capMinWidth - 1f).forEach { width ->
            assertEquals(
                "availableWidth=$width",
                edgeMargin,
                (width - windowWidth(width)) / 2,
                0.001f,
            )
        }
    }

    /** 阈值及以上（含横屏/平板）：边长封顶，弹窗保持自然宽度居中，留白自然变宽 */
    @Test
    fun cellIsCappedFromTheCapWidthUp() {
        listOf(capMinWidth, 640f, 800f, 1024f, 1280f).forEach { width ->
            assertEquals("availableWidth=$width", maxCell, cell(width), 0.001f)
            assertEquals("availableWidth=$width", naturalWindowWidth, windowWidth(width), 0.001f)
            assertTrue("availableWidth=$width", (width - windowWidth(width)) / 2 >= edgeMargin)
        }
    }

    /** 阈值处是硬边界：600dp 以下按留白反推（可以大于 48dp），一过 600dp 立刻收到 48dp */
    @Test
    fun theCapStartsExactlyAtTheCapWidth() {
        assertTrue("capMinWidth-1 应大于上限", cell(capMinWidth - 1f) > maxCell)
        assertEquals(maxCell, cell(capMinWidth), 0.001f)
    }

    /** 阈值以下随屏宽单调递增且恒为正；极端窄屏也不会算出负数 */
    @Test
    fun cellSizeFollowsWidthBelowTheCapAndStaysPositive() {
        assertTrue(cell(320f) > 0f)
        assertTrue(cell(320f) < cell(360f))
        assertTrue(cell(360f) < cell(426.7f))
        assertTrue(cell(426.7f) < cell(capMinWidth - 1f))
        assertEquals(0f, cell(100f), 0.001f)
    }
}
