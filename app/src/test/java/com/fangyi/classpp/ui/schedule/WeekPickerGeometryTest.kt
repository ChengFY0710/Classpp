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
 * - 高度 ≥ 400dp：由宽度决定——窗口未到封顶临界宽（自然卡宽 + 2×反推留白 + 2×边距）时
 *   完全按留白反推，其中 384dp–450dp 这段继续随屏宽等比放大（49dp→60dp）；窗口到达封顶
 *   临界宽起（宽屏手机/横屏/平板），边长封顶 [WeekCellSizeMax]（60dp），卡片保持自然宽度居中；
 * - 高度低于 400dp：不论窗口多宽都从 49dp 基准乘「高度/400」缩小，40dp 封底，
 *   宽窗口不再继承 49–60dp 的放大值；
 * - 窄屏宽度反推值本身低于高度钳制值时仍以宽度为准，保证卡片贴边不横向溢出。
 *
 * 方格反推仍按 [CardShadowPadding]（16dp）留白基准走，尺寸规则与放宽前完全一致；
 * [WeekPickerShadowPadding]（50dp）是目标留白，实际取值受窗口约束：Popup 内容按屏幕
 * AT_MOST 测量、窗口不能越出屏幕（越出会把方格压掉一列），左右留白按「(屏宽 − 卡宽)/2
 * − 边距」封顶（适配段恒为 16dp、窗口距屏幕两侧 1dp，与放宽前一致），顶部同样按可用
 * 高度封顶（空间充足时恒为 50dp）。见 [weekPickerSideShadowPaddingDp] /
 * [weekPickerTopShadowPaddingDp]。
 *
 * 断言直接引用 ScheduleHeader.kt 里的常量（同为 internal），
 * 调整留白/上限/下限时这里同步生效，不会各写一份数字而悄悄失配。
 */
class WeekPickerGeometryTest {

    private val edgeMargin = WeekPickerEdgeMargin.value

    /** 方格反推的留白基准：尺寸规则与投影留白放宽前完全一致，不随实际留白（50dp）加宽而变 */
    private val sizingPadding = CardShadowPadding.value

