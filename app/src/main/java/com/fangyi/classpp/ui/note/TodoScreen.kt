package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TagRepository
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.expandRepeatOccurrences
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.isOverdue
import com.fangyi.classpp.data.sortedFor
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.components.BottomFadeScrim
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.NoteCard
import com.fangyi.classpp.ui.components.NoteCardSection
import com.fangyi.classpp.ui.components.NoteCardSwipe
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.components.SwipeAction
import com.fangyi.classpp.ui.components.SwipeCoordinator
import com.fangyi.classpp.ui.components.labelText
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.ProvideOverscroll
import com.fangyi.classpp.ui.motion.RubberBandLazyColumn
import com.fangyi.classpp.ui.motion.TabTransitionState
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.isGlassStyleEnabled
import com.fangyi.classpp.ui.theme.rememberGlassBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** epochDay → millis 的换算步长（同 IsoDate 的私有常量） */
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

/** 相邻两组卡片（已置顶/已逾期/未完成/已完成）之间的间距 */
private val SectionSpacing = 20.dp

/**
 * 待办页：页面底色铺 background（tab 转场缩小淡出时不透出其他层）。
 * 内容层为待办列表（[hazeSource] 采样源：列表滚动到顶栏之下时透出毛玻璃），
 * 顶栏浮在上层；选中态在此持有，可见列表由 [filterTodosByGroup] 按选中分组下标派生。
 *
 * 列表分「已置顶」「已逾期」「未完成」「已完成」四组，已置顶在最上（无置顶待办时整组
 * 隐藏）、已逾期次之、已完成在下（默认收起），组内按创建时间（创建早的在前）；
 * 勾选切换写回仓库，完成即移组，截止日期已过的待办移入已逾期组、标题标红。
 * 顶栏分类菜单（分类块唤起，与排序菜单同款弹层）是四组显隐的多选开关：
 * 默认全勾选，取消勾选即整组隐藏（[visibleSections] 派生，点选不收菜单）。
 * 新建待办浮层（[NewTodoSheet]）两段式挂载：[showNewTodoSheet] 置真即挂载滑入，
 * 关闭先清 visible 播完出场，onDismissed 才卸载；点卡片拉起待办详情浮层
 * （[TodoDetailSheet]，挂载接线同课表页课程详情）。任一浮层在场时经
 * [onOverlayOverNavBarChange] 请求藏底部导航栏（同课表页全屏浮层）。
 *
 * [tabTransition]：tab 转场状态只读视图（ui.navigation 的 TabPager 状态机），透传给
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
    // 侧滑互斥协调器：同一时刻至多一张待办卡处于展开态（起手滑他卡时原卡自动收起）
    val swipeCoordinator = remember { SwipeCoordinator() }
    // 卡片展开态下系统返回 = 收起卡片而非退出；各浮层自己的 BackHandler 注册在后、
    // 优先级更高，互不干扰（同课表页编辑态返回拦截的模式）
    BackHandler(enabled = swipeCoordinator.activeKey != null) {
        swipeCoordinator.activeKey = null
    }
    var selectedGroupIndex by rememberSaveable { mutableIntStateOf(0) }
    // 特殊分组页（已完成/未完成/已逾期/已置顶/已归档/最近删除）：分组浮层第一部分跳转进入，
    // 顶栏分组条首位出现对应胶囊并**常驻**——specialSection 记胶囊存在，specialActive 记
    // 页面在场（胶囊选中态）：切常规分组只离页不清胶囊（转未选态），再点未选胶囊回页、
    // 点已选胶囊才退出关闭。二者独立于 [selectedGroupIndex] 保存——进入不动常规选中，
    // 关闭后自动回到进入前的常规分组
    var specialSection by rememberSaveable { mutableStateOf<TodoSpecialSection?>(null) }
    var specialActive by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    // 顶栏胶囊的液态玻璃：设置页「更高级的玻璃材质」开关 + API 31+（RenderEffect）
    // 双门控，关闭即原实心底（pressFeedback + background）。采样层 = 页面底色 + 待办
    // 列表——列表从顶栏下滚过，玻璃才有东西可折射（底色必须画进采样层，
    // 否则漏透明像素）；顶栏胶囊在采样节点之外，玻璃不会照见自己。采样与顶栏的 Haze
    // 磨砂同源（hazeSource / layerBackdrop 挂同一内容节点），两套采样互不干扰：
    // Haze 管整条顶栏的磨砂，Backdrop 只喂顶栏胶囊（OverlaySheet 顶栏按钮同款结构）
    val glassPillsEnabled = isGlassStyleEnabled()
    val topBarPillBackdrop = rememberGlassBackdrop(MaterialTheme.colorScheme.background)
    // 活跃待办：仓库已剔除最近删除（软删条目只在 deletedTodos 流里，供回收站页使用）
    val todos by repository.todos.collectAsState()
    val deletedTodos by repository.deletedTodos.collectAsState()

    // 标签配色表（标签名 → 颜色）：与 TagChoosingCard 同源，用户自定义标签取
    // TagEntry.colorArgb（null = 跟随主题 Primary）。**仅用户标签进表**——课程标签
    // 在卡片上不取课程卡配色，跟随默认 Primary（10% 底 + Primary 字）
    val userTagEntries by remember(tagRepository) {
        tagRepository?.tags ?: MutableStateFlow(emptyList())
    }.collectAsState()
    val primaryColor = MaterialTheme.colorScheme.primary
    val tagColorMap = remember(userTagEntries, primaryColor) {
        userTagEntries.associate { it.name to (it.colorArgb?.let(::Color) ?: primaryColor) }
    }
    // 排序状态：默认创建时间降序（创建早的在前）
    var sort by rememberSaveable { mutableStateOf(TodoSort.Created) }
    var sortDescending by rememberSaveable { mutableStateOf(true) }
    // 排序菜单展开态（DropdownMenu 挂在 TodoTopBar 展开动作行的排序块处）
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // 分类菜单展开态（弹层锚在展开动作行的分类块处，与排序菜单同款三件套接线）
    var categoryMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // 分类可见性：菜单四项全勾选 = 四组全显（默认），取消勾选即时隐藏对应分组。
    // rememberSaveable：旋转/切 tab/进程重建不丢，冷启动回默认全选——与排序状态同口径
    var visibleSections by rememberSaveable { mutableStateOf(TodoSection.entries.toSet()) }
    // 分组显隐开关：在可见集合里增删该组（不可变更新触发重组），列表即时增减对应分组
    val toggleSectionVisible: (TodoSection) -> Unit = { section ->
        visibleSections = if (section in visibleSections) visibleSections - section else visibleSections + section
    }
    // 顶栏「更多」展开态：翻转展开/收起动作行（分组/排序/分类/视图/概览）
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    // 列表让位与顶栏展开同帧同曲线：动作行展开/收起都是 Motion.ExpandMillis 一条时长，
    // 进场 Decelerate、退场 Accelerate——与 TodoTopBar 的 AnimatedVisibility 同规格，
    // 顶栏长高的同时列表同步下移，两者之间不露缝也不重叠
    val topBarExtraSpace by animateDpAsState(
        targetValue = if (menuExpanded) TopBarActionRowSpace else 0.dp,
        animationSpec = if (menuExpanded) {
            tween(Motion.ExpandMillis, easing = Motion.Decelerate)
        } else {
            tween(Motion.ExpandMillis, easing = Motion.Accelerate)
        },
        label = "todoTopBarActionSpace",
    )
    // 详情浮层：被点待办 id（点卡片进入），空 = 未打开（同课表页 detailTargetId）
    var detailTargetId by rememberSaveable { mutableStateOf("") }
    // 所点条目的分期日期（ISO 文本，空 = 非逐期卡）：详情按 id 反查原始待办，逐期上下文
    // 只能由点击带入——随重复的截止行按它折算出该条目的具体截止
    var detailTargetDate by rememberSaveable { mutableStateOf("") }
    // 编辑浮层：被编辑待办 id（详情顶栏「编辑」进入），空 = 未打开（两段式挂载见浮层处）
    var editTargetId by rememberSaveable { mutableStateOf("") }
    // 分组浮层：待办分组（顶栏展开动作行的分组块进入），空 = 未打开（两段式挂载见浮层处）
    var groupsVisible by rememberSaveable { mutableStateOf(false) }
    // 四张全屏浮层（新建/详情/编辑/分组）的挂载态：声明提前到页面布局之前——底部渐变
    // 遮罩（页面 Box 内的 BottomFadeScrim）要读 anyOverlayMounted 决定显隐；各浮层
    // 自己的两段式挂载块（置真与卸载回调）仍在原位
    var newTodoMounted by remember { mutableStateOf(false) }
    var detailMounted by remember { mutableStateOf<Todo?>(null) }
    var editMounted by remember { mutableStateOf<Todo?>(null) }
    var groupsMounted by remember { mutableStateOf(false) }
    val anyOverlayMounted = newTodoMounted || detailMounted != null || editMounted != null ||
        groupsMounted
    // 分组列表（顺序 + 置顶标记）：仓库 todos.json 持久化，顶栏胶囊与分组浮层共享同一份；
    // 分组写回失败的 Toast 文案在组合期解析（commitGroups 回调里非组合上下文，同编辑浮层约定）
    val groups by repository.groups.collectAsState()
    val groupSaveFailedMessage = stringResource(R.string.todo_error_save_failed)
    // 侧滑动作结果 Toast 文案（组合期解析——回调里非组合上下文，同 groupSaveFailedMessage 约定）
    val todoDeletedMessage = stringResource(R.string.toast_todo_deleted)
    val todoRestoredMessage = stringResource(R.string.toast_todo_restored)
    val todoPurgedMessage = stringResource(R.string.toast_todo_purged)
    val todoPinnedMessage = stringResource(R.string.toast_todo_pinned)
    val todoUnpinnedMessage = stringResource(R.string.toast_todo_unpinned)
    // 分组写回统一入口：新建（浮层追加）、确认（置顶/重排草稿）、删除（滤除）、重命名
    // （立即生效）共用——整表交仓库 setGroups 落盘（被删组的待办由仓库级联回未分组）；
    // 顶栏过滤下标按原组名跟随（下标按位置存，重排后不跟随会指错组）：仅 2 起的用户
    // 分组下标需要跟随，0（全部）/1（未分组）与分组编辑无关；选中的组被重命名时经
    // [renames] 映射（原名 → 新名）跟随到新名字；组被删则 indexOfFirst 得 -1 → 回 0（全部）
    val commitGroups: (List<TodoGroup>, Map<String, String>) -> Unit = { newGroups, renames ->
        if (selectedGroupIndex >= 2) {
            val selectedName = groups.getOrNull(selectedGroupIndex - 2)?.name
            val followedName = selectedName?.let { renames[it] ?: it }
            val newIndex = followedName
                ?.let { name -> newGroups.indexOfFirst { it.name == name } }
                ?: -1
            selectedGroupIndex = if (newIndex >= 0) newIndex + 2 else 0
        }
        scope.launch {
            if (repository.setGroups(newGroups) is TodoOpResult.Err) {
                AppToasts.show(context, groupSaveFailedMessage)
            }
        }
    }

    // 按选中分组派生可见列表（0 = 全部、1 = 未分组、2 起 = 用户分组）；特殊分组页在场时
    // 改按特殊页口径过滤。离页（胶囊保留未选）回常规过滤。
    // 过滤前先做重复待办的分期展开（虚拟独立待办：已完成分期进已完成、过去未完成期全显、
    // 未来只显最近一期）——先展开后过滤，特殊页的已完成/未完成口径才能看到分期。
    // 最近删除页例外：条目不在活跃列表里（仓库单列 deletedTodos 流），整条待办一张卡、
    // 不做分期展开，并按删除时刻倒序（最近删的在最前）
    val today = LocalToday
    val nowMinute = currentMinuteOfDay()
    val special = if (specialActive) specialSection else null
    val displayTodos = remember(todos, today) { todos.expandRepeatOccurrences(today) }
    val visibleTodos = when (special) {
        TodoSpecialSection.Trash -> deletedTodos.sortedByDescending { it.deletedAtMillis ?: 0L }
        null -> filterTodosByGroup(displayTodos, selectedGroupIndex, groups)
        else -> filterTodosBySpecial(displayTodos, special, today, nowMinute)
    }

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
                    .hazeSource(hazeState)
                    // 玻璃顶栏胶囊开启时同一节点再挂液态玻璃采样标记（Haze 管顶栏磨砂、
                    // Backdrop 只喂顶栏胶囊）；关闭时不挂载，采样层零开销
                    .then(
                        if (glassPillsEnabled) {
                            Modifier.layerBackdrop(topBarPillBackdrop)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                TodoList(
                    todos = visibleTodos,
                    sort = sort,
                    sortDescending = sortDescending,
                    // 标签配色表（用户标签所选颜色）：透传给列表卡的 NoteCard
                    tagColors = tagColorMap,
                    // 特殊分组页 = 该页本身就是过滤口径，分类菜单的分组显隐不再参与（全组可见）
                    visibleSections =
                        if (special != null) TodoSection.entries.toSet() else visibleSections,
                    // 特殊页分组头全部默认展开（常规列表「已完成默认收起」不适用）；空页给占位文案
                    expandAllSections = special != null,
                    emptyHint = special?.let { stringResource(it.emptyHintRes) },
                    // 最近删除页：平铺（无四组分组头）+ 已删除样式卡片（右撤回 / 左滑彻底删除）
                    deletedCards = special == TodoSpecialSection.Trash,
                    topBarExtraSpace = topBarExtraSpace,
                    swipeCoordinator = swipeCoordinator,
                    // 勾选路由：分期卡（重复待办的展开副本，dates[0] 即分期日期）走逐期
                    // 结算，普通待办走整条完成
                    onToggleCompleted = { todo, completed ->
                        scope.launch {
                            if (todo.repeat.type != RepeatType.None) {
                                todo.dates.firstOrNull()?.let { date ->
                                    repository.setOccurrenceCompleted(todo.id, date, completed)
                                }
                            } else {
                                repository.setCompleted(todo.id, completed)
                            }
                        }
                    },
                    // 侧滑置顶：写回仓库后卡片随分组移动（移入/移出「已置顶」组），Toast 同步反馈
                    onTogglePinned = { id, pinned ->
                        scope.launch {
                            when (repository.setPinned(id, pinned)) {
                                TodoOpResult.Ok ->
                                    AppToasts.show(context, if (pinned) todoPinnedMessage else todoUnpinnedMessage)
                                is TodoOpResult.Err -> AppToasts.show(context, groupSaveFailedMessage)
                            }
                        }
                    },
                    // 侧滑删除：展开后再拖到完全变色松手才删（无确认框，满色就是强确认）；
                    // 失败 Toast、卡片自动滑回。软删：条目进最近删除，可从回收站恢复
                    onDeleteTodo = { id ->
                        scope.launch {
                            when (repository.removeTodo(id)) {
                                TodoOpResult.Ok -> AppToasts.show(context, todoDeletedMessage)
                                is TodoOpResult.Err -> AppToasts.show(context, groupSaveFailedMessage)
                            }
                        }
                    },
                    // 最近删除页的撤回按钮：清除软删标记，待办回到原分组（分组已删则回未分组）
                    onRestoreTodo = { id ->
                        scope.launch {
                            when (repository.restoreTodo(id)) {
                                TodoOpResult.Ok -> AppToasts.show(context, todoRestoredMessage)
                                is TodoOpResult.Err -> AppToasts.show(context, groupSaveFailedMessage)
                            }
                        }
                    },
                    // 最近删除页的左滑「彻底删除」：满色松手才真正从文件移除（不可恢复）
                    onPurgeTodo = { id ->
                        scope.launch {
                            when (repository.purgeTodo(id)) {
                                TodoOpResult.Ok -> AppToasts.show(context, todoPurgedMessage)
                                is TodoOpResult.Err -> AppToasts.show(context, groupSaveFailedMessage)
                            }
                        }
                    },
                    onTodoClick = {
                        detailTargetId = it.id
                        detailTargetDate = it.dates.firstOrNull()?.toString().orEmpty()
                    },
                )
            }
            // 底部渐变遮罩：页面自绘（原导航栏内置层下沉至此），盖住滚进导航栏区域的列表；
            // 全屏浮层在场时随导航栏一同下移出屏——与 MainActivity 的导航栏 AnimatedVisibility
            // 共用 Motion.EditMillis + Motion.Overlay 规格。画在内容层之上、顶栏之下（遮罩管底、顶栏管顶）
            BottomFadeScrim(
                visible = !anyOverlayMounted,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            TodoTopBar(
                groups = groups.map { it.name },
                // 置顶分组数：仓库维持「置顶构成连续前缀」不变式，顶栏按此把前 N 粒胶囊
                // 固定最前（行首图钉）；下标语义不受视觉前移影响（仍按 groups 原序偏移）
                pinnedGroupCount = groups.count { it.pinned },
                // 特殊分组页胶囊（分组条首位，常驻）+ 点击切换：已选中 = 退出关闭（胶囊消失），
                // 未选中 = 回到该页；切任何常规胶囊 = 离开特殊页（胶囊保留、转未选态）
                specialSection = specialSection,
                specialSectionSelected = specialActive,
                onSpecialSectionClick = {
                    if (specialActive) {
                        specialSection = null
                        specialActive = false
                    } else {
                        specialActive = true
                    }
                },
                selectedGroupIndex = selectedGroupIndex,
                onGroupSelect = {
                    specialActive = false
                    selectedGroupIndex = it
                },
                // 更多点击翻转展开态;收起时顺带关排序/分类菜单——菜单锚在展开动作行的
                // 动作块上,行收起锚点即消失,不先关会悬空
                onMoreClick = {
                    if (menuExpanded) {
                        sortMenuExpanded = false
                        categoryMenuExpanded = false
                    }
                    menuExpanded = !menuExpanded
                },
                // 排序块点击展开菜单(SortMenu 三件套挂在动作块处)；两菜单同锚一行互斥，
                // 打开一个时先关另一个，避免两张菜单卡叠影
                onSortClick = {
                    categoryMenuExpanded = false
                    sortMenuExpanded = true
                },
                // 分类块打开分类菜单（同互斥约定；多选开关，点选不收菜单）
                onCategoryClick = {
                    sortMenuExpanded = false
                    categoryMenuExpanded = true
                },
                // 分组块打开待办分组浮层（动作行保持展开，关浮层回到原状，不额外折叠）
                onGroupClick = { groupsVisible = true },
                menuExpanded = menuExpanded,
                sortMenuExpanded = sortMenuExpanded,
                onSortMenuDismiss = { sortMenuExpanded = false },
                categoryMenuExpanded = categoryMenuExpanded,
                onCategoryMenuDismiss = { categoryMenuExpanded = false },
                // 分类块状态：特殊分组页在场 = 置灰不可点（该页本身就是过滤口径，显隐开关
                // 无意义）；其余页面四组未全选（有分组被隐藏）= primary 提示过滤生效中
                categoryEnabled = special == null,
                categoryFiltered = visibleSections.size < TodoSection.entries.size,
                // 分类菜单：四个分组显隐开关，勾选态即分组可见性；点选只切换不收起，
                // 便于连续勾选多项（收起靠点菜单外区域）
                categoryMenuSections = listOf(
                    PopupMenuSection(
                        items = listOf(
                            PopupMenuItem(
                                label = stringResource(R.string.todo_section_pinned),
                                checked = TodoSection.Pinned in visibleSections,
                                onClick = { toggleSectionVisible(TodoSection.Pinned) },
                            ),
                            PopupMenuItem(
                                label = stringResource(R.string.todo_section_overdue),
                                checked = TodoSection.Overdue in visibleSections,
                                onClick = { toggleSectionVisible(TodoSection.Overdue) },
                            ),
                            PopupMenuItem(
                                label = stringResource(R.string.todo_section_incomplete),
                                checked = TodoSection.Active in visibleSections,
                                onClick = { toggleSectionVisible(TodoSection.Active) },
                            ),
                            PopupMenuItem(
                                label = stringResource(R.string.todo_section_completed),
                                checked = TodoSection.Done in visibleSections,
                                onClick = { toggleSectionVisible(TodoSection.Done) },
                            ),
                        ),
                    ),
                ),
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
                pillBackdrop = if (glassPillsEnabled) topBarPillBackdrop else null,
                tabTransition = tabTransition,
            )
        }
    }

    // 新建待办浮层：两段式挂载（同 ScheduleScreen 的浮层接线）。画在页面内容之后
    // （组合顺序即绘制顺序，浮层盖住页面），与 ScheduleScreen 的浮层位置一致；
    // newTodoMounted 声明提前到了页面布局之前（BottomFadeScrim 读 anyOverlayMounted）
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
            groups = groups,
            todos = todos,
            // 选择分组浮层的「新建」走同一套整表写回（落盘失败 Toast 由 commitGroups 统一弹）
            onGroupsChange = commitGroups,
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
    if (detailEntry != null) detailMounted = detailEntry
    // 编辑浮层挂载快照：只在详情「编辑」回调里取一次、不跟随仓库回流（表单是快照语义，
    // 同 AddCoursePanel 草稿）；visible 走反查，待办被删则反查落空自动收起
    // （editMounted 声明提前到了页面布局之前，BottomFadeScrim 读 anyOverlayMounted）
    detailMounted?.let { detail ->
        // 回调（协程）里不是组合上下文，字符串在组合期解析（同 NewTodoSheet 的 saveFailedMessage）
        val saveFailedMessage = stringResource(R.string.todo_error_save_failed)
        val stepDeletedMessage = stringResource(R.string.toast_todo_step_deleted)
        key(detail.id) {
            TodoDetailSheet(
                visible = detailEntry != null,
                // 编辑浮层盖上来时本层缩小后退，清 id（确认/取消/删除）的瞬间同步回位
                covered = editTargetId != "",
                todo = detail,
                tagColors = tagColorMap,
                occurrenceDate = IsoDate.parseOrNull(detailTargetDate),
                onDismiss = {
                    detailTargetId = ""
                    detailTargetDate = ""
                },
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
                // 详情保留在下层不关：最新快照（含未落盘备注）直接当表单初值挂载编辑浮层
                // （叠在详情之上），确认/取消后回到详情；不等落盘——备注不丢、打开零延迟
                onEdit = { latest ->
                    editMounted = latest
                    editTargetId = latest.id
                },
                // 步骤新增/勾选：各自落库（addStep 现铸步骤 id、setStepDone 按 id 定点切换），
                // Err（待办已删 / 空标题等异常）Toast 提示；列表随仓库回流自动刷新
                onStepAdd = { title ->
                    scope.launch {
                        val result = repository.addStep(detail.id, title)
                        if (result is TodoOpResult.Err) AppToasts.show(context, saveFailedMessage)
                    }
                },
                onStepToggle = { stepId, done ->
                    scope.launch {
                        val result = repository.setStepDone(detail.id, stepId, done)
                        if (result is TodoOpResult.Err) AppToasts.show(context, saveFailedMessage)
                    }
                },
                // 步骤侧滑删除：松手过阈值即删（removeStep 按 todo+step 双 id 定点滤除），
                // 成功 Toast 反馈；Err（待办/步骤已被并发改动等）Toast 提示、卡片由侧滑层自动滑回
                onStepDelete = { stepId ->
                    scope.launch {
                        when (repository.removeStep(detail.id, stepId)) {
                            TodoOpResult.Ok -> AppToasts.show(context, stepDeletedMessage)
                            is TodoOpResult.Err -> AppToasts.show(context, saveFailedMessage)
                        }
                    }
                },
                // 倒计时开关：落库后列表卡左区显示剩余天数（详情菜单文案随仓库回流自动切换）
                onCountdownToggle = { enabled ->
                    scope.launch {
                        val result = repository.setCountdown(detail.id, enabled)
                        if (result is TodoOpResult.Err) AppToasts.show(context, saveFailedMessage)
                    }
                },
            )
        }
    }
    // 编辑待办浮层：两段式挂载同详情，画在详情之后（组合顺序即绘制顺序）叠在详情之上。
    // editTargetId 是期望目标（空 = 关），editMounted 是挂载时的快照（表单初值只取这一次）；
    // visible 从当前列表反查：待办被删反查落空 → 自动收起（与删除确认的级联一致）
    val editEntry = todos.firstOrNull { it.id == editTargetId }
    editMounted?.let { initial ->
        // 回调（协程）里不是组合上下文，字符串在组合期解析（同 NewTodoSheet 的 saveFailedMessage）
        val editFailedMessage = stringResource(R.string.todo_error_save_failed)
        key(initial.id) {
            NewTodoSheet(
                visible = editEntry != null,
                onDismissed = { editMounted = null },
                onDismiss = { editTargetId = "" },
                tagRepository = tagRepository,
                scheduleRepository = scheduleRepository,
                groups = groups,
                todos = todos,
                // 选择分组浮层的「新建」走同一套整表写回（落盘失败 Toast 由 commitGroups 统一弹）
                onGroupsChange = commitGroups,
                existing = initial,
                onConfirm = { todo ->
                    scope.launch {
                        // 空名等非法值已在浮层内拦截，Err 仅剩落盘失败等异常，Toast 提示、表单保持原样
                        when (repository.updateTodo(todo)) {
                            TodoOpResult.Ok -> editTargetId = ""
                            is TodoOpResult.Err -> AppToasts.show(context, editFailedMessage)
                        }
                    }
                },
                onDelete = {
                    scope.launch {
                        when (repository.removeTodo(initial.id)) {
                            TodoOpResult.Ok -> {
                                // 反查落空本会让两个浮层自动收起，这里显式清 id 收尾
                                editTargetId = ""
                                detailTargetId = ""
                            }
                            is TodoOpResult.Err -> AppToasts.show(context, editFailedMessage)
                        }
                    }
                },
            )
        }
    }

    // 待办分组浮层：顶栏展开动作行的分组块进入（两段式挂载，同上）；
    // 编辑态在浮层内自治：顶栏「确认」经 onGroupsChange 写回（置顶/重排），
    // 底栏「删除」经 onDeleteGroups 立即从 groups 移除（不可经确认框取消撤销）、
    // 「重命名」经 onGroupsChange（立即生效，renames 映射让顶栏跟随新名）；
    // 普通态点击分组/「未分组」/「全部」经 onOpen* 跳转：顶栏胶囊切到对应下标 + 收起浮层；
    // 第一部分（状态/留存）行经 onOpenSpecial 打开对应特殊分组页（顶栏首位出胶囊，收起浮层）
    // ——切常规分组只离页（胶囊保留、转未选态），再点未选胶囊回页、点已选胶囊才退出关闭
    if (groupsVisible) groupsMounted = true
    if (groupsMounted) {
        TodoGroupsSheet(
            todos = todos,
            deletedCount = deletedTodos.size,
            groups = groups,
            visible = groupsVisible,
            onDismiss = { groupsVisible = false },
            onDismissed = { groupsMounted = false },
            onGroupsChange = commitGroups,
            onDeleteGroups = { names -> commitGroups(groups.filterNot { it.name in names }, emptyMap()) },
            onOpenGroup = { name ->
                val index = groups.indexOfFirst { it.name == name }
                if (index >= 0) selectedGroupIndex = index + 2
                specialActive = false
                groupsVisible = false
            },
            onOpenUngrouped = {
                selectedGroupIndex = 1
                specialActive = false
                groupsVisible = false
            },
            onOpenAll = {
                selectedGroupIndex = 0
                specialActive = false
                groupsVisible = false
            },
            // 第一部分（状态/留存）行：打开对应特殊分组页（顶栏首位出胶囊并置为选中），收起浮层
            onOpenSpecial = { section ->
                specialSection = section
                specialActive = true
                groupsVisible = false
            },
        )
    }

    // 浮层在场（含收场动画期间）就藏底部导航栏，onDismissed 卸载后才放回（同课表页全屏浮层）；
    // 四个浮层任一在场都算，避免分组/详情/编辑浮层开着时导航栏提前露回
    // （anyOverlayMounted 的计算提前到了页面布局之前，底部渐变遮罩同读这一份）
    LaunchedEffect(anyOverlayMounted) {
        onOverlayOverNavBarChange(anyOverlayMounted)
    }
}

/** 列表分组：已置顶在最上（无置顶待办整组不渲染）、已逾期次之、未完成居中、已完成在下（默认收起），空组不渲染 */
private enum class TodoSection { Pinned, Overdue, Active, Done }

