package com.fangyi.classpp.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 菜单落位与生长锚点（A1）的纯几何：
 *
 * - 生长锚点 [menuAnchorFraction]：由**最终**卡片矩形与锚点中心反推。锚点在卡片下方 →
 *   顶边（0f）；在上方 → 底边（1f）；横向/纵向落在卡内 → 按中心投影，越界钳到 0/1。
 *   翻转、钳位后不需要调用点再猜角；
 * - 参考点换算 [anchorPointOf]：锚点矩形内的比例点 → 窗口坐标。默认几何中心；锚点比菜单
 *   宽得多时（整行卡 + 自适应宽菜单）必须取靠边一端，否则中心落在菜单外被钳到卡缘；
 * - 图层换算 [menuAnchorOrigin]：缩放图层 = 卡片 + 四周透明投影留白，锚点必须按卡片
 *   边缘反推（直接写 0f/1f 会锚在留白外缘，动画中卡片会横竖各偏约 3dp）；尺寸未量出
 *   时返回 null，调用方沿用上一帧锚点，不能把 NaN 灌进 TransformOrigin；
 * - 声明式落位 [placeMenu]：首选方位 → 越界翻转（下方放不下翻上方、右侧放不下翻右缘
 *   对齐）→ 交调用方钳进安全区；
 * - [clampCard]：卡片钳进可用区，菜单比可用区还大时以左上缘为准。
 *
 * 断言直接引用 PopupMenuPopup.kt 里的 internal 函数，调参时这里同步生效。
 */
class MenuPlacementTest {

    private val density = Density(1f)

    /** 锚点：x 100..300、y 500..560 的卡片 */
    private val anchor = IntRect(100, 500, 300, 560)

    /** 菜单卡片：宽 200、高 120 */
    private val cardSize = IntSize(200, 120)

    /** 可用区（已扣安全区与 margin）：0..1000 × 0..800 */
    private val available = IntRect(0, 0, 1000, 800)

    /** 左右/上留白 16dp、下留白 48dp @1x（CardShadowPadding / CardShadowBottomPadding） */
    private val padSide = 16f
    private val padTop = 16f

    private fun fraction(card: IntRect, anchorCenter: Offset) =
        menuAnchorFraction(card, anchorCenter)!!

    @Test
    fun anchorBelowCardProjectsToTopEdge() {
        val card = IntRect(100, 600, 300, 720)
        val origin = fraction(card, anchorCenter = Offset(200f, 560f))
        assertEquals(0f, origin.pivotFractionY, 0f)
        assertEquals((200f - 100f) / 200f, origin.pivotFractionX, 1e-6f)
    }

    @Test
    fun anchorAboveCardProjectsToBottomEdge() {
        val card = IntRect(100, 300, 300, 420)
        val origin = fraction(card, anchorCenter = Offset(200f, 560f))
        assertEquals(1f, origin.pivotFractionY, 0f)
    }

    @Test
    fun anchorInsideCardProjectsProportionallyAndClampsHorizontally() {
        val card = IntRect(100, 400, 300, 520)
        // 锚点在卡片左侧外：横向钳到 0
        val left = fraction(card, anchorCenter = Offset(0f, 460f))
        assertEquals(0f, left.pivotFractionX, 0f)
        // 锚点在卡片右侧外：横向钳到 1
        val right = fraction(card, anchorCenter = Offset(500f, 460f))
        assertEquals(1f, right.pivotFractionX, 0f)
        // 纵向落在卡内：按比例投影（460 在 400..520 正中）
        assertEquals(0.5f, right.pivotFractionY, 1e-6f)
    }

    @Test
    fun anchorPointOfScalesInsideTheAnchorRect() {
        val rect = IntRect(100, 500, 300, 560)
        assertEquals(Offset(100f, 500f), anchorPointOf(rect, Offset(0f, 0f)))
        assertEquals(Offset(200f, 530f), anchorPointOf(rect, Offset(0.5f, 0.5f)))
        assertEquals(Offset(300f, 560f), anchorPointOf(rect, Offset(1f, 1f)))
    }

