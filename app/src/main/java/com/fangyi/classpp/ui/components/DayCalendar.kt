package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.DayCardShape
import com.fangyi.classpp.ui.theme.classppColors
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 日历卡（DayCalendar）：设计稿的月历卡片，自带白底圆角卡壳，自上而下三层——
 *
 * 一、**星期表头**：一~日（R.array.weekdays_short，周一起始），15sp Medium；今天所在列
 *     Primary 色（仅当卡内展示的月/周包含今天时高亮，浏览其他月份不高亮）。
 * 二、**日历网格**：每天一枚 34dp 圆球（background 色），日期数字 17sp Medium；今天圆球
 *     Primary、数字 onPrimary；非本月日期数字 secondaryText（圆球颜色不变）。有待办的日期
 *     （[markedDates]）在圆球下方画一枚 4dp Primary 小点——小点从 34dp 圆球 Box 底对齐后
 *     offset 出球体，落在行距之间，不占布局空间、不撑开行距。
 * 三、**底栏**：左侧「XXXX年XX月」（无前导零）标题，右侧左右两个圆形箭头按钮（Primary）：
 *     展开态切换上/下月（日号月末收拢），折叠态切换上/下周（锚点 ±7 天）。
 *
 * **折叠**：单行周视图是月历的折叠形态，进度走 [DayCalendarFoldState]（0 = 月历，1 = 单行）。
 * 折叠进度同时驱动外框高度（整月网格高 ↔ 单行高插值，clipToBounds 裁掉滑出内容）与网格
 * 整体上移（锚点周成为唯一可见行），表头与底栏固定不动。进度在 layout/graphicsLayer 内
 * 读取，拖拽全程不重组。两种驱动方式：
 * - **自驱**（默认，[foldState] 不传）：组件在表头/网格区挂竖向拖拽，上滑跟手折叠、
 *   下滑跟手展开，松手按位移过半或甩动速度吸附；
 * - **宿主驱动**（[foldState] 传入）：组件内手势自动关闭，由宿主在自己的手势里跟手写
 *   进度——日程页 04 用这种方式把折叠并进根级转场手势（上滑先折叠、下滑先展开再过渡
 *   02，见 DayScreen），避免两套手势抢同一根手指。
 *
 * **锚点（anchor）唯一**：展开态展示锚点所在月、折叠态展示锚点所在周，标题始终显示锚点
 * 所在年月——两种形态、按钮切换与折叠联动共用同一份锚点状态。本组件暂不接数据库，
 * [markedDates] 由调用方传入；锚点月可无限翻页，小点这类窗口化数据按
 * [onGridRangeChange] 回报的网格日期窗口取值即可（见 DayScreen 的待办小点）。[onDateClick]
 * 本次预留（null = 无点击交互）。
 */
