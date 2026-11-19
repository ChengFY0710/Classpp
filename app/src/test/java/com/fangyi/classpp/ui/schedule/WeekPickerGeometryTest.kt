package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周数弹窗的几何：六列、正方形、列间距与卡片内边距都不变，方格边长随屏宽伸缩，
 * 超过封顶临界宽后一律封顶 [WeekCellSizeMax]；窗口高度低于 [WeekPickerHeightThreshold]
 * 时改由高度钳制（优先于宽度），一律从 [WeekCellSizeHeightBase]（49dp）等比缩小，
 * 缩到 [WeekCellSizeMin] 后不再变化——
 *
 * - 高度 ≥ 400dp：由宽度决定——窗口未到封顶临界宽（自然窗口宽 + 2×边距）时完全按留白反推，
 *   弹窗距屏幕两侧恒为 WeekPickerEdgeMargin，其中 384dp–450dp 这段继续随屏宽等比放大
 *   （49dp→60dp）；窗口到达封顶临界宽起（宽屏手机/横屏/平板），边长封顶 [WeekCellSizeMax]
 *   （60dp），弹窗保持自然宽度居中；
 * - 高度低于 400dp：不论窗口多宽都从 49dp 基准乘「高度/400」缩小，40dp 封底，
 *   宽窗口不再继承 49–60dp 的放大值；
 * - 窄屏宽度反推值本身低于高度钳制值时仍以宽度为准，保证弹窗贴边不横向溢出。
 *
 * 断言直接引用 ScheduleHeader.kt 里的常量（同为 internal），
 * 调整留白/上限/下限时这里同步生效，不会各写一份数字而悄悄失配。
 */
class WeekPickerGeometryTest {

    private val edgeMargin = WeekPickerEdgeMargin.value
    private val shadowPadding = CardShadowPadding.value
    private val cardPadding = WeekPickerCellSpace.value
    private val cellSpace = WeekPickerCellSpace.value
    private val maxCell = WeekCellSizeMax.value
    private val heightBase = WeekCellSizeHeightBase.value
    private val minCell = WeekCellSizeMin.value
    private val heightThreshold = WeekPickerHeightThreshold.value
    private val columns = 6

    /** 默认高度取充足值（800dp）：不触发高度钳制，等价于只看宽度的旧行为 */
    private fun cell(availableWidthDp: Float, availableHeightDp: Float = 800f) = weekCellSizeDp(
        availableWidthDp = availableWidthDp,
        availableHeightDp = availableHeightDp,
        edgeMarginDp = edgeMargin,
        shadowPaddingDp = shadowPadding,
        cardPaddingDp = cardPadding,
        cellSpaceDp = cellSpace,
        maxCellSizeDp = maxCell,
        heightBaseCellSizeDp = heightBase,
        minCellSizeDp = minCell,
        columns = columns,
    )

    /** 弹窗窗口（卡片＋投影留白）宽度 */
    private fun windowWidth(availableWidthDp: Float, availableHeightDp: Float = 800f): Float {
        val side = cell(availableWidthDp, availableHeightDp)
        val cardWidth = columns * side + (columns - 1) * cellSpace + 2 * cardPadding
        return cardWidth + 2 * shadowPadding
    }

    /** 自然宽度（封顶后的弹窗窗口宽度） */
    private val naturalWindowWidth =
        columns * maxCell + (columns - 1) * cellSpace + 2 * cardPadding + 2 * shadowPadding

    /** 封顶临界宽：反推值恰好涨到上限的窗口宽度，更宽的窗口一律封顶 */
    private val capBindingWidth = naturalWindowWidth + 2 * edgeMargin

    /** 384dp → 450dp：方格继续随屏宽等比放大，弹窗窗口始终 = 屏宽 − 2×边距 */
    @Test
    fun cellsScaleFrom49To60Between384And450() {
        val startWidth = 384f
        val endWidth = 450f
        assertEquals("384dp", 49f, cell(startWidth), 0.001f)
        assertEquals("450dp", 60f, cell(endWidth), 0.001f)
        assertEquals("417dp 中点", 54.5f, cell((startWidth + endWidth) / 2f), 0.001f)
        listOf(startWidth, (startWidth + endWidth) / 2f, endWidth).forEach { width ->
            assertEquals(
                "availableWidth=$width",
                width - 2 * edgeMargin,
                windowWidth(width),
                0.001f,
            )
        }
    }

    /** 临界宽以下：弹窗窗口到屏幕两侧的距离恒为 edgeMargin（弹窗几乎贴边） */
    @Test
    fun popupKeepsEdgeMarginBelowTheBindingWidth() {
        listOf(320f, 360f, capBindingWidth * 0.9f, capBindingWidth - 1f).forEach { width ->
            assertEquals(
                "availableWidth=$width",
                edgeMargin,
                (width - windowWidth(width)) / 2,
                0.001f,
            )
        }
    }

