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
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.Correct
import com.fangyi.classpp.ui.theme.Error
import com.fangyi.classpp.ui.theme.PillShape
import java.util.Date
import kotlin.math.roundToInt

/** 日期带高度（滚动渐隐的参考高度，模糊进度也以此为刻度） */
internal val DateBandHeight = 41.dp

/** 5 天视图下每个节次行的固定高度（容纳两行课程名 + 教师/地点/房间 + 起止时间） */
internal val GridRowHeight = 150.dp

/**
 * 7 天视图每行**多加**的高度：列一窄，课名换行更多，卡片就得更长一点。
 * 想调"7 日视图比 5 日视图高多少"只改这一个数值：
 * 行高、日期带之外的页高、跨节卡高度、@Preview 全部由 [gridRowHeight] 派生。
 */
internal val SevenDayRowExtraHeight = 10.dp

/** 按列数取每行高度：5 天视图 [GridRowHeight]，7 天视图再加 [SevenDayRowExtraHeight]（其他天数按 7 天算） */
internal fun gridRowHeight(daysPerWeek: Int): Dp =
    if (daysPerWeek > 5) GridRowHeight + SevenDayRowExtraHeight else GridRowHeight

/** 单元格内边距，卡片间形成网格沟槽（@Preview 与网格保持一致） */
internal val CellPadding = 3.dp
internal val CellPaddingTop = 2.dp //用于平衡网格线带来的视觉偏差

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
 * 课表网格：整块内容区是一个横向 Pager，**每周一页**（日期数字带 + 按节次分行的课程格）。
 * 左右滑动即切换上一周/下一周，卡片与网格线跟手横移，顶栏不动。
 *
 * 列数由 [daysPerWeek] 决定（5 = 周一~五，7 = 周一~日），行/列结构与顶栏星期行同为零水平边距
 * 等分，天然列对齐；每周页里 `content.dates` 的个数应与 [daysPerWeek] 一致（见 ScheduleScreen）。
 *
 * 关键结构：Pager 是列表里的**唯一 item**，纵向滚动留在 Pager 之外——
 * 三页因此共享同一纵向位置与折叠状态，拖动途中相邻周与当前周严格对齐；
 * 每页高度固定（日期带 + 各行 + 尾部留白），故给 Pager 显式高度，避开 item 纵向约束无界。
 *
 * 日期带高亮列与星期行高亮列同源（均由顶栏日期推导，见 [WeekPageContent.highlightDate]）。
 * 折叠通过外部 modifier.nestedScroll 接入，本组件不感知折叠状态。
 * 列表状态由调用方持有（供模糊进度与日期带渐隐计算），顶部偏移由 contentPadding.top 跟随顶栏高度；
 * Pager 状态同样由调用方持有（供周次 ↔ 翻页双向同步）。
 *
 * 喂进来的 [WeekPageContent.courses] 已由 resolveWeekCards 解析过：各卡两两不重叠
 * （交替课程只画当周那门，起止节次可以不一致），非本周的交替课只剩卡片底部 1/4 色条。
 */
