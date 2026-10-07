package com.fangyi.classpp.ui.note

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.sortedFor
import com.fangyi.classpp.ui.components.NoteCard
import com.fangyi.classpp.ui.components.NoteCardSection
import com.fangyi.classpp.ui.components.PopupMenuCard
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 演示分组：数据层接入后由用户自定义分组替换 */
private val DemoGroups = listOf("作业", "日常琐事")

/**
 * 顶栏在内容坐标系里的占位高：TodoTopBar 的私有常量镜像（状态栏之上另算）——
 * 顶栏顶部留白 3dp + 胶囊行 Box 高 40dp + 上下阴影呼吸位 8dp×2。
 */
private val TopBarRowSpace = 59.dp

/** 列表首组与顶栏的间距 */
private val ListTopSpacing = 12.dp

/** 列表尾部余量：列表从浮动导航栏下滚过，末张卡片能滚离底边更远 */
private val ListBottomSlack = 96.dp

/** 两组卡片（未完成/已完成）之间的间距 */
private val SectionSpacing = 20.dp

/** 排序菜单与排序胶囊下缘的间距 */
private val SortMenuGap = 8.dp

/**
 * 待办页：页面底色铺 background（tab 转场缩小淡出时不透出其他层）。
 * 内容层为待办列表（[hazeSource] 采样源：列表滚动到顶栏之下时透出毛玻璃），
 * 顶栏浮在上层；选中态在此持有，分组胶囊过滤后续接入。
 *
 * 列表分「无日期」（未完成，本期新建的待办均无日期属性归此组）与「已完成」
 * （默认收起）两组，组内按创建时间新在前；勾选切换写回仓库，完成即移组。
 * 新建待办浮层（[NewTodoSheet]）两段式挂载：[showNewTodoSheet] 置真即挂载滑入，
 * 关闭先清 visible 播完出场，onDismissed 才卸载；浮层在场时经
 * [onOverlayOverNavBarChange] 请求藏底部导航栏（同课表页全屏浮层）。
 */
@Composable
fun TodoScreen(
    modifier: Modifier = Modifier,
    repository: TodoRepository? = null,
    showNewTodoSheet: Boolean = false,
    onNewTodoSheetDismiss: () -> Unit = {},
    onOverlayOverNavBarChange: (Boolean) -> Unit = {},
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
    // 排序菜单锚点（排序胶囊的窗口坐标；非空 = 菜单打开）
    var sortMenuAnchor by remember { mutableStateOf<Rect?>(null) }

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
            )
        }
        TodoTopBar(
            groups = DemoGroups,
            selectedGroupIndex = selectedGroupIndex,
            onGroupSelect = { selectedGroupIndex = it },
            // 文件夹操作后续接入；排序点击弹锚定菜单
            onFolderClick = {},
            onSortClick = { anchor -> sortMenuAnchor = anchor },
            modifier = Modifier.align(Alignment.TopCenter),
            hazeState = hazeState,
        )
    }

    // 排序菜单：锚定排序胶囊下方；点选项 = 应用并收起（收起由这里与动作一起处理）
    sortMenuAnchor?.let { anchor ->
        SortMenuPopup(
            anchor = anchor,
            sort = sort,
            sortDescending = sortDescending,
            onSelectSort = {
                sort = it
                sortMenuAnchor = null
            },
            onSelectDescending = {
                sortDescending = it
                sortMenuAnchor = null
            },
            onDismiss = { sortMenuAnchor = null },
        )
    }

    // 新建待办浮层：两段式挂载（同 ScheduleScreen 的浮层接线）。画在页面内容之后
    // （组合顺序即绘制顺序，浮层盖住页面），与 ScheduleScreen 的浮层位置一致
    var newTodoMounted by remember { mutableStateOf(false) }
    if (showNewTodoSheet) newTodoMounted = true
    if (newTodoMounted) {
        NewTodoSheet(
            visible = showNewTodoSheet,
            onDismissed = { newTodoMounted = false },
            onDismiss = onNewTodoSheetDismiss,
            onConfirm = { name ->
                scope.launch {
                    // 空名已在浮层内拦截，Err 仅剩落盘失败等异常，Toast 提示、表单保持原样
                    when (repository.addTodo(Todo(id = "", name = name))) {
                        is TodoReadResult.Ok -> onNewTodoSheetDismiss()
                        is TodoReadResult.Err -> AppToasts.show(
                            context,
                            context.getString(R.string.todo_error_save_failed),
                        )
                    }
                }
            },
        )
    }
    // 浮层在场（含收场动画期间）就藏底部导航栏，onDismissed 卸载后才放回（同课表页全屏浮层）
    LaunchedEffect(newTodoMounted) {
        onOverlayOverNavBarChange(newTodoMounted)
    }
}

/**
 * 待办列表：未完成归「无日期」组、已完成归「已完成」组（默认收起），空组不渲染；
 * 组内按顶栏选定的排序与方向（默认创建时间降序——创建早的在前）。列表从顶栏与浮动
 * 导航栏下滚过，上下各留出让位。
 */
