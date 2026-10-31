package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TagOpResult
import com.fangyi.classpp.data.TagRepository
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.TRASH_RETENTION_DAYS
import com.fangyi.classpp.data.comparator
import com.fangyi.classpp.data.expandRepeatOccurrences
import com.fangyi.classpp.data.xmu.XmuTodoMapper
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.isOverdue
import com.fangyi.classpp.data.sortedFor
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.components.BottomFadeScrim
import com.fangyi.classpp.ui.components.NoteCard
import com.fangyi.classpp.ui.components.NoteCardSectionHeader
import com.fangyi.classpp.ui.components.NoteCardSwipe
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SwipeAction
import com.fangyi.classpp.ui.components.SwipeCoordinator
import com.fangyi.classpp.ui.components.TodoInboxCard
import com.fangyi.classpp.ui.components.TodoInboxItem
import com.fangyi.classpp.ui.components.labelText
import com.fangyi.classpp.ui.motion.CrossPages
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.ProvideOverscroll
import com.fangyi.classpp.ui.motion.RubberBandLazyColumn
import com.fangyi.classpp.ui.motion.RubberBandLazyVerticalStaggeredGrid
import com.fangyi.classpp.ui.motion.TabTransitionState
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.CardSectionTitleSpacing
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.isGlassStyleEnabled
import com.fangyi.classpp.ui.theme.rememberGlassBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** epochDay → millis 的换算步长（同 IsoDate 的私有常量） */
private const val MILLIS_PER_DAY = 86_400_000L

/** 列表首组与顶栏的间距 */
private val ListTopSpacing = 12.dp

/** 列表尾部余量：列表从浮动导航栏下滚过，末张卡片能滚离底边更远 */
private val ListBottomSlack = 96.dp

/** 相邻两组卡片（已置顶/已逾期/未完成/已完成）之间的间距 */
private val SectionSpacing = 20.dp

