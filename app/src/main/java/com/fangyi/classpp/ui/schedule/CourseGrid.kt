package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.Outline
import java.util.Date
import kotlin.math.roundToInt

/** 日期带高度（滚动渐隐的参考高度，模糊进度也以此为刻度） */
internal val DateBandHeight = 41.dp

/** 每个节次行的固定高度（容纳两行课程名 + 教师/地点/房间 + 起止时间） */
private val GridRowHeight = 150.dp

/** 单元格内边距，卡片间形成网格沟槽 */
private val CellPadding = 3.dp
private val CellPaddingTop = 2.dp //用于平衡网格线带来的视觉偏差

/** 网格线笔宽 */
private val GridLineWidth = 1.dp

/**
 * 末行之后的尾部留白：扩大课表的可滚动范围（含原 8dp 间距）。
 * 节次少时也能有足够的滚动行程展示折叠/模糊过渡，按需调整此常量即可。
 */
private val TrailingScrollSpace = 120.dp

/** 日期带渐隐速率：值越大消失越快；1.0f 为原始速度（滚完自身高度才消失） */
private const val DateBandFadeSpeed = 3.0f


/**
 * 课表网格：整块内容区是一个横向 Pager，**每周一页**（日期数字带 + 按节次分行的 5 列课程格）。
 * 左右滑动即切换上一周/下一周，卡片与网格线跟手横移，顶栏不动。
 *
 * 关键结构：Pager 是列表里的**唯一 item**，纵向滚动留在 Pager 之外——
 * 三页因此共享同一纵向位置与折叠状态，拖动途中相邻周与当前周严格对齐；
 * 每页高度固定（日期带 + 各行 + 尾部留白），故给 Pager 显式高度，避开 item 纵向约束无界。
 *
 * 与 [ScheduleHeader] 星期行同为零水平边距五等分，天然列对齐，
 * 日期带高亮列与星期行高亮列同源（均由顶栏日期推导，见 [WeekPageContent.highlightDate]）。
 * 折叠通过外部 modifier.nestedScroll 接入，本组件不感知折叠状态。
 * 列表状态由调用方持有（供模糊进度与日期带渐隐计算），顶部偏移由 contentPadding.top 跟随顶栏高度；
 * Pager 状态同样由调用方持有（供周次 ↔ 翻页双向同步）。
 */
@Composable
fun CourseGrid(
    pagerState: PagerState,
    timeSlots: List<TimeSlot>,
    contentForWeek: (Int) -> WeekPageContent,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    editMode: Boolean = false,
    showDates: Boolean = true,
    onAddClick: ((day: Int, slot: TimeSlot) -> Unit)? = null,
) {
    val pageHeight = (if (showDates) DateBandHeight else 0.dp) +
        GridRowHeight * timeSlots.size + TrailingScrollSpace
    // 接缝竖线是否要画：派生成布尔 state —— 拖动中恒为真，只在起手/落位翻转一次。
    // 切不可让 draw 直接读 currentPageOffsetFraction（拖动中逐帧都变）：那会让页面图层
    // 被逐帧判为失效、整页绘制指令（日期带 + 各行卡片）跟着重录一遍，图层也就白加了。
    // 派生 state 只在结果翻转时通知读者，与"是否滚过阈值"是同一用法。
    val seamVisible: State<Boolean> = remember(pagerState) {
        derivedStateOf { pagerState.isSeamVisible }
    }
    LazyColumn(
        state = state,
        contentPadding = contentPadding,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        item(key = "weekPager") {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pageHeight),
                // 编辑态固定当前周（设计稿里编辑态没有周次指示，翻周无从判断），
                // 顺带不必预组合邻页
                beyondViewportPageCount = if (editMode) 0 else 1,
                userScrollEnabled = !editMode,
                // 一次手势最多翻一周（对应「上一周/下一周」），甩动不跨周跳
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(1),
                ),
            ) { page ->
                WeekPage(
                    // 这里刻意不 remember：编辑态的 provider 会读草稿 state，
                    // 记忆会让"刚加的课"刷不出来（key 没变 → 计算不重跑）
                    content = contentForWeek(page + 1),
                    timeSlots = timeSlots,
                    seamVisible = seamVisible,
                    listState = state,
                    showDates = showDates,
                    editMode = editMode,
                    onAddClick = onAddClick,
                )
            }
        }
    }
}

