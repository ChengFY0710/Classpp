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
        // 01 静止态满幅直角：不裁圆角
        assertEquals(0f, frame.imageRadius, 0.01f)

        // 01 白卡完全在屏外（比图片底边低一个 DayCardGap），模糊带仍是原来的 320dp 底部 scrim
        assertEquals(893f + DayCardGap.value, frame.content.top, 0.01f)
        assertEquals(573f, frame.bandTop, 0.01f)
        assertEquals(893f, frame.bandBottom, 0.01f)
        assertEquals(320f, frame.bandHeight, 0.01f)

        assertEquals(0f, frame.dateTranslationY, 0.01f)
        assertEquals(1f, frame.topScrimAlpha, 0.01f)
        assertEquals(0f, frame.topBarContentAlpha, 0.01f)
        assertEquals(0f, frame.topBarBackgroundAlpha, 0.01f)
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
        // 滑动刚开始的第一帧（open 从 0 跨到极小正值）立即切到机身圆角
        val swipeStart = DayGeometry.frame(metrics, open = 0.001f, collapse = 0f)
        val halfway = DayGeometry.frame(metrics, open = 0.5f, collapse = 0f)
        val compact = DayGeometry.frame(metrics, open = 1f, collapse = 0f)
        val schedule = DayGeometry.frame(metrics, open = 1f, collapse = 1f)

        assertEquals(0f, calendar.imageRadius, 0.01f)
        assertEquals(28f, swipeStart.imageRadius, 0.03f)
        assertEquals(26f, halfway.imageRadius, 0.01f)
        assertEquals(24f, compact.imageRadius, 0.01f)
        // 02→04 只收窄/下移，不参与圆角插值
        assertEquals(24f, schedule.imageRadius, 0.01f)
    }

    @Test
    fun calendarImageCornerFadesFromDeviceCornerToSharp() {
        // 02→01 展开落定后：状态机把系数从 1 渐变到 0，圆角随之收成直角。
        val rounded = DayGeometry.frame(
            metrics,
            open = 0f,
            collapse = 0f,
            deviceCornerAlpha = 1f,
        )
        val halfway = DayGeometry.frame(
            metrics,
            open = 0f,
            collapse = 0f,
            deviceCornerAlpha = 0.5f,
        )
        val sharp = DayGeometry.frame(
            metrics,
            open = 0f,
            collapse = 0f,
            deviceCornerAlpha = 0f,
        )
        // open > 0 时忽略收角系数：一滑动仍是机身圆角，随后插值到 24dp。
        val swiping = DayGeometry.frame(
            metrics,
            open = 0.5f,
            collapse = 0f,
            deviceCornerAlpha = 0f,
        )

        assertEquals(28f, rounded.imageRadius, 0.01f)
        assertEquals(14f, halfway.imageRadius, 0.01f)
        assertEquals(0f, sharp.imageRadius, 0.01f)
        assertEquals(26f, swiping.imageRadius, 0.01f)
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

        // 日期组 01→02 的位移与设计稿量取一致（约 -485dp）；02 尚未缩放/横移
        assertEquals(0f, frame.dateTranslationX, 0.01f)
        assertEquals(-485f, frame.dateTranslationY, 0.5f)
        assertEquals(1f, frame.dateScale, 0.01f)
        assertEquals(0f, frame.topScrimAlpha, 0.01f)
        assertEquals(0f, frame.topBarContentAlpha, 0.01f)
        assertEquals(0f, frame.topBarBackgroundAlpha, 0.01f)
    }

    @Test
    fun scheduleStateCoversCalendarAndBandCompletely() {
        val frame = DayGeometry.frame(metrics, open = 1f, collapse = 1f)

        // 图片卡最终宽 200dp、居中、顶边状态栏下 112dp；高度按等比缩到 240×200/378
        val finalHeight = 240f * 200f / 378f
        assertEquals(101f, frame.image.left, 0.01f)
        assertEquals(160f, frame.image.top, 0.01f)
        assertEquals(301f, frame.image.right, 0.01f)
        assertEquals(160f + finalHeight, frame.image.bottom, 0.01f)
        assertEquals(finalHeight, frame.image.height, 0.01f)

        // 04 白卡 top≈117dp；高度固定 235dp（不随收起拉高），图片卡与模糊带仍在卡内
        assertEquals(116f, frame.content.top, 0.01f)
        assertEquals(351f, frame.content.bottom, 0.01f)
        assertEquals(235f, frame.content.height, 0.01f)
        assertTrue(frame.image.top >= frame.content.top)
        assertTrue(frame.image.bottom <= frame.content.bottom)
        assertEquals(0f, frame.bandHeight, 0.01f)

        assertTrue(frame.topBarContentAlpha >= 1f)
        assertTrue(frame.topBarBackgroundAlpha >= 1f)
        assertEquals(0f, frame.topBarTranslationY, 0.01f)

        // 日期组缩到最终卡宽比例、按卡内左边距右移；底边跟着缩小的图片卡底边
        val groupScale = 200f / 378f
        val expectedGroupX = (101f + (33f - 12f) * groupScale) - 33f
        assertEquals(groupScale, frame.dateScale, 0.001f)
        assertEquals(expectedGroupX, frame.dateTranslationX, 0.01f)
        assertEquals(
            frame.image.bottom - 26f * groupScale,
            759f + frame.dateTranslationY,
            0.5f,
        )
    }

    @Test
    fun dateGroupShrinksWithImageDuringCollapse() {
        val compact = DayGeometry.frame(metrics, open = 1f, collapse = 0f)
        val halfway = DayGeometry.frame(metrics, open = 1f, collapse = 0.5f)
        val schedule = DayGeometry.frame(metrics, open = 1f, collapse = 1f)

        assertEquals(1f, compact.dateScale, 0.01f)
        assertTrue(halfway.dateScale < 1f)
        assertTrue(halfway.dateScale > schedule.dateScale)
        assertEquals(200f / 378f, schedule.dateScale, 0.001f)

        // 收窄后整组向右归位，始终贴住卡片左侧
        assertTrue(halfway.dateTranslationX > 0f)
        assertTrue(schedule.dateTranslationX > halfway.dateTranslationX)

        // 图片高度等比收缩，日期组底边随缩小的卡底走（不再固定下移 100dp），
        // 但仍完整落在图片卡内。
        assertTrue(schedule.image.height < compact.image.height)
        assertEquals(
            compact.image.height * schedule.image.width / compact.image.width,
            schedule.image.height,
            0.01f,
        )
        val scheduleDateBottom = 759f + schedule.dateTranslationY
        assertTrue(scheduleDateBottom <= schedule.image.bottom)
        assertTrue(scheduleDateBottom >= schedule.image.top)
        assertTrue(kotlin.math.abs(schedule.dateTranslationY - compact.dateTranslationY) < 2f)
    }

    @Test
    fun transitionPassesThroughDesign03Composition() {
        // 03 的构图仍以 contentTop=204dp 为锚点；白卡起点随 DayCardGap 变化，
        // 反解出的 collapse 也随之移动（gap=12 时约 0.551，gap=24 时约 0.577）。
        val contentTopCompact = 300f + DayCardGap.value
        val collapse = (contentTopCompact - 204f) / (contentTopCompact - 116f)
        val frame = DayGeometry.frame(metrics, open = 1f, collapse = collapse)

        assertEquals(204f, frame.content.top, 0.5f)
        // 白卡高度固定，03 也是 235dp
        assertEquals(235f, frame.content.height, 0.01f)

        // 设计稿 03：图片卡 top≈117dp、宽≈282dp。gap 非 12dp 时收窄点略偏，
        // 图片 top 仍对齐，宽度给 8dp 容差（gap=24 时约 275dp）。
        assertEquals(117f, frame.image.top, 2f)
        assertEquals(282f, frame.image.width, 8f)

        // 白卡顶边已越过卡片中点 → 模糊带归零，03 没有残留模糊带
        assertEquals(0f, frame.bandHeight, 0.01f)
        // 顶栏内容已基本就位；背景按新节奏接近 04 才出现，03 处还没有。
        assertTrue(frame.topBarContentAlpha > 0.95f)
        assertEquals(0f, frame.topBarBackgroundAlpha, 0.01f)
    }

    @Test
    fun topBarContentLeadsBackground() {
        val early = DayGeometry.frame(metrics, open = 1f, collapse = 0.4f)
        val backgroundGone = DayGeometry.frame(metrics, open = 1f, collapse = 0.65f)
        val late = DayGeometry.frame(metrics, open = 1f, collapse = 0.85f)
        val schedule = DayGeometry.frame(metrics, open = 1f, collapse = 1f)

        // 02→04：内容先淡入并下移，背景此时仍全透明。
        assertTrue(early.topBarContentAlpha > 0.5f)
        assertTrue(early.topBarTranslationY < 0f)
        assertEquals(0f, early.topBarBackgroundAlpha, 0.01f)

        // 04→02 反向：背景先消失，内容此时仍完全可见、尚未上移。
        assertEquals(1f, backgroundGone.topBarContentAlpha, 0.01f)
        assertEquals(0f, backgroundGone.topBarBackgroundAlpha, 0.01f)
        assertEquals(0f, backgroundGone.topBarTranslationY, 0.01f)

        // 接近 04：内容已就位，背景正在渐入。
        assertEquals(1f, late.topBarContentAlpha, 0.01f)
        assertTrue(late.topBarBackgroundAlpha > 0f)
        assertTrue(late.topBarBackgroundAlpha < 1f)
        assertEquals(1f, schedule.topBarBackgroundAlpha, 0.01f)
    }

    @Test
    fun calendarElementsFadeOutTowardSchedule() {
        val calendar = DayGeometry.frame(metrics, open = 0f, collapse = 0f)
        val compact = DayGeometry.frame(metrics, open = 1f, collapse = 0f)
        val early = DayGeometry.frame(metrics, open = 1f, collapse = 0.2f)
        val imageFadeStart = DayGeometry.frame(metrics, open = 1f, collapse = 0.5f)
        val imageHalf = DayGeometry.frame(metrics, open = 1f, collapse = 0.75f)
        val schedule = DayGeometry.frame(metrics, open = 1f, collapse = 1f)

        // 01/02 都完整可见；日期组从 02 出发就开始淡出（比图片卡更早被白卡覆盖）。
        assertEquals(1f, calendar.imageAlpha, 0.01f)
        assertEquals(1f, calendar.dateAlpha, 0.01f)
        assertEquals(1f, compact.imageAlpha, 0.01f)
        assertEquals(1f, compact.dateAlpha, 0.01f)
        assertEquals(1f, early.imageAlpha, 0.01f)
        assertEquals(0.5f, early.dateAlpha, 0.01f)

        // 图片卡在 collapse 0.5 才开始淡出，0.75 时半透明；04 两者都归零。
        assertEquals(1f, imageFadeStart.imageAlpha, 0.01f)
        assertEquals(0f, imageFadeStart.dateAlpha, 0.01f)
        assertEquals(0.5f, imageHalf.imageAlpha, 0.01f)
        assertEquals(0f, schedule.imageAlpha, 0.01f)
        assertEquals(0f, schedule.dateAlpha, 0.01f)
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
        assertEquals(
            (schedule.image.height - 893f) / 2f,
            schedule.imageTranslationY(metrics),
            0.01f,
        )
    }

    @Test
    fun contentCardHeightStaysFixedAcrossStates() {
        val compact = DayGeometry.frame(metrics, open = 1f, collapse = 0f)
        val mid = DayGeometry.frame(metrics, open = 1f, collapse = 0.5f)
        val settled = DayGeometry.frame(metrics, open = 1f, collapse = 1f)

        assertEquals(235f, compact.content.height, 0.01f)
        assertEquals(235f, mid.content.height, 0.01f)
        assertEquals(235f, settled.content.height, 0.01f)
    }
}
