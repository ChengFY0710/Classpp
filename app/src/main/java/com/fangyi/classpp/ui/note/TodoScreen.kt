package com.fangyi.classpp.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TagRepository
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.isOverdue
import com.fangyi.classpp.data.sortedFor
import com.fangyi.classpp.ui.components.NoteCard
import com.fangyi.classpp.ui.components.NoteCardSection
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.motion.ProvideOverscroll
import com.fangyi.classpp.ui.motion.RubberBandLazyColumn
import com.fangyi.classpp.ui.motion.TabTransitionState
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** 演示分组：数据层接入后由用户自定义分组替换 */
private val DemoGroups = listOf("作业", "日常琐事")

/** epochDay → millis 的换算步长（同 IsoDate/DeadlineCard 的私有常量） */
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 顶栏在内容坐标系里的占位高：TodoTopBar 的私有常量镜像（状态栏之上另算）——
 * 顶栏顶部留白 3dp + 胶囊行 Box 高 40dp + 上下阴影呼吸位 8dp×2。
 */
private val TopBarRowSpace = 59.dp

/** 列表首组与顶栏的间距 */
private val ListTopSpacing = 12.dp

/** 列表尾部余量：列表从浮动导航栏下滚过，末张卡片能滚离底边更远 */
private val ListBottomSlack = 96.dp

/** 相邻两组卡片（已逾期/未完成/已完成）之间的间距 */
private val SectionSpacing = 20.dp

/**
 * 待办页：页面底色铺 background（tab 转场缩小淡出时不透出其他层）。
 * 内容层为待办列表（[hazeSource] 采样源：列表滚动到顶栏之下时透出毛玻璃），
 * 顶栏浮在上层；选中态在此持有，分组胶囊过滤后续接入。
 *
 * 列表分「已逾期」「未完成」「已完成」三组，已逾期在最上、已完成在下（默认收起），
 * 组内按创建时间（创建早的在前）；勾选切换写回仓库，完成即移组，
 * 截止日期已过的待办移入已逾期组、标题标红。
 * 新建待办浮层（[NewTodoSheet]）两段式挂载：[showNewTodoSheet] 置真即挂载滑入，
 * 关闭先清 visible 播完出场，onDismissed 才卸载；点卡片拉起待办详情浮层
 * （[TodoDetailSheet]，挂载接线同课表页课程详情）。任一浮层在场时经
 * [onOverlayOverNavBarChange] 请求藏底部导航栏（同课表页全屏浮层）。
 *
 * [tabTransition]：tab 转场状态只读视图（MainActivity 的 TabPageState），透传给
 * [TodoTopBar] 启用转场冻结层——与课表页顶栏同一机制，杜绝快速切 tab 时顶栏
 * 毛玻璃闪缺（详见 tabTransitionFreeze）。
 */
