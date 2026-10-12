package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R
import com.fangyi.classpp.data.isOverdue
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.GroupCard
import com.fangyi.classpp.ui.components.GroupCardItem
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCardwithIcon
import com.fangyi.classpp.ui.components.SettingsCardwithIconItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween

/**
 * 待办分组浮层：待办页顶栏「更多 → 分组」进入，内容分两部分——
 *
 * - **第一部分（状态与留存，无标题 [CardSection]）**：已完成 / 未完成 / 已逾期 一张三行卡，
 *   已归档、最近删除各一张单行卡；
 * - **第二部分（带「分组」小标题的 [CardSection]）**：未分组、全部各一张单行卡，
 *   用户自定义分组一张 [GroupCard]——自定义分组无行首图标，与带图标的固定行区分
 *   （正是 [GroupCard] 与 [SettingsCardwithIcon] 的行型差异）。
 *
 * 顶栏左蓝「新建」（`ic_add_circle`，**本次暂不做**：[onConfirm] 空实现，按压反馈由
 * 胶囊自带）+ 居中标题 + 右白「关闭」，与课表切换浮层同款顶栏排布。浮层框架
 * [OverlaySheet]：两段式关闭（visible / onDismissed），系统返回与点遮罩、下拉、
 * 关闭胶囊同走 [onDismiss]；列表型浮层走 [SheetImeBehavior.IgnoreIme]（无输入框，
 * 键盘不必让位），内容列由浮层自带的橡皮筋滚动接管，这里不再加滚动。
 *
 * **计数口径**（数据层接入后替换，各行注释标明）：已完成 / 未完成 / 已逾期 实时从 [todos]
 * 统计，逾期判定与列表同一份（[IsoDate.today] + [currentMinuteOfDay]）；已归档 /
 * 最近删除数据层暂无该概念（`removeTodo` 是硬删，无回收站），恒 0；未分组 = [Todo] 尚无
 * 分组字段 ⇒ 全部待办都未分组，全部 = 待办总数；自定义分组计数恒 0（无分组数据模型）。
 *
 * **交互本期全部空置**（同顶栏占位块惯例，只留按压反馈）：所有行与新建按钮的点击回调为空，
 * 行右侧箭头只是设计语言，导航/过滤等数据层接好后再实装；自定义分组的 [GroupCard]
 * 长按编辑态同样不接（设计稿顶栏无编辑入口，editing 走默认 false，长按无响应）。
 */
@Composable
internal fun TodoGroupsSheet(
    todos: List<Todo>,
    groups: List<String>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 系统返回 = 关浮层而非退出 app；enabled 跟 visible——出场动画期间不再拦截
    //（visible 已 false，重复提交被挡在门外），同 TodoDetailSheet
    BackHandler(enabled = visible, onBack = onDismiss)

    // 逾期判定的"此刻"：当日/分钟随重组取值（同 TodoList 的写法）——跨过截止时刻后，
    // 任何一次重组都会重算计数
    val today = IsoDate.today()
    val nowMinute = currentMinuteOfDay()
    val completedCount = todos.count { it.completed }
    val incompleteCount = todos.count { !it.completed }
    val overdueCount = todos.count { it.isOverdue(today, nowMinute) }
    val totalCount = todos.size

    OverlaySheet(
        title = stringResource(R.string.todo_groups_title),
        confirmLabel = stringResource(R.string.todo_groups_create),
        confirmIcon = R.drawable.ic_add_circle,
        // 新建分组本次暂不做：空回调保留胶囊按压反馈，接功能时由调用方挂新建表单浮层
        onConfirm = {},
        rightAction = SheetTopAction(
            label = stringResource(R.string.todo_groups_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        // 列表型浮层：没有输入框，键盘不必让位（同课表切换浮层）
        imeBehavior = SheetImeBehavior.IgnoreIme,
        modifier = modifier,
    ) {
        // 部分间 12dp；每部分内部的卡间 10dp 由 CardSection 的 spacing 默认值负责
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 第一部分：状态与留存。设计稿无小标题，CardSection 退化为纯分组容器
            CardSection {
                // 状态三行同卡：计数实时来自待办列表（值右对齐走 fieldValue）
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_checkbox_checked,
                            label = stringResource(R.string.todo_section_completed),
                            value = completedCount.toString(),
                            // 点击本期空置（行右侧箭头是设计语言，导航后续接入）
                            onClick = {},
                        ),
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_checkbox_unchecked,
                            label = stringResource(R.string.todo_section_incomplete),
                            value = incompleteCount.toString(),
                            onClick = {},
                        ),
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_checkbox_warning,
                            label = stringResource(R.string.todo_section_overdue),
                            value = overdueCount.toString(),
                            onClick = {},
                        ),
                    ),
                )
                // 已归档单行卡：数据层暂无归档概念，计数恒 0（接入后由归档统计替换）
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_archive,
                            label = stringResource(R.string.todo_section_archived),
                            value = "0",
                            onClick = {},
                        ),
                    ),
                )
                // 最近删除单行卡：无回收站（removeTodo 硬删），计数恒 0（接入软删后替换）
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_delete,
                            label = stringResource(R.string.todo_section_trash),
                            value = "0",
                            onClick = {},
                        ),
                    ),
                )
            }
            // 第二部分：分组。标题复用顶栏动作块的「分组」文案（同一语义词）
            CardSection(title = stringResource(R.string.todo_action_group)) {
                // 未分组单行卡：Todo 还没有分组字段 ⇒ 全部待办都算未分组
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_folder_hint,
                            label = stringResource(R.string.todo_group_ungrouped),
                            value = totalCount.toString(),
                            onClick = {},
                        ),
                    ),
                )
                // 全部单行卡：待办总数
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_folder,
                            label = stringResource(R.string.todo_group_all),
                            value = totalCount.toString(),
                            onClick = {},
                        ),
                    ),
                )
                // 用户自定义分组：GroupCard（无图标行）；计数恒 0——分组数据模型尚未落库，
                // 数据层接入后由分组统计替换。点击与长按编辑态本期都不接
                if (groups.isNotEmpty()) {
                    GroupCard(
                        items = groups.map { name ->
                            // 分组以名字为身份键：数据层就地重排/多选时按 id 跟随
                            GroupCardItem(id = name, label = name, value = "0")
                        },
                        onItemClick = {},
                    )
                }
            }
        }
    }
}