@Composable
fun DayCalendar(
    modifier: Modifier = Modifier,
    markedDates: Set<IsoDate> = emptySet(),
    onDateClick: ((IsoDate) -> Unit)? = null,
    onGridRangeChange: ((firstDate: IsoDate, lastDate: IsoDate) -> Unit)? = null,
    foldState: DayCalendarFoldState? = null,
    initiallyFolded: Boolean = false,
    today: IsoDate = IsoDate.today(),
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // 宿主未传折叠状态时自建（自驱）；传入则复用且关闭组件内手势（宿主驱动）
    val fold = foldState ?: remember { DayCalendarFoldState(scope, initiallyFolded) }
    val selfGesture = foldState == null
    val folded by remember { derivedStateOf { fold.progress >= 0.5f } }

    var anchor by remember(today) { mutableStateOf(today) }
    val (anchorYear, anchorMonth, _) = anchor.yearMonthDay()

    // 网格起点 = 本月 1 号所在 ISO 周的周一；行数按月动态 5/6 行
    val firstOfMonth = remember(anchorYear, anchorMonth) { IsoDate.of(anchorYear, anchorMonth, 1) }
    val gridStart = firstOfMonth.mondayOfWeek()
    val daysInMonth = (firstOfMonth.plusMonthsClamped(1) - firstOfMonth).toInt()
    val rowCount = (firstOfMonth.isoDayOfWeek() - 1 + daysInMonth + 6) / 7
    // 完全折叠后唯一可见的行：锚点日期所在周（锚点必在本月内，索引必然合法）
    val visibleWeekIndex = ((anchor.mondayOfWeek().epochDay - gridStart.epochDay) / 7L).toInt()

    // 展示中的网格日期窗口（首尾都含前后月补位日期）：markedDates 这类窗口化数据的取值
    // 范围。经 rememberUpdatedState 恒调最新回调（同 DayScreen 的 commitState 惯例）；
    // 仅锚点月翻页时窗口变化才回调——折叠只在月内取行、窗口不变。
    val lastGridDate = gridStart + rowCount * 7 - 1
    val gridRangeChange by rememberUpdatedState(onGridRangeChange)
    LaunchedEffect(gridStart, lastGridDate) {
        gridRangeChange?.invoke(gridStart, lastGridDate)
    }

    val monthContentHeight = RowStep * rowCount - RowSpacing + DotZone
    val weekContentHeight = BallSize + DotZone
    val foldRangeDp = monthContentHeight - weekContentHeight
    // 行程与甩动阈值随锚点月行数（5/6 行）变化：回填给折叠状态，自驱/宿主手势共用
    val density = LocalDensity.current
    val foldRangePx = with(density) { foldRangeDp.toPx() }
    val foldFlingThresholdPx = with(density) { FoldFlingVelocity.toPx() }
    SideEffect {
        fold.rangePx = foldRangePx
        fold.flingThresholdPx = foldFlingThresholdPx
    }

    // 今天列高亮：展示的月/周包含今天才高亮（浏览其他月份不高亮）
    val todayColumn = today.isoDayOfWeek() - 1
    val showsToday = if (folded) {
        anchor.mondayOfWeek() == today.mondayOfWeek()
    } else {
        val (todayYear, todayMonth, _) = today.yearMonthDay()
        anchorYear == todayYear && anchorMonth == todayMonth
    }

    val monthTitle = stringResource(R.string.date_year_month_cn_format, anchorYear, anchorMonth)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, DayCardShape)
            .padding(horizontal = CardPaddingHorizontal, vertical = CardPaddingVertical),
    ) {
        // —— 手势区（仅自驱模式）：表头 + 网格。竖向拖拽跟手折叠/展开；日期点击是
        // 子级 clickable，起手超过触摸阈值前不消费位移，与拖拽天然不冲突。
        // 宿主驱动模式不挂手势：折叠由宿主根级手势统一驱动（DayScreen 04）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (selfGesture) {
                        Modifier.pointerInput(Unit) {
                            val tracker = VelocityTracker()
                            detectVerticalDragGestures(
                                onDragStart = {
                                    tracker.resetTracking()
                                    // 打断吸附动画，从当前进度接管（跟手）
                                    fold.stop()
                                },
                                onDragCancel = { fold.settle(0f) },
                                onDragEnd = { fold.settle(tracker.calculateVelocity().y) },
                                onVerticalDrag = { change, dragAmount ->
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    change.consume()
                                    // 上滑（dragAmount < 0）折叠：进度增，行程换算见
                                    // [DayCalendarFoldState.dragBy]
                                    fold.dragBy(dragAmount)
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            WeekdayHeader(
                todayColumn = todayColumn,
                highlightToday = showsToday,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(HeaderGridGap))
            // 折叠外框：高度插值 + 裁切都在 foldHeight 内完成（裁切必须先于测量挂链）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .foldHeight(fold, monthContentHeight, weekContentHeight),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            // 网格整体上移，让锚点周在完全折叠时对齐外框顶部、成为唯一可见行；
                            // 底部预留的 DotZone 让末行/单行的待办小点不被裁掉
                            translationY = -(RowStep * visibleWeekIndex).toPx() * fold.progress
                        },
                    verticalArrangement = Arrangement.spacedBy(RowSpacing),
                ) {
                    repeat(rowCount) { row ->
                        Row(Modifier.fillMaxWidth()) {
                            repeat(7) { column ->
                                val date = gridStart + row * 7 + column
                                DayCell(
                                    date = date,
                                    inMonth = date.epochDay >= firstOfMonth.epochDay &&
                                        date.epochDay < firstOfMonth.epochDay + daysInMonth,
                                    isToday = date == today,
                                    marked = date in markedDates,
                                    onClick = onDateClick,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(BottomBarGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = monthTitle,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                fontSize = TitleFontSize,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            NavButton(
                iconRes = R.drawable.ic_arrow_circle_left,
                contentDescription = stringResource(
                    if (folded) R.string.cd_calendar_previous_week else R.string.cd_calendar_previous_month,
                ),
                onClick = { anchor = if (folded) anchor - 7 else anchor.plusMonthsClamped(-1) },
            )
            NavButton(
                iconRes = R.drawable.ic_arrow_circle_right,
                contentDescription = stringResource(
                    if (folded) R.string.cd_calendar_next_week else R.string.cd_calendar_next_month,
                ),
                onClick = { anchor = if (folded) anchor + 7 else anchor.plusMonthsClamped(1) },
            )
        }
    }
}

/**
 * DayCalendar 折叠状态：[progress] 0 = 月历展开，1 = 收拢为锚点周单行。
 *
 * 两种驱动方式（见 [DayCalendar] KDoc）：
 * - **自驱**：组件内部手势调用 [dragBy] 跟手、[settle] 松手收束；
 * - **宿主驱动**：宿主在自己的手势里调用同一组接口——与 DayTransitionState 的
 *   dragXxxBy/settle 同一口径（跟手同步直写 snapshot float，不 suspend）。
 *
 * [progress] 跟手期间每帧只触发重测/重绘，不重组。折叠总行程（月历 5/6 行不同）与
 * 甩动阈值由组件经 SideEffect 回填（[rangePx]/[flingThresholdPx]），宿主无需感知。
 */
@Stable
class DayCalendarFoldState(
    private val scope: CoroutineScope,
    initiallyFolded: Boolean = false,
) {
    var progress by mutableFloatStateOf(if (initiallyFolded) 1f else 0f)
        private set

    /** 进度过半即视为折叠（周单行）形态 */
    val folded: Boolean get() = progress >= 0.5f

    /** 折叠总行程（px）：月历网格高 - 单行高，由组件按锚点月行数回填 */
    internal var rangePx by mutableFloatStateOf(0f)

    /** 甩动判向阈值（px/s），由组件回填（FoldFlingVelocity 换算） */
    internal var flingThresholdPx by mutableFloatStateOf(0f)

    private var animationJob: Job? = null

    /**
     * 跟手推进：[deltaY] 为手指位移（正 = 下滑展开，负 = 上滑折叠），返回实际消费的
     * 像素（与 [deltaY] 同号；端点上没有可消费量时返回 0，剩余位移交给外层手势/滚动）。
     * 调用即接管：在途吸附动画被取消，从当前进度继续跟手。
     */
    fun dragBy(deltaY: Float): Float {
        animationJob?.cancel()
        animationJob = null
        val range = rangePx
        if (range <= 0f || deltaY == 0f) return 0f
        val next = (progress - deltaY / range).coerceIn(0f, 1f)
        val consumedPx = (progress - next) * range
        if (consumedPx == 0f) return 0f
        progress = next
        return consumedPx
    }

    /**
     * 松手吸附：下滑速度优先判展开、上滑优先判折叠，否则按过半吸附；
     * 弹簧收尾用 [Motion.settle]（可见阈值换算成不足一像素的进度值，末帧不跳）。
     */
    fun settle(velocityY: Float) {
        val target = when {
            velocityY > flingThresholdPx -> 0f
            velocityY < -flingThresholdPx -> 1f
            else -> if (progress >= 0.5f) 1f else 0f
        }
        if (progress == target) return
        animationJob?.cancel()
        animationJob = scope.launch {
            val driver = Animatable(progress)
            driver.animateTo(
                targetValue = target,
                animationSpec = Motion.settle(0.5f / rangePx.coerceAtLeast(1f)),
            ) {
                progress = value
            }
            progress = target
        }
    }

    /** 新手势接管：停住在途吸附动画，从当前进度继续跟手 */
    fun stop() {
        animationJob?.cancel()
        animationJob = null
    }

    /** 无动画立即回展开态（跨天重置等场景，与 DayTransitionState.resetForNewDay 配对） */
    fun reset() {
        stop()
        progress = 0f
    }
}

/** 创建挂到 [scope] 的 [DayCalendarFoldState]；独立使用传给 [DayCalendar] 即自驱折叠 */
@Composable
fun rememberDayCalendarFoldState(
    scope: CoroutineScope,
    initiallyFolded: Boolean = false,
): DayCalendarFoldState = remember(scope) { DayCalendarFoldState(scope, initiallyFolded) }

/** 星期表头：一~日 7 列均分，今天所在列 Primary（仅当 [highlightToday]） */
@Composable
private fun WeekdayHeader(
    todayColumn: Int,
    highlightToday: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val labels = stringArrayResource(R.array.weekdays_short)
    Row(modifier = modifier) {
        labels.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(BallSize),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = WeekdayFontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = if (highlightToday && index == todayColumn) colors.primary else colors.onSurface,
                )
            }
        }
    }
}

/** 单个日期：34dp 圆球 + 数字；[marked] 时球下画 4dp Primary 小点（不占布局空间） */
@Composable
private fun DayCell(
    date: IsoDate,
    inMonth: Boolean,
    isToday: Boolean,
    marked: Boolean,
    onClick: ((IsoDate) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(BallSize)
                .then(
                    if (onClick != null) {
                        Modifier.pressClickable(shape = CircleShape) { onClick(date) }
                    } else {
                        Modifier
                    },
                )
                .background(if (isToday) colors.primary else colors.background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth().toString(),
                fontSize = DayNumberFontSize,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    isToday -> colors.onPrimary
                    !inMonth -> MaterialTheme.classppColors.secondaryText
                    else -> colors.onSurface
                },
            )
            if (marked) {
                // BottomCenter 对齐后整体下移 DotDrop：球底到小点顶的间隙即 DotGap；
                // 呼吸区 DotZone 含 2dp 余量，小点底缘悬在裁切边界之上不被削
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = DotDrop)
                        .size(DotSize)
                        .background(colors.primary, CircleShape),
                )
            }
        }
    }
}

