package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.ui.schedule.CourseColor
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.DayTimelineNowChipShape
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import kotlin.math.abs

/** 一天的整点数：刻度从 0:00 到次日 0:00 共 [HoursPerDay] + 1 个 */
private const val HoursPerDay = 24

/** 相邻整点刻度的纵向间距，也是时间轴的时间比例尺：1 小时 = [HourSpacing] */
private val HourSpacing = 50.dp

/** 左侧时间区域宽度，刻度在其中水平居中；右缘即网格竖线 */
private val TimeAreaWidth = 40.dp

/** 卡片区域左缘与网格竖线的距离：12dp（与页面卡片水平边距一致） */
private val TaskAreaStartGap = 12.dp

/** 网格线笔宽：与课表页 CourseGrid 同款；当前时刻横线同款 */
private val GridLineWidth = 1.dp

/** 当前时刻矩形规格：距屏幕左缘 [NowChipStartPadding]、定宽 [NowChipWidth]（横线自其
 *  右缘起画，左侧不外露）、高 [NowChipHeight]、时刻字号见 ClassppTextTokens.timelineNowChip */
private val NowChipStartPadding = 2.dp
private val NowChipWidth = 36.dp
private val NowChipHeight = 22.dp

/** SubcomposeLayout 里时间轴主体层的 slot id（卡片内容实测层用任务下标 0..n−1） */
private const val TimelineBodySlot = "day-timeline-body"

/** 实测内容高度缓存的容量上限：按日期切换才会累积，64 条足够覆盖数天 */
private const val MeasuredHeightCacheLimit = 64

/** 实测内容高度缓存键：内容 + 形态 + 卡宽；字体度量由缓存生命周期（见 remember 键）保证 */
private data class CardHeightKey(
    val content: DayTaskMeasureKey,
    val compact: Boolean,
    val narrow: Boolean,
    val widthPx: Int,
)

/**
 * 日程页时间轴：左侧 [TimeAreaWidth] 时间列自 0:00 到次日 0:00 共 [HoursPerDay] + 1 个
 * 整点刻度（基础间隔 [HourSpacing]），右侧为日程卡片区域——[DayTaskCard] 按任务时长
 * 比例排布（基础比例 1 小时 = [HourSpacing]），由 [tasks] 传入当天任务，无任务时整块留白。
 *
 * 时间轴跨度是动态的：卡片需要更多纵向空间时（内容撑高、贴背 2dp 间距），在下方卡
 * 开始时刻插入额外空间；短卡触发紧凑卡 40dp 最低高度（≈48 分钟）时，差额插在卡自己
 * 的结束时刻，卡底恰好落到结束刻度。整点刻度跨度随插入量局部变大，没有插入的小时仍
 * 保持 [HourSpacing]。卡片内容高度由 [DayTaskCardContent] 在求解前**实测**
 * （与渲染同一 composable、同一卡宽），而不是靠镜像排版常量估算——备注折行、字体行高
 * 等任何撑高都会同步推后下卡并让轴局部拉伸，上下卡间距始终 ≥ 2dp。实测结果按
 * [DayTaskMeasureKey] + 形态 + 卡宽缓存（见 [CardHeightKey]），重测命中时直接复用，
 * 不再组合测量副本。映射：
 * y(t) = [HourSpacing] × ((t − 锚点) / 60 + 0.5) + 已插入的累计额外空间。
 * 卡片顶边 = y(开始时刻)，高度见 [solveDayTaskLayout]（上下卡至少 2dp、贴背恰好 2dp）。
 *
 * 当前时间指示器（[nowMinute] 非 null 且不早于锚点时）：Error 色横线自时刻矩形右缘
 * 贯穿屏幕右缘（压过任务卡），时刻矩形（白字 Error 底）距屏幕左缘 [NowChipStartPadding]、
 * 文字居中，垂直对齐当前时刻的映射位置。时刻矩形与某整点刻度文字的纵向距离小于
 * 「矩形半高 + 刻度行高一半」时，该整点刻度文字隐藏——按 [DayTaskLayout.yAt] 的实际
 * 像素距离判断，局部拉伸后分钟距离不再代表视觉距离。
 *
 * 网格线与课表页同款（1dp 实线、colorScheme.outline）：顶部/底部两条全宽横线 + 时间列
 * 右缘一条贯通竖线，无逐小时横线。刻度/卡片/指示器共用 [DayTaskLayout.yAt] 映射，
 * 卡片区域起点 x = [TimeAreaWidth] + [TaskAreaStartGap]、右缘收进 [PageHorizontalSpacing]
 * （与其他日程卡同边距）。
 *
 * 网格锚点（首刻度）= 当天最早任务（[tasks]）开始时刻所在小时的整点（floor，如最早
 * 任务 7:30 → 7:00 起），不显示任务前的空档；无任务时恒为 0:00 起显示一整天；末刻度
 * 固定为次日 0:00。容器高度取「末刻度底边」与「卡片实际底边」的较大值，内容撑出
 * 24:00 时页面继续向下延伸。
 */
