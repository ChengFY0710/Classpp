package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.components.BottomNumberSelectCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.MenuGrowth
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuPopup
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SettingsCardwithIcon
import com.fangyi.classpp.ui.components.SettingsCardwithIconItem
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.note.DEFAULT_DEADLINE_MINUTE
import com.fangyi.classpp.ui.note.currentMinuteOfDay
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import dev.chrisbanes.haze.rememberHazeState

/** 本课程一次上课的具体时刻：日期 + 开始分钟数（截止 = 该刻，deadlineDate/deadlineMinute 成对落库） */
private data class CourseOccurrence(val date: IsoDate, val startMinute: Int)

/**
 * 从 [today] 起向学期末逐日扫描本课程的下一次上课：星期与周规律都命中的第一天。
 * 扫描含今天——当天这节课**已开始**（开始时刻 ≤ [nowMinute]）则视为已过，取下一次；
 * 走到学期末仍无课次（已结课）返回 null。
 */
private fun nextCourseOccurrence(
    entry: CourseEntry,
    schedule: Schedule,
    today: IsoDate,
    nowMinute: Int,
): CourseOccurrence? {
    val startMinute = schedule.slots.getOrNull(entry.startSlot - 1)
        ?.let { TimeText.parseMinutes(it.startTime) }
        ?: DEFAULT_DEADLINE_MINUTE
    val termStartMonday = schedule.termStart.mondayOfWeek()
    val termEndExclusive = termStartMonday + schedule.totalWeeks * 7
    var date = today
    while (date.epochDay < termEndExclusive.epochDay) {
        val week = ((date.mondayOfWeek() - termStartMonday) / 7).toInt() + 1
        if (week in 1..schedule.totalWeeks &&
            date.isoDayOfWeek() == entry.dayOfWeek &&
            entry.weeks.contains(week) &&
            (date != today || startMinute > nowMinute)
        ) {
            return CourseOccurrence(date, startMinute)
        }
        date += 1
    }
    return null
}

/**
 * 新建课程待办浮层：课程详情浮层左上「新建」胶囊的二级浮层，[OverlaySheet] 叠
 * [OverlaySheet]（画在详情浮层之后、自建 haze 采样源、后注册 BackHandler，同
 * [com.fangyi.classpp.ui.note.DeadlineSettingsSheet] 的分层模式）。
 *
 * 简化版的建待办流程：只有待办名与截止日期两组（其余字段落默认值）——待办**自动打上
 * 本节课程的课程标签**（= 课程名字符串，与手动建待办时勾选课程标签同语义）并**自动打开
 * 倒计时**（截止左侧显示剩余天数），落库后详情浮层的待办卡列表（按课程名标签过滤）与
 * 待办列表页都直接可见、可正常管理。
 *
 * 截止只给两个档位（PopupMenuCard 选择菜单，当前项打勾）：
 * - 「下节课」= 本课程下一次上课的开始时刻（含今天的扫描，课已开始则取下一次）；
 *   本学期已无课次时菜单项置灰锁定、档位默认「自定义」。
 * - 「自定义」= 展开周次行（第 X 周），点击唤起周数滚轮，范围 本周数..学期总周数；
 *   截止 = 该周的本节课日 + 本节课开始时间（不校验该周周规律是否上课——截止只是日期
 *   语义）。本节课开始时间取节次表 [Schedule.slots] 的开始文本，缺失/非法回退 8:00。
 *
 * 待办名为空时点「确认」不创建：输入卡红描边报错（新建待办浮层同款交互），输入即清除。
 * 表单字段随浮层卸载而复位（remember，两段式挂载保证出场期间组合仍在、改动不闪空）。
 *
 * @param onConfirm 确认回调：交调用方落库（[Todo] 已带课程名标签与截止对，id 为空串由
 *   仓库补齐）；落库成功与否由调用方处理（失败 Toast 不关浮层）
 */
