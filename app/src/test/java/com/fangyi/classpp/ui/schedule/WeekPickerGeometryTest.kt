package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.ui.theme.ClassppTextTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周数弹窗的尺寸模型：
 *
 * - 宽度 ≤384dp：卡片占满「屏幕 − 两侧 17dp」，方格从 49dp 等比缩小，间距、字号、
 *   圆角、投影一起乘同一比例；
 * - 宽度 384–450dp：卡片继续占满「屏幕 − 两侧 17dp」，只放大方格（49→60dp），
 *   间距 8dp、字号 23sp、圆角、投影保持基准值；
 * - 宽度 >450dp：卡片封顶 416dp（方格 60dp）居中，投影留白自然变宽；
 * - 高度：只受「胶囊下缘 → 窗口底部」的预算约束（顶部投影留白向上压进头部区域，
 *   不占预算），行数参与反推；放不下时整卡（含字号）等比缩小，缩到
 *   [WeekCellSizeMin] 仍不够就改为内部滚动。
 *
 * 断言直接引用 ScheduleHeader.kt 里的常量（同为 internal），调整参考值时这里同步生效。
 */
class WeekPickerGeometryTest {

    private val baseCell = WeekCellSizeBase.value
    private val maxCell = WeekCellSizeMax.value
    private val minCell = WeekCellSizeMin.value
    private val cellSpace = WeekPickerCellSpace.value
    private val gap = WeekPickerGap.value
    private val edge = WeekPickerEdgeMargin.value
    private val sideMin = WeekPickerSideMinPadding.value
    private val sideMax = WeekPickerShadowPadding.value
    private val bottomMax = WeekPickerShadowBottomPadding.value
    private val shadowMin = WeekPickerShadowMinPadding.value
    private val numberFont = ClassppTextTokens.weekGridNumber.fontSize.value
    private val numberLineHeight = ClassppTextTokens.weekGridNumber.lineHeight.value
    private val shadowElevation = WeekPickerShadowElevation.value
    private val cardCorner = WeekPickerCardCorner.value
    private val cellCorner = WeekPickerCellCorner.value

    private val columns = 6

    /** 384dp 宽的参考卡宽：6 列方格 + 7 道间距/内边距 */
    private val baseCardWidth = columns * baseCell + (columns + 1) * cellSpace

    /** 450dp 宽的封顶卡宽 */
    private val maxCardWidth = columns * maxCell + (columns + 1) * cellSpace

    private fun geometry(width: Float, belowPill: Float, rows: Int = 4) =
        weekPickerGeometry(
            availableWidthDp = width,
            availableHeightBelowPillDp = belowPill,
            rows = rows,
        )

    private fun cardWidth(g: WeekPickerGeometry): Float =
        columns * g.cellSize.value + (columns + 1) * g.cellSpace.value

    private fun cardHeight(g: WeekPickerGeometry, rows: Int = 4): Float =
        rows * g.cellSize.value + (rows + 1) * g.cellSpace.value

    private fun fontOf(g: WeekPickerGeometry): Float =
        ClassppTextTokens.weekGridNumberScaled(g.visualScale).fontSize.value

    private fun assertRoundedShapesScaleWith(g: WeekPickerGeometry) {
        assertEquals(cardCorner * g.visualScale, g.cardCorner.value, EPS)
        assertEquals(cellCorner * g.visualScale, g.cellCorner.value, EPS)
        assertEquals(shadowElevation * g.visualScale, g.shadowElevation.value, EPS)
        assertEquals(numberFont * g.visualScale, fontOf(g), EPS)
        assertEquals(numberLineHeight * g.visualScale,
            ClassppTextTokens.weekGridNumberScaled(g.visualScale).lineHeight.value, EPS)
    }

