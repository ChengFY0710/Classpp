package com.fangyi.classpp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.tooling.preview.Preview
import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.checkInPercent
import com.fangyi.classpp.data.expandRepeatOccurrences
import com.fangyi.classpp.data.isOverdue
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.sortedFor
import com.fangyi.classpp.ui.note.cardTimeText
import com.fangyi.classpp.ui.note.deadlineCardText
import com.fangyi.classpp.ui.note.deadlineColor
import com.fangyi.classpp.ui.note.repeatCardText
import com.fangyi.classpp.ui.theme.ClassppTheme

/**
 * 日程页的待办提醒卡：未完成待办合并一张白卡（DayCardShape），
 * 展示信息与完成动画与待办页 NoteCard 完全一致，四处刻意不同——
 * - 倒计时恒开（不读 [Todo.countdown]，逾期显「逾期N天」红、当天「0天」、1/2 天橙/黄）；
 * - 无侧滑、无长按（进编辑页的入口只在待办页），保留勾选完成与行点击；
 * - 多条待办同卡，行上下 [DayAlertRowPaddingVertical] 依次叠加；
 * - 行按压高亮取直角矩形（NoteCard 传 [RectangleShape]）：行与行之间不露圆角缺口，
 *   首尾行的外缘圆角由外卡 DayCardShape 统一裁出——按下时高亮与整卡轮廓贴合，
 *   不再逐行亮成独立的小圆角胶囊。
 *
 * **截止窗口随锚定日期推算**（[viewDate]）：[showOverdueTodos] 开启时，锚定今天
 * 收「今明后天 + 已逾期」（原口径），锚定其他日期收「[viewDate, viewDate + 2] 窗口 +
 * 全部已逾期」（截止早于查看日的倒计时显示「逾期N天」，欠着的到哪天都要还）；关闭时
 * 全视图都不收相对查看日已逾期（截止早于查看日）的待办，只收查看日起 2 天内截止的
 * （查看日当天未逾期的照常显示）。倒计时（含取色）同锚推算——看哪天就显示「从那天起
 * 还剩几天/逾期几天」；标题逾期标红与倒计时同源（截止早于锚定日期，或今天卡上当天已过点）。
 * 右侧截止文案：今天卡取相对词（按真实今天），非今天卡取绝对日期（详情浮层同款「确定
 * 日期」口径）——相对词按真实今天算，会与随日期推算的倒计时错位。
 *
 * 排序按截止早的在前：逾期（按截止日升序）→ 0 天 → 1 天 → 2 天，同日按截止时刻，
 * 无截止沉底；无候选待办时整卡经 Expandable 折叠不占位（含与上方卡片 10dp 的间距）。
 *
 * 行镜、行进出场、完成回显与停一拍落库、行点击绑定等提醒卡通用行为由共享外壳
 * [DayAlertCard] 负责，本组件只定义候选与行内容。不在本卡截止窗口内的全天任务由
 * [DayTaskAlldayAlert] 按查看日展示，二者不重复。
 *
 * @param todos 活跃待办（仓库原样；删除由仓库分流，不在流里）
 * @param today 今天（跨零点自动跳天的 [com.fangyi.classpp.ui.LocalToday]）
 * @param viewDate 卡片锚定日期：截止窗口按它推算（截止 ≤ viewDate + 2 天），默认 = 今天
 * @param showOverdueTodos 是否显示相对查看日已逾期的待办（个性化「日程」开关）：开启 =
 *   原口径（今天收今明后天+逾期，其他日期欠着的到哪天都要还）；关闭 = 任何日期都只收
 *   查看日起 2 天内截止的，查看日当天未逾期的照常显示。默认 true 保留既有预览/旧调用
 *   口径，应用内默认关闭
 * @param nowMinute 当天分钟数（State，逾期判定用，宿主对表驱动）：只在本卡锚定今天时
 *   由行内容读取——非今天卡不订阅对表，30s 一跳不会重组横滑三页里的邻页
 * @param tagColors 标签配色表（标签名 → 颜色，同待办页口径）
 * @param onComplete 勾选完成回调：组件只做回显与停一拍，落库路由（分期/整条）在宿主
 * @param onTodoClick 行点击回调（传分期副本）：宿主唤起待办详情；null = 行不可点
 */
