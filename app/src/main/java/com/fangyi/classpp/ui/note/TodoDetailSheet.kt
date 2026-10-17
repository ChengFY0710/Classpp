package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.components.BottomTextFieldCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.MenuGrowth
import com.fangyi.classpp.ui.components.NoteCard
import com.fangyi.classpp.ui.components.NoteCardSwipe
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuPopup
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.components.SheetInfoCard
import com.fangyi.classpp.ui.components.SheetInfoEntry
import com.fangyi.classpp.ui.components.SheetTextArea
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.SwipeAction
import com.fangyi.classpp.ui.components.SwipeCoordinator
import com.fangyi.classpp.ui.components.TagChip
import com.fangyi.classpp.ui.components.flagColor
import com.fangyi.classpp.ui.components.flagLabel
import com.fangyi.classpp.ui.components.labelText
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.NoteCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState

/** 标签行内胶囊间距（同 NoteCard 属性行）；胶囊与文字的 4dp 在 TagChip 内部 */
private val TagRowSpacing = 12.dp

/** 标题行「更多」按钮边长：与 titleLarge 行高（28sp）等高，顶对齐下视觉居中于标题首行 */
private val TitleMoreIconSize = 28.dp

/** 标题与「更多」按钮的间距 */
private val TitleMoreSpacing = 12.dp

/** 更多菜单卡顶缘与按钮底缘的间距（同 TodoTopBar 排序菜单节奏） */
private val TitleMenuGap = 12.dp

/**
 * 待办详情浮层：点待办卡弹出，结构对标 [com.fangyi.classpp.ui.schedule.CourseDetailSheet]——
 * 正文为待办标题、标题下方的标签行（[TagChip]，空标签不渲染）、四行只读信息
 * （[SheetInfoCard]：截止日期 / 紧急程度 / 待办时间 / 待办地点，四行常显，
 * 空值显灰「无」）、备注输入（[SheetTextArea]）、带标题的「创建与完成时间」分组
 * （[SheetInfoCard] 两条时刻戳：创建时间常显、完成时间仅已完成时渲染）与「步骤」分组
 * （列表走 [NoteCard] 右侧勾选框、支持向右滑呼出删除动作卡（双阈值手势见
 * [com.fangyi.classpp.ui.components.NoteCardSwipeLayer]），其下是等高的
 * 「添加步骤」行卡 [AddStepCard]）。
 *
 * 信息行行首各带 24dp onSurface 图标（[InfoRowIcon]），值色两处按紧急度取色、与
 * 列表卡同源：截止值走 [deadlineColor]（剩 2 天黄 / 1 天橙 / 当天及逾期红 / 其余 primary）、
 * 紧急程度走 [flagColor]（非常紧急红 / 很紧急橙 / 紧急黄 / 不紧急绿 / 无档灰）；
 * 时间与地点保持默认 primary。
 *
 * 正文按浮层内容规范排版：标题与标签行保持裸元素（规范里唯二的例外），信息卡与备注
 * 同收进一个无题 [CardSection]；顶层块间距走 [SheetSectionSpacingBetween]。
 *
 * 顶栏左「编辑」右「关闭」：编辑把**最新快照**（备注 trim 后、可尚未落盘）经 [onEdit] 交回调用方，
 * 据此打开编辑待办浮层（叠在本浮层之上，本浮层保持打开）；备注在任意关闭路径
 * （关闭胶囊 / 系统返回 / 下拉 / 点遮罩）先经 [onNoteSave] 落盘再关，输入不丢；
 * 调用方负责实际持久化与去重。
 *
 * 浮层框架是 [OverlaySheet]：两段式关闭（visible / onDismissed）；键盘避让走默认的
 * ContentScroll（备注聚焦时内容滚动让位）。
 */