    /** 384dp 是正常手机的参考点：49dp 方格、卡片 350dp、两侧各 17dp */
    @Test
    fun width384IsTheNormalPhoneReference() {
        val g = geometry(384f, 800f)
        assertEquals(baseCell, g.cellSize.value, EPS)
        assertEquals(cellSpace, g.cellSpace.value, EPS)
        assertEquals(baseCardWidth, cardWidth(g), EPS)
        assertEquals(sideMin, g.sidePadding.value, EPS)
        assertEquals(1f, g.visualScale, EPS)
        assertEquals(numberFont, fontOf(g), EPS)
        assertEquals(17f, (384f - cardWidth(g)) / 2f, EPS)
        assertEquals(384f, cardWidth(g) + 2 * g.sidePadding.value + 2 * edge, EPS)
        assertRoundedShapesScaleWith(g)
    }

    /** 384–450dp：卡片跟着屏宽铺满 17dp 边距，只放大方格，字号/间距/圆角/投影不变 */
    @Test
    fun widths384To450GrowCellsOnly() {
        listOf(385f, 400f, 425f, 449f, 450f).forEach { width ->
            val g = geometry(width, 800f)
            val expectedCell = (width - 2 * edge - 2 * sideMin - 7 * cellSpace) / columns
            assertEquals("width=$width", expectedCell, g.cellSize.value, EPS)
            assertEquals("width=$width", cellSpace, g.cellSpace.value, EPS)
            assertEquals("width=$width", numberFont, fontOf(g), EPS)
            assertEquals("width=$width", 1f, g.visualScale, EPS)
            assertEquals("width=$width", 7 * cellSpace + columns * expectedCell,
                cardWidth(g), EPS)
            assertEquals("width=$width", sideMin, g.sidePadding.value, EPS)
            assertEquals("width=$width", 17f, (width - cardWidth(g)) / 2f, EPS)
            assertRoundedShapesScaleWith(g)
        }
        assertEquals(60f, geometry(450f, 800f).cellSize.value, EPS)
    }

    /** 超过 450dp：弹窗不再放大，卡片 416dp 居中，留白随屏宽增长并封顶 50dp */
    @Test
    fun widthsAbove450StopGrowing() {
        listOf(451f, 500f, 518f, 800f, 1200f).forEach { width ->
            val g = geometry(width, 800f)
            assertEquals("width=$width", maxCell, g.cellSize.value, EPS)
            assertEquals("width=$width", cellSpace, g.cellSpace.value, EPS)
            assertEquals("width=$width", numberFont, fontOf(g), EPS)
            assertEquals("width=$width", 1f, g.visualScale, EPS)
            assertEquals("width=$width", maxCardWidth, cardWidth(g), EPS)
            val expectedSide = ((width - maxCardWidth) / 2f - edge).coerceIn(0f, sideMax)
            assertEquals("width=$width", expectedSide, g.sidePadding.value, EPS)
            assertTrue("width=$width", cardWidth(g) + 2 * g.sidePadding.value <= width + EPS)
        }
    }

    /** 384dp 以下：所有 token 按同一比例缩小，卡片仍铺满 17dp 边距 */
    @Test
    fun widthsBelow384ShrinkEveryTokenProportionally() {
        listOf(360f, 320f, 280f, 240f).forEach { width ->
            val expectedScale = (width - 2 * edge - 2 * sideMin) / baseCardWidth
            val g = geometry(width, 800f)
            assertEquals("width=$width", baseCell * expectedScale, g.cellSize.value, EPS)
            assertEquals("width=$width", cellSpace * expectedScale, g.cellSpace.value, EPS)
            assertEquals("width=$width", expectedScale, g.visualScale, EPS)
            assertEquals("width=$width", numberFont * expectedScale, fontOf(g), EPS)
            assertEquals("width=$width", sideMin, g.sidePadding.value, EPS)
            assertEquals("width=$width", width - 2 * edge,
                cardWidth(g) + 2 * g.sidePadding.value, EPS)
            assertEquals("width=$width", 17f, (width - cardWidth(g)) / 2f, EPS)
            assertRoundedShapesScaleWith(g)
        }
    }

