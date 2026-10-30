package com.fangyi.classpp.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.lerp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.kyant.shapes.RoundedRectangle
import com.fangyi.classpp.ui.theme.ClassppTextTokens
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
 * 一、**星期表头**：一~日（R.array.weekdays_short，周一起始），16sp SemiBold；今天所在列
 *     Primary 色（仅当卡内展示的月/周包含今天时高亮，浏览其他月份不高亮）。
 * 二、**日历网格**：每天一枚 34dp 圆球（background 色），日期数字 16sp SemiBold；选中日期
 *     圆球 Primary、数字 onPrimary（[onDateClick] 点选即切日，选中/取消走 150ms 背景+
 *     字色渐变，与课程编辑的周数方格同款）；今天圆球 primaryContainer、
 *     数字 primary（选中今天即回到实心 Primary）；非本月日期数字 secondaryText（圆球颜色
 *     不变）。有待办的日期（[markedDates]）在圆球下方画一枚 4dp Primary 小点——小点从
 *     34dp 圆球 Box 底对齐后 offset 出球体，落在行距之间，不占布局空间、不撑开行距。
 * 三、**底栏**：左侧「XXXX年XX月」（无前导零）标题，右侧左右两个圆形箭头按钮（Primary）：
 *     展开态切换上/下月（日号月末收拢），折叠态切换上/下周（锚点 ±7 天）。选中非今天时
 *     标题右侧出现「回到今天」文字按钮（Primary，见 [onBackToToday]），进出场只占用标题
 *     与箭头之间的空白，不撑动底栏布局。
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
 * 所在年月——两种形态、按钮切换与折叠联动共用同一份锚点状态。锚点初始与选中日对齐
 * （[selectedDate]，null = 今天），选中日变化后自动跟随——点选日期球与宿主「回到今天」
 * 都走这一条路；箭头翻页只挪锚点、不改选中。锚点变化即触发**横滑换页**（见
 * [MonthSwap]：Motion.AgendaCalendarSwitchMillis + Overlay 曲线，新页从方向侧滑入、
 * 旧页滑出）：展开态翻月/跨月跳转让整月网格横滑、外框高度在 5/6 行间随同一进度插值、
 * 底栏不跳，标题短滑淡入淡出与网格同帧；折叠态翻周让可见周行横滑换行（跨月时仍走
 * 整月换页，各层用自己的锚点周对齐可见行）。本组件暂不接数据库，[markedDates] 由调用方
 * 传入；锚点月可无限翻页，小点这类窗口化数据按 [onGridRangeChange] 回报的网格日期窗口
 * 取值即可（见 DayScreen 的待办小点）。
 */