/** 分组 → 分组标题文案资源（标题在组合期经 stringResource 解析，故只存资源 id） */
private val TodoSection.titleRes: Int
    get() = when (this) {
        TodoSection.Pinned -> R.string.todo_section_pinned
        TodoSection.Overdue -> R.string.todo_section_overdue
        TodoSection.Active -> R.string.todo_section_incomplete
        TodoSection.Done -> R.string.todo_section_completed
    }

/** 当前时刻的当天分钟数 0..1439（[Todo.isOverdue] 的 nowMinute 入参；课表页课程详情复用） */
internal fun currentMinuteOfDay(): Int {
    val now = GregorianCalendar()
    return now.get(GregorianCalendar.HOUR_OF_DAY) * 60 + now.get(GregorianCalendar.MINUTE)
}

/**
 * 按顶栏分组下标派生可见待办：0 = 全部、1 = 未分组（group == null）、
 * 2 起 = 用户分组（[groups] 下标偏移 2，与 TodoTopBar 的胶囊下标一致）。
 * 下标越界（组被删后残留的保存态等）兜底回全部；未分组判定只需 null——
 * 引用完整性由仓库在写入归一化时维护。
 */
internal fun filterTodosByGroup(
    todos: List<Todo>,
    selectedGroupIndex: Int,
    groups: List<TodoGroup>,
): List<Todo> = when {
    selectedGroupIndex == 0 -> todos
    selectedGroupIndex == 1 -> todos.filter { it.group == null }
    else -> groups.getOrNull(selectedGroupIndex - 2)
        ?.let { group -> todos.filter { it.group == group.name } }
        ?: todos
}