@Composable
fun CourseGrid(
    pagerState: PagerState,
    timeSlots: List<TimeSlot>,
    contentForPage: (Int) -> WeekPageContent,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    editMode: Boolean = false,
    showDates: Boolean = true,
    daysPerWeek: Int = 5,
    onAddClick: ((day: Int, slot: TimeSlot) -> Unit)? = null,
    onEditClick: ((courseId: String) -> Unit)? = null,
    onCourseLongClick: ((courseId: String, anchor: Rect) -> Unit)? = null,
    onSlotLongClick: ((day: Int, slot: TimeSlot, anchor: Rect) -> Unit)? = null,
) {
    // 列数至少 1（0/负值理论上被校验挡住，这里兜一下避免算出空页宽或除零）
    val days = daysPerWeek.coerceAtLeast(1)
    // 行高随列数变（7 天视图更高）：页高、每行、跨节卡三处都取自同一个值，否则卡片会溢出格子
    val rowHeight = gridRowHeight(days)
    val pageHeight = (if (showDates) DateBandHeight else 0.dp) +
        rowHeight * timeSlots.size + TrailingScrollSpace
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
        modifier = modifier.background(MaterialTheme.colorScheme.background),
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
                    // 记忆会让"刚加的课"刷不出来（key 没变 → 计算不重跑）。
                    // 入参是**页索引**：页 0 = 最左页（学期前为第 0 周/当今周，学期后为
                    // 第 1 周），页 → 周的换算由调用方在 contentForPage 内完成
                    content = contentForPage(page),
                    timeSlots = timeSlots,
                    days = days,
                    rowHeight = rowHeight,
                    seamVisible = seamVisible,
                    listState = state,
                    showDates = showDates,
                    editMode = editMode,
                    onAddClick = onAddClick,
                    onEditClick = onEditClick,
                    onCourseLongClick = onCourseLongClick,
                    onSlotLongClick = onSlotLongClick,
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
 * 页面内容的指令表保持缓存（与 tab 页转场层同一手法，见 MainActivity.tabLayer）。
 *
 * **跨节卡叠加层**：[Box] 内第二层镜像复刻行/列结构（同 weight 分列、同行高、同 CellPadding），
 * 后绘制故不透明卡片压过行分界网格线；空节点无 pointerInput，点击穿透回底层网格。
 * 编辑态跨节卡整卡可点（含续格覆盖区域）→ 编辑该课；长按 → 交替课程菜单。
 * 底层 [GridRow] 对起始格与续格留空（见其注释），两层分工不重叠。
 *
 * 编辑态空位的添加卡片：点击 → 新建课程；长按 → 粘贴课程菜单（[onSlotLongClick]，
 * 仅剪贴板有课时调用方才弹菜单，见 ScheduleScreen）。
 */
@Composable
private fun WeekPage(
    content: WeekPageContent,
    timeSlots: List<TimeSlot>,
    days: Int,
    rowHeight: Dp,
    seamVisible: State<Boolean>,
    listState: LazyListState,
    showDates: Boolean,
    editMode: Boolean,
    onAddClick: ((day: Int, slot: TimeSlot) -> Unit)?,
    onEditClick: ((courseId: String) -> Unit)?,
    onCourseLongClick: ((courseId: String, anchor: Rect) -> Unit)?,
    onSlotLongClick: ((day: Int, slot: TimeSlot, anchor: Rect) -> Unit)?,
) {
    val density = LocalDensity.current
    val bandPx = with(density) { DateBandHeight.toPx() }
    val trailingPx = with(density) { TrailingScrollSpace.toPx() }
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .graphicsLayer { }
            .drawBehind {
                if (seamVisible.value) {
                    val stroke = GridLineWidth.toPx()
                    // 页右缘正是最后一列的边界（列按宽度等分）→ 与末尾接缝线同一条
                    val x = size.width - stroke / 2f
                    drawLine(
                        outline,
                        Offset(x, bandPx),
                        Offset(x, size.height - trailingPx),
                        strokeWidth = stroke,
                    )
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showDates) {
                DateBand(
                    weekDates = content.dates,
                    days = days,
                    highlightDate = content.highlightDate,
                    highlightIsToday = content.highlightIsToday,
                    termStartDate = content.termStartDate,
                    termEndDate = content.termEndDate,
                    listState = listState,
                    seamVisible = seamVisible,
                )
            }
            timeSlots.forEach { slot ->
                GridRow(
                    slot = slot,
                    courses = content.courses,
                    days = days,
                    rowHeight = rowHeight,
                    editMode = editMode,
                    onAddClick = onAddClick,
                    onEditClick = onEditClick,
                    onCourseLongClick = onCourseLongClick,
                    onSlotLongClick = onSlotLongClick,
                )
            }
            Spacer(modifier = Modifier.height(TrailingScrollSpace))
        }

        // ——— 跨节卡叠加层：y 偏移由镜像 Spacer(日期带) + 行高自然对齐 ———
        Column(modifier = Modifier.matchParentSize()) {
            if (showDates) Spacer(modifier = Modifier.height(DateBandHeight))
            timeSlots.forEach { slot ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight),
                ) {
                    for (day in 1..days) {
                        val course = content.courses.findAt(day, slot.id)
                        val cardBounds = remember(day) { BoundsHolder() }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(
                                    start = CellPadding,
                                    end = CellPadding,
                                    top = CellPaddingTop,
                                    bottom = CellPadding,
                                ),
                        ) {
                            if (course != null && course.span > 1) {
                                val lastIdx = (slot.id - 1 + course.span - 1)
                                    .coerceAtMost(timeSlots.lastIndex)
                                val effSpan = lastIdx - (slot.id - 1) + 1
                                CourseCard(
                                    course = course,
                                    slot = slot,
                                    endTime = timeSlots[lastIdx].endTime,
                                    // 必须 required：单元格内容区最高只有一行（rowHeight − 内边距），
                                    // 普通 height() 会被父约束钳回单行高度，跨不出去。
                                    // requiredHeight 对被钳掉的超高内容默认居中放置（卡顶偏上 (H−行高)/2），
                                    // 先放开高度约束（无钳制即无居中），再按 Top 钉在格顶、向下溢出，
                                    // 卡顶/卡底与普通卡四边内缩一致，任意 span 成立
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                        .requiredHeight(rowHeight * effSpan - CellPaddingTop - CellPadding)
                                        // 长按菜单的锚点取卡片自身窗口坐标（溢出部分按整卡算）
                                        .onGloballyPositioned { cardBounds.value = it.boundsInWindow() },
                                    // 整张跨节卡可点（含续格覆盖区域）→ 编辑态开编辑面板、
                                    // 浏览态开课程详情浮层，含义由调用方的回调路由
                                    onClick = if (onEditClick != null) {
                                        { onEditClick(course.id) }
                                    } else {
                                        null
                                    },
                                    // 编辑态长按 → 以这门课为基准新建交替课程
                                    onLongClick = if (editMode && onCourseLongClick != null) {
                                        { onCourseLongClick(course.id, cardBounds.value) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日期数字带：各上课日一行日号，与顶栏日期同日 primary 高亮；随自身滚出量渐隐。
 * 列数与 [days]（5/7）一致，竖分隔线由 [columnDividerXs] 与课程行同源计算。
 * [highlightIsToday] = 高亮列是今天（查看今周）：该日号垫主题 primaryContainer 胶囊底；
 * 浏览其它周高亮该周周一，只变色不加底。
 *
 * 学期起止日标记（[termStartDate]/[termEndDate] 落在带内时）：绿（Correct）/红（Error）字，
 * 标出学期的头尾；起止日恰为「今天」时不标记——今天样式（蓝字 + 胶囊底）优先。
 *
 * 翻页途中页右缘的接缝竖线由本带自己补（页级 drawBehind 补的那段会被带的底色盖住），
 * 画在自身 drawBehind 内 ⇒ 与带内其它竖线一同渐隐，不会在滚过顶栏后留一截浮线；
 * 开关同样取自派生后的布尔 state，避免拖动中逐帧重录本层指令。
 */
@Composable
private fun DateBand(
    weekDates: List<Date>,
    days: Int,
    highlightDate: Date,
    highlightIsToday: Boolean,
    termStartDate: Date?,
    termEndDate: Date?,
    listState: LazyListState,
    seamVisible: State<Boolean>,
) {
    val density = LocalDensity.current
    val bandHeightPx = with(density) { DateBandHeight.toPx() }
    val outline = MaterialTheme.colorScheme.outline

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

            .background(MaterialTheme.colorScheme.background)
            // 网格线画在 graphicsLayer 之后（更内层），随日期带渐隐一起淡出；
            // 画在 background 之后，线条压在带底色之上
            .drawBehind {
                val stroke = GridLineWidth.toPx()
                // 各列内部边界（共 days-1 条）；末列右界即页右缘，与接缝线同一条故不重复画
                columnDividerXs(size.width, days).forEach { x ->
                    drawLine(outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                }
                // 顶部横线：星期行与网格的分隔线；底部不画（与首行之间无线）
                drawLine(
                    outline,
                    Offset(0f, stroke / 2),
                    Offset(size.width, stroke / 2),
                    strokeWidth = stroke,
                )
                // 右缘接缝竖线：仅在翻页途中出现，与下方课程行的补线接成一条（同一 x、同一笔宽）
                if (seamVisible.value) {
                    val x = size.width - stroke / 2f
                    drawLine(outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        weekDates.forEach { date ->
            val highlighted = date.isSameDay(highlightDate)
            // 今天样式 = 高亮列是今天（垫胶囊底的那种）：学期起止日恰为今天时绿/红让位
            val isTodayStyle = highlighted && highlightIsToday
            val isTermStart = !isTodayStyle && termStartDate != null && date.isSameDay(termStartDate)
            val isTermEnd = !isTodayStyle && termEndDate != null && date.isSameDay(termEndDate)
            // 当今日期（仅查看今周时高亮列是今天）：日号垫主题 primaryContainer 胶囊底——
            // 背景写在 padding 之前（包住文字与内边距），文字保持原字号原色，只多一层底；
            // 浏览其它周（高亮该周周一）只变色不加底。文字由等宽 Box 居中，非今天格视觉不变
            Box(
                modifier = Modifier
                    .weight(1f)
                    .offset(y = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = date.dayOfMonth().toString(),
                    modifier = if (isTodayStyle) {
                        Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, PillShape)
                            .padding(horizontal = 14.dp, vertical = 3.dp)
                    } else {
                        Modifier
                    },
                    fontSize = 18.sp,
                    fontWeight = if (highlighted || isTermStart || isTermEnd) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Medium
                    },
                    // 学期起止日标记优先于普通高亮（今天样式已在上游让位）：
                    // 开始日绿、结束日红；一日起止的极端学期按开始日（绿）计
                    color = when {
                        isTermStart -> Correct
                        isTermEnd -> Error
                        highlighted -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/**
 * 一个节次行：按 [days] 等分（5 或 7）的等宽单元格，行高 [rowHeight]（7 天视图更高，见 [gridRowHeight]）。
 * 普通态有课渲染课程卡片、无课露网格背景；编辑态空位渲染添加卡片（点击新建、长按出粘贴菜单），
 * 已有课程卡（含本周不上的置灰卡）点击进编辑、长按出课程菜单。
 * 跨节课的起始格与续格都留空——卡片由 [WeekPage] 的叠加层跨行绘制。
 */
@Composable
private fun GridRow(
    slot: TimeSlot,
    courses: List<Course>,
    days: Int,
    rowHeight: Dp,
    editMode: Boolean,
    onAddClick: ((day: Int, slot: TimeSlot) -> Unit)?,
    onEditClick: ((courseId: String) -> Unit)?,
    onCourseLongClick: ((courseId: String, anchor: Rect) -> Unit)?,
    onSlotLongClick: ((day: Int, slot: TimeSlot, anchor: Rect) -> Unit)?,
) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .drawBehind {
                val stroke = GridLineWidth.toPx()
                columnDividerXs(size.width, days).forEach { x ->
                    drawLine(outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                }
                // 底部横线：行间分隔；顶部不画（与日期带之间无线）
                val y = size.height - stroke / 2
                drawLine(outline, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            },
    ) {
        for (day in 1..days) {
            val course = courses.findAt(day, slot.id)
            // 续格（被跨节课覆盖）：不画课卡也不画添加卡，点击自然落空
            val covered = courses.isContinuationAt(day, slot.id)
            // 编辑态才有添加回调（只给空位添加卡用；已有课卡点击走 onEditClick）
            val add: (() -> Unit)? = if (editMode && onAddClick != null && !covered) {
                { onAddClick(day, slot) }
            } else {
                null
            }
            val cardBounds = remember(day) { BoundsHolder() }
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
                        // 长按粘贴菜单的锚点取添加卡片自身窗口坐标（填满格 = 格坐标）；
                        // 回调仅在编辑态传入（同下方课程卡），菜单是否弹出由调用方按剪贴板决定
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { cardBounds.value = it.boundsInWindow() },
                        onLongClick = if (editMode && onSlotLongClick != null) {
                            { onSlotLongClick(day, slot, cardBounds.value) }
                        } else {
                            null
                        },
                    )
                    course != null && course.span == 1 -> CourseCard(
                        course = course,
                        slot = slot,
                        modifier = Modifier
                            .fillMaxSize()
                            // 长按菜单的锚点取卡片自身窗口坐标（填满格 = 格坐标）
                            .onGloballyPositioned { cardBounds.value = it.boundsInWindow() },
                        // 点击：编辑态开编辑面板、浏览态开课程详情浮层（回调路由见 ScheduleScreen）
                        onClick = if (onEditClick != null) {
                            { onEditClick(course.id) }
                        } else {
                            null
                        },
                        // 长按菜单仅编辑态传入（复制/新建交替都是编辑动作）
                        onLongClick = if (editMode && onCourseLongClick != null) {
                            { onCourseLongClick(course.id, cardBounds.value) }
                        } else {
                            null
                        },
                    )
                    // span > 1 的起始格与 covered 格：留空，卡片由叠加层绘制
                }
            }
        }
    }
}

/**
 * 卡片窗口坐标（长按弹菜单的锚点）：只在长按回调里读，故存普通字段而非 Compose state
 * ——写在 onGloballyPositioned 里也不会像 state 那样让布局阶段触发重组。
 */
private class BoundsHolder {
    var value: Rect = Rect.Zero
}

@Preview(showBackground = true, name = "课表网格")
@Composable
private fun CourseGridPreview() {
    ClassppTheme {
        CourseGrid(
            pagerState = rememberPagerState(initialPage = 1) { 20 },
            timeSlots = DefaultTimeSlots,
            contentForPage = { page ->
                val pageWeek = page + 1
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

/** 7 天视图预览：7 列（含周六/周日）与日期带 7 个日号同屏 */
@Preview(showBackground = true, name = "课表网格 · 7 天", widthDp = 411)
@Composable
private fun CourseGridSevenDayPreview() {
    ClassppTheme {
        CourseGrid(
            pagerState = rememberPagerState(initialPage = 1) { 20 },
            timeSlots = DefaultTimeSlots,
            daysPerWeek = 7,
            contentForPage = { page ->
                val pageWeek = page + 1
                val dates = datesForWeek(pageWeek, daysPerWeek = 7)
                WeekPageContent(
                    week = pageWeek,
                    courses = MockCoursesWeekend,
                    dates = dates,
                    highlightDate = dates[1],
                )
            },
        )
    }
}
