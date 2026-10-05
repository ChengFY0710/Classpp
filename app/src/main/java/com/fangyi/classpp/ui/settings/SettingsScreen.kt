package com.fangyi.classpp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.clearFocusOnTap
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.ThemeMode
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// 纵向节奏（分组间距、首项距顶栏、尾部余量）。横向边距走 ui.theme 的 PageHorizontalSpacing
// （与浮层同源），不在此列
private val SectionSpacing = 18.dp

/** 底部额外留白：末屏内容可继续上滑一段（滑到顶栏之后仍有一段余量） */
private val BottomScrollSlack = 120.dp

/**
 * 设置页：全屏覆盖层（由 MainActivity 组合在底部导航之后），返回键/关闭按钮经 [onClose] 退出。
 *
 * 课表级设置（课表名/学期/天数/节数时间/显示开关）已整体迁往编辑态的「课表设置」浮层
 * （见 Schedule ui 的 [com.fangyi.classpp.ui.schedule.ScheduleSettingsSheet]），本页只剩：
 * - app 级设置「深色模式」三选一（[themeMode] / [onThemeModeChange]，任何状态下都显示）；
 * - [repository] = null → 加载指示（仅首帧毫秒级）；
 * - 新建课表已迁至课表页空态的「新建课表」浮层（NewScheduleSheet），本页不再承载创建入口；
 * - 有激活课表 → 正文留空，仅保留顶栏（返回按钮 + 标题 + 渐变模糊）。
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    repository: ScheduleRepository? = null,
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    BackHandler { onClose() }

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
                            .verticalScroll(rememberScrollState())
                            // 点空白（卡片间隙/留白）取消聚焦收起键盘；课表名的失焦提交
                            // （SheetTextField）也由这次清焦触发
                            .clearFocusOnTap()
                            .padding(horizontal = PageHorizontalSpacing)
                            .padding(
                                // 首项距顶栏留 20dp（SectionSpacing），与页面其余间距同源
                                top = topBarHeight + SectionSpacing,
                                bottom = navBarPadding + SectionSpacing + BottomScrollSlack,
                            ),
                        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
                    ) {
                        // app 级设置不依赖课表数据：无激活课表时同样可用
                        AppearanceSection(
                            mode = themeMode,
                            onSelect = onThemeModeChange,
                        )
                        // 课表级设置已迁往「课表设置」浮层，激活课表本身不再有专属设置项
                        //（顶栏模糊的采样源仍由本列承担，不可省）
                    }
                }
            }

            SettingsTopBar(
                title = stringResource(R.string.settings_title),
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

/**
 * 外观设置：app 级「深色模式」三选一（跟随系统/浅色/深色），行尾显示当前模式。
 * 点行弹选择对话框，点选项即时生效并关闭——对话框下方的页面同步变色，反馈直观，
 * 无需「确定」一步。
 */
@Composable
private fun AppearanceSection(
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    CardSection(title = stringResource(R.string.section_appearance)) {
        SettingsCard(
            items = listOf(
                SettingsCardItem.Nav(
                    label = stringResource(R.string.appearance_theme_mode),
                    value = stringResource(mode.labelRes),
                    onClick = { picking = true },
                ),
            ),
        )
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = {
                Text(stringResource(R.string.appearance_theme_mode))
            },
            text = {
                Column {
                    ThemeMode.entries.forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(candidate)
                                    picking = false
                                }
                                // 行高给足触达面积；radio 与文字的间距随行内边距统一
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 点击由整行承担：radio 本身 onClick = null，只做状态展示
                            RadioButton(selected = candidate == mode, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(candidate.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            // 即时生效交互：唯一的按钮承担「关闭」，与设置页既有对话框的取消位一致
            confirmButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}
