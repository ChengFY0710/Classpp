package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.isOverdue
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.ui.components.BottomTextFieldCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.ConfirmDialogCard
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.components.GroupCard
import com.fangyi.classpp.ui.components.GroupCardItem
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCardwithIcon
import com.fangyi.classpp.ui.components.SettingsCardwithIconItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetPillButton
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors

/**
 * 待办分组浮层：待办页顶栏「更多 → 分组」进入，内容分两部分——
 *
 * - **第一部分（状态与留存，无标题 [CardSection]）**：已完成 / 未完成 / 已逾期 一张三行卡，
 *   已归档、最近删除各一张单行卡；
 * - **第二部分（带「分组」小标题的 [CardSection]）**：未分组、全部各一张单行卡，
 *   用户自定义分组一张 [GroupCard]——自定义分组无行首图标，与带图标的固定行区分
 *   （正是 [GroupCard] 与 [SettingsCardwithIcon] 的行型差异）。
 *
 * **顶栏普通态**：左蓝「新建」（`ic_add_circle` → [BottomTextFieldCard] 底部输入弹卡，
 * 经 [onGroupsChange] 整表写回落库）+ 居中标题 + 右白「关闭」，与课表切换浮层同款顶栏排布。
 * 浮层框架 [OverlaySheet]：两段式关闭（visible / onDismissed），系统返回与点遮罩、下拉、
 * 关闭胶囊同走 [requestClose]；列表型浮层走 [SheetImeBehavior.IgnoreIme]，内容列由浮层
 * 自带的橡皮筋滚动接管，这里不再加滚动。
 *
 * ## 编辑态（长按 [GroupCard] 条目进入）
 * 编辑态状态（editing / selectedIds / 草稿）全部在本组件内 remember：进程重建即草稿丢失，
 * 等价按下「取消」，无数据损失（同理无需 Saver 样板）。
 *
 * - **进入**：长按某行 → 以 [groups] 快照草稿 [draft]、自动选中该行；顶栏换「左白✕取消 +
 *   居中标题 + 右蓝✓确认」（[OverlaySheet] 的 confirmAtEnd），底栏操作行从底部滑入。
 * - **底栏操作行**（[EditActionRow]）：置顶 / 重命名 / 删除三颗胶囊，进出场用浮层同源的
 *   [Motion.Overlay] 非线性曲线（进 [Motion.SheetEnterMillis]、出 [Motion.SheetExitMillis]），
 *   退出编辑态整块滑出。钉底规格（渐变兜底 / 12-24 内距 / 导航栏留白）自绘自持——
 *   OverlaySheet 传 `bottomScrim = false` 只留钉底定位，规格随动画层整体移动。
 * - **置顶**（[applyPin] / [pinAction]，只作用于草稿）：选中全未置顶 = `ic_pin`「置顶」、
 *   全已置顶 = `ic_pin_off`「取消」、空选择或混合选择 = negative 置灰不可点；提交后新置顶项
 *   落置顶前缀尾部（顺序即置顶操作顺序）、取消置顶项按原相对序落回非置顶区——稳定划分维持
 *   [GroupCard] 的置顶前缀契约。
 * - **删除**：底栏「删除」（空选择置灰）→ [DeleteGroupConfirmDialog] 两段式确认 → **立即生效**
 *   （[onDeleteGroups]，仅删分组），同时从草稿移除；不经顶栏「取消」撤销，删空自动退编辑。
 * - **重命名**：底栏按钮渲染但恒置灰占位（本次不做）。
 * - **退出**：顶栏「确认」= [onGroupsChange] 写回草稿（顺序 + 置顶）并退编辑；「取消」/ 点遮罩 /
 *   系统返回 = 丢草稿退编辑（[requestClose] 三分支合一）；编辑态下拉关闭被 dragDismissEnabled
 *   禁用（松手回弹，不关浮层——只拦回调会把浮层卡在半拖出位置）。
 *
 * **计数口径**：已完成 / 未完成 / 已逾期 实时从 [todos] 统计，逾期判定与列表同一份
 * （[IsoDate.today] + [currentMinuteOfDay]）；已归档 / 最近删除数据层暂无该概念
 * （`removeTodo` 是硬删，无回收站），恒 0；未分组 = `group == null` 的待办数、
 * 全部 = 待办总数、自定义分组 = 该组名的待办数（分组数据已入 [TodoFile.groups]，
 * 引用完整性由仓库归一化维护）。
 *
 * 第一部分各行的点击仍为空占位（导航/过滤等接好后再实装）。
 *
 * @param groups 分组列表：顺序即显示序，置顶项须构成连续前缀（[TodoGroup] 不变式）
 * @param onGroupsChange 整表写回统一入口：编辑态确认（顺序 + 置顶）、普通态新建（追加分组）
 * @param onDeleteGroups 删除：立即从真实列表移除选中分组，不可经顶栏「取消」撤销
 */
