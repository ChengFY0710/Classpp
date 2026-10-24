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

/** 02 图片卡高度；02→04 随宽度等比缩小，04 高度 = 240 × (200/卡宽)。 */
internal val DayCardHeight = 240.dp

/** 图片卡与白底内容卡的间距。 */
internal val DayCardGap = 24.dp

/** 图片卡/白卡圆角（DayCardShape 同值）。 */
internal val DayCardCorner = 24.dp

/**
 * 白卡几何占位高度：只喂给 frame.content.bottom（测试按此断言），生产 UI 不消费——
 * 真实白卡内容是 DayCalendar 组件，高度随月/周折叠形态自适应；卡的位置一律由
 * frame.content.top 决定，02/03/04 全程只改位置不拉高。
 */
internal val DayContentHeight = 235.dp

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
internal val DayTopScrimHeight = 120.dp

/** 01 底部渐变磨砂高度（沿用原 DayScreen）。 */
internal val DayBottomScrimHeight = 320.dp

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

/** 02→04 顶栏内容（标题）淡入并下移完成的 collapse 进度。 */
internal const val DayTopBarContentFadeEnd = 0.6f

/** 02→04 顶栏背景（渐变模糊）开始淡入的 collapse 进度；接近 04 才出现。 */
internal const val DayTopBarBackgroundFadeStart = 0.7f

/**
 * 图片卡（含下段模糊带）开始渐隐的 collapse 进度：此前保持不透明，
 * 让设计稿 03 的中间构图仍有完整图片卡；此后随白卡上移逐渐淡出。
 */
internal const val DayImageFadeStart = 0.5f

/**
 * 日期组（含图片 icon）渐隐完成的 collapse 进度：日期组位于图片卡下段，
 * 会比图片卡本体更早被白卡盖住，所以从 02 出发就开始淡出。
 */
internal const val DayDateFadeEnd = 0.4f

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
     * 机身圆角半径（dp）：01 滑动开始瞬间套到全屏图上的裁剪半径，也是 01→02
     * 圆角插值的起点。01 静止态不裁剪（直角），滑动一开始才取这个值。
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
    /**
     * 图片卡圆角：01 静止态在 0 与机身圆角之间由 deviceCornerAlpha 控制
     * （展开落定后渐收成直角）；用户一开始滑动（open > 0）立即切到机身圆角，
     * 再随 01→02 连续插值到 24dp；02/04 恒为 24dp。
     */
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
    /** 日期组相对 01 基准位的横向位移：02→04 随卡片收窄向右归位。 */
    val dateTranslationX: Float,
    /** 日期组相对 01 基准位的纵向位移。 */
    val dateTranslationY: Float,
    /**
     * 日期组整体缩放：01→02 保持 1；02→04 跟随图片卡宽度比例缩小，
     * 让“日期 / 日期文字 / 图片 icon”一起收缩。锚点在组内左下角。
     */
    val dateScale: Float,
    /** 01 顶部磨砂/提示的透明度（1→0）。 */
    val topScrimAlpha: Float,
    /** 顶栏内容（标题）透明度：先于背景淡入，04→02 最后淡出。 */
    val topBarContentAlpha: Float,
    /** 顶栏背景（渐变模糊）透明度：接近 04 才淡入，04→02 先淡出。 */
    val topBarBackgroundAlpha: Float,
    /** 顶栏内容进场下移量（负值，随内容 alpha 归零）；背景不参与位移。 */
    val topBarTranslationY: Float,
    /** 01 底部 scrim 的黑色 tint 浓度（02 起为 0，交给纯模糊）。 */
    val bandTintAlpha: Float,
    /** 图片卡（含下段模糊带）透明度：02→04 后段渐隐，04 为 0，避免过滚动时露出台历残影。 */
    val imageAlpha: Float,
    /** 日期组（含图片 icon）透明度：先于图片卡被白卡盖住，02→04 前段渐隐，04 为 0。 */
    val dateAlpha: Float,
) {
    /** 模糊带可见高度；白卡顶边越过卡片中点后为 0（03/04 无残留）。 */
    val bandHeight: Float get() = (bandBottom - bandTop).coerceAtLeast(0f)
}

internal object DayGeometry {

