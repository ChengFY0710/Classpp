package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.expandRepeatOccurrences
import com.fangyi.classpp.data.isOverdue
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.GradientTintedIcon
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCardwithIcon
import com.fangyi.classpp.ui.components.SettingsCardwithIconItem
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetCardPadding
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowShape
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import kotlin.math.min

/**
 * 待办概览浮层：顶栏展开动作行「概览」进入（纯展示，无确认动作）。
 *
 * 设计重心是情绪价值，其次是提醒价值：
 * 1. 今日收工卡：环形进度 + 收工文案（今天完成 N 件，还差 N 件就收工）；
 * 2. 三统计卡：本周完成 / 连续天数 / 已逾期（>0 用 error，0 降级次级色）；
 * 3. 本周节奏：周一至周日 7 列，有完成的日期竖柱（urgentGreen）、今天列 primaryContainer 底；
 * 4. 需要留意：只列计数 > 0 的已逾期 / 今天截止 / 未来 7 天，行直接用 SettingsCardwithIcon
 *    渲染（纯展示，行点击为空操作）；三项全 0 时给一张绿勾提示卡；
 * 5. 全部待办：已完成 / 未完成 / 已逾期三列数字 + 三段分布条 + 总数；
 * 6. 最近完成：近 7 天每条待办取最近一次完成，倒序最多 3 条。
 *
 * **统计口径分两层**（关键）：
 * - 情绪指标（本周完成 / 连续天数 / 最近完成）按「完成记录」统计：非重复待办用
 *   [Todo.completedAtMillis]（本地时区落天），重复待办用 [Todo.completedOccurrences]
 *   （只取 ≤ 今天）。同一条重复待办一周内完成多期会贡献多个「完成的事」，
 *   `completedAtMillis` 缺失的旧数据不计入这三项（但仍计入分布）。
 * - 分布与提醒按「列表展开口径」统计：[List.expandRepeatOccurrences] 的虚拟卡片，
 *   与页面四个分组、逾期判定同源；合并重复（默认）只算一张滚动卡。
 * - 今日收工环形是两层混合：分子 [TodoOverviewStats.todayCompleted] 走完成记录，
 *   分母 [TodoOverviewStats.todayTotal] = 分子 + 今天截止未完成 + 逾期未完成 +
 *   待办时间为今天的未完成卡（列表展开口径）——清完所有欠账才算收工。
 *   同一张卡只归一个口径（逾期 > 今天截止 > 待办时间为今天），不重复计数。
 *
 * 颜色只取主题语义色（完成 urgentGreen、逾期 error、其余 primary、次级 secondaryText），
 * 字号全部走 [ClassppTextTokens]，卡片/间距沿用浮层既有 token。顶栏只有右侧「关闭」胶囊，
 * 返回键/遮罩/下拉关闭都只请求关闭，播完退场动画才由宿主卸载（两段式）。
 */
@Composable
internal fun TodoOverviewSheet(
    visible: Boolean,
    todos: List<Todo>,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
) {
    val today = LocalToday
    // 概览是短驻留浮层：分钟口径只在进入/数据变化时取一次，跨分钟不倒计时刷新
    val stats = remember(todos, today) {
        buildTodoOverviewStats(todos, today, currentMinuteOfDay())
    }

    BackHandler(enabled = visible, onBack = onDismiss)

    OverlaySheet(
        title = stringResource(R.string.todo_overview_title),
        // 纯展示浮层：不渲染左侧确认胶囊，只有右侧关闭
        confirmLabel = null,
        rightAction = SheetTopAction(
            label = stringResource(R.string.detail_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        // 纯展示列表：没有输入框，键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            if (stats.total == 0) {
                OverviewEmptyCard()
            } else {
                // 今日收工卡与三统计卡是一组视觉紧凑的顶部块：组内 10dp（卡↔卡），
                // 与下方区块之间仍走浮层顶层 SheetSectionSpacingBetween（12dp）
                Column(verticalArrangement = Arrangement.spacedBy(CardSectionSpacing)) {
                    OverviewTodayCard(stats)
                    OverviewStatCardsRow(stats)
                }
                CardSection(title = stringResource(R.string.todo_overview_week_section)) {
                    OverviewWeekCard(stats, today)
                }
                CardSection(title = stringResource(R.string.todo_overview_attention)) {
                    OverviewAttentionCard(stats)
                }
                CardSection(title = stringResource(R.string.todo_overview_all)) {
                    OverviewDistributionCard(stats)
                }
                if (stats.recent.isNotEmpty()) {
                    CardSection(title = stringResource(R.string.todo_overview_recent)) {
                        OverviewRecentCard(stats.recent, today)
                    }
                }
            }
        }
    }
}