@Composable
internal fun TodoGroupsSheet(
    todos: List<Todo>,
    groups: List<TodoGroup>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onGroupsChange: (List<TodoGroup>) -> Unit,
    onDeleteGroups: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ——— 编辑态状态机（全 remember：进程重建 = 草稿丢 = 等价取消，无数据损失）———
    var editing by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    // 编辑态显示源：进入时快照 groups，置顶/拖拽只改它，确认才写回
    var draft by remember { mutableStateOf(emptyList<TodoGroup>()) }
    // 删除确认请求：非空 = 对话框打开（值为按显示顺序快照的选中组名）
    var deleteTargets by remember { mutableStateOf<List<String>?>(null) }
    // 新建分组弹卡：期望态 + 挂载态两段式（同删除确认框），普通态顶栏「新建」触发；
    // createDraft 是受控草稿（打开时清空），确认才追加进分组表
    var createRequested by remember { mutableStateOf(false) }
    var createMounted by remember { mutableStateOf(false) }
    var createDraft by remember { mutableStateOf("") }
    if (createRequested) createMounted = true

    val displayGroups = if (editing) draft else groups

    fun exitEdit() {
        editing = false
        selectedIds = emptySet()
        draft = emptyList()
    }

    fun enterEdit(id: String) {
        draft = groups
        selectedIds = setOf(id)
        editing = true
    }

    fun confirmEdit() {
        onGroupsChange(draft)
        exitEdit()
    }

    // 遮罩 / 下拉 / 系统返回 / 顶栏取消共用一个出口：编辑态先退编辑，非编辑态才真正关浮层
    val requestClose = { if (editing) exitEdit() else onDismiss() }

    // 系统返回 = 退编辑（编辑态）或关浮层（普通态）；删除确认框在其挂载处后注册 BackHandler，
    // 返回键先到它（先关确认框、留在浮层），同 TodoDetailSheet
    BackHandler(enabled = visible, onBack = requestClose)

    // 逾期判定的"此刻"：当日/分钟随重组取值（同 TodoList 的写法）——跨过截止时刻后，
    // 任何一次重组都会重算计数
    val today = IsoDate.today()
    val nowMinute = currentMinuteOfDay()
    val completedCount = todos.count { it.completed }
    val incompleteCount = todos.count { !it.completed }
    val overdueCount = todos.count { it.isOverdue(today, nowMinute) }
    val totalCount = todos.size
    // 未分组 = group == null（引用完整性由仓库归一化维护，读侧无需容错）
    val ungroupedCount = todos.count { it.group == null }

    // 置顶按钮三态（读草稿 + 选中集，编辑态内每次变化重组刷新）
    val currentPinAction = pinAction(displayGroups, selectedIds)
    val onPin: () -> Unit = {
        when (currentPinAction) {
            PinAction.Pin -> draft = applyPin(draft, selectedIds, pinned = true)
            PinAction.Unpin -> draft = applyPin(draft, selectedIds, pinned = false)
            PinAction.Disabled -> Unit
        }
    }

    OverlaySheet(
        title = stringResource(R.string.todo_groups_title),
        // 编辑态确认到右侧、取消动作到左侧（两颗胶囊图标随位置镜像）
        confirmAtEnd = editing,
        confirmLabel = stringResource(
            if (editing) R.string.edit_confirm else R.string.todo_groups_create,
        ),
        confirmIcon = if (editing) R.drawable.ic_checkmark_circle else R.drawable.ic_add_circle,
        // 编辑态确认 = 提交草稿并退编辑；普通态「新建」= 挂载底部输入弹卡（两段式，同删除确认框）
        onConfirm = {
            if (editing) confirmEdit() else {
                createDraft = ""
                createMounted = true
                createRequested = true
            }
        },
        rightAction = SheetTopAction(
            label = stringResource(
                if (editing) R.string.settings_cancel else R.string.todo_groups_close,
            ),
            // 两态都是 ✕ 圆：普通态 = 关闭（白胶囊深色圆），编辑态 = 取消（白胶囊深色圆）
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = requestClose,
        ),
        onDismiss = requestClose,
        visible = visible,
        onDismissed = onDismissed,
        // 编辑态禁用下拉关闭：下拉手势只回弹不触发关闭（拦成「退编辑」会与手势语义打架，
        // 只拦回调则闸门落下没人真关浮层、整层卡在半拖出位置）
        dragDismissEnabled = !editing,
        // 列表型浮层：没有输入框，键盘不必让位（同课表切换浮层）
        imeBehavior = SheetImeBehavior.IgnoreIme,
        // 钉底规格由底栏动画层自持（见 EditActionRow），容器只给钉底定位
        bottomScrim = false,
        bottomContent = {
            EditActionRow(
                editing = editing,
                pinAction = currentPinAction,
                hasSelection = selectedIds.isNotEmpty(),
                onPin = onPin,
                // 重命名恒置灰占位（本次不做），点击到不了这里
                onRename = {},
                onDelete = { deleteTargets = displayGroups.filter { it.name in selectedIds }.map { it.name } },
            )
        },
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
                // 未分组单行卡：group == null 的待办数（引用完整性由仓库归一化维护）
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_folder_hint,
                            label = stringResource(R.string.todo_group_ungrouped),
                            value = ungroupedCount.toString(),
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
                // 用户自定义分组：GroupCard（无图标行），计数 = 该组名的待办数；
                // 编辑态显示草稿、普通态显示真实列表
                if (displayGroups.isNotEmpty()) {
                    GroupCard(
                        items = displayGroups.map { group ->
                            // 分组以名字为身份键：多选/拖拽/删除时按 id 跟随
                            GroupCardItem(
                                id = group.name,
                                label = group.name,
                                value = todos.count { it.group == group.name }.toString(),
                                pinned = group.pinned,
                            )
                        },
                        editing = editing,
                        selectedIds = selectedIds,
                        onEnterEdit = { enterEdit(it) },
                        onItemClick = {},
                        onToggleSelect = { id ->
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        },
                        // 拖拽落位同步重排草稿（GroupCard 契约）；置顶项由组件内锁序
                        onMove = { from, to -> draft = draft.move(from, to) },
                    )
                }
            }
        }
    }

    // 删除确认框：挂在 OverlaySheet 调用之后（画到浮层卡片之上），后注册 BackHandler——
    // 返回键先到它：取消/返回只关确认框、留在浮层，关掉后才轮到浮层的出口。
    // 挂载与可见性分离（同浮层两段式关闭）：确认/取消先清请求播淡出，播完才卸载
    var deleteDialogMounted by remember { mutableStateOf(false) }
    val deleteDialogVisible = deleteTargets != null
    if (deleteDialogVisible) deleteDialogMounted = true
    // 淡出期间文案快照：确认后 targets 已清、草稿已删，播动画的卡片不能跟着变空
    var shownDeleteTargets by remember { mutableStateOf(emptyList<String>()) }
    if (deleteDialogVisible) shownDeleteTargets = deleteTargets.orEmpty()
    if (deleteDialogMounted) {
        DeleteGroupConfirmDialog(
            groupNames = shownDeleteTargets,
            visible = deleteDialogVisible,
            onDismissed = { deleteDialogMounted = false },
            onCancel = { deleteTargets = null },
            onConfirm = {
                val names = deleteTargets
                deleteTargets = null
                if (!names.isNullOrEmpty()) {
                    // 立即生效：真实列表先删（不经顶栏取消撤销），草稿同步移除保持显示一致
                    onDeleteGroups(names)
                    val nameSet = names.toSet()
                    draft = draft.filterNot { it.name in nameSet }
                    selectedIds = selectedIds - nameSet
                    if (draft.isEmpty()) exitEdit()
                }
            },
        )
    }

    // 新建分组弹卡（[BottomTextFieldCard]）：叠加在浮层之后组合（画到浮层之上），
    // 返回键/遮罩点击/键盘让位由弹卡框架接管；挂载与可见性分离（同删除确认框）。
    // 挂载即自动聚焦弹键盘、整卡被键盘顶起；确认 = 追加分组经 onGroupsChange 整表写回落库，
    // 落库失败的 Toast 由宿主（TodoScreen 的 commitGroups）统一处理
    if (createMounted) {
        val createName = createDraft.trim()
        BottomTextFieldCard(
            visible = createRequested,
            onDismiss = { createRequested = false },
            onDismissed = { createMounted = false },
            title = stringResource(R.string.todo_group_create_title),
            placeholder = stringResource(R.string.todo_group_create_hint),
            value = createDraft,
            onValueChange = { createDraft = it },
            // 实时校验：空名或与现有分组重名时确认钮置灰（同前一版居中弹窗约定）
            confirmEnabled = createName.isNotEmpty() && createName !in groups.map { it.name },
            onConfirm = {
                createRequested = false
                onGroupsChange(groups + TodoGroup(createName))
            },
        )
    }
}