@Composable
fun TodoScreen(
    modifier: Modifier = Modifier,
    repository: TodoRepository? = null,
    tagRepository: TagRepository? = null,
    scheduleRepository: ScheduleRepository? = null,
    showNewTodoSheet: Boolean = false,
    onNewTodoSheetDismiss: () -> Unit = {},
    onOverlayOverNavBarChange: (Boolean) -> Unit = {},
    tabTransition: TabTransitionState? = null,
) {
    // get() 返回即 bootstrap 完成，此分支仅首帧毫秒级；早返回后下方 smart cast 为非空
    if (repository == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedGroupIndex by rememberSaveable { mutableIntStateOf(0) }
    val hazeState = remember { HazeState() }
    val todos by repository.todos.collectAsState()
    // 排序状态：默认创建时间降序（创建早的在前）
    var sort by rememberSaveable { mutableStateOf(TodoSort.Created) }
    var sortDescending by rememberSaveable { mutableStateOf(true) }
    // 排序菜单展开态（DropdownMenu 挂在 TodoTopBar 的排序胶囊处）
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // 详情浮层：被点待办 id（点卡片进入），空 = 未打开（同课表页 detailTargetId）
    var detailTargetId by rememberSaveable { mutableStateOf("") }

    // 整页（列表 + 顶栏）滚动内容启用 iOS 式橡皮筋 overscroll（ui.motion 的
    // ProvideOverscroll）：列表滚到顶/底后继续拖动，内容整块被拉出边缘、越拉越硬，
    // 松手无过冲弹回；顶栏分组胶囊（rubberBandHorizontalScroll）同享。平移露出的是
    // 页面底色（background 同色，边缘看不出破绽）
    ProvideOverscroll {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 内容层：待办列表从顶栏毛玻璃下滚过（列表滚动到顶栏之下时被渐变模糊）
            Box(
                Modifier
                    .matchParentSize()
                    .hazeSource(hazeState),
            ) {
                TodoList(
                    todos = todos,
                    sort = sort,
                    sortDescending = sortDescending,
                    onToggleCompleted = { id, completed ->
                        scope.launch { repository.setCompleted(id, completed) }
                    },
                    onTodoClick = { detailTargetId = it.id },
                )
            }
            TodoTopBar(
                groups = DemoGroups,
                selectedGroupIndex = selectedGroupIndex,
                onGroupSelect = { selectedGroupIndex = it },
                // 文件夹操作后续接入；排序点击展开菜单（SortMenuPopup 挂在胶囊处）
                onFolderClick = {},
                onSortClick = { sortMenuExpanded = true },
                sortMenuExpanded = sortMenuExpanded,
                onSortMenuDismiss = { sortMenuExpanded = false },
                sortMenuSections = listOf(
                    PopupMenuSection(
                        items = listOf(
                            PopupMenuItem(
                                label = stringResource(R.string.sort_created),
                                checked = sort == TodoSort.Created,
                                onClick = {
                                    sort = TodoSort.Created
                                    sortMenuExpanded = false
                                },
                            ),
                            PopupMenuItem(
                                label = stringResource(R.string.sort_deadline),
                                checked = sort == TodoSort.Deadline,
                                onClick = {
                                    sort = TodoSort.Deadline
                                    sortMenuExpanded = false
                                },
                            ),
                            PopupMenuItem(
                                label = stringResource(R.string.sort_urgency),
                                checked = sort == TodoSort.Urgency,
                                onClick = {
                                    sort = TodoSort.Urgency
                                    sortMenuExpanded = false
                                },
                            ),
                            PopupMenuItem(
                                label = stringResource(R.string.sort_name),
                                checked = sort == TodoSort.Name,
                                onClick = {
                                    sort = TodoSort.Name
                                    sortMenuExpanded = false
                                },
                            ),
                        ),
                    ),
                    PopupMenuSection(
                        showDivider = true,
                        items = listOf(
                            PopupMenuItem(
                                label = stringResource(R.string.sort_ascending),
                                checked = !sortDescending,
                                onClick = {
                                    sortDescending = false
                                    sortMenuExpanded = false
                                },
                            ),
                            PopupMenuItem(
                                label = stringResource(R.string.sort_descending),
                                checked = sortDescending,
                                onClick = {
                                    sortDescending = true
                                    sortMenuExpanded = false
                                },
                            ),
                        ),
                    ),
                ),
                modifier = Modifier.align(Alignment.TopCenter),
                hazeState = hazeState,
                tabTransition = tabTransition,
            )
        }
    }

    // 新建待办浮层：两段式挂载（同 ScheduleScreen 的浮层接线）。画在页面内容之后
    // （组合顺序即绘制顺序，浮层盖住页面），与 ScheduleScreen 的浮层位置一致
    var newTodoMounted by remember { mutableStateOf(false) }
    if (showNewTodoSheet) newTodoMounted = true
    if (newTodoMounted) {
        // 回调（协程）里不是组合上下文，字符串在组合期解析——lint 不允许经
        // LocalContext 查资源值（AddCoursePanel 同款做法）
        val saveFailedMessage = stringResource(R.string.todo_error_save_failed)
        NewTodoSheet(
            visible = showNewTodoSheet,
            onDismissed = { newTodoMounted = false },
            onDismiss = onNewTodoSheetDismiss,
            tagRepository = tagRepository,
            scheduleRepository = scheduleRepository,
            onConfirm = { todo ->
                scope.launch {
                    // 空名等非法值已在浮层内拦截，Err 仅剩落盘失败等异常，Toast 提示、表单保持原样
                    when (repository.addTodo(todo)) {
                        is TodoReadResult.Ok -> onNewTodoSheetDismiss()
                        is TodoReadResult.Err -> AppToasts.show(context, saveFailedMessage)
                    }
                }
            },
        )
    }
    // 待办详情浮层：两段式挂载与课表页课程详情同款——detailTargetId 是期望目标（空 = 关），
    // detailMounted 在收场动画期间保持内容稳定不闪空，onDismissed 才卸载。
    // 条目从当前列表反查：待办在浮层开着时被删则反查落空 → visible 转 false 自动收起。
    // 画在新建浮层之后（组合顺序即绘制顺序），两者都盖住页面内容
    val detailEntry = todos.firstOrNull { it.id == detailTargetId }
    var detailMounted by remember { mutableStateOf<Todo?>(null) }
    if (detailEntry != null) detailMounted = detailEntry
    detailMounted?.let { detail ->
        // 回调（协程）里不是组合上下文，字符串在组合期解析（同 NewTodoSheet 的 saveFailedMessage）
        val saveFailedMessage = stringResource(R.string.todo_error_save_failed)
        key(detail.id) {
            TodoDetailSheet(
                visible = detailEntry != null,
                todo = detail,
                onDismiss = { detailTargetId = "" },
                onDismissed = { detailMounted = null },
                // 备注随任意关闭路径落盘：未改动零开销；Err 仅剩校验不过等异常（Toast 提示）
                onNoteSave = { note ->
                    val cleaned = note.trim()
                    if (cleaned != detail.note) {
                        scope.launch {
                            val result = repository.updateTodo(detail.copy(note = cleaned))
                            if (result is TodoOpResult.Err) AppToasts.show(context, saveFailedMessage)
                        }
                    }
                },
            )
        }
    }

    // 浮层在场（含收场动画期间）就藏底部导航栏，onDismissed 卸载后才放回（同课表页全屏浮层）；
    // 两个浮层任一在场都算，避免详情浮层开着时导航栏提前露回
    val anyOverlayMounted = newTodoMounted || detailMounted != null
    LaunchedEffect(anyOverlayMounted) {
        onOverlayOverNavBarChange(anyOverlayMounted)
    }
}