@Composable
fun DayTodoAlert(
    todos: List<Todo>,
    today: IsoDate,
    viewDate: IsoDate = today,
    nowMinute: State<Int>,
    tagColors: Map<String, Color>,
    onComplete: (Todo) -> Unit,
    modifier: Modifier = Modifier,
    onTodoClick: ((Todo) -> Unit)? = null,
    showOverdueTodos: Boolean = true,
) {
    // 候选集：重复待办先按锚定日期展开逐期（分期副本自带逐期折算的截止与完成态），再过滤
    // 排序。截止窗口随锚定日期推算：
    // - 开关开：今天收截止 ≤ 今天 + 2（含逾期，原口径）；非今天收 [那天, 那天 + 2]
    //   窗口 + 全部已逾期（截止早于查看日的按锚定日显示「逾期N天」）；
    // - 开关关：只收相对查看日未逾期的，即查看日起 2 天内截止的（查看日当天的照常显示）。
    // 窗口只看日期：截止时刻已过的仍是「0 天」组，只是标题转逾期红（isOverdue），
    // 与待办页口径一致
    val candidates = remember(todos, today, viewDate, showOverdueTodos) {
        todos
            .expandRepeatOccurrences(viewDate)
            .filter { it.isDayTodoAlertCandidate(today, viewDate, showOverdueTodos) }
            .sortedFor(TodoSort.Deadline)
    }
    DayAlertCard(
        candidates = candidates,
        onComplete = onComplete,
        modifier = modifier,
        onTodoClick = onTodoClick,
    ) { todo, completed, onCheckedChange, onClick ->
        DayTodoAlertRowContent(
            todo = todo,
            today = today,
            viewDate = viewDate,
            nowMinute = nowMinute,
            completed = completed,
            tagColors = tagColors,
            onCheckedChange = onCheckedChange,
            onClick = onClick,
        )
    }
}

/**
 * 本卡的候选口径（[DayTaskAlldayAlert] 据此让位，确保同一任务不在两张卡重复）：未完成、
 * 有截止日期，且落在截止窗口内——截止 ≤ [viewDate] + 2 天（窗口随查看日推算）。[showOverdueTodos]
 * 开启时保留原口径：相对查看日已逾期（截止早于查看日，倒计时显示「逾期N天」）的待办
 * 始终在场，看今天时截止早于今天的也一并显示；关闭时全视图都不收相对查看日已逾期的，
 * 只收 [viewDate, viewDate + 2] 内截止的（查看日当天未逾期的照常显示）。
 */
internal fun Todo.isDayTodoAlertCandidate(
    today: IsoDate,
    viewDate: IsoDate,
    showOverdueTodos: Boolean = true,
): Boolean {
    if (completed) return false
    val deadline = deadlineDate ?: return false
    // 相对锚定日还剩几天：负数 = 截止早于查看日，本卡按「逾期N天」渲染
    val daysLeft = deadline - viewDate
    if (!showOverdueTodos) {
        // 关闭：只收查看日起两天内截止的——查看日当天（含 0 天）未逾期的照常显示，
        // 截止早于查看日的（相对查看日已逾期）不上卡
        return daysLeft in 0L..2L
    }
    // 看今天：截止早于今天的逾期也落在两天的余量里，daysLeft ≤ 2 即原口径
    if (viewDate == today) return daysLeft <= 2L
    // 原口径：截止日在真实今天之前（欠着的到哪天都要还）或落在窗口内
    return daysLeft <= 2L || deadline.epochDay < today.epochDay
}

/**
 * 单条待办行：[NoteCard] 的提醒卡接线——展示信息与完成动画与待办页逐位同款
 * （文案格式化与配色复用待办页的 internal 取值），倒计时恒开、无侧滑，行内距取合并卡的
 * [DayAlertRowPaddingVertical]，按压高亮取直角（shape 传 RectangleShape，外缘圆角由外卡裁出）；
 * [onClick] 接宿主唤起待办详情（null = 行不可点）。
 */
@Composable
private fun DayTodoAlertRowContent(
    todo: Todo,
    today: IsoDate,
    viewDate: IsoDate,
    nowMinute: State<Int>,
    completed: Boolean,
    tagColors: Map<String, Color>,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
) {
    // 倒计时随锚定日期推算（与截止窗口同一锚点）：看哪天就显示「从那天起还剩几天」，
    // 取色与数字同一份天数（阈值表见 deadlineColor(daysLeft)）；今天即原口径（LocalToday）
    val countdownDays = todo.deadlineDate?.let { it - viewDate }
    NoteCard(
        title = todo.name,
        time = todo.cardTimeText(),
        // 截止文案：今天卡取相对词（今天/明天，按真实今天）；非今天卡取绝对日期——
        // 相对词按真实今天算，会与随查看日期推算的倒计时错位
        deadline = todo.deadlineCardText(relativeDate = viewDate == today),
        deadlineColor = todo.deadlineColor(daysLeft = countdownDays),
        repeat = todo.repeatCardText(),
        // 倒计时恒开（日程页提醒卡口径，不读待办页的开关）：传值即渲染「N天/逾期N天」
        countdownDays = countdownDays,
        // 打卡进度：非打卡待办为 null（行保持原形态）；打卡时左区显示「XX%」并让位倒计时
        checkInPercent = todo.checkInPercent(),
        tags = todo.tags,
        tagColors = tagColors,
        urgency = todo.urgency,
        completed = completed,
        // 标题逾期标红与倒计时的「逾期N天」同源：截止早于锚定日期即标红；今天卡额外按
        // 真实时刻判定当天已过点的（isOverdue）。对表 State 只在这一支读取：非今天卡
        // 短路在前，不建立对表订阅，30s 一跳不会波及横滑轨里的邻页
        overdue = countdownDays != null &&
            (countdownDays < 0L ||
                (viewDate == today && todo.isOverdue(today, nowMinute.value))),
        onCheckedChange = onCheckedChange,
        onClick = onClick,
        verticalPadding = DayAlertRowPaddingVertical,
        // 行按压高亮取直角：行与行之间不露圆角缺口，外缘圆角由外卡 DayCardShape 统一裁出
        shape = RectangleShape,
    )
}