@Composable
fun DayCalendar(
    modifier: Modifier = Modifier,
    selectedDate: IsoDate? = null,
    markedDates: Set<IsoDate> = emptySet(),
    onDateClick: ((IsoDate) -> Unit)? = null,
    onBackToToday: (() -> Unit)? = null,
    onGridRangeChange: ((firstDate: IsoDate, lastDate: IsoDate) -> Unit)? = null,
    foldState: DayCalendarFoldState? = null,
    initiallyFolded: Boolean = false,
    today: IsoDate = IsoDate.today(),
) {
    val colors = MaterialTheme.colorScheme
    // 选中查看的日期：null = 跟随 today（未接选中态的调用方/预览保持原「今天高亮」不变）
    val selected = selectedDate ?: today
    val scope = rememberCoroutineScope()
    // 宿主未传折叠状态时自建（自驱）；传入则复用且关闭组件内手势（宿主驱动）
    val fold = foldState ?: remember { DayCalendarFoldState(scope, initiallyFolded) }
    val selfGesture = foldState == null
    val folded by remember { derivedStateOf { fold.progress >= 0.5f } }

    // 锚点初始与选中日对齐，选中日变化（点选日期球/宿主回到今天）后自动跟随；箭头翻页
    // 只挪锚点、不改选中，两条路在此汇合
    var anchor by remember(today) { mutableStateOf(selected) }
    LaunchedEffect(selected) { anchor = selected }
    val (anchorYear, anchorMonth, _) = anchor.yearMonthDay()

    // 换页横滑：snapshotFlow 收集锚点变化，与上一显示锚点比对——跨月则整月网格换页
    // （新旧两层月网格横滑），月内挪周（折叠态箭头 ±7 天）则同一份月网格横滑换可见
    // 周行，共用 Motion.AgendaCalendarSwitchMillis + Overlay 一条曲线。lastDisplayedAnchor
    // 随每次锚点变化更新——折叠周翻页在月内挪过锚点再跨月时，快照里的锚点周
    // （fromWeekIndex）仍然准确。切换途中来了新锚点：当前动画先播完（collect 挂起），
    // 再按最新目标起下一页——连点累积翻页、不跳帧。网格参数一律从目标月日期现场推算
    // （本协程跨重组存活，捕获组合期局部值会过期）。三个状态同用 remember(today)：
    // 跨天重置一起回到选中日，不当成一次换页。
    val swapState = remember(today) { mutableStateOf<MonthSwap?>(null) }
    var lastDisplayedAnchor by remember(today) { mutableStateOf(anchor) }
    LaunchedEffect(today) {
        snapshotFlow { anchor }.collect { target ->
            val prev = lastDisplayedAnchor
            lastDisplayedAnchor = target
            val direction = if (target.epochDay > prev.epochDay) 1 else -1
            val (prevYear, prevMonth, _) = prev.yearMonthDay()
            val (targetYear, targetMonth, _) = target.yearMonthDay()
            val targetFirst = IsoDate.of(targetYear, targetMonth, 1)
            val targetGridStart = targetFirst.mondayOfWeek()
            val targetDaysInMonth = (targetFirst.plusMonthsClamped(1) - targetFirst).toInt()
            val targetRowCount = (targetFirst.isoDayOfWeek() - 1 + targetDaysInMonth + 6) / 7

            if (prevYear == targetYear && prevMonth == targetMonth) {
                // 折叠态周切换：锚点月没变、只挪了周——同一份月网格换可见行，横滑语义
                // 与月切换一致。展开态周内变化不可见（fold.progress = 0，网格整体原位）；
                // 同周变化（点选日期球等）没有可动的内容，两种情况都直接跳过。
                val prevWeekIndex = ((prev.mondayOfWeek().epochDay - targetGridStart.epochDay) / 7L).toInt()
                val targetWeekIndex = ((target.mondayOfWeek().epochDay - targetGridStart.epochDay) / 7L).toInt()
                if (prevWeekIndex == targetWeekIndex || !folded) return@collect
                animateSwap(
                    swapState,
                    MonthSwap(
                        fromGridStart = targetGridStart,
                        fromFirstOfMonth = targetFirst,
                        fromDaysInMonth = targetDaysInMonth,
                        fromRowCount = targetRowCount,
                        fromWeekIndex = prevWeekIndex,
                        fromMonthHeight = RowStep * targetRowCount - RowSpacing + DotZone,
                        direction = direction,
                    ),
                )
            } else {
                // 跨月：整月换页（折叠周翻页跨月 / 展开翻月 / 回到今天 / 点邻月补位日期）
                val prevFirst = IsoDate.of(prevYear, prevMonth, 1)
                val prevGridStart = prevFirst.mondayOfWeek()
                val prevDaysInMonth = (prevFirst.plusMonthsClamped(1) - prevFirst).toInt()
                val prevRowCount = (prevFirst.isoDayOfWeek() - 1 + prevDaysInMonth + 6) / 7
                animateSwap(
                    swapState,
                    MonthSwap(
                        fromGridStart = prevGridStart,
                        fromFirstOfMonth = prevFirst,
                        fromDaysInMonth = prevDaysInMonth,
                        fromRowCount = prevRowCount,
                        fromWeekIndex = ((prev.mondayOfWeek().epochDay - prevGridStart.epochDay) / 7L).toInt(),
                        fromMonthHeight = RowStep * prevRowCount - RowSpacing + DotZone,
                        direction = direction,
                    ),
                )
            }
        }
    }

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
            // 折叠外框：高度插值 + 裁切都在 foldHeight 内完成（裁切必须先于测量挂链）。
            // 内部最多两层整月网格——目标月（锚点月）常驻，换页（翻月/折叠翻周）在途时
            // 叠一层旧页快照：两层同宽、横向位移互补（monthLayer），如胶片换页般无缝
            // 横滑，横向溢出由外框 clipToBounds 裁掉。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .foldHeight(fold, swapState, monthContentHeight, weekContentHeight),
            ) {
                MonthGrid(
                    modifier = Modifier
                        .fillMaxWidth()
                        .monthLayer(fold, swapState, isTarget = true, weekIndex = visibleWeekIndex),
                    firstOfMonth = firstOfMonth,
                    gridStart = gridStart,
                    daysInMonth = daysInMonth,
                    rowCount = rowCount,
                    today = today,
                    selected = selected,
                    markedDates = markedDates,
                    onDateClick = onDateClick,
                )
                swapState.value?.let { swap ->
                    MonthGrid(
                        modifier = Modifier
                            .fillMaxWidth()
                            .monthLayer(fold, swapState, isTarget = false, weekIndex = swap.fromWeekIndex),
                        firstOfMonth = swap.fromFirstOfMonth,
                        gridStart = swap.fromGridStart,
                        daysInMonth = swap.fromDaysInMonth,
                        rowCount = swap.fromRowCount,
                        today = today,
                        selected = selected,
                        markedDates = markedDates,
                        // 滑出层不响应点选：点到旧月日期会把选中跳回旧月、触发反向横滑
                        onDateClick = null,
                    )
                }
            }
        }
        Spacer(Modifier.height(BottomBarGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 年月标题：随月切换做方向性短滑 + 淡入淡出，与网格横滑同帧、同曲线同时长
            // （AgendaCalendarSwitchMillis + Overlay），整卡换页一个节奏；折叠翻周不动
            // 标题（年月没变，targetState 相同即无转场）。滑距取文本宽 1/3，尺寸变化
            // （如 9 月 ↔ 10 月）由默认 SizeTransform 平滑。
            AnimatedContent(
                targetState = anchorYear to anchorMonth,
                transitionSpec = {
                    val forward = targetState.first * 12 + targetState.second >
                        initialState.first * 12 + initialState.second
                    val move = tween<IntOffset>(Motion.AgendaCalendarSwitchMillis, easing = Motion.Overlay)
                    val fade = tween<Float>(Motion.AgendaCalendarSwitchMillis, easing = Motion.Overlay)
                    if (forward) {
                        (slideInHorizontally(move) { it / 3 } + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(move) { -it / 3 } + fadeOut(fade))
                    } else {
                        (slideInHorizontally(move) { -it / 3 } + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(move) { it / 3 } + fadeOut(fade))
                    }
                },
                modifier = Modifier.padding(start = 12.dp, end = 6.dp),
                label = "dayCalendarMonthTitle",
            ) { (year, month) ->
                Text(
                    text = stringResource(R.string.date_year_month_cn_format, year, month),
                    style = ClassppTextTokens.cardTitle,
                    color = colors.onSurface,
                )
            }
            // 「回到今天」：选中非今天且宿主可响应（回调非空）时出现。渐隐出现/渐隐消失，
            // 与课表页顶栏「返回本周」同一节奏（Motion.FastMillis：进场减速、退场加速）。
            // 占位即时进出、宽度只占用标题与箭头之间的空白（末尾 Spacer 吸收），标题、
            // 箭头与底栏行高都不动；触控盒定高 32dp（收敛在 NavButton 的 40dp 行内）、
            // 文字居中——字号怎么调盒子高度都不变，布局与字号彻底解耦
            AnimatedVisibility(
                visible = onBackToToday != null && selected != today,
                enter = fadeIn(tween(Motion.FastMillis, easing = Motion.Decelerate)),
                exit = fadeOut(tween(Motion.FastMillis, easing = Motion.Accelerate)),
            ) {
                Box(
                    modifier = Modifier
                        .height(BackToTodayHeight)
                        .pressClickable(BackToTodayShape) { onBackToToday?.invoke() }
                        .clip(BackToTodayShape)
                        .padding(horizontal = BackToTodayPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.back_to_today),
                        style = ClassppTextTokens.calendarAction,
                        color = colors.primary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
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
     * 收束动画用普通缓动曲线（[Motion.AgendaCalendarFoldMillis] + [Motion.Standard]），
     * 不走弹簧的非线性收尾。
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
                animationSpec = tween(
                    Motion.AgendaCalendarFoldMillis,
                    easing = Motion.Standard,
                ),
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

/**
 * 一次换页的横滑快照：被替换页（from）的网格参数 + 滑动方向 + 进度。月切换时 from 为
 * 旧月网格；折叠周切换时 from 与目标同月——同一份月网格换可见周行，两层各自对齐自己
 * 的锚点周。[progress] 由 DayCalendar 内收集锚点变化的协程驱动 0→1；读侧（[monthLayer]
 * 的 graphicsLayer 与 [foldHeight] 的 measure）延迟读取，动画全程不重组。
 *
 * @param fromGridStart 旧页网格起点（1 号所在 ISO 周的周一）
 * @param fromWeekIndex 旧页锚点周所在行（折叠态旧层上移对齐可见行用）
 * @param fromMonthHeight 旧页网格内容高（RowStep × 行数 - RowSpacing + DotZone，
 *   口径同 monthContentHeight）
 * @param direction +1 = 翻下一页（内容左移、新页从右滑入），-1 = 翻上一页（镜像）
 */
private class MonthSwap(
    val fromGridStart: IsoDate,
    val fromFirstOfMonth: IsoDate,
    val fromDaysInMonth: Int,
    val fromRowCount: Int,
    val fromWeekIndex: Int,
    val fromMonthHeight: Dp,
    val direction: Int,
) {
    val progress = Animatable(0f)
}

/** 起一次换页：快照入 [swapState] → 按 [Motion.AgendaCalendarSwitchMillis] 节奏驱动到落定 → 清快照 */
private suspend fun animateSwap(swapState: MutableState<MonthSwap?>, swap: MonthSwap) {
    swapState.value = swap
    swap.progress.animateTo(1f, tween(Motion.AgendaCalendarSwitchMillis, easing = Motion.Overlay))
    swapState.value = null
}

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
                    style = ClassppTextTokens.calendarWeekday,
                    color = if (highlightToday && index == todayColumn) colors.primary else colors.onSurface,
                )
            }
        }
    }
}

