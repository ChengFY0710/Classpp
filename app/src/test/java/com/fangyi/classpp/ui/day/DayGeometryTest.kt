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
        // 取一个与 DayCardShape 24dp 不同的机身圆角，验证 01→02 的连续插值
        deviceCornerRadius = 28f,
    )

    @Test
    fun calendarStateKeepsFullScreenImageAndBottomScrim() {
        val frame = DayGeometry.frame(metrics, open = 0f, collapse = 0f)

        assertEquals(0f, frame.image.left, 0.01f)
        assertEquals(0f, frame.image.top, 0.01f)
        assertEquals(402f, frame.image.right, 0.01f)
        assertEquals(893f, frame.image.bottom, 0.01f)
        // 01 先裁机身圆角（不是 0，也不是 DayCardShape）
        assertEquals(28f, frame.imageRadius, 0.01f)

        // 01 白卡完全在屏外（比图片底边低一个 DayCardGap），模糊带仍是原来的 360dp 底部 scrim
        assertEquals(893f + DayCardGap.value, frame.content.top, 0.01f)
        assertEquals(533f, frame.bandTop, 0.01f)
        assertEquals(893f, frame.bandBottom, 0.01f)
        assertEquals(360f, frame.bandHeight, 0.01f)

        assertEquals(0f, frame.dateTranslationY, 0.01f)
        assertEquals(1f, frame.topScrimAlpha, 0.01f)
        assertEquals(0f, frame.topBarAlpha, 0.01f)
        assertEquals(0.30f, frame.bandTintAlpha, 0.01f)
    }

    @Test
    fun openTransitionKeepsImageToContentGapConstant() {
        val opens = listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.99f, 1f)
        opens.forEach { open ->
            val frame = DayGeometry.frame(metrics, open = open, collapse = 0f)
            assertEquals(
                DayCardGap.value,
                frame.content.top - frame.image.bottom,
                0.01f,
            )
        }
    }

    @Test
    fun imageCornerRadiusMorphsFromDeviceCornerToDayCardShape() {
        val calendar = DayGeometry.frame(metrics, open = 0f, collapse = 0f)
        val halfway = DayGeometry.frame(metrics, open = 0.5f, collapse = 0f)
        val compact = DayGeometry.frame(metrics, open = 1f, collapse = 0f)
        val schedule = DayGeometry.frame(metrics, open = 1f, collapse = 1f)

        assertEquals(28f, calendar.imageRadius, 0.01f)
        assertEquals(26f, halfway.imageRadius, 0.01f)
        assertEquals(24f, compact.imageRadius, 0.01f)
        // 02→04 只收窄/下移，不参与圆角插值
        assertEquals(24f, schedule.imageRadius, 0.01f)
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

        // 白卡顶边 = 图片卡底边 + DayCardGap（当前 token 24dp → 324dp）
        val contentTop = 300f + DayCardGap.value
        assertEquals(12f, frame.content.left, 0.01f)
        assertEquals(contentTop, frame.content.top, 0.01f)
        assertEquals(390f, frame.content.right, 0.01f)
        assertEquals(contentTop + 235f, frame.content.bottom, 0.01f)
        assertEquals(235f, frame.content.height, 0.01f)

        // 模糊带：卡片中点起、白卡顶边止；02 起不再有黑色 tint
        assertEquals(180f, frame.bandTop, 0.01f)
        assertEquals(contentTop, frame.bandBottom, 0.01f)
        assertEquals(contentTop - 180f, frame.bandHeight, 0.01f)
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
        // 03 的构图仍以 contentTop=204dp 为锚点；白卡起点随 DayCardGap 变化，
        // 反解出的 collapse 也随之移动（gap=12 时约 0.551，gap=24 时约 0.577）。
        val contentTopCompact = 300f + DayCardGap.value
        val collapse = (contentTopCompact - 204f) / (contentTopCompact - 116f)
        val frame = DayGeometry.frame(metrics, open = 1f, collapse = collapse)

        assertEquals(204f, frame.content.top, 0.5f)
        // 高度 ramp 从 collapse=0.55 开始；该锚点落在 ramp 前段，仍应接近 235dp
        assertEquals(235f, frame.content.height, 1f)

        // 设计稿 03：图片卡 top≈117dp、宽≈282dp。gap 非 12dp 时收窄点略偏，
        // 图片 top 仍对齐，宽度给 8dp 容差（gap=24 时约 275dp）。
        assertEquals(117f, frame.image.top, 2f)
        assertEquals(282f, frame.image.width, 8f)

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