    /**
     * @param deviceCornerAlpha 01 静止态的机身圆角系数（0 = 直角，1 = 机身圆角）。
     *   02→01 展开动画落定后由状态机从 1 渐变到 0，让圆角收成直角；open > 0 时
     *   几何按滑动中的机身圆角取值，忽略此系数。
     */
    fun frame(
        metrics: DayMetrics,
        open: Float,
        collapse: Float,
        deviceCornerAlpha: Float = 0f,
    ): DayFrame {
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
        val finalImageWidth = DayScheduleImageWidth.value
        // 等比缩小：宽度缩到 200dp 时，高度按同一比例收缩（240 × 200/378）。
        val finalImageHeight = if (compactImage.width > 0f) {
            cardHeight * finalImageWidth / compactImage.width
        } else {
            cardHeight
        }
        val finalImage = DayRect(
            left = (metrics.screenWidth - finalImageWidth) / 2f,
            top = metrics.statusBar + DayScheduleImageTopInset.value,
            right = (metrics.screenWidth + finalImageWidth) / 2f,
            bottom = metrics.statusBar + DayScheduleImageTopInset.value + finalImageHeight,
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
        // 白卡就是日程页内容本体，高度固定；02→04 只改位置，不拉高。
        val contentHeight = DayContentHeight.value
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

        // 02→04 日期组整体跟随图片卡宽度缩放；01→02 保持 1（设计稿里日期行尺寸不变）。
        // 横向按卡内左边距等比内收，保证收窄后日期组仍贴在卡片左侧。
        val dateScale = if (o < 1f) {
            1f
        } else {
            (image.width / compactImage.width).coerceIn(0f, 1f)
        }
        val dateInnerStart = DayDateEdgePadding.value - compactImage.left
        val dateTranslationX = if (o < 1f) {
            0f
        } else {
            (image.left + dateInnerStart * dateScale) - DayDateEdgePadding.value
        }

        // 日期组：01 基准（导航胶囊上方）→ 02 图片卡内部左下 → 02→04 跟随图片卡。
        // 图片高度等比缩小后底边上移，日期组底边跟着 image.bottom 走，并按 dateScale
        // 缩放卡内下边距，保证整组始终留在图片卡内部。
        val baseDateBottom =
            metrics.screenHeight - metrics.navBar - metrics.navReserve - metrics.dateBottomGap
        val compactDateBottom = compactImage.bottom - DayDateInCardBottomPadding.value
        val openDateDelta = compactDateBottom - baseDateBottom
        val dateTranslationY = if (o < 1f) {
            o * openDateDelta
        } else {
            val targetBottom =
                image.bottom - DayDateInCardBottomPadding.value * dateScale
            openDateDelta + (targetBottom - compactDateBottom)
        }

        // 顶栏内容先淡入/下移；背景接近 04 才独立淡入，且不参与位移。
        // 04→02 反过来：背景先淡出，内容再淡出上移。
        val topBarContentAlpha = smoothstep(0f, DayTopBarContentFadeEnd, c)
        val topBarBackgroundAlpha = smoothstep(DayTopBarBackgroundFadeStart, 1f, c)
        // 02→04 的台历淡出：图片卡后段渐隐，日期组更早被白卡盖住、从 02 起就淡出。
        // 04 两者都归零，白卡被 Overscroll 拖上去时不会露出图片卡/日期组残影。
        val imageAlpha = 1f - smoothstep(DayImageFadeStart, 1f, c)
        val dateAlpha = 1f - smoothstep(0f, DayDateFadeEnd, c)

        return DayFrame(
            image = image,
            // 01 静止态从满幅直角开始；展开落定后 deviceCornerAlpha 从 1 渐收到 0，
            // 圆角随之由机身圆角平滑收成直角。滑动一开始（open > 0）立即切回机身
            // 圆角，再随卡片收拢连续过渡到 DayCardShape 的 24dp。02→04 的 open 恒为 1，
            // 圆角保持 24dp 不参与收窄。
            imageRadius = if (o <= 0f) {
                metrics.deviceCornerRadius * deviceCornerAlpha.coerceIn(0f, 1f)
            } else {
                lerp(metrics.deviceCornerRadius, DayCardCorner.value, o)
            },
            content = content,
            bandTop = bandTop,
            bandBottom = bandBottom,
            dateTranslationX = dateTranslationX,
            dateTranslationY = dateTranslationY,
            dateScale = dateScale,
            topScrimAlpha = 1f - o,
            topBarContentAlpha = topBarContentAlpha,
            topBarBackgroundAlpha = topBarBackgroundAlpha,
            topBarTranslationY =
                -(1f - topBarContentAlpha) * DayTopBarEntryOffset.value,
            bandTintAlpha = 0.30f * (1f - o),
            imageAlpha = imageAlpha,
            dateAlpha = dateAlpha,
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