@Composable
internal fun DayTimeline(
    modifier: Modifier = Modifier,
    tasks: List<DayTask> = emptyList(),
    nowMinute: Int? = null,
) {
    val outline = MaterialTheme.colorScheme.outline
    val secondaryText = MaterialTheme.classppColors.secondaryText
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val layoutDirection = LocalLayoutDirection.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val configuration = LocalConfiguration.current
    val textStyle = LocalTextStyle.current
    // 实测内容高度缓存：内容/形态/卡宽与字体度量都相同时，重测不再组合测量副本。
    // 字体解析器、密度（含字号缩放）、布局方向、区域配置或环境文字样式变化时整体重建
    val cardHeightCache = remember(
        density,
        layoutDirection,
        fontFamilyResolver,
        configuration,
        textStyle,
    ) { mutableMapOf<CardHeightKey, Dp>() }
    // 锚点（首刻度）= 当天最早任务开始时刻所在小时的整点（floor，如最早任务 7:30 → 7:00）；
    // 无任务时恒为 0:00 起显示整天。末刻度固定为次日 0:00。
    val anchorHour = tasks.minOfOrNull { it.startMinute }?.div(60) ?: 0
    val anchorMinute = anchorHour * 60
    val hourLabels = remember(anchorHour) {
        List(HoursPerDay - anchorHour + 1) { index ->
            val hour = anchorHour + index
            // 末刻度为次日 0:00（TimeText.format 只收 0..1439，此处特判）
            if (hour == HoursPerDay) "0:00" else TimeText.format(hour * 60)
        }
    }
    // 卡宽/形态只由区域宽与时长决定：先用估算求解一次拿到每卡宽度，再实测内容高度并
    // 重新求解纵向排布。SubcomposeLayout 让实测发生在放置之前——内容撑高的卡片会参与
    // 2dp 间距与轴拉伸计算，而不是渲染后发现压住了下卡
    SubcomposeLayout(modifier = modifier.fillMaxWidth()) { constraints ->
        val regionWidth = constraints.maxWidth.toDp() -
            TimeAreaWidth - TaskAreaStartGap - PageHorizontalSpacing
        val estimated = tasks.solveDayTaskLayout(
            regionWidth = regionWidth,
            hourSpacing = HourSpacing,
            anchorMinute = anchorMinute,
            fontScale = fontScale,
        )
        val contentHeights = estimated.placements.mapIndexed { index, placement ->
            val widthPx = placement.width.roundToPx()
            val cacheKey = CardHeightKey(
                content = placement.task.measureKey,
                compact = placement.compact,
                narrow = placement.narrow,
                widthPx = widthPx,
            )
            // 命中即复用上次实测值：内容/形态/卡宽与字体度量都没变，高度必然相同，
            // 省掉测量副本的组合与文本排版；未命中才 subcompose 测量并回填缓存
            cardHeightCache[cacheKey] ?: run {
                val measured = subcompose(index) {
                    DayTaskCardContent(
                        task = placement.task,
                        compact = placement.compact,
                        narrow = placement.narrow,
                    )
                }.first().measure(
                    Constraints(minWidth = widthPx, maxWidth = widthPx),
                ).height.toDp()
                if (cardHeightCache.size >= MeasuredHeightCacheLimit) cardHeightCache.clear()
                cardHeightCache[cacheKey] = measured
                measured
            }
        }
        val solvedLayout = tasks.solveDayTaskLayout(
            regionWidth = regionWidth,
            hourSpacing = HourSpacing,
            anchorMinute = anchorMinute,
            fontScale = fontScale,
            contentHeights = contentHeights.takeIf { it.isNotEmpty() },
        )
        val body = subcompose(TimelineBodySlot) {
            DayTimelineBody(
                layout = solvedLayout,
                tasks = tasks,
                nowMinute = nowMinute,
                anchorHour = anchorHour,
                anchorMinute = anchorMinute,
                hourLabels = hourLabels,
                outline = outline,
                secondaryText = secondaryText,
            )
        }.first()
        val bodyPlaceable = body.measure(constraints)
        layout(bodyPlaceable.width, bodyPlaceable.height) {
            bodyPlaceable.placeRelative(0, 0)
        }
    }
}

/**
 * 时间轴主体（网格线 + 整点刻度 + 任务卡 + 当前时刻指示器）：[layout] 已按实测内容
 * 高度求解完毕，刻度和卡片的纵向位置共用同一 [DayTaskLayout.yAt] 映射。
 */