@Composable
internal fun TodoDetailSheet(
    visible: Boolean,
    todo: Todo,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onNoteSave: (String) -> Unit,
    onEdit: (Todo) -> Unit,
    // 新增一条步骤（标题已 trim、非空）；勾选/取消一条步骤——两者都由宿主落库，
    // 失败 Toast 也由宿主提示
    onStepAdd: (String) -> Unit,
    onStepToggle: (String, Boolean) -> Unit,
    // 侧滑删除一条步骤（展开后再拖到完全变色松手才执行，无确认框）；由宿主落库、失败 Toast
    onStepDelete: (stepId: String) -> Unit,
    // 开关列表卡倒计时（true = 开启）；落库后本层菜单文案随传入的 todo 自动回流
    onCountdownToggle: (Boolean) -> Unit,
    // 被编辑浮层盖住：本层缩小后退（透传 OverlaySheet 的分层动效）
    covered: Boolean = false,
) {
    // 备注本地态：键在待办 id + 仓库 note 上——换目标重建时初值跟着换；编辑浮层改了备注
    // 并确认（仓库 note 变化）也触发重初始化，否则残留旧值会在关闭时把新值覆盖回去。
    // 打字不回流仓库（值未变不重置）；落盘后回流同值同样不重置
    var note by rememberSaveable(todo.id, todo.note) { mutableStateOf(todo.note) }
    // 新增步骤弹卡的草稿与两段式挂载（同 TodoGroupsSheet 的命名卡）：挂载与可见性分离，
    // 打开时清草稿；确认即关、由宿主落库
    var stepDraft by remember { mutableStateOf("") }
    var stepRequested by remember { mutableStateOf(false) }
    var stepMounted by remember { mutableStateOf(false) }
    // 标题行更多菜单的展开态；倒计时开关是持久化状态（Todo.countdown），这里只读不存
    var moreExpanded by remember { mutableStateOf(false) }
    // haze 采样源：自建后同时交给本浮层容器（顶栏磨砂）与叠在其上的输入弹卡
    // （弹卡背景模糊）——两者必须同一个实例，否则弹卡按默认平底渲染（见 BottomTextFieldCard KDoc）
    val sheetHazeState = rememberHazeState()
    val closeWithSave = {
        onNoteSave(note.trim())
        onDismiss()
    }
    // 系统返回 = 收起浮层而非退出 app；与关闭/下拉同走 closeWithSave，备注照常先落盘。
    // enabled 跟 visible：出场动画期间不再拦截（visible 已 false，重复提交被挡在门外）
    BackHandler(enabled = visible, onBack = closeWithSave)
    // 步骤侧滑互斥协调器：同一时刻至多一张步骤卡展开。其 BackHandler 注册在上面的浮层
    // 出口之后（后注册者优先，同项目分层出口约定）——展开态下系统返回先收起卡片，
    // 无展开卡才轮到关浮层
    val stepSwipeCoordinator = remember { SwipeCoordinator() }
    BackHandler(enabled = stepSwipeCoordinator.activeKey != null) {
        stepSwipeCoordinator.activeKey = null
    }
    // 点「编辑」先收键盘：详情还挂在编辑浮层下层，焦点不清会把键盘带进编辑表单
    val focusManager = LocalFocusManager.current

    OverlaySheet(
        title = stringResource(R.string.todo_detail_title),
        confirmLabel = stringResource(R.string.detail_edit),
        confirmIcon = R.drawable.ic_edit,
        // 编辑图标单独调到 26dp（其余浮层维持默认 30dp）
        confirmIconSize = 26.dp,
        onConfirm = {
            focusManager.clearFocus()
            onEdit(todo.copy(note = note.trim()))
        },
        rightAction = SheetTopAction(
            label = stringResource(R.string.detail_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = closeWithSave,
        ),
        onDismiss = closeWithSave,
        visible = visible,
        covered = covered,
        onDismissed = onDismissed,
        // 采样源交给容器（见 sheetHazeState 声明处）：顶栏磨砂与叠层弹卡的背景模糊同源
        hazeState = sheetHazeState,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 16.dp),
                // 顶对齐：标题换行长高时「更多」按钮钉在首行右侧不动
                //（按钮 28dp = titleLarge 行高，首行内视觉居中）
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = todo.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    // 避让按钮：占满剩余宽度，过长自动换行
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(TitleMoreSpacing))
                // 菜单锚点：弹层挂在按钮的 Box 内，锚点窗口坐标即按钮窗口坐标（PopupMenuPopup 约定）
                Box {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_circle),
                        contentDescription = stringResource(R.string.cd_todo_more),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(TitleMoreIconSize)
                            .pressClickable(CircleShape, onClick = { moreExpanded = true }),
                    )
                    TitleMoreMenu(
                        expanded = moreExpanded,
                        onDismiss = { moreExpanded = false },
                        hazeState = sheetHazeState,
                        // 无截止日期或已标记完成：倒计时项置灰不可点（无截止时仓库不变式
                        // 也保证开关必为 false；完成后仅禁用入口，开关值原样保留，取消
                        // 完成即恢复可点）
                        countdownEnabled = todo.deadlineDate != null && !todo.completed,
                        completed = todo.completed,
                        countdownOn = todo.countdown,
                        onCountdownToggle = onCountdownToggle,
                    )
                }
            }
            if (todo.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(TagRowSpacing),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    todo.tags.forEach { tag -> TagChip(tag) }
                }
            }
            // 四行常显：截止日期与紧急程度是结构化字段（未设也占位），时间/地点同理——
            // 值一律右对齐走 fieldValue，空值占位灰字「无」（同截止入口行的未设态文案）；
            // 行首图标同 NoteCard 属性行（闹钟/旗/时钟/定位针），尺寸与着色见 [InfoRowIcon]
            val noneText = stringResource(R.string.deadline_card_none)
            // 「今天」在组合期取一次：下面 [listOfNotNull] 的 let 不是组合上下文，值先落地
            val today = LocalToday
            // 信息卡与备注输入是浮层规范里的两个"孤块"，同收进一个无题 CardSection
            // （组内间距走 CardSection 默认节奏）
            CardSection {
                SheetInfoCard(
                    entries = listOf(
                        SheetInfoEntry(
                            label = stringResource(R.string.deadline_card_label),
                            value = todo.deadlineText() ?: noneText,
                            icon = { InfoRowIcon(R.drawable.ic_clock_alarm) },
                            // 截止值色与列表卡同源（[deadlineColor]）：离截止越近越急
                            valueColor = todo.deadlineColor(),
                        ),
                        SheetInfoEntry(
                            label = stringResource(R.string.sort_urgency),
                            value = todo.urgency.flagLabel(),
                            icon = { InfoRowIcon(R.drawable.ic_flag_regular) },
                            // 档位色与旗标同源（[flagColor]）：红/橙/黄/绿，无档显灰
                            valueColor = todo.urgency.flagColor(),
                        ),
                        SheetInfoEntry(
                            label = stringResource(R.string.todo_detail_time_label),
                            value = todo.scheduledText() ?: noneText,
                            icon = { InfoRowIcon(R.drawable.ic_clock) },
                        ),
                        SheetInfoEntry(
                            label = stringResource(R.string.todo_location_label),
                            value = todo.location.ifBlank { noneText },
                            icon = { InfoRowIcon(R.drawable.ic_location) },
                        ),
                    ),
                )
                SheetTextArea(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = stringResource(R.string.detail_note_ph),
                )
            }
            // 重复：仅设置过重复的待办显示（重复待办 = 一组截止日期不同的虚拟独立待办，
            // 统计按 repeat 规则展开的应完成日期序列计）——类型卡 + 计数卡各一张；
            // 逾期数走展示口径（落账数 + 水位后到昨天的实时补算，跨天驻留不用等写库刷新）
            if (todo.repeat.type != RepeatType.None) {
                CardSection(title = stringResource(R.string.todo_section_repeat)) {
                    SheetInfoCard(
                        entries = listOf(
                            SheetInfoEntry(
                                label = stringResource(R.string.todo_detail_repeat_type),
                                value = todo.repeat.type.labelText(),
                            ),
                        ),
                    )
                    SheetInfoCard(
                        entries = listOf(
                            SheetInfoEntry(
                                label = stringResource(R.string.todo_detail_completed_count),
                                value = stringResource(R.string.todo_count_times, todo.completedOccurrences.size),
                            ),
                            SheetInfoEntry(
                                label = stringResource(R.string.todo_detail_overdue_count),
                                value = stringResource(R.string.todo_count_times, todo.overdueOccurrences.size),
                            ),
                        ),
                    )
                }
            }
            // 更多信息：分组 + 只读时刻戳，无图标槽；值色固定 Primary（不传 valueColor 即默认）
            CardSection(title = stringResource(R.string.todo_section_more_info)) {
                SheetInfoCard(
                    entries = listOfNotNull(
                        // 分组：group 即分组名，null（未分组）显既有「未分组」文案
                        SheetInfoEntry(
                            label = stringResource(R.string.todo_detail_group_label),
                            value = todo.group ?: stringResource(R.string.todo_group_ungrouped),
                        ),
                        SheetInfoEntry(
                            label = stringResource(R.string.todo_created_label),
                            // 旧数据文件缺 createdAt 时仓库尚未补齐（0）：仍占位显「无」，不显 1970 假数据
                            value = if (todo.createdAtMillis == 0L) {
                                noneText
                            } else {
                                epochMillisText(todo.createdAtMillis, today)
                            },
                        ),
                        // 完成时间：仓库不变式保证未完成时 completedAtMillis 为 null，此行随之不渲染
                        todo.completedAtMillis?.let { millis ->
                            SheetInfoEntry(
                                label = stringResource(R.string.todo_completed_label),
                                value = epochMillisText(millis, today),
                            )
                        },
                    ),
                )
            }
            // 步骤：列表走 NoteCard（右侧勾选框、完成态划线置灰；支持向右滑呼出删除——
            // 双阈值手势见 [NoteCardSwipeLayer]，[stepSwipeCoordinator] 保证单卡展开、
            // 展开态按系统返回先收起），其下是等高的「添加步骤」行卡；步骤为空时只有按钮卡
            CardSection(title = stringResource(R.string.todo_section_steps)) {
                todo.steps.forEach { step ->
                    NoteCard(
                        title = step.title,
                        completed = step.done,
                        // 卡片本身静态（不传 onClick）：勾选框是唯一交互位
                        onCheckedChange = { onStepToggle(step.id, it) },
                        // 侧滑只配删除侧（向右滑呼出，error 红，展开后再拖满色松手即删）；
                        // pin 传 null——向左方向不可拖出
                        swipe = NoteCardSwipe(
                            key = step.id,
                            coordinator = stepSwipeCoordinator,
                            delete = SwipeAction(
                                iconRes = R.drawable.ic_delete,
                                tint = MaterialTheme.colorScheme.error,
                                activeColor = MaterialTheme.colorScheme.error,
                                onActiveColor = MaterialTheme.colorScheme.onError,
                                contentDescription = stringResource(R.string.todo_swipe_delete),
                                onAction = { onStepDelete(step.id) },
                            ),
                            pin = null,
                        ),
                    )
                }
                AddStepCard(
                    onClick = {
                        stepDraft = ""
                        stepMounted = true
                        stepRequested = true
                    },
                )
            }
        }
    }
    // 新增步骤弹卡（[BottomTextFieldCard]）：组合在浮层之后（画到浮层之上），返回键/遮罩/
    // 键盘让位由弹卡框架接管；确认即关并交宿主落库，空名时确认钮置灰
    if (stepMounted) {
        BottomTextFieldCard(
            visible = stepRequested,
            onDismiss = { stepRequested = false },
            onDismissed = { stepMounted = false },
            title = stringResource(R.string.todo_step_add),
            placeholder = stringResource(R.string.todo_step_create_hint),
            value = stepDraft,
            onValueChange = { stepDraft = it },
            confirmEnabled = stepDraft.isNotBlank(),
            // 卡片磨砂：采样源同本浮层顶栏（见 sheetHazeState 声明处）
            hazeState = sheetHazeState,
            onConfirm = {
                stepRequested = false
                onStepAdd(stepDraft.trim())
            },
        )
    }
}

