package com.fangyi.classpp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.expandRepeatOccurrences
import com.fangyi.classpp.data.isOverdue
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.sortedFor
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.note.cardTimeText
import com.fangyi.classpp.ui.note.deadlineCardText
import com.fangyi.classpp.ui.note.deadlineColor
import com.fangyi.classpp.ui.note.deadlineDaysLeft
import com.fangyi.classpp.ui.note.repeatCardText
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.DayCardShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 待办提醒卡与上方上课提醒卡（DayCourseAlert）的间距 */
private val TodoAlertTopSpacing = 10.dp

/** 合并卡内每条待办行的上下内距：比 NoteCard 整卡的 14dp 收紧（多条同卡的设计稿口径） */
private val RowPaddingVertical = 12.dp

/**
 * 日程页的待办提醒卡：未完成待办合并一张白卡（DayCardShape），
 * 展示信息与完成动画与待办页 NoteCard 完全一致，四处刻意不同——
 * - 倒计时恒开（不读 [Todo.countdown]，逾期显「逾期N天」红、当天「0天」、1/2 天橙/黄）；
 * - 无侧滑、无长按（进编辑页的入口只在待办页），保留勾选完成与行点击；
 * - 多条待办同卡，行上下 [RowPaddingVertical] 依次叠加；
 * - 行按压高亮取直角矩形（NoteCard 传 [RectangleShape]）：行与行之间不露圆角缺口，
 *   首尾行的外缘圆角由外卡 DayCardShape 统一裁出——按下时高亮与整卡轮廓贴合，
 *   不再逐行亮成独立的小圆角胶囊。
 *
 * **截止窗口随锚定日期推算**（[viewDate]）：锚定今天（默认）收「今明后天 + 已逾期」的
 * 未完成待办（原口径）；锚定其他日期收「[viewDate, viewDate + 2] 窗口 + 全部已逾期」——
 * 窗口随查看日期整体前移（临近的天接着显示，过头的自动隐去），而已逾期的拖欠始终在场
 * （欠着的到哪天都要还）。倒计时（含取色）同锚推算——看哪天就显示「从那天起还剩几天/
 * 逾期几天」；标题逾期标红与倒计时同源（截止早于锚定日期，或今天卡上当天已过点）。
 * 右侧截止文案：今天卡取相对词（按真实今天），非今天卡取绝对日期（详情浮层同款「确定
 * 日期」口径）——相对词按真实今天算，会与随日期推算的倒计时错位。
 *
 * 排序按截止早的在前：逾期（按截止日升序）→ 0 天 → 1 天 → 2 天，同日按截止时刻，
 * 无截止沉底；无候选待办时整卡经 [Expandable] 折叠不占位（含与上方卡片 10dp 的间距）。
 *
 * 行的进出场成对：候选集新增的行先隐藏入列，下一拍起播入场——高度自上缘窗帘式拉开
 * （下方行实时让位）+ 内容淡入，取待办页卡片进场的同款 token（[Motion.TodoCardEnterMillis]，
 * 吃 [Motion.TodoCardEnterDelayMillis] 的让位拍，与退场交叉时先腾位、再现身）；开场首帧
 * 已在场的行不起入场（[rows] 初值直接取候选全集）。退场见下方完成时序。
 *
 * 完成时序与待办页同拍：点勾当帧回显（[completed] 覆盖仓库值，划线/倒计时退场当帧起播），
 * 停一拍（倒计时恒开取 [Motion.TodoCountdownCompleteSettleMillis]）后经 [onComplete]
 * 交宿主落库；数据回流该行退出候选集 → 行上滑渐隐（[Motion.TodoCardExitMillis]）；最后一行
 * 退场结束后 expanded 翻 false，整卡走 [Expandable] 的折叠动画收起。
 *
 * 行点击经 [onTodoClick] 交宿主唤起待办详情浮层：传的是**分期副本**（重复待办已展开），
 * 宿主取其 id 与 `dates.first()`（分期日期）定位原始待办 + 逐期上下文；null（默认）行不可点。
 *
 * @param todos 活跃待办（仓库原样；删除由仓库分流，不在流里）
 * @param today 今天（跨零点自动跳天的 [com.fangyi.classpp.ui.LocalToday]）
 * @param viewDate 卡片锚定日期：截止窗口按它推算（截止 ≤ viewDate + 2 天），默认 = 今天
 * @param nowMinute 当天分钟数（逾期判定用，宿主对表驱动）
 * @param tagColors 标签配色表（标签名 → 颜色，同待办页口径）
 * @param onComplete 勾选完成回调：组件只做回显与停一拍，落库路由（分期/整条）在宿主
 * @param onTodoClick 行点击回调（传分期副本）：宿主唤起待办详情；null = 行不可点
 */