/**
 * 待办页：页面底色铺 background（tab 转场缩小淡出时不透出其他层）。
 * 内容层为待办列表（[hazeSource] 采样源：列表滚动到顶栏之下时透出毛玻璃），
 * 顶栏浮在上层；选中态在此持有，可见列表由 [filterTodosByGroup] 等过滤函数按页面
 * 身份派生（见下方 CrossPages 段）。
 *
 * 分组/特殊分组页/标签页/列表↔收集箱视图之间的切换走整页交叉过渡（ui.motion 的
 * [CrossPages]，Cresto 层级交叉动效的移植）：每个页面身份一个独立内容组合，旧页淡出
 * 下沉、新页延迟退让后放大浮起——切组不再触发条目级交叉淡入淡出（那是同一 LazyColumn
 * 换数据的产物），条目级 animateItem 只服务页内增删移与折叠；各页过滤口径与视图
 * 从页面 key 解码，退场中的旧页继续渲染自己的内容（视图也是身份段的一部分，见
 * 下方视图菜单段）；列表滚动位、网格滚动位与分区折叠经 SaveableStateProvider 按页保活
 * （列表与收集箱各存各的滚动位）。顶栏不参与过渡（页面 chrome 常驻原位）。
 *
 * 列表分「已置顶」「已逾期」「未完成」「已完成」四组，已置顶在最上（无置顶待办时整组
 * 隐藏）、已逾期次之、已完成在下（默认收起），组内按创建时间（创建早的在前）；
 * 勾选切换写回仓库，完成即移组，截止日期已过的待办移入已逾期组、标题标红。
 * 顶栏分类菜单（分类块唤起，与排序菜单同款弹层）是四组显隐的多选开关：
 * 默认全勾选，取消勾选即整组隐藏（[visibleSections] 派生，点选不收菜单）。
 * 顶栏视图菜单（视图块唤起，与排序/分类同款弹层）在列表视图（默认）与收集箱视图
 * 之间切换（选择经 [TodoInboxPreferences] 持久化，冷启动回到上次视图）：收集箱视图
 * 把非空分组渲染成 [TodoInboxCard]——三组及以上按个性化页的
 * 「瀑布流视图」开关（[masonryView]，默认开启）走两列瀑布流（卡间距 10dp，按卡片
 * 高度落进较短一列）或整宽竖排，两组及以下始终整宽竖排（半宽卡会把标题与
 * 属性行挤得过窄；特殊分组页的单卡同理）；卡内只显示
 * 标题与时间/截止/重复（三项均带图标），行点击拉起详情浮层、勾选照常落库；分组口径
 * 与列表同源（[buildTodoSections]）。视图段编进 CrossPages 的页面 key，视图切换与
 * 切分组同享整页交叉过渡；最近删除页保留列表且不编码视图段（撤回/彻底删除需要整卡
 * 动作，切视图不播动画、内容不变），顶栏视图块在该页置灰不可点。收集箱卡不渲染
 * 倒计时，从该视图打开的详情里「开启倒计时」菜单项同样置灰（开关无可视反馈）。
 * 新建待办浮层（[NewTodoSheet]）两段式挂载：[showNewTodoSheet] 置真即挂载滑入，
 * 关闭先清 visible 播完出场，onDismissed 才卸载；点卡片拉起待办详情浮层
 * （[TodoDetailSheet]，挂载接线同课表页课程详情）。任一浮层在场时经
 * [onOverlayOverNavBarChange] 请求藏底部导航栏（同课表页全屏浮层）。
 *
 * [tabTransition]：tab 转场状态只读视图（ui.navigation 的 TabPager 状态机），透传给
 * [TodoTopBar] 启用转场冻结层——与课表页顶栏同一机制，杜绝快速切 tab 时顶栏
 * 毛玻璃闪缺（详见 tabTransitionFreeze）。
 *
 * 长按列表卡片进入待办编辑页（仅常规分组与标签页可用，特殊分组页无入口，闸门见
 * [enterTodoEdit]）：顶栏经 AnimatedContent 换场为编辑顶栏（[TodoEditBar]，删除/移动/
 * 同步/置顶（只选中已置顶待办时切「取消」）/确认，规格与动画逐项对齐课表编辑顶栏，
 * Motion.EditMillis + Standard），删除/置顶/取消置顶/移动批量作用于选中待办并即时落库
 *（移动经 [GroupPickSheet] 点选目标分组），
 * 「确认」/系统返回退出（不设取消，退出还原进编辑前的动作行展开状态）；
 * 卡片经 [NoteCard.selected] 转选中蓝框形态、分组头强制展开并把箭头换成「全选/取消
 * 全选」；编辑态下底部导航栏与渐隐遮罩下移出屏、侧滑与完成勾选停用。
 * 长按命中（onLongClick 在超时即触发）时手指仍按在卡片上：顶栏换场与导航栏下移照旧
 * 即时启动，列表内容的移动则推迟到本次手势结束——页面 Box 的 pointerInput 被动旁听
 * 手势，编辑态内第一次「全部指针抬起」置真 [editListReleased]（松手闸门），编辑顶栏
 * 让位补差、被顺手收起的动作行的让位追赶（[editEnterTopBarExtraSpace]）与收起组的
 * 强制展开自此才开始，卡片不会在用户指下挪动。
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
    // 收集箱「瀑布流视图」开关（个性化页的 State 实例透传，默认开启）：关闭时收集箱
    // 无论几组都整宽竖排；值只在 TodoInboxContent 叶子内读，切换开关不牵动整页
    masonryView: State<Boolean> = mutableStateOf(true),
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
    // 待办编辑态：长按列表卡片进入（仅常规分组/标签页可用，特殊分组页与最近删除无入口），
    // 选中集与编辑标记都 rememberSaveable（旋转/进程重建不丢）。删除/置顶即点即落库
    // （与课表编辑的草稿会话不同），「确认」只负责退出——不设取消，系统返回同语义
    var todoEditing by rememberSaveable { mutableStateOf(false) }
    var selectedTodoIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // 列表内容的松手闸门：onLongClick 在长按超时即命中、手指仍按在卡上——顶栏换场与
    // 导航栏下移照旧即时启动，但列表的移动（编辑顶栏让位补差 + 收起组强制展开）要等
    // 松手，否则卡片在用户指下突然挪动。页面 Box 的 pointerInput 旁听每次手势，编辑态
    // 内第一次全部指针抬起即置真；退出编辑复位。不 rememberSaveable：编辑态只在长按
    // 手势进行中进入，进程重建等恢复场景手指必然已不在屏上，下一次点按的抬起会自愈置真
    var editListReleased by remember { mutableStateOf(false) }
    // 顶栏动作行的进编辑前展开状态：enterTodoEdit 收起动作行前快照，退出编辑（确认/
    // 返回）时还原——编辑顶栏只是临时接管，不因一次编辑丢掉用户的展开偏好。
    // rememberSaveable：与 todoEditing 同口径，进程重建恢复编辑态后退出照样还原
    var menuExpandedBeforeEdit by rememberSaveable { mutableStateOf(false) }
    var selectedGroupIndex by rememberSaveable { mutableIntStateOf(0) }
    // 特殊分组页（已完成/未完成/已逾期/已置顶/已归档/最近删除）：分组浮层第一部分跳转进入，
    // 顶栏分组条首位出现对应胶囊并**常驻**——specialSection 记胶囊存在，specialActive 记
    // 页面在场（胶囊选中态）：切常规分组只离页不清胶囊（转未选态），再点未选胶囊回页、
    // 点已选胶囊才退出关闭。二者独立于 [selectedGroupIndex] 保存——进入不动常规选中，
    // 关闭后自动回到进入前的常规分组
    var specialSection by rememberSaveable { mutableStateOf<TodoSpecialSection?>(null) }
    var specialActive by rememberSaveable { mutableStateOf(false) }
    // 标签特殊分组页（分组浮层标签区跳转进入）：与特殊分组页同一套「胶囊常驻 + active 记
    // 页面在场」语义——selectedTag 记顶栏标签胶囊（标签名），tagActive 记页面在场。二者独立
    // 于 [selectedGroupIndex] 与 [specialSection] 保存；specialActive 与 tagActive 在所有切换
    // 点互斥（任一页打开即关另一页），点常规胶囊两页一起退（胶囊保留、转未选态）
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var tagActive by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    // 顶栏胶囊的液态玻璃：设置页「更高级的玻璃材质」开关 + API 31+（RenderEffect）
    // 双门控，关闭即原实心底（pressFeedback + background）。采样层 = 页面底色 + 待办
    // 列表——列表从顶栏下滚过，玻璃才有东西可折射（底色必须画进采样层，
    // 否则漏透明像素）；顶栏胶囊在采样节点之外，玻璃不会照见自己。采样与顶栏的 Haze
    // 磨砂同源（hazeSource / layerBackdrop 挂同一内容节点），两套采样互不干扰：
    // Haze 管整条顶栏的磨砂，Backdrop 只喂顶栏胶囊（OverlaySheet 顶栏按钮同款结构）
    val glassPillsEnabled = isGlassStyleEnabled()
    val topBarPillBackdrop = rememberGlassBackdrop(MaterialTheme.colorScheme.background)
    // 活跃待办：仓库已剔除最近删除与已归档（软删条目只在 deletedTodos 流、归档条目只在
    // archivedTodos 流里，分别供回收站页与已归档页使用）
    val todos by repository.todos.collectAsState()
    val deletedTodos by repository.deletedTodos.collectAsState()
    val archivedTodos by repository.archivedTodos.collectAsState()

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
    // 课程标签：由激活课表按课程名去重派生（不入库，同 NewTodoSheet 的 TagChoosingCard 约定），
    // 分组浮层标签卡以只读锁定行展示——icon 与顶栏胶囊都取主题 Primary
    val activeSchedule by remember(scheduleRepository) {
        scheduleRepository?.activeSchedule ?: MutableStateFlow<Schedule?>(null)
    }.collectAsState()
    val courseTagNames = remember(activeSchedule) {
        activeSchedule?.courses?.distinctBy { it.name }?.map { it.name }.orEmpty()
    }
    // 排序状态：默认创建时间降序（创建早的在前）
    var sort by rememberSaveable { mutableStateOf(TodoSort.Created) }
    var sortDescending by rememberSaveable { mutableStateOf(true) }
    // 排序菜单展开态（DropdownMenu 挂在 TodoTopBar 展开动作行的排序块处）
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // 分类菜单展开态（弹层锚在展开动作行的分类块处，与排序菜单同款三件套接线）
    var categoryMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // 视图模式：false = 列表（默认），true = 收集箱。rememberSaveable 管旋转/切 tab/
    // 进程重建，初值取 SharedPreferences 里用户上次的选择——普通冷启动也回到上次视图
    //（与个性化页的显示开关同源，见 TodoInboxPreferences）
    var inboxView by rememberSaveable {
        mutableStateOf(TodoInboxPreferences.loadInboxView(context))
    }
    // 视图选择即时落盘（布尔翻转无连续变化，无需去抖）：以 LaunchedEffect 统一收口，
    // 菜单项与将来新增的入口都无需各自记得写盘；首帧写入与读到的值相同，幂等无副作用
    LaunchedEffect(inboxView) {
        TodoInboxPreferences.saveInboxView(context, inboxView)
    }
    // 视图菜单展开态（弹层锚在展开动作行的视图块处，与排序/分类同款三件套）
    var viewMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // 分类可见性：菜单四项全勾选 = 四组全显（默认），取消勾选即时隐藏对应分组。
    // rememberSaveable：旋转/切 tab/进程重建不丢，冷启动回默认全选——与排序状态同口径
    var visibleSections by rememberSaveable { mutableStateOf(TodoSection.entries.toSet()) }
    // 分组显隐开关：在可见集合里增删该组（不可变更新触发重组），列表即时增减对应分组
    val toggleSectionVisible: (TodoSection) -> Unit = { section ->
        visibleSections = if (section in visibleSections) visibleSections - section else visibleSections + section
    }
    // 顶栏「更多」展开态：翻转展开/收起动作行（分组/排序/分类/视图/概览）
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    // 动作行让位的进编辑冻结快照：enterTodoEdit 在长按命中那一刻快照列表当前的让位值
    // （topBarExtraSpace 的当前帧值），供下方冻结目标使用；无需复位——快照只在进入编辑
    // 那一刻读取，编辑态期间动作行不可能再展开
    var editEnterTopBarExtraSpace by remember { mutableStateOf(0.dp) }
    // 列表让位与顶栏展开同帧同曲线：动作行展开/收起都是 Motion.ExpandMillis 一条时长，
    // 进场 Decelerate、退场 Accelerate——与 TodoTopBar 的 AnimatedVisibility 同规格，
    // 顶栏长高的同时列表同步下移，两者之间不露缝也不重叠。
    // 例外：长按进编辑的手势期间（todoEditing 且未松手）目标冻结在快照上——enterTodoEdit
    // 会顺手收起展开的动作行，让位归零的动画照常驱动顶栏自身，但列表不再跟随（卡片不能
    // 在指下移动）；松手（editListReleased）后目标切回 menuExpanded 口径（彼时必为 0），
    // 快照归零的追赶补差与编辑顶栏让位（editBarExtraSpace）同帧同曲线（EditMillis +
    // Overlay），列表整段移动共享一条曲线
    val topBarExtraSpace by animateDpAsState(
        targetValue = when {
            todoEditing && !editListReleased -> editEnterTopBarExtraSpace
            else -> if (menuExpanded) TopBarActionRowSpace else 0.dp
        },
        animationSpec = when {
            todoEditing -> tween(Motion.EditMillis, easing = Motion.Overlay)
            menuExpanded -> tween(Motion.ExpandMillis, easing = Motion.Decelerate)
            else -> tween(Motion.ExpandMillis, easing = Motion.Accelerate)
        },
        label = "todoTopBarActionSpace",
    )
    // 编辑顶栏让位：编辑顶栏（状态栏下 6dp 留白 + 64dp 动作行）比常规顶栏高出的部分，
    // EditMillis + Overlay 补差，列表不被顶栏压住。进编辑受 editListReleased 松手闸门
    // （手指还按在卡上时列表不动，松手才补差），退出无闸门、与遮罩/导航栏同帧收回。
    // 与顶栏展开动作行的让位（上方 topBarExtraSpace）相互独立——两者叠加进 contentPadding
    val editBarExtraSpace by animateDpAsState(
        targetValue = if (todoEditing && editListReleased) EditBarRowSpace - TopBarRowSpace else 0.dp,
        animationSpec = tween(Motion.EditMillis, easing = Motion.Overlay),
        label = "todoEditBarSpace",
    )
    // 详情浮层：被点待办 id（点卡片进入），空 = 未打开（同课表页 detailTargetId）
    var detailTargetId by rememberSaveable { mutableStateOf("") }
    // 所点条目的分期日期（ISO 文本，空 = 非逐期卡）：详情按 id 反查原始待办，逐期上下文
    // 只能由点击带入——随重复的截止行按它折算出该条目的具体截止
    var detailTargetDate by rememberSaveable { mutableStateOf("") }
    // 详情「开启倒计时」的可用性：收集箱卡不渲染倒计时（TodoInboxCard 组件口径），
    // 从收集箱打开的详情把该项置灰。打开时按页面 key 的视图段写入、不在详情期间重算
    //（浮层盖住页面，视图不会在打开期间变化）；rememberSaveable 与 detailTargetId 同口径。
    // 不在关闭时复位：收场动画期间内容仍在，复位会让菜单项闪回可点状态
    var detailCountdownAvailable by rememberSaveable { mutableStateOf(true) }
    // 编辑浮层：被编辑待办 id（详情顶栏「编辑」进入），空 = 未打开（两段式挂载见浮层处）
    var editTargetId by rememberSaveable { mutableStateOf("") }
    // 分组浮层：待办分组（顶栏展开动作行的分组块进入），空 = 未打开（两段式挂载见浮层处）
    var groupsVisible by rememberSaveable { mutableStateOf(false) }
    // 移动-选择分组浮层（待办编辑态「移动」唤起，复用 GroupPickSheet）：两段式挂载同分组浮层
    var movePickVisible by rememberSaveable { mutableStateOf(false) }
    // 五张全屏浮层（新建/详情/编辑/分组/移动选组）的挂载态：声明提前到页面布局之前——底部渐变
    // 遮罩（页面 Box 内的 BottomFadeScrim）要读 anyOverlayMounted 决定显隐；各浮层
    // 自己的两段式挂载块（置真与卸载回调）仍在原位
    var newTodoMounted by remember { mutableStateOf(false) }
    var detailMounted by remember { mutableStateOf<Todo?>(null) }
    var editMounted by remember { mutableStateOf<Todo?>(null) }
    var groupsMounted by remember { mutableStateOf(false) }
    var movePickMounted by remember { mutableStateOf(false) }
    val anyOverlayMounted = newTodoMounted || detailMounted != null || editMounted != null ||
        groupsMounted || movePickMounted
    // 分组列表（顺序 + 置顶标记）：仓库 todos.json 持久化，顶栏胶囊与分组浮层共享同一份；
    // 分组写回失败的 Toast 文案在组合期解析（commitGroups 回调里非组合上下文，同编辑浮层约定）
    val groups by repository.groups.collectAsState()
    val groupSaveFailedMessage = stringResource(R.string.todo_error_save_failed)
    // 侧滑动作结果 Toast 文案（组合期解析——回调里非组合上下文，同 groupSaveFailedMessage 约定）
    val todoDeletedMessage = stringResource(R.string.toast_todo_deleted)
    val todoRestoredMessage = stringResource(R.string.toast_todo_restored)
    val todoPurgedMessage = stringResource(R.string.toast_todo_purged)
    val todoArchivedMessage = stringResource(R.string.toast_todo_archived)
    val todoUnarchivedMessage = stringResource(R.string.toast_todo_unarchived)
    val todoPinnedMessage = stringResource(R.string.toast_todo_pinned)
    val todoUnpinnedMessage = stringResource(R.string.toast_todo_unpinned)
    val todoMovedMessage = stringResource(R.string.toast_todo_moved)
    // 分组写回统一入口：新建（浮层追加）、确认（置顶/重排草稿）、删除（滤除）、重命名
    // （立即生效）共用——整表连 renames 交仓库 setGroups 落盘：被删组的待办由仓库级联回
    // 未分组，重命名组（renames 映射，原名 → 新名）的待办归属同一次提交跟随到新名；
    // 顶栏过滤下标按原组名跟随（下标按位置存，重排后不跟随会指错组）：仅 2 起的用户
    // 分组下标需要跟随，0（全部）/1（未分组）与分组编辑无关；选中的组被重命名时经
    // [renames] 映射跟随到新名字；组被删则 indexOfFirst 得 -1 → 回 0（全部）
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
            if (repository.setGroups(newGroups, renames) is TodoOpResult.Err) {
                AppToasts.show(context, groupSaveFailedMessage)
            }
        }
    }

    // 「添加到日历」流程（详情菜单项唤起）：权限/列账户/写入/账户选择卡全在
    // CalendarAddFlow 编排（与日程页详情共用同一套），这里取状态与总入口
    val calendarAdd = rememberCalendarAddState()

    // 标签写回统一入口（分组浮层标签编辑态底栏触发）：标签库先落盘，待办侧回写随后——
    // tags.json 与 todos.json 两个文件无法同一次提交原子落盘，两步各自失败都 Toast 提示
    // （中途失败留下「库已改、待办未跟随」的窗口，属落盘异常时的可接受降级）。
    // 重命名成功后顶栏标签胶囊跟随到新名（同 commitGroups 的 renames 跟随语义）；
    // 批量删除成功后胶囊随之消失，标签页在场则一并退出；确认（重排）只写标签库，
    // 顺序变化不影响胶囊
    val commitTagRename: (String, String) -> Unit = { from, to ->
        scope.launch {
            val tags = tagRepository ?: return@launch
            if (tags.renameTag(from, to) is TagOpResult.Err) {
                AppToasts.show(context, groupSaveFailedMessage)
                return@launch
            }
            if (repository.renameTagInTodos(from, to) is TodoOpResult.Err) {
                AppToasts.show(context, groupSaveFailedMessage)
            }
            if (selectedTag == from) selectedTag = to
        }
    }
    val commitTagDelete: (List<String>) -> Unit = { names ->
        scope.launch {
            val tags = tagRepository ?: return@launch
            val nameSet = names.toSet()
            // 标签库整表滤除（一次落盘），待办侧批量解绑随后
            if (tags.setTags(userTagEntries.filterNot { it.name in nameSet }) is TagOpResult.Err) {
                AppToasts.show(context, groupSaveFailedMessage)
                return@launch
            }
            if (repository.removeTagsFromTodos(nameSet) is TodoOpResult.Err) {
                AppToasts.show(context, groupSaveFailedMessage)
            }
            if (selectedTag in nameSet) {
                selectedTag = null
                tagActive = false
            }
        }
    }
    val commitTagsOrder: (List<TagEntry>) -> Unit = { newTags ->
        scope.launch {
            val tags = tagRepository ?: return@launch
            if (tags.setTags(newTags) is TagOpResult.Err) {
                AppToasts.show(context, groupSaveFailedMessage)
            }
        }
    }

    // 过滤前先做重复待办的分期展开（虚拟独立待办：已完成分期进已完成、过去未完成期全显、
    // 未来只显最近一期）——先展开后过滤，特殊页的已完成/未完成口径才能看到分期。各页面
    // 的可见列表改在 CrossPages 的内容 lambda 里从页面 key 解码派生（见 TodoList 调用处）：
    // 退场中的旧页继续渲染自己的口径，不读当前选中态。最近删除页例外：条目不在活跃列表里
    // （仓库单列 deletedTodos 流），整条待办一张卡、不做分期展开，并按删除时刻倒序（最近删的在最前）
    val today = LocalToday
    val nowMinute = currentMinuteOfDay()
    val special = if (specialActive) specialSection else null
    val activeTag = if (tagActive) selectedTag else null
    val displayTodos = remember(todos, today) { todos.expandRepeatOccurrences(today) }
    // 页面身份编码为 CrossPages 的 key（字符串——SaveableStateProvider 的状态保活要把
    // key 存进 Bundle；前缀决定身份段，标签名含下划线也不冲突，解码只按前缀 removePrefix）。
    // 顶栏（页面 chrome）照旧读 special/activeTag/selectedGroupIndex，不参与整页过渡
    val todoPageBaseKey = when {
        special != null -> TodoPageKeySpecialPrefix + special.name
        activeTag != null -> TodoPageKeyTagPrefix + activeTag
        else -> TodoPageKeyGroupPrefix + selectedGroupIndex
    }
    // 视图段也是页面身份的一部分：列表与收集箱各是一页，切换视图与切分组同享 CrossPages
    // 整页交叉过渡，两边的滚动位也各按视图保活（切回恢复）。最近删除页与已归档页例外——
    // 回收站恒走列表（撤回需要整卡动作），已归档页也恒走列表（取消归档需要整卡动作，
    // 收集箱卡的勾选/点击对冻结的归档条目都无意义），视图切换不改 key、也不播动画；
    // 编辑态只可能从列表视图进入，!todoEditing 仅作兜底（编辑中不把 key 翻到收集箱页）
    val inboxActive = inboxView && !todoEditing &&
        special != TodoSpecialSection.Trash && special != TodoSpecialSection.Archived
    val todoPageKey = if (inboxActive) TodoPageKeyInboxPrefix + todoPageBaseKey else todoPageBaseKey
    // 最近删除页与已归档页恒走列表，顶栏视图块置灰不可点；进入这两页时顺手收起可能残留
    // 的视图菜单（弹层是 Popup，不受动作块置灰约束，状态不清理会在离开该页后重新弹出）；
    // 最近删除页另补跑一次到期清理——装载时已清过一轮，这里兜住应用长时间驻留期间
    // 新满 30 天的条目
    LaunchedEffect(special) {
        if (special == TodoSpecialSection.Trash || special == TodoSpecialSection.Archived) {
            viewMenuExpanded = false
        }
        if (special == TodoSpecialSection.Trash) {
            repository.purgeExpiredDeleted()
        }
    }
    // 回到前台补扫一次回收站保留期：进程被冻结多日再回来时既不会重新装载（bootstrap 已
    // 跑过），也不一定切到最近删除页；到期条目仍应按时彻底删除。失败静默，留给下一次扫描
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, repository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { repository.purgeExpiredDeleted() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 待办编辑三件套：进入（长按卡片，自动选中长按卡；顶栏展开的菜单/动作行与侧滑展开卡
    // 一并收起——编辑顶栏取代顶栏后它们无处安放；动作行展开状态经 menuExpandedBeforeEdit
    // 快照待退出还原，动作行的列表让位冻结到松手见 topBarExtraSpace）、退出（确认/系统
    // 返回，还原动作行展开状态）、单卡选中切换（编辑态下点卡片任意位置，含勾选框区域）。
    // 进入带可用性闸门：仅常规分组（全部/未分组/用户分组）与标签页可用——特殊分组页
    //（已完成/未完成/已逾期/已置顶/已归档/最近删除）本身是过滤口径，无编辑语义
    val enterTodoEdit: (String) -> Unit = { id ->
        if (!todoEditing && special == null) {
            // 动作行展开中被长按：先快照（展开状态给退出还原用；列表让位值供
            // topBarExtraSpace 冻结到松手——两者读的都是当前帧值，随后 menuExpanded
            // 翻转不影响它们）
            editEnterTopBarExtraSpace = topBarExtraSpace
            menuExpandedBeforeEdit = menuExpanded
            todoEditing = true
            selectedTodoIds = setOf(id)
            menuExpanded = false
            sortMenuExpanded = false
            categoryMenuExpanded = false
            viewMenuExpanded = false
            swipeCoordinator.activeKey = null
        }
    }
    val exitTodoEdit: () -> Unit = {
        todoEditing = false
        selectedTodoIds = emptySet()
        editListReleased = false
        menuExpanded = menuExpandedBeforeEdit
    }
    // 系统返回同「确认」语义（注册在 swipeCoordinator 收起之后、各浮层之前——浮层自己的
    // BackHandler 注册在后、优先级更高，浮层在场时先关浮层）
    BackHandler(enabled = todoEditing, onBack = exitTodoEdit)
    val toggleTodoSelect: (String) -> Unit = { id ->
        selectedTodoIds = if (id in selectedTodoIds) selectedTodoIds - id else selectedTodoIds + id
    }
    // 分组头「全选/取消全选」：整组待办 id 并入/移出选中集（重复待办的分期卡同享一个
    // id，整系列同选同撤）
    val toggleTodoSelectAll: (Set<String>, Boolean) -> Unit = { ids, select ->
        selectedTodoIds = if (select) selectedTodoIds + ids else selectedTodoIds - ids
    }
    // 编辑顶栏「删除」：批量软删所选（进最近删除，同侧滑删除语义），成功清空选中、
    // 保持编辑态等用户继续操作或确认退出；失败 Toast 保存失败
    val onDeleteSelected: () -> Unit = {
        val ids = selectedTodoIds.toList()
        if (ids.isNotEmpty()) {
            scope.launch {
                when (repository.removeTodos(ids)) {
                    TodoOpResult.Ok -> {
                        selectedTodoIds = emptySet()
                        AppToasts.show(context, todoDeletedMessage)
                    }
                    is TodoOpResult.Err -> AppToasts.show(context, groupSaveFailedMessage)
                }
            }
        }
    }
    // 编辑顶栏「置顶」：逐条写回（重复待办整系列生效，同侧滑置顶语义），成功 Toast
    // 已置顶、任一失败 Toast 保存失败；置顶后卡片移入「已置顶」组，选中保持
    val onPinSelected: () -> Unit = {
        val ids = selectedTodoIds.toList()
        if (ids.isNotEmpty()) {
            scope.launch {
                var failed = false
                for (id in ids) {
                    if (repository.setPinned(id, true) is TodoOpResult.Err) failed = true
                }
                AppToasts.show(context, if (failed) groupSaveFailedMessage else todoPinnedMessage)
            }
        }
    }
    // 编辑顶栏「取消」（只选中已置顶待办时出现，同一块按钮切换文案/图标）：逐条写回
    // 取消置顶，成功 Toast 已取消置顶、任一失败 Toast 保存失败；卡片移出「已置顶」组，
    // 选中保持
    val onUnpinSelected: () -> Unit = {
        val ids = selectedTodoIds.toList()
        if (ids.isNotEmpty()) {
            scope.launch {
                var failed = false
                for (id in ids) {
                    if (repository.setPinned(id, false) is TodoOpResult.Err) failed = true
                }
                AppToasts.show(context, if (failed) groupSaveFailedMessage else todoUnpinnedMessage)
            }
        }
    }
    // 编辑顶栏置顶钮三态：空选择置灰；所选待办全部已置顶 → 按钮切「取消」+ ic_pin_off，
    // 点击取消置顶；其余（全未置顶，或已置顶与未置顶混合）→ 显示「置顶」，点击整批置顶
    //（混合时未置顶的一并置顶、已置顶的保持不动）
    val pinAction = todoPinAction(todos, selectedTodoIds)
    // 编辑顶栏「移动」：唤起选择分组浮层（复用新建/编辑浮层的 GroupPickSheet，含「新建」），
    // 点选分组（null = 未分组）即把所选待办整批移动过去——只写 group 字段，其余不动
    //（置顶状态跟随，已置顶待办移动后仍在「已置顶」组；重复待办整系列生效，同删除/置顶
    // 语义）。目标组与现组相同的待办跳过：无变化不写盘、不提示。成功清空选中（移动后
    // 待办可能离开当前过滤视图，非「全部」页尤甚）、保持编辑态；任一失败 Toast 保存失败、
    // 选中保留可重试
    val onMovePick: (String?) -> Unit = { target ->
        movePickVisible = false
        val movable = todos.filter { it.id in selectedTodoIds && it.group != target }
        if (movable.isNotEmpty()) {
            scope.launch {
                var failed = false
                for (todo in movable) {
                    if (repository.updateTodo(todo.copy(group = target)) is TodoOpResult.Err) failed = true
                }
                if (failed) {
                    AppToasts.show(context, groupSaveFailedMessage)
                } else {
                    selectedTodoIds = emptySet()
                    AppToasts.show(context, todoMovedMessage)
                }
            }
        }
    }

    // 勾选完成的乐观回显（列表项 key → 目标完成态，key 口径同 [Todo.listItemKey]）：
    // 仓库是「落盘成功才发布」（Mutex + IO 写文件），点勾到数据回流之间有几十毫秒死区，
    // 且分期卡是被列表数据「移除」而非改值——animateItem 的退场淡出复用最后一次组合
    // 内容，勾选动画从头到尾没机会播，卡片是「没勾上就消失」。回显让 NoteCard 的完成态
    // 由它覆盖仓库值，勾选/划线动画手势当帧起播；停一拍（普通待办
    // Motion.TodoCardCompleteSettleMillis，开倒计时的取倒计时版多留一拍，等倒计时
    // 退场与内容跟随播完）再落库，完成反馈播完列表重排（退场/避让/补位进场）才开始。
    // 普通待办与分期卡同一套
    var completionEcho by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // 「飞入已完成组」的完成副本登记（元素 = 基础 key，口径见 [Todo.listItemKeyBase]）：
    // 勾选时刻由列表按落点可见性判定写入（shouldFlyCompletedIntoView），命中的完成副本
    // 渲染原 key 走同 key 位移「飞入」，其余带 #done 段走「退场 + 入场」。取消完成不在
    // 点按帧动它（完成副本还在场，换 key 会当帧凭空闪一下退场+入场），等写入结算后
    // 清理：Ok 才清（条目已离场，防残留影响下次完成判定），Err 保留（卡片留在已完成
    // 组，key 必须维持原样）
    var flyDoneKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val toggleTodoCompleted: (Todo, Boolean, Boolean) -> Unit = { todo, completed, flyIntoDone ->
        val date = todo.dates.firstOrNull()
        // 重复待办但展不开分期日期（无 dates 的异常形态）：与旧行为一致不结算，
        // 也不置回显——置了没有写入来结算，勾选会卡在假完成态
        if (todo.repeat.type == RepeatType.None || date != null) {
            // 回显 key 先于 fly 记账读取：必须与被点卡片当前的渲染 key 一致（完成方向点的
            // 是未完成卡、key 恒为基础段；取消完成点的完成副本是否带 #done 由现有登记定）
            val echoKey = todo.listItemKey(flyDoneKeys)
            val flyKey = todo.listItemKeyBase()
            // fly 记账只在完成方向写：副本此刻尚不存在，改 key 无闪烁；判定随勾选事件
            // 一次性生效，之后不随重组重算（key 帧间稳定）
            if (completed) {
                flyDoneKeys = if (flyIntoDone) flyDoneKeys + flyKey else flyDoneKeys - flyKey
            }
            completionEcho = completionEcho + (echoKey to completed)
            scope.launch {
                delay(
                    if (todo.countdown) {
                        Motion.TodoCountdownCompleteSettleMillis
                    } else {
                        Motion.TodoCardCompleteSettleMillis
                    }.toLong(),
                )
                val result = if (todo.repeat.type != RepeatType.None && date != null) {
                    repository.setOccurrenceCompleted(todo.id, date, completed)
                } else {
                    repository.setCompleted(todo.id, completed)
                }
                // Ok 在仓库 publish 之后返回（数据已含本次写入），Err 则数据未动——两种
                // 结算都是清掉「仍是本值」的回显：期间被再次点击覆盖成反向值则保留，
                // 等反向写入自己结算，避免把用户刚点的状态闪回去；Err 另 Toast 提示
                if (completionEcho[echoKey] == completed) {
                    completionEcho = completionEcho - echoKey
                }
                if (!completed && result is TodoOpResult.Ok) {
                    flyDoneKeys = flyDoneKeys - flyKey
                }
                if (result is TodoOpResult.Err) {
                    AppToasts.show(context, groupSaveFailedMessage)
                }
            }
        }
    }

    // 整页（列表 + 顶栏）滚动内容启用 iOS 式橡皮筋 overscroll（ui.motion 的
    // ProvideOverscroll）：列表滚到顶/底后继续拖动，内容整块被拉出边缘、越拉越硬，
    // 松手无过冲弹回；顶栏分组胶囊（rubberBandHorizontalScroll）同享。平移露出的是
    // 页面底色（background 同色，边缘看不出破绽）
    ProvideOverscroll {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // 手势旁听器（GroupCard/DayScreen 的根级 awaitEachGesture 同款接法）：
                // 不消费任何事件、不干扰子层点击/侧滑/滚动，只观察每次手势的「按下 →
                // 全部抬起」——长按命中时那根手指还在屏上，编辑态内第一次全部抬起就是
                // 它的松手（editListReleased 置真，列表让位与内容重排自此启动）
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            if (awaitPointerEvent().changes.none { it.pressed }) break
                        }
                        if (todoEditing) editListReleased = true
                    }
                },
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
                // 分组/特殊分组页/标签页切换的整页交叉过渡（ui.motion 的 CrossPages，
                // Cresto 层级交叉动效的移植）：每个页面身份一个独立列表组合，旧页淡出
                // 下沉、新页延迟退让后放大浮起。原先「切组 = 同一 LazyColumn 换数据、
                // 逐 item 交叉淡入淡出」的效果由此结构性移除——条目级 animateItem 只再
                // 服务页内变化（勾选/删除/置顶/排序/折叠的进出场与避让收拢）。各页过滤
                // 口径从页面 key 解码（退场中的旧页继续渲染自己的内容），列表滚动位与
                // 分区折叠经 SaveableStateProvider 按页保活，切回恢复
                CrossPages(
                    selectedKey = todoPageKey,
                    // 分组切换的整页交叉时序：与日程页跳日共用同一常量，两处同节奏
                    spec = Motion.PageJumpCross,
                    modifier = Modifier.matchParentSize(),
                ) { pageKey ->
                    val page = pageKey.toTodoPageIdentity()
                    val pageTodos = when {
                        page.special == TodoSpecialSection.Trash ->
                            deletedTodos.sortedByDescending { it.deletedAtMillis ?: 0L }
                        page.special == TodoSpecialSection.Archived ->
                            // 已归档条目不在活跃列表里（仓库单列 archivedTodos 流），按归档时刻
                            // 倒序（最近归档的在最前），与「最近归档」的时间语义一致
                            archivedTodos.sortedByDescending { it.archivedAtMillis ?: 0L }
                        page.special != null -> filterTodosBySpecial(displayTodos, page.special, today, nowMinute)
                        page.tag != null -> filterTodosByTag(displayTodos, page.tag)
                        else -> filterTodosByGroup(displayTodos, page.groupIndex, groups)
                    }
                    // 特殊分组页 = 该页本身就是过滤口径，分类菜单的分组显隐不再参与（全组可见）；
                    // 标签页的分类菜单可用，显隐开关照常作用于其过滤结果
                    val pageVisibleSections = if (page.special != null) {
                        TodoSection.entries.toSet()
                    } else {
                        visibleSections
                    }
                    val pageEmptyHint = when {
                        page.special != null -> stringResource(page.special.emptyHintRes)
                        page.tag != null -> stringResource(R.string.todo_empty_tag)
                        else -> null
                    }
                    // 详情入口：列表卡与收集箱卡共用（分期上下文由所点条目的 dates[0] 带入；
                    // 倒计时开关可用性由当前页面的视图段决定——收集箱卡不渲染倒计时）
                    val openTodoDetail: (Todo) -> Unit = {
                        detailTargetId = it.id
                        detailTargetDate = it.dates.firstOrNull()?.toString().orEmpty()
                        detailCountdownAvailable = !page.inbox
                    }
                    // 收集箱视图：由页面 key 的视图段决定（最近删除页不编码视图段，恒走列表）。
                    // 从 key 解码而非读当前 inboxView——退场中的列表页要继续渲染自己的内容，
                    // 视图切换的整页交叉过渡才成立。多分区走两列瀑布流（10dp 间距），
                    // 单分区（特殊分组页）用整宽卡
                    if (page.inbox) {
                        TodoInboxContent(
                            todos = pageTodos,
                            sort = sort,
                            sortDescending = sortDescending,
                            visibleSections = pageVisibleSections,
                            emptyHint = pageEmptyHint,
                            topBarExtraSpace = topBarExtraSpace,
                            completionEcho = completionEcho,
                            flyDoneKeys = flyDoneKeys,
                            masonryView = masonryView,
                            onTodoClick = openTodoDetail,
                            onToggleCompleted = { todo, completed ->
                                toggleTodoCompleted(todo, completed, false)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        return@CrossPages
                    }
                    TodoList(
                        todos = pageTodos,
                        sort = sort,
                        sortDescending = sortDescending,
                        // 标签配色表（用户标签所选颜色）：透传给列表卡的 NoteCard
                        tagColors = tagColorMap,
                        // 特殊分组页 = 该页本身就是过滤口径，分类菜单的分组显隐不再参与（全组可见）；
                        // 标签页的分类菜单可用，显隐开关照常作用于其过滤结果
                        visibleSections = pageVisibleSections,
                        // 特殊页/标签页分组头全部默认展开（常规列表「已完成默认收起」不适用）；空页给占位文案
                        expandAllSections = page.special != null || page.tag != null,
                        emptyHint = pageEmptyHint,
                        // 最近删除页：平铺（无四组分组头）+ 已删除样式卡片（右撤回 / 左滑彻底删除）
                        deletedCards = page.special == TodoSpecialSection.Trash,
                        // 已归档页：平铺（无四组分组头）+ 已归档样式卡片（右取消归档；
                        // 左滑软删进最近删除）
                        archivedCards = page.special == TodoSpecialSection.Archived,
                        topBarExtraSpace = topBarExtraSpace,
                        editBarExtraSpace = editBarExtraSpace,
                        swipeCoordinator = swipeCoordinator,
                        // 待办编辑态：卡片转选中形态、分组头强制展开并换「全选/取消全选」、
                        // 侧滑与勾选停用；进入闸门在 enterTodoEdit 内（特殊分组页关断）。
                        // 与 editBarExtraSpace 同受 editListReleased 松手闸门——列表内容
                        // （含收起组的强制展开）等长按松手才重排
                        editing = todoEditing && editListReleased,
                        selectedTodoIds = selectedTodoIds,
                        // 勾选完成的乐观回显：卡片完成态由它覆盖仓库值（见上方 completionEcho）
                        completionEcho = completionEcho,
                        // 「飞入已完成组」登记（见上方 flyDoneKeys）：命中的完成副本同 key 位移
                        flyDoneKeys = flyDoneKeys,
                        onEnterEdit = enterTodoEdit,
                        onToggleSelect = toggleTodoSelect,
                        onToggleSelectAll = toggleTodoSelectAll,
                        // 勾选路由：乐观回显 + 停一拍落库，分期卡按 dates[0] 逐期结算、
                        // 普通待办整条完成（见上方 completionEcho / toggleTodoCompleted）
                        onToggleCompleted = toggleTodoCompleted,
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
                        // （已归档页的左滑删除同走这条——归档标记保留，恢复仍回已归档页）
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
                        // 已归档页的取消归档按钮：清除归档标记，待办重回原分组与原状态
                        onUnarchiveTodo = { id ->
                            scope.launch {
                                when (repository.unarchiveTodo(id)) {
                                    TodoOpResult.Ok -> AppToasts.show(context, todoUnarchivedMessage)
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
                        onTodoClick = openTodoDetail,
                    )
                }
            }
            // 底部渐变遮罩：页面自绘（原导航栏内置层下沉至此），盖住滚进导航栏区域的列表；
            // 全屏浮层在场或待办编辑态时随导航栏一同下移出屏——与 MainActivity 的导航栏
            // AnimatedVisibility 共用 Motion.EditMillis + Motion.Overlay 规格。
            // 画在内容层之上、顶栏之下（遮罩管底、顶栏管顶）
            BottomFadeScrim(
                visible = !anyOverlayMounted && !todoEditing,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            // 顶栏换场：常规顶栏上移淡出、编辑顶栏自上方下移淡入——课表编辑（ScheduleScreen
            // 的 topBarSwap）同规格：同一个 todoEditing 翻转同帧启动、450ms，顶栏换场另配
            // 更缓的 Motion.Standard（Overlay 前重后轻，顶栏会在前 ~100ms 窜完大半行程，
            // 观感太急）；列表让位补差走 editBarExtraSpace（Overlay；进编辑等长按松手才
            // 启动——见 editListReleased，退出时与遮罩/导航栏同帧收回）
            AnimatedContent(
                targetState = !todoEditing,
                contentAlignment = Alignment.TopCenter,
                transitionSpec = {
                    (slideInVertically(
                        animationSpec = tween(Motion.EditMillis, easing = Motion.Standard),
                    ) { -it } + fadeIn(tween(Motion.EditMillis, easing = Motion.Standard))) togetherWith
                        (slideOutVertically(
                            animationSpec = tween(Motion.EditMillis, easing = Motion.Standard),
                        ) { -it } + fadeOut(tween(Motion.EditMillis, easing = Motion.Standard)))
                },
                modifier = Modifier.align(Alignment.TopCenter),
                label = "todoTopBarSwap",
            ) { viewing ->
                if (viewing) {
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
                                tagActive = false
                            }
                        },
                        // 标签特殊分组页胶囊（特殊胶囊之后、置顶分组之前，常驻）+ 点击切换：
                        // 已选中 = 退出关闭（胶囊消失），未选中 = 回到该页（顺带退出特殊分组页）
                        tagPillName = selectedTag,
                        // 胶囊强调色 = 标签色（colorArgb null 已在 tagColorMap 兜底为 primary；
                        // 查不到的残留名同样兜底 primary）
                        tagPillColor = selectedTag?.let { tagColorMap[it] },
                        tagPillSelected = tagActive,
                        onTagPillClick = {
                            if (tagActive) {
                                selectedTag = null
                                tagActive = false
                            } else {
                                tagActive = true
                                specialActive = false
                            }
                        },
                        selectedGroupIndex = selectedGroupIndex,
                        onGroupSelect = {
                            specialActive = false
                            tagActive = false
                            selectedGroupIndex = it
                        },
                        // 更多点击翻转展开态;收起时顺带关排序/分类/视图菜单——菜单锚在展开
                        // 动作行的动作块上,行收起锚点即消失,不先关会悬空
                        onMoreClick = {
                            if (menuExpanded) {
                                sortMenuExpanded = false
                                categoryMenuExpanded = false
                                viewMenuExpanded = false
                            }
                            menuExpanded = !menuExpanded
                        },
                        // 排序块点击展开菜单(SortMenu 三件套挂在动作块处)；三个菜单同锚一行
                        // 互斥，打开一个时先关其余，避免两张菜单卡叠影
                        onSortClick = {
                            categoryMenuExpanded = false
                            viewMenuExpanded = false
                            sortMenuExpanded = true
                        },
                        // 分类块打开分类菜单（同互斥约定；多选开关，点选不收菜单）
                        onCategoryClick = {
                            sortMenuExpanded = false
                            viewMenuExpanded = false
                            categoryMenuExpanded = true
                        },
                        // 视图块打开视图菜单（同互斥约定；两项单选，点选即切换并收菜单）
                        onViewClick = {
                            sortMenuExpanded = false
                            categoryMenuExpanded = false
                            viewMenuExpanded = true
                        },
                        // 分组块打开待办分组浮层（动作行保持展开，关浮层回到原状，不额外折叠）
                        onGroupClick = { groupsVisible = true },
                        menuExpanded = menuExpanded,
                        sortMenuExpanded = sortMenuExpanded,
                        onSortMenuDismiss = { sortMenuExpanded = false },
                        categoryMenuExpanded = categoryMenuExpanded,
                        onCategoryMenuDismiss = { categoryMenuExpanded = false },
                        // 分类块状态：特殊分组页在场 = 置灰不可点（该页本身就是过滤口径，显隐开关
                        // 无意义）；标签页可点（显隐开关作用于标签过滤结果）；其余页面四组未全选
                        // （有分组被隐藏）= primary 提示过滤生效中
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
                        // 视图菜单：两项单选（列表为默认），点选即切换并收菜单；
                        // 动作块图标由 inboxView 决定（列表行 / 收集箱格）。
                        // 最近删除页与已归档页恒走列表：视图块置灰不可点（与分类块在特殊页的置灰同款）
                        inboxView = inboxView,
                        viewEnabled = special != TodoSpecialSection.Trash &&
                            special != TodoSpecialSection.Archived,
                        viewMenuExpanded = viewMenuExpanded,
                        onViewMenuDismiss = { viewMenuExpanded = false },
                        viewMenuSections = listOf(
                            PopupMenuSection(
                                items = listOf(
                                    PopupMenuItem(
                                        label = stringResource(R.string.todo_view_list),
                                        checked = !inboxView,
                                        onClick = {
                                            inboxView = false
                                            viewMenuExpanded = false
                                        },
                                    ),
                                    PopupMenuItem(
                                        label = stringResource(R.string.todo_view_inbox),
                                        checked = inboxView,
                                        onClick = {
                                            inboxView = true
                                            viewMenuExpanded = false
                                        },
                                    ),
                                ),
                            ),
                        ),
                        // 对齐交由 AnimatedContent（TopCenter）负责，本体铺满宽度即可
                        modifier = Modifier,
                        hazeState = hazeState,
                        pillBackdrop = if (glassPillsEnabled) topBarPillBackdrop else null,
                        tabTransition = tabTransition,
                    )
                } else {
                    TodoEditBar(
                        moveEnabled = selectedTodoIds.isNotEmpty(),
                        pinAction = pinAction,
                        onDelete = onDeleteSelected,
                        onMove = {
                            movePickMounted = true
                            movePickVisible = true
                        },
                        onPin = onPinSelected,
                        onUnpin = onUnpinSelected,
                        onConfirm = exitTodoEdit,
                        hazeState = hazeState,
                    )
                }
            }
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
                // 完成情况浮层里的绿/红球切换：按所点分期落库（补完成先归账逾期、取消完成
                // 出完成集合，仓库口径见 setOccurrenceCompleted）；Err Toast 提示
                onOccurrenceCompletedChange = { date, completed ->
                    scope.launch {
                        val result = repository.setOccurrenceCompleted(detail.id, date, completed)
                        if (result is TodoOpResult.Err) AppToasts.show(context, saveFailedMessage)
                    }
                },
                // 倒计时开关：落库后列表卡左区显示剩余天数（详情菜单文案随仓库回流自动切换）；
                // 收集箱视图打开时整项置灰（该视图的卡不渲染倒计时，见 detailCountdownAvailable）
                countdownAvailable = detailCountdownAvailable,
                onCountdownToggle = { enabled ->
                    scope.launch {
                        val result = repository.setCountdown(detail.id, enabled)
                        if (result is TodoOpResult.Err) AppToasts.show(context, saveFailedMessage)
                    }
                },
                // 「添加到日历」（详情菜单项）：权限/账户选择/写入由 calendarAdd 编排；
                // 传详情快照 detail，写入目标锁定点菜单那一刻的内容
                onAddToCalendar = { calendarAdd.begin(detail) },
                // 「归档」（详情菜单项）：落库后待办移入已归档页，本浮层随活跃列表反查
                // 落空自动收起（同删除后详情的收场路径）；目标 id 同步显式清空——归档条目
                // 取消归档后会重回活跃列表，残留 id 会让浮层凭空重新弹出；Err Toast 提示、
                // 浮层保持打开
                onArchive = {
                    scope.launch {
                        when (repository.archiveTodo(detail.id)) {
                            TodoOpResult.Ok -> {
                                detailTargetId = ""
                                detailTargetDate = ""
                                AppToasts.show(context, todoArchivedMessage)
                            }
                            is TodoOpResult.Err -> AppToasts.show(context, groupSaveFailedMessage)
                        }
                    }
                },
            )
        }
    }
    // 日历账户选择卡：画在详情浮层之后（组合顺序即绘制顺序，盖在详情之上），
    // 两段式挂载收口在 CalendarPickerHost
    CalendarPickerHost(calendarAdd, hazeState = hazeState)
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
    // 分组编辑态在浮层内自治：顶栏「确认」经 onGroupsChange 写回（置顶/重排），
    // 底栏「删除」经 onDeleteGroups 立即从 groups 移除（不可经确认框取消撤销）、
    // 「重命名」经 onGroupsChange（立即生效，renames 映射让顶栏跟随新名）；
    // 标签编辑态同构自治：「确认」经 onTagsChange 写回重排，「重命名/删除」经
    // onRenameTag/onDeleteTags 立即生效（待办侧回写由宿主完成）；两种编辑态互斥；
    // 普通态点击分组/「未分组」/「全部」经 onOpen* 跳转：顶栏胶囊切到对应下标 + 收起浮层；
    // 第一部分（状态/留存）行经 onOpenSpecial 打开对应特殊分组页（顶栏首位出胶囊，收起浮层）
    // ——切常规分组只离页（胶囊保留、转未选态），再点未选胶囊回页、点已选胶囊才退出关闭
    if (groupsVisible) groupsMounted = true
    if (groupsMounted) {
        TodoGroupsSheet(
            todos = todos,
            deletedCount = deletedTodos.size,
            archivedCount = archivedTodos.size,
            groups = groups,
            tags = userTagEntries,
            courseTags = courseTagNames,
            visible = groupsVisible,
            onDismiss = { groupsVisible = false },
            onDismissed = { groupsMounted = false },
            onGroupsChange = commitGroups,
            onDeleteGroups = { names -> commitGroups(groups.filterNot { it.name in names }, emptyMap()) },
            onOpenTag = { name ->
                selectedTag = name
                tagActive = true
                specialActive = false
                groupsVisible = false
            },
            onTagsChange = commitTagsOrder,
            onRenameTag = commitTagRename,
            onDeleteTags = commitTagDelete,
            onOpenGroup = { name ->
                val index = groups.indexOfFirst { it.name == name }
                if (index >= 0) selectedGroupIndex = index + 2
                specialActive = false
                tagActive = false
                groupsVisible = false
            },
            onOpenUngrouped = {
                selectedGroupIndex = 1
                specialActive = false
                tagActive = false
                groupsVisible = false
            },
            onOpenAll = {
                selectedGroupIndex = 0
                specialActive = false
                tagActive = false
                groupsVisible = false
            },
            // 第一部分（状态/留存）行：打开对应特殊分组页（顶栏首位出胶囊并置为选中），收起浮层
            onOpenSpecial = { section ->
                specialSection = section
                specialActive = true
                tagActive = false
                groupsVisible = false
            },
        )
    }

    // 移动-选择分组浮层：编辑顶栏「移动」唤起（复用新建/编辑浮层的 GroupPickSheet）——
    // 未分组单行卡 + 自定义分组卡，点选即经 onMovePick 整批移动；「新建」经 commitGroups
    // 当场建组落库（与新建待办浮层同款，建完留在列表等点选）。画在分组浮层之后，
    // 浮层自带的 BackHandler 后注册：返回键先关本浮层，再轮到编辑态出口
    if (movePickVisible) movePickMounted = true
    if (movePickMounted) {
        GroupPickSheet(
            todos = todos,
            groups = groups,
            visible = movePickVisible,
            onDismiss = { movePickVisible = false },
            onDismissed = { movePickMounted = false },
            onGroupsChange = commitGroups,
            onPick = onMovePick,
        )
    }

    // 浮层在场（含收场动画期间）或待办编辑态就藏底部导航栏，浮层卸载/退出编辑后才放回
    //（走 MainActivity 现有的导航栏 AnimatedVisibility，EditMillis + Overlay 同规格下移
    // 出屏/上移回位，与课表页编辑态同一个翻转同帧启动的模型一致）；
    // 四个浮层任一在场都算，避免分组/详情/编辑浮层开着时导航栏提前露回
    // （anyOverlayMounted 的计算提前到了页面布局之前，底部渐变遮罩同读这一份）
    LaunchedEffect(anyOverlayMounted, todoEditing) {
        onOverlayOverNavBarChange(anyOverlayMounted || todoEditing)
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

/**
 * 四个分组的口径（列表视图与收集箱视图共用同一份）：
 * - 置顶组收编全部置顶条目（移动语义，不与其余组重复）；其余组只在非置顶里划分；
 * - 分类菜单取消勾选的组整组不进结果（visibleSections 过滤，空列表 = 四组全隐）；
 * - 组内按当前排序 [sort]/[sortDescending] 排；
 * - 空组不返回（列表不渲染空分区头、收集箱不渲染空卡）。
 */