/**
 * 按特殊分组页（[TodoSpecialSection]）口径从**活跃待办**派生可见列表：已完成/未完成/
 * 已逾期/已置顶实时过滤；已归档数据层暂无该概念，恒空仅 UI。最近删除的条目不在活跃
 * 列表里（仓库单列 deletedTodos 流），由宿主按删除时刻倒序直接取用，这里同样返回空。
 * 逾期判定与列表同一份（[today] 取 [LocalToday]、[nowMinute] 重组时现取，同 TodoList）。
 * 页内仍按置顶/逾期/未完成/已完成分区展示（置顶收编语义不变——「已置顶」页单卡侧滑
 * 即可撤销置顶）。
 */
internal fun filterTodosBySpecial(
    todos: List<Todo>,
    section: TodoSpecialSection,
    today: IsoDate,
    nowMinute: Int,
): List<Todo> = when (section) {
    TodoSpecialSection.Completed -> todos.filter { it.completed }
    TodoSpecialSection.Incomplete -> todos.filter { !it.completed }
    TodoSpecialSection.Overdue -> todos.filter { it.isOverdue(today, nowMinute) }
    TodoSpecialSection.Pinned -> todos.filter { it.pinned }
    // 已归档无数据层概念；最近删除走仓库的 deletedTodos 流（不在活跃列表里）
    TodoSpecialSection.Archived, TodoSpecialSection.Trash -> emptyList()
}

