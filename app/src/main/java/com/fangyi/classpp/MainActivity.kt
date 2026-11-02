package com.fangyi.classpp

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fangyi.classpp.data.AppContainer
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.notification.NotificationAlarmReceiver
import com.fangyi.classpp.data.notification.NotificationScheduler
import com.fangyi.classpp.data.xmu.XmuDailySyncRunner
import com.fangyi.classpp.data.xmu.XmuDailySyncScheduler
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.day.DayInitialData
import com.fangyi.classpp.ui.day.DayScreen
import com.fangyi.classpp.ui.motion.LocalPageOverlayTransform
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.PageOverlayTransition
import com.fangyi.classpp.ui.motion.rememberPageOverlayTransitionState
import com.fangyi.classpp.ui.navigation.AppTab
import com.fangyi.classpp.ui.navigation.BottomNavBar
import com.fangyi.classpp.ui.navigation.NavBarTransitionHeadroom
import com.fangyi.classpp.ui.navigation.TabPager
import com.fangyi.classpp.ui.navigation.rememberTabPagerState
import com.fangyi.classpp.ui.note.TodoInboxPreferences
import com.fangyi.classpp.ui.note.TodoScreen
import com.fangyi.classpp.ui.schedule.CardHeightPreferences
import com.fangyi.classpp.ui.schedule.CardHeightScales
import com.fangyi.classpp.ui.schedule.ScheduleScreen
import com.fangyi.classpp.ui.schedule.TeacherDisplayPreferences
import com.fangyi.classpp.ui.settings.GlassPreferences
import com.fangyi.classpp.ui.settings.LocalSettingsFreezeEpoch
import com.fangyi.classpp.ui.settings.SettingsScreen
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.LocalGlassStyle
import com.fangyi.classpp.ui.theme.ThemeMode
import com.fangyi.classpp.ui.theme.ThemePreferences
import com.fangyi.classpp.ui.theme.rememberGlassBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 课程卡片高度的「滑动结束」判定：值停顿此时长即视为结束——落盘与课表网格跟进共用
 * 同一拍（拖动中每次变化都重置计时、全程不触发，松手后一次性补最终值）。
 */
private const val CardHeightSettleMillis = 300L

/**
 * 设置页覆盖层预热帧的启动延迟：等 tab 预热（三页各两帧 + 两轮停靠重录 + 500ms）
 * 全部落定后再做，避免两批首挂载/Haze 初始化抢同一帧；仍留出充足时间，用户首次
 * 打开设置通常远晚于此。
 */
private const val SettingsPrewarmDelayMillis = 1000L

class MainActivity : ComponentActivity() {

