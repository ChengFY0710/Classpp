package com.fangyi.classpp.ui.day

import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.navigation.NavReserve
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing

// ---------------------------------------------------------------
// 日程页台历转场几何：全部 dp token 集中在此，UI 层只读 DayGeometry.frame(...)
// 的结果。数值由 .mimocode/docs 的 5 张设计稿按 402×893dp / statusBar=48dp /
// navBar=34dp 量取；真机用实际 WindowInsets 代入，比例关系不变。
// ---------------------------------------------------------------

/** 02 图片卡顶边距状态栏下沿。 */
internal val DayCardTopInset = 12.dp

/** 图片卡高度：01→02→04 全程不变。 */
internal val DayCardHeight = 240.dp

/** 图片卡与白底内容卡的间距。 */
internal val DayCardGap = 24.dp

/** 图片卡/白卡圆角（DayCardShape 同值）。 */
internal val DayCardCorner = 24.dp

/** 02 白卡高度（设计稿 02/03 相同）。 */
internal val DayContentCompactHeight = 235.dp

/** 04 白卡高度：恰好完整覆盖 04 的图片卡与模糊带。 */
internal val DayContentScheduleHeight = 285.dp

/** 04 图片卡宽（收窄终点）。 */
internal val DayScheduleImageWidth = 200.dp

/** 04 图片卡顶边相对状态栏下沿的偏移。 */
internal val DayScheduleImageTopInset = 112.dp

/** 顶栏标题行高。 */
internal val DayTopBarRowHeight = 56.dp

/** 顶栏行与白卡之间的间距，也是 04 顶栏 Haze 层下缘到白卡的距离。 */
internal val DayTopBarGap = 12.dp

/** 顶栏标题左侧页边距（与 ScheduleHeader 的 HeaderEdgePadding 一致）。 */
internal val DayTopBarEdgePadding = 33.dp

/** 01/02 日期组左侧页边距（沿用原 DayScreen）。 */
internal val DayDateEdgePadding = 33.dp

/** 01 顶部提示距状态栏的额外留白（沿用原 DayScreen）。 */
internal val DayHintTopPadding = 8.dp

/** 01 顶部渐变磨砂高度（沿用原 DayScreen）。 */
internal val DayTopScrimHeight = 135.dp

/** 01 底部渐变磨砂高度（沿用原 DayScreen）。 */
internal val DayBottomScrimHeight = 360.dp

/** 02 日期组底边到图片卡底边的内距。 */
internal val DayDateInCardBottomPadding = 26.dp

/** 01 日期组底边到导航栏胶囊上方的额外留白（沿用原 DayScreen）。 */
internal val DayDateBottomGap = 36.dp

/** 一段转场 0→1 对应的一比一手势行程。 */
internal val DayDragDistance = 360.dp

/** 快速甩动直判阈值（与 OverlaySheet 的拖拽关闭速度同规格）。 */
internal val DayFlingVelocity = 800.dp

/** 顶栏进场时从上方下移的距离。 */
internal val DayTopBarEntryOffset = 24.dp

/** 01 顶部磨砂退出时上移的距离。 */
internal val DayTopScrimExitOffset = 40.dp

/** 01 提示文案退出时上移的距离。 */
internal val DayHintExitOffset = 24.dp

/** 顶栏完全可见的 collapse 进度（设计稿 03 处已全亮）。 */
internal const val DayTopBarFadeEnd = 0.45f

/** 白卡从 235dp 长到 285dp 的起始 collapse 进度：03 之前保持 235dp。 */
internal const val DayContentHeightRampStart = 0.55f

/** 状态栏图标切换到「日历视觉」的 open 阈值（01 图片上 / 02 浅灰底）。 */
internal const val DayCalendarIconSwitch = 0.5f

/** 设计稿量取 01/02 的“21”等大；保留左下锚点缩放，便于后续只改这个 token。 */
internal const val DayDateNumberScale = 1f

/**
 * 几何计算所需的稳定量（dp）：屏幕尺寸与系统栏 inset。
 * 其余常量从文件顶部 token 读取，测试只需传入前四项即可复现设计稿基准。
 */
