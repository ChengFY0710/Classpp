package com.fangyi.classpp.ui.schedule

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.OpResult
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.DEFAULT_SLOT_DURATION_MINUTES
import com.fangyi.classpp.data.model.DEFAULT_TIME_SCHEME_ID
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeScheme
import com.fangyi.classpp.ui.components.BottomDateSelectCard
import com.fangyi.classpp.ui.components.BottomNumberSelectCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.RowChoiceCard
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.settings.TermDatesCard
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

/**
 * 「课表设置」浮层（编辑栏按钮打开）——原课表设置页的课表级设置整体迁入：
 * 课表名 / 学期起止 / 每周天数 / 节数与时间 / 显示非本周开关。
 *
 * 容器为 [OverlaySheet]：进出场动画、顶栏拖拽关闭与顶栏渐变模糊都由它提供。
 * 设置即改即提交（注入的 [repository]，Err 走系统 Toast，不占版面），但整个浮层只在
 * 编辑态可开——改动处于编辑态事务内：保存则保留，取消随整库快照一并回滚。
 * 无确认/取消语义，故无左确认胶囊，右上只留「关闭」。
 *
 * 「节数与时间」本身不再内联：分组内只有一行「课程时间方案」入口（附当前方案名），
 * 点击叠开 [TimeSchemeSheet]（子浮层，经 [OverlaySheet.covered] 让本浮层缩小后退）；
 * 子浮层的明细改动先落草稿，右上「确认」才整套写回本课表；保存方案则是独立落库，
 * 浮层内确认即持久化，不随编辑事务的保存/取消回滚。
 *
 * 合并两小节会同时改写节表与课程映射：[mergeCourses] 是合并输入（编辑态传课程草稿，
 * 保证网格看到的就是被合并的课程），确认成功后再经 [onCoursesMerged] 把重映射后的
 * 课程同步回草稿——否则后续「保存编辑」会用旧 span 覆盖合并结果。
 *
 * 确认时的课程口径同样取 [mergeCourses]：用户刚调的结束节次只落在课程草稿里，若拿仓库
 * 已保存的旧跨度做越界判定，「草稿改短 + 缩节数」会被误报成课程冲突。草稿与已保存课程
 * 不同时，节表、时长与课程同批校验、同批落库，避免留下「节数已缩小、课程还伸到旧末节」
 * 的非法中间态。
 *
 * 滚动、键盘让位（课表名输入框在场 → ContentScroll）由 OverlaySheet 内建，
 * 这里只负责内容与日期/时间对话框。内容列按浮层规范排版：顶层块一律 [CardSection]——
 * 课表名是孤立的输入卡、收进无题分组，其余是带题分组；顶层块间距走
 * [SheetSectionSpacingBetween]（12dp，改走浮层节奏，不再与设置页面的 18dp 分组间距同步）。
 */
