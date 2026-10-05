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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.PageOverlayTransition
import com.fangyi.classpp.ui.navigation.AppTab
import com.fangyi.classpp.ui.navigation.BottomNavBar
import com.fangyi.classpp.ui.placeholder.AgendaScreen
import com.fangyi.classpp.ui.placeholder.TodoScreen
import com.fangyi.classpp.ui.schedule.ScheduleScreen
import com.fangyi.classpp.ui.settings.SettingsScreen
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.ThemeMode
import com.fangyi.classpp.ui.theme.ThemePreferences
import kotlin.math.roundToInt

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

                // tab 平移进度（浮点序号）：只在各页 measure 阶段被读取，
                // 整段动画每帧只重排、不重组
                val tabProgress = animateFloatAsState(
                    targetValue = selectedTab.ordinal.toFloat(),
                    // 先快后慢：Motion.Decelerate 起点即全速、此后单调减速收尾；
                    animationSpec = tween(
                        durationMillis = Motion.TabMillis,
                        easing = Motion.Decelerate,
                    ),
                    label = "tabProgress",
                )

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
                        // 三个页面常驻组合，切 tab 只横向平移（见 tabPage）：
                        // 若用 SaveableStateProvider 按 key 重建课表页，首帧 headerHeight
                        // 回落到估算值再被校正，紧贴头部的日期带会明显跳闪一次。
                        AgendaScreen(
                            Modifier
                                .fillMaxSize()
                                .tabPage(
                                    tab = AppTab.Agenda,
                                    selectedTab = selectedTab,
                                    progress = tabProgress,
                                ),
                        )
                        ScheduleScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .tabPage(
                                    tab = AppTab.Timetable,
                                    selectedTab = selectedTab,
                                    progress = tabProgress,
                                ),
                            repository = repository,
                            editing = editing,
                            onEditingChange = { editing = it },
                            onOverlayOverNavBarChange = { navBarHiddenByOverlay = it },
                            onOpenSettings = { showSettings = true },
                        )
                        TodoScreen(
                            Modifier
                                .fillMaxSize()
                                .tabPage(
                                    tab = AppTab.Todo,
                                    selectedTab = selectedTab,
                                    progress = tabProgress,
                                ),
                        )
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
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            BottomNavBar(
                                selectedTab = selectedTab,
                                // 退场动画期间仍在组合中，挡掉点击：编辑中途或浮层打开时不许切 tab
                                onTabSelected = { if (!editing && !navBarHiddenByOverlay) selectedTab = it },
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
 * tab 页横向平移：视觉位置 = (自身序号 − 动画进度) × 页宽，三页像一条连续带子——
 * 旧页滑出、新页滑入；跨两个 tab 时中间页会扫过。它拆成两半、各司其职：
 *
 * - **布局槽位**取"最终"位置（选中页 0，其余按序号差 ±N 屏）：命中测试、滚动、语义都由
 *   布局决定，离屏页自然收不到点击，与切 tab 前后一致；
 * - **layer 平移**只承担动画中的追赶位移（起始 ±N 屏 → 收尾 0）。
 *
 * 拆开是为了帧率：布局位移会改变节点在父层绘制指令里的位置，父层必须把三页的绘制指令
 * 整段重录（课表页含日期带 + 三个周页 × 每行五格卡片，一帧重录一次就掉帧）；
 * layer 平移只更新变换矩阵，子树布局、绘制指令、文字排版一概不动，离屏层还能被整层剔除。
 * 两半相加仍是连续带子：槽位 (序号 − 目标) × 页宽 + layer (目标 − 进度) × 页宽
 * = (序号 − 进度) × 页宽。
 *
 * 三页常驻组合、只挪位置、不重建（见调用处注释：重建会让课表页首帧跳闪）。
 * [progress] 以 [State] 传入、在布局与 layer 阶段读取：动画期间不重组、不重排。
 */
private fun Modifier.tabPage(
    tab: AppTab,
    selectedTab: AppTab,
    progress: State<Float>,
): Modifier {
    val target = selectedTab.ordinal
    val placed = this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val slotX = (tab.ordinal - target) * placeable.width
        layout(placeable.width, placeable.height) { placeable.place(slotX, 0) }
    }
    val animated = placed.graphicsLayer {
        // 取整到整像素：动画期间文字不糊，收尾正好归 0 与原位重合
        translationX = ((target - progress.value) * size.width).roundToInt().toFloat()
    }
    return if (tab == selectedTab) animated else animated.clearAndSetSemantics {}
}