/**
 * 待办列表：已置顶、已逾期、未完成、已完成各归一组——已置顶组在最上（所有置顶待办，
 * 无置顶待办时整组不渲染）、已逾期组次之（标题标红的卡片，截止日期已过且未完成）、
 * 未完成居中、已完成在下（默认收起），空组不渲染；
 * 组内按顶栏选定的排序与方向（默认创建时间降序——创建早的在前）。置顶条目归已置顶组、
 * 不再进其余三组（移动而非重复展示，同分组置顶的语义）。空组不渲染的另一条路是
 * 分类菜单：[visibleSections] 未含的组整组隐藏（默认全组可见）。卡片经 [TodoCard]
 * 接模型字段（时间文本 [cardTimeText]、标签、紧急旗），并支持左右侧滑呼出删除/置顶
 * 动作卡（两段式侧滑手势见 [NoteCardSwipeLayer]：首段拖拽仅呼出，展开态再拖满色才
 * 执行）：同一时刻至多一张展开（[swipeCoordinator]
 * 互斥），展开态按系统返回自动收起（页面层 BackHandler）。列表从顶栏与浮动导航栏下滚过，
 * 上下各留出让位；顶栏展开动作行时按 [topBarExtraSpace] 额外让位（宿主动画下发）。
 *
 * 特殊分组页（分组浮层第一部分跳转）复用同一渲染：[expandAllSections] = 分组头全部
 * 默认展开（规避「已完成默认收起」把整页收起来）；[emptyHint] = 过滤结果为空时的
 * 居中占位文案。「已置顶」页的卡片照常支持侧滑——单卡撤销置顶即从该页消失。
 *
 * 最近删除页（[deletedCards] = true）走另一条渲染分支：无标题 [CardSection] 把全部卡片
 * 平铺在一起（10dp 卡间距），不套四组分区头——条目由宿主按删除时刻倒序传入，与
 * 「最近删除」的时间语义一致；卡片为已删除样式（右侧撤回按钮），左滑呼出「彻底删除」。
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
    // 标签配色表（标签名 → 颜色）：透传给每张卡的 NoteCard，标签胶囊据此着色
    tagColors: Map<String, Color>,
    visibleSections: Set<TodoSection>,
    // 特殊分组页：分组头全部默认展开（常规列表「已完成默认收起」不适用）
    expandAllSections: Boolean = false,
    // 列表为空时的居中占位文案（特殊分组页传入）；null = 维持空白（常规列表行为不变）
    emptyHint: String? = null,
    // 最近删除页：平铺卡片（无四组分区）、已删除样式（右撤回 / 左滑彻底删除）
    deletedCards: Boolean = false,
    topBarExtraSpace: Dp,
    swipeCoordinator: SwipeCoordinator,
    // 待办整条传入而非只传 id：分期卡（重复待办的展开副本）要靠 dates[0] 路由到逐期结算
    onToggleCompleted: (todo: Todo, completed: Boolean) -> Unit,
    onTogglePinned: (id: String, pinned: Boolean) -> Unit,
    onDeleteTodo: (id: String) -> Unit,
    onRestoreTodo: (id: String) -> Unit = {},
    onPurgeTodo: (id: String) -> Unit = {},
    onTodoClick: (todo: Todo) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 逾期判定的"此刻"：当天日期取 [LocalToday]（跨零点自动跳天）——零点多后即使没有别的
    // 交互，逾期分组也会自己重算；分钟口径仍是重组时现取
    val today = LocalToday
    val nowMinute = currentMinuteOfDay()
    val sections = remember(todos, sort, sortDescending, today, nowMinute, visibleSections) {
        // 置顶组收编全部置顶条目（移动语义，不与其余组重复）；其余组只在非置顶里划分。
        // 分类菜单取消勾选的组整组不进列表（visibleSections 过滤，空列表 = 四组全隐）
        val pinned = todos.filter { it.pinned }.sortedFor(sort, sortDescending)
        val overdue = todos.filter { !it.pinned && it.isOverdue(today, nowMinute) }
            .sortedFor(sort, sortDescending)
        val active = todos.filter { !it.pinned && !it.completed && !it.isOverdue(today, nowMinute) }
            .sortedFor(sort, sortDescending)
        val done = todos.filter { !it.pinned && it.completed }.sortedFor(sort, sortDescending)
        listOfNotNull(
            pinned.takeIf { it.isNotEmpty() && TodoSection.Pinned in visibleSections }
                ?.let { TodoSection.Pinned to it },
            overdue.takeIf { it.isNotEmpty() && TodoSection.Overdue in visibleSections }
                ?.let { TodoSection.Overdue to it },
            active.takeIf { it.isNotEmpty() && TodoSection.Active in visibleSections }
                ?.let { TodoSection.Active to it },
            done.takeIf { it.isNotEmpty() && TodoSection.Done in visibleSections }
                ?.let { TodoSection.Done to it },
        )
    }
    val density = LocalDensity.current
    val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    RubberBandLazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = topInset + TopBarRowSpace + ListTopSpacing + topBarExtraSpace,
            bottom = bottomInset + ListBottomSlack,
            start = PageHorizontalSpacing,
            end = PageHorizontalSpacing,
        ),
    ) {
        if (deletedCards) {
            // 最近删除页：整页收进一个无标题 CardSection 平铺（10dp 卡间距）——不套四组
            // 分组头，顺序由宿主按删除时刻倒序给定；空列表沿用统一的居中占位文案
            if (todos.isEmpty()) {
                if (emptyHint != null) {
                    item(key = "emptyHint") {
                        Box(
                            Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = emptyHint,
                                fontSize = 14.sp,
                                color = MaterialTheme.classppColors.secondaryText,
                            )
                        }
                    }
                }
            } else {
                item(key = "trashCards") {
                    CardSection {
                        todos.forEach { todo ->
                            TrashedTodoCard(
                                todo = todo,
                                tagColors = tagColors,
                                swipeCoordinator = swipeCoordinator,
                                onRestore = { onRestoreTodo(todo.id) },
                                onPurge = { onPurgeTodo(todo.id) },
                            )
                        }
                    }
                }
            }
        } else {
            // 空列表占位：特殊分组页过滤结果为空（含恒空的已归档）时给居中文案；
            // 常规列表不传 emptyHint，全组被分类菜单隐藏时维持原空白行为
            if (sections.isEmpty() && emptyHint != null) {
                item(key = "emptyHint") {
                    Box(
                        Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = emptyHint,
                            fontSize = 14.sp,
                            color = MaterialTheme.classppColors.secondaryText,
                        )
                    }
                }
            }
            sections.forEachIndexed { index, (section, group) ->
                item(key = section.name) {
                    NoteCardSection(
                        title = stringResource(section.titleRes),
                        initiallyExpanded = expandAllSections || section != TodoSection.Done,
                        // 组间距给首组之外的每组：首组贴列表起点，再加间距会比下面的组低一截
                        modifier = if (index > 0) Modifier.padding(top = SectionSpacing) else Modifier,
                    ) {
                        group.forEach { todo ->
                            TodoCard(
                                todo = todo,
                                tagColors = tagColors,
                                overdue = section == TodoSection.Overdue,
                                swipeCoordinator = swipeCoordinator,
                                onDelete = { onDeleteTodo(todo.id) },
                                onTogglePinned = { onTogglePinned(todo.id, !todo.pinned) },
                                onClick = { onTodoClick(todo) },
                            ) { onToggleCompleted(todo, it) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单条待办卡片：[Todo] → [NoteCard] 的字段接线——标题、完成态与勾选直通，
 * 待办时间走 [cardTimeText]、截止时间走 [deadlineCardText]（颜色由 [deadlineColor]
 * 按剩余天数映射）、重复规则走 [repeatCardText]（primary 蓝，排在两个时间项之后），
 * 标签按模型透传并经 [tagColors] 着色（TagChoosingCard 挑选的颜色，见 tagColorMap）；
 * 紧急旗按档位透传；[overdue] 为真（在已逾期组）时标题标红。
 * [Todo.countdown] 开启时把剩余天数交给 [NoteCard.countdownDays]，卡片左区显示倒计时
 * （天数口径与截止配色同源，见 [deadlineDaysLeft]）。
 * 点卡片（非勾选框）经 [onClick] 拉起详情浮层——勾选框是独立 toggleable，不会误触。
 * 侧滑动作：左侧删除（error 红，呼出后再拖满色松手即删）、右侧置顶（primary 蓝；已置顶卡
 * 显示 ic_pin_off、执行取消置顶），动作卡语义见 [NoteCardSwipe]。
 */