    /** 目标投影留白（左右上）：实际取值按可用空间封顶，屏幕足够时才吃满 */
    private val actualPadding = WeekPickerShadowPadding.value
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
        shadowPaddingDp = sizingPadding,
        cardPaddingDp = cardPadding,
        cellSpaceDp = cellSpace,
        maxCellSizeDp = maxCell,
        heightBaseCellSizeDp = heightBase,
        minCellSizeDp = minCell,
        columns = columns,
    )

    /** 卡片（含卡内边距）宽度 */
    private fun cardWidth(availableWidthDp: Float, availableHeightDp: Float = 800f): Float =
        columns * cell(availableWidthDp, availableHeightDp) + (columns - 1) * cellSpace + 2 * cardPadding

    /** 弹窗窗口（卡片＋按屏宽封顶后的实际左右留白）宽度 */
    private fun windowWidth(availableWidthDp: Float, availableHeightDp: Float = 800f): Float =
        cardWidth(availableWidthDp, availableHeightDp) +
            2 * weekPickerSideShadowPaddingDp(availableWidthDp, cell(availableWidthDp, availableHeightDp))

    /** 封顶临界宽：反推值恰好涨到上限的窗口宽度（按反推留白基准计），更宽的窗口一律封顶 */
    private val capBindingWidth =
        columns * maxCell + (columns - 1) * cellSpace + 2 * cardPadding + 2 * sizingPadding + 2 * edgeMargin

    /** 384dp → 450dp：方格继续随屏宽等比放大，尺寸规则与投影留白放宽前完全一致 */
    @Test
    fun cellsScaleFrom49To60Between384And450() {
        val startWidth = 384f
        val endWidth = capBindingWidth
        assertEquals("384dp", 49f, cell(startWidth), 0.001f)
        assertEquals("450dp", 60f, cell(endWidth), 0.001f)
        assertEquals("417dp 中点", 54.5f, cell((startWidth + endWidth) / 2f), 0.001f)
    }

    /** 临界宽以下：左右留白按屏宽封顶回 16dp 基准，窗口距屏幕两侧恰为 edgeMargin
     *  （与放宽前完全一致）；卡片到屏幕两侧恒为 17dp——目标 50dp 只在屏幕放得下时才吃满 */
    @Test
    fun cardKeepsScreenMarginAndWindowStaysInsideScreenBelowTheBindingWidth() {
        listOf(320f, 360f, 384f, capBindingWidth * 0.9f, capBindingWidth - 1f).forEach { width ->
            assertEquals(
                "availableWidth=$width",
                edgeMargin + sizingPadding,
                (width - cardWidth(width)) / 2,
                0.001f,
            )
            assertEquals(
                "availableWidth=$width",
                width - 2 * edgeMargin,
                windowWidth(width),
                0.001f,
            )
            assertEquals(
                "availableWidth=$width",
                sizingPadding,
                weekPickerSideShadowPaddingDp(width, cell(width)),
                0.001f,
            )
        }
    }

    /** 临界宽起（含宽屏手机/横屏/平板）：边长封顶，卡片保持自然宽度居中，留白自然变宽；
     *  窗口始终收在屏幕内（越出会压掉方格一列），屏宽足够后才吃满目标留白 */
    @Test
    fun cellIsCappedFromTheBindingWidthUp() {
        listOf(capBindingWidth, capBindingWidth + 1f, capBindingWidth * 1.2f, capBindingWidth * 2f).forEach { width ->
            assertEquals("availableWidth=$width", maxCell, cell(width), 0.001f)
            assertTrue("availableWidth=$width", windowWidth(width) <= width + 0.001f)
            assertTrue("availableWidth=$width", (width - cardWidth(width)) / 2 >= edgeMargin + sizingPadding - 0.001f)
            assertTrue(
                "availableWidth=$width",
                weekPickerSideShadowPaddingDp(width, cell(width)) <= actualPadding + 0.001f,
            )
        }
    }

    /** 左右留白按屏宽封顶：窄屏恒为 16dp 基准（窗口距屏幕 1dp、与放宽前一致），
     *  屏宽 ≥ 卡片 + 2×(目标留白 + 边距) 时才吃满 50dp */
    @Test
    fun sideShadowPaddingIsClampedByScreenWidth() {
        // 适配段：可分配宽度只有「16dp 反推留白 + 1dp 边距」
        listOf(320f, 360f, 384f, capBindingWidth - 1f).forEach { width ->
            assertEquals(
                "width=$width",
                sizingPadding,
                weekPickerSideShadowPaddingDp(width, cell(width)),
                0.001f,
            )
        }
        // 封顶段：卡片固定 416dp，500dp 屏先分到 (500−416)/2−1 = 41dp，518dp 起才够 50dp
        assertEquals(
            "500dp",
            (500f - cardWidth(500f)) / 2 - edgeMargin,
            weekPickerSideShadowPaddingDp(500f, cell(500f)),
            0.001f,
        )
        assertEquals(actualPadding, weekPickerSideShadowPaddingDp(520f, cell(520f)), 0.001f)
        assertEquals(actualPadding, weekPickerSideShadowPaddingDp(800f, cell(800f)), 0.001f)
        // 宽度未知（首帧之前/预览）：回落 16dp 基准，避免内容越出未知宽度的窗口
        assertEquals(sizingPadding, weekPickerSideShadowPaddingDp(0f, cell(384f)), 0.001f)
    }

    /** 顶部留白按可用高度封顶：空间充足恒为 50dp，内容超高时回落，最低 16dp 基准 */
    @Test
    fun topShadowPaddingIsClampedByAvailableHeight() {
        assertEquals(actualPadding, weekPickerTopShadowPaddingDp(800f, cell(384f), 3), 0.001f)
        assertEquals(sizingPadding, weekPickerTopShadowPaddingDp(300f, 40f, 6), 0.001f)
        // 高度未知（首帧之前/预览）：回落 16dp 基准
        assertEquals(sizingPadding, weekPickerTopShadowPaddingDp(0f, cell(384f), 3), 0.001f)
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

    /** 窄屏宽度反推值本身低于 40dp：高度下限不得反过来撑破宽度适配，卡片仍贴边
     *  （到屏幕边缘恒 17dp，窗口两侧各越出 33dp 属预期） */
    @Test
    fun heightFloorNeverBreaksWidthFitOnNarrowWindows() {
        val widthFit = cell(320f)
        assertTrue("320dp 宽反推值应低于下限", widthFit < minCell)
        listOf(400f, 320f, 240f, 120f).forEach { height ->
            assertEquals("height=$height", widthFit, cell(320f, height), 0.001f)
            assertEquals(
                "height=$height",
                edgeMargin + sizingPadding,
                (320f - cardWidth(320f, height)) / 2,
                0.001f,
            )
        }
    }
}