@Composable
private fun DayTimelineBody(
    layout: DayTaskLayout,
    tasks: List<DayTask>,
    nowMinute: Int?,
    anchorHour: Int,
    anchorMinute: Int,
    hourLabels: List<String>,
    outline: Color,
    secondaryText: Color,
) {
    // 末刻度（次日 0:00）的格子底边；卡片内容撑出 24:00 时容器继续向下延伸
    val axisBottom = layout.yAt(HoursPerDay * 60) + HourSpacing / 2
    val cardBottom = layout.placements.maxOfOrNull { it.offsetY + it.height } ?: 0.dp
    val timelineHeight = maxOf(axisBottom, cardBottom)
    // 当前时刻线（早于锚点即不在时间轴范围内）与刻度隐藏距离：时刻矩形半高 + 刻度
    // 文字行高一半，按 yAt 的像素差判断——局部拉伸后分钟差不再等于视觉距离
    val visibleNowMinute = nowMinute?.takeIf { it >= anchorMinute }
    val nowY = visibleNowMinute?.let { layout.yAt(it) }
    val labelHalfHeight = with(LocalDensity.current) {
        ClassppTextTokens.timelineLabel.lineHeight.toDp()
    } / 2
    val nowNearTickDistance = NowChipHeight / 2 + labelHalfHeight
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(timelineHeight)
            .drawBehind {
                val stroke = GridLineWidth.toPx()
                // 首尾横线全宽，笔迹整体落在容器内（与课表页首/末线同口径）
                drawLine(
                    outline,
                    Offset(0f, stroke / 2),
                    Offset(size.width, stroke / 2),
                    strokeWidth = stroke,
                )
                drawLine(
                    outline,
                    Offset(0f, size.height - stroke / 2),
                    Offset(size.width, size.height - stroke / 2),
                    strokeWidth = stroke,
                )
                // 时间列右缘竖线：贯通上下横线
                val x = TimeAreaWidth.toPx()
                drawLine(outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            },
    ) {
        hourLabels.forEachIndexed { index, label ->
            // 刻度中心 = y(该整点)；局部拉伸会改变相邻刻度的间距，未受影响的小时
            // 仍为 HourSpacing。时刻矩形与本刻度中心的像素距离过近时文字隐藏，该处
            // 由指示器矩形就近覆盖
            val tickMinute = (anchorHour + index) * 60
            val tickY = layout.yAt(tickMinute)
            val hiddenByNow = nowY != null &&
                abs((tickY - nowY).value) < nowNearTickDistance.value
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = tickY - HourSpacing / 2)
                    .size(width = TimeAreaWidth, height = HourSpacing),
                contentAlignment = Alignment.Center,
            ) {
                if (!hiddenByNow) {
                    Text(
                        text = label,
                        style = ClassppTextTokens.timelineLabel,
                        color = secondaryText,
                    )
                }
            }
        }
        if (tasks.isNotEmpty()) {
            // 卡片区域：网格竖线右侧留呼吸距、右缘与页面其他日程卡对齐；区域内按
            // 时长比例求解位置/宽度/接触角（重叠均分、纵向间距与局部拉伸见求解器）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = TimeAreaWidth + TaskAreaStartGap, end = PageHorizontalSpacing),
            ) {
                layout.placements.forEach { DayTaskCard(it) }
            }
        }
        if (visibleNowMinute != null && nowY != null) {
            // 当前时间指示器：Error 色横线自时刻矩形右缘起贯穿屏幕右缘（不外露矩形
            // 左侧，覆盖层压过任务卡与刻度），并向左多画 1dp 压进矩形底下保证接缝无隙；
            // 时刻矩形贴时间列左缘、定宽定高、文字水平垂直居中，垂直对齐当前时刻的
            // 映射位置（与刻度/卡片同一 yAt）
            val error = MaterialTheme.colorScheme.error
            val onError = MaterialTheme.colorScheme.onError
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawLine(
                            error,
                            Offset(
                                (NowChipStartPadding + NowChipWidth - 1.dp).toPx(),
                                nowY.toPx(),
                            ),
                            Offset(size.width, nowY.toPx()),
                            strokeWidth = GridLineWidth.toPx(),
                        )
                    },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = NowChipStartPadding, y = nowY - NowChipHeight / 2)
                    .size(width = NowChipWidth, height = NowChipHeight)
                    .clip(DayTimelineNowChipShape)
                    .background(error),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = TimeText.format(visibleNowMinute),
                    style = ClassppTextTokens.timelineNowChip,
                    color = onError,
                )
            }
        }
    }
}

// ---------- 预览 ----------

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 900)
@Composable
private fun DayTimelinePreview() {
    ClassppTheme {
        // 最早任务 8:00 → 锚点 8:00（轴顶即 8:00 刻度），指示器 9:00 压过首卡
        DayTimeline(
            tasks = listOf(
                DayTask.Course(
                    name = "数据结构",
                    teacher = "陈锦秀",
                    location = "学武楼 A304",
                    note = "这是备注内容，这是备注内容",
                    color = CourseColor.Blue,
                    startMinute = 8 * 60,
                    endMinute = 9 * 60 + 40,
                ),
                DayTask.Todo(
                    id = "todo-1",
                    name = "待办标题待办标题待办标题",
                    completed = false,
                    deadlineText = "10/27 6:00",
                    deadlineColor = Color(0xFF006FEF),
                    onToggle = null,
                    startMinute = 9 * 60 + 40,
                    endMinute = 10 * 60,
                ),
            ),
            nowMinute = 9 * 60,
        )
    }
}