@Composable
internal fun ScheduleSettingsSheet(
    schedule: Schedule,
    // 当前有效课程（编辑态 = 课程草稿，非编辑态 = 仓库课程）：合并输入，
    // 也是确认时课程越界校验与同批落库的口径
    mergeCourses: List<CourseEntry>,
    repository: ScheduleRepository,
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    // 合并落库成功后把重映射后的课程同步回编辑草稿
    onCoursesMerged: (List<CourseEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 错误一律走系统 Toast，不占版面；同一条出口会刷新上一条，连点不排队（见 AppToasts）
    fun showError(error: ScheduleError) {
        AppToasts.show(context, error.toMessage(context))
    }

    // 统一提交入口：Err 即时 toast，Ok 无事
    fun submit(result: OpResult) {
        (result as? OpResult.Err)?.let { showError(it.error) }
    }

    // 返回键关浮层（同切换课表浮层：组合顺序上晚于编辑态的取消 BackHandler，优先接管）
    BackHandler(enabled = true) { onDismiss() }

    // 学期起止日期滚轮弹窗（BottomDateSelectCard）：期望态 + 挂载态两段式（同新建待办
    // 的各滚轮弹窗）——弹窗在场时经 covered 让本浮层缩小后退（分层动效）
    var pickerVisible by remember { mutableStateOf(false) }
    var pickerMounted by remember { mutableStateOf(false) }
    var pickerInitial by remember { mutableStateOf(IsoDate.today()) }
    var pickerCallback by remember { mutableStateOf<((IsoDate) -> Unit)?>(null) }

    // 学期周数横向滚轮弹窗（BottomNumberSelectCard）：期望态 + 挂载态两段式（同日期轮）；
    // 初值在请求时快照（挂载期间本浮层的起止日不会再变）
    var weeksVisible by remember { mutableStateOf(false) }
    var weeksMounted by remember { mutableStateOf(false) }
    var weeksInitial by remember { mutableIntStateOf(TERM_WEEKS_MIN) }

    // 「课程时间方案」子浮层：挂载与可见性分离（同其他叠层），可见期间本浮层缩小后退
    var schemeSheetVisible by remember { mutableStateOf(false) }
    var schemeSheetMounted by remember { mutableStateOf(false) }

    // 全局方案库：入口行显示的当前方案名按「当前配置 == 某方案」反查，未命中即自定义
    val timeSchemes by repository.timeSchemes.collectAsState()
    val defaultSchemeName = stringResource(R.string.time_scheme_default)
    val customSchemeName = stringResource(R.string.time_scheme_custom)
    val currentSchemeName = remember(
        schedule.slots,
        schedule.slotDurationMinutes,
        timeSchemes,
        defaultSchemeName,
        customSchemeName,
    ) {
        val defaultScheme = TimeScheme(
            id = DEFAULT_TIME_SCHEME_ID,
            name = defaultSchemeName,
            slots = DEFAULT_SLOTS,
            slotDurationMinutes = DEFAULT_SLOT_DURATION_MINUTES,
        )
        (listOf(defaultScheme) + timeSchemes).firstOrNull {
            it.slots == schedule.slots &&
                it.slotDurationMinutes == schedule.slotDurationMinutes
        }?.name ?: customSchemeName
    }

    // 设置内容的 haze 采样源：OverlaySheet 顶栏磨砂用，叠在其上的学期日期/节次时刻
    // 滚轮弹窗也拿它做卡片磨砂背景模糊——同一状态同源采样
    val sheetHazeState = rememberHazeState()

    OverlaySheet(
        title = stringResource(R.string.settings_title),
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
        covered = pickerVisible || weeksVisible || schemeSheetVisible,
        onDismissed = onDismissed,
        imeBehavior = SheetImeBehavior.ContentScroll,
        // 共享采样源：叠在本浮层上的两枚滚轮弹窗拿它做卡片磨砂背景模糊（见 sheetHazeState 声明处）
        hazeState = sheetHazeState,
        modifier = modifier,
    ) {
        SettingsContent(
            schedule = schedule,
            timeSchemeName = currentSchemeName,
            // 学期起止步：挂载日期轮（画在本浮层之后），初值由设置卡给出
            onDatePickRequest = { initialDate, onPicked ->
                pickerInitial = initialDate
                pickerCallback = onPicked
                pickerMounted = true
                pickerVisible = true
            },
            // 学期周数：挂载横向滚轮弹窗（画在本浮层之后），初值 = 当前起止日的日历周数
            onPickWeeks = {
                weeksInitial = totalWeeks(schedule.termStart, schedule.termEnd)
                weeksMounted = true
                weeksVisible = true
            },
            // 课程时间方案：叠开子浮层（内容与提交都在子浮层内完成）
            onOpenTimeScheme = {
                schemeSheetMounted = true
                schemeSheetVisible = true
            },
            onRename = { name ->
                scope.launch { submit(repository.renameSchedule(schedule.id, name)) }
            },
            onTerm = { start, end ->
                scope.launch { submit(repository.setTerm(schedule.id, start, end)) }
            },
            onDays = { days ->
                // 只改天数：周六/周日的课保留在数据里，5 天视图只是不画它们，
                // 切回 7 天原样出现——故切换不会失败，也无需联动改学期结束日
                scope.launch { submit(repository.setDaysPerWeek(schedule.id, days)) }
            },
            onShowInactive = { show ->
                scope.launch { submit(repository.setShowInactiveCourses(schedule.id, show)) }
            },
        )
    }

    // 课程时间方案子浮层：叠在本浮层之上（后注册因此先收返回键）。
    // 确认 → 整套提交（成功才关，失败 toast 并保留草稿）；取消/返回丢弃草稿。
    // 保存方案 → 独立落库，成功后经 timeSchemes 流回到子浮层顶部列表
    if (schemeSheetMounted) {
        TimeSchemeSheet(
            schedule = schedule,
            courses = mergeCourses,
            timeSchemes = timeSchemes,
            visible = schemeSheetVisible,
            onDismiss = { schemeSheetVisible = false },
            onDismissed = { schemeSheetMounted = false },
            onApply = { slots, duration, mergedCourses ->
                val coursesChanged = mergedCourses != null && mergedCourses != schedule.courses
                val settingsChanged = slots != schedule.slots ||
                    duration != schedule.slotDurationMinutes
                // 草稿与当前配置一致（只是看了看）→ 直接关，不做无意义的落盘
                if (!coursesChanged && !settingsChanged) {
                    schemeSheetVisible = false
                } else {
                    // 校验与提交都用「当前有效课程」：编辑态是课程草稿（用户刚调的结束节次
                    // 只落在草稿里），仓库中已保存的旧跨度不参与判定——否则草稿已把课改短、
                    // 仓库还停在旧末节时，缩节数会被旧数据误判成课程越界
                    val effectiveCourses = mergedCourses ?: mergeCourses
                    scope.launch {
                        val result = if (effectiveCourses == schedule.courses) {
                            // 与已保存课程一致：只动设置
                            repository.setSlotsAndDuration(schedule.id, slots, duration)
                        } else {
                            // 合并改写课程映射 / 草稿与已保存课程不同：节表、时长与课程
                            // 一次校验、一次提交——否则会留下「节数已缩小、课程还伸到旧末节」
                            // 的非法中间态（切换课表会丢弃草稿，该态无人修正）
                            repository.setSlotsDurationAndCourses(
                                schedule.id,
                                slots,
                                duration,
                                effectiveCourses,
                            )
                        }
                        when (result) {
                            OpResult.Ok -> {
                                mergedCourses?.let(onCoursesMerged)
                                schemeSheetVisible = false
                            }
                            is OpResult.Err -> showError(result.error)
                        }
                    }
                }
            },
            onSaveScheme = { name, slots, duration ->
                scope.launch { submit(repository.addTimeScheme(name, slots, duration)) }
            },
            // 方案库的改名/删除同样即时落库（不参与课表编辑事务，见 restoreEditBaseline）
            onRenameScheme = { id, name ->
                scope.launch { submit(repository.renameTimeScheme(id, name)) }
            },
            onDeleteScheme = { id ->
                scope.launch { submit(repository.deleteTimeScheme(id)) }
            },
        )
    }

    // 学期日期滚轮弹窗画在浮层之后（组合顺序即绘制顺序）；确认沿用同一套联动：
    // 开始日任意一天可选、平移结束日保持学期长度；结束日同样任意一天都合法
    if (pickerMounted) {
        BottomDateSelectCard(
            visible = pickerVisible,
            onDismiss = { pickerVisible = false },
            onDismissed = {
                pickerMounted = false
                pickerCallback = null
            },
            hazeState = sheetHazeState,
            initialDate = pickerInitial,
            onConfirm = { picked ->
                pickerVisible = false
                pickerCallback?.invoke(picked)
                pickerCallback = null
            },
        )
    }

    // 学期周数横向滚轮弹窗（BottomNumberSelectCard）画在浮层之后（组合顺序即绘制顺序）；
    // 确认把结束日整周平移到目标周数（endForTotalWeeks）并整表提交仓库（学期起止同一条
    // 提交通道），取消/遮罩/返回只请求关闭，播完退场才卸载（两段式，同日期轮）
    if (weeksMounted) {
        BottomNumberSelectCard(
            visible = weeksVisible,
            onDismiss = { weeksVisible = false },
            onDismissed = { weeksMounted = false },
            title = stringResource(R.string.weeks_select_title),
            valueRange = TERM_WEEKS_MIN..TERM_WEEKS_MAX,
            initialValue = weeksInitial,
            unit = stringResource(R.string.unit_week),
            hazeState = sheetHazeState,
            onConfirm = { weeks ->
                weeksVisible = false
                scope.launch {
                    submit(
                        repository.setTerm(
                            schedule.id,
                            schedule.termStart,
                            endForTotalWeeks(schedule.termStart, schedule.termEnd, weeks),
                        ),
                    )
                }
            },
        )
    }
}

/** 当前课表全套设置：课表名 / 学期 / 每周天数 / 节数与时间 / 显示。数据变更经回调即时提交仓库。 */
@Composable
private fun SettingsContent(
    schedule: Schedule,
    // 入口行显示的当前方案名（默认 / 已保存方案 / 自定义），由宿主反查
    timeSchemeName: String,
    // 学期起止步上报：初值为当前起止日，[onPicked] 收确认日期后经 [onTerm] 提交仓库；
    // 弹窗本体（BottomDateSelectCard）由 ScheduleSettingsSheet 组合在浮层之后
    onDatePickRequest: (initialDate: IsoDate, onPicked: (IsoDate) -> Unit) -> Unit,
    // 学期周数步上报：挂载周数滚轮弹窗（BottomNumberSelectCard，画在本浮层之后）
    onPickWeeks: () -> Unit,
    // 课程时间方案：叠开子浮层（明细与提交都在子浮层内完成）
    onOpenTimeScheme: () -> Unit,
    onRename: (name: String) -> Unit,
    onTerm: (start: IsoDate, end: IsoDate) -> Unit,
    onDays: (days: Int) -> Unit,
    onShowInactive: (show: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 滚动由 OverlaySheet 的内容列承担，这里只排内容；顶层块间距 = 浮层统一值（12dp）
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween),
    ) {
        // 课表名是孤立的输入卡：无题 CardSection 收拢（SheetTextField 自带「课表名称」标签，
        // 不需要分组标题）
        CardSection {
            ScheduleNameField(name = schedule.name, onRename = onRename)
        }

        // TermDatesCard 自带分组与卡片，勿再包一层（否则标题重复）
        TermDatesCard(
            start = schedule.termStart,
            end = schedule.termEnd,
            // 改开始日 → 平移结束日保持学期长度（星期几不变）；结束日任意一天都合法
            //（可停在周中，末周就只上到那一天）
            onPickStart = {
                onDatePickRequest(schedule.termStart) { picked ->
                    onTerm(picked, schedule.termEnd + (picked - schedule.termStart).toInt())
                }
            },
            onPickEnd = {
                onDatePickRequest(schedule.termEnd) { picked -> onTerm(schedule.termStart, picked) }
            },
            onPickWeeks = onPickWeeks,
            onSetWeeks = { weeks ->
                onTerm(schedule.termStart, endForTotalWeeks(schedule.termStart, schedule.termEnd, weeks))
            },
        )

        CardSection(title = stringResource(R.string.section_days)) {
            // RowChoiceCard 自带白卡与滑动蓝底动效，无需再套 SettingsCard；
            // selectedIndex 由状态推导恒非空 → 必有选中、无“再点取消”交互（点已选项 = 写回同值）
            RowChoiceCard(
                options = listOf(
                    stringResource(R.string.days_5),
                    stringResource(R.string.days_7),
                ),
                selectedIndex = if (schedule.daysPerWeek == 7) 1 else 0,
                onSelect = { index -> onDays(if (index == 1) 7 else 5) },
            )
        }

        CardSection(title = stringResource(R.string.section_slots)) {
            // 「节次与时间」整体迁入独立浮层：这里只留一行入口（当前方案名 + 箭头），
            // 点击叠开 TimeSchemeSheet；明细修改在子浮层内草稿化，确认才写回本课表
            SettingsCard(
                items = listOf(
                    SettingsCardItem.Nav(
                        label = stringResource(R.string.time_scheme_title),
                        description = stringResource(R.string.time_scheme_entry_desc),
                        value = timeSchemeName,
                        onClick = onOpenTimeScheme,
                    ),
                ),
            )
        }

        CardSection(title = stringResource(R.string.section_adjustment)) {
            // 调课 / 调休入口：占位导航行（暂无功能，点击不响应），接入时把 onClick
            // 换成各自的挂载请求回调
            SettingsCard(
                items = listOf(
                    SettingsCardItem.Nav(
                        label = stringResource(R.string.adjust_course_label),
                        onClick = {},
                    ),
                    SettingsCardItem.Nav(
                        label = stringResource(R.string.adjust_holiday_label),
                        onClick = {},
                    ),
                ),
            )
        }

        CardSection(title = stringResource(R.string.section_display)) {
            // 新版 SettingsCard 开关行：整行可点切换（涟漪铺满整行），说明文字在卡内 label 下方
            SettingsCard(
                items = listOf(
                    SettingsCardItem.Toggle(
                        label = stringResource(R.string.show_inactive),
                        description = stringResource(R.string.show_inactive_desc),
                        checked = schedule.showInactiveCourses,
                        onCheckedChange = onShowInactive,
                    ),
                ),
            )
        }
    }

}

