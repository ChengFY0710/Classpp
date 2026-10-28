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
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.DayCardShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 提醒卡与上方卡片（上课提醒卡或上一张提醒卡）的间距 */
internal val DayAlertTopSpacing = 10.dp

/** 合并卡内每条待办行的上下内距：比 NoteCard 整卡的 14dp 收紧（多条同卡的设计稿口径） */
internal val DayAlertRowPaddingVertical = 12.dp

/** 默认行序：截止日 + 截止时刻升序（近的在前），无截止沉底 */
internal val DayAlertDeadlineRowOrder: Comparator<Todo> = compareBy(
    { it.deadlineDate?.epochDay ?: Long.MAX_VALUE },
    { it.deadlineMinute ?: Int.MAX_VALUE },
)

/**
 * 日程页提醒卡的共享外壳：未完成待办合并一张白卡（DayCardShape），行内容由
 * [rowContent] 各自提供；本组件负责所有「提醒卡」共同的行为——
 * - 顶部 [DayAlertTopSpacing] 放在折叠内容内部：无候选时整卡经 [Expandable] 收起，
 *   间距一并归零，列表里不留空隙；
 * - 行镜（[DayAlertRow]）：候选集里消失的行先播上滑渐隐、退完才移出，新行先隐藏入列
 *   下一拍起播入场，与退场交叉时先腾位、再现身；
 * - 完成时序：点勾当帧经 echo 回显（行内划线/倒计时退场当帧起播），停一拍
 *   （[Motion.TodoCountdownCompleteSettleMillis]）后经 [onComplete] 交宿主落库；
 * - 行点击经 [onTodoClick] 交宿主（传的是分期副本，重复待办已展开），null = 行不可点。
 *
 * 行内距 [DayAlertRowPaddingVertical] 与直角按压高亮（NoteCard 传 RectangleShape）由调用方
 * 的行内容沿用：行与行之间不露圆角缺口，首尾行的外缘圆角由外卡 DayCardShape 统一裁出。
 *
 * @param candidates 已过滤、已排序的候选待办（展示口径由调用方定义）
 * @param onComplete 勾选完成回调：组件只做回显与停一拍，落库路由（分期/整条）在宿主
 * @param onTodoClick 行点击回调（传分期副本）；null = 行不可点
 * @param rowOrder 行镜归并后的行序；候选集变化时退场中的行也按它归位。应与调用方候选
 *   排序口径一致，否则行序会在归并时被重排
 * @param rowContent 单行内容：外壳已把回显完成态、包装好停拍的勾选回调与绑定好待办的
 *   点击回调交给它，行内容只负责画与转发
 */
@Composable
internal fun DayAlertCard(
    candidates: List<Todo>,
    onComplete: (Todo) -> Unit,
    modifier: Modifier = Modifier,
    onTodoClick: ((Todo) -> Unit)? = null,
    rowOrder: Comparator<Todo> = DayAlertDeadlineRowOrder,
    rowContent: @Composable (
        todo: Todo,
        completed: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        onClick: (() -> Unit)?,
    ) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 展示行 = 候选集的延迟卸载镜像：候选集里消失的行先播上滑渐隐，退完才移出。
    // 初值直接取首帧候选全集——已有多条待办时开场不起展开动画，从无到有才播
    var rows by remember {
        mutableStateOf(candidates.map { DayAlertRow(it.dayAlertRowKey(), it, visible = true) })
    }
    // 完成回显（行 key → 目标完成态，同 TodoScreen 的 completionEcho 口径）：
    // 仓库「落盘成功才发布」有几十毫秒死区，回显让完成动画当帧起播；行退场移除时清理
    val echo = remember { mutableStateMapOf<String, Boolean>() }
    // 候选集变化时归并（赋值结构同值不触发重组，只有候选集/行状态真变才落到下一帧）
    rows = reconcileDayAlertRows(rows, candidates, rowOrder)

    Expandable(expanded = rows.isNotEmpty(), modifier = modifier) {
        // 顶部间距放进折叠内容：收起时间距一并归零，列表里不留空隙
        Column(
            modifier = Modifier
                .padding(top = DayAlertTopSpacing)
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
                        rowContent(
                            row.todo,
                            echo[row.key] == true,
                            { checked ->
                                if (checked && echo[row.key] != true) {
                                    echo[row.key] = true
                                    scope.launch {
                                        delay(Motion.TodoCountdownCompleteSettleMillis.toLong())
                                        onComplete(row.todo)
                                    }
                                }
                            },
                            onTodoClick?.let { callback -> { callback(row.todo) } },
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
 * 先以 false 入列、下一拍翻 true 起播入场（见 reconcileDayAlertRows 与行内起播 effect），
 * 候选集里消失的行置 false 退场、播完由行内计时移除
 */
private data class DayAlertRow(
    val key: String,
    val todo: Todo,
    val visible: Boolean,
)

/** 行 key：待办 id + 分期日期段（同 TodoScreen 分期卡 key 口径——同一系列的多条拖欠期同屏互不串位） */
private fun Todo.dayAlertRowKey(): String =
    id + "#" + (dates.firstOrNull()?.epochDay?.toString() ?: "")

/**
 * 展示行与候选集归并：仍在线的行刷新数据；候选集里消失的行置 visible=false 启动退场
 * （保留在场等动画播完，移除由行内计时处理）；新出现的行先以 visible=false 隐藏入列、
 * 由行内起播 effect 下一拍翻回 true 播入场（直接可见会凭空出现；开场首帧的全集走
 * [DayAlertCard] 的 rows 初值、不经这里，不起动画），入列后全列表按 [order] 重排
 * （稳定排序，退场中的行也按其键归位）。幂等——输入不变时输出结构同值。
 */
private fun reconcileDayAlertRows(
    rows: List<DayAlertRow>,
    candidates: List<Todo>,
    order: Comparator<Todo>,
): List<DayAlertRow> {
    val pending = LinkedHashMap<String, Todo>()
    candidates.forEach { pending[it.dayAlertRowKey()] = it }
    val merged = mutableListOf<DayAlertRow>()
    for (row in rows) {
        val candidate = pending.remove(row.key)
        merged += when {
            candidate != null -> row.copy(todo = candidate, visible = true)
            row.visible -> row.copy(visible = false)
            else -> row
        }
    }
    pending.values.forEach { merged += DayAlertRow(it.dayAlertRowKey(), it, visible = false) }
    return merged.sortedWith(compareBy(order) { it.todo })
}
