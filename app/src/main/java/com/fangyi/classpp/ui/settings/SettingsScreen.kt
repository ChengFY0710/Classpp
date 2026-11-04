package com.fangyi.classpp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.xmu.XmuAutoLoginManager
import com.fangyi.classpp.data.xmu.XmuSyncRepository
import com.fangyi.classpp.ui.components.SettingsCardwithIcon
import com.fangyi.classpp.ui.components.SettingsCardwithIconItem
import com.fangyi.classpp.ui.components.SheetBottomSlack
import com.fangyi.classpp.ui.motion.LocalPageOverlayActive
import com.fangyi.classpp.ui.motion.LocalPageOverlayPrewarm
import com.fangyi.classpp.ui.motion.PageOverlayTransition
import com.fangyi.classpp.ui.motion.PageOverlayTransitionState
import com.fangyi.classpp.ui.motion.ProvideOverscroll
import com.fangyi.classpp.ui.motion.rememberPageOverlayTransitionState
import com.fangyi.classpp.ui.motion.rubberBandVerticalScroll
import com.fangyi.classpp.ui.schedule.CardHeightScales
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.ThemeMode
import com.fangyi.classpp.ui.theme.isDarkTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// 纵向节奏：首项距顶栏为页自有节奏（18dp）；分组间距与课表设置浮层同源
// （ui.theme 的 SettingsSectionSpacing），尾部余量与 overlay 浮层同源
// （ui.components 的 SheetBottomSlack）——浮层调节奏这里自动跟随。横向边距走
// ui.theme 的 PageHorizontalSpacing（与浮层同源），不在此列。
// 子页（PersonalizationScreen / NotificationScreen）复用同一节奏
internal val TopBarContentGap = 18.dp

/** 根页三张入口卡之间的间距（设计稿：独立卡片、留缝堆叠） */
private val EntryCardSpacing = 12.dp

/**
 * 「账号与同步」入口卡的行值：未连接 / 已连接 姓名 / 登录已过期。
 * 状态流在这一行内 collect（repository == null 时显示未连接）；过期态没有独立落盘标志，
 * 仅由同步流程的探活结果驱动（子页横幅同一份判定），这里只呈现「已连接与否」。
 *
 * 订阅收敛在本卡：账号状态变化只重组这一行（item 列表按 label/value 记忆化），
 * 不再向上失效整个设置根页。
 */
@Composable
private fun SettingsAccountSyncCard(
    repository: XmuSyncRepository?,
    onClick: () -> Unit,
) {
    val sync = if (repository != null) repository.state.collectAsState().value else null
    val label = stringResource(R.string.settings_account_sync)
    val valueText = if (sync != null && sync.uid.isNotEmpty()) {
        if (sync.accountName.isBlank()) stringResource(R.string.xmu_connected_label)
        else stringResource(R.string.xmu_connected_name, sync.accountName)
    } else {
        stringResource(R.string.xmu_not_connected_label)
    }
    val items = remember(label, valueText, onClick) {
        listOf(
            SettingsCardwithIconItem(
                icon = R.drawable.ic_arrow_sync_circle,
                label = label,
                value = valueText,
                onClick = onClick,
            ),
        )
    }
    SettingsCardwithIcon(items = items)
}

/**
 * 「AI 设置」入口卡的行值：已配置 / 未配置（连接与 Key 是否齐备）。
 *
 * 与 [SettingsAccountSyncCard] 同一套订阅收敛：只有本卡 collect 配置状态，
 * 状态变化只重组这一行（item 列表按 label/value 记忆化），不向上失效设置根页。
 */
@Composable
private fun SettingsAiEntryCard(
    repository: AiChatRepository?,
    onClick: () -> Unit,
) {
    val configured = if (repository != null) repository.configured.collectAsState().value else false
    val label = stringResource(R.string.settings_ai)
    val valueText = stringResource(
        if (configured) R.string.ai_settings_status_configured
        else R.string.ai_settings_status_not_configured,
    )
    val items = remember(label, valueText, onClick) {
        listOf(
            SettingsCardwithIconItem(
                icon = R.drawable.ic_ai,
                label = label,
                value = valueText,
                onClick = onClick,
            ),
        )
    }
    SettingsCardwithIcon(items = items)
}