    /** 临界宽起（含宽屏手机/横屏/平板）：边长封顶，弹窗保持自然宽度居中，留白自然变宽；
     * 临界宽处留白恰为边距，浮点噪声以 0.001dp 容差吸收 */
    @Test
    fun cellIsCappedFromTheBindingWidthUp() {
        listOf(capBindingWidth, capBindingWidth + 1f, capBindingWidth * 1.2f, capBindingWidth * 2f).forEach { width ->
            assertEquals("availableWidth=$width", maxCell, cell(width), 0.001f)
            assertEquals("availableWidth=$width", naturalWindowWidth, windowWidth(width), 0.001f)
            assertTrue("availableWidth=$width", (width - windowWidth(width)) / 2 >= edgeMargin - 0.001f)
        }
    }

    /** 临界处是硬边界：临界宽以下按留白反推（恒小于上限），到临界宽立刻收到上限 */
    @Test
    fun theCapStartsExactlyAtTheBindingWidth() {
        assertTrue("临界宽-1 应小于上限", cell(capBindingWidth - 1f) < maxCell)
        assertEquals(maxCell, cell(capBindingWidth), 0.001f)
    }

    /** 临界宽以下随屏宽单调递增且恒为正；极端窄屏也不会算出负数 */
    @Test
    fun cellSizeFollowsWidthBelowTheBindingAndStaysPositive() {
        assertTrue(cell(capBindingWidth * 0.5f) > 0f)
        assertTrue(cell(capBindingWidth * 0.5f) < cell(capBindingWidth * 0.75f))
        assertTrue(cell(capBindingWidth * 0.75f) < cell(capBindingWidth * 0.9f))
        assertTrue(cell(capBindingWidth * 0.9f) < cell(capBindingWidth - 1f))
        assertEquals(0f, cell(80f), 0.001f)
    }

    /** 高度达到参考高度起不参与高度钳制：任意 ≥ 400dp 的高度都与只看宽度的结果一致 */
    @Test
    fun heightAtOrAboveThresholdKeepsWidthFit() {
        listOf(heightThreshold, heightThreshold + 1f, 480f, 800f, 1600f).forEach { height ->
            listOf(320f, 384f, 450f, 800f).forEach { width ->
                assertEquals(
                    "width=$width height=$height",
                    cell(width),
                    cell(width, height),
                    0.001f,
                )
            }
        }
    }

    /** 高度低于 400dp：方格按「高度/400」等比缩小，49dp 基准缩到 40dp 后不再变化 */
    @Test
    fun cellsShrinkProportionallyBelowThresholdAndStopAt40() {
        assertEquals("400dp 高", 49f, cell(384f, 400f), 0.001f)
        assertEquals("360dp 高", heightBase * 0.9f, cell(384f, 360f), 0.001f)
        assertEquals("340dp 高", heightBase * 0.85f, cell(384f, 340f), 0.001f)
        // 49 × 0.8 = 39.2 → 40dp 封底
        assertEquals("320dp 高", minCell, cell(384f, 320f), 0.001f)
        listOf(300f, 240f, 160f, 80f).forEach { height ->
            assertEquals("height=$height", minCell, cell(384f, height), 0.001f)
        }
    }

    /** 高度钳制优先于宽度：低于 400dp 后不论窗口多宽，一律从 49dp 基准缩小（不再取 60dp） */
    @Test
    fun heightClampOverridesWidthBelowThreshold() {
        // 400dp 高仍走宽度：384dp 宽 49dp、450dp 宽起 60dp
        assertEquals(49f, cell(384f, 400f), 0.001f)
        assertEquals(60f, cell(450f, 400f), 0.001f)
        assertEquals(60f, cell(800f, 400f), 0.001f)
        // 低于 400dp 立刻切到 49dp 基准，宽度不再参与
        listOf(384f, 450f, 800f, 1200f).forEach { width ->
            assertEquals("width=$width", heightBase * 0.9f, cell(width, 360f), 0.001f)
            assertEquals("width=$width", heightBase * 0.85f, cell(width, 340f), 0.001f)
            assertEquals("width=$width", minCell, cell(width, 320f), 0.001f)
        }
        assertEquals("399dp 高", heightBase * 399f / 400f, cell(800f, 399f), 0.001f)
    }

    /** 窄屏宽度反推值本身低于 40dp：高度下限不得反过来撑破宽度适配，弹窗仍贴边 */
    @Test
    fun heightFloorNeverBreaksWidthFitOnNarrowWindows() {
        val widthFit = cell(320f)
        assertTrue("320dp 宽反推值应低于下限", widthFit < minCell)
        listOf(400f, 320f, 240f, 120f).forEach { height ->
            assertEquals("height=$height", widthFit, cell(320f, height), 0.001f)
            assertEquals(
                "height=$height",
                edgeMargin,
                (320f - windowWidth(320f, height)) / 2,
                0.001f,
            )
        }
    }
}