    /**
     * 宽锚点 + 窄菜单（新建课程待办的截止菜单就是这个形状）：整行卡 366×60、菜单 125×96
     * 右对齐顶对齐。参考点用矩形中心（默认值）时中心落在菜单左缘的左侧，被钳到菜单左缘
     * ——读感是「从卡左侧往右撑开」；改用行卡右缘的垂直中心后回到右上角，即行尾箭头处。
     */
    @Test
    fun wideAnchorWithNarrowCardProjectsToTrailingEdgeWithEdgeReferencePoint() {
        val rowCard = IntRect(0, 500, 366, 560)
        val menuCard = IntRect(366 - 125, 500, 366, 596)

        // 默认参考点 = 几何中心 (183, 530)：横向在菜单左缘 241 之外 → 钳到 0（左缘）
        val centered = fraction(menuCard, anchorCenter = anchorPointOf(rowCard, MenuAnchorCenter))
        assertEquals(0f, centered.pivotFractionX, 0f)
        assertEquals(0.3125f, centered.pivotFractionY, 1e-6f)

        // 参考点取行右缘垂直中心 (366, 530)：横向落在菜单右缘 → 1（右上角）
        val trailing = fraction(menuCard, anchorCenter = anchorPointOf(rowCard, Offset(1f, 0.5f)))
        assertEquals(1f, trailing.pivotFractionX, 0f)
        assertEquals(0.3125f, trailing.pivotFractionY, 1e-6f)
    }

    /**
     * AI 对话输入栏的附件菜单（宽输入卡 366×60、菜单 125×120 向上展开并右对齐 + 号
     * 右侧 16dp）：菜单整体落在卡上方，纵向锚点本来就是底边（1f）；横向若用默认矩形
     * 中心，中心落在菜单左缘外被钳到 0f，读感变成「从卡左侧撑开」。取卡右缘垂直中心
     * 后回到右下角，即 + 号一侧。
     */
    @Test
    fun aiAttachmentMenuGrowsFromTrailingEdgeAboveWideInputCard() {
        val inputCard = IntRect(100, 500, 466, 560)
        // 菜单右缘 = 卡右缘 - 16dp（RowCardPaddingHorizontal），底边在卡上方 8dp
        val menuCard = IntRect(325, 372, 450, 492)

        val centered = fraction(menuCard, anchorCenter = anchorPointOf(inputCard, MenuAnchorCenter))
        assertEquals(0f, centered.pivotFractionX, 0f)
        assertEquals(1f, centered.pivotFractionY, 0f)

        val trailing = fraction(menuCard, anchorCenter = anchorPointOf(inputCard, Offset(1f, 0.5f)))
        assertEquals(1f, trailing.pivotFractionX, 0f)
        assertEquals(1f, trailing.pivotFractionY, 0f)
    }

    @Test
    fun unmeasuredCardYieldsNoFraction() {
        assertNull(menuAnchorFraction(IntRect.Zero, Offset(10f, 10f)))
        assertNull(menuAnchorFraction(IntRect(0, 0, 0, 100), Offset(10f, 10f)))
        assertNull(menuAnchorFraction(IntRect(0, 0, 100, 0), Offset(10f, 10f)))
    }

    @Test
    fun originConvertsCardFractionToLayerCoordinates() {
        val card = IntRect(100, 600, 300, 720)
        val layer = Size(
            width = card.width + padSide * 2,
            height = card.height + padTop + 48f,
        )
        val origin = menuAnchorOrigin(
            layerSize = layer,
            cardRect = card,
            anchorCenter = Offset(200f, 560f),
            padStart = padSide,
            padTop = padTop,
        )!!
        // 卡顶边在图层中的位置 = 上留白；横向 = 左留白 + 卡片中心
        assertEquals((padSide + 100f) / layer.width, origin.pivotFractionX, 1e-6f)
        assertEquals(padTop / layer.height, origin.pivotFractionY, 1e-6f)
        // 换算回像素必须落在卡片边缘上（不是留白外缘）
        assertEquals(padSide + 100f, origin.pivotFractionX * layer.width, 0.001f)
        assertEquals(padTop, origin.pivotFractionY * layer.height, 0.001f)
    }

    @Test
    fun unmeasuredLayerYieldsNoOrigin() {
        assertNull(
            menuAnchorOrigin(
                layerSize = Size.Zero,
                cardRect = IntRect(0, 0, 100, 100),
                anchorCenter = Offset.Zero,
                padStart = 16f,
                padTop = 16f,
            ),
        )
    }

