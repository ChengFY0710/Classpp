package com.fangyi.classpp

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.ui.navigation.AppTab
import com.fangyi.classpp.ui.navigation.BottomNavBar
import com.fangyi.classpp.ui.placeholder.AgendaScreen
import com.fangyi.classpp.ui.placeholder.TodoScreen
import com.fangyi.classpp.ui.schedule.ScheduleScreen
import com.fangyi.classpp.ui.settings.SettingsScreen
import com.fangyi.classpp.ui.theme.ClassppTheme
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
                                progress = tabProgress,
                                selected = selectedTab == AppTab.Agenda,
                            ),
                    )
                    ScheduleScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .tabPage(
                                tab = AppTab.Timetable,
                                progress = tabProgress,
                                selected = selectedTab == AppTab.Timetable,
                            ),
                        repository = repository,
                        onOpenSettings = { showSettings = true },
                    )
                    TodoScreen(
                        Modifier
                            .fillMaxSize()
                            .tabPage(
                                tab = AppTab.Todo,
                                progress = tabProgress,
                                selected = selectedTab == AppTab.Todo,
                            ),
                    )
                    BottomNavBar(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    // 最后组合 ⇒ 绘制与命中测试覆盖三屏与底部导航（课表页仅被覆盖、不重建）
                    if (showSettings) {
                        SettingsScreen(
                            onClose = { showSettings = false },
                            repository = repository,
                            modifier = Modifier.fillMaxSize(),
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
 * tab 平移时长：先快后慢的减速曲线下，位移的大部分集中在开头，
 */
private const val TabTransitionMillis = 400

/**
 * tab 页横向平移：按「自身序号 − 动画进度」× 页宽 定位，选中页恒在 0，
 * 三页像一条连续带子一起平移——旧页滑出、新页滑入；跨两个 tab 时中间页会扫过。
 *
 * 三页常驻组合、只挪位置、不重建（见调用处注释：重建会让课表页首帧跳闪）。
 * 非选中页落在屏幕外：被窗口裁剪故不参与绘制，位置不在命中范围内故收不到点击/滚动，
 * 且对 TalkBack 隐藏。
 *
 * [progress] 以 [State] 传入并在 measure 阶段读取：动画期间只重排，不重组。
 */
private fun Modifier.tabPage(
    tab: AppTab,
    progress: State<Float>,
    selected: Boolean,
): Modifier {
    val placed = this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val x = ((tab.ordinal - progress.value) * placeable.width).roundToInt()
        layout(placeable.width, placeable.height) { placeable.place(x, 0) }
    }
    return if (selected) placed else placed.clearAndSetSemantics {}
}
