package com.fangyi.classpp.ui.schedule

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.EditTransitionMillis
import com.fangyi.classpp.R
import com.fangyi.classpp.data.FieldReason
import com.fangyi.classpp.data.OpResult
import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.ScheduleValidator
import com.fangyi.classpp.data.TermPosition
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.cellCourses
import com.fangyi.classpp.ui.navigation.NavReserve
import com.fangyi.classpp.ui.theme.ClassppTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * 课表页：可折叠头部（顶栏行/日期行/星期行）+ 课表网格。
 * 折叠状态由 [CollapseState] 的 NestedScrollConnection 驱动，跟手折叠/展开。
 *
 * 网格与顶栏为叠层（Box）：网格铺满全屏并经 contentPadding.top 让位于顶栏，
 * 折叠后内容从顶栏背后滚过，顶栏通过 Haze 对其做背景模糊（随日期带渐隐同步渐入）。
 *
 * 网格内容区是横向 Pager（每周一页，见 [CourseGrid]）：左右滑动跟手翻上一周/下一周，
 * 顶栏是叠层里的独立一层，翻页期间位置与内容均不动，只在拖动过半时随周次更新文案。
 *
 * [editing] 为编辑态：顶栏换成固定编辑栏（保存 / 切换课表 / 取消），日期带隐藏、翻周停用、
 * 底部导航栏由调用方隐藏；空位渲染添加卡片，点它开弹窗加课。改动只落进
 * [ScheduleEditSession] 的草稿，点保存才写回仓库，取消（或返回键）整份丢弃。
 *
 * 数据来自 [repository]（null = 尚未加载完成，显示指示器）；
 * 无激活课表时显示空状态，经 [onOpenSettings] 引导至设置页新建。
 */