/**
 * 编辑态底部操作栏：置顶 / 重命名（恒置灰占位）/ 删除三颗胶囊，整块从底部滑入滑出。
 *
 * 进出场 = 浮层同源的 [Motion.Overlay] 非线性曲线（进 [Motion.SheetEnterMillis]、
 * 出 [Motion.SheetExitMillis]），与浮层本体同一套手感。渐变兜底 + 12/24 内距 +
 * 导航栏留白在这里自绘自持（OverlaySheet 传 `bottomScrim = false` 只给钉底定位）——
 * 规格随动画层整体移动，非编辑态不残留底部薄纱。胶囊参数同切换课表浮层的导入/导出按钮。
 */
@Composable
private fun EditActionRow(
    editing: Boolean,
    pinAction: PinAction,
    hasSelection: Boolean,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    AnimatedVisibility(
        visible = editing,
        enter = slideInVertically(tween(Motion.SheetEnterMillis, easing = Motion.Overlay)) { it } +
            fadeIn(tween(Motion.SheetEnterMillis, easing = Motion.Overlay)),
        exit = slideOutVertically(tween(Motion.SheetExitMillis, easing = Motion.Overlay)) { it } +
            fadeOut(tween(Motion.SheetExitMillis, easing = Motion.Overlay)),
        label = "todoGroupsEditActions",
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        ),
                    ),
                )
                .navigationBarsPadding()
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
            ) {
                // 置灰统一用 theme 的 negative（弱化规范色，非危险色）；可点时置顶蓝、删除红
                val negative = MaterialTheme.classppColors.negative
                val pinEnabled = pinAction != PinAction.Disabled
                SheetPillButton(
                    label = stringResource(
                        if (pinAction == PinAction.Unpin) R.string.settings_cancel else R.string.edit_pin,
                    ),
                    icon = if (pinAction == PinAction.Unpin) R.drawable.ic_pin_off else R.drawable.ic_pin,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = if (pinEnabled) MaterialTheme.colorScheme.primary else negative,
                    onClick = onPin,
                    enabled = pinEnabled,
                    iconSize = 25.dp,
                    topPadding = 10.dp,
                    bottomPadding = 10.dp,
                    startPadding = 15.dp,
                    endPadding = 10.dp,
                )
                SheetPillButton(
                    label = stringResource(R.string.edit_rename),
                    icon = R.drawable.ic_rename,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = negative,
                    onClick = onRename,
                    enabled = false,
                    iconSize = 25.dp,
                    topPadding = 10.dp,
                    bottomPadding = 10.dp,
                    startPadding = 15.dp,
                    endPadding = 10.dp,
                )
                SheetPillButton(
                    label = stringResource(R.string.edit_delete),
                    icon = R.drawable.ic_delete,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = if (hasSelection) MaterialTheme.colorScheme.error else negative,
                    onClick = onDelete,
                    enabled = hasSelection,
                    iconSize = 25.dp,
                    topPadding = 10.dp,
                    bottomPadding = 10.dp,
                    startPadding = 15.dp,
                    endPadding = 10.dp,
                )
            }
        }
    }
}

