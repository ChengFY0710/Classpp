package com.fangyi.classpp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
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
import com.fangyi.classpp.ui.components.SettingsCardwithIcon
import com.fangyi.classpp.ui.components.SettingsCardwithIconItem
import com.fangyi.classpp.ui.motion.ProvideOverscroll
import com.fangyi.classpp.ui.motion.pageSlideIn
import com.fangyi.classpp.ui.motion.pageSlideOut
import com.fangyi.classpp.ui.motion.rubberBandVerticalScroll
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.ThemeMode
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// 纵向节奏（分组间距、首项距顶栏、尾部余量）。横向边距走 ui.theme 的 PageHorizontalSpacing
// （与浮层同源），不在此列。子页（PersonalizationScreen）复用同一节奏
internal val SectionSpacing = 18.dp

/** 底部额外留白：末屏内容可继续上滑一段（滑到顶栏之后仍有一段余量） */
internal val BottomScrollSlack = 120.dp

/** 根页三张入口卡之间的间距（设计稿：独立卡片、留缝堆叠） */
private val EntryCardSpacing = 12.dp

/**
 * 设置页：全屏覆盖层（由 MainActivity 组合在底部导航之后），返回键/关闭按钮经 [onClose] 退出。
 *
 * 结构 = 设置根页 + 子页右滑覆盖层（转场节奏同设置页本身：进 360 / 出 250）：
 * - 根页（[SettingsHomePage]）标题「设置」，正文三张独立入口卡：个性化 / 数据管理 / 关于；
 * - 「个性化」子页（[PersonalizationScreen]）承载 app 级外观设置，已实装；
 *   「数据管理」「关于」入口本次仅占位（点击暂无响应，子页后续接入）；
 * - 返回键优先关子页；无子页时经 [onClose] 退出设置。子页退场动画期间 [showPersonalization]
 *   已复位，此时再按返回会直接退出设置——与设置页覆盖层「退场中幂等关闭」同语义。
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    repository: ScheduleRepository? = null,
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    var showPersonalization by rememberSaveable { mutableStateOf(false) }
    BackHandler {
        if (showPersonalization) showPersonalization = false else onClose()
    }

    Box(modifier.fillMaxSize()) {
        // 整页（根页 + 个性化子页）滚动内容启用 iOS 式橡皮筋 overscroll（ui.motion 的
        // ProvideOverscroll）：滚到顶/底后继续拖动，内容整块被拉出边缘、越拉越硬，松手
        // 无过冲弹回。平移露出的是 Scaffold 同色底（background）；顶栏 haze 毛玻璃条固定
        // 不动、内容从其后滚过/弹回，即 iOS 大标题页的手感
        ProvideOverscroll {
            SettingsHomePage(
                onClose = onClose,
                onOpenPersonalization = { showPersonalization = true },
                repository = repository,
                modifier = Modifier.fillMaxSize(),
            )
            // 子页整页从右缘滑入盖住根页（根页本身是不透明整页，无需压暗遮罩），退场右滑出；
            // 转场节奏与设置页覆盖层本身一致（ui.motion 的 pageSlideIn/Out），同源才有连续感
            AnimatedVisibility(
                visible = showPersonalization,
                enter = pageSlideIn(),
                exit = pageSlideOut(),
            ) {
                PersonalizationScreen(
                    onBack = { showPersonalization = false },
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 设置根页：顶栏（标题「设置」+ 圆形返回按钮 + 渐变模糊）+ 三张独立入口卡。
 *
 * 课表级设置（课表名/学期/天数/节数时间/显示开关）在编辑态的「课表设置」浮层
 * （见 Schedule ui 的 [com.fangyi.classpp.ui.schedule.ScheduleSettingsSheet]），
 * 外观设置已迁往「个性化」子页，本页只承担导航分发。
 * [repository] = null → 加载指示（仅首帧毫秒级）。
 */
@Composable
private fun SettingsHomePage(
    onClose: () -> Unit,
    onOpenPersonalization: () -> Unit,
    repository: ScheduleRepository?,
    modifier: Modifier = Modifier,
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
            var topBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
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
                        .hazeSource(hazeState),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(hazeState)
                            // 橡皮筋增强版 verticalScroll：入口卡不足一屏的大屏上也能拉出
                            // 橡皮筋（ui.motion 的 rubberBandVerticalScroll）
                            .rubberBandVerticalScroll(rememberScrollState())
                            .padding(horizontal = PageHorizontalSpacing)
                            .padding(
                                // 首项距顶栏留 SectionSpacing，与页面其余间距同源
                                top = topBarHeight + SectionSpacing,
                                bottom = navBarPadding + SectionSpacing + BottomScrollSlack,
                            ),
                        verticalArrangement = Arrangement.spacedBy(EntryCardSpacing),
                    ) {
                        // 三张独立入口卡（设计稿形态）；数据管理/关于子页待实装，入口先占位
                        SettingsCardwithIcon(
                            items = listOf(
                                SettingsCardwithIconItem(
                                    icon = R.drawable.ic_paint_brush,
                                    label = stringResource(R.string.settings_personalization),
                                    onClick = onOpenPersonalization,
                                ),
                            ),
                        )
                        SettingsCardwithIcon(
                            items = listOf(
                                SettingsCardwithIconItem(
                                    icon = R.drawable.ic_book_database,
                                    label = stringResource(R.string.settings_data_management),
                                    onClick = {},
                                ),
                            ),
                        )
                        SettingsCardwithIcon(
                            items = listOf(
                                SettingsCardwithIconItem(
                                    icon = R.drawable.ic_info,
                                    label = stringResource(R.string.settings_about),
                                    onClick = {},
                                ),
                            ),
                        )
                    }
                }
            }

            SettingsTopBar(
                title = stringResource(R.string.settings_root_title),
                onBack = onClose,
                hazeState = hazeState,
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