/**
 * 标题行「更多」菜单（本文件私有）：[PopupMenuPopup] 共享弹层宿主——投影留白、越界钳制
 * 与 scale/alpha 进出场由宿主处理；卡片右缘对齐锚点（按钮）右缘、顶缘与按钮底缘留
 * [TitleMenuGap]，锚点在行尾故自卡片右上角长出。
 *
 * 条目：添加到日历（本轮只做 UI——点按仅收起菜单，不接日历写入）；倒计时在
 * [countdownEnabled] 为 false（无截止日期，或该待办已标记完成）时置灰不可点
 * （`classppColors.negative`）、可点时点按把新开关值交 [onCountdownToggle] 落库；
 * 文案：完成态（[completed] = true）恒显「开启倒计时」（此刻必然置灰），未完成随
 * 传入的 [countdownOn] 在「开启/关闭倒计时」间切换（状态源 = `Todo.countdown`，
 * 不在本组件内留存）。
 */
@Composable
private fun TitleMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    hazeState: HazeState,
    countdownEnabled: Boolean,
    completed: Boolean,
    countdownOn: Boolean,
    onCountdownToggle: (Boolean) -> Unit,
) {
    val gapPx = with(LocalDensity.current) { TitleMenuGap.roundToPx() }
    PopupMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        // 菜单卡的背景模糊与本浮层的顶栏磨砂同源（见 sheetHazeState 声明处）
        hazeState = hazeState,
        sections = listOf(
            PopupMenuSection(
                items = listOf(
                    PopupMenuItem(
                        label = stringResource(R.string.todo_menu_add_to_calendar),
                        icon = R.drawable.ic_calendar_regular,
                        onClick = onDismiss,
                    ),
                    PopupMenuItem(
                        // 完成态恒显「开启倒计时」（此刻整体置灰不可点，不暴露「关闭」误导
                        // 用户以为还能操作）；未完成随开关在「开启/关闭倒计时」间切换
                        label = stringResource(
                            if (!completed && countdownOn) {
                                R.string.todo_menu_countdown_stop
                            } else {
                                R.string.todo_menu_countdown_start
                            },
                        ),
                        icon = R.drawable.ic_hourglass_half,
                        enabled = countdownEnabled,
                        onClick = {
                            // 交宿主落库（列表卡随即显示/收起剩余天数）；本层文案随回流切换
                            onCountdownToggle(!countdownOn)
                            onDismiss()
                        },
                    ),
                ),
            ),
        ),
        cardPosition = { anchorBounds, _, cardSize ->
            IntOffset(
                x = anchorBounds.right - cardSize.width,
                y = anchorBounds.bottom + gapPx,
            )
        },
        menuGrowth = MenuGrowth.TopRight,
    )
}