/**
 * 删除分组确认框：[ConfirmDialogCard] 白卡 + 遮罩，叠加在浮层**之后**组合——返回键时后注册的
 * 它先收到，优先于浮层出口。仅删分组：该组待办由仓库在同一次 setGroups 提交里级联
 * 置回未分组（不删除待办本身），文案只提分组；删除钮走 error（同删除课表/删除待办确认框先例）。
 */
@Composable
private fun DeleteGroupConfirmDialog(
    groupNames: List<String>,
    visible: Boolean,
    onDismissed: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    FadeOverlayDialog(
        visible = visible,
        onDismiss = onCancel,
        onDismissed = onDismissed,
        cardHorizontalPadding = 32.dp,
    ) {
        ConfirmDialogCard(
            title = stringResource(R.string.todo_groups_delete_title),
            message = if (groupNames.size == 1) {
                stringResource(R.string.todo_groups_delete_message, groupNames.first())
            } else {
                stringResource(R.string.todo_groups_delete_message_multi, groupNames.size)
            },
        ) {
            DialogPillButton(
                label = stringResource(R.string.settings_cancel),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onCancel,
            )
            DialogPillButton(
                label = stringResource(R.string.edit_delete),
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onConfirm,
            )
        }
    }
}

/** 置顶按钮三态：可点置顶 / 可点取消置顶 / 置灰（空选择或同时选中已置顶与未置顶） */
internal enum class PinAction { Pin, Unpin, Disabled }