@Composable
private fun TodoCard(
    todo: Todo,
    // 标签配色表（标签名 → 颜色）：透传给 NoteCard 的 tagColors
    tagColors: Map<String, Color>,
    overdue: Boolean,
    swipeCoordinator: SwipeCoordinator,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    NoteCard(
        title = todo.name,
        time = todo.cardTimeText(),
        deadline = todo.deadlineCardText(),
        deadlineColor = todo.deadlineColor(),
        repeat = todo.repeatCardText(),
        // 倒计时：开关开着且仍有截止日期时才有天数可显（截止被清掉时仓库已把开关归一化回 false）
        countdownDays = if (todo.countdown) todo.deadlineDaysLeft() else null,
        tags = todo.tags,
        tagColors = tagColors,
        urgency = todo.urgency,
        completed = todo.completed,
        overdue = overdue,
        onClick = onClick,
        onCheckedChange = onCheckedChange,
        swipe = NoteCardSwipe(
            // 侧滑互斥 key 带分期日期后缀：同一系列的分期卡同屏时互不干扰
            //（删除/置顶动作作用于整个系列——分期没有独立的删除语义）
            key = todo.id + "#" + (todo.dates.firstOrNull()?.epochDay?.toString() ?: ""),
            coordinator = swipeCoordinator,
            delete = SwipeAction(
                iconRes = R.drawable.ic_delete,
                tint = MaterialTheme.colorScheme.error,
                activeColor = MaterialTheme.colorScheme.error,
                onActiveColor = MaterialTheme.colorScheme.onError,
                contentDescription = stringResource(R.string.todo_swipe_delete),
                onAction = onDelete,
            ),
            pin = SwipeAction(
                iconRes = if (todo.pinned) R.drawable.ic_pin_off else R.drawable.ic_pin,
                tint = MaterialTheme.colorScheme.primary,
                activeColor = MaterialTheme.colorScheme.primary,
                onActiveColor = MaterialTheme.colorScheme.onPrimary,
                contentDescription = stringResource(
                    if (todo.pinned) R.string.todo_swipe_unpin else R.string.todo_swipe_pin,
                ),
                onAction = onTogglePinned,
            ),
        ),
    )
}