internal data class DayMetrics(
    val screenWidth: Float,
    val screenHeight: Float,
    val statusBar: Float,
    val navBar: Float,
    val pageHorizontal: Float = PageHorizontalSpacing.value,
    val navReserve: Float = NavReserve.value,
    val dateBottomGap: Float = DayDateBottomGap.value,
    /**
     * 机身圆角半径（dp）：01 台历全屏图的裁剪半径，也是 01→02 圆角插值的起点。
     * UI 由 rememberDeviceCornerRadius() 读系统真实 R 角；低版本/取不到时该 helper
     * 退化为 24dp。单测可传任意值验证「机身圆角 → DayCardShape」的连续过渡。
     */
    val deviceCornerRadius: Float = DayCardCorner.value,
)

/** dp 矩形；left/top/right/bottom 与 Compose 的 Rect 同语义。 */
internal data class DayRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * 同一组 open/collapse 进度派生出的全部画面几何（dp）。
 *
 * 所有元素都从这两个进度取同一个快照，保证跟手阶段严格同步；UI 在 layout/draw
 * 阶段调用 [DayGeometry.frame]，不进入组合，因此拖动每帧不触发重组。
 */
internal data class DayFrame(
    /** 图片卡矩形（shape 裁剪 + 图片居中裁切共用）。 */
    val image: DayRect,
    /** 图片卡圆角：01 为机身圆角，01→02 连续插值到 24dp；02/04 恒为 24dp。 */
    val imageRadius: Float,
    /** 白底内容卡矩形。 */
    val content: DayRect,
    /** 图片卡下段模糊带的顶边。 */
    val bandTop: Float,
    /**
     * 模糊带底边：01→02 开头白卡仍在屏外时钉在屏底（保持 01 的 360dp scrim），
     * 白卡顶边进入屏幕后即等于白卡顶边，被白卡自然截断。
     */
    val bandBottom: Float,
    /** 日期组相对 01 基准位的纵向位移。 */
    val dateTranslationY: Float,
    /** 01 顶部磨砂/提示的透明度（1→0）。 */
    val topScrimAlpha: Float,
    /** 顶栏透明度/位移进度（03 处已为 1）。 */
    val topBarAlpha: Float,
    /** 顶栏进场下移量（负值，随 alpha 归零）。 */
    val topBarTranslationY: Float,
    /** 01 底部 scrim 的黑色 tint 浓度（02 起为 0，交给纯模糊）。 */
    val bandTintAlpha: Float,
) {
    /** 模糊带可见高度；白卡顶边越过卡片中点后为 0（03/04 无残留）。 */
    val bandHeight: Float get() = (bandBottom - bandTop).coerceAtLeast(0f)
}

internal object DayGeometry {