// ---------- 统计口径 ----------

/** 一周中某天的完成记录数：日期 + 当天完成的事数（重复待办的每一期各记一次） */
internal data class OverviewDay(val date: IsoDate, val count: Int)

/** 最近完成的一条：待办标题 + 最近一次完成日期 */
internal data class OverviewRecent(val title: String, val day: IsoDate)

/**
 * 概览全部派生数据：分布/提醒走列表展开口径，周/连续/最近走完成记录口径。
 *
 * [todayTotal] 是「今日收工口径」的分母：今天完成 + 今天截止未完成 + 逾期未完成 +
 * 待办时间为今天的未完成卡，清完所有欠账才算收工；[todayCompleted] 是分子，
 * 进度 = 分子 / 分母。
 */
internal data class TodoOverviewStats(
    val total: Int,
    val completed: Int,
    val active: Int,
    val overdue: Int,
    val weekDays: List<OverviewDay>,
    val weekCompleted: Int,
    val weekActiveDays: Int,
    val todayCompleted: Int,
    val todayTotal: Int,
    val streak: Int,
    val dueToday: Int,
    val dueNext7: Int,
    val scheduledToday: Int,
    val recent: List<OverviewRecent>,
)

/** 连续完成上限：防御病态数据（每天重复的历史序列）导致的长循环 */
private const val MaxStreakDays = 3660

/** 最近完成窗口：今天与往前 6 天 */
private const val RecentWindowDays = 7

/** 最近完成最多展示条数 */
private const val RecentLimit = 3

/** 最近完成候选：标题 + 完成日 + 完成时刻（时刻用于同日排序，重复待办为 0） */
private data class RecentCandidate(val title: String, val day: IsoDate, val millis: Long)

/**
 * 概览派生数据，纯函数（单测注入固定 today/nowMinute）。
 *
 * 分布/提醒：[List.expandRepeatOccurrences] 的卡片口径——合并重复只出一张滚动卡、
 * 拆分重复逐期出卡，与待办列表四个分组完全一致。
 *
 * 周/连续/最近：完成记录口径。非重复待办只认 `completed && completedAtMillis > 0`
 * 的旧式整条完成；重复待办认 `completedOccurrences`（去重、≤ 今天），两者不混算，
 * 避免旧数据/脏数据重复计数。
 */