/**
 * 「添加步骤」行卡（本文件私有）：白底 + [NoteCardShape]（与步骤卡同形），
 * 行高下限同步骤卡（[SheetFieldHeight]），内容居中——primary 着色的 ic_add_circle
 * 24dp 与文案（[classppTextStyles] 的 fieldValue），两者间距 12dp。
 */
@Composable
private fun AddStepCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SheetFieldHeight)
            .clip(NoteCardShape)
            .background(MaterialTheme.colorScheme.surface)
            // 按压反馈同 NoteCard 的可点态：位置在 clip 之前（缩放作用于整卡、不被圆角裁掉）
            .pressClickable(NoteCardShape, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add_circle_regular),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.todo_step_add),
            style = MaterialTheme.classppTextStyles.fieldValue,
        )
    }
}

/** 信息行图标：24dp、onSurface 着色（[SheetInfoCard] 图标槽规格，同
 *  [com.fangyi.classpp.ui.components.SettingsCardwithIcon]）；资源对齐
 *  [com.fangyi.classpp.ui.components.NoteCard] 各属性行（闹钟 = 截止、时钟 = 待办时间）。 */
@Composable
private fun InfoRowIcon(@DrawableRes res: Int) {
    Icon(
        painter = painterResource(res),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(24.dp),
    )
}