/**
 * 单行入口卡的 item 列表：按标签与回调记忆化。字符串/回调不变时列表实例不变，
 * 下行卡片可整体 skip（`SettingsCardwithIconItem` 已标 @Immutable）。
 */
@Composable
private fun rememberSettingsEntryItem(
    @DrawableRes icon: Int,
    @StringRes labelRes: Int,
    onClick: () -> Unit,
): List<SettingsCardwithIconItem> {
    val label = stringResource(labelRes)
    return remember(icon, label, onClick) {
        listOf(SettingsCardwithIconItem(icon = icon, label = label, onClick = onClick))
    }
}

/**
 * 设置页：全屏覆盖层（由 MainActivity 组合在底部导航之后），返回键/关闭按钮经 [onClose] 退出。
 *
 * 结构 = 设置根页 + 子页覆盖层（完整转场与设置页覆盖层同源，ui.motion 的 [PageOverlayTransition]）：
 * - 根页（[SettingsHomePage]）标题「设置」，正文六张独立入口卡：
 *   个性化 / 通知与提醒 / AI 设置 / 账号与同步 / 数据管理 / 关于；
 * - 「个性化」子页（[PersonalizationScreen]）承载 app 级外观设置，已实装；
 *   「通知与提醒」子页（[NotificationScreen]）承载通知设置项 UI，已实装；
 *   「AI 设置」子页（[AISettingsScreen]）承载 AI 服务商/连接/Key 配置，已实装；
 *   「账号与同步」子页（[XmuSyncScreen]）承载课程中心待办同步，已实装；
 *   「数据管理」「关于」入口本次仅占位（点击暂无响应，子页后续接入）；
 * - 各子页互斥（各自从根页打开、经根页返回，永不同屏）：「账号与同步」覆盖层嵌在最外层，
 *   「AI 设置」次之，通知页与「个性化页 + 根页」整体作为它们的 behind——根页仍只组合一次，
 *   嵌套层的让位视差与压暗遮罩把下层内容当一个整体对待；
 * - 返回键优先关子页（账号与同步 → AI 设置 → 通知页 → 个性化页）；无子页时经 [onClose]
 *   退出设置。子页退场动画期间标志已复位，此时再按返回会直接退出设置——与设置页覆盖层
 *   「退场中幂等关闭」同语义。
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    repository: ScheduleRepository? = null,
    xmuSyncRepository: XmuSyncRepository? = null,
    xmuAutoLoginManager: XmuAutoLoginManager? = null,
    // AI 对话仓库：AI 设置子页读写连接配置与 Key，根页入口卡展示「已配置/未配置」
    aiChatRepository: AiChatRepository? = null,
    // 直达「账号与同步」子页（课表页教务导入浮层的账号行跳转）：置真时展开子页，
    // 与手动从根页进入走完全相同的子页转场；消费后回调复位标志
    openAccountSyncDirectly: Boolean = false,
    onAccountSyncDirectlyOpened: () -> Unit = {},
    // 直达「AI 设置」子页（AI 对话浮层的设置入口跳转）：语义同 openAccountSyncDirectly
    openAiSettingsDirectly: Boolean = false,
    onAiSettingsDirectlyOpened: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    // State 实例透传（本层不读 .value）：滑条拖动时本屏与个性化页整体 skip，只重组值的叶子
    cardHeights: State<CardHeightScales> = mutableStateOf(CardHeightScales()),
    onCardHeightsChange: (CardHeightScales) -> Unit = {},
    // 课程卡片「显示教师」开关（同 cardHeights 的透传纪律）
    showTeacher: State<Boolean> = mutableStateOf(true),
    onShowTeacherChange: (Boolean) -> Unit = {},
    // 玻璃材质开关（同 cardHeights 的透传纪律）
    glassStyle: State<Boolean> = mutableStateOf(false),
    onGlassStyleChange: (Boolean) -> Unit = {},
    // 待办收集箱「瀑布流视图」开关（同 cardHeights 的透传纪律）
    todoMasonry: State<Boolean> = mutableStateOf(true),
    onTodoMasonryChange: (Boolean) -> Unit = {},
    // 外层设置页覆盖层的进度状态（MainActivity 持有）：根页顶栏在设置页进出场视差
    // 期间冻结重放需要它。null = 独立预览等无覆盖层场景
    overlayTransitionState: PageOverlayTransitionState? = null,
) {
    var showPersonalization by rememberSaveable { mutableStateOf(false) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    var showAiSettings by rememberSaveable { mutableStateOf(false) }
    var showXmuSync by rememberSaveable { mutableStateOf(false) }
    // 覆盖层内容常驻组合（ui.motion 的 PageOverlayTransition）：隐藏态下页面只是停在
    // 屏外、返回键处理必须让位给系统（否则主界面按返回会被吃掉）；转场彻底结束后把
    // 子页复位到根页——与此前 AnimatedVisibility「关闭即销毁」的重开语义一致
    val overlayActive = LocalPageOverlayActive.current
    // 启动空闲预热帧（MainActivity 在首帧后触发）：整页停在 x=0 近透明画两帧，
    // 让各屏顶栏的 Haze 初始化并录下就位帧。交互仍按 overlayActive 判定
    val prewarm = LocalPageOverlayPrewarm.current
    // 四个嵌套覆盖层的进度状态上提：既传给嵌套转场，也让各屏顶栏在「任意一层在动」
    // 时统一走冻结重放（根页在全部四层之外，子页也可能被祖先的 behind 视差带走）
    val personalizationTransition = rememberPageOverlayTransitionState()
    val notificationTransition = rememberPageOverlayTransitionState()
    val aiSettingsTransition = rememberPageOverlayTransitionState()
    val xmuSyncTransition = rememberPageOverlayTransitionState()
    val settingsTransforming: () -> Boolean = remember(
        overlayTransitionState,
        personalizationTransition,
        notificationTransition,
        aiSettingsTransition,
        xmuSyncTransition,
    ) {
        {
            overlayTransitionState?.isTransforming == true ||
                personalizationTransition.isTransforming ||
                notificationTransition.isTransforming ||
                aiSettingsTransition.isTransforming ||
                xmuSyncTransition.isTransforming
        }
    }
    // 导航回调记忆化（同 MainActivity 的范式）：闭包捕获同一 MutableState 实例，
    // 语义与内联写法一致；避免每次重组都把各屏的入参变成新实例、连带重组
    val openPersonalization = remember { { showPersonalization = true } }
    val openNotifications = remember { { showNotifications = true } }
    val openAiSettings = remember { { showAiSettings = true } }
    val openXmuSync = remember { { showXmuSync = true } }
    val closePersonalization = remember { { showPersonalization = false } }
    val closeNotifications = remember { { showNotifications = false } }
    val closeAiSettings = remember { { showAiSettings = false } }
    val closeXmuSync = remember { { showXmuSync = false } }
    val fullSizeModifier = remember { Modifier.fillMaxSize() }
    BackHandler(enabled = overlayActive) {
        when {
            // 子页逐层退：账号与同步在最外层，优先于 AI 设置/通知页/个性化页
            showXmuSync -> showXmuSync = false
            showAiSettings -> showAiSettings = false
            showNotifications -> showNotifications = false
            showPersonalization -> showPersonalization = false
            else -> onClose()
        }
    }
    LaunchedEffect(overlayActive) {
        if (!overlayActive) {
            showPersonalization = false
            showNotifications = false
            showAiSettings = false
            showXmuSync = false
        }
    }
    // 直达子页请求：展开「账号与同步」（转场与手动进入一致）。声明在上方复位效果之后，
    // 首帧两个效果依次执行时直达请求后跑、不被隐藏态复位吞掉
    LaunchedEffect(openAccountSyncDirectly) {
        if (openAccountSyncDirectly) {
            showXmuSync = true
            onAccountSyncDirectlyOpened()
        }
    }
    // 直达「AI 设置」请求（AI 对话浮层的设置入口）：语义同上
    LaunchedEffect(openAiSettingsDirectly) {
        if (openAiSettingsDirectly) {
            showAiSettings = true
            onAiSettingsDirectlyOpened()
        }
    }

    Box(modifier.fillMaxSize()) {
        // 整页（根页 + 各子页）滚动内容启用 iOS 式橡皮筋 overscroll（ui.motion 的
        // ProvideOverscroll）：滚到顶/底后继续拖动，内容整块被拉出边缘、越拉越硬，松手
        // 无过冲弹回。平移露出的是 Scaffold 同色底（background）；顶栏 haze 毛玻璃条固定
        // 不动、内容从其后滚过/弹回，即 iOS 大标题页的手感
        ProvideOverscroll {
            // 子页以与设置页覆盖层完全相同的完整转场盖住根页（ui.motion 的 PageOverlayTransition：
            // 让位视差 + 压暗遮罩 + 右滑进出场 + 转场期左缘圆角）——共用同一实现，两层观感严格一致。
            // 「账号与同步」嵌在最外层，「AI 设置」次之：通知页 + 「个性化 + 根页」整体作为
            // 它们的 behind，各子页永不同屏。prewarm 逐层下发：预热帧里各屏都停在 x=0
            // （子页屏外预热会让 Haze 效果落在屏外，触发 RenderThread 递归）
            PageOverlayTransition(
                visible = showXmuSync,
                modifier = fullSizeModifier,
                state = xmuSyncTransition,
                prewarm = prewarm,
                behind = {
                    PageOverlayTransition(
                        visible = showAiSettings,
                        modifier = fullSizeModifier,
                        state = aiSettingsTransition,
                        prewarm = prewarm,
                        behind = {
                            PageOverlayTransition(
                                visible = showNotifications,
                                modifier = fullSizeModifier,
                                state = notificationTransition,
                                prewarm = prewarm,
                                behind = {
                                    PageOverlayTransition(
                                        visible = showPersonalization,
                                        modifier = fullSizeModifier,
                                        state = personalizationTransition,
                                        prewarm = prewarm,
                                        behind = {
                                            SettingsHomePage(
                                                onClose = onClose,
                                                onOpenPersonalization = openPersonalization,
                                                onOpenNotifications = openNotifications,
                                                onOpenAiSettings = openAiSettings,
                                                onOpenXmuSync = openXmuSync,
                                                xmuSyncRepository = xmuSyncRepository,
                                                aiChatRepository = aiChatRepository,
                                                repository = repository,
                                                // 设置覆盖层隐藏时整页停在屏外仍是常驻组合：Haze 效果只在
                                                // 覆盖层活跃期挂载，避免对屏外页面做源捕获/模糊（Haze 2 下
                                                // 屏外页面上的效果会触发 RenderThread 递归崩溃）
                                                overlayActive = overlayActive,
                                                settingsTransforming = settingsTransforming,
                                                modifier = fullSizeModifier,
                                            )
                                        },
                                    ) {
                                        PersonalizationScreen(
                                            onBack = closePersonalization,
                                            themeMode = themeMode,
                                            onThemeModeChange = onThemeModeChange,
                                            repository = repository,
                                            cardHeights = cardHeights,
                                            onCardHeightsChange = onCardHeightsChange,
                                            showTeacher = showTeacher,
                                            onShowTeacherChange = onShowTeacherChange,
                                            glassStyle = glassStyle,
                                            onGlassStyleChange = onGlassStyleChange,
                                            todoMasonry = todoMasonry,
                                            onTodoMasonryChange = onTodoMasonryChange,
                                            settingsTransforming = settingsTransforming,
                                            modifier = fullSizeModifier,
                                        )
                                    }
                                },
                            ) {
                                NotificationScreen(
                                    onBack = closeNotifications,
                                    settingsTransforming = settingsTransforming,
                                    modifier = fullSizeModifier,
                                )
                            }
                        },
                    ) {
                        AISettingsScreen(
                            onBack = closeAiSettings,
                            aiChatRepository = aiChatRepository,
                            settingsTransforming = settingsTransforming,
                            modifier = fullSizeModifier,
                        )
                    }
                },
            ) {
                XmuSyncScreen(
                    onBack = closeXmuSync,
                    repository = xmuSyncRepository,
                    autoLogin = xmuAutoLoginManager,
                    settingsTransforming = settingsTransforming,
                    modifier = fullSizeModifier,
                )
            }
        }
    }
}

/**
 * 设置根页：顶栏（标题「设置」+ 圆形返回按钮 + 渐变模糊）+ 六张独立入口卡。
 *
 * 课表级设置（课表名/学期/天数/节数时间/显示开关）在编辑态的「课表设置」浮层
 * （见 Schedule ui 的 [com.fangyi.classpp.ui.schedule.ScheduleSettingsSheet]），
 * 外观设置在「个性化」子页、通知设置在「通知与提醒」子页、AI 服务商配置在
 * 「AI 设置」子页、课程中心待办同步在「账号与同步」子页，本页只承担导航分发。
 * 账号与 AI 两张入口卡的行内同步展示状态（账号：未连接 / 已连接 姓名 / 登录已过期，
 * 取自 [xmuSyncRepository] 的状态流；AI：已配置 / 未配置，取自 [aiChatRepository]）。
 * [repository] = null → 加载指示（仅首帧毫秒级）。
 */