/**
 * 翻页/拖动途中（当前页偏移非零）→ 页右缘正是相邻周的分界，需要补一条竖线。
 * 静止时页右缘与屏幕右缘重合，补线只会贴着屏幕边缘，故不画。
 */
private val PagerState.isSeamVisible: Boolean
    get() = currentPageOffsetFraction != 0f

/**
 * 一周的整页：日期带 + 各节次行 + 尾部留白。
 *
 * 页右缘的接缝竖线分两段补：课程行区由本页补（行内无底色，画在身后即可），
 * 日期带区由 [DateBand] 自己补（带的底色会盖住身后画的线），两段同 x 同笔宽、接成一条。
 * 开关取自派生后的布尔 state（见 [CourseGrid]），只在起手/落位翻转，不随逐帧偏移抖动。
 *
 * 本页自带**独立图层**（空 `graphicsLayer` 即可）：周 Pager 靠放置（placement）移动页面，
 * 页面若没有自己的图层，其绘制指令会被录进 Pager 那一层——每帧位移都要把整页
 * （日期带 + 各行卡片）的指令重录一遍，翻周时就掉帧；有图层后每帧只更新"这一层画在哪"，
 * 页面内容的指令表保持缓存（与 tab 平移同一手法，见 MainActivity.tabPage）。
 */
@Composable
private fun WeekPage(
    content: WeekPageContent,
    timeSlots: List<TimeSlot>,
    seamVisible: State<Boolean>,
    listState: LazyListState,
    showDates: Boolean,
    editMode: Boolean,
    onAddClick: ((day: Int, slot: TimeSlot) -> Unit)?,
) {
    val density = LocalDensity.current
    val bandPx = with(density) { DateBandHeight.toPx() }
    val trailingPx = with(density) { TrailingScrollSpace.toPx() }
    Column(
        modifier = Modifier
            .graphicsLayer { }
            .drawBehind {
                if (seamVisible.value) {
                    val stroke = GridLineWidth.toPx()
                    val x = size.width - stroke / 2f
                    drawLine(
                        Outline,
                        Offset(x, bandPx),
                        Offset(x, size.height - trailingPx),
                        strokeWidth = stroke,
                    )
                }
            },
    ) {
        if (showDates) {
            DateBand(
                weekDates = content.dates,
                highlightDate = content.highlightDate,
                listState = listState,
                seamVisible = seamVisible,
            )
        }
        timeSlots.forEach { slot ->
            GridRow(
                slot = slot,
                courses = content.courses,
                editMode = editMode,
                onAddClick = onAddClick,
            )
        }
        Spacer(modifier = Modifier.height(TrailingScrollSpace))
    }
}

/**
 * 日期数字带：5 天日号，与顶栏日期同日 primary 高亮；随自身滚出量渐隐。
 *
 * 翻页途中页右缘的接缝竖线由本带自己补（页级 drawBehind 补的那段会被带的底色盖住），
 * 画在自身 drawBehind 内 ⇒ 与带内其它竖线一同渐隐，不会在滚过顶栏后留一截浮线；
 * 开关同样取自派生后的布尔 state，避免拖动中逐帧重录本层指令。
 */