internal fun buildTodoOverviewStats(
    todos: List<Todo>,
    today: IsoDate,
    nowMinute: Int,
): TodoOverviewStats {
    val expanded = todos.expandRepeatOccurrences(today)
    val completed = expanded.count { it.completed }
    val overdue = expanded.count { !it.completed && it.isOverdue(today, nowMinute) }
    val active = expanded.size - completed - overdue

    // 完成记录按天落账：dayCounts 给周柱，completedDays 给连续天数
    val dayCounts = mutableMapOf<IsoDate, Int>()
    val completedDays = mutableSetOf<IsoDate>()
    fun record(day: IsoDate) {
        if (day.epochDay > today.epochDay) return
        dayCounts[day] = (dayCounts[day] ?: 0) + 1
        completedDays += day
    }
    for (todo in todos) {
        if (todo.repeat.type == RepeatType.None) {
            val millis = todo.completedAtMillis
            if (todo.completed && millis != null && millis > 0L) record(IsoDate.today(millis))
        } else {
            todo.completedOccurrences.distinct().forEach { record(it) }
        }
    }

    val monday = today.mondayOfWeek()
    val weekDays = (0..6).map { offset ->
        val date = monday + offset
        OverviewDay(date = date, count = dayCounts[date] ?: 0)
    }
    val weekCompleted = weekDays.sumOf { it.count }
    val weekActiveDays = weekDays.count { it.count > 0 }

    // 今天还没完成时从昨天起算：当天没过完不算断签
    val streakAnchor = if (today in completedDays) today else today - 1
    var streak = 0
    var cursor = streakAnchor
    while (streak < MaxStreakDays && cursor in completedDays) {
        streak++
        cursor -= 1
    }

    // 提醒与收工分母：今天截止的已逾期时刻只归逾期行，不重复计数；
    // 待办时间为今天（dates 含今天）且未被截止归口的未完成卡也纳入收工分母
    var dueToday = 0
    var dueNext7 = 0
    var scheduledToday = 0
    for (todo in expanded) {
        if (todo.completed) continue
        if (todo.isOverdue(today, nowMinute)) continue
        val deadline = todo.deadlineDate
        when {
            deadline == today -> dueToday++
            deadline != null && deadline.epochDay in (today.epochDay + 1)..(today.epochDay + 7) -> dueNext7++
        }
        // 截止在今天或更早的卡已按截止归口（今天截止/逾期），dates 不再重复计
        if ((deadline == null || deadline.epochDay > today.epochDay) && today in todo.dates) {
            scheduledToday++
        }
    }

    // 最近完成：每条待办只取最近一次完成，避免天天重复的系列刷屏
    val candidates = mutableListOf<RecentCandidate>()
    for (todo in todos) {
        val candidate = if (todo.repeat.type == RepeatType.None) {
            val millis = todo.completedAtMillis
            if (todo.completed && millis != null && millis > 0L) {
                RecentCandidate(todo.name, IsoDate.today(millis), millis)
            } else {
                null
            }
        } else {
            todo.completedOccurrences
                .filter { it.epochDay <= today.epochDay }
                .maxByOrNull { it.epochDay }
                ?.let { RecentCandidate(todo.name, it, 0L) }
        }
        if (candidate != null && (today - candidate.day) in 0L..(RecentWindowDays - 1).toLong()) {
            candidates += candidate
        }
    }
    val recent = candidates
        .sortedWith(
            compareByDescending<RecentCandidate> { it.day.epochDay }
                .thenByDescending { it.millis },
        )
        .take(RecentLimit)
        .map { OverviewRecent(title = it.title, day = it.day) }

    return TodoOverviewStats(
        total = expanded.size,
        completed = completed,
        active = active,
        overdue = overdue,
        weekDays = weekDays,
        weekCompleted = weekCompleted,
        weekActiveDays = weekActiveDays,
        todayCompleted = dayCounts[today] ?: 0,
        todayTotal = (dayCounts[today] ?: 0) + dueToday + overdue + scheduledToday,
        streak = streak,
        dueToday = dueToday,
        dueNext7 = dueNext7,
        scheduledToday = scheduledToday,
        recent = recent,
    )
}

// ---------- 概览卡片 ----------