@Composable
fun DayTodoAlert(
    todos: List<Todo>,
    today: IsoDate,
    viewDate: IsoDate = today,
    nowMinute: Int,
    tagColors: Map<String, Color>,
    onComplete: (Todo) -> Unit,
    modifier: Modifier = Modifier,
    onTodoClick: ((Todo) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // 候选集：重复待办先按锚定日期展开逐期（分期副本自带逐期折算的截止与完成态），再过滤
    // 排序。截止窗口随锚定日期推算，已逾期的拖欠始终在场：
    // - 今天：截止 ≤ 今天 + 2（含逾期），原口径；
    // - 非今天：[那天, 那天 + 2] 的窗口 + 全部已逾期（截止早于真实今天的未完成待办）——
    //   窗口随查看日期整体前移（临近的天接着显示，过头的自动隐去），欠着的到哪天都要还。
    // 窗口只看日期：截止时刻已过的仍是「0 天」组，只是标题转逾期红（isOverdue），
    // 与待办页口径一致
    val candidates = remember(todos, today, viewDate) {
        todos
            .expandRepeatOccurrences(viewDate)
            .filter { todo ->
                if (todo.completed) return@filter false
                val deadline = todo.deadlineDate ?: return@filter false
                deadline - viewDate <= 2L || deadline.epochDay < today.epochDay
            }
            .sortedFor(TodoSort.Deadline)
    }
    // 展示行 = 候选集的延迟卸载镜像：候选集里消失的行先播上滑渐隐，退完才移出。
    // 初值直接取首帧候选全集——已有多条待办时开场不起展开动画，从无到有才播
    var rows by remember {
        mutableStateOf(candidates.map { DayTodoAlertRow(it.alertRowKey(), it, visible = true) })
    }
    // 完成回显（行 key → 目标完成态，同 TodoScreen 的 completionEcho 口径）：
    // 仓库「落盘成功才发布」有几十毫秒死区，回显让完成动画当帧起播；行退场移除时清理
    val echo = remember { mutableStateMapOf<String, Boolean>() }
    // 候选集变化时归并（赋值结构同值不触发重组，只有候选集/行状态真变才落到下一帧）
    rows = reconcileRows(rows, candidates)

    Expandable(expanded = rows.isNotEmpty(), modifier = modifier) {
        // 顶部间距放进折叠内容：收起时间距一并归零，列表里不留空隙
        Column(
            modifier = Modifier
                .padding(top = TodoAlertTopSpacing)
                .clip(DayCardShape)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            rows.forEach { row ->
                key(row.key) {
                    AnimatedVisibility(
                        visible = row.visible,
                        // 入场 = 整卡 Expandable 的窗帘式揭示（高度自上缘拉开、兄弟项实时
                        // 让位，内容淡入），时长与让位拍取待办页卡片进场的同款 token——
                        // 与退场交叉时（切日期窗口滑动）先腾位、再现身
                        enter = expandVertically(
                            animationSpec = tween(
                                durationMillis = Motion.TodoCardEnterMillis,
                                delayMillis = Motion.TodoCardEnterDelayMillis,
                                easing = Motion.Decelerate,
                            ),
                            expandFrom = Alignment.Top,
                        ) + fadeIn(
                            animationSpec = tween(
                                durationMillis = Motion.TodoCardEnterMillis,
                                delayMillis = Motion.TodoCardEnterDelayMillis,
                                easing = Motion.Decelerate,
                            ),
                        ),
                        exit = slideOutVertically(
                            targetOffsetY = { -it },
                            animationSpec = tween(Motion.TodoCardExitMillis, easing = Motion.Accelerate),
                        ) + fadeOut(tween(Motion.TodoCardExitMillis, easing = Motion.Accelerate)) +
                            shrinkVertically(
                                animationSpec = tween(Motion.TodoCardExitMillis, easing = Motion.Accelerate),
                                shrinkTowards = Alignment.Top,
                            ),
                    ) {
                        DayTodoAlertRowContent(
                            todo = row.todo,
                            today = today,
                            viewDate = viewDate,
                            nowMinute = nowMinute,
                            completed = echo[row.key] == true,
                            tagColors = tagColors,
                            onCheckedChange = { checked ->
                                if (checked && echo[row.key] != true) {
                                    echo[row.key] = true
                                    scope.launch {
                                        delay(Motion.TodoCountdownCompleteSettleMillis.toLong())
                                        onComplete(row.todo)
                                    }
                                }
                            },
                            onClick = onTodoClick?.let { callback -> { callback(row.todo) } },
                        )
                    }
                    // 入场起播：新行先以 visible=false 隐藏入列（开场首帧的候选全集由
                    // rows 初值直接可见、不走这里），组合后翻回 true——AnimatedVisibility
                    // 从隐藏态起播上面的入场动画，切日期新增的待办不再凭空出现
                    LaunchedEffect(row.key) {
                        if (!row.visible) {
                            rows = rows.map {
                                if (it.key == row.key) it.copy(visible = true) else it
                            }
                        }
                    }
                    // 退场计时：visible 翻 false 起等退场播完再移出行列表并清回显——
                    // 最后一行移除后 expanded 翻 false，整卡折叠。visible 若中途翻回
                    // true（待办页取消完成）效果重启、不再移除
                    LaunchedEffect(row.visible) {
                        if (!row.visible) {
                            delay(Motion.TodoCardExitMillis.toLong())
                            rows = rows.filterNot { it.key == row.key }
                            echo.remove(row.key)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 提醒行：候选待办的展示镜像，[visible] = false 时隐藏或播上滑渐隐退场——新行入场前
 * 先以 false 入列、下一拍翻 true 起播入场（见 reconcileRows 与行内起播 effect），
 * 候选集里消失的行置 false 退场、播完由行内计时移除
 */
private data class DayTodoAlertRow(
    val key: String,
    val todo: Todo,
    val visible: Boolean,
)

/** 行 key：待办 id + 分期日期段（同 TodoScreen 分期卡 key 口径——同一系列的多条拖欠期同屏互不串位） */
private fun Todo.alertRowKey(): String =
    id + "#" + (dates.firstOrNull()?.epochDay?.toString() ?: "")

/**
 * 展示行与候选集归并：仍在线的行刷新数据；候选集里消失的行置 visible=false 启动退场
 * （保留在场等动画播完，移除由行内计时处理）；新出现的行先以 visible=false 隐藏入列、
 * 由行内起播 effect 下一拍翻回 true 播入场（直接可见会凭空出现；开场首帧的全集走
 * [rows] 的初值、不经这里，不起动画），入列后全列表按截止日 + 时刻重排（稳定排序，
 * 退场中的行也按其截止归位）。幂等——输入不变时输出结构同值。
 */
private fun reconcileRows(
    rows: List<DayTodoAlertRow>,
    candidates: List<Todo>,
): List<DayTodoAlertRow> {
    val pending = LinkedHashMap<String, Todo>()
    candidates.forEach { pending[it.alertRowKey()] = it }
    val merged = mutableListOf<DayTodoAlertRow>()
    for (row in rows) {
        val candidate = pending.remove(row.key)
        merged += when {
            candidate != null -> row.copy(todo = candidate, visible = true)
            row.visible -> row.copy(visible = false)
            else -> row
        }
    }
    pending.values.forEach { merged += DayTodoAlertRow(it.alertRowKey(), it, visible = false) }
    return merged.sortedWith(
        compareBy(
            { it.todo.deadlineDate?.epochDay ?: Long.MAX_VALUE },
            { it.todo.deadlineMinute ?: Int.MAX_VALUE },
        ),
    )
}

/**
 * 单条待办行：[NoteCard] 的提醒卡接线——展示信息与完成动画与待办页逐位同款
 * （文案格式化与配色复用待办页的 internal 取值），倒计时恒开、无侧滑，行内距取合并卡的
 * [RowPaddingVertical]，按压高亮取直角（shape 传 RectangleShape，外缘圆角由外卡裁出）；
 * [onClick] 接宿主唤起待办详情（null = 行不可点）。
 */
@Composable
private fun DayTodoAlertRowContent(
    todo: Todo,
    today: IsoDate,
    viewDate: IsoDate,
    nowMinute: Int,
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
        tags = todo.tags,
        tagColors = tagColors,
        urgency = todo.urgency,
        completed = completed,
        // 标题逾期标红与倒计时的「逾期N天」同源：截止早于锚定日期即标红；今天卡额外按
        // 真实时刻判定当天已过点的（isOverdue）
        overdue = countdownDays != null &&
            (countdownDays < 0L || (viewDate == today && todo.isOverdue(today, nowMinute))),
        onCheckedChange = onCheckedChange,
        onClick = onClick,
        verticalPadding = RowPaddingVertical,
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
            nowMinute = 12 * 60 + 30,
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
            nowMinute = 12 * 60 + 30,
            tagColors = emptyMap(),
            onComplete = {},
        )
    }
}