    @Test
    fun bottomStartPlacesMenuBelowAndLeftAligned() {
        val placed = placeMenu(
            placement = MenuPlacement(gravity = MenuGravity.BottomStart, gap = 8.dp),
            anchor = anchor,
            cardSize = cardSize,
            available = available,
            density = density,
        )
        assertEquals(100, placed.left)
        assertEquals(anchor.bottom + 8, placed.top)
    }

    @Test
    fun bottomEndPlacesMenuBelowAndRightAligned() {
        val placed = placeMenu(
            placement = MenuPlacement(gravity = MenuGravity.BottomEnd, gap = 8.dp),
            anchor = anchor,
            cardSize = cardSize,
            available = available,
            density = density,
        )
        assertEquals(anchor.right - cardSize.width, placed.left)
        assertEquals(anchor.bottom + 8, placed.top)
    }

    @Test
    fun overlapReducesTheGap() {
        val placed = placeMenu(
            placement = MenuPlacement(gravity = MenuGravity.BottomStart, gap = 0.dp, overlap = 12.dp),
            anchor = anchor,
            cardSize = cardSize,
            available = available,
            density = density,
        )
        assertEquals(anchor.bottom - 12, placed.top)
    }

    @Test
    fun flipsAboveWhenBelowDoesNotFit() {
        val lowAnchor = IntRect(100, 740, 300, 790)
        val placed = placeMenu(
            placement = MenuPlacement(gravity = MenuGravity.BottomStart, gap = 8.dp),
            anchor = lowAnchor,
            cardSize = cardSize,
            available = available,
            density = density,
        )
        // 下方只剩 2px：翻到锚点上方
        assertEquals(lowAnchor.top - 8 - cardSize.height, placed.top)
        assertEquals(100, placed.left)
    }

    @Test
    fun topPlacementFlipsBelowWhenAboveDoesNotFit() {
        val highAnchor = IntRect(100, 10, 300, 60)
        val placed = placeMenu(
            placement = MenuPlacement(gravity = MenuGravity.TopStart, gap = 8.dp),
            anchor = highAnchor,
            cardSize = cardSize,
            available = available,
            density = density,
        )
        assertEquals(highAnchor.bottom + 8, placed.top)
    }

    @Test
    fun flipsToEndWhenStartOverflowsRight() {
        val rightAnchor = IntRect(850, 400, 950, 460)
        val placed = placeMenu(
            placement = MenuPlacement(gravity = MenuGravity.BottomStart, inset = 8.dp),
            anchor = rightAnchor,
            cardSize = cardSize,
            available = available,
            density = density,
        )
        // Start 端（950 + 8 + 200 > 1000）放不下 → 翻到 End（右缘对齐）
        assertEquals(rightAnchor.right - 8 - cardSize.width, placed.left)
    }

    @Test
    fun keepsPreferredSideWhenNeitherFits() {
        // 锚点贴右下角、可用区比菜单还小：两侧都放不下时保留首选方位，交钳位处理
        val tiny = IntRect(0, 0, 150, 100)
        val placed = placeMenu(
            placement = MenuPlacement(gravity = MenuGravity.BottomStart, gap = 8.dp),
            anchor = IntRect(100, 90, 140, 110),
            cardSize = cardSize,
            available = tiny,
            density = density,
        )
        assertEquals(100, placed.left)
        assertEquals(110 + 8, placed.top)
    }

    @Test
    fun clampCardKeepsCardInsideAvailableArea() {
        val clamped = clampCard(
            rect = IntRect(-20, 700, 180, 820),
            cardSize = cardSize,
            available = available,
        )
        assertEquals(0, clamped.left)
        assertEquals(available.bottom - cardSize.height, clamped.top)
        assertEquals(0, clamped.right - clamped.left - cardSize.width)
        assertEquals(0, clamped.bottom - clamped.top - cardSize.height)
    }

    @Test
    fun clampCardPinsToTopLeftWhenMenuIsLargerThanAvailable() {
        val clamped = clampCard(
            rect = IntRect(50, 50, 250, 170),
            cardSize = cardSize,
            available = IntRect(10, 10, 110, 70),
        )
        assertEquals(10, clamped.left)
        assertEquals(10, clamped.top)
    }
}