/**
 * 最近删除页的单条卡片：[Todo] → [NoteCard] 的已删除样式接线——标题、时间、截止、重复、
 * 标签、紧急旗与普通卡同一份取值，右侧勾选框换成撤回按钮（[NoteCard.deleted]，点按
 * [onRestore]），其余样式与普通卡完全一致。不接点击：详情/编辑浮层只认活跃待办，
 * 回收站条目点开也改不了。左滑呼出「彻底删除」动作卡（满色松手即执行，同普通卡删除的
 * 强确认模型），右滑侧不提供置顶。
 */
@Composable
private fun TrashedTodoCard(
    todo: Todo,
    tagColors: Map<String, Color>,
    swipeCoordinator: SwipeCoordinator,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    NoteCard(
        title = todo.name,
        time = todo.cardTimeText(),
        deadline = todo.deadlineCardText(),
        deadlineColor = todo.deadlineColor(),
        repeat = todo.repeatCardText(),
        countdownDays = if (todo.countdown) todo.deadlineDaysLeft() else null,
        tags = todo.tags,
        tagColors = tagColors,
        urgency = todo.urgency,
        completed = todo.completed,
        deleted = true,
        onRestore = onRestore,
        swipe = NoteCardSwipe(
            key = todo.id,
            coordinator = swipeCoordinator,
            delete = SwipeAction(
                iconRes = R.drawable.ic_delete,
                tint = MaterialTheme.colorScheme.error,
                activeColor = MaterialTheme.colorScheme.error,
                onActiveColor = MaterialTheme.colorScheme.onError,
                contentDescription = stringResource(R.string.todo_swipe_purge),
                onAction = onPurge,
            ),
            pin = null,
        ),
    )
}