@Composable
private fun SettingsHomePage(
    onClose: () -> Unit,
    onOpenPersonalization: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenXmuSync: () -> Unit,
    repository: ScheduleRepository?,
    overlayActive: Boolean,
    settingsTransforming: () -> Boolean = { false },
    modifier: Modifier = Modifier,
    xmuSyncRepository: XmuSyncRepository? = null,
    aiChatRepository: AiChatRepository? = null,
) {
    Scaffold(
        modifier = modifier,
        // 沉浸式：只吃左右 inset，状态栏/手势条区域交给内容与顶栏自己铺满
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        // 顶栏叠在内容之上：内容整屏铺开（hazeSource），首屏经 topBarHeight 内缩到顶栏之下，
        // 上滑时从顶栏背后滚过，顶栏用 Haze 对其做自上而下的渐变背景模糊
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val hazeState = rememberHazeState()
            // 顶栏返回按钮的液态玻璃：采样层挂下方滚动内容（与 hazeSource 同一节点，
            // Haze 管顶栏磨砂、Backdrop 只喂返回按钮）；null = 玻璃关闭，标记不挂载
            val glassBackdrop = rememberSettingsGlassBackdrop()
            var topBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            // 滚动状态上提：冻结键要读滚动位置，隐藏态停录后滚动变化会让旧帧失配
            val scrollState = rememberScrollState()
            // 预热帧也要挂 Haze（否则录到的就位帧没有模糊）；隐藏态不录制保留上次的帧
            val hazeActive = overlayActive || LocalPageOverlayPrewarm.current
            // 冻结键：主题/顶栏高度/滚动位置/账号状态任一变化都回退实时绘制。
            // 只在绘制期求值，不逐帧重组
            val darkTheme = MaterialTheme.isDarkTheme
            val freezeEpoch = LocalSettingsFreezeEpoch.current
            val freezeKey: () -> Any = remember(
                darkTheme,
                scrollState,
                xmuSyncRepository,
                freezeEpoch,
            ) {
                {
                    val sync = xmuSyncRepository?.state?.value
                    SettingsTopBarFreezeKey(
                        page = SettingsPage.Root,
                        darkTheme = darkTheme,
                        topBarHeight = topBarHeight,
                        scroll = scrollState.value,
                        epoch = freezeEpoch,
                        inputs = sync?.let { listOf(it.uid, it.accountName) },
                    )
                }
            }
            // 手势条（小白条）区域：内容可铺到屏幕底，末尾留出这段避免被遮挡
            val navBarPadding = WindowInsets.navigationBars
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()

            when {
                // 采样源始终存在：加载态也挂 hazeSource，避免顶栏背后无源可采
                repository == null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(hazeState)
                        // 玻璃返回按钮开启时加载态同样挂采样标记（按钮玻璃一直渲染）
                        .then(
                            if (glassBackdrop != null) {
                                Modifier.layerBackdrop(glassBackdrop)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(hazeState)
                            // 玻璃返回按钮开启时同一节点再挂液态玻璃采样标记
                            .then(
                                if (glassBackdrop != null) {
                                    Modifier.layerBackdrop(glassBackdrop)
                                } else {
                                    Modifier
                                },
                            )
                            // 橡皮筋增强版 verticalScroll：入口卡不足一屏的大屏上也能拉出
                            // 橡皮筋（ui.motion 的 rubberBandVerticalScroll）
                            .rubberBandVerticalScroll(scrollState)
                            .padding(horizontal = PageHorizontalSpacing)
                            .padding(
                                // 首项距顶栏留 TopBarContentGap（页自有节奏），尾部余量与浮层同源
                                top = topBarHeight + TopBarContentGap,
                                bottom = navBarPadding + SheetBottomSlack,
                            ),
                        verticalArrangement = Arrangement.spacedBy(EntryCardSpacing),
                    ) {
                        // 六张独立入口卡（设计稿形态）；数据管理/关于子页待实装，入口先占位。
                        // 入口 item 列表按标签/回调记忆化，账号与 AI 行在各自卡内 collect，
                        // 状态变化只重组对应行
                        val noop = remember { {} }
                        SettingsCardwithIcon(
                            items = rememberSettingsEntryItem(
                                icon = R.drawable.ic_paint_brush,
                                labelRes = R.string.settings_personalization,
                                onClick = onOpenPersonalization,
                            ),
                        )
                        SettingsCardwithIcon(
                            items = rememberSettingsEntryItem(
                                icon = R.drawable.ic_alert,
                                labelRes = R.string.settings_notifications,
                                onClick = onOpenNotifications,
                            ),
                        )
                        SettingsAiEntryCard(
                            repository = aiChatRepository,
                            onClick = onOpenAiSettings,
                        )
                        SettingsAccountSyncCard(
                            repository = xmuSyncRepository,
                            onClick = onOpenXmuSync,
                        )
                        SettingsCardwithIcon(
                            items = rememberSettingsEntryItem(
                                icon = R.drawable.ic_book_database,
                                labelRes = R.string.settings_data_management,
                                onClick = noop,
                            ),
                        )
                        SettingsCardwithIcon(
                            items = rememberSettingsEntryItem(
                                icon = R.drawable.ic_info,
                                labelRes = R.string.settings_about,
                                onClick = noop,
                            ),
                        )
                    }
                }
            }

            SettingsTopBar(
                title = stringResource(R.string.settings_root_title),
                onBack = onClose,
                hazeState = if (hazeActive) hazeState else null,
                buttonBackdrop = glassBackdrop,
                freezeKey = freezeKey,
                recordWhen = { hazeActive },
                isTransforming = settingsTransforming,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onGloballyPositioned { coords ->
                        // 顶栏实测高度 = 内容首屏内缩量（px → dp）
                        topBarHeight = with(density) { coords.size.height.toFloat().toDp() }
                    },
            )
        }
    }
}