/** 列表分组：已逾期在最上、未完成居中、已完成在下（默认收起），空组不渲染 */
private enum class TodoSection { Overdue, Active, Done }

/** 分组 → 分组标题文案资源（标题在组合期经 stringResource 解析，故只存资源 id） */
private val TodoSection.titleRes: Int
    get() = when (this) {
        TodoSection.Overdue -> R.string.todo_section_overdue
        TodoSection.Active -> R.string.todo_section_incomplete
        TodoSection.Done -> R.string.todo_section_completed
    }

/** 当前时刻的当天分钟数 0..1439（[Todo.isOverdue] 的 nowMinute 入参） */
private fun currentMinuteOfDay(): Int {
    val now = GregorianCalendar()
    return now.get(GregorianCalendar.HOUR_OF_DAY) * 60 + now.get(GregorianCalendar.MINUTE)
}

/**
 * 待办列表：已逾期、未完成、已完成各归一组——已逾期组在最上（标题标红的卡片，
 * 截止日期已过且未完成）、未完成居中、已完成在下（默认收起），空组不渲染；
 * 组内按顶栏选定的排序与方向（默认创建时间降序——创建早的在前）。卡片经 [TodoCard]
 * 接模型字段（时间文本 [cardTimeText]、标签、紧急旗）。列表从顶栏与浮动导航栏下滚过，
 * 上下各留出让位。
 *
 * 滚动容器为 [RubberBandLazyColumn]（页面 [ProvideOverscroll] 作用域内）：内容超一屏
 * 时滚到边缘拉出橡皮筋，待办很少、不足一屏时同样能拉——短内容是 foundation 派发门槛
 * 拦下的场景，由该封装的兜底手势层接管。
 */
@Composable
private fun TodoList(
    todos: List<Todo>,
    sort: TodoSort,
    sortDescending: Boolean,
    onToggleCompleted: (id: String, completed: Boolean) -> Unit,
    onTodoClick: (todo: Todo) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 逾期判定的"此刻"：当日/分钟随重组取值——跨过截止时刻后，任何一次重组都会重算分组
    val today = IsoDate.today()
    val nowMinute = currentMinuteOfDay()
    val sections = remember(todos, sort, sortDescending, today, nowMinute) {
        val overdue = todos.filter { it.isOverdue(today, nowMinute) }.sortedFor(sort, sortDescending)
        val active = todos.filter { !it.completed && !it.isOverdue(today, nowMinute) }
            .sortedFor(sort, sortDescending)
        val done = todos.filter { it.completed }.sortedFor(sort, sortDescending)
        listOfNotNull(
            overdue.takeIf { it.isNotEmpty() }?.let { TodoSection.Overdue to it },
            active.takeIf { it.isNotEmpty() }?.let { TodoSection.Active to it },
            done.takeIf { it.isNotEmpty() }?.let { TodoSection.Done to it },
        )
    }
    val density = LocalDensity.current
    val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    RubberBandLazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = topInset + TopBarRowSpace + ListTopSpacing,
            bottom = bottomInset + ListBottomSlack,
            start = PageHorizontalSpacing,
            end = PageHorizontalSpacing,
        ),
    ) {
        sections.forEachIndexed { index, (section, group) ->
            item(key = section.name) {
                NoteCardSection(
                    title = stringResource(section.titleRes),
                    initiallyExpanded = section != TodoSection.Done,
                    // 组间距给首组之外的每组：首组贴列表起点，再加间距会比下面的组低一截
                    modifier = if (index > 0) Modifier.padding(top = SectionSpacing) else Modifier,
                ) {
                    group.forEach { todo ->
                        TodoCard(
                            todo = todo,
                            sort = sort,
                            overdue = section == TodoSection.Overdue,
                            onClick = { onTodoClick(todo) },
                        ) { onToggleCompleted(todo.id, it) }
                    }
                }
            }
        }
    }
}