/**
 * 置顶按钮状态判定：选中集内全未置顶 = [PinAction.Pin]、全已置顶 = [PinAction.Unpin]，
 * 空选择或混合（既有已置顶又有未置顶）= [PinAction.Disabled]。
 */
internal fun pinAction(groups: List<TodoGroup>, selected: Set<String>): PinAction {
    val chosen = groups.filter { it.name in selected }
    if (chosen.isEmpty()) return PinAction.Disabled
    return when {
        chosen.all { it.pinned } -> PinAction.Unpin
        chosen.none { it.pinned } -> PinAction.Pin
        else -> PinAction.Disabled
    }
}

/**
 * 置顶 / 取消置顶：改标记后做一次稳定划分（`filter { pinned } + filter { !pinned }`），
 * 维持 [TodoGroup] / [GroupCard] 的「置顶连续前缀」契约——
 * 新置顶项落到置顶前缀尾部（同批保持原相对序，前缀整体顺序即置顶操作顺序），
 * 取消置顶项按原相对序落回非置顶区（划分稳定）。
 */
internal fun applyPin(groups: List<TodoGroup>, selected: Set<String>, pinned: Boolean): List<TodoGroup> {
    val flagged = groups.map { if (it.name in selected) it.copy(pinned = pinned) else it }
    return flagged.filter { it.pinned } + flagged.filter { !it.pinned }
}

/** 分组重排（[GroupCard] 的 onMove 契约：调用方同步重排）；下标越界或原地则原样返回 */
internal fun List<TodoGroup>.move(from: Int, to: Int): List<TodoGroup> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
