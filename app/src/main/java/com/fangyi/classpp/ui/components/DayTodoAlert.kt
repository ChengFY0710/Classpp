package com.fangyi.classpp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
 * 日程页的待办提醒卡：截止在今明后天与逾期的未完成待办合并一张白卡（DayCardShape），
 * 展示信息与完成动画与待办页 NoteCard 完全一致，三处刻意不同——
 * - 倒计时恒开（不读 [Todo.countdown]，逾期显「逾期N天」红、当天「0天」、1/2 天橙/黄）；
 * - 无侧滑、无长按（进编辑页的入口只在待办页），保留勾选完成与行点击；
 * - 多条待办同卡，行上下 [RowPaddingVertical] 依次叠加。
 *
 * 排序按截止早的在前：逾期（按截止日升序）→ 0 天 → 1 天 → 2 天，同日按截止时刻；
 * 无候选待办时整卡经 [Expandable] 折叠不占位（含与上方卡片 10dp 的间距）。
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
 * @param nowMinute 当天分钟数（逾期判定用，宿主对表驱动）
 * @param tagColors 标签配色表（标签名 → 颜色，同待办页口径）
 * @param onComplete 勾选完成回调：组件只做回显与停一拍，落库路由（分期/整条）在宿主
 * @param onTodoClick 行点击回调（传分期副本）：宿主唤起待办详情；null = 行不可点
 */
@Composable
fun DayTodoAlert(
    todos: List<Todo>,
    today: IsoDate,
    nowMinute: Int,
    tagColors: Map<String, Color>,
    onComplete: (Todo) -> Unit,
    modifier: Modifier = Modifier,
    onTodoClick: ((Todo) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // 候选集：重复待办先展开逐期（分期副本自带逐期折算的截止与完成态），再过滤排序。
    // 截止窗口只看日期（deadline - today ≤ 2）：当天时刻已过的仍是「0 天」组，
    // 只是标题转逾期红（isOverdue），与待办页口径一致
    val candidates = remember(todos, today) {
        todos
            .expandRepeatOccurrences(today)
            .filter { todo ->
                if (todo.completed) return@filter false
                val deadline = todo.deadlineDate ?: return@filter false
                deadline - today <= 2L
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

/** 提醒行：候选待办的展示镜像，[visible] = false 时播上滑渐隐退场、播完由行内计时移除 */
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
 * （保留在场等动画播完，移除由行内计时处理）；新出现的行追加后全列表按截止日 + 时刻
 * 重排（稳定排序，退场中的行也按其截止归位）。幂等——输入不变时输出结构同值。
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
    pending.values.forEach { merged += DayTodoAlertRow(it.alertRowKey(), it, visible = true) }
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
 * [RowPaddingVertical]；[onClick] 接宿主唤起待办详情（null = 行不可点）。
 */
@Composable
private fun DayTodoAlertRowContent(
    todo: Todo,
    today: IsoDate,
    nowMinute: Int,
    completed: Boolean,
    tagColors: Map<String, Color>,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
) {
    NoteCard(
        title = todo.name,
        time = todo.cardTimeText(),
        deadline = todo.deadlineCardText(),
        deadlineColor = todo.deadlineColor(),
        repeat = todo.repeatCardText(),
        // 倒计时恒开（日程页提醒卡口径，不读待办页的开关）：传值即渲染「N天/逾期N天」，
        // 取色与右侧截止时间同源（deadlineColor）
        countdownDays = todo.deadlineDaysLeft(),
        tags = todo.tags,
        tagColors = tagColors,
        urgency = todo.urgency,
        completed = completed,
        overdue = todo.isOverdue(today, nowMinute),
        onCheckedChange = onCheckedChange,
        onClick = onClick,
        verticalPadding = RowPaddingVertical,
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