/**
 * 单条待办卡片：[Todo] → [NoteCard] 的字段接线——标题、完成态与勾选直通，
 * 时间走 [cardTimeText] 映射（跟随顶栏排序键 [sort]，选「截止日期」时该位置改显截止日期），
 * 标签与紧急旗按模型透传；[overdue] 为真（在已逾期组）时标题标红。
 * 点卡片（非勾选框）经 [onClick] 拉起详情浮层——勾选框是独立 toggleable，不会误触。
 */
@Composable
private fun TodoCard(
    todo: Todo,
    sort: TodoSort,
    overdue: Boolean,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    NoteCard(
        title = todo.name,
        time = todo.cardTimeText(sort),
        tags = todo.tags,
        urgency = todo.urgency,
        completed = todo.completed,
        overdue = overdue,
        onClick = onClick,
        onCheckedChange = onCheckedChange,
    )
}

/**
 * 待办 → NoteCard 属性行的时间文本（KDoc 形态："12:30"、"6月18日 14:30"）。
 *
 * 与顶栏排序按钮联动：排序键为 [TodoSort.Deadline] 时，这一位让给截止日期
 * （[deadlineCardText]——列表正按截止日期排，卡片就显所排的字段）；其余排序键
 * 一律显待办时间：
 *
 * - 有日期：今天只显时刻段，非今天前置日期段；全天显「全天」，无时刻只显日期段；
 * - 无日期时刻时兜底截止值（同格式；截止日期与时刻成对，TodoValidator 约束）；
 * - 均无返回 null，NoteCard 缺席该属性（标签前无空位）。
 */
@Composable
private fun Todo.cardTimeText(sort: TodoSort): String? {
    if (sort == TodoSort.Deadline) return deadlineCardText()
    val timePart = when (timeKind) {
        TodoTimeKind.Period -> {
            val start = startMinute
            val end = endMinute
            if (start != null && end != null) "${TimeText.format(start)}-${TimeText.format(end)}" else null
        }
        TodoTimeKind.AllDay -> stringResource(R.string.todo_time_all_day)
        TodoTimeKind.None -> null
    }
    val scheduled = listOfNotNull(dates.cardDatePart(), timePart)
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }
    if (scheduled != null) return scheduled
    // 截止兜底：今天不显日期段（时刻即全部信息），跨天显「6月18日 14:30」
    return listOfNotNull(
        deadlineDate?.takeUnless { it == IsoDate.today() }?.toCardDateText(),
        deadlineMinute?.let(TimeText::format),
    ).joinToString(" ").takeIf { it.isNotEmpty() }
}

/**
 * 截止日期文本（排序=截止日期时占据属性行首位）：日期段「6月18日」+ 时刻，
 * 今天不显日期段（时刻即全部信息，同待办时间的今天规则）。
 * 无截止日期返回 null——这一位缺席，卡片只剩标签；标签也没有则整行不渲染。
 */
private fun Todo.deadlineCardText(): String? {
    val date = deadlineDate ?: return null
    return listOfNotNull(
        date.takeUnless { it == IsoDate.today() }?.toCardDateText(),
        deadlineMinute?.let(TimeText::format),
    ).joinToString(" ").takeIf { it.isNotEmpty() }
}

/** 日期段：空缺席；单日为今天不显（时刻即全部信息）；否则「6月18日、6月20日」。待办详情浮层复用 */
internal fun List<IsoDate>.cardDatePart(): String? {
    if (isEmpty()) return null
    if (size == 1 && single() == IsoDate.today()) return null
    return joinToString("、") { it.toCardDateText() }
}

/** 日期段文本 `6月18日`（月/日不补零，NoteCard KDoc 形态；换算同 IsoDate.toString 的 UTC 整数天）。待办详情浮层复用 */
internal fun IsoDate.toCardDateText(): String {
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochDay * MILLIS_PER_DAY
    }
    return String.format(
        Locale.ROOT,
        "%d月%d日",
        calendar.get(GregorianCalendar.MONTH) + 1,
        calendar.get(GregorianCalendar.DAY_OF_MONTH),
    )
}
