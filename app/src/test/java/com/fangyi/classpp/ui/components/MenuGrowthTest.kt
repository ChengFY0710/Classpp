package com.fangyi.classpp.ui.components

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 菜单生长锚点（缩放锚点）的两段几何：
 *
 * - 角 → 图层比例 [menuGrowthOrigin]：缩放图层 = 卡片 + 四周透明投影留白，故锚点必须按
 *   卡片**边缘**反推（左右各 16dp 留白、底部 48dp），直接写 0f/1f 会锚在留白外缘上——
 *   动画中卡片会横竖各偏约 3dp（scale 0.8 时 = 16dp × 0.2）。尺寸未量出（首帧 0）时返回
 *   null，调用方沿用上一帧锚点，不能把 NaN 灌进 TransformOrigin；
 * - 半屏选角 [menuGrowthForAnchor]：锚点在窗口右半屏 → 右上角（菜单向右放不下，会被钳到
 *   屏幕右缘，锚点随即跑到卡片左侧）；窗口宽未知（≤ 0）退回默认左角。
 *
 * 断言直接引用 PopupMenuPopup.kt 里的函数与常量（同为 internal），调参时这里同步生效。
 */
class MenuGrowthTest {

    /** 左右/上留白 16dp、下留白 48dp @3x（CardShadowPadding / CardShadowBottomPadding） */
    private val padSide = 48f
    private val padTop = 48f
    private val padBottom = 144f

    /** 卡片本体尺寸：宽 360px（120dp）、高 180px（60dp） */
    private val cardWidth = 360f
    private val cardHeight = 180f

    /** 图层尺寸 = 卡片 + 留白 */
    private val layer = Size(
        width = cardWidth + padSide * 2,
        height = cardHeight + padTop + padBottom,
    )

    private fun origin(growth: MenuGrowth) = menuGrowthOrigin(
        growth = growth,
        layerSize = layer,
        padStart = padSide,
        padEnd = padSide,
        padTop = padTop,
        padBottom = padBottom,
    )!!

    /** 四角各自锚在卡片对应的尖角上：横向落在卡片左/右缘，纵向落在卡片顶/底缘 */
    @Test
    fun cornersAnchorOnTheCardEdgesNotTheShadowPadding() {
        val left = origin(MenuGrowth.TopLeft)
        val right = origin(MenuGrowth.TopRight)
        val bottomLeft = origin(MenuGrowth.BottomLeft)
        val bottomRight = origin(MenuGrowth.BottomRight)

        // 左缘 = 左侧留白；右缘 = 图层宽 − 右侧留白（都不是 0 / 1）
        assertEquals(padSide / layer.width, left.pivotFractionX, 1e-6f)
        assertEquals((layer.width - padSide) / layer.width, right.pivotFractionX, 1e-6f)
        assertEquals(padTop / layer.height, left.pivotFractionY, 1e-6f)
        assertEquals((layer.height - padBottom) / layer.height, bottomLeft.pivotFractionY, 1e-6f)

        // 锚点即卡片边缘：换算回像素必须正好落在卡片角上
        assertEquals(padSide, left.pivotFractionX * layer.width, 0.001f)
        assertEquals(padSide + cardWidth, right.pivotFractionX * layer.width, 0.001f)
        assertEquals(padTop, left.pivotFractionY * layer.height, 0.001f)
        assertEquals(padTop + cardHeight, bottomLeft.pivotFractionY * layer.height, 0.001f)

        // 同侧两角共边，异侧不重合（左右缘、顶底缘各自分开）
        assertEquals(left.pivotFractionX, bottomLeft.pivotFractionX, 1e-6f)
        assertEquals(left.pivotFractionY, right.pivotFractionY, 1e-6f)
        assertEquals(bottomLeft.pivotFractionY, bottomRight.pivotFractionY, 1e-6f)
        assertNotEquals(left.pivotFractionX, right.pivotFractionX)
        assertNotEquals(left.pivotFractionY, bottomLeft.pivotFractionY)
    }

    /** 没有留白时退化为图层角本身：0f/1f（留白是唯一的偏移来源） */
    @Test
    fun zeroPaddingDegeneratesToLayerCorners() {
        val topLeft = menuGrowthOrigin(MenuGrowth.TopLeft, layer, 0f, 0f, 0f, 0f)!!
        val bottomRight = menuGrowthOrigin(MenuGrowth.BottomRight, layer, 0f, 0f, 0f, 0f)!!
        assertEquals(0f, topLeft.pivotFractionX, 0f)
        assertEquals(0f, topLeft.pivotFractionY, 0f)
        assertEquals(1f, bottomRight.pivotFractionX, 0f)
        assertEquals(1f, bottomRight.pivotFractionY, 0f)
    }

    /** 尺寸未就绪（首帧）：返回 null 而不是 NaN/负数，调用方跳过赋值沿用上一帧锚点 */
    @Test
    fun unmeasuredLayerYieldsNoOrigin() {
        assertNull(menuGrowthOrigin(MenuGrowth.TopRight, Size.Zero, padSide, padSide, padTop, padBottom))
        assertNull(menuGrowthOrigin(MenuGrowth.TopRight, Size(0f, layer.height), padSide, padSide, padTop, padBottom))
        assertNull(menuGrowthOrigin(MenuGrowth.TopRight, Size(layer.width, 0f), padSide, padSide, padTop, padBottom))
    }

    /** 半屏选角：右半屏用右角，左半屏与正中用默认左角 */
    @Test
    fun anchorSidePicksTheGrowthCorner() {
        val windowWidth = 1080
        assertEquals(MenuGrowth.TopRight, menuGrowthForAnchor(900f, windowWidth))
        assertEquals(MenuGrowth.TopLeft, menuGrowthForAnchor(300f, windowWidth))
        // 正中不算右半屏（边界值保持在左角，与旧行为一致）
        assertEquals(MenuGrowth.TopLeft, menuGrowthForAnchor(540f, windowWidth))
        // 刚过正中即翻角
        assertEquals(MenuGrowth.TopRight, menuGrowthForAnchor(541f, windowWidth))
    }

    /** 窗口宽未知（containerSize 为 0 且宿主 View 未量出）：一律退回默认左角，不做半屏判断 */
    @Test
    fun unknownWindowWidthFallsBackToTheLeftCorner() {
        assertEquals(MenuGrowth.TopLeft, menuGrowthForAnchor(900f, 0))
        assertEquals(MenuGrowth.TopLeft, menuGrowthForAnchor(900f, -1))
    }
}