/** 底栏左右切换按钮：24dp 图标 + 40dp 圆形触控区（按压反馈沿圆轮廓） */
@Composable
private fun NavButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(NavButtonSize)
            .pressClickable(shape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(NavIconSize),
        )
    }
}

/**
 * 折叠外框高度：在 [expanded]/[collapsed] 之间随折叠进度插值。进度在 measure 块内
 * 读取——动画/拖拽每帧只触发重测，不走重组。
 */
/**
 * 折叠外框：上报高度在 [expanded]/[collapsed] 之间随折叠进度插值，并把内容裁切到这个
 * 高度内。裁切必须发生在测量**外层**（本函数内部的 clipToBounds 先于 layout 挂链）——
 * 它裁的是动画中的外框高度；内容则按自然高度测量（内层约束放开），只显示不挤压。
 * 若把裁切挂在调用侧 layout 之后（内层），它裁到的会是内容的自然高度，滑出的行会
 * 溢出外框、叠到底栏上。进度在 measure 块内读取——拖拽每帧只触发重测，不走重组。
 */
private fun Modifier.foldHeight(
    fold: DayCalendarFoldState,
    expanded: Dp,
    collapsed: Dp,
): Modifier = clipToBounds().layout { measurable, constraints ->
    val height = lerp(expanded, collapsed, fold.progress).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
    )
    layout(placeable.width, height) { placeable.placeRelative(0, 0) }
}