    fun frame(metrics: DayMetrics, open: Float, collapse: Float): DayFrame {
        val o = open.coerceIn(0f, 1f)
        val c = collapse.coerceIn(0f, 1f)

        val cardHeight = DayCardHeight.value
        val compactTop = metrics.statusBar + DayCardTopInset.value
        val compactImage = DayRect(
            left = metrics.pageHorizontal,
            top = compactTop,
            right = metrics.screenWidth - metrics.pageHorizontal,
            bottom = compactTop + cardHeight,
        )
        val finalImage = DayRect(
            left = (metrics.screenWidth - DayScheduleImageWidth.value) / 2f,
            top = metrics.statusBar + DayScheduleImageTopInset.value,
            right = (metrics.screenWidth + DayScheduleImageWidth.value) / 2f,
            bottom = metrics.statusBar + DayScheduleImageTopInset.value + cardHeight,
        )
        val fullImage = DayRect(
            left = 0f,
            top = 0f,
            right = metrics.screenWidth,
            bottom = metrics.screenHeight,
        )

        val image = if (o < 1f) {
            lerp(fullImage, compactImage, o)
        } else {
            lerp(compactImage, finalImage, c)
        }

        val contentTopCompact = compactImage.bottom + DayCardGap.value
        val contentTopSchedule = metrics.statusBar + DayTopBarRowHeight.value + DayTopBarGap.value
        val contentTop = if (o < 1f) {
            // 01→02 白卡顶边始终跟随图片卡底边 + DayCardGap，间距从第一帧到 02 恒定；
            // 不再从屏幕底边独立插值（否则间距会从 0 长到 DayCardGap）。
            // 01 时白卡整体在屏外，比图片底边低一个 DayCardGap 不可见。
            image.bottom + DayCardGap.value
        } else {
            lerp(contentTopCompact, contentTopSchedule, c)
        }
        val contentHeight = if (o < 1f) {
            DayContentCompactHeight.value
        } else {
            lerp(
                DayContentCompactHeight.value,
                DayContentScheduleHeight.value,
                smoothstep(DayContentHeightRampStart, 1f, c),
            )
        }
        val content = DayRect(
            left = metrics.pageHorizontal,
            top = contentTop,
            right = metrics.screenWidth - metrics.pageHorizontal,
            bottom = contentTop + contentHeight,
        )

        val bandTopStart = metrics.screenHeight - DayBottomScrimHeight.value
        val compactBandTop = compactImage.top + compactImage.height / 2f
        val scheduleBandTop = finalImage.top + finalImage.height / 2f
        val bandTop = if (o < 1f) {
            lerp(bandTopStart, compactBandTop, o)
        } else {
            lerp(compactBandTop, scheduleBandTop, c)
        }
        // 01→02 开头白卡还在屏外（contentTop 比屏底低一个 gap），模糊带底边先钉在屏底，
        // 保持 01 的 360dp 底部 scrim 不变；白卡顶边进入屏幕后再由它截断。
        val bandBottom = if (o < 1f) {
            contentTop.coerceAtMost(metrics.screenHeight)
        } else {
            contentTop
        }

        // 日期组：01 基准（导航胶囊上方）→ 02 图片卡内部左下 → 02→04 跟随图片卡下移。
        val baseDateBottom =
            metrics.screenHeight - metrics.navBar - metrics.navReserve - metrics.dateBottomGap
        val compactDateBottom = compactImage.bottom - DayDateInCardBottomPadding.value
        val openDateDelta = compactDateBottom - baseDateBottom
        val collapseDateDelta = finalImage.top - compactImage.top
        val dateTranslationY = o * openDateDelta + o * c * collapseDateDelta

        val topBarAlpha = smoothstep(0f, DayTopBarFadeEnd, c)

        return DayFrame(
            image = image,
            // 01 全屏图先裁成机身圆角，再随卡片收拢连续过渡到 DayCardShape 的 24dp；
            // 02→04 的 open 恒为 1，圆角保持 24dp 不参与收窄。
            imageRadius = lerp(metrics.deviceCornerRadius, DayCardCorner.value, o),
            content = content,
            bandTop = bandTop,
            bandBottom = bandBottom,
            dateTranslationY = dateTranslationY,
            topScrimAlpha = 1f - o,
            topBarAlpha = topBarAlpha,
            topBarTranslationY = -(1f - topBarAlpha) * DayTopBarEntryOffset.value,
            bandTintAlpha = 0.30f * (1f - o),
        )
    }
}

/** 图片在卡内的居中裁切比例：卡片宽 = 屏宽 × scale，图片始终铺满卡片。 */
internal fun DayFrame.imageScale(metrics: DayMetrics): Float =
    if (metrics.screenWidth <= 0f) 1f else image.width / metrics.screenWidth

/**
 * 图片层相对卡片中心的平移（dp）。图片层本身是屏宽×屏高，以 center 为锚缩放后，
 * 平移让图片中心落在卡片中心；父层圆角裁剪裁剪出最终卡片。
 */
internal fun DayFrame.imageTranslationX(metrics: DayMetrics): Float =
    (image.width - metrics.screenWidth) / 2f

internal fun DayFrame.imageTranslationY(metrics: DayMetrics): Float =
    (image.height - metrics.screenHeight) / 2f

private fun lerp(start: DayRect, stop: DayRect, fraction: Float): DayRect = DayRect(
    left = lerp(start.left, stop.left, fraction),
    top = lerp(start.top, stop.top, fraction),
    right = lerp(start.right, stop.right, fraction),
    bottom = lerp(start.bottom, stop.bottom, fraction),
)

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/** Hermite smoothstep：边界处导数为 0，顶栏淡入/白卡末段长高没有生硬折角。 */
internal fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge0 == edge1) return if (value < edge0) 0f else 1f
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