/**
 * 课表名输入（浮层首项）：[SheetTextField] 卡片 + 本地草稿。
 * 焦点离开（点别处或键盘「完成」收起）时提交：清空视为放弃、静默回当前名；
 * 名字有变才回调 [onRename]——仓库校验失败走统一 toast，输入框保留用户文字便于修改。
 */
@Composable
private fun ScheduleNameField(name: String, onRename: (String) -> Unit) {
    // 草稿以当前名为 key：改名成功/切课表导致 name 变化时草稿归位，旋转重建同 current
    var draft by remember(name) { mutableStateOf(name) }
    var hadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    SheetTextField(
        label = stringResource(R.string.schedule_name_label),
        value = draft,
        onValueChange = { draft = it },
        placeholder = stringResource(R.string.schedule_name_hint),
        imeAction = ImeAction.Done,
        // 键盘「完成」收起焦点 → 走下面的失焦提交
        onImeAction = { focusManager.clearFocus() },
        modifier = Modifier.onFocusEvent { state ->
            if (state.hasFocus) {
                hadFocus = true
            } else if (hadFocus) {
                hadFocus = false
                val trimmed = draft.trim()
                when {
                    trimmed.isEmpty() -> draft = name
                    trimmed != name -> onRename(trimmed)
                }
            }
        },
    )
}