// ---------- 日历运算辅助（UTC 无时区语义同 IsoDate；项目惯例规避 java.time） ----------

private const val MillisPerDay = 86_400_000L
private val UtcZone: TimeZone = TimeZone.getTimeZone("UTC")

private fun IsoDate.toCalendar(): GregorianCalendar =
    GregorianCalendar(UtcZone).apply { timeInMillis = epochDay * MillisPerDay }

/** 几号（1..31），日历格子上的数字 */
private fun IsoDate.dayOfMonth(): Int =
    toCalendar().get(GregorianCalendar.DAY_OF_MONTH)

/** 月份步进，月末收拢（1/31 + 1 月 = 2/28），语义同 RepeatSchedule 的私有 plusMonths */
private fun IsoDate.plusMonthsClamped(months: Int): IsoDate {
    val cal = toCalendar().apply { add(GregorianCalendar.MONTH, months) }
    return IsoDate(Math.floorDiv(cal.timeInMillis, MillisPerDay))
}

// ---------- 视觉规格（设计稿量取；颜色一律取主题，见各用点） ----------

private val BallSize = 34.dp
private val RowSpacing = 10.dp
private val RowStep = BallSize + RowSpacing
/** 星期表头与日历网格的间距，独立于行距 [RowSpacing]，可单独调 */
private val HeaderGridGap = 6.dp
private val DotSize = 4.dp
private val DotGap = 3.dp
/** 小点从球底下垂的距离：底对齐后 offset 它，球底到小点顶的间隙即 [DotGap] */
private val DotDrop = DotSize + DotGap
/**
 * 网格内容底部为待办小点预留的呼吸区（折叠裁切与小点溢出共用该边界）。
 * 在 [DotDrop] 之外多留 2dp 余量：非整数像素密度下 dp→px 取整会让小点底缘
 * 恰好压在 clipToBounds 边界上被削掉一点，余量保证它悬在边界之上。
 */