/**
 * 截止值：随重复（下一次重复开始前）显规则文本「下一次重复开始前 N 天」——各期截止由展示层
 * 自动折算，存储的固定日期不再是实际截止；否则 `3/12 8:00`（本年）/ `2027/3/12 8:00`
 * （非本年）；未设截止返回 null（调用方显「无」）。
 */
@Composable
private fun Todo.deadlineText(): String? {
    val date = deadlineDate ?: return null
    if (deadlineBeforeRepeat) {
        return stringResource(R.string.deadline_before_repeat_value, deadlineBeforeDays)
    }
    val minute = deadlineMinute ?: return null
    // 「今天」取共享源（跨零点/跨年自动跳天，见 [LocalToday]），不直接读系统时钟
    return "${date.toAbsoluteDateText(LocalToday)} ${TimeText.format(minute)}"
}

/**
 * 待办时间：时段 `10/14 8:00 - 9:00`（本年）/ `2027/10/14 8:00 - 9:00`（非本年），
 * 全天 `10/14 全天` / `2027/10/14 全天`，多日 `10/14、10/20 全天`；无日期也无时刻返回 null。
 *
 * 与列表卡片的 [cardTimeText] 分工：这里不省略"今天"的日期段（详情页要给出确定的日期），
 * 日期段走 [toAbsoluteDateText]（本年省年份、月日不补零），也不兜底截止值（截止日期有自己的行）。
 */
@Composable
private fun Todo.scheduledText(): String? {
    val timePart = when (timeKind) {
        TodoTimeKind.Period -> {
            val start = startMinute
            val end = endMinute
            if (start != null && end != null) {
                // 起止之间的分隔符带空格（`HH:mm - HH:mm`）
                "${TimeText.format(start)} - ${TimeText.format(end)}"
            } else null
        }
        TodoTimeKind.AllDay -> stringResource(R.string.todo_time_all_day)
        TodoTimeKind.None -> null
    }
    // 「今天」在组合期取一次：joinToString 的变换 lambda 不是组合上下文，值先落地
    val today = LocalToday
    val datePart = dates.joinToString("、") { it.toAbsoluteDateText(today) }.takeIf { it.isNotEmpty() }
    return listOfNotNull(datePart, timePart)
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }
}