/**
 * [ScheduleError] → 本地化文案。课程类错误正常都经仓库 rejection() 归并为 CoursesOutOfRange，
 * 但兜底分支同样给中文结论，绝不把内部 id / 英文 message 直接显示给用户。
 * 非 Composable（context 版）：供 showError 在回调/协程里调用，结果走系统 Toast。
 * 设置页的「新建课表」空态同样复用（创建失败的出口）。
 */
internal fun ScheduleError.toMessage(context: Context): String = when (this) {
    // 起止日任意星期几都合法（开学日不再要求周一），只剩"早于开始日"一种非法
    is ScheduleError.TermRangeInvalid -> context.getString(R.string.error_term_range)
    is ScheduleError.DaysPerWeekInvalid -> context.getString(R.string.error_days_per_week)
    is ScheduleError.SlotCountInvalid -> context.getString(R.string.error_slot_count)
    is ScheduleError.SlotTimeFormatInvalid -> context.getString(R.string.error_slot_time_format)
    is ScheduleError.SlotOrderInvalid -> context.getString(R.string.error_slot_order)
    ScheduleError.InvalidScheduleName -> context.getString(R.string.error_schedule_name)
    ScheduleError.InvalidTimeSchemeName -> context.getString(R.string.error_time_scheme_name)
    is ScheduleError.CoursesOutOfRange -> context.getString(
        R.string.error_courses_out_of_range,
        affected.size,
        affected.take(3).joinToString { it.name },
    )

    is ScheduleError.NotFound -> context.getString(R.string.error_not_found)
    is ScheduleError.PersistFailed -> context.getString(R.string.error_persist_failed)
    // 真正没覆盖到的形态：不暴露内部 message，只给通用提示（细节进日志由调用方决定）
    else -> context.getString(R.string.error_unexpected)
}

@Preview(showBackground = true, name = "课表设置内容")
@Composable
private fun SettingsContentPreview() {
    ClassppTheme {
        SettingsContent(
            schedule = Schedule(
                id = "preview",
                name = "2026春",
                termStart = IsoDate.parse("2026-03-02"),
                termEnd = IsoDate.parse("2026-06-19"),
            ),
            timeSchemeName = "默认",
            onDatePickRequest = { _, _ -> },
            onPickWeeks = {},
            onOpenTimeScheme = {},
            onRename = {},
            onTerm = { _, _ -> },
            onDays = {},
            onShowInactive = {},
        )
    }
}