/**
 * 待办 → NoteCard 待办时间项的文本（"12:30"、"明天 14:00-16:00"、"6/18 全天"）。
 *
 * 日期段走 [cardDatePart]（±2 天内显相对词），时刻段按 [TodoTimeKind] 拼接：
 * 全天显「全天」，无时刻只显日期段；均无返回 null，NoteCard 缺席该属性。
 */
@Composable
internal fun Todo.cardTimeText(): String? {
    val timePart = when (timeKind) {
        TodoTimeKind.Period -> {
            val start = startMinute
            val end = endMinute
            if (start != null && end != null) "${TimeText.format(start)}-${TimeText.format(end)}" else null
        }
        TodoTimeKind.AllDay -> stringResource(R.string.todo_time_all_day)
        TodoTimeKind.None -> null
    }
    return listOfNotNull(dates.cardDatePart(), timePart)
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }
}

/**
 * 截止时间文本：日期段（[toCardDateLabel]——今天显「今天」）+ 时刻。
 * 无截止日期返回 null——这一位缺席；截止日期与时刻成对出现（TodoValidator 约束）。
 */
@Composable
internal fun Todo.deadlineCardText(): String? {
    val date = deadlineDate ?: return null
    return listOfNotNull(
        date.toCardDateLabel(),
        deadlineMinute?.let(TimeText::format),
    ).joinToString(" ")
}