    /** 矮窗放不下时整卡等比缩小（含字号），卡片高度正好收进胶囊下方预算 */
    @Test
    fun shortWindowsShrinkEverythingProportionally() {
        // 384dp 宽、4 行的基准卡高 = 4×49 + 5×8 = 236
        listOf(240f, 212f, 180f, 160f).forEach { belowPill ->
            val expectedScale = (belowPill - gap - shadowMin) / (4f * baseCell + 5 * cellSpace)
            val g = geometry(384f, belowPill)
            assertEquals("below=$belowPill", baseCell * expectedScale, g.cellSize.value, EPS)
            assertEquals("below=$belowPill", cellSpace * expectedScale, g.cellSpace.value, EPS)
            assertEquals("below=$belowPill", expectedScale, g.visualScale, EPS)
            assertEquals("below=$belowPill", numberFont * expectedScale, fontOf(g), EPS)
            assertFalse("below=$belowPill", g.scrollable)
            assertEquals("below=$belowPill", belowPill,
                gap + cardHeight(g) + g.bottomPadding.value, EPS)
            assertRoundedShapesScaleWith(g)
        }
    }

    /** 450dp 宽的手机遇到矮窗：同样按高度等比缩小，字号可以低于 23sp */
    @Test
    fun shortWindowShrinksFontEvenAtMaximumWidth() {
        val tall = geometry(450f, 800f)
        assertEquals(maxCell, tall.cellSize.value, EPS)
        assertEquals(numberFont, fontOf(tall), EPS)

        val belowPill = 264f
        val expectedScale = (belowPill - gap - shadowMin) / (4f * maxCell + 5 * cellSpace)
        val short = geometry(450f, belowPill)
        assertEquals(expectedScale, short.visualScale, EPS)
        assertEquals(maxCell * expectedScale, short.cellSize.value, EPS)
        assertEquals(numberFont * expectedScale, fontOf(short), EPS)
        assertTrue(short.cellSize.value < maxCell)
        assertTrue(fontOf(short) < numberFont)
        assertEquals(belowPill, gap + cardHeight(short) + short.bottomPadding.value, EPS)
    }

    /** 行数参与高度反推：2 行短学期在 212dp 预算下仍是满格，4 行才会缩 */
    @Test
    fun rowCountParticipatesInHeightFit() {
        val twoRows = geometry(384f, 212f, rows = 2)
        assertEquals(baseCell, twoRows.cellSize.value, EPS)
        assertEquals(numberFont, fontOf(twoRows), EPS)
        assertTrue(gap + cardHeight(twoRows, 2) + twoRows.bottomPadding.value <= 212f + EPS)

        val fourRows = geometry(384f, 212f, rows = 4)
        assertTrue(fourRows.cellSize.value < baseCell)
        assertEquals(212f, gap + cardHeight(fourRows, 4) + fourRows.bottomPadding.value, EPS)
    }

    /** 缩到 28dp 仍放不下：不再压卡片，改为内部滚动（卡片框锁在预算内） */
    @Test
    fun belowReadableFloorScrollsInsteadOfSqueezing() {
        val belowPill = 144f
        val g = geometry(384f, belowPill)
        assertEquals(minCell, g.cellSize.value, EPS)
        assertTrue(g.scrollable)
        assertEquals(belowPill - gap - shadowMin, g.maxCardHeight.value, EPS)
        assertEquals(shadowMin, g.bottomPadding.value, EPS)
        assertEquals(belowPill, gap + g.maxCardHeight.value + g.bottomPadding.value, EPS)
        assertTrue(g.maxCardHeight.value < cardHeight(g))
        assertRoundedShapesScaleWith(g)
    }

    /** 极端窄窗：宽度绑定时允许跌破可读下限，横向绝不溢出（高度还有空间，不触发滚动） */
    @Test
    fun narrowWidthBeatsTheReadabilityFloor() {
        val g = geometry(120f, 800f)
        assertFalse(g.scrollable)
        assertTrue("cell=${g.cellSize.value}", g.cellSize.value < minCell)
        assertTrue(cardWidth(g) + 2 * g.sidePadding.value <= 120f + EPS)
        assertEquals(numberFont * (120f - 34f) / baseCardWidth, fontOf(g), EPS)
    }

