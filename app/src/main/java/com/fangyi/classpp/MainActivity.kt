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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.PageOverlayTransition
import com.fangyi.classpp.ui.motion.rememberDeviceCornerRadius
import com.fangyi.classpp.ui.navigation.AppTab
import com.fangyi.classpp.ui.navigation.BottomNavBar
import com.fangyi.classpp.ui.note.TodoScreen
import com.fangyi.classpp.ui.placeholder.AgendaScreen
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
                val repository = rememberScheduleRepository()

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
                            // 再整屏滑入——滑入全程不透明，不叠淡入淡出
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

                // 设置页覆盖层完整转场（ui.motion 的 PageOverlayTransition）：下层让位视差、
                // 压暗遮罩、右滑进出场与转场期左缘圆角都在其中，与个性化子页共用同一实现
                PageOverlayTransition(
                    visible = showSettings,
                    modifier = Modifier.fillMaxSize(),
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
                                        AppTab.Agenda -> AgendaScreen(Modifier.fillMaxSize())
                                        AppTab.Timetable -> ScheduleScreen(
                                            modifier = Modifier.fillMaxSize(),
                                            repository = repository,
                                            editing = editing,
                                            onEditingChange = { editing = it },
                                            onOverlayOverNavBarChange = { navBarHiddenByOverlay = it },
                                            onOpenSettings = { showSettings = true },
                                        )
                                        AppTab.Todo -> TodoScreen(Modifier.fillMaxSize())
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
                                // 退场动画期间仍在组合中，挡掉点击：编辑中途或浮层打开时不许切 tab
                                onTabSelected = { if (!editing && !navBarHiddenByOverlay) selectTab(it) },
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