    /**
     * 通知点击的落页 tab：冷启动读 intent extras、应用在前台被复用时 onNewIntent 写入；
     * 组合内的 LaunchedEffect 消费（切 tab）后清空。null = 无待落页。
     */
    private val pendingNotificationTab = mutableStateOf<AppTab?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingNotificationTab.value = notificationTabOf(intent)
    }

    /** 通知 extras → 落页 tab：课程提醒进课表、待办提醒进待办；无标记返回 null */
    private fun notificationTabOf(intent: Intent?): AppTab? =
        when (intent?.getStringExtra(NotificationAlarmReceiver.EXTRA_NOTIFICATION_TAB)) {
            NotificationAlarmReceiver.TAB_COURSE -> AppTab.Timetable
            NotificationAlarmReceiver.TAB_TODO -> AppTab.Todo
            else -> null
        }

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
        // 课程卡片高度倍率：与主题同为 SharedPreferences 真源，冷启动读一次
        // （load 内按范围钳制；旧版本存的绝对 dp 行高在 load 内自动换算迁移）
        val savedCardScales = CardHeightPreferences.load(this)
        // 课程卡片「显示教师」开关：同为 SharedPreferences 真源，冷启动读一次
        val savedShowTeacher = TeacherDisplayPreferences.load(this)
        // 玻璃材质开关（更高级的玻璃材质）：同为 SharedPreferences 真源，冷启动读一次
        val savedGlassStyle = GlassPreferences.load(this)
        // 待办收集箱「瀑布流视图」开关：同为 SharedPreferences 真源，冷启动读一次
        val savedTodoMasonry = TodoInboxPreferences.loadMasonryView(this)
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
        // 通知点击冷启动：extras 指定落页 tab（旋转等重建不复读，避免把用户已切走的 tab 拉回）
        if (savedInstanceState == null) {
            pendingNotificationTab.value = notificationTabOf(intent)
        }
        setContent {
            // 外观模式状态：prefs 是真源，旋转/系统深色切换等重建时 onCreate 重读，无需 saveable
            var themeMode by remember { mutableStateOf(savedThemeMode) }
            // 课程卡片高度倍率状态：同 themeMode，prefs 真源、重建时重读。
            // 以 State 实例向下传递（实参不读 .value）：拖动滑条只失效真正消费值的
            // 叶子（个性化页滑条卡片、CourseGrid），根 lambda 与两块屏幕全部 skip
            val cardHeightsState = remember { mutableStateOf(savedCardScales) }
            // 落盘去抖：拖动中只改内存，snapshotFlow 在协程内读值不参与组合，
            // 停顿 300ms 才写 SharedPreferences（避免每帧 apply）
            LaunchedEffect(cardHeightsState) {
                snapshotFlow { cardHeightsState.value }
                    .debounce(CardHeightSettleMillis)
                    .collect { scales -> CardHeightPreferences.save(this@MainActivity, scales) }
            }
            // 课表网格跟随态：滑条与值文字读上方即时态（全程跟手），CourseGrid 只读这份——
            // debounce 以「变化停顿」为准：拖动中每次变化都重置计时、全程不触发，
            // 松手（含吸附归位动画收尾）停顿后一次性补最终值，课表滑动期间零重排
            val gridCardHeightsState = remember { mutableStateOf(savedCardScales) }
            LaunchedEffect(cardHeightsState, gridCardHeightsState) {
                snapshotFlow { cardHeightsState.value }
                    .debounce(CardHeightSettleMillis)
                    .collect { gridCardHeightsState.value = it }
            }
            // 「显示教师」开关状态：prefs 真源、重建时重读；布尔翻转无连续变化，
            // 落盘在回调里即时完成（同 themeMode），无需去抖
            val showTeacherState = remember { mutableStateOf(savedShowTeacher) }
            // 玻璃材质开关状态：同 showTeacher（prefs 真源、回调即时落盘）
            val glassStyleState = remember { mutableStateOf(savedGlassStyle) }
            // 待办收集箱「瀑布流视图」开关状态：同 showTeacher（prefs 真源、回调即时落盘）；
            // State 实例透传给待办页，收集箱布局在叶子内读值
            val todoMasonryState = remember { mutableStateOf(savedTodoMasonry) }
            val darkTheme = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // 玻璃材质开关（更高级的玻璃材质）：组合根部下发，浮层顶栏按钮等共享组件
            // 经 LocalGlassStyle 读取；读 .value，翻转自根部重组（与 themeMode 同拍）
            CompositionLocalProvider(LocalGlassStyle provides glassStyleState.value) {
                ClassppTheme(darkTheme = darkTheme) {
                    // 主题切换（含设置页即时改选）后重设系统栏外观；enableEdgeToEdge 幂等
                    SideEffect {
                        this@MainActivity.enableEdgeToEdge(
                            statusBarStyle = systemBarStyle(darkTheme),
                            navigationBarStyle = systemBarStyle(darkTheme),
                        )
                    }
                    // 设置页开关（全屏覆盖层，经下方 PageOverlayTransition 盖在三屏与底部导航之上）
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    // 设置页覆盖层转场状态：进度提升到组合外，供 behind 里的 tab 页面
                    // （课表/待办顶栏、日程页 Haze）在视差期间读取并冻结模糊输出
                    val settingsOverlayState = rememberPageOverlayTransitionState()
                    // 设置页覆盖层空闲预热帧：首帧后 idle 把整页在 x=0 近透明画两帧，
                    // 让四屏顶栏的 Haze 初始化并录下带模糊的就位帧（见下方 effect）
                    var settingsPrewarm by remember { mutableStateOf(false) }
                    // 设置页冻结帧代际：离开 RESUMED 递增（窗口不可见后系统可能清空
                    // 冻结层显示列表；设置页隐藏期不录制，旧帧会变空帧）
                    var settingsFreezeEpoch by remember { mutableStateOf(0) }
                    // 设置页直达子页标志：课表页教务导入浮层的「账号与同步」跳转先置真再开
                    // 设置页，账号与同步子页随之展开（SettingsScreen 消费后回调复位）
                    var settingsOpenAccountSync by rememberSaveable { mutableStateOf(false) }
                    // 课表编辑态：由课表页的编辑按钮进入，编辑期间隐藏底部导航栏
                    var editing by rememberSaveable { mutableStateOf(false) }
                    // 课表页的全屏浮层（课程详情）在场时同样藏导航栏：导航栏在组合顺序上
                    // 画在页面（连同页内浮层）之上，只能以「藏」实现浮层「盖住」它。
                    // saveable 记住：旋转重建时浮层若开着，导航栏首帧就不闪现
                    var navBarHiddenByOverlay by rememberSaveable { mutableStateOf(false) }
                    // 新建待办浮层开关：待办页已选中时再点底部导航的「新建」胶囊置真
                    var showNewTodoSheet by rememberSaveable { mutableStateOf(false) }
                    val container = rememberAppContainer()
                    val repository = container?.scheduleRepository
                    val todoRepository = container?.todoRepository
                    val tagRepository = container?.tagRepository
                    val xmuSyncRepository = container?.xmuSyncRepository
                    val xmuAutoLoginManager = container?.xmuAutoLoginManager
                    val xmuScheduleImporter = container?.xmuScheduleImporter
                    // AI 对话（日程页顶栏右侧按钮唤起对话浮层，M1 唯一 AI 入口）
                    val aiChatRepository = container?.aiChatRepository
                    // tab 页转场状态机：惰性挂载/常驻/切页动画/停靠页冻结帧回位的全部机制
                    // 见 ui.navigation 的 TabPagerState（原内联于此，纯搬移）
                    val pagerState = rememberTabPagerState()

                    // 日程页首挂载所需的持久化快照：启动协程在 IO 线程预读，首次切到
                    // 日程页时不再在主线程组合期读 5 项 prefs + 解析图片列表 JSON
                    var dayInitial by remember { mutableStateOf<DayInitialData?>(null) }
                    LaunchedEffect(Unit) {
                        dayInitial = withContext(Dispatchers.IO) {
                            runCatching { DayInitialData.load(this@MainActivity.applicationContext) }
                                .getOrDefault(DayInitialData())
                        }
                    }

                    // 回调记忆化：ScheduleScreen 的其余入参都稳定（repository 单例、editing 仅
                    // 编辑翻转才变），回调若每次重组都是新实例，切 tab 改 selectedTab 就会连带
                    // 课表页整树无谓重组，打乱 haze 源/效果同帧失效的节奏（快速切 tab 时顶栏
                    // 毛玻璃闪帧的诱因之一）。闭包捕获 rememberSaveable 的同一 State 实例，
                    // 语义与内联写法一致
                    val onEditingChange = remember { { value: Boolean -> editing = value } }
                    val onOverlayOverNavBarChange =
                        remember { { value: Boolean -> navBarHiddenByOverlay = value } }
                    val onOpenSettings = remember { { showSettings = true } }
                    // 教务导入浮层「账号与同步」：先置直达子页标志再开设置页（同一帧生效）
                    val onOpenAccountSync = remember {
                        {
                            settingsOpenAccountSync = true
                            showSettings = true
                        }
                    }
                    val onAccountSyncDirectlyOpened =
                        remember { { settingsOpenAccountSync = false } }
                    val onNewTodoSheetDismiss = remember { { showNewTodoSheet = false } }
                    // 页面 modifier 同理记忆化：Modifier.fillMaxSize() 每次调用都是新实例，
                    // 内联写在调用点上会让三页在每次切 tab 时都白重组一遍
                    val pageModifier = remember { Modifier.fillMaxSize() }
                    // 设置页入参记忆化（同款理由）：设置页四屏常驻组合，MainActivity 每次
                    // 重组若传出新的 lambda/Modifier，整棵设置树会白重组一遍。闭包捕获的
                    // 是同一批 State 实例，语义与内联写法一致
                    val onCloseSettings = remember { { showSettings = false } }
                    val onSettingsThemeModeChange = remember {
                        { mode: ThemeMode ->
                            themeMode = mode
                            ThemePreferences.save(this@MainActivity, mode)
                        }
                    }
                    val onSettingsCardHeightsChange = remember {
                        { scales: CardHeightScales -> cardHeightsState.value = scales }
                    }
                    val onSettingsShowTeacherChange = remember {
                        { show: Boolean ->
                            showTeacherState.value = show
                            TeacherDisplayPreferences.save(this@MainActivity, show)
                        }
                    }
                    val onSettingsGlassStyleChange = remember {
                        { enabled: Boolean ->
                            glassStyleState.value = enabled
                            GlassPreferences.save(this@MainActivity, enabled)
                        }
                    }
                    val onSettingsTodoMasonryChange = remember {
                        { enabled: Boolean ->
                            todoMasonryState.value = enabled
                            TodoInboxPreferences.saveMasonryView(this@MainActivity, enabled)
                        }
                    }
                    val settingsModifier = remember { Modifier.fillMaxSize() }

                    // 通知点击落页：消费 pendingNotificationTab（冷启动 extras / onNewIntent 写入）
                    // 切到对应 tab；target 与当前一致时 selectTab 幂等直接返回
                    LaunchedEffect(pendingNotificationTab.value) {
                        pendingNotificationTab.value?.let { target ->
                            pagerState.selectTab(target)
                            pendingNotificationTab.value = null
                        }
                    }

                    // 首帧之后空闲预热：提前组合其余页面并录好停靠冻结帧，首次切页只剩
                    // 矩阵动画 + 整帧重放。日程页依赖预读快照，等它就绪再预热（此时页面
                    // 才能完整组合，冻结帧才录得到真实画面）。已挂载/选中的页自动跳过
                    LaunchedEffect(pagerState, dayInitial) {
                        if (dayInitial == null) return@LaunchedEffect
                        withFrameNanos { }
                        pagerState.prewarm(listOf(AppTab.Agenda, AppTab.Todo, AppTab.Timetable))
                    }

                    // 设置页覆盖层预热帧：等 tab 预热与首批重录落定后再做，避免与切页/
                    // 首挂载抢同一帧。预热期整页停在 x=0、alpha≈0.01（见 PageOverlayTransition），
                    // 两帧就够 Haze 初始化 + 冻结层录制；用户若在此期间打开设置，visible
                    // 翻转会让位给正常转场，finally 保证近透明覆盖层不会残留
                    LaunchedEffect(pagerState, dayInitial, showSettings) {
                        if (dayInitial == null || showSettings) return@LaunchedEffect
                        withFrameNanos { }
                        delay(SettingsPrewarmDelayMillis)
                        if (showSettings) return@LaunchedEffect
                        // 与切页动画互斥：预热会抢同一帧，先等选中页落定再画
                        snapshotFlow {
                            pagerState.transitionFor(pagerState.selectedTab)?.isTransitioning == true
                        }.first { !it }
                        if (showSettings) return@LaunchedEffect
                        settingsPrewarm = true
                        try {
                            withFrameNanos { }
                            // 两帧预热态绘制：首帧完成组合/Haze 初始化/冻结层录制，
                            // 第二帧留出效果出图与录制的余量
                            withFrameNanos { }
                            withFrameNanos { }
                        } finally {
                            settingsPrewarm = false
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

                    // 通知续排：课表/待办数据首次加载完成与每次变化（编辑课程、增删改待办等）
                    // 都整体重排提醒闹钟（首条 emit 即冷启动排闹）；通知设置的变化由通知页自己
                    // 触发重排，不经过这里。debounce 合并连发的数据变更，只重排一次
                    LaunchedEffect(repository, todoRepository) {
                        val scheduleRepository = repository ?: return@LaunchedEffect
                        val todoRepository = todoRepository ?: return@LaunchedEffect
                        combine(scheduleRepository.activeSchedule, todoRepository.todos) { _, _ -> }
                            .debounce(500)
                            .collect {
                                NotificationScheduler.reschedule(this@MainActivity.applicationContext)
                            }
                    }

                    // 每日自动同步：进 App 幂等续排闹钟（自愈一切漏排）+ 补漏判定——
                    // 当天 6:00 已过且还没同步过就当场同步一次，与后台闹钟同一条静默路径
                    LaunchedEffect(repository) {
                        if (repository == null) return@LaunchedEffect
                        val appContext = applicationContext
                        XmuDailySyncScheduler.scheduleNext(appContext)
                        XmuDailySyncRunner.runIfDue(appContext)
                    }

                    // 回前台重录顶栏转场冻结帧：进多任务/回桌面时窗口不可见，系统会丢弃
                    // RenderNode 的显示列表（haze 为同一问题在 onStop 释放捕获层，
                    // chrisbanes/haze#497），transitionFreeze 录制的就位帧随之清空。
                    // 可见页回前台全量重画即自愈（就位分支照常重录）；停靠页（x=±1.2、
                    // alpha=0）永远处于「转场中」判定（x≠0），冻结层只重放、从不重录，
                    // 空帧会一直带进下次切回课表页的滑入——顶栏整条空白露出底色
                    val lifecycleOwner = LocalLifecycleOwner.current
                    LaunchedEffect(lifecycleOwner) {
                        lifecycleOwner.lifecycle.currentStateFlow.collect { state ->
                            if (state != Lifecycle.State.RESUMED) {
                                // 设置页四屏隐藏时不录制（Haze 已卸载），窗口不可见后
                                // 旧帧可能被系统清空：递增代际让冻结键失配，下次打开
                                // 先实时绘制、落定后再录新帧，绝不重放空帧/过期帧
                                settingsFreezeEpoch++
                                return@collect
                            }
                            pagerState.reRecordParkedTabs()
                        }
                    }

                    // 深浅主题切换后重录停靠页冻结帧：冻结帧录的是录制当时主题的整条栏面
                    // （兜底色 + Haze 模糊输出 + 胶囊），停靠页只重放不重录——切完主题再
                    // 切回该页，滑入全程整帧重放旧主题栏面，落定那帧才换新（顶栏模糊慢半拍
                    // 才变色，旧深色栏面压在浅色页上穿帮一瞬，反之亦然）。切主题即回位
                    // 重录新主题帧，后续滑入重放的就是新帧
                    LaunchedEffect(darkTheme) {
                        pagerState.reRecordParkedTabs()
                    }

                    // 跨天后重录停靠页冻结帧：日程页背景图/日期随 LocalToday 变化，旧帧
                    // 的内容键已失效；停靠页只重放不重录，主动补一帧新画面，首次切回仍能
                    // 全程冻结。重录若撞上背景图 cross-dissolve，冻结键里的 settled 状态
                    // 会让该帧在淡入完成后自动失效（回退实时绘制），不会重放半程淡入
                    LaunchedEffect(LocalToday) {
                        pagerState.reRecordParkedTabs()
                    }

                    // 玻璃材质（GlassPreferences）：开启时把页面世界标记进
                    // layerBackdrop 采样层，三胶囊经 drawBackdrop 取样做液态玻璃（模糊+折射+
                    // 染色）。采样内容 = 页面底色（与下方 Box 的 background 同源）+ 各 tab 页，
                    // 官方教程明确底色必须画进采样层，否则玻璃后漏透明像素；导航栏在采样
                    // 层的 Box 之外，玻璃不会照见自己。lens 折射需 API 33+、blur 需 API 31+
                    // （RenderEffect），API 31 以下开关保留但维持原白底渲染。关闭开关时
                    // layerBackdrop 不挂载，采样层零开销
                    val useGlass = glassStyleState.value && Build.VERSION.SDK_INT >= 31
                    val navBackdrop = rememberGlassBackdrop(MaterialTheme.colorScheme.background)
                    // 导航栏显隐：编辑态 / 页内全屏浮层在场时隐藏（退场动画仍占组合）。
                    // 采样层只在导航栏真正留在组合里时挂载——退场动画中胶囊仍在屏上取样，
                    // 提前摘层会采到过期底图；完全离场后再摘，长时间编辑/浮层期间采样零开销
                    val navBarVisible = !editing && !navBarHiddenByOverlay
                    val navBarVisibility = remember { MutableTransitionState(navBarVisible) }
                    navBarVisibility.targetState = navBarVisible
                    val navBarMounted =
                        navBarVisibility.currentState || navBarVisibility.targetState

                    // 设置页覆盖层完整转场（ui.motion 的 PageOverlayTransition）：下层让位视差、
                    // 压暗遮罩、右滑进出场与转场期左缘圆角都在其中，与个性化子页共用同一实现
                    PageOverlayTransition(
                        visible = showSettings,
                        // 进度状态外部持有：供 behind 里的 tab 页面在视差期间冻结 Haze
                        state = settingsOverlayState,
                        // 空闲预热帧：仅在不打开时生效，打开即让位给正常转场
                        prewarm = settingsPrewarm,
                        modifier = Modifier
                            .fillMaxSize()
                            // 页面世界的基础底色，随主题即时切换：窗口 windowBackground（XML
                            // 主题）只在 Activity 重建时更新，应用内切颜色模式后仍是旧色；
                            // tab 转场旧页淡出、设置页转场视差让位时都会露出页面背后的底色，
                            // 用这层 Compose background 兜住，转场底色才跟着主题走
                            .background(MaterialTheme.colorScheme.background),
                        behind = {
                            // 已挂载的页面层（惰性挂载与常驻机制见 TabPagerState/TabPager）：
                            // 每页由 TabPager 包一层 Box 承载转场变换，页面内容仅组合一次、
                            // 之后常驻；tab 分派与各屏参数在本 lambda 内装配。
                            // 玻璃开启时外面包一层采样标记 Box（TabPager 无 modifier 参数）；
                            // 关闭时不挂 layerBackdrop，Box 纯透传不影响原渲染路径
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (useGlass && navBarMounted) {
                                            Modifier.layerBackdrop(navBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                // 设置页视差状态只注入 tab 页面世界：页内顶栏/日程页
                                // 据此外在视差期间冻结 Haze 模糊输出。设置页自身内容
                                // 不消费该 local（嵌套覆盖层移动的是设置页内容）
                                CompositionLocalProvider(
                                    LocalPageOverlayTransform provides settingsOverlayState,
                                ) {
                                    TabPager(state = pagerState) { tab, transition ->
                                        when (tab) {
                                            AppTab.Agenda -> {
                                                // 快照就绪前不组合：保证预热的这一帧就是完整
                                                // 画面（冻结帧才录得到真实内容），寻常启动时
                                                // 加载只需几毫秒，用户无感
                                                val initial = dayInitial
                                                if (initial != null) {
                                                    DayScreen(
                                                        initialData = initial,
                                                        modifier = pageModifier,
                                                        // 仅用于状态栏图标在非 Agenda tab 恢复主题默认；
                                                        // 不参与转场，也不改导航栏布局
                                                        active = tab == pagerState.selectedTab,
                                                        // 日历卡待办小点的数据源（网格窗口内取覆盖日期）
                                                        todoRepository = todoRepository,
                                                        // 上课提醒卡数据源：今天该上的课 + 当前时刻 →
                                                        // 倒计时/上课中/课已上完/今日无课
                                                        scheduleRepository = repository,
                                                        // 待办提醒卡标签配色数据源（与待办页同源）
                                                        tagRepository = tagRepository,
                                                        // AI 对话（日程页顶栏右侧按钮唤起对话浮层）
                                                        aiChatRepository = aiChatRepository,
                                                        // 选择图片浮层在场时藏底部导航栏（同课表/待办页浮层）
                                                        onOverlayOverNavBarChange = onOverlayOverNavBarChange,
                                                        // tab 转场状态：01/02 的 Haze 冻结层据此重放就位帧
                                                        tabTransition = transition,
                                                    )
                                                }
                                            }
                                            AppTab.Timetable -> ScheduleScreen(
                                                modifier = pageModifier,
                                                repository = repository,
                                                todoRepository = todoRepository,
                                                editing = editing,
                                                onEditingChange = onEditingChange,
                                                onOverlayOverNavBarChange = onOverlayOverNavBarChange,
                                                onOpenSettings = onOpenSettings,
                                                onOpenAccountSync = onOpenAccountSync,
                                                xmuSyncRepository = xmuSyncRepository,
                                                xmuAutoLoginManager = xmuAutoLoginManager,
                                                xmuScheduleImporter = xmuScheduleImporter,
                                                tabTransition = transition,
                                                // 网格读降频态（即时态只喂个性化页滑条）
                                                cardHeights = gridCardHeightsState,
                                                // 课程卡片教师行显隐（个性化页开关写入）
                                                showTeacher = showTeacherState,
                                            )
                                            AppTab.Todo -> TodoScreen(
                                                modifier = pageModifier,
                                                repository = todoRepository,
                                                tagRepository = tagRepository,
                                                scheduleRepository = repository,
                                                showNewTodoSheet = showNewTodoSheet,
                                                onNewTodoSheetDismiss = onNewTodoSheetDismiss,
                                                onOverlayOverNavBarChange = onOverlayOverNavBarChange,
                                                tabTransition = transition,
                                                // 收集箱「瀑布流视图」开关（个性化页写入，默认开启）
                                                masonryView = todoMasonryState,
                                            )
                                        }
                                    }
                                }
                            }
                            // 编辑态或课表页全屏浮层在场时隐藏底部导航栏（设计稿如此，也避免编辑中途被切走）：
                            // 下移出屏 / 上移入屏，与网格收拢、遮罩（ScheduleScreen 内）共用
                            // [Motion.EditMillis] + [Motion.Overlay]，同一个 editing 翻转同帧启动；
                            // 顶栏换场同帧但另用更缓的 [Motion.Standard]（见 ScheduleScreen）
                            AnimatedVisibility(
                                visibleState = navBarVisibility,
                                enter = slideInVertically(
                                    // 从自身高度下方起步：上移入
                                    animationSpec = tween(Motion.EditMillis, easing = Motion.Overlay),
                                ) { it } + fadeIn(tween(Motion.EditMillis, easing = Motion.Overlay)),
                                exit = slideOutVertically(
                                    // 滑向自身高度下方：下移出
                                    animationSpec = tween(Motion.EditMillis, easing = Motion.Overlay),
                                ) { it } + fadeOut(tween(Motion.EditMillis, easing = Motion.Overlay)),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    // 导航栏永远压在所有页面之上：页面 z 序随选中递增无上界，
                                    // 用极大值一劳永逸——切 tab 转场中新页从它下方滑过，它纹丝不动
                                    .zIndex(Float.MAX_VALUE),
                            ) {
                                // 顶部余量见 NavBarTransitionHeadroom：转场层 alpha 合成按层
                                // 边界裁剪，按压放大的「新建」胶囊顶边与投影必须留在层内
                                BottomNavBar(
                                    modifier = Modifier.padding(top = NavBarTransitionHeadroom),
                                    selectedTab = pagerState.selectedTab,
                                    // 关闭时必须传 null：backdrop 非空即玻璃（全 app 统一判定），
                                    // 采样层对象本身常驻（挂载与否由 useGlass 门控），非空会让
                                    // 胶囊误走玻璃分支
                                    backdrop = if (useGlass) navBackdrop else null,
                                    // 退场动画期间仍在组合中，挡掉点击：编辑中途或浮层打开时不许切 tab。
                                    // 待办页已选中时胶囊即「新建」入口：再点呼起新建待办浮层
                                    onTabSelected = { tab ->
                                        if (!editing && !navBarHiddenByOverlay) {
                                            if (tab == pagerState.selectedTab && tab == AppTab.Todo) {
                                                showNewTodoSheet = true
                                            } else {
                                                pagerState.selectTab(tab)
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    ) {
                        // 冻结帧代际：四屏顶栏的冻结键读它；离开 RESUMED 后自动失效，
                        // 避免重放被系统清空的旧帧
                        CompositionLocalProvider(
                            LocalSettingsFreezeEpoch provides settingsFreezeEpoch,
                        ) {
                            SettingsScreen(
                                onClose = onCloseSettings,
                                repository = repository,
                                xmuSyncRepository = xmuSyncRepository,
                                xmuAutoLoginManager = xmuAutoLoginManager,
                                openAccountSyncDirectly = settingsOpenAccountSync,
                                onAccountSyncDirectlyOpened = onAccountSyncDirectlyOpened,
                                themeMode = themeMode,
                                onThemeModeChange = onSettingsThemeModeChange,
                                cardHeights = cardHeightsState,
                                // 只改内存即时生效；落盘由上方 LaunchedEffect 去抖完成
                                onCardHeightsChange = onSettingsCardHeightsChange,
                                showTeacher = showTeacherState,
                                // 只改内存即时生效 + 即时落盘（布尔翻转无连续变化，无需去抖）
                                onShowTeacherChange = onSettingsShowTeacherChange,
                                glassStyle = glassStyleState,
                                // 只改内存即时生效 + 即时落盘（同 showTeacher）
                                onGlassStyleChange = onSettingsGlassStyleChange,
                                todoMasonry = todoMasonryState,
                                // 只改内存即时生效 + 即时落盘（同 showTeacher）
                                onTodoMasonryChange = onSettingsTodoMasonryChange,
                                // 设置页根页顶栏的冻结层要读外层覆盖层进度
                                overlayTransitionState = settingsOverlayState,
                                modifier = settingsModifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 进程级依赖容器：remember 持有、LaunchedEffect 内调起 suspend [AppContainer.get]。
 * 返回即三库磁盘加载完成 ⇒ 非 null 即数据就绪；null 仅首帧毫秒级（各屏自行显示加载态）。
 */
@Composable
private fun rememberAppContainer(): AppContainer? {
    var container by remember { mutableStateOf<AppContainer?>(null) }
    val context = LocalContext.current
    LaunchedEffect(context) {
        container = AppContainer.get(context.applicationContext)
    }
    return container
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