/** 今日收工卡：环形进度 + 收工文案；分子/分母口径见 [TodoOverviewStats.todayTotal] */
@Composable
private fun OverviewTodayCard(stats: TodoOverviewStats) {
    SheetCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TodayProgressRing(
                completed = stats.todayCompleted,
                total = stats.todayTotal,
            ) {
                if (stats.todayTotal == 0) {
                    // 全清特例：今天没做也没欠，环心不放 0/0，放绿勾
                    Icon(
                        painter = painterResource(R.drawable.ic_checkmark_circle),
                        contentDescription = null,
                        tint = MaterialTheme.classppColors.urgentGreen,
                        modifier = Modifier.size(TodayRingCenterIconSize),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(
                                R.string.todo_overview_today_fraction,
                                stats.todayCompleted,
                                stats.todayTotal,
                            ),
                            style = ClassppTextTokens.countdownNumber,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(
                            text = stringResource(
                                R.string.todo_overview_today_percent,
                                stats.todayCompleted * 100 / stats.todayTotal,
                            ),
                            style = ClassppTextTokens.metaTextSmall,
                            color = MaterialTheme.classppColors.secondaryText,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    stats.todayTotal == 0 -> stringResource(R.string.todo_overview_today_clear)
                    stats.todayCompleted == stats.todayTotal -> {
                        stringResource(R.string.todo_overview_today_all_done)
                    }
                    else -> stringResource(
                        R.string.todo_overview_today_progress,
                        stats.todayCompleted,
                        stats.todayTotal - stats.todayCompleted,
                    )
                },
                style = ClassppTextTokens.secondaryTextLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 今日完成环形：轨道一圈（onSurfaceCard，同无完成周柱）+ 进度弧（urgentGreen，完成语义色） */
@Composable
private fun TodayProgressRing(
    completed: Int,
    total: Int,
    content: @Composable () -> Unit,
) {
    // 0/0 特例按全清处理画满环；进场从 0 生长（animateFloatAsState 首帧直接落位，没有进场动画）
    val target = if (total <= 0) 1f else (completed.toFloat() / total).coerceIn(0f, 1f)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) {
        progress.animateTo(target, tween(durationMillis = TodayRingGrowMillis))
    }
    val trackColor = MaterialTheme.classppColors.onSurfaceCard
    val progressColor = MaterialTheme.classppColors.urgentGreen
    Box(modifier = Modifier.size(TodayRingSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = TodayRingStroke.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress.value > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.value,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** 三张统计卡：本周完成 / 连续天数 / 已逾期；逾期 >0 用 error，0 降级次级色（同分布卡规则） */
@Composable
private fun OverviewStatCardsRow(stats: TodoOverviewStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CardSectionSpacing),
    ) {
        OverviewStatCard(
            count = stats.weekCompleted,
            label = stringResource(R.string.todo_overview_week_completed),
            countColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        OverviewStatCard(
            count = stats.streak,
            label = stringResource(R.string.todo_overview_streak_short),
            countColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        OverviewStatCard(
            count = stats.overdue,
            label = stringResource(R.string.todo_section_overdue),
            countColor = if (stats.overdue > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.classppColors.secondaryText
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 单张统计小卡：数字 + 标签，纵向居中；颜色由调用方给（0 值降级在调用方处理） */
@Composable
private fun OverviewStatCard(
    count: Int,
    label: String,
    countColor: Color,
    modifier: Modifier = Modifier,
) {
    SheetCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                // statNumber：行高收紧到字号档——默认行盒的下延留白会把「数字+描述」整组顶得偏上
                style = ClassppTextTokens.statNumber,
                color = countColor,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = ClassppTextTokens.metaTextSmall,
                color = MaterialTheme.classppColors.secondaryText,
                maxLines = 1,
            )
        }
    }
}

/** 空态：活跃待办为空时的单卡提示（列表本身已有空态，概览只做轻量承接） */
@Composable
private fun OverviewEmptyCard() {
    SheetCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GradientTintedIcon(
                icon = R.drawable.ic_clipboard_task,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(OverviewEmptyIconSize),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.todo_overview_empty_title),
                style = ClassppTextTokens.emptyTitle,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.todo_overview_empty_hint),
                style = ClassppTextTokens.emptyHint,
                color = MaterialTheme.classppColors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 本周节奏：周一至周日 7 列；有完成的日期竖柱按次数分档，今天整列 primaryContainer 底 */
@Composable
private fun OverviewWeekCard(stats: TodoOverviewStats, today: IsoDate) {
    val labels = stringArrayResource(R.array.weekdays_short)
    SheetCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                stats.weekDays.forEachIndexed { index, day ->
                    WeekDayCell(
                        label = labels.getOrElse(index) { "" },
                        day = day,
                        isToday = day.date == today,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (stats.weekCompleted == 0) {
                    stringResource(R.string.todo_overview_week_empty)
                } else {
                    stringResource(
                        R.string.todo_overview_week_summary,
                        stats.weekCompleted,
                        stats.weekActiveDays,
                    )
                },
                style = ClassppTextTokens.settingsDescription,
                color = MaterialTheme.classppColors.secondaryText,
            )
        }
    }
}

/** 单日列：星期标签 + 完成次数竖柱；今天整列用 primaryContainer 圆角底强调 */
@Composable
private fun WeekDayCell(
    label: String,
    day: OverviewDay,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(WeekCellWidth)
                .clip(RowShape)
                .then(
                    if (isToday) {
                        Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = ClassppTextTokens.navLabel.copy(
                    color = if (isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.classppColors.secondaryText
                    },
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width(WeekBarWidth)
                    .height(WeekBarAreaHeight),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(weekBarHeight(day.count))
                        .clip(PillShape)
                        .background(
                            if (day.count > 0) {
                                MaterialTheme.classppColors.urgentGreen
                            } else {
                                MaterialTheme.classppColors.onSurfaceCard
                            },
                        ),
                )
            }
        }
    }
}

/** 周柱高度：0 次留 3dp 基线，1..4 次每次加 4dp（封顶 4 次避免极端值撑破卡片） */
private fun weekBarHeight(count: Int): Dp =
    if (count <= 0) WeekBarMinHeight else WeekBarMinHeight + WeekBarStep * min(count, 4)

/** 需要留意：只渲染计数 > 0 的行；三项全 0 时给绿勾提示卡 */
@Composable
private fun OverviewAttentionCard(stats: TodoOverviewStats) {
    val items = listOfNotNull(
        if (stats.overdue > 0) {
            SettingsCardwithIconItem(
                icon = R.drawable.ic_checkbox_warning,
                label = stringResource(R.string.todo_section_overdue),
                value = stringResource(R.string.todo_overview_items, stats.overdue),
                // 纯展示浮层：行不承担动作，按压反馈与尾随箭头随组件规格
                onClick = {},
            )
        } else {
            null
        },
        if (stats.dueToday > 0) {
            SettingsCardwithIconItem(
                icon = R.drawable.ic_clock_alarm,
                label = stringResource(R.string.todo_overview_due_today),
                value = stringResource(R.string.todo_overview_items, stats.dueToday),
                onClick = {},
            )
        } else {
            null
        },
        if (stats.dueNext7 > 0) {
            SettingsCardwithIconItem(
                icon = R.drawable.ic_calendar_checkmark,
                label = stringResource(R.string.todo_overview_due_next7),
                value = stringResource(R.string.todo_overview_items, stats.dueNext7),
                onClick = {},
            )
        } else {
            null
        },
    )
    if (items.isEmpty()) {
        SheetCard(modifier = Modifier.fillMaxWidth()) {
            Icon(
                painter = painterResource(R.drawable.ic_checkmark_circle),
                contentDescription = null,
                tint = MaterialTheme.classppColors.urgentGreen,
                modifier = Modifier.size(OverviewRowIconSize),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.todo_overview_none_attention),
                style = ClassppTextTokens.secondaryTextLarge,
                color = MaterialTheme.classppColors.secondaryText,
            )
        }
    } else {
        SettingsCardwithIcon(items = items)
    }
}

/** 全部待办：三列数字 + 三段分布条 + 总数；数字为 0 时降为次级色 */
@Composable
private fun OverviewDistributionCard(stats: TodoOverviewStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SheetCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(SheetCardPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverviewStatColumn(
                count = stats.completed,
                label = stringResource(R.string.todo_section_completed),
                color = MaterialTheme.classppColors.urgentGreen,
                modifier = Modifier.weight(1f),
            )
            OverviewStatColumn(
                count = stats.active,
                label = stringResource(R.string.todo_section_incomplete),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            OverviewStatColumn(
                count = stats.overdue,
                label = stringResource(R.string.todo_section_overdue),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        DistributionBar(
            completed = stats.completed,
            active = stats.active,
            overdue = stats.overdue,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.todo_overview_total, stats.total),
            style = ClassppTextTokens.settingsDescription,
            color = MaterialTheme.classppColors.secondaryText,
        )
    }
}

/** 单列数字 + 分组名：数字走倒计时大号 token，0 时用次级色避免“红色 0”的误读 */
@Composable
private fun OverviewStatColumn(
    count: Int,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = ClassppTextTokens.countdownNumber,
            color = if (count > 0) color else MaterialTheme.classppColors.secondaryText,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = ClassppTextTokens.metaTextSmall,
            color = MaterialTheme.classppColors.secondaryText,
            maxLines = 1,
        )
    }
}

/** 三段分布条：按占比分宽，非零段至少占 2%（同数据管理页用量条），全 0 退化为 outline 空轨道 */
@Composable
private fun DistributionBar(completed: Int, active: Int, overdue: Int) {
    val colors = listOf(
        MaterialTheme.classppColors.urgentGreen,
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.error,
    )
    val weights = segmentWeights(intArrayOf(completed, active, overdue))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DistributionBarHeight)
            .clip(PillShape),
    ) {
        if (weights.all { it <= 0f }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.outline),
            )
        } else {
            weights.forEachIndexed { index, weight ->
                if (weight > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .background(colors[index]),
                    )
                }
            }
        }
    }
}