@Composable
fun ScheduleScreen(
    modifier: Modifier = Modifier,
    repository: ScheduleRepository? = null,
    editing: Boolean = false,
    onEditingChange: (Boolean) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    // get() 返回即 bootstrap 完成，此分支仅首帧毫秒级；早返回后下方 smart cast 为非空
    if (repository == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var selectedWeek by rememberSaveable { mutableIntStateOf(0) }
    val today = remember { Date() }

    // State 对象本身稳定（remember），供 Pager 的 pageCount 闭包长期读取；schedule 为当前值
    val scheduleState = repository.activeSchedule.collectAsState()
    val schedule = scheduleState.value

    // 选周：0=未初始化 → 学期中取当前周、学期后取末周、学期前取第 1 周；
    // 学期起止被设置页修改后自动钳制到新范围
    val week = when {
        schedule == null -> 1
        selectedWeek == 0 -> when (val position = repository.termPosition(schedule.id)) {
            is TermPosition.InTerm -> position.week
            TermPosition.AfterTerm -> schedule.totalWeeks
            else -> 1
        }
        else -> selectedWeek.coerceIn(1, schedule.totalWeeks)
    }
    LaunchedEffect(week) {
        if (selectedWeek != week) selectedWeek = week
    }

    // 本周（仅学期中有）：供周数弹窗「本周」方块浅蓝高亮；学期起止变更随 schedule 换新而重算
    val currentWeek: Int? = if (schedule != null) {
        remember(schedule) {
            (repository.termPosition(schedule.id) as? TermPosition.InTerm)?.week
        }
    } else {
        null
    }

    // 顶栏日期：查看本周显示今天，查看其它周（学期外一律算「其它周」）显示该周周一；
    // 头部日期标题与星期高亮均由 date 驱动，随之联动（格式仍为 #月#日 周X）
    val headerDate = if (schedule != null && week != currentWeek) {
        remember(schedule, week) {
            repository.datesForWeek(schedule.id, week).first().toUiDate()
        }
    } else {
        today
    }

    // 节次：结构性数据，随 schedule 换新而变；实例只建一次，避免翻页期三页无谓重组
    val timeSlots = if (schedule != null) {
        remember(schedule) { schedule.slots.toUiSlots() }
    } else {
        emptyList()
    }

    val density = LocalDensity.current
    val maxCollapsePx = with(density) { TopBarHeight.toPx() }
    val collapseState = rememberCollapseState(maxCollapsePx)

    val listState = rememberLazyListState()
    val hazeState = rememberHazeState()

    // 顶栏实测高度（含状态栏 inset）→ 网格 contentPadding.top。
    // 两种模式各存一份：切换模式时立刻拿到上一轮的实测值，内容不会先跳一帧；
    // 首次进入该模式用估算值，onGloballyPositioned 在首帧绘制前即会校正
    var headerHeight by remember { mutableStateOf(HeaderHeightGuess) }
    var editBarHeight by remember { mutableStateOf(EditBarHeightGuess) }
    val topBarHeight = if (editing) editBarHeight else headerHeight
    val bandHeightPx = with(density) { DateBandHeight.toPx() }
    // 与日期带渐隐同步：index==0 时 offset/bandHeight 爬升，带滚过后恒为 1。
    // derivedStateOf 只在输出变化时通知 → 渐隐窗口外零新增重组
    val blurProgress by remember(bandHeightPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                (listState.firstVisibleItemScrollOffset / bandHeightPx).coerceIn(0f, 1f)
            } else {
                1f
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 顶部状态栏 inset 由头部自行吸收；底部不留白，网格直接延伸到导航栏（小白条）之下
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        if (schedule == null) {
            // 空状态：不组合头部与网格，经按钮引导至设置页新建课表
            EmptyScheduleContent(
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            // ——— 内容区横向翻周 ———
            // 初始页即目标周：Pager 只在有课表时创建，避免首帧从第 1 周跳变到当前周。
            // pageCount 刻意读 State 而非局部值：该闭包在创建时被 Pager 捕获，
            // 读局部值会让学期起止修改后的周数变化（如 20 → 25 周）不再生效
            val pagerState = rememberPagerState(initialPage = week - 1) {
                scheduleState.value?.totalWeeks ?: 1
            }

            // 每周页内容（纯函数、无 I/O）：key=schedule 覆盖置灰开关/时段/天数/学期等全部变更源——
            // 任意 mutator 成功都会发布新的 Schedule 实例，记忆随之失效、同帧刷新
            val contentForWeek: (Int) -> WeekPageContent = remember(schedule, currentWeek, today) {
                { pageWeek ->
                    val dates = repository.datesForWeek(schedule.id, pageWeek)
                    WeekPageContent(
                        week = pageWeek,
                        // 先解析再按开关过滤：同格只留当周那张卡，非本周的交替课只剩色条，
                        // 关掉「显示本周不上的课」也照旧看得到这格还有别的课
                        courses = repository.coursesForWeek(schedule.id, pageWeek)
                            .toWeekCards(schedule.showInactiveCourses),
                        // 天数即列数：仓库按 daysPerWeek 返回 5 或 7 个日期，网格与星期行同步列数
                        dates = dates.map { it.toUiDate() },
                        // 与顶栏日期同规则：查看本周高亮今天，其它周高亮该周周一
                        // （越界周 dates 为空时退回今天，页内无日期带可高亮）
                        highlightDate = if (pageWeek == currentWeek) {
                            today
                        } else {
                            (dates.firstOrNull() ?: IsoDate.today()).toUiDate()
                        },
                    )
                }
            }

            // ——— 编辑态：草稿只活在 UI 层，点保存才写回仓库 ———
            // 会话随 editing 重开（进来时取当前课表），退出即换成占位会话、草稿丢弃；
            // Saver 连 active 一起存，故旋转/进程重建不会把恢复出来的草稿清掉。
            // inputs 含 schedule.id：切换激活课表时 inputs 变化 → 旧会话连同草稿一起
            // 被丢弃、以新课表的课程快照重建——切换即隐式"丢弃旧草稿、开始编辑新课表"
            val session: ScheduleEditSession = rememberSaveable(
                editing,
                schedule.id,
                saver = ScheduleEditSession.Saver,
            ) {
                if (editing) ScheduleEditSession(draft = schedule.courses) else ScheduleEditSession.Inactive
            }
            val editSession: ScheduleEditSession? = session.takeIf { it.active }
            // 待添加的格子：[dayOfWeek, slotId]；空 = 添加面板未打开
            var addTarget by rememberSaveable { mutableStateOf(listOf<Int>()) }
            // 正在编辑的课程 id（点已有课卡进入）；空 = 编辑面板未打开。
            // 编辑条目从草稿反查，删除后反查落空 → 面板自动关闭
            var editTargetId by rememberSaveable { mutableStateOf("") }
            // 长按菜单：被长按卡的 id + 窗口坐标；空 = 菜单未打开。
            // 刻意不 rememberSaveable——菜单是瞬时 UI，旋转重建就收起（Rect 也不可存）
            var menuAnchor by remember { mutableStateOf<CardMenuAnchor?>(null) }
            // 选择要编辑的交替课程：被点卡片的 id；空 = 弹窗未打开
            var chooserSourceId by rememberSaveable { mutableStateOf("") }
            // 新建交替课程：源课 id（长按菜单进来）；空 = 面板未打开
            var alternateSourceId by rememberSaveable { mutableStateOf("") }
            // 切换课表浮层：开关、待切换目标（非空 = 脏草稿确认打开）、新建表单态
            // （新建失败走系统 Toast，不再有表单内联错误）
            var switcherVisible by rememberSaveable { mutableStateOf(false) }
            var pendingSwitchId by rememberSaveable { mutableStateOf("") }
            var createMode by rememberSaveable { mutableStateOf(false) }
            // 编辑态的网格数据源：草稿 → 渲染模型（复用仓库路径同一套映射与置灰规则）
            val displayedContentForWeek: (Int) -> WeekPageContent =
                if (editSession != null) {
                    remember(editSession, schedule, currentWeek, today) {
                        { pageWeek ->
                            val dates = repository.datesForWeek(schedule.id, pageWeek)
                            WeekPageContent(
                                week = pageWeek,
                                // 编辑态**一律显示全部课程**（非本周的照旧置灰），不受
                                // 「显示本周不上的课」开关影响：开关关掉时若把它们藏起来，
                                // 用户看不见"占着这一格但本周不上"的课，加课撞上冲突却找不到原因
                                courses = editSession.courses.toWeekCards(pageWeek),
                                dates = dates.map { it.toUiDate() },
                                highlightDate = if (pageWeek == currentWeek) {
                                    today
                                } else {
                                    (dates.firstOrNull() ?: IsoDate.today()).toUiDate()
                                },
                            )
                        }
                    }
                } else {
                    contentForWeek
                }
            val onAddClick: (Int, TimeSlot) -> Unit = remember {
                { day, slot -> addTarget = listOf(day, slot.id) }
            }
            // 点已有课卡：同格只有一门 → 直接开编辑面板；多门（交替课程）→ 先让用户点名要编辑哪门，
            // 否则其余几门永远进不去（它们不在本周的网格上）
            val onEditClick: (String) -> Unit = remember(editSession) {
                { courseId ->
                    val draft = editSession?.courses.orEmpty()
                    val entry = draft.firstOrNull { it.id == courseId }
                    if (entry != null && draft.cellCourses(entry.dayOfWeek, entry.startSlot).size > 1) {
                        chooserSourceId = courseId
                    } else {
                        editTargetId = courseId
                    }
                }
            }
            // 长按已有课卡 → 弹「新建交替课程」菜单，锚点用卡片自己的窗口坐标
            val onCourseLongClick: (String, Rect) -> Unit = remember {
                { courseId, anchor -> menuAnchor = CardMenuAnchor(courseId, anchor) }
            }

            // 手势 → 周次：currentPage 在拖动过半时即翻转，顶栏周数胶囊与日期随之切换；
            // 写回同值时 State 自身忽略，不产生额外重组
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { selectedWeek = it + 1 }
            }

            // 周次 → 翻页：周数弹窗、返回本周、学期范围钳制触发的换周。
            // 相邻周走滑动动画（与手势翻页连贯），跨多周直接落位（与原先的瞬时换周一致）
            LaunchedEffect(week) {
                val target = week - 1
                val current = pagerState.currentPage
                if (current != target) {
                    if (abs(current - target) == 1) {
                        pagerState.animateScrollToPage(target)
                    } else {
                        pagerState.scrollToPage(target)
                    }
                }
            }

            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            // 点顶栏星期行 = 5 天 / 7 天视图互切（等价于设置页的「每周上课天数」）。
            // 只改天数：周六/周日的课保留在数据里、5 天视图只是不画它们，切回 7 天原样出现，
            // 故切换永远不会失败，也不必弹任何提示。
            val onToggleDaysPerWeek: () -> Unit = {
                val targetDays = if (schedule.daysPerWeek == 7) 5 else 7
                scope.launch { repository.setDaysPerWeek(schedule.id, targetDays) }
            }

            // 保存：先拿"草稿 + 快照"整份预检（给用户可读的原因），再整份写回仓库。
            //
            // 必须整份替换，不能按 diff 逐条 upsert：逐条写在中间态短暂非法时就整轮失败，
            // 而合法的草稿常常必然经过非法中间态——典型的是"把原课周数缩成单周 + 同格再加一门
            // 双周的交替课"：先写新课那一步，仓库里的原课还占着全部周数，于是弹出一场
            // 最终并不存在的"与「原课」时间冲突"。
            val onSaveEdit: () -> Unit = {
                val session = editSession
                if (session != null) {
                    val blocking = ScheduleValidator
                        .validateCourses(schedule.copy(courses = session.courses))
                        .firstOrNull()
                    if (blocking != null) {
                        Toast.makeText(
                            context,
                            blocking.toEditMessage(context),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        scope.launch {
                            val result = repository.replaceCourses(schedule.id, session.courses)
                            if (result is OpResult.Err) {
                                Toast.makeText(
                                    context,
                                    result.error.toEditMessage(context),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                onEditingChange(false)
                            }
                        }
                    }
                }
            }

            // 切换激活课表：关浮层、清脏确认，并把依赖旧草稿的瞬时状态全部复位——
            // 旧草稿的格子/编辑目标 id 在新课表里没有意义；selectedWeek 归零让选周逻辑
            // （week 计算）重新按新课表的学期位置落周
            val performSwitch: (String) -> Unit = { id ->
                pendingSwitchId = ""
                switcherVisible = false
                createMode = false
                addTarget = emptyList()
                editTargetId = ""
                chooserSourceId = ""
                alternateSourceId = ""
                menuAnchor = null
                selectedWeek = 0
                scope.launch {
                    // 先关浮层再落盘：id 取自列表故 NotFound 不可达，落盘失败提示后重开浮层即可重试
                    val r = repository.setActiveSchedule(id)
                    if (r is OpResult.Err) {
                        Toast.makeText(
                            context,
                            r.error.toEditMessage(context),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            // 统一切换入口（行点击 / 导入 / 新建成功）：草稿有改动先弹丢弃确认
            val requestSwitch: (String) -> Unit = { id ->
                if (id != schedule.id) {
                    if (editSession != null && editSession.courses != schedule.courses) {
                        pendingSwitchId = id
                    } else {
                        performSwitch(id)
                    }
                }
            }
            // 导出：当前激活课表 → pretty JSON → 系统分享面板。
            // 读的是仓库已保存状态，草稿里未保存的改动不包含在内（已接受的限制）
            val onExport: () -> Unit = {
                scope.launch {
                    when (val r = repository.exportSchedule(schedule.id)) {
                        is ReadResult.Ok -> {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_TEXT, r.value)
                            }
                            try {
                                context.startActivity(Intent.createChooser(send, null))
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.error_unexpected),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        is ReadResult.Err -> Toast.makeText(
                            context,
                            r.error.toEditMessage(context),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            // 新建：成功先回列表态再走切换（脏则弹确认；取消确认也不会停在表单里重复点创建）；
            // 失败走系统 Toast（新建失败多为重名/名称非法，表单内容无需改动引导）
            val onCreateConfirm: (String, IsoDate, IsoDate) -> Unit = { name, start, end ->
                scope.launch {
                    when (val r = repository.createSchedule(name, start, end)) {
                        is ReadResult.Ok -> {
                            createMode = false
                            requestSwitch(r.value)
                        }
                        is ReadResult.Err -> AppToasts.show(context, r.error.toEditMessage(context))
                    }
                }
            }
            // 删除课表：仓库删除（删激活项自动回落到剩余第一张，全部数据层校验已就绪）；
            // 删的是当前激活课表时周选择归零防越界（同 performSwitch 的复位思路）；
            // 删空则关浮层回无课表空态（新建课表页）
            val onDeleteSchedule: (String) -> Unit = { id ->
                scope.launch {
                    when (val r = repository.deleteSchedule(id)) {
                        is OpResult.Ok -> {
                            if (id == schedule.id) selectedWeek = 0
                            if (repository.schedules.value.isEmpty()) {
                                switcherVisible = false
                                createMode = false
                            }
                        }
                        is OpResult.Err -> AppToasts.show(context, r.error.toEditMessage(context))
                    }
                }
            }
            // 导入：系统文件选择器读 JSON → 仓库导入（新 id、名称去重、不自动激活）→
            // 走统一切换路径（含脏确认）；取消确认则新课表留在列表里但不激活
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        val json = try {
                            context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()?.use { it.readText() }
                        } catch (_: Exception) {
                            null
                        }
                        if (json == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.import_read_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            when (val r = repository.importSchedule(json)) {
                                is ReadResult.Ok -> requestSwitch(r.value)
                                is ReadResult.Err -> Toast.makeText(
                                    context,
                                    r.error.toEditMessage(context),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
            }

            // 编辑态的返回键 = 取消（保存有独立按钮，故这里直接丢弃草稿）
            BackHandler(enabled = editSession != null) { onEditingChange(false) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                CourseGrid(
                    pagerState = pagerState,
                    timeSlots = timeSlots,
                    contentForWeek = displayedContentForWeek,
                    state = listState,
                    // 滚动到底时最后一行可停在导航栏胶囊上方，网格背景仍铺满屏幕底缘；
                    // top 跟随顶栏高度，折叠期视觉与原先 Column 上推一致
                    contentPadding = PaddingValues(
                        top = topBarHeight,
                        bottom = WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom)
                            .asPaddingValues()
                            .calculateBottomPadding() + NavReserve,
                    ),
                    editMode = editSession != null,
                    // 编辑态没有日期层（设计稿里日期带消失），页高相应少一条
                    showDates = editSession == null,
                    // 列数：5 天课表 5 列、7 天课表 7 列（含周六/周日），与顶栏星期行/日期带同源
                    daysPerWeek = schedule.daysPerWeek,
                    onAddClick = onAddClick,
                    onEditClick = onEditClick,
                    onCourseLongClick = onCourseLongClick,
                    modifier = Modifier
                        .fillMaxSize()
                        // 编辑态不折叠：顶栏换成了固定编辑栏，滚动连接不参与
                        .then(
                            if (editSession == null) {
                                Modifier.nestedScroll(collapseState.nestedScrollConnection)
                            } else {
                                Modifier
                            },
                        )
                        .hazeSource(hazeState),
                )
                // 顶栏 ↔ 编辑栏进出场：新内容自顶部滑入淡入、旧内容向上滑出淡出，进退对称，
                // 与底部导航栏的 AnimatedVisibility 共用 [EditTransitionMillis] 规格，
                // 同一个 editing 翻转同帧启动，两侧严格同步。
                // 内容读 lambda 参数而非外层 editSession：会话翻转时若读外层值，
                // 退场中的旧槽位也会跟着渲染成新内容
                AnimatedContent(
                    targetState = editSession == null,
                    contentAlignment = Alignment.TopStart,
                    transitionSpec = {
                        (slideInVertically(
                            animationSpec = tween(EditTransitionMillis, easing = FastOutSlowInEasing),
                        ) { -it } + fadeIn(tween(EditTransitionMillis))) togetherWith
                            (slideOutVertically(
                                animationSpec = tween(EditTransitionMillis, easing = FastOutSlowInEasing),
                            ) { -it } + fadeOut(tween(EditTransitionMillis)))
                    },
                    modifier = Modifier.align(Alignment.TopStart),
                    label = "topBarSwap",
                ) { viewing ->
                    if (viewing) {
                        ScheduleHeader(
                            collapseFraction = collapseState.collapseFraction,
                            date = headerDate,
                            selectedWeek = week,
                            currentWeek = currentWeek,
                            onWeekSelected = { selectedWeek = it },
                            onEditClick = { onEditingChange(true) },
                            onSettingsClick = onOpenSettings,
                            onMenuExpandedChange = { collapseState.menuOpen = it },
                            weekRange = 1..schedule.totalWeeks,
                            daysPerWeek = schedule.daysPerWeek,
                            onDaysPerWeekToggle = onToggleDaysPerWeek,
                            blurProgress = blurProgress,
                            hazeState = hazeState,
                            modifier = Modifier.onGloballyPositioned { coords ->
                                headerHeight = with(density) { coords.size.height.toFloat().toDp() }
                            },
                        )
                    } else {
                        ScheduleEditBar(
                            onSave = onSaveEdit,
                            onSwitchSchedule = { switcherVisible = true },
                            onCancel = { onEditingChange(false) },
                            // 与折叠后的原顶栏同一套背景模糊：内容滚到栏下时渐入
                            blurProgress = blurProgress,
                            hazeState = hazeState,
                            // 编辑态的星期行同样按课表天数分列，与网格列对齐
                            daysPerWeek = schedule.daysPerWeek,
                            modifier = Modifier.onGloballyPositioned { coords ->
                                editBarHeight = with(density) { coords.size.height.toFloat().toDp() }
                            },
                        )
                    }
                }
            }

            // 面板目标反查：添加=目标格子（进度 / 旋转重建后 slotId 仍有效）；编辑=草稿反查条目；
            // 交替课=源课与它的起始节（新面板沿用同一格的位置）
            val targetDay = addTarget.firstOrNull()
            val targetSlot = addTarget.getOrNull(1)?.let { id -> timeSlots.firstOrNull { it.id == id } }
            val editingEntry = editSession?.courses?.firstOrNull { it.id == editTargetId }
            val editingSlot = editingEntry?.let { e -> timeSlots.firstOrNull { it.id == e.startSlot } }
            val alternateSource = editSession?.courses?.firstOrNull { it.id == alternateSourceId }
            val alternateSlot = alternateSource?.let { e -> timeSlots.firstOrNull { it.id == e.startSlot } }
            // 选择弹窗的候选：被点卡片所在格的全部课程（从草稿反查，条目没了弹窗自然关闭）
            val chooserCourses = editSession?.let { draft ->
                draft.courses.firstOrNull { it.id == chooserSourceId }
                    ?.let { draft.courses.cellCourses(it.dayOfWeek, it.startSlot) }
            }.orEmpty()

            // 添加/编辑课程面板：页内覆盖层而非 Dialog 窗口——输入法要接得进来（见 AddCoursePanel 注释）。
            // 三种入口互斥：点已有课卡编辑 / 点空格新建 / 长按已有课新建交替课，归一成一个面板请求。
            // 编辑态从草稿反查条目，命中才给请求（删除后条目消失 → 请求清空 → 面板自动关闭）。
            // 收场动画期间请求已清空但面板还在组合里——mountedRequest 记住最后一次请求，
            // 参数保持稳定让面板带着原内容滑出；动画播完（onDismissed）才真正卸载。
            // key() 保证换目标时面板整体重建（表单初值复位，与旧的 if 分支行为一致）。
            val panelRequest = when {
                editingEntry != null && editingSlot != null -> AddCourseRequest(
                    day = editingEntry.dayOfWeek,
                    slot = editingSlot,
                    existing = editingEntry,
                )
                targetDay != null && targetSlot != null -> AddCourseRequest(
                    day = targetDay,
                    slot = targetSlot,
                )
                alternateSource != null && alternateSlot != null -> AddCourseRequest(
                    day = alternateSource.dayOfWeek,
                    slot = alternateSlot,
                    alternateFrom = alternateSource,
                )
                else -> null
            }
            var mountedRequest by remember { mutableStateOf<AddCourseRequest?>(null) }
            if (panelRequest != null) mountedRequest = panelRequest
            mountedRequest?.let { request ->
                key(request) {
                    AddCoursePanel(
                        visible = panelRequest != null,
                        day = request.day,
                        slot = request.slot,
                        schedule = schedule,
                        draftCourses = editSession?.courses.orEmpty(),
                        existing = request.existing,
                        alternateFrom = request.alternateFrom,
                        onDismiss = {
                            editTargetId = ""
                            addTarget = emptyList()
                            alternateSourceId = ""
                        },
                        onDismissed = { mountedRequest = null },
                        onConfirm = { entry ->
                            val existingId = request.existing?.id
                            if (existingId != null) editSession?.update(existingId, entry) else editSession?.add(entry)
                            editTargetId = ""
                            addTarget = emptyList()
                            alternateSourceId = ""
                        },
                        onDelete = request.existing?.let { existing ->
                            {
                                editSession?.remove(existing.id)
                                editTargetId = ""
                            }
                        },
                    )
                }
            }

            // 交替课程选择弹窗：同格多门时才出现（见 onEditClick），选完开那一门的编辑面板
            if (editSession != null && chooserCourses.size > 1) {
                AlternatePickerDialog(
                    courses = chooserCourses,
                    onDismiss = { chooserSourceId = "" },
                    onPick = { entry ->
                        chooserSourceId = ""
                        editTargetId = entry.id
                    },
                )
            }

            // 切换课表浮层：列表/新建 + 底部导出导入；脏草稿确认叠在其上——
            // 组合顺序保证返回键优先级：确认 → 浮层（表单态先回列表）→ 编辑取消。
            // 挂载与可见性分离（同课程面板的两段式关闭）：关闭先播浮层出场动画，
            // 播完（onDismissed）才真正卸载
            var switcherMounted by remember { mutableStateOf(false) }
            if (switcherVisible) switcherMounted = true
            if (switcherMounted) {
                ScheduleSwitcherSheet(
                    visible = switcherVisible,
                    onDismissed = { switcherMounted = false },
                    schedules = repository.schedules.collectAsState().value,
                    activeScheduleId = schedule.id,
                    createMode = createMode,
                    onCreateModeChange = {
                        createMode = it
                    },
                    onDismiss = {
                        switcherVisible = false
                        createMode = false
                    },
                    onSwitch = requestSwitch,
                    onDelete = onDeleteSchedule,
                    onExport = onExport,
                    onImport = {
                        importLauncher.launch(
                            arrayOf(
                                "application/json",
                                "text/plain",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onCreateConfirm = onCreateConfirm,
                )
                if (pendingSwitchId.isNotEmpty()) {
                    DiscardSwitchConfirmDialog(
                        onCancel = { pendingSwitchId = "" },
                        onConfirm = { performSwitch(pendingSwitchId) },
                    )
                }
            }

            // 长按卡片的上下文菜单（Popup 独立窗口，位置由卡片坐标决定）
            menuAnchor?.let { anchor ->
                CourseContextMenu(
                    anchor = anchor.rect,
                    onDismiss = { menuAnchor = null },
                    onNewAlternate = {
                        alternateSourceId = anchor.courseId
                        menuAnchor = null
                    },
                )
            }
        }
    }
}

/** 普通态顶栏总高估算（状态栏 + 顶栏行 + 日期行 + 星期行），首帧后由实测值覆盖 */
private val HeaderHeightGuess = 168.dp

/** 编辑栏总高估算（状态栏 + 按钮行 + 星期行），首帧后由实测值覆盖 */
private val EditBarHeightGuess = 112.dp

/**
 * 长按菜单的锚点：被长按卡的 id（菜单动作用它回查草稿）与卡片的窗口坐标（菜单贴卡片定位）。
 * 非 saveable 的瞬时状态，见 [ScheduleScreen] 里的 menuAnchor。
 */
private data class CardMenuAnchor(val courseId: String, val rect: Rect)

/**
 * 课程面板请求：三种入口（点课卡编辑 / 空格新建 / 长按新建交替课）归一后的参数快照。
 * 三个入口互斥；数据类相等性配合 key() 使用——同一目标重组合不重建面板，换目标才重建。
 */
private data class AddCourseRequest(
    val day: Int,
    val slot: TimeSlot,
    val existing: CourseEntry? = null,
    val alternateFrom: CourseEntry? = null,
)

/**
 * 数据层错误 → 用户可读文案（编辑流程的 Toast 用）。课程类错误一律点名到课程或给中文结论，
 * 绝不把内部 id / 英文 message 直接抛给用户；真正没覆盖到的形态才落到通用文案。
 */
private fun ScheduleError.toEditMessage(context: Context): String = when (this) {
    is ScheduleError.CourseFieldInvalid -> when (reason) {
        FieldReason.BlankName -> context.getString(R.string.error_course_name_blank)
        else -> context.getString(R.string.error_unexpected)
    }
    is ScheduleError.WeeksBeyondTerm -> context.getString(R.string.error_weeks_beyond_term)
    // 末周被收掉后放不下的课：给中文结论（课程名请去网格里看）
    is ScheduleError.TermEndInvalid -> context.getString(R.string.error_term_end_short)
    // 学期被改短/改窄后放不下的课：说清是哪几门
    is ScheduleError.CoursesOutOfRange -> context.getString(
        R.string.error_courses_out_of_range,
        affected.size,
        affected.take(3).joinToString { it.name },
    )
    // 冲突对里 a 是列表靠前的那门（新增的课总在末尾），故指它
    is ScheduleError.GridConflict -> context.getString(R.string.error_grid_conflict, a.name)
    is ScheduleError.PersistFailed -> context.getString(R.string.error_persist_failed)
    is ScheduleError.NotFound -> context.getString(R.string.error_not_found)
    is ScheduleError.ImportFormatInvalid -> context.getString(R.string.error_import_format)
    is ScheduleError.ImportVersionUnsupported -> context.getString(R.string.error_import_version)
    is ScheduleError.ImportKindMismatch -> context.getString(R.string.error_import_kind)
    // 真正没覆盖到的形态：不把内部 message 抛给用户
    else -> context.getString(R.string.error_unexpected)
}

/** 无激活课表时的空状态：标题 + 说明 + 新建按钮（打开设置页） */
@Composable
private fun EmptyScheduleContent(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.empty_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_desc),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.create_schedule))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScheduleScreenPreview() {
    ClassppTheme {
        ScheduleScreen()
    }
}
