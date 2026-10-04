package com.fangyi.classpp

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.RoundedCorner
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.ui.navigation.AppTab
import com.fangyi.classpp.ui.navigation.BottomNavBar
import com.fangyi.classpp.ui.placeholder.AgendaScreen
import com.fangyi.classpp.ui.placeholder.TodoScreen
import com.fangyi.classpp.ui.schedule.ScheduleScreen
import com.fangyi.classpp.ui.settings.SettingsScreen
import com.fangyi.classpp.ui.theme.ClassppTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 固定浅色系统栏外观：状态栏/导航栏全透明、深色前景。
        // 必须用 light 而非 auto：auto 会把 isNavigationBarContrastEnforced 置为 true，
        // 三键导航下系统会自动垫一层灰色遮罩
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            ClassppTheme {
                var selectedTab by rememberSaveable { mutableStateOf(AppTab.Timetable) }
                // 设置页开关（全屏覆盖层，见 Box 内组合顺序）
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
                    // 先快后慢：LinearOutSlowIn 起点即全速、此后单调减速收尾；
                    animationSpec = tween(
                        durationMillis = TabTransitionMillis,
                        easing = LinearOutSlowInEasing,
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

                Box(Modifier.fillMaxSize()) {
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
                    // 共用 [EditTransitionMillis] 规格，同一个 editing 翻转同帧启动，两侧严格同步
                    AnimatedVisibility(
                        visible = !editing && !navBarHiddenByOverlay,
                        enter = slideInVertically(
                            // 从自身高度下方起步：上移入
                            animationSpec = tween(EditTransitionMillis, easing = FastOutSlowInEasing),
                        ) { it } + fadeIn(tween(EditTransitionMillis)),
                        exit = slideOutVertically(
                            // 滑向自身高度下方：下移出
                            animationSpec = tween(EditTransitionMillis, easing = FastOutSlowInEasing),
                        ) { it } + fadeOut(tween(EditTransitionMillis)),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        BottomNavBar(
                            selectedTab = selectedTab,
                            // 退场动画期间仍在组合中，挡掉点击：编辑中途或浮层打开时不许切 tab
                            onTabSelected = { if (!editing && !navBarHiddenByOverlay) selectedTab = it },
                        )
                    }
                    // 设置页背景压暗：与设置页同节奏（进 360 / 出 250）的纯淡入淡出遮罩，
                    // 规格同浮层遮罩（32% 黑）——进场时压暗背景提供进深，退场时随滑出恢复。
                    // 只画不拦截点击：覆盖层全可见时遮罩被完全盖住，仅转场期间透出
                    AnimatedVisibility(
                        visible = showSettings,
                        enter = fadeIn(tween(SettingsEnterMillis, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(SettingsExitMillis, easing = FastOutSlowInEasing)),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                        )
                    }
                    // 最后组合 ⇒ 绘制与命中测试覆盖三屏与底部导航（课表页仅被覆盖、不重建）。
                    // 进场整页从右缘滑入（不叠淡入淡出，进深感交给背后的压暗遮罩），
                    // 呼应左上角返回箭头的子页语义；
                    // AnimatedVisibility 退场期间仍保持组合，返回键/返回按钮经 onClose 幂等关闭
                    AnimatedVisibility(
                        visible = showSettings,
                        enter = slideInHorizontally(
                            // 从自身宽度右侧起步：整页右进
                            animationSpec = tween(SettingsEnterMillis, easing = FastOutSlowInEasing),
                        ) { it },
                        exit = slideOutHorizontally(
                            // 滑向自身宽度右侧：整页右出；退场略快于进场，收场更利落
                            animationSpec = tween(SettingsExitMillis, easing = FastOutSlowInEasing),
                        ) { it },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // 左缘圆角只存在于转场期间：滑入/滑出时左上/左下裁出与机身 R 角一致的
                        // 圆角（API 31+ 读系统真实半径，低版本退化 24dp 近似），与压暗遮罩配合
                        // 让页面像一张卡片滑过背景；完全就位后恢复矩形贴边、零裁剪开销
                        val view = LocalView.current
                        val density = LocalDensity.current
                        val leftCornerPx = remember {
                            val insets = view.rootWindowInsets
                            val tl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
                            } else {
                                0
                            }
                            val bl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
                            } else {
                                0
                            }
                            maxOf(tl, bl).takeIf { it > 0 }?.toFloat()
                                ?: with(density) { 24.dp.toPx() }
                        }
                        // 圆角开关：内容首帧必然处于转场中，初始即为开（圆角恒定全开、
                        // 不随滑动插值收放）；完全就位（当前态与目标态都是 Visible）后
                        // 保持 500ms 圆角，画面彻底静止再恢复矩形贴边并摘掉裁剪层
                        val settled = transition.currentState == EnterExitState.Visible &&
                            transition.targetState == EnterExitState.Visible
                        var cornersOn by remember { mutableStateOf(true) }
                        LaunchedEffect(settled) {
                            if (settled) {
                                delay(100)
                                cornersOn = false
                            } else {
                                cornersOn = true
                            }
                        }
                        SettingsScreen(
                            onClose = { showSettings = false },
                            repository = repository,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    if (cornersOn) {
                                        shape = RoundedCornerShape(
                                            topStart = leftCornerPx,
                                            bottomStart = leftCornerPx,
                                        )
                                        clip = true
                                    } else {
                                        shape = RectangleShape
                                        clip = false
                                    }
                                },
                        )
                    }
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
 * tab 平移时长：先快后慢的减速曲线下，位移的大部分集中在开头，末尾只是缓慢收住。
 * 想让节奏更利落可下调（300ms 左右），曲线不变。
 */
private const val TabTransitionMillis = 400

/**
 * 编辑态过渡时长：顶栏 ↔ 编辑栏（ScheduleScreen 内 AnimatedContent）与底部导航栏
 * 的 AnimatedVisibility 共用同一规格（配合 FastOutSlowInEasing），同一个 editing
 * 翻转同帧启动，两侧才能严格同步；internal 供 ScheduleScreen 引用，避免数值漂移。
 * 想更快收场可下调（300ms 左右），曲线不变。
 */
internal const val EditTransitionMillis = 360

/** 设置页进场时长：整页右滑入 + 淡入，与编辑栏同节奏（FastOutSlowIn 360ms） */
private const val SettingsEnterMillis = 360

/** 设置页退场时长：同方向右滑出，比进场短一些，返回更利落 */
private const val SettingsExitMillis = 250

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