private val DotZone = DotDrop + 2.dp
private val NavButtonSize = 40.dp
private val NavIconSize = 24.dp
private val CardPaddingHorizontal = 8.dp
private val CardPaddingVertical = 8.dp
private val BottomBarGap = 4.dp
/** 折叠甩动吸附的速度阈值（对标 M3 anchoredDraggable 默认值） */
internal val FoldFlingVelocity = 125.dp
private val WeekdayFontSize = 15.sp
private val DayNumberFontSize = 17.sp
private val TitleFontSize = 17.sp

// ---------- 预览 ----------

/** 预览用固定「今天」（设计稿基准 2026-10-21 周三）与设计稿同款待办小点数据 */
private val PreviewToday = IsoDate.of(2026, 10, 21)

private val PreviewMarkedDates: Set<IsoDate> = listOf(
    "2026-09-28", "2026-09-29", "2026-09-30",
    "2026-10-06", "2026-10-08", "2026-10-13", "2026-10-14", "2026-10-15",
    "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-27", "2026-10-28", "2026-10-29",
).map { IsoDate.parse(it) }.toSet()

/** 月历展开·浅色（设计稿上图） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun DayCalendarMonthLightPreview() = ClassppTheme {
    DayCalendar(
        markedDates = PreviewMarkedDates,
        today = PreviewToday,
        modifier = Modifier.padding(12.dp),
    )
}

/** 月历展开·深色 */
@Preview(showBackground = true, backgroundColor = 0xFF0F1114, widthDp = 390)
@Composable
private fun DayCalendarMonthDarkPreview() = ClassppTheme(darkTheme = true) {
    DayCalendar(
        markedDates = PreviewMarkedDates,
        today = PreviewToday,
        modifier = Modifier.padding(12.dp),
    )
}

/** 折叠周视图·浅色（设计稿下图：单行周视图是月历的折叠形态） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun DayCalendarWeekLightPreview() = ClassppTheme {
    DayCalendar(
        markedDates = PreviewMarkedDates,
        today = PreviewToday,
        initiallyFolded = true,
        modifier = Modifier.padding(12.dp),
    )
}