    /** 宽度未知（首帧之前/预览）：回落到参考设计，投影留白取上限 */
    @Test
    fun unknownWindowFallsBackToReferenceLayout() {
        val g = geometry(0f, 0f)
        assertEquals(1f, g.visualScale, EPS)
        assertEquals(baseCell, g.cellSize.value, EPS)
        assertEquals(sideMin, g.sidePadding.value, EPS)
        assertEquals(bottomMax, g.bottomPadding.value, EPS)
        assertFalse(g.scrollable)
    }

    /** 任取窗口尺寸与行数：不横向溢出；不滚动时高度收进预算；滚动时卡片框收进预算 */
    @Test
    fun everyWindowFitsWithoutOverflow() {
        val widths = listOf(120f, 200f, 240f, 280f, 320f, 360f, 384f, 400f, 450f, 600f, 1000f)
        val heights = listOf(80f, 120f, 160f, 212f, 240f, 288f, 400f, 800f)
        val rowCounts = listOf(1, 2, 3, 4, 6, 10)
        rowCounts.forEach { rows ->
            widths.forEach { width ->
                heights.forEach { belowPill ->
                    val g = geometry(width, belowPill, rows)
                    val tag = "width=$width below=$belowPill rows=$rows"
                    assertRoundedShapesScaleWith(g)
                    assertTrue(tag, g.cellSize.value >= 0f)
                    assertTrue("$tag cell=${g.cellSize.value}", g.cellSize.value <= maxCell + EPS)
                    assertTrue("$tag gap=${g.cellSpace.value}", g.cellSpace.value <= cellSpace + EPS)
                    assertTrue(tag, g.sidePadding.value >= 0f)
                    assertTrue(tag, g.sidePadding.value <= sideMax + EPS)
                    assertTrue(tag, g.bottomPadding.value >= 0f)
                    assertTrue(tag, g.bottomPadding.value <= bottomMax + EPS)
                    assertTrue(tag, cardWidth(g) + 2 * g.sidePadding.value <= width + EPS)
                    if (g.scrollable) {
                        assertTrue("$tag cell=${g.cellSize.value}", g.cellSize.value <= minCell + EPS)
                        assertTrue(
                            tag,
                            gap + g.maxCardHeight.value + g.bottomPadding.value
                                <= belowPill + EPS,
                        )
                    } else {
                        assertTrue(
                            tag,
                            gap + cardHeight(g, rows) + g.bottomPadding.value
                                <= belowPill + EPS,
                        )
                    }
                }
            }
        }
    }

    /** 方格随宽度单调不减并在 450dp 封顶；字号在 384dp 后恒为 23sp；高度越小越小 */
    @Test
    fun cellSizeIsMonotonicInBothAxes() {
        var previousCell = 0f
        var previousGap = 0f
        var previousFont = 0f
        listOf(200f, 240f, 280f, 320f, 360f, 384f, 385f, 400f, 450f, 451f, 800f)
            .forEach { width ->
                val g = geometry(width, 800f)
                assertTrue("width=$width cell=${g.cellSize.value}",
                    g.cellSize.value >= previousCell - EPS)
                assertTrue("width=$width gap=${g.cellSpace.value}",
                    g.cellSpace.value >= previousGap - EPS)
                assertTrue("width=$width font=${fontOf(g)}", fontOf(g) >= previousFont - EPS)
                previousCell = g.cellSize.value
                previousGap = g.cellSpace.value
                previousFont = fontOf(g)
            }
        assertEquals(maxCell, previousCell, EPS)
        assertEquals(numberFont, previousFont, EPS)

        var previousHeightCell = 0f
        listOf(140f, 160f, 180f, 212f, 240f, 288f, 400f, 800f).forEach { belowPill ->
            val cell = geometry(384f, belowPill).cellSize.value
            assertTrue("below=$belowPill cell=$cell previous=$previousHeightCell",
                cell >= previousHeightCell - EPS)
            previousHeightCell = cell
        }
        assertEquals(baseCell, previousHeightCell, EPS)
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