/**
 * 单个日期：34dp 圆球 + 数字。选中实心 Primary、数字 onPrimary；今天未选中 primaryContainer
 * 淡底、数字 primary；[marked] 时球下画 4dp Primary 小点（不占布局空间）。选中/取消时
 * 背景与字色各走一条渐变（[Motion.FastMillis] 先快后慢，与课程编辑的周数方格同节奏）。
 */
@Composable
private fun DayCell(
    date: IsoDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    marked: Boolean,
    onClick: ((IsoDate) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // 背景/字色各走一条渐变：点选渐变变蓝、再点渐变回底色，字色同步过渡
    //（Motion.FastMillis 先快后慢，与课程编辑的周数方格同一节奏）
    val ballColor by animateColorAsState(
        targetValue = when {
            isSelected -> colors.primary
            isToday -> colors.primaryContainer
            else -> colors.background
        },
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "dayCellColor",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            isSelected -> colors.onPrimary
            isToday -> colors.primary
            !inMonth -> MaterialTheme.classppColors.secondaryText
            else -> colors.onSurface
        },
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "dayCellLabel",
    )
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
                .background(ballColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth().toString(),
                style = ClassppTextTokens.calendarDay,
                color = labelColor,
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

/**
 * 整月网格：[rowCount] 行、每行 7 枚 [DayCell]（weight 均分，周一起始）。横滑/折叠的
 * 位移全部由调用方挂在 [modifier] 上（monthLayer 在绘制期读进度），本函数只管内容。
 * 单元格按日期加 key：换月时同一调用位的组合槽位会换成新日期，颜色动画状态必须随
 * 旧日期销毁、新日期从目标色起步，否则新页会从旧日期残留的选中色开始渐变（翻月闪蓝）。
 */
@Composable
private fun MonthGrid(
    modifier: Modifier = Modifier,
    firstOfMonth: IsoDate,
    gridStart: IsoDate,
    daysInMonth: Int,
    rowCount: Int,
    today: IsoDate,
    selected: IsoDate,
    markedDates: Set<IsoDate>,
    onDateClick: ((IsoDate) -> Unit)?,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RowSpacing),
    ) {
        repeat(rowCount) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val date = gridStart + row * 7 + column
                    key(date) {
                        DayCell(
                            date = date,
                            inMonth = date.epochDay >= firstOfMonth.epochDay &&
                                date.epochDay < firstOfMonth.epochDay + daysInMonth,
                            isToday = date == today,
                            isSelected = date == selected,
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
 * 月网格层位移：换页（展开翻月、折叠翻周）横滑与折叠纵移合并在一个 graphicsLayer 里，
 * 进度全部在绘制期读取——每帧只更新图层矩阵，不重组。横滑：目标页从方向侧滑入
 * （偏移 direction × 层宽 → 0），旧页滑出（0 → -direction × 层宽），两层同宽、位移互补，
 * 滑动中如胶片换页般无缝衔接，横向溢出由外层 [foldHeight] 的 clipToBounds 裁掉。纵移：
 * 各层按**自己的**
 * 锚点周上移（两层锚点周可能不是同一行，折叠态跨月换页各自对齐各自的可见行），完全
 * 折叠时锚点周成为唯一可见行；底部预留的 DotZone 让末行/单行的待办小点不被裁掉。
 */
private fun Modifier.monthLayer(
    fold: DayCalendarFoldState,
    swap: State<MonthSwap?>,
    isTarget: Boolean,
    weekIndex: Int,
): Modifier = graphicsLayer {
    val s = swap.value
    translationX = when {
        s == null -> 0f
        isTarget -> s.direction * size.width * (1f - s.progress.value)
        else -> -s.direction * size.width * s.progress.value
    }
    translationY = -(RowStep * weekIndex).toPx() * fold.progress
}

/**
 * 折叠外框：上报高度在 [expanded]/[collapsed] 之间随折叠进度插值，并把内容裁切到这个
 * 高度内。裁切必须发生在测量**外层**（本函数内部的 clipToBounds 先于 layout 挂链）——
 * 它裁的是动画中的外框高度；内容则按自然高度测量（内层约束放开），只显示不挤压。
 * 若把裁切挂在调用侧 layout 之后（内层），它裁到的会是内容的自然高度，滑出的行会
 * 溢出外框、叠到底栏上。折叠插值的展开端点先随月切换进度从旧月高插到目标月高
 * （[MonthSwap] 在途时，5/6 行翻页底栏平滑升降、不跳变），两个进度都在 measure 块内
 * 读取——动画/拖拽每帧只触发重测，不走重组。
 */
private fun Modifier.foldHeight(
    fold: DayCalendarFoldState,
    swap: State<MonthSwap?>,
    expanded: Dp,
    collapsed: Dp,
): Modifier = clipToBounds().layout { measurable, constraints ->
    val s = swap.value
    val expandedNow = if (s != null) lerp(s.fromMonthHeight, expanded, s.progress.value) else expanded
    val height = lerp(expandedNow, collapsed, fold.progress).roundToPx()
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
private val RowSpacing = 8.dp
private val RowStep = BallSize + RowSpacing
/** 星期表头与日历网格的间距，独立于行距 [RowSpacing]，可单独调 */
private val HeaderGridGap = 6.dp
private val DotSize = 3.dp
private val DotGap = 2.dp
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
/** 「回到今天」文字按钮：触控盒定高、文字居中，字号增大只占盒内空间、不撑动底栏 */
private val BackToTodayHeight = 32.dp
private val BackToTodayShape = RoundedRectangle(6.dp)
private val BackToTodayPadding = 6.dp
private val CardPaddingHorizontal = 8.dp
private val CardPaddingVertical = 4.dp
private val BottomBarGap = 2.dp
/** 折叠甩动吸附的速度阈值（对标 M3 anchoredDraggable 默认值） */
internal val FoldFlingVelocity = 125.dp

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

/** 月历展开·选中非今天（今天淡底 + 「回到今天」按钮，浅色） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun DayCalendarMonthSelectedPreview() = ClassppTheme {
    DayCalendar(
        selectedDate = PreviewToday + 5,
        markedDates = PreviewMarkedDates,
        onBackToToday = {},
        today = PreviewToday,
        modifier = Modifier.padding(12.dp),
    )
}