/**
 * 三段占比 → Row 权重：非零段至少占 [MinSegmentFraction]，再整体归一化；全 0 返回全 0
 * （调用方据此显示空轨道）。纯函数，与数据管理页用量条同口径。
 */
private fun segmentWeights(counts: IntArray): FloatArray {
    val total = counts.sum()
    if (total <= 0) return FloatArray(counts.size)
    val adjusted = counts.map { count ->
        if (count <= 0) 0.0 else {
            (count.toDouble() / total).coerceAtLeast(MinSegmentFraction.toDouble())
        }
    }
    val adjustedSum = adjusted.sum()
    return FloatArray(counts.size) { index -> (adjusted[index] / adjustedSum).toFloat() }
}

/** 最近完成：近 7 天每条待办取最近一次，最多 3 条；与周统计同窗口 */
@Composable
private fun OverviewRecentCard(recent: List<OverviewRecent>, today: IsoDate) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SheetCardShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        recent.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = RowCardPaddingHorizontal,
                        vertical = RecentRowVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark_circle),
                    contentDescription = null,
                    tint = MaterialTheme.classppColors.urgentGreen,
                    modifier = Modifier.size(OverviewRowIconSize),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.classppTextStyles.fieldLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = relativeDayLabel(item.day, today),
                    style = MaterialTheme.classppTextStyles.noteProperty,
                )
            }
        }
    }
}