/**
 * 重复规则文本（NoteCard 重复项）：档位名（无重复/每天/每周/每月/每年/自定义——自定义也
 * 只显「自定义」，频率与方格细项进编辑浮层看）；无重复语义返回 null——这一位缺席。
 */
@Composable
internal fun Todo.repeatCardText(): String? =
    repeat.type.takeUnless { it == RepeatType.None }?.labelText()

/**
 * 距截止的剩余天数（无截止返回 null，可为负 = 已逾期）；列表卡倒计时（[countdownDays]）
 * 与截止配色（[deadlineColor]）共用同一口径，两者永远同步。
 *
 * 「今天」取 [LocalToday]（跨零点自动跳天，见 [rememberTodayState]）而非系统时钟——
 * 后者只在重组那一刻取值，挂过夜会停在旧日期。
 */
@Composable
internal fun Todo.deadlineDaysLeft(): Long? = deadlineDate?.let { it - LocalToday }

/**
 * 截止时间取色（NoteCard 图标与文字同用）：距截止剩余 >2 天 primary；剩 2 天
 * urgentYellow；剩 1 天 urgentOrange；当天及已过期 error。截止缺席时返回
 * primary（调用方仅在有截止文本时消费该色）。
 */
@Composable
internal fun Todo.deadlineColor(): Color {
    val days = deadlineDaysLeft() ?: return MaterialTheme.colorScheme.primary
    return when {
        days == 0L -> MaterialTheme.colorScheme.error
        days == 1L -> MaterialTheme.classppColors.urgentOrange
        days == 2L -> MaterialTheme.classppColors.urgentYellow
        days > 2 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
}

/** 日期段：空缺席；否则逐日走 [toCardDateLabel]，多日以「、」连接（详情浮层不复用——它要确定日期） */
@Composable
internal fun List<IsoDate>.cardDatePart(): String? {
    if (isEmpty()) return null
    val labels = mutableListOf<String>()
    for (date in this) labels += date.toCardDateLabel()
    return labels.joinToString("、")
}

/**
 * 绝对日期段：非本年 `2027/3/12`（YYYY/M/D）、本年 `3/12`（M/D），月/日不补零。
 * [toCardDateLabel] 相对词之外的兜底段与待办详情浮层（截止日期/待办时间）的日期段共用；
 * [today] 可注入，便于单测把「今年」钉死。换算同 IsoDate.toString 的 UTC 整数天。
 */
internal fun IsoDate.toAbsoluteDateText(today: IsoDate = IsoDate.today()): String {
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochDay * MILLIS_PER_DAY
    }
    val year = calendar.get(GregorianCalendar.YEAR)
    val month = calendar.get(GregorianCalendar.MONTH) + 1
    val day = calendar.get(GregorianCalendar.DAY_OF_MONTH)
    val todayYear = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = today.epochDay * MILLIS_PER_DAY
    }.get(GregorianCalendar.YEAR)
    return if (year == todayYear) {
        String.format(Locale.ROOT, "%d/%d", month, day)
    } else {
        String.format(Locale.ROOT, "%d/%d/%d", year, month, day)
    }
}

/**
 * 时刻戳文本（待办创建/完成时间）：`3/12 8:00`（本年）/ `2027/3/12 8:00`（非本年）——
 * 日期段走 [toAbsoluteDateText]，时刻段走 [TimeText.format]（时不补零、分钟两位）。
 *
 * 时刻戳按 [zone]（默认设备时区，同 [IsoDate.today] 的时区语义）换算成日历日与当天分钟数；
 * [today]/[zone] 可注入，便于单测把「今年」与时区钉死。
 */
internal fun epochMillisText(
    millis: Long,
    today: IsoDate = IsoDate.today(),
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val local = GregorianCalendar(zone).apply { timeInMillis = millis }
    val date = IsoDate.of(
        local.get(GregorianCalendar.YEAR),
        local.get(GregorianCalendar.MONTH) + 1,
        local.get(GregorianCalendar.DAY_OF_MONTH),
    )
    val minuteOfDay = local.get(GregorianCalendar.HOUR_OF_DAY) * 60 + local.get(GregorianCalendar.MINUTE)
    return "${date.toAbsoluteDateText(today)} ${TimeText.format(minuteOfDay)}"
}

/**
 * 卡片日期段：距今天 ±2 天内显相对词（后天/明天/今天/昨天/前天），更远显 [toAbsoluteDateText]
 * 的绝对日期。待办时间与截止时间共用；详情浮层不复用——详情页要给出确定日期，直接走
 * [toAbsoluteDateText]（不带相对词）。「今天」取 [LocalToday]：跨零点自动跳天，
 * 否则「今天/明天」会随卡片一直停在打开那天。
 */
@Composable
internal fun IsoDate.toCardDateLabel(): String {
    val today = LocalToday
    when (epochDay - today.epochDay) {
        2L -> return stringResource(R.string.date_card_day_after_tomorrow)
        1L -> return stringResource(R.string.date_card_tomorrow)
        0L -> return stringResource(R.string.date_card_today)
        -1L -> return stringResource(R.string.date_card_yesterday)
        -2L -> return stringResource(R.string.date_card_day_before_yesterday)
    }
    return toAbsoluteDateText()
}