@Composable
private fun TodoList(
    todos: List<Todo>,
    sort: TodoSort,
    sortDescending: Boolean,
    onToggleCompleted: (id: String, completed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = remember(todos, sort, sortDescending) {
        todos.filter { !it.completed }.sortedFor(sort, sortDescending)
    }
    val done = remember(todos, sort, sortDescending) {
        todos.filter { it.completed }.sortedFor(sort, sortDescending)
    }
    val density = LocalDensity.current
    val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = topInset + TopBarRowSpace + ListTopSpacing,
            bottom = bottomInset + ListBottomSlack,
            start = PageHorizontalSpacing,
            end = PageHorizontalSpacing,
        ),
    ) {
        if (active.isNotEmpty()) {
            item(key = "active") {
                NoteCardSection(title = stringResource(R.string.todo_section_undated)) {
                    active.forEach { todo ->
                        NoteCard(
                            title = todo.name,
                            completed = todo.completed,
                            onCheckedChange = { onToggleCompleted(todo.id, it) },
                        )
                    }
                }
            }
        }
        if (done.isNotEmpty()) {
            item(key = "done") {
                NoteCardSection(
                    title = stringResource(R.string.todo_section_completed),
                    initiallyExpanded = false,
                    modifier = Modifier.padding(top = SectionSpacing),
                ) {
                    done.forEach { todo ->
                        NoteCard(
                            title = todo.name,
                            completed = todo.completed,
                            onCheckedChange = { onToggleCompleted(todo.id, it) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 排序弹出菜单：锚定 [anchor]（排序胶囊窗口坐标）下方弹出——CourseContextMenu 同款
 * 自定义 [PopupPositionProvider]（贴锚点左下、越界钳进窗口）+ focusable 弹窗（返回键/
 * 点外部收起），进场同规格（Motion.PopupScale/PopupFadeMillis）：scale 0.8→1 + alpha 0→1。
 *
 * 两分组（组间分割线由 PopupMenuCard 的 showDivider 控制）：排序字段单选 + 升/降序单选。
 * 点选项 = 应用并收起（收起由 [onSelectSort]/[onSelectAscending] 与动作一起处理）。
 */
@Composable
private fun SortMenuPopup(
    anchor: Rect,
    sort: TodoSort,
    sortDescending: Boolean,
    onSelectSort: (TodoSort) -> Unit,
    onSelectDescending: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    // 首帧后置真：从 0.8/0 长到 1/1
    LaunchedEffect(Unit) { visibleState.targetState = true }

    val density = LocalDensity.current
    val positionProvider = remember(anchor, density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val gap = with(density) { SortMenuGap.roundToPx() }
                return IntOffset(
                    x = anchor.left.roundToInt()
                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y = (anchor.bottom.roundToInt() + gap)
                        .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                )
            }
        }
    }

    val transition = rememberTransition(visibleState, label = "SortMenu")
    val scale by transition.animateFloat(
        transitionSpec = { tween(Motion.PopupScaleMillis, easing = Motion.Standard) },
        label = "scale",
    ) { visible -> if (visible) 1f else 0.8f }
    val alpha by transition.animateFloat(
        transitionSpec = { tween(Motion.PopupFadeMillis, easing = Motion.Standard) },
        label = "alpha",
    ) { visible -> if (visible) 1f else 0f }

    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        // focusable：返回键收起；dismissOnClickOutside 默认开启
        properties = PopupProperties(focusable = true),
    ) {
        PopupMenuCard(
            sections = listOf(
                PopupMenuSection(
                    items = listOf(
                        PopupMenuItem(
                            label = stringResource(R.string.sort_created),
                            checked = sort == TodoSort.Created,
                            onClick = { onSelectSort(TodoSort.Created) },
                        ),
                        PopupMenuItem(
                            label = stringResource(R.string.sort_deadline),
                            checked = sort == TodoSort.Deadline,
                            onClick = { onSelectSort(TodoSort.Deadline) },
                        ),
                        PopupMenuItem(
                            label = stringResource(R.string.sort_urgency),
                            checked = sort == TodoSort.Urgency,
                            onClick = { onSelectSort(TodoSort.Urgency) },
                        ),
                        PopupMenuItem(
                            label = stringResource(R.string.sort_name),
                            checked = sort == TodoSort.Name,
                            onClick = { onSelectSort(TodoSort.Name) },
                        ),
                    ),
                ),
                PopupMenuSection(
                    showDivider = true,
                    items = listOf(
                        PopupMenuItem(
                            label = stringResource(R.string.sort_ascending),
                            checked = !sortDescending,
                            onClick = { onSelectDescending(false) },
                        ),
                        PopupMenuItem(
                            label = stringResource(R.string.sort_descending),
                            checked = sortDescending,
                            onClick = { onSelectDescending(true) },
                        ),
                    ),
                ),
            ),
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                // 从锚点（排序胶囊）那一侧长出来
                transformOrigin = TransformOrigin(0f, 0f)
            },
        )
    }
}
