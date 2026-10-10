package com.fangyi.classpp

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TagRepository
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.PageOverlayTransition
import com.fangyi.classpp.ui.motion.TabTransitionState
import com.fangyi.classpp.ui.motion.rememberDeviceCornerRadius
import com.fangyi.classpp.ui.navigation.AppTab
import com.fangyi.classpp.ui.navigation.BottomNavBar
import com.fangyi.classpp.ui.note.TodoScreen
import com.fangyi.classpp.ui.placeholder.AgendaScreen
import com.fangyi.classpp.ui.schedule.CardHeightPreferences
import com.fangyi.classpp.ui.schedule.ScheduleScreen
import com.fangyi.classpp.ui.settings.SettingsScreen
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.ThemeMode
import com.fangyi.classpp.ui.theme.ThemePreferences
import com.kyant.shapes.UnevenRoundedRectangle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 外观模式真源：冷启动只读这一次，早于 super.onCreate，下面的 setTheme 才能生效
        val savedThemeMode = ThemePreferences.load(this)
        // 手动浅色/深色时覆盖窗口背景主题：Compose 首帧之前窗口底色就是对的，不闪白。
        // 跟随系统不 setTheme：manifest 的 Theme.Classpp + values-night 已按系统深浅自动选中
        when (savedThemeMode) {
            ThemeMode.System -> Unit
            ThemeMode.Light -> setTheme(R.style.Theme_Classpp_Light)
            ThemeMode.Dark -> setTheme(R.style.Theme_Classpp_Dark)
        }
        // 课程卡片高度：与主题同为 SharedPreferences 真源，冷启动读一次（load 内按范围钳制）
        val savedCardHeights = CardHeightPreferences.load(this)
        super.onCreate(savedInstanceState)
        // 系统栏外观随主题：状态栏/导航栏全透明、前景图标深浅按模式切换。
        // 必须显式 light 而非 auto：auto 会把 isNavigationBarContrastEnforced 置为 true，
        // 三键导航下系统会自动垫一层灰色遮罩。Compose 首帧后由 SideEffect 随 darkTheme
        // 维护（见下），这里只兜首帧，darkTheme 初值与组合内计算同源
        val initialDark = when (savedThemeMode) {
            ThemeMode.System ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle(initialDark),
            navigationBarStyle = systemBarStyle(initialDark),
        )
        setContent {
            // 外观模式状态：prefs 是真源，旋转/系统深色切换等重建时 onCreate 重读，无需 saveable
            var themeMode by remember { mutableStateOf(savedThemeMode) }
            // 课程卡片高度状态：同 themeMode，prefs 真源、重建时重读
            var cardHeights by remember { mutableStateOf(savedCardHeights) }
            val darkTheme = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            ClassppTheme(darkTheme = darkTheme) {
                // 主题切换（含设置页即时改选）后重设系统栏外观；enableEdgeToEdge 幂等
                SideEffect {
                    this@MainActivity.enableEdgeToEdge(
                        statusBarStyle = systemBarStyle(darkTheme),
                        navigationBarStyle = systemBarStyle(darkTheme),
                    )
                }
                var selectedTab by rememberSaveable { mutableStateOf(AppTab.Timetable) }
                // 设置页开关（全屏覆盖层，经下方 PageOverlayTransition 盖在三屏与底部导航之上）
                var showSettings by rememberSaveable { mutableStateOf(false) }
                // 课表编辑态：由课表页的编辑按钮进入，编辑期间隐藏底部导航栏
                var editing by rememberSaveable { mutableStateOf(false) }
                // 课表页的全屏浮层（课程详情）在场时同样藏导航栏：导航栏在组合顺序上
                // 画在页面（连同页内浮层）之上，只能以「藏」实现浮层「盖住」它。
                // saveable 记住：旋转重建时浮层若开着，导航栏首帧就不闪现
                var navBarHiddenByOverlay by rememberSaveable { mutableStateOf(false) }
                // 新建待办浮层开关：待办页已选中时再点底部导航的「新建」胶囊置真
                var showNewTodoSheet by rememberSaveable { mutableStateOf(false) }
                val repository = rememberScheduleRepository()
                val todoRepository = rememberTodoRepository()
                // 标签宿主（新建待办浮层的标签选择卡）：连同课表仓库（课程标签由课表派生）一起传入
                val tagRepository = rememberTagRepository()

                // 回调记忆化：ScheduleScreen 的其余入参都稳定（repository 单例、editing 仅
                // 编辑翻转才变），回调若每次重组都是新实例，切 tab 改 selectedTab 就会连带
                // 课表页整树无谓重组，打乱 haze 源/效果同帧失效的节奏（快速切 tab 时顶栏
                // 毛玻璃闪帧的诱因之一）。闭包捕获 rememberSaveable 的同一 State 实例，
                // 语义与内联写法一致
                val onEditingChange = remember { { value: Boolean -> editing = value } }
                val onOverlayOverNavBarChange =
                    remember { { value: Boolean -> navBarHiddenByOverlay = value } }
                val onOpenSettings = remember { { showSettings = true } }
                val onNewTodoSheetDismiss = remember { { showNewTodoSheet = false } }
                // 页面 modifier 同理记忆化：Modifier.fillMaxSize() 每次调用都是新实例，
                // 内联写在调用点上会让三页在每次切 tab 时都白重组一遍
                val pageModifier = remember { Modifier.fillMaxSize() }

                // 惰性挂载：启动只组合默认页，其余页首次选中才进组合，挂载成本推迟到
                // 第一次访问。挂载后常驻（切走时停靠屏外、渲染层剔除，不销毁）——按 key
                // 重建课表页会首帧 headerHeight 跳闪，常驻保住状态，惰性省掉启动开销
                val mountedTabs = remember { mutableStateListOf(selectedTab) }
                // 每页一份的转场状态（首选取值即原位）：x 页宽分数（0=原位、±1=相邻屏外）、
                // 透明度、缩放，动画全走 Animatable、每帧只更新 layer 矩阵
                val pageStates = remember {
                    mutableMapOf<AppTab, TabPageState>().apply { put(selectedTab, TabPageState(0f)) }
                }
                // tab 页转场圆角：滑入途中裁前缘两角、卡片化（缩小淡出）裁四角，全部取
                // 机身 R 角（shapes 库连续曲率，与设置页覆盖层转场同款）。圆角定义在
                // layer 本地坐标，随页面缩放等比变小——满屏时正好贴合机身 R 角
                val deviceCornerRadius = rememberDeviceCornerRadius()
                val tabShapes = remember(deviceCornerRadius) { TabShapes(deviceCornerRadius) }
                // 递增 z 序：最新选中的页取最高值，永远盖住正在淡出的旧页；
                // 底部导航栏另用极大值压在所有页面之上（见下）
                var zIndexTick by remember { mutableFloatStateOf(0f) }
                val scope = rememberCoroutineScope()

                // 切 tab：旧页居中缩小淡出、新页从相对方向整屏滑入（无压暗、无圆角裁切，
                // 那是设置页覆盖层的专属效果）。方向由序号差决定；所有属性同一条 spec
                // （Motion.TabMillis + Motion.Overlay，与设置页进场同一节奏）同帧启动
                fun selectTab(tab: AppTab) {
                    if (tab == selectedTab) return
                    val from = selectedTab
                    // 新页在右则从右缘滑入，在左则从左缘滑入
                    val dir = if (tab.ordinal > from.ordinal) 1f else -1f
                    selectedTab = tab
                    if (tab !in mountedTabs) {
                        mountedTabs.add(tab)
                        // 与组合同帧生效：初始 x 即滑入起点，首帧就在屏外、不会闪现原位
                        pageStates[tab] = TabPageState(dir)
                    }
                    zIndexTick += 1f
                    pageStates.getValue(tab).zIndex = zIndexTick
                    val spec = tween<Float>(durationMillis = Motion.TabMillis, easing = Motion.Overlay)
                    val incoming = pageStates.getValue(tab)
                    scope.launch {
                        val gen = ++incoming.generation
                        if (incoming.alpha.value <= 0f) {
                            // 隐藏页停靠屏外：先把三属性摆到滑入起点（此刻不可见，无跳变），
                            // 再整屏滑入——滑入全程不透明，不叠淡入淡出。转场全程只有图层
                            // 矩阵在动，页面显示列表（含顶栏已画好的模糊）原样复用，不重画
                            incoming.x.snapTo(dir)
                            incoming.alpha.snapTo(1f)
                            incoming.scale.snapTo(1f)
                            incoming.x.animateTo(0f, spec)
                        } else {
                            // 转场中途被再次选中：从当前值就地淡回原位，不重新定位
                            launch { incoming.x.animateTo(0f, spec) }
                            launch { incoming.alpha.animateTo(1f, spec) }
                            incoming.scale.animateTo(1f, spec)
                        }
                    }
                    pageStates.getValue(from).let { outgoing ->
                        scope.launch {
                            val gen = ++outgoing.generation
                            // 旧页原位不动（x 归 0 兜住中途被切换的残位）、居中缩小淡出，
                            // 与新页滑入同 spec 同帧；Animatable 互斥保证连点时各属性
                            // 一律从当前值继续，任何连点序列都无跳变
                            launch { outgoing.x.animateTo(0f, spec) }
                            launch { outgoing.scale.animateTo(Motion.TabShrinkScale, spec) }
                            outgoing.alpha.animateTo(0f, spec)
                            if (outgoing.generation == gen) {
                                // 彻底淡出后停靠屏外：命中测试收不到、渲染线程整层剔除。
                                // 停靠期间顶栏冻结层只重放、不重录；进多任务等场景系统
                                // 会丢弃不可见窗口的 RenderNode 显示列表，故回前台时由
                                // 上方 LaunchedEffect 回位一帧重录，冻结层跨后台保持有效。
                                // generation 守卫：若淡出途中该页又被选中，本协程已随
                                // Animatable 互斥被取消，不会走到这里
                                outgoing.x.snapTo(dir * TabParkFraction)
                                outgoing.scale.snapTo(1f)
                            }
                        }
                    }
                }

                // 一次性存储降级提示：get() 返回即 bootstrap 完成，loadState 已定型
                LaunchedEffect(repository) {
                    val repo = repository ?: return@LaunchedEffect
                    val message = when (repo.loadState.value) {
                        LoadState.Ready -> null
                        LoadState.RestoredFromBackup -> R.string.load_state_restored
                        LoadState.ResetAfterCorruption -> R.string.load_state_reset
                    }
                    if (message != null) {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }

                // 停靠页顶栏冻结帧回位重录（回前台与深浅主题切换共用）：给停靠页造一帧
                // 原位绘制时机——x 归零（alpha=0 且压在当前页之下，不可见、无实际命中
                // 风险）→ 位置回调把 haze 几何刷回就位值 → 该帧绘制照常走录制分支、
                // 冻结帧重录 → 再弹回停靠位。两次 withFrameNanos：第一次恢复在下一帧的
                // 动画相位（早于其绘制），第二次恢复时中间那帧已完成绘制并重录完毕，
                // 回弹才安全。就位页（被选中）逐帧自录、淡出中页（alpha>0）由转场协程
                // 管理，均跳过
                fun reRecordParkedTabs() {
                    mountedTabs.forEach { tab ->
                        if (tab == selectedTab) return@forEach
                        val page = pageStates.getValue(tab)
                        if (page.alpha.value > 0f) return@forEach
                        scope.launch {
                            val parkX = page.x.value
                            page.x.snapTo(0f)
                            withFrameNanos { }
                            withFrameNanos { }
                            // 这一帧内该页被再次选中（转场已接管 x、alpha=1）时不再回弹；
                            // 此时冻结帧刚录好，该次转场正好直接受保护
                            if (page.alpha.value <= 0f && page.x.value == 0f) {
                                page.x.snapTo(parkX)
                            }
                        }
                    }
                }

                // 回前台重录顶栏转场冻结帧：进多任务/回桌面时窗口不可见，系统会丢弃
                // RenderNode 的显示列表（haze 为同一问题在 onStop 释放捕获层，
                // chrisbanes/haze#497），tabTransitionFreeze 录制的就位帧随之清空。
                // 可见页回前台全量重画即自愈（就位分支照常重录）；停靠页（x=±1.2、
                // alpha=0）永远处于「转场中」判定（x≠0），冻结层只重放、从不重录，
                // 空帧会一直带进下次切回课表页的滑入——顶栏整条空白露出底色
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.currentStateFlow.collect { state ->
                        if (state != Lifecycle.State.RESUMED) return@collect
                        reRecordParkedTabs()
                    }
                }

                // 深浅主题切换后重录停靠页冻结帧：冻结帧录的是录制当时主题的整条栏面
                // （兜底色 + Haze 模糊输出 + 胶囊），停靠页只重放不重录——切完主题再
                // 切回该页，滑入全程整帧重放旧主题栏面，落定那帧才换新（顶栏模糊慢半拍
                // 才变色，旧深色栏面压在浅色页上穿帮一瞬，反之亦然）。切主题即回位
                // 重录新主题帧，后续滑入重放的就是新帧
                LaunchedEffect(darkTheme) {
                    reRecordParkedTabs()
                }

                // 设置页覆盖层完整转场（ui.motion 的 PageOverlayTransition）：下层让位视差、
                // 压暗遮罩、右滑进出场与转场期左缘圆角都在其中，与个性化子页共用同一实现
                PageOverlayTransition(
                    visible = showSettings,
                    modifier = Modifier
                        .fillMaxSize()
                        // 页面世界的基础底色，随主题即时切换：窗口 windowBackground（XML
                        // 主题）只在 Activity 重建时更新，应用内切颜色模式后仍是旧色；
                        // tab 转场旧页淡出、设置页转场视差让位时都会露出页面背后的底色，
                        // 用这层 Compose background 兜住，转场底色才跟着主题走
                        .background(MaterialTheme.colorScheme.background),
                    behind = {
                        // 已挂载的页面层（见上方 mountedTabs 注释）：每页包一层 Box 承载
                        // 转场变换（tabLayer），key(tab) 保证新增页面插队时已有页面的
                        // 节点与状态原样保留。页面内容仅组合一次、之后常驻
                        mountedTabs.forEach { tab ->
                            key(tab) {
                                val state = pageStates.getValue(tab)
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        // 非选中页清空语义：屏外/淡出中的页面不该被
                                        // 无障碍、自动化视为可达内容
                                        .then(
                                            if (tab == selectedTab) Modifier
                                            else Modifier.clearAndSetSemantics { },
                                        )
                                        .tabLayer(state, tabShapes),
                                ) {
                                    when (tab) {
                                        AppTab.Agenda -> AgendaScreen(pageModifier)
                                        AppTab.Timetable -> ScheduleScreen(
                                            modifier = pageModifier,
                                            repository = repository,
                                            todoRepository = todoRepository,
                                            editing = editing,
                                            onEditingChange = onEditingChange,
                                            onOverlayOverNavBarChange = onOverlayOverNavBarChange,
                                            onOpenSettings = onOpenSettings,
                                            tabTransition = pageStates.getValue(AppTab.Timetable).transition,
                                            cardHeights = cardHeights,
                                        )
                                        AppTab.Todo -> TodoScreen(
                                            modifier = pageModifier,
                                            repository = todoRepository,
                                            tagRepository = tagRepository,
                                            scheduleRepository = repository,
                                            showNewTodoSheet = showNewTodoSheet,
                                            onNewTodoSheetDismiss = onNewTodoSheetDismiss,
                                            onOverlayOverNavBarChange = onOverlayOverNavBarChange,
                                            tabTransition = pageStates.getValue(AppTab.Todo).transition,
                                        )
                                    }
                                }
                            }
                        }
                        // 编辑态或课表页全屏浮层在场时隐藏底部导航栏（设计稿如此，也避免编辑中途被切走）：
                        // 下移出屏 / 上移入屏，与顶栏的编辑栏过渡（ScheduleScreen 内 AnimatedContent）
                        // 共用 [Motion.EditMillis] 规格，同一个 editing 翻转同帧启动，两侧严格同步
                        AnimatedVisibility(
                            visible = !editing && !navBarHiddenByOverlay,
                            enter = slideInVertically(
                                // 从自身高度下方起步：上移入
                                animationSpec = tween(Motion.EditMillis, easing = Motion.Standard),
                            ) { it } + fadeIn(tween(Motion.EditMillis)),
                            exit = slideOutVertically(
                                // 滑向自身高度下方：下移出
                                animationSpec = tween(Motion.EditMillis, easing = Motion.Standard),
                            ) { it } + fadeOut(tween(Motion.EditMillis)),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                // 导航栏永远压在所有页面之上：页面 z 序随选中递增无上界，
                                // 用极大值一劳永逸——切 tab 转场中新页从它下方滑过，它纹丝不动
                                .zIndex(Float.MAX_VALUE),
                        ) {
                            BottomNavBar(
                                selectedTab = selectedTab,
                                // 退场动画期间仍在组合中，挡掉点击：编辑中途或浮层打开时不许切 tab。
                                // 待办页已选中时胶囊即「新建」入口：再点呼起新建待办浮层
                                onTabSelected = { tab ->
                                    if (!editing && !navBarHiddenByOverlay) {
                                        if (tab == selectedTab && tab == AppTab.Todo) {
                                            showNewTodoSheet = true
                                        } else {
                                            selectTab(tab)
                                        }
                                    }
                                },
                            )
                        }
                    },
                ) {
                    SettingsScreen(
                        onClose = { showSettings = false },
                        repository = repository,
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            ThemePreferences.save(this@MainActivity, mode)
                        },
                        cardHeights = cardHeights,
                        onCardHeightsChange = { heights ->
                            cardHeights = heights
                            CardHeightPreferences.save(this@MainActivity, heights)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * 进程级课表仓库：remember 持有、LaunchedEffect 内调起 suspend [ScheduleRepository.get]。
 * 返回即磁盘加载完成 ⇒ 非 null 即数据就绪；null 仅首帧毫秒级（各屏自行显示加载态）。
 */
@Composable
private fun rememberScheduleRepository(): ScheduleRepository? {
    var repository by remember { mutableStateOf<ScheduleRepository?>(null) }
    val context = LocalContext.current
    LaunchedEffect(context) {
        repository = ScheduleRepository.get(context.applicationContext)
    }
    return repository
}

/**
 * 进程级待办仓库：remember 持有、LaunchedEffect 内调起 suspend [TodoRepository.get]。
 * 返回即磁盘加载完成 ⇒ 非 null 即数据就绪；null 仅首帧毫秒级（各屏自行显示加载态）。
 */
@Composable
private fun rememberTodoRepository(): TodoRepository? {
    var repository by remember { mutableStateOf<TodoRepository?>(null) }
    val context = LocalContext.current
    LaunchedEffect(context) {
        repository = TodoRepository.get(context.applicationContext)
    }
    return repository
}

/**
 * 进程级标签仓库：remember 持有、LaunchedEffect 内调起 suspend [TagRepository.get]。
 * 返回即磁盘加载完成 ⇒ 非 null 即数据就绪；null 仅首帧毫秒级（各屏自行显示加载态）。
 */
@Composable
private fun rememberTagRepository(): TagRepository? {
    var repository by remember { mutableStateOf<TagRepository?>(null) }
    val context = LocalContext.current
    LaunchedEffect(context) {
        repository = TagRepository.get(context.applicationContext)
    }
    return repository
}

/**
 * 系统栏样式按主题二选一：全透明底，前景图标深色（浅色主题）/浅色（深色主题）。
 * 两处调用（onCreate 兜首帧 + SideEffect 随主题维护）同源，规格不漂移。
 */
private fun systemBarStyle(dark: Boolean): SystemBarStyle =
    if (dark) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    }

/**
 * tab 页的转场状态（每页一份，见 [selectTab]）：动画全走 Animatable，在
 * [tabLayer] 的 graphicsLayer 块内读取——每帧只更新变换矩阵，子树布局、绘制指令、
 * 文字排版一概不动，与 PageOverlayTransition 同一性能形态。
 */
private class TabPageState(initialX: Float) {
    /** 页宽分数：0=原位、±1=相邻一屏外、淡出后停靠 ±[TabParkFraction]。 */
    val x = Animatable(initialX)

    val alpha = Animatable(1f)

    val scale = Animatable(1f)

    /** 绘制层级：每次选中递增（[selectTab]），最新页盖住正在淡出的旧页。 */
    var zIndex by mutableFloatStateOf(0f)

    /** 转场协程代号：淡出协程收尾（停靠屏外）前校验，被新协程取代即放弃。 */
    var generation = 0

    /**
     * 转场状态只读视图：课表页顶栏的转场冻结层据此在绘制期判定转场是否进行中
     * （转场期间重放就位帧、绕开 haze 的中间态几何重采样，见 ScheduleHeader）。
     */
    val transition = TabTransitionState(x, scale)
}

/** 旧页淡出后的屏外停靠位：多留 0.2 屏余量，任何缩放与像素圆整下都不会露边。 */
private const val TabParkFraction = 1.2f

/**
 * tab 页转场层：z 序 + 转场矩阵（平移/透明度/缩放）合并进同一个 graphicsLayer，
 * 动画每帧只更新这一个矩阵；隐藏页停靠屏外，渲染线程整层剔除。
 * translationX 取整到整像素：动画期间文字不糊，收尾正好归 0 与原位重合。
 *
 * 圆角裁剪（[TabShapes]，机身 R 角连续曲率），按优先级取用：
 * - 缩放 < 1（卡片化：淡出中、或被中途唤回长回全屏途中）→ 裁四角；
 * - 缩放 = 1 且 x ≠ 0（滑入途中，前缘露在屏内、后缘贴着屏外）→ 裁前缘两角，
 *   x > 0 从右滑入裁左缘、x < 0 从左滑入裁右缘；
 * - 就位（x = 0、缩放 = 1）→ 恢复矩形：前缘两角此时正贴机身 R 角，摘掉裁剪无跳变；
 * 停靠屏外的隐藏页也命中"前缘两角"分支，但 alpha=0 不可见，无碍。
 */
private fun Modifier.tabLayer(state: TabPageState, shapes: TabShapes): Modifier = this
    .zIndex(state.zIndex)
    .graphicsLayer {
        translationX = (state.x.value * size.width).roundToInt().toFloat()
        alpha = state.alpha.value
        val shrink = state.scale.value
        scaleX = shrink
        scaleY = shrink
        when {
            shrink < 1f -> {
                shape = shapes.card
                clip = true
            }
            state.x.value != 0f -> {
                shape = if (state.x.value > 0f) shapes.leftCorners else shapes.rightCorners
                clip = true
            }
            else -> {
                shape = RectangleShape
                clip = false
            }
        }
    }

/**
 * tab 页转场期的圆角裁剪形状：全部取机身 R 角、连续曲率（shapes 库
 * [UnevenRoundedRectangle]，与设置页覆盖层转场同款），只按裁剪位置区分。
 */
private class TabShapes(corner: Dp) {
    /** 左缘两角圆滑：页面停在右侧屏外或从右滑入时，露出的前缘是左缘。 */
    val leftCorners = UnevenRoundedRectangle(topStart = corner, bottomStart = corner)

    /** 右缘两角圆滑：从左滑入时露出的前缘是右缘。 */
    val rightCorners = UnevenRoundedRectangle(topEnd = corner, bottomEnd = corner)

    /** 四角全圆：缩小淡出的卡片，以及被中途唤回后长回全屏的途中。 */
    val card = UnevenRoundedRectangle(
        topStart = corner,
        topEnd = corner,
        bottomStart = corner,
        bottomEnd = corner,
    )
}