@Composable
private fun DateBand(
    weekDates: List<Date>,
    highlightDate: Date,
    listState: LazyListState,
    seamVisible: State<Boolean>,
) {
    val density = LocalDensity.current
    val bandHeightPx = with(density) { DateBandHeight.toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DateBandHeight)
            // 在 draw 阶段读滚动偏移，滚动时不触发整列重组：
            // offset = 0 全显，≥ bandHeight 全隐
            .graphicsLayer {
                // LazyList 在带完全滚入顶部 contentPadding（藏进顶栏背后）后会把
                // firstVisibleItem 切到下一项、offset 归零重计；不加 index 守卫会让
                // alpha 在顶栏后跳回 1，被 Haze 模糊重新捕捉到。
                // 与 ScheduleScreen.blurProgress 的守卫保持一致：
                // index==0 时随 offset 爬升（乘速率倍数更快渐隐），否则带已滚过 → 恒全隐。
                val fadeProgress = if (listState.firstVisibleItemIndex == 0) {
                    (listState.firstVisibleItemScrollOffset * DateBandFadeSpeed / bandHeightPx)
                        .coerceIn(0f, 1f)
                } else {
                    1f
                }
                alpha = 1f - fadeProgress
            }

            .background(MaterialTheme.colorScheme.surfaceContainer)
            // 网格线画在 graphicsLayer 之后（更内层），随日期带渐隐一起淡出；
            // 画在 background 之后，线条压在带底色之上
            .drawBehind {
                val stroke = GridLineWidth.toPx()
                for (i in 1..4) {
                    val x = (size.width * i / 5f).roundToInt().toFloat()
                    drawLine(Outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                }
                // 顶部横线：星期行与网格的分隔线；底部不画（与首行之间无线）
                drawLine(
                    Outline,
                    Offset(0f, stroke / 2),
                    Offset(size.width, stroke / 2),
                    strokeWidth = stroke,
                )
                // 右缘接缝竖线：与下方课程行的补线接成一条（同一 x、同一笔宽）
                if (seamVisible.value) {
                    val x = size.width - stroke / 2f
                    drawLine(Outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        weekDates.forEach { date ->
            val highlighted = date.isSameDay(highlightDate)
            Text(
                text = date.dayOfMonth().toString(),
                modifier = Modifier.weight(1f).offset(y = 1.dp),
                fontSize = 18.sp,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/**
 * 一个节次行：5 个等宽单元格。
 * 普通态有课渲染课程卡片、无课露网格背景；编辑态空位渲染添加卡片，
 * 且"本周不上"的置灰卡片也可点（那一格本周空着，点它去添加，否则永远加不进去）。
 */
@Composable
private fun GridRow(
    slot: TimeSlot,
    courses: List<Course>,
    editMode: Boolean,
    onAddClick: ((day: Int, slot: TimeSlot) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GridRowHeight)
            .drawBehind {
                val stroke = GridLineWidth.toPx()
                for (i in 1..4) {
                    val x = (size.width * i / 5f).roundToInt().toFloat()
                    drawLine(Outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                }
                // 底部横线：行间分隔；顶部不画（与日期带之间无线）
                val y = size.height - stroke / 2
                drawLine(Outline, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            },
    ) {
        for (day in 1..5) {
            val course = courses.findAt(day, slot.id)
            // 编辑态才有添加回调；同一个回调也给置灰卡片用
            val add: (() -> Unit)? = if (editMode && onAddClick != null) {
                { onAddClick(day, slot) }
            } else {
                null
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = CellPadding, end = CellPadding, bottom = CellPadding, top = CellPaddingTop),
            ) {
                when {
                    course == null && add != null -> AddCourseCard(
                        slot = slot,
                        onClick = add,
                        modifier = Modifier.fillMaxSize(),
                    )
                    course != null -> CourseCard(
                        course = course,
                        slot = slot,
                        modifier = Modifier.fillMaxSize(),
                        onClick = if (!course.active) add else null,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "课表网格")
@Composable
private fun CourseGridPreview() {
    ClassppTheme {
        CourseGrid(
            pagerState = rememberPagerState(initialPage = 1) { 20 },
            timeSlots = DefaultTimeSlots,
            contentForWeek = { pageWeek ->
                val dates = datesForWeek(pageWeek)
                WeekPageContent(
                    week = pageWeek,
                    // 预览仍用单周 mock：每周显示同一份课程
                    courses = MockCourses,
                    dates = dates,
                    // 与旧预览一致：高亮所看周的周二
                    highlightDate = dates[1],
                )
            },
        )
    }
}