@Composable
internal fun CourseTodoSheet(
    visible: Boolean,
    entry: CourseEntry,
    schedule: Schedule,
    onConfirm: (todo: Todo) -> Unit,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
) {
    // 表单态：待办名（空名确认红描边）+ 截止档位（下节课 / 自定义周次）
    var name by remember { mutableStateOf("") }
    var nameBlank by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current

    // 返回键优先收键盘（键盘收起后不返回给应用），键盘已收起时才关浮层（新建待办浮层同款）
    BackHandler {
        if (imeInsets.getBottom(density) > 0) keyboard?.hide() else onDismiss()
    }

    // 截止解析的基准量：第 1 周 = 开学所在日历周（周一），周号公式与课表页 todayWeek 同源
    val today = LocalToday
    val termStartMonday = schedule.termStart.mondayOfWeek()
    val currentWeek = (((today.mondayOfWeek() - termStartMonday) / 7).toInt() + 1)
    val totalWeeks = schedule.totalWeeks
    // 本节课开始分钟：节次缺失/时间非法时回退 8:00（同截止设置浮层的播种值口径）
    val slotStartMinute = schedule.slots.getOrNull(entry.startSlot - 1)
        ?.let { TimeText.parseMinutes(it.startTime) }
        ?: DEFAULT_DEADLINE_MINUTE
    // 「下节课」挂载时算一次即可（浮层存续期间"下一次"不会变）；null = 本学期已结课
    val nextOccurrence = remember(entry.id, schedule, today) {
        nextCourseOccurrence(entry, schedule, today, currentMinuteOfDay())
    }
    // 周数滚轮范围：本周数（开学前夹到第 1 周）..学期总周数；学期后退化为末周单点
    val weekRange = currentWeek.coerceIn(1, totalWeeks)..totalWeeks

    var mode by remember {
        mutableStateOf(if (nextOccurrence == null) DeadlineMode.Custom else DeadlineMode.NextClass)
    }
    var customWeek by remember { mutableIntStateOf(currentWeek.coerceIn(1, totalWeeks)) }

    // 选择菜单（Popup 独立窗口，无需参与分层动效）与周数滚轮弹卡：期望态 + 挂载态
    // 两段式；滚轮声明在 OverlaySheet 之后（组合顺序即绘制顺序），在场时经 covered
    // 让本浮层缩小后退（同截止设置浮层的滚轮弹卡约定）
    var menuVisible by remember { mutableStateOf(false) }
    var weekWheelVisible by remember { mutableStateOf(false) }
    var weekWheelMounted by remember { mutableStateOf(false) }

    // haze 采样源自建（同截止设置浮层：不共享详情浮层的），交给本浮层、选择菜单与
    // 滚轮弹卡——顶栏磨砂与叠层弹层的背景模糊同源
    val sheetHazeState = rememberHazeState()

    OverlaySheet(
        title = stringResource(R.string.course_todo_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消在左（同新建待办浮层的顶栏排布）
        confirmAtEnd = true,
        onConfirm = {
            if (name.isBlank()) {
                nameBlank = true
            } else {
                // 确认时才把档位解析成具体截止：下节课 = 扫描结果；自定义 = 所选周的
                // 本节课日 + 开始时间。nextOccurrence 为 null 时档位锁在自定义（菜单项
                // 置灰切不过去），deadline 恒非空；let 仅作类型收窄的兜底
                val deadline = when (mode) {
                    DeadlineMode.NextClass -> nextOccurrence
                    DeadlineMode.Custom -> CourseOccurrence(
                        date = termStartMonday + (customWeek - 1) * 7 + (entry.dayOfWeek - 1),
                        startMinute = slotStartMinute,
                    )
                }
                deadline?.let {
                    onConfirm(
                        Todo(id = "", name = name.trim()).copy(
                            deadlineDate = it.date,
                            deadlineMinute = it.startMinute,
                            // 自动打开倒计时：恒有截止，仓库归一化（countdown && 有截止）不会回收
                            countdown = true,
                            // 自动打上本节课程的课程标签（= 课程名）
                            tags = listOf(entry.name),
                        ),
                    )
                }
            }
        },
        rightAction = SheetTopAction(
            label = stringResource(R.string.settings_cancel),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        covered = weekWheelVisible,
        onDismissed = onDismissed,
        hazeState = sheetHazeState,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 待办名是孤立的输入卡：无题 CardSection 收拢（新建待办浮层同款）
            CardSection {
                SheetTextField(
                    label = stringResource(R.string.todo_name_label),
                    value = name,
                    onValueChange = {
                        name = it
                        nameBlank = false
                    },
                    placeholder = stringResource(R.string.todo_name_hint),
                    imeAction = ImeAction.Done,
                    // 键盘「完成」收起键盘，创建走右上「确认」
                    onImeAction = { focusManager.clearFocus() },
                    isError = nameBlank,
                )
            }
            // 截止日期分组：入口行（点行弹选择菜单）+「自定义」档位展开的周次行
            CardSection(title = stringResource(R.string.deadline_card_label)) {
                Box {
                    SettingsCardwithIcon(
                        items = listOf(
                            SettingsCardwithIconItem(
                                icon = R.drawable.ic_clock_alarm,
                                label = stringResource(R.string.deadline_card_label),
                                value = stringResource(
                                    if (mode == DeadlineMode.NextClass) {
                                        R.string.course_todo_deadline_next
                                    } else {
                                        R.string.course_todo_deadline_custom
                                    },
                                ),
                                // 尾部上下箭头：提示「点行弹菜单」而非「进入下一层」（选择行同款）
                                trailingIcon = R.drawable.ic_chevron_up_down,
                                onClick = {
                                    // 弹菜单前收键盘（入口行进弹层的通用约定）
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    menuVisible = true
                                },
                            ),
                        ),
                    )
                    PopupMenuPopup(
                        expanded = menuVisible,
                        onDismiss = { menuVisible = false },
                        sections = listOf(
                            PopupMenuSection(
                                items = listOf(
                                    PopupMenuItem(
                                        label = stringResource(R.string.course_todo_deadline_next),
                                        checked = mode == DeadlineMode.NextClass,
                                        // 本学期已结课：置灰锁定（negative 色、不可点）
                                        enabled = nextOccurrence != null,
                                        onClick = {
                                            menuVisible = false
                                            mode = DeadlineMode.NextClass
                                        },
                                    ),
                                    PopupMenuItem(
                                        label = stringResource(R.string.course_todo_deadline_custom),
                                        checked = mode == DeadlineMode.Custom,
                                        onClick = {
                                            menuVisible = false
                                            mode = DeadlineMode.Custom
                                        },
                                    ),
                                ),
                            ),
                        ),
                        hazeState = sheetHazeState,
                        // 锚点 = 整张入口行卡：菜单卡右缘对齐行卡右缘、顶边贴行顶边；
                        // 锚点贴屏幕右缘，生长角取右上
                        menuGrowth = MenuGrowth.TopRight,
                        cardPosition = { anchorBounds, _, cardSize ->
                            IntOffset(anchorBounds.right - cardSize.width, anchorBounds.top)
                        },
                    )
                }
                // 「自定义」档位展开的周次行：独立白卡（组内间距 CardSection 默认 10dp，
                // 与设计稿一致）；展开/收起走高度动画（同截止设置浮层的日期/时刻卡）
                Expandable(expanded = mode == DeadlineMode.Custom) {
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.course_todo_deadline_custom),
                                description = stringResource(R.string.course_todo_custom_desc),
                                value = stringResource(R.string.week_format, customWeek),
                                onClick = {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    weekWheelMounted = true
                                    weekWheelVisible = true
                                },
                            ),
                        ),
                    )
                }
            }
        }
    }

    // 周数滚轮弹卡：范围 本周数..学期总周数，初值夹紧进范围防旧值越界（两段式，同
    // 截止设置浮层的「开始前」天数轮）
    if (weekWheelMounted) {
        BottomNumberSelectCard(
            visible = weekWheelVisible,
            onDismiss = { weekWheelVisible = false },
            onDismissed = { weekWheelMounted = false },
            title = stringResource(R.string.weeks_select_title),
            valueRange = weekRange,
            initialValue = customWeek.coerceIn(weekRange.first, weekRange.last),
            unit = stringResource(R.string.unit_week),
            hazeState = sheetHazeState,
            onConfirm = { week ->
                customWeek = week
                weekWheelVisible = false
            },
        )
    }
}

/** 截止档位：下节课（下一次上课的开始时刻）或自定义周次（该周本节课开始时刻） */
private enum class DeadlineMode { NextClass, Custom }

/** 新建课程待办浮层快速预览（Android Studio 中直接查看） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 720)
@Composable
private fun CourseTodoSheetPreview() = ClassppTheme {
    CourseTodoSheet(
        visible = true,
        entry = CourseEntry(
            id = "course-1",
            name = "微积分 I-2",
            teacher = "张三",
            location = "教学楼 101",
            dayOfWeek = 3,
            startSlot = 2,
            weeks = WeekPattern.everyWeek(16),
            color = CourseColor.Blue,
        ),
        schedule = Schedule(
            id = "schedule-1",
            name = "2026 春季学期",
            termStart = IsoDate.of(2026, 3, 2),
            termEnd = IsoDate.of(2026, 6, 21),
            courses = emptyList(),
        ),
        onConfirm = {},
        onDismiss = {},
        onDismissed = {},
    )
}