@Preview(showBackground = true, widthDp = 412, name = "待办提醒卡（逾期/0/1/2天多行）")
@Composable
private fun DayTodoAlertPreview() {
    ClassppTheme {
        DayTodoAlert(
            todos = listOf(
                Todo(
                    id = "overdue",
                    name = "户口乔迁材料报送",
                    dates = listOf(IsoDate.of(2026, 9, 27)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 12 * 60 + 30,
                    deadlineDate = IsoDate.of(2026, 9, 27),
                    deadlineMinute = 6 * 60,
                    tags = listOf("待办标签"),
                ),
                Todo(
                    id = "today",
                    name = "给绿萝浇水",
                    dates = listOf(IsoDate.of(2026, 10, 25)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 18 * 60,
                    deadlineDate = IsoDate.of(2026, 10, 25),
                    deadlineMinute = 21 * 60,
                ),
                Todo(
                    id = "one-day",
                    name = "交实验报告",
                    dates = listOf(IsoDate.of(2026, 10, 26)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 8 * 60,
                    deadlineDate = IsoDate.of(2026, 10, 26),
                    deadlineMinute = 9 * 60 + 40,
                ),
                Todo(
                    id = "two-days",
                    name = "户口乔迁材料报送",
                    dates = listOf(IsoDate.of(2026, 10, 27)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 12 * 60 + 30,
                    deadlineDate = IsoDate.of(2026, 10, 27),
                    deadlineMinute = 6 * 60,
                    tags = listOf("待办标签"),
                ),
            ),
            today = IsoDate.of(2026, 10, 25),
            nowMinute = remember { mutableIntStateOf(12 * 60 + 30) },
            tagColors = emptyMap(),
            onComplete = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 412, name = "非今天（截止窗口按 viewDate 推算）")
@Composable
private fun DayTodoAlertViewDatePreview() {
    ClassppTheme {
        DayTodoAlert(
            todos = listOf(
                // 截止 = viewDate 当天：上卡，倒计时「0 天」（红）
                Todo(
                    id = "due-that-day",
                    name = "交实验报告",
                    dates = listOf(IsoDate.of(2026, 10, 27)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 8 * 60,
                    deadlineDate = IsoDate.of(2026, 10, 27),
                    deadlineMinute = 9 * 60 + 40,
                ),
                // 截止 = viewDate + 2（窗口边缘）：上卡，倒计时「2 天」（黄）
                Todo(
                    id = "due-window-edge",
                    name = "户口乔迁材料报送",
                    dates = listOf(IsoDate.of(2026, 10, 29)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 12 * 60 + 30,
                    deadlineDate = IsoDate.of(2026, 10, 29),
                    deadlineMinute = 6 * 60,
                    tags = listOf("待办标签"),
                ),
                // 已逾期（截止早于真实今天）：非今天也上卡——拖欠始终在场；倒计时按查看日
                // 推算（10/27 看 = 「逾期3天」红）
                Todo(
                    id = "overdue-before-view",
                    name = "交接送卡申请",
                    dates = listOf(IsoDate.of(2026, 10, 24)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 9 * 60,
                    deadlineDate = IsoDate.of(2026, 10, 24),
                    deadlineMinute = 8 * 60,
                ),
                // 截止 = viewDate + 9（> 2 天）：不上卡
                Todo(
                    id = "too-far",
                    name = "预约体检",
                    dates = listOf(IsoDate.of(2026, 11, 5)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 10 * 60,
                    deadlineDate = IsoDate.of(2026, 11, 5),
                    deadlineMinute = 8 * 60,
                ),
                // 无截止：窗口口径不上卡（提醒卡只收有截止的待办）
                Todo(
                    id = "no-deadline",
                    name = "给绿萝浇水",
                    dates = listOf(IsoDate.of(2026, 10, 27)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 18 * 60,
                ),
            ),
            today = IsoDate.of(2026, 10, 25),
            viewDate = IsoDate.of(2026, 10, 27),
            nowMinute = remember { mutableIntStateOf(12 * 60 + 30) },
            tagColors = emptyMap(),
            onComplete = {},
        )
    }
}