private fun buildTodoSections(
    todos: List<Todo>,
    sort: TodoSort,
    sortDescending: Boolean,
    today: IsoDate,
    nowMinute: Int,
    visibleSections: Set<TodoSection>,
): List<Pair<TodoSection, List<Todo>>> {
    val pinned = todos.filter { it.pinned }.sortedFor(sort, sortDescending)
    val overdue = todos.filter { !it.pinned && it.isOverdue(today, nowMinute) }
        .sortedFor(sort, sortDescending)
    val active = todos.filter { !it.pinned && !it.completed && !it.isOverdue(today, nowMinute) }
        .sortedFor(sort, sortDescending)
    val done = todos.filter { !it.pinned && it.completed }.sortedFor(sort, sortDescending)
    return listOfNotNull(
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

/** 收集箱视图的卡片间距：两列之间与同列上下卡之间都取 10dp（需求定值） */
private val InboxCardSpacing = 10.dp

/**
 * 收集箱视图：四个分组各一张 [TodoInboxCard]。
 *
 * - 三至四个分区（常规分组页/标签页）：按「瀑布流视图」开关（[masonryView]）——
 *   开启（默认）走两列瀑布流，按卡片实际高度自动落进较短的一列（置顶/逾期各占一列后，
 *   未完成落进较矮的右列，已完成补左列，与设计稿的四卡排布一致）；关闭则整宽竖排；
 * - 一至两个分区（特殊分组页只过滤出一组，或常规页只剩两组）：始终整宽竖排——两个
 *   分区不两列并排，半宽卡会把标题与属性行挤得过窄；
 * - 分区口径、卡片内顺序、完成回显与列表视图完全同源（见 [buildTodoSections] 与
 *   [TodoScreen] 的 completionEcho）：勾选后卡片即时变色/划线，停一拍落库，
 *   条目随后在「未完成 ↔ 已完成」两卡之间移动；
 * - 卡片只显示标题 + 时间/截止/重复，不渲染标签、紧急旗、倒计时（组件口径）；
 *   头部强调色按分组：已完成 urgentGreen、已逾期 error、其余 primary，图标取对应
 *   特殊分组图标（已完成勾选框 / 未完成空框 / 已逾期警告框 / 已置顶图钉）；
 * - 点击行经 [onTodoClick] 交宿主拉起详情浮层（详情里的倒计时开关由宿主按视图置灰：
 *   本视图的卡不渲染倒计时，开启后无任何可视反馈）；勾选框经 [onToggleCompleted] 落库；
 * - 最近删除页不进入本视图（调用方保留列表：撤回/彻底删除需要整卡动作）；
 * - 空结果沿用列表的居中占位文案（特殊页/标签页），常规页为空时不渲染内容。
 *
 * 滚动与列表视图同一套边距与手感：顶部让出状态栏 + 顶栏 + 动作行（[topBarExtraSpace]），
 * 底部留出导航栏与浮动导航的余量；页面横向边距取 [PageHorizontalSpacing]；网格经
 * [RubberBandLazyVerticalStaggeredGrid]（页面 [ProvideOverscroll] 作用域内）——内容超一屏
 * 时边缘拉出橡皮筋，收集箱常见的不满一屏同样能拉（foundation 派发门槛的兜底同列表）。
 */
@Composable
private fun TodoInboxContent(
    todos: List<Todo>,
    sort: TodoSort,
    sortDescending: Boolean,
    visibleSections: Set<TodoSection>,
    emptyHint: String?,
    topBarExtraSpace: Dp,
    completionEcho: Map<String, Boolean>,
    flyDoneKeys: Set<String>,
    // 收集箱「瀑布流视图」开关（个性化页 State 透传）：值在此叶子读取
    masonryView: State<Boolean>,
    onTodoClick: (Todo) -> Unit,
    onToggleCompleted: (Todo, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalToday
    val nowMinute = currentMinuteOfDay()
    val sections = remember(todos, sort, sortDescending, today, nowMinute, visibleSections) {
        buildTodoSections(todos, sort, sortDescending, today, nowMinute, visibleSections)
    }
    val density = LocalDensity.current
    val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    // 网格滚动位提升到空结果早返回之前：组清空再恢复时不重置滚动；状态随 CrossPages 的
    // SaveableStateProvider 按页面 key 保活（列表视图与收集箱视图各存各的滚动位）
    val gridState = rememberLazyStaggeredGridState()
    if (sections.isEmpty()) {
        if (emptyHint != null) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(top = topInset + TopBarRowSpace + ListTopSpacing + topBarExtraSpace),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyHint,
                    style = ClassppTextTokens.emptyHint,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            }
        }
        return
    }
    // 一至两个分区始终整宽竖排（两张也竖排，不挤成半宽并排——窄卡放不下标题与
    // 时间/截止/重复；特殊分组页的单卡同理）；三个分区起由「瀑布流视图」开关决定：
    // 开启（默认）走两列瀑布流，关闭则同样整宽竖排
    val fullWidthCards = sections.size <= 2 || !masonryView.value
    RubberBandLazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = topInset + TopBarRowSpace + ListTopSpacing + topBarExtraSpace,
            bottom = bottomInset + ListBottomSlack,
            start = PageHorizontalSpacing,
            end = PageHorizontalSpacing,
        ),
        horizontalArrangement = Arrangement.spacedBy(InboxCardSpacing),
        verticalItemSpacing = InboxCardSpacing,
    ) {
        sections.forEach { (section, group) ->
            item(
                key = "inbox_" + section.name,
                span = if (fullWidthCards) {
                    StaggeredGridItemSpan.FullLine
                } else {
                    StaggeredGridItemSpan.SingleLane
                },
            ) {
                // 展示字段的格式化函数（cardTimeText / deadlineColor 等）是 @Composable，
                // 只能在组合上下文里现算，不进 remember
                val cardItems = group.map { todo ->
                    val key = todo.listItemKey(flyDoneKeys)
                    TodoInboxItem(
                        id = key,
                        title = todo.name,
                        time = todo.cardTimeText(),
                        deadline = todo.deadlineCardText(),
                        deadlineColor = todo.deadlineColor(),
                        repeat = todo.repeatCardText(),
                        // 乐观回显覆盖仓库值：勾选当帧先变色/划线（同列表卡）
                        completed = completionEcho[key] ?: todo.completed,
                        // 逾期卡整卡红标题；已完成优先（两者都真取完成灰，同 NoteCard）
                        overdue = section == TodoSection.Overdue,
                    )
                }
                // 卡片行 id → 原始待办（含分期副本）：点击/勾选回传完整模型给宿主
                val todoByItemId = group.associateBy { it.listItemKey(flyDoneKeys) }
                // 头部强调色与图标：已完成绿、已逾期红、其余 primary（颜色规范 4.3）
                val accent = when (section) {
                    TodoSection.Done -> MaterialTheme.classppColors.urgentGreen
                    TodoSection.Overdue -> MaterialTheme.colorScheme.error
                    TodoSection.Pinned, TodoSection.Active -> MaterialTheme.colorScheme.primary
                }
                val icon = when (section) {
                    TodoSection.Pinned -> R.drawable.ic_pin
                    TodoSection.Overdue -> R.drawable.ic_checkbox_warning
                    TodoSection.Active -> R.drawable.ic_checkbox_unchecked
                    TodoSection.Done -> R.drawable.ic_checkbox_checked
                }
                TodoInboxCard(
                    title = stringResource(section.titleRes),
                    items = cardItems,
                    onTodoClick = { item -> todoByItemId[item.id]?.let(onTodoClick) },
                    iconRes = icon,
                    headerColor = accent,
                    compact = !fullWidthCards,
                    onItemCheckedChange = { item, checked ->
                        todoByItemId[item.id]?.let { onToggleCompleted(it, checked) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 列表项 animateItem 三条规格：进场淡入 / 避让收拢 / 退场淡出——曲线与时长全从 [Motion]
 * 取（TodoCardEnter/Place/ExitMillis，Decelerate/Accelerate 族对）。进场淡入带
 * TodoCardEnterDelayMillis 延迟：退场卡先谢幕一拍，同位置补位的新卡（重复待办完成后
 * 前移的「下一期」）才淡入，两段内容几乎相同的交叉动画错开，完成不再读作闪烁。
 * 分区头与卡片、常规列表与最近删除页共用同一套，同屏节奏才一致。
 */
private val TodoListFadeInSpec: FiniteAnimationSpec<Float> =
    tween(
        durationMillis = Motion.TodoCardEnterMillis,
        delayMillis = Motion.TodoCardEnterDelayMillis,
        easing = Motion.Decelerate,
    )

private val TodoListPlaceSpec: FiniteAnimationSpec<IntOffset> =
    tween(durationMillis = Motion.TodoCardPlaceMillis, easing = Motion.Decelerate)

private val TodoListFadeOutSpec: FiniteAnimationSpec<Float> =
    tween(durationMillis = Motion.TodoCardExitMillis, easing = Motion.Accelerate)

/** 完成副本与未完成副本共享的基础 key（不含完成态段）：待办 id + 分期日期后缀（同侧滑互斥 key 口径） */
private fun Todo.listItemKeyBase(): String =
    "card_" + id + "#" + (dates.firstOrNull()?.epochDay?.toString() ?: "")

/**
 * 待办列表项的 LazyColumn key：基础 key（[listItemKeyBase]）之上，完成副本默认追加
 * "#done" 段。完成态分段是刻意的：勾选完成/取消完成时条目会在未完成/已完成两组间换位，
 * 若两组共用同一 key，LazyColumn 把它当作**同一条目的位移**——落点在视口附近时是好看
 * 的「飞入已完成组」，但已完成列表超过一屏时落点远离视口，220ms 的避让动画会让卡片从
 * 原位掠过整屏直坠组内（item 出窗即被回收时则直接消失），读作闪烁。
 *
 * 折中（飞入手感与远近稳定兼得）：勾选时刻由列表按落点可见性一次性判定
 * （[shouldFlyCompletedIntoView]），判可飞入的完成副本由宿主登记进 [flyDoneKeys]、渲染
 * 原 key（同 key 位移 = 飞入）；其余完成副本带 "#done" 段走「原组退场淡出 + 新组延迟
 * 淡入」（与分组折叠的进出场同构，远近都稳）。登记只在勾选事件写入、不随重组重算，
 * key 在帧间保持稳定。乐观回显（TodoScreen 的 completionEcho）按被点卡片自身的 key
 * 读写，两种副本各取各的段，回显不会错位。
 * "card_" 前缀与分区头的 "header_" key 隔离命名空间。
 */
private fun Todo.listItemKey(flyDoneKeys: Set<String>): String {
    val base = listItemKeyBase()
    return if (completed && base !in flyDoneKeys) base + "#done" else base
}

/**
 * 「飞入已完成组」的落点可见性判定（勾选时刻一次性快照，结果由宿主记入 flyDoneKeys）：
 * 已完成组展开、分组头在视口内、且完成副本按排序将落入的条目（分组头下方第 N 张卡，
 * 卡高用视口内卡片的实测高近似）顶端仍在视口内，才同 key 飞入——落点远离视口时飞入
 * 会掠过整屏，退回「退场 + 入场」。被点卡片必然可见而已完成组整体在其下方，分组头
 * 可见性即是落点远近的充分代理；估计只需分辨屏内/屏外，一张卡高的误差可容忍。
 */
private fun shouldFlyCompletedIntoView(
    layoutInfo: LazyListLayoutInfo,
    doneCards: List<Todo>,
    copy: Todo,
    sort: TodoSort,
    sortDescending: Boolean,
): Boolean {
    if (doneCards.isEmpty()) return false
    val visible = layoutInfo.visibleItemsInfo
    val header = visible.firstOrNull { it.key == "header_" + TodoSection.Done.name } ?: return false
    val cardHeight = visible.firstOrNull { (it.key as? String)?.startsWith("card_") == true }?.size ?: return false
    if (cardHeight <= 0) return false
    // 与实际列表序一致的比较器：sortedFor 是稳定排序，同键并列保持分期升序（同系列
    // 完成副本按 epochDay 依次排布），补一条 epochDay 升序次级键才能数对落点位
    val comparator = sort.comparator(sortDescending)
        .then(compareBy<Todo> { it.dates.firstOrNull()?.epochDay ?: Long.MIN_VALUE })
    val landingIndex = doneCards.count { comparator.compare(it, copy) < 0 }
    val landingTop = header.offset + header.size + landingIndex * cardHeight
    return landingTop >= layoutInfo.viewportStartOffset && landingTop < layoutInfo.viewportEndOffset
}

/** CrossPages 页面 key 的前缀（本文件私有）：前缀决定身份段，解码只按前缀 removePrefix */
private const val TodoPageKeyGroupPrefix = "group_"
private const val TodoPageKeySpecialPrefix = "special_"
private const val TodoPageKeyTagPrefix = "tag_"

/**
 * 收集箱视图段前缀：放在身份段最前（`inbox_group_0` / `inbox_tag_工作` /
 * `inbox_special_Completed`）。列表页不带该段（key 维持原样），解码先剥它再按下方
 * 三类身份段解析；最近删除页不编码视图段（回收站恒走列表）。
 */
private const val TodoPageKeyInboxPrefix = "inbox_"

/**
 * 待办页的页面身份（CrossPages key 的解码结果）：特殊分组页 / 标签页 / 常规分组下标
 * 三选一，外加视图段 [inbox]（列表 = false、收集箱 = true——视图切换也是整页交叉
 * 过渡的一种，键值见 [TodoPageKeyInboxPrefix]）。key 编码为字符串是因为 CrossPages 的
 * 状态保活要把 key 写进 Bundle（SaveableStateProvider 的 key 须 Bundle 可存）；前缀
 * 决定身份段——标签名里含下划线也不会错位（解码只 removePrefix，不按分隔符切分）。
 */
private data class TodoPageIdentity(
    val special: TodoSpecialSection? = null,
    val tag: String? = null,
    val groupIndex: Int = 0,
    val inbox: Boolean = false,
)

/** 解码页面 key（编码见 [TodoPageKeyInboxPrefix] 等前缀常量处说明）：先剥视图段再解身份段 */
private fun String.toTodoPageIdentity(): TodoPageIdentity {
    val inbox = startsWith(TodoPageKeyInboxPrefix)
    val identityKey = if (inbox) removePrefix(TodoPageKeyInboxPrefix) else this
    return when {
        identityKey.startsWith(TodoPageKeySpecialPrefix) ->
            TodoPageIdentity(
                special = TodoSpecialSection.valueOf(identityKey.removePrefix(TodoPageKeySpecialPrefix)),
                inbox = inbox,
            )
        identityKey.startsWith(TodoPageKeyTagPrefix) ->
            TodoPageIdentity(tag = identityKey.removePrefix(TodoPageKeyTagPrefix), inbox = inbox)
        else ->
            TodoPageIdentity(
                groupIndex = identityKey.removePrefix(TodoPageKeyGroupPrefix).toIntOrNull() ?: 0,
                inbox = inbox,
            )
    }
}

/** 已折叠分区集的保存器：枚举按名字存取（未知名字丢弃，防枚举变更后恢复崩溃） */
private val CollapsedSectionsSaver = listSaver<Set<TodoSection>, String>(
    save = { sections -> sections.map(TodoSection::name) },
    restore = { names ->
        names.mapNotNull { name -> TodoSection.entries.firstOrNull { it.name == name } }.toSet()
    },
)

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
 * 已逾期/已置顶实时过滤。已归档与最近删除的条目都不在活跃列表里（仓库单列 archivedTodos /
 * deletedTodos 流），由宿主按归档/删除时刻倒序直接取用，这里同样返回空。
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
    // 已归档走仓库的 archivedTodos 流、最近删除走 deletedTodos 流（都不在活跃列表里）
    TodoSpecialSection.Archived, TodoSpecialSection.Trash -> emptyList()
}

/**
 * 按标签派生可见待办（标签特殊分组页）：[Todo.tags] 含该标签名的活跃待办。
 * 页内仍按置顶/逾期/未完成/已完成分区展示（复用 [TodoList] 的固定分组渲染，置顶收编
 * 语义不变——「已置顶」页单卡侧滑即可撤销置顶）。
 */
internal fun filterTodosByTag(todos: List<Todo>, tag: String): List<Todo> =
    todos.filter { tag in it.tags }

/**
 * 待办列表：已置顶、已逾期、未完成、已完成各归一组——已置顶组在最上（所有置顶待办，
 * 无置顶待办时整组不渲染）、已逾期组次之（标题标红的卡片，截止日期已过且未完成）、
 * 未完成居中、已完成在下（默认收起），空组不渲染；
 * 组内按顶栏选定的排序与方向（默认创建时间降序——创建早的在前）。置顶条目归已置顶组、
 * 不再进其余三组（移动而非重复展示，同分组置顶的语义）。空组不渲染的另一条路是
 * 分类菜单：[visibleSections] 未含的组整组隐藏（默认全组可见）。渲染结构：分区头与
 * 卡片各自是独立 LazyColumn item（头 key = "header_" + 分区名、卡 key 见
 * [Todo.listItemKey]），每项挂 Modifier.animateItem——进出场淡入淡出与邻居避让收拢
 * 全走 [TodoListFadeInSpec] / [TodoListPlaceSpec] / [TodoListFadeOutSpec] 三条规格
 * （Motion.TodoCard* 令牌）：勾选完成/侧滑删除/置顶的跨组移动 = 原组退场 + 新组进场
 * 两侧同时收拢，排序切换 = 组内避让，整组清空 = 分区头与余卡一起淡出。分组/页面级
 * 切换不经过条目动画——宿主以 CrossPages 按页叠放、整页交叉过渡（见 TodoScreen），
 * 本组件的条目进出场只服务页内增删移与折叠。折叠即宿主停止
 * 下发该组卡片项，收合同样由避让动画完成——分区头照常渲染（见 [NoteCardSectionHeader]），
 * 折叠集由本组件持有（rememberSaveable，编辑态强制展开不写回）。卡片经 [TodoCard]
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
 * 最近删除页（[deletedCards] = true）走另一条渲染分支：列表顶部固定一条保留期
 * [SheetInfoBanner]（满 30 天由仓库自动彻底删除，见 [TodoRepository.purgeExpiredDeleted]），
 * 卡片逐项平铺（无四组分区头、
 * 同一套 animateItem 规格，卡间距走每项底部内距）——条目由宿主按删除时刻倒序传入，与
 * 「最近删除」的时间语义一致；卡片为已删除样式（右侧撤回按钮），左滑呼出「彻底删除」。
 * 已归档页（[archivedCards] = true）同样逐项平铺：无保留期提示条（归档不自动清理），
 * 卡片为已归档样式（右侧取消归档按钮，左滑软删进最近删除——归档条目不接点击，常规写
 * 路径对归档条目不可见），条目由宿主按归档时刻倒序传入。
 *
 * 滚动容器为 [RubberBandLazyColumn]（页面 [ProvideOverscroll] 作用域内）：内容超一屏
 * 时滚到边缘拉出橡皮筋，待办很少、不足一屏时同样能拉——短内容是 foundation 派发门槛
 * 拦下的场景，由该封装的兜底手势层接管。
 *
 * 待办编辑态（[editing] = true，长按卡片进入，见 [TodoCard.onLongClick]）复用同一渲染
 * 转入选中形态：卡片点按由详情改为切换选中、长按停用，分组头经
 * [NoteCardSectionHeader.editMode] 把展开箭头换成「全选/取消全选」（按组内待办 id 整组
 * 增删 [selectedTodoIds]，重复待办的分期卡同享一个 id，整系列同选同撤），卡片项强制
 * 下发——渲染条件叠加而不写回折叠集，退出编辑恢复原折叠选择；侧滑与完成勾选停用。
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
    // 已归档页：平铺卡片（无四组分区）、已归档样式（右取消归档；左滑软删进最近删除）
    archivedCards: Boolean = false,
    topBarExtraSpace: Dp,
    // 编辑顶栏让位补差（EditBarRowSpace − TopBarRowSpace 的动画值，宿主随 todoEditing 下发）
    editBarExtraSpace: Dp = 0.dp,
    swipeCoordinator: SwipeCoordinator,
    // 待办编辑态：卡片转选中形态、分组头强制展开并换「全选/取消全选」、侧滑与勾选停用
    editing: Boolean = false,
    // 编辑态选中集（待办 id；重复待办整系列共享一个 id）
    selectedTodoIds: Set<String> = emptySet(),
    // 勾选完成的乐观回显（列表项 key → 目标完成态，宿主持有）：卡片完成态由它覆盖
    // 仓库值，点勾当帧先播勾选/划线动画，仓库回流后由宿主清条目（见 TodoScreen 的
    // completionEcho）；缺席条目照常读仓库值
    completionEcho: Map<String, Boolean> = emptyMap(),
    // 「飞入已完成组」的完成副本登记（基础 key 集，宿主持有）：命中的完成副本渲染原
    // key 走同 key 位移「飞入」，其余带 #done 段走退场+入场（见 [Todo.listItemKey]）
    flyDoneKeys: Set<String> = emptySet(),
    // 长按卡片进编辑（可用性闸门在宿主回调内：特殊分组页关断）；编辑态下卡片长按不触发
    onEnterEdit: (String) -> Unit = {},
    // 编辑态单卡选中切换
    onToggleSelect: (String) -> Unit = {},
    // 编辑态分组头「全选/取消全选」：整组待办 id 并入（select = true）/移出选中集
    onToggleSelectAll: (Set<String>, select: Boolean) -> Unit = { _, _ -> },
    // 待办整条传入而非只传 id：分期卡（重复待办的展开副本）要靠 dates[0] 路由到逐期结算；
    // 第三参 flyIntoDone = 勾选时刻的落点可见性判定（是否同 key 飞入已完成组，见
    // shouldFlyCompletedIntoView），宿主据此维护 flyDoneKeys
    onToggleCompleted: (todo: Todo, completed: Boolean, flyIntoDone: Boolean) -> Unit,
    onTogglePinned: (id: String, pinned: Boolean) -> Unit,
    onDeleteTodo: (id: String) -> Unit,
    onRestoreTodo: (id: String) -> Unit = {},
    onUnarchiveTodo: (id: String) -> Unit = {},
    onPurgeTodo: (id: String) -> Unit = {},
    onTodoClick: (todo: Todo) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 逾期判定的"此刻"：当天日期取 [LocalToday]（跨零点自动跳天）——零点多后即使没有别的
    // 交互，逾期分组也会自己重算；分钟口径仍是重组时现取
    val today = LocalToday
    val nowMinute = currentMinuteOfDay()
    val sections = remember(todos, sort, sortDescending, today, nowMinute, visibleSections) {
        buildTodoSections(todos, sort, sortDescending, today, nowMinute, visibleSections)
    }
    // 分区折叠态（原 NoteCardSection 组件内部的 rememberSaveable 提升至宿主）：旋转/切
    // tab/进程重建不丢。编辑态的强制展开只是渲染条件叠加（卡片项下发条件 editing ||
    // 未折叠），不写回这份集合，退出编辑自动恢复用户此前对各组的折叠选择。初始值沿用
    // 旧口径——常规列表「已完成默认收起」、特殊页/标签页全展开；rememberSaveable 只在
    // 首次组合取初值，expandAllSections 中途翻转不重置（与旧组件 init-once 语义一致）。
    // 与旧实现的唯一行为差异：分区因清空而整体消失、之后再出现时，折叠选择不再重置
    // 回初值——折叠是用户偏好，跟随而不是归零
    var collapsedSections by rememberSaveable(stateSaver = CollapsedSectionsSaver) {
        mutableStateOf(if (expandAllSections) emptySet() else setOf(TodoSection.Done))
    }
    val toggleSectionCollapsed: (TodoSection) -> Unit = { section ->
        collapsedSections =
            if (section in collapsedSections) collapsedSections - section else collapsedSections + section
    }
    val density = LocalDensity.current
    val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    // 列表状态提升到本组件（RubberBandLazyColumn 本有默认参数，显式传入）：勾选路由要读
    // layoutInfo 判定「飞入已完成组」的落点可见性（见 onCardToggleCompleted）
    val listState = rememberLazyListState()
    // 勾选路由包装：完成方向先按当前布局一次性判定落点可见性（快照经回调交给宿主记入
    // flyDoneKeys，key 方案见 [Todo.listItemKey]），再交宿主落库；已完成组收起时无落点
    // 可言、直接不飞。取消完成不判定——飞回与否由完成时的登记决定（见 TodoScreen
    // toggleTodoCompleted 的取消结算）
    val onCardToggleCompleted: (Todo, Boolean) -> Unit = { todo, completed ->
        val flyIntoDone = completed &&
            TodoSection.Done !in collapsedSections &&
            shouldFlyCompletedIntoView(
                layoutInfo = listState.layoutInfo,
                doneCards = sections.firstOrNull { it.first == TodoSection.Done }?.second.orEmpty(),
                copy = todo.copy(completed = true),
                sort = sort,
                sortDescending = sortDescending,
            )
        onToggleCompleted(todo, completed, flyIntoDone)
    }
    RubberBandLazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = topInset + TopBarRowSpace + ListTopSpacing + topBarExtraSpace + editBarExtraSpace,
            bottom = bottomInset + ListBottomSlack,
            start = PageHorizontalSpacing,
            end = PageHorizontalSpacing,
        ),
    ) {
        if (deletedCards) {
            // 最近删除页：顶部固定一条保留期说明（满 30 天自动彻底删除，见仓库
            // purgeExpiredDeleted），逐卡 item 平铺（无四组分组头，卡间距走每项底部内距、
            // 末卡不带）——顺序由宿主按删除时刻倒序给定，与「最近删除」的时间语义一致；
            // 空列表沿用统一的居中占位文案（提示条与占位同处一个 item，占位仍在剩余空间
            // 居中，不因提示条把整页撑出可滚动的一屏）。撤回/彻底删除后卡片走 animateItem
            // 退场，其余卡片避让收拢
            if (todos.isEmpty()) {
                item(key = "trashEmptyState") {
                    Column(Modifier.fillParentMaxSize()) {
                        SheetInfoBanner(
                            text = stringResource(R.string.todo_trash_retention_hint, TRASH_RETENTION_DAYS),
                        )
                        if (emptyHint != null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = emptyHint,
                                    style = ClassppTextTokens.emptyHint,
                                    color = MaterialTheme.classppColors.secondaryText,
                                )
                            }
                        }
                    }
                }
            } else {
                item(key = "trashRetentionBanner") {
                    SheetInfoBanner(
                        text = stringResource(R.string.todo_trash_retention_hint, TRASH_RETENTION_DAYS),
                        modifier = Modifier.padding(bottom = CardSectionSpacing),
                    )
                }
                todos.forEachIndexed { index, todo ->
                    item(key = todo.listItemKey(flyDoneKeys)) {
                        TrashedTodoCard(
                            todo = todo,
                            tagColors = tagColors,
                            swipeCoordinator = swipeCoordinator,
                            onRestore = { onRestoreTodo(todo.id) },
                            onPurge = { onPurgeTodo(todo.id) },
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = TodoListFadeInSpec,
                                    placementSpec = TodoListPlaceSpec,
                                    fadeOutSpec = TodoListFadeOutSpec,
                                )
                                .padding(
                                    bottom = if (index < todos.lastIndex) CardSectionSpacing else 0.dp,
                                ),
                        )
                    }
                }
            }
        } else if (archivedCards) {
            // 已归档页：逐卡平铺（无四组分组头；归档无保留期语义，也没有回收站那条提示条）
            // ——顺序由宿主按归档时刻倒序给定（最近归档的在最前），卡片为已归档样式
            // （右侧取消归档按钮；左滑软删进最近删除，归档标记保留）；取消归档/删除后卡片
            // 走 animateItem 退场，其余卡片避让收拢。空页给居中占位文案（与特殊分组页共用
            // emptyHint）
            if (todos.isEmpty()) {
                if (emptyHint != null) {
                    item(key = "archivedEmptyState") {
                        Box(
                            Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = emptyHint,
                                style = ClassppTextTokens.emptyHint,
                                color = MaterialTheme.classppColors.secondaryText,
                            )
                        }
                    }
                }
            } else {
                todos.forEachIndexed { index, todo ->
                    item(key = todo.listItemKey(flyDoneKeys)) {
                        ArchivedTodoCard(
                            todo = todo,
                            tagColors = tagColors,
                            swipeCoordinator = swipeCoordinator,
                            onDelete = { onDeleteTodo(todo.id) },
                            onUnarchive = { onUnarchiveTodo(todo.id) },
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = TodoListFadeInSpec,
                                    placementSpec = TodoListPlaceSpec,
                                    fadeOutSpec = TodoListFadeOutSpec,
                                )
                                .padding(
                                    bottom = if (index < todos.lastIndex) CardSectionSpacing else 0.dp,
                                ),
                        )
                    }
                }
            }
        } else {
            // 空列表占位：特殊分组页过滤结果为空时给居中文案；
            // 常规列表不传 emptyHint，全组被分类菜单隐藏时维持原空白行为
            if (sections.isEmpty() && emptyHint != null) {
                item(key = "emptyHint") {
                    Box(
                        Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = emptyHint,
                            style = ClassppTextTokens.emptyHint,
                            color = MaterialTheme.classppColors.secondaryText,
                        )
                    }
                }
            }
            sections.forEachIndexed { index, (section, group) ->
                // 分区头独立成 item：整组清空时头与余卡一起走 animateItem 退场（淡出 +
                // 邻组避让收拢），不再瞬时消失。组间距给首组之外的每组：首组贴列表起点，
                // 再加间距会比下面的组低一截
                item(key = "header_" + section.name) {
                    // 分组头「全选/取消全选」的口径：组内待办 id 集（分期卡同享 id，整系列同选同撤）
                    val sectionIds = remember(group) { group.map { it.id }.toSet() }
                    val allSelected = sectionIds.isNotEmpty() && sectionIds.all { it in selectedTodoIds }
                    NoteCardSectionHeader(
                        title = stringResource(section.titleRes),
                        expanded = section !in collapsedSections,
                        onToggleClick = { toggleSectionCollapsed(section) },
                        editMode = editing,
                        allSelected = allSelected,
                        onSelectAllToggle = if (editing) {
                            { onToggleSelectAll(sectionIds, !allSelected) }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = TodoListFadeInSpec,
                                placementSpec = TodoListPlaceSpec,
                                fadeOutSpec = TodoListFadeOutSpec,
                            )
                            .then(if (index > 0) Modifier.padding(top = SectionSpacing) else Modifier),
                    )
                }
                // 卡片逐张成 item：进出场淡入淡出与邻居避让收拢全走 animateItem
                //（TodoList*Spec 三条规格）。折叠 = 宿主不下发卡片项（编辑态强制下发、
                // 不写回折叠集，退出编辑恢复原折叠选择）；卡间距走每项底部内距、末卡
                // 不带——与旧 Column spacedBy「只在卡与卡之间留白」口径一致，末卡身份
                // 随增删变化时的差值由避让动画顺滑带过
                if (editing || section !in collapsedSections) {
                    group.forEachIndexed { cardIndex, todo ->
                        item(key = todo.listItemKey(flyDoneKeys)) {
                            TodoCard(
                                todo = todo,
                                // 完成态先经乐观回显覆盖（见 TodoScreen 的 completionEcho）
                                completed = completionEcho[todo.listItemKey(flyDoneKeys)] ?: todo.completed,
                                tagColors = tagColors,
                                overdue = section == TodoSection.Overdue,
                                editing = editing,
                                selected = todo.id in selectedTodoIds,
                                swipeCoordinator = swipeCoordinator,
                                onDelete = { onDeleteTodo(todo.id) },
                                onTogglePinned = { onTogglePinned(todo.id, !todo.pinned) },
                                // 编辑态点卡片（任意位置）切换选中，常规态拉起详情浮层
                                onClick = { if (editing) onToggleSelect(todo.id) else onTodoClick(todo) },
                                onLongClick = { onEnterEdit(todo.id) },
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = TodoListFadeInSpec,
                                        placementSpec = TodoListPlaceSpec,
                                        fadeOutSpec = TodoListFadeOutSpec,
                                    )
                                    .padding(
                                        bottom = if (cardIndex < group.lastIndex) CardSectionSpacing else 0.dp,
                                    ),
                            ) { onCardToggleCompleted(todo, it) }
                        }
                    }
                }
            }
        }
    }
}

/** 来源徽标文本：课程中心同步的待办显示课程名（缺失时回落「课程中心」）；用户手建无徽标 */
@Composable
private fun Todo.sourceLabel(): String? =
    if (source == XmuTodoMapper.SOURCE_XMU) {
        sourceDetail?.takeIf { it.isNotBlank() } ?: stringResource(R.string.xmu_source_badge)
    } else {
        null
    }

/**
 * 单条待办卡片：[Todo] → [NoteCard] 的字段接线——标题、完成态与勾选直通，
 * 待办时间走 [cardTimeText]、截止时间走 [deadlineCardText]（颜色由 [deadlineColor]
 * 按剩余天数映射）、重复规则走 [repeatCardText]（primary 蓝，排在两个时间项之后），
 * 标签按模型透传并经 [tagColors] 着色（TagChoosingCard 挑选的颜色，见 tagColorMap）；
 * 紧急旗按档位透传；[overdue] 为真（在已逾期组）时标题标红。
 * [Todo.countdown] 开启时把剩余天数交给 [NoteCard.countdownDays]，卡片左区显示倒计时
 * （天数口径与截止配色同源，见 [deadlineDaysLeft]）。
 * 点卡片（非勾选框）经 [onClick] 拉起详情浮层——勾选框是独立 toggleable，不会误触；
 * 编辑态（[editing]）点按改由宿主接「切换选中」，卡片转选中形态（[selected] 画蓝框）、
 * 侧滑/完成勾选/长按全部停用。
 * 长按卡片（非编辑态）经 [onLongClick] 进入待办编辑页（可用性闸门在宿主回调内）。
 * 侧滑动作：左侧删除（error 红，呼出后再拖满色松手即删）、右侧置顶（primary 蓝；已置顶卡
 * 显示 ic_pin_off、执行取消置顶），动作卡语义见 [NoteCardSwipe]。
 */
@Composable
private fun TodoCard(
    todo: Todo,
    // 完成态（宿主解析后传入）：乐观回显覆盖仓库值，驱动 NoteCard 的勾选框与划线过渡
    completed: Boolean,
    // 标签配色表（标签名 → 颜色）：透传给 NoteCard 的 tagColors
    tagColors: Map<String, Color>,
    overdue: Boolean,
    // 编辑态：卡片转选中形态（onClick 已由宿主接「切换选中」），侧滑/勾选/长按停用
    editing: Boolean,
    // 编辑态下该卡是否在选中集（NoteCard 画 Primary 蓝框）
    selected: Boolean,
    swipeCoordinator: SwipeCoordinator,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    // 列表项级 modifier：animateItem（进出场/避让）与卡间距内距由宿主组装后传入
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    NoteCard(
        modifier = modifier,
        title = todo.name,
        time = todo.cardTimeText(),
        deadline = todo.deadlineCardText(),
        deadlineColor = todo.deadlineColor(),
        repeat = todo.repeatCardText(),
        // 倒计时：开关开着且仍有截止日期时才有天数可显（截止被清掉时仓库已把开关归一化回 false）
        countdownDays = if (todo.countdown) todo.deadlineDaysLeft() else null,
        tags = todo.tags,
        tagColors = tagColors,
        sourceLabel = todo.sourceLabel(),
        urgency = todo.urgency,
        completed = completed,
        overdue = overdue,
        onClick = onClick,
        onCheckedChange = if (editing) null else onCheckedChange,
        selected = if (editing) selected else null,
        onLongClick = if (editing) null else onLongClick,
        swipe = if (editing) {
            // 编辑态禁用侧滑：侧滑删除/置顶与批量选中是两套动作模型，编辑态只认顶栏
            null
        } else {
            NoteCardSwipe(
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
            )
        },
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
    // 列表项级 modifier：animateItem（进出场/避让）与卡间距内距由宿主组装后传入
    modifier: Modifier = Modifier,
) {
    NoteCard(
        modifier = modifier,
        title = todo.name,
        time = todo.cardTimeText(),
        deadline = todo.deadlineCardText(),
        deadlineColor = todo.deadlineColor(),
        repeat = todo.repeatCardText(),
        countdownDays = if (todo.countdown) todo.deadlineDaysLeft() else null,
        tags = todo.tags,
        tagColors = tagColors,
        sourceLabel = todo.sourceLabel(),
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
 * 已归档页的单条卡片：[Todo] → [NoteCard] 的已归档样式接线——右侧勾选框换成取消归档按钮
 * （[NoteCard.archived]，点按 [onUnarchive]），其余样式与普通卡完全一致（完成态照常划线
 * 置灰，时间/截止/标签等只读展示）。不接点击：详情/编辑浮层只认活跃待办，归档条目是
 * 冻结态。左滑呼出「删除」动作卡（满色松手即软删进最近删除——与主列表删除同一语义同一
 * 样式，归档标记保留，回收站恢复后仍回已归档页），右滑不提供置顶。
 */
@Composable
private fun ArchivedTodoCard(
    todo: Todo,
    tagColors: Map<String, Color>,
    swipeCoordinator: SwipeCoordinator,
    onDelete: () -> Unit,
    onUnarchive: () -> Unit,
    // 列表项级 modifier：animateItem（进出场/避让）与卡间距内距由宿主组装后传入
    modifier: Modifier = Modifier,
) {
    NoteCard(
        modifier = modifier,
        title = todo.name,
        time = todo.cardTimeText(),
        deadline = todo.deadlineCardText(),
        deadlineColor = todo.deadlineColor(),
        repeat = todo.repeatCardText(),
        countdownDays = if (todo.countdown) todo.deadlineDaysLeft() else null,
        tags = todo.tags,
        tagColors = tagColors,
        sourceLabel = todo.sourceLabel(),
        urgency = todo.urgency,
        completed = todo.completed,
        archived = true,
        onUnarchive = onUnarchive,
        swipe = NoteCardSwipe(
            key = todo.id,
            coordinator = swipeCoordinator,
            delete = SwipeAction(
                iconRes = R.drawable.ic_delete,
                tint = MaterialTheme.colorScheme.error,
                activeColor = MaterialTheme.colorScheme.error,
                onActiveColor = MaterialTheme.colorScheme.onError,
                contentDescription = stringResource(R.string.todo_swipe_delete),
                onAction = onDelete,
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
 * 截止时间文本：日期段 + 时刻。日期段默认走 [toCardDateLabel]（距今天 ±2 天内显相对词，
 * 更远绝对日期）；[relativeDate] = false 时直接走 [toAbsoluteDateText] 的绝对日期（详情
 * 浮层的「确定日期」口径）——日程页非今天的提醒卡用它：相对词按真实今天算，会和随查看
 * 日期推算的倒计时错位。无截止日期返回 null——这一位缺席；截止日期与时刻成对出现
 * （TodoValidator 约束）。
 */
@Composable
internal fun Todo.deadlineCardText(relativeDate: Boolean = true): String? {
    val date = deadlineDate ?: return null
    val dateText = if (relativeDate) date.toCardDateLabel() else date.toAbsoluteDateText()
    return listOfNotNull(
        dateText,
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
 * 截止时间取色（NoteCard 图标与文字同用）：距截止剩余 [daysLeft] 天——>2 天 primary；剩
 * 2 天 urgentYellow；剩 1 天 urgentOrange；当天及已过期 error。截止缺席时返回 primary
 * （调用方仅在有截止文本时消费该色）。[daysLeft] 由调用方给定：列表卡按 LocalToday 推，
 * 日程页提醒卡按查看日期推（与各自的倒计时数字同一份天数，颜色永远同步）。
 */
@Composable
internal fun Todo.deadlineColor(daysLeft: Long?): Color {
    val days = daysLeft ?: return MaterialTheme.colorScheme.primary
    return when {
        days == 0L -> MaterialTheme.colorScheme.error
        days == 1L -> MaterialTheme.classppColors.urgentOrange
        days == 2L -> MaterialTheme.classppColors.urgentYellow
        days > 2 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
}

/** 同上，天数按 LocalToday 现推（待办列表卡等「相对真实今天」的口径） */
@Composable
internal fun Todo.deadlineColor(): Color = deadlineColor(daysLeft = deadlineDaysLeft())

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