/** 相对日期：今天/昨天/前天，其余「N 天前」（最近窗口只取 ≤ 今天，diff 恒非负） */
@Composable
private fun relativeDayLabel(day: IsoDate, today: IsoDate): String {
    val diff = (today - day).toInt()
    return when (diff) {
        0 -> stringResource(R.string.date_card_today)
        1 -> stringResource(R.string.date_card_yesterday)
        2 -> stringResource(R.string.date_card_day_before_yesterday)
        else -> stringResource(R.string.todo_overview_days_ago, diff)
    }
}

// ---------- 文件私有几何常量 ----------

/** 今日环外径：128dp，今日收工卡的视觉重心 */
private val TodayRingSize = 128.dp

/** 今日环描边宽：12dp，与分布条同档粗细 */
private val TodayRingStroke = 12.dp

/** 今日环环心绿勾边长：28dp，全清特例使用 */
private val TodayRingCenterIconSize = 28.dp

/** 今日环进场生长动效时长：600ms（短驻留浮层，进场播一次，不循环） */
private const val TodayRingGrowMillis = 600

/** 空态图标边长：与设置页信息头部卡同级（40dp） */
private val OverviewEmptyIconSize = 40.dp

/** 全零绿勾/最近完成行内图标：24dp，对齐 SettingsCardwithIcon 行首图标规格（提醒卡图标已随组件渲染） */
private val OverviewRowIconSize = 24.dp

/** 周列固定宽：7 列在卡片内容宽内居中，今天列底不会被拉成整格（32dp 在间距阶梯内） */
private val WeekCellWidth = 32.dp

/** 周柱宽：10dp，柱区高 24dp，柱底对齐 */
private val WeekBarWidth = 10.dp
private val WeekBarAreaHeight = 24.dp

/** 无完成日的基线高 3dp；每次完成加 4dp，最多按 4 次计 */
private val WeekBarMinHeight = 3.dp
private val WeekBarStep = 4.dp

/** 分布条高度：12dp，介于打卡条 6dp 与用量条 20dp 之间 */
private val DistributionBarHeight = 12.dp

/** 最近完成行纵向内距：12dp（行内紧凑，三行不显得厚重） */
private val RecentRowVerticalPadding = 12.dp

/** 分布条非零段最小占比：2%（同数据管理页用量条） */
private const val MinSegmentFraction = 0.02f

// ---------- 预览 ----------

/** 概览浮层预览：今日完成 2 件 + 逾期 1 + 今天截止 1 + 待办时间为今天（明天截止）1 → 环形 2/5（40%） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 900)
@Composable
private fun TodoOverviewSheetPreview() = ClassppTheme {
    val today = IsoDate.today()
    val now = System.currentTimeMillis()
    TodoOverviewSheet(
        visible = true,
        todos = listOf(
            Todo(
                id = "preview-1",
                name = "高等数学作业",
                dates = listOf(today),
                completed = true,
                completedAtMillis = now,
            ),
            Todo(
                id = "preview-2",
                name = "实验报告",
                dates = listOf(today - 1),
                completed = true,
                completedAtMillis = now - 86_400_000L,
            ),
            Todo(
                id = "preview-3",
                name = "背单词",
                dates = listOf(today - 12),
                repeat = RepeatRule(type = RepeatType.Daily),
                completedOccurrences = listOf(today - 2, today - 1, today),
            ),
            Todo(
                id = "preview-4",
                name = "读书笔记",
                dates = listOf(today),
                deadlineDate = today + 1,
            ),
            Todo(
                id = "preview-5",
                name = "课程论文",
                dates = listOf(today - 5),
                deadlineDate = today - 2,
            ),
            Todo(
                id = "preview-6",
                name = "整理错题本",
                dates = listOf(today),
                // 23:59 截止：预览在几乎所有时刻都走「今天截止」分支而非逾期
                deadlineDate = today,
                deadlineMinute = 23 * 60 + 59,
            ),
        ),
        onDismiss = {},
        onDismissed = {},
    )
}
