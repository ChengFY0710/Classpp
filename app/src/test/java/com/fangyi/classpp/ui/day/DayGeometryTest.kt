package com.fangyi.classpp.ui.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 台历转场几何：用设计稿基准（402×893dp、statusBar 48dp、navBar 34dp）锁定三个真实状态
 * 与设计稿 03 的中间构图。公式改动（间距/高度/日期位移）会在这里立刻暴露。
 */
class DayGeometryTest {

    private val metrics = DayMetrics(
        screenWidth = 402f,
        screenHeight = 893f,
        statusBar = 48f,
        navBar = 34f,
    )

    @Test
    fun calendarStateKeepsFullScreenImageAndBottomScrim() {
        val frame = DayGeometry.frame(metrics, open = 0f, collapse = 0f)

        assertEquals(0f, frame.image.left, 0.01f)
        assertEquals(0f, frame.image.top, 0.01f)
        assertEquals(402f, frame.image.right, 0.01f)
        assertEquals(893f, frame.image.bottom, 0.01f)
        assertEquals(0f, frame.imageRadius, 0.01f)

        // 01 白卡完全在屏外，模糊带就是原来的 360dp 底部 scrim
        assertEquals(893f, frame.content.top, 0.01f)
        assertEquals(533f, frame.bandTop, 0.01f)
        assertEquals(893f, frame.bandBottom, 0.01f)
        assertEquals(360f, frame.bandHeight, 0.01f)

        assertEquals(0f, frame.dateTranslationY, 0.01f)
        assertEquals(1f, frame.topScrimAlpha, 0.01f)
        assertEquals(0f, frame.topBarAlpha, 0.01f)
        assertEquals(0.30f, frame.bandTintAlpha, 0.01f)
    }

    @Test
    fun compactStateMatchesDesignCardAndWhiteCard() {
        val frame = DayGeometry.frame(metrics, open = 1f, collapse = 0f)

        // 图片卡：两侧 12dp、顶边状态栏下 12dp、240dp 高、24dp 圆角
        assertEquals(12f, frame.image.left, 0.01f)
        assertEquals(60f, frame.image.top, 0.01f)
        assertEquals(390f, frame.image.right, 0.01f)
        assertEquals(300f, frame.image.bottom, 0.01f)
        assertEquals(24f, frame.imageRadius, 0.01f)

        // 白卡：设计稿 top≈313dp / bottom≈548dp
        assertEquals(12f, frame.content.left, 0.01f)
        assertEquals(312f, frame.content.top, 0.01f)
        assertEquals(390f, frame.content.right, 0.01f)
        assertEquals(547f, frame.content.bottom, 0.01f)
        assertEquals(235f, frame.content.height, 0.01f)

        // 模糊带：卡片中点起、白卡顶边止；02 起不再有黑色 tint
        assertEquals(180f, frame.bandTop, 0.01f)
        assertEquals(312f, frame.bandBottom, 0.01f)
        assertEquals(132f, frame.bandHeight, 0.01f)
        assertEquals(0f, frame.bandTintAlpha, 0.01f)

        // 日期组 01→02 的位移与设计稿量取一致（约 -485dp）
        assertEquals(-485f, frame.dateTranslationY, 0.5f)
        assertEquals(0f, frame.topScrimAlpha, 0.01f)
        assertEquals(0f, frame.topBarAlpha, 0.01f)
    }

    @Test
    fun scheduleStateCoversCalendarAndBandCompletely() {
        val frame = DayGeometry.frame(metrics, open = 1f, collapse = 1f)

        // 图片卡最终宽 200dp、居中、顶边状态栏下 112dp
        assertEquals(101f, frame.image.left, 0.01f)
        assertEquals(160f, frame.image.top, 0.01f)
        assertEquals(301f, frame.image.right, 0.01f)
        assertEquals(400f, frame.image.bottom, 0.01f)

        // 04 白卡 top≈117dp、height 285dp、bottom≈402dp；图片卡与模糊带完全落在卡内
        assertEquals(116f, frame.content.top, 0.01f)
        assertEquals(401f, frame.content.bottom, 0.01f)
        assertEquals(285f, frame.content.height, 0.01f)
        assertTrue(frame.image.top >= frame.content.top)
        assertTrue(frame.image.bottom <= frame.content.bottom)
        assertEquals(0f, frame.bandHeight, 0.01f)

        assertTrue(frame.topBarAlpha >= 1f)
        assertEquals(0f, frame.topBarTranslationY, 0.01f)
        // 日期组随图片卡再下移 100dp，被白卡完全盖住
        assertEquals(-385f, frame.dateTranslationY, 0.5f)
    }

    @Test
    fun transitionPassesThroughDesign03Composition() {
        // 由 contentTop = 312 - 196p 反解设计稿 03 的 204dp
        val collapse = (312f - 204f) / (312f - 116f)
        val frame = DayGeometry.frame(metrics, open = 1f, collapse = collapse)

        assertEquals(204f, frame.content.top, 0.5f)
        assertEquals(235f, frame.content.height, 0.5f)

        // 设计稿 03：图片卡 top≈117dp、宽≈282dp；此处落在 115dp/280dp（±2dp）
        assertEquals(117f, frame.image.top, 2f)
        assertEquals(282f, frame.image.width, 3f)

        // 白卡顶边已越过卡片中点 → 模糊带归零，03 没有残留模糊带
        assertEquals(0f, frame.bandHeight, 0.01f)
        assertEquals(1f, frame.topBarAlpha, 0.01f)
    }

    @Test
    fun imageTransformKeepsCenterCropWithoutLetterbox() {
        val compact = DayGeometry.frame(metrics, open = 1f, collapse = 0f)
        assertEquals(378f / 402f, compact.imageScale(metrics), 0.0001f)
        assertEquals(-12f, compact.imageTranslationX(metrics), 0.01f)
        assertEquals((240f - 893f) / 2f, compact.imageTranslationY(metrics), 0.01f)

        val schedule = DayGeometry.frame(metrics, open = 1f, collapse = 1f)
        assertEquals(200f / 402f, schedule.imageScale(metrics), 0.0001f)
        assertEquals(-101f, schedule.imageTranslationX(metrics), 0.01f)
        assertEquals((240f - 893f) / 2f, schedule.imageTranslationY(metrics), 0.01f)
    }

    @Test
    fun contentHeightOnlyGrowsLateSoDesign03StaysCompact() {
        val beforeRamp = DayGeometry.frame(metrics, open = 1f, collapse = 0.54f)
        val afterRamp = DayGeometry.frame(metrics, open = 1f, collapse = 0.90f)
        val settled = DayGeometry.frame(metrics, open = 1f, collapse = 1f)

        assertEquals(235f, beforeRamp.content.height, 0.01f)
        assertTrue(afterRamp.content.height > 235f)
        assertTrue(afterRamp.content.height < 285f)
        assertEquals(285f, settled.content.height, 0.01f)
    }
}
