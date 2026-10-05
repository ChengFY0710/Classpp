package com.fangyi.classpp.ui.settings

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
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.motion.rubberBandVerticalScroll
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.ThemeMode
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 个性化子页（设置 → 个性化）：由 [SettingsScreen] 以整页覆盖转场组合在根页之上
 * （与设置页覆盖层同源，见 ui.motion 的 PageOverlayTransition），返回按钮/返回键经 [onBack] 回到设置根页。
 *
 * 骨架与设置根页同构（Scaffold + 滚动内容列 + 顶栏叠加），正文承载 app 级外观设置：
 * 「颜色模式」三选一（跟随系统/浅色/深色，[themeMode] / [onThemeModeChange]），
 * 行尾显示当前模式，点行弹选择对话框，点选项即时生效并关闭——对话框下方的页面同步变色，
 * 反馈直观，无需「确定」一步。后续外观类设置项（字号、图标风格等）继续加入本页。
 */
@Composable
fun PersonalizationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        // 沉浸式：只吃左右 inset，状态栏/手势条区域交给内容与顶栏自己铺满（同设置根页）
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

            // 采样源始终存在：正文列挂 hazeSource，顶栏背后才有内容可采（不可省）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    // 橡皮筋增强版 verticalScroll：内容不足一屏时也能拉出橡皮筋
                    // （ui.motion 的 rubberBandVerticalScroll）
                    .rubberBandVerticalScroll(rememberScrollState())
                    .padding(horizontal = PageHorizontalSpacing)
                    .padding(
                        // 首项距顶栏留 SectionSpacing，与设置根页节奏同源
                        top = topBarHeight + SectionSpacing,
                        bottom = navBarPadding + SectionSpacing + BottomScrollSlack,
                    ),
                verticalArrangement = Arrangement.spacedBy(SectionSpacing),
            ) {
                ColorModeSection(
                    mode = themeMode,
                    onSelect = onThemeModeChange,
                )
            }

            SettingsTopBar(
                title = stringResource(R.string.settings_personalization),
                onBack = onBack,
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
 * 颜色模式设置：app 级三选一（跟随系统/浅色/深色），行尾显示当前模式。
 * 点行弹选择对话框，点选项即时生效并关闭——对话框下方的页面同步变色，反馈直观，
 * 无需「确定」一步。
 */
@Composable
private fun ColorModeSection(
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    SettingsCard(
        items = listOf(
            SettingsCardItem.Nav(
                label = stringResource(R.string.appearance_theme_mode),
                value = stringResource(mode.labelRes),
                onClick = { picking = true },
            ),
        ),
    )

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
