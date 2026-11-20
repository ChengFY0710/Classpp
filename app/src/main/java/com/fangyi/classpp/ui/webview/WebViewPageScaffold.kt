package com.fangyi.classpp.ui.webview

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.WebAddressBar
import com.fangyi.classpp.ui.settings.SettingsTopBar
import com.fangyi.classpp.ui.settings.SettingsTopBarAction
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.LocalNavigationBarInsets
import com.fangyi.classpp.ui.theme.LocalStatusBarHeight
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.isGlassStyleEnabled
import com.fangyi.classpp.ui.theme.rememberGlassBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/**
 * 全屏 WebView 页顶栏的确认 / 停止动作：图标、无障碍描述与可用态。
 *
 * 配色固定为 primary 圆钮（玻璃模式内容色走 onPrimaryGlass），与「从教务网页识别」
 * 页右上圆钮完全同规格；需要别的配色再扩展，不在调用点各写一套。
 */
@Immutable
internal data class WebViewPageAction(
    @DrawableRes val icon: Int,
    val contentDescription: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 全屏 WebView 页共用骨架：设置页同款顶栏 + 顶栏下的网页内容 + 底部地址栏。
 *
 * 布局与「从教务网页识别」页一致：
 * - [SettingsTopBar]（返回圆钮 + 居中标题）最后绘制，圆钮投影盖在内容与底栏之上；
 *   顶栏实测高度回写给内容区，WebView 只占顶栏以下区域；
 * - 底部 [WebAddressBar] 浮在网页之上（导航栏 inset + 键盘避让 + 10dp 呼吸量），
 *   地址栏可以采样页面做玻璃；固定入口页传 [addressEditable] = false，地址只读但
 *   「手机版 / 电脑版」UA 菜单仍可用；
 * - 液态玻璃采样层只记录整页底色：WebView 是 AndroidView，放进 Compose 采样层会
 *   反复录制导致页面概率性闪烁（与识别页同一取舍）；顶栏圆钮 / 地址栏仍拿到
 *   [rememberGlassBackdrop] 下发的 backdrop。
 *
 * 网页加载进度统一交给地址栏左侧圆环（[loading]）；错误提示由宿主页面自己走
 * `AppToasts`，本骨架不再画错误遮罩 / 内联文案。
 */
@Composable
internal fun WebViewPageScaffold(
    title: String,
    onBack: () -> Unit,
    address: String,
    desktopMode: Boolean,
    onDesktopModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    action: WebViewPageAction? = null,
    addressPlaceholder: String = "",
    loading: Boolean = false,
    statusText: String? = null,
    readOnly: Boolean = false,
    addressEditable: Boolean = false,
    onAddressChange: ((String) -> Unit)? = null,
    onOpenAddress: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val glassEnabled = isGlassStyleEnabled()
    // 液态玻璃采样层：只记录整页底色；顶栏圆钮与地址栏在采样节点之外，玻璃不照见自己
    val glassBackdrop = rememberGlassBackdrop(background)
    val buttonBackdrop = if (glassEnabled) glassBackdrop else null
    val confirmContentColor = if (glassEnabled) {
        MaterialTheme.classppColors.onPrimaryGlass
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val density = LocalDensity.current
    // 顶栏实测高度（含状态栏 inset）：初始用稳定 inset + 64dp 估算，测量后校正
    val initialTopBarHeight = LocalStatusBarHeight.current + TopBarContentHeight
    var topBarHeight by remember { mutableStateOf(initialTopBarHeight) }

    val topAction = action?.let {
        SettingsTopBarAction(
            icon = it.icon,
            contentDescription = it.contentDescription,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = confirmContentColor,
            enabled = it.enabled,
            onClick = it.onClick,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 统一不避让横向 inset：铺满屏幕宽（左右顶到边缘），状态栏交给顶栏自理
            .background(background),
    ) {
        if (glassEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(glassBackdrop),
            )
        }
        // 内容区在采样层之外：顶栏以下才是 WebView 的加载 / 显示范围
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarHeight)
                .clipToBounds(),
        ) {
            content()
        }
        // 底部浮层：在采样层之外，玻璃地址栏可以采样页面
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(LocalNavigationBarInsets.current)
                .imePadding()
                .padding(horizontal = PageHorizontalSpacing, vertical = BottomBarVerticalPadding),
        ) {
            WebAddressBar(
                value = address,
                onValueChange = { onAddressChange?.invoke(it) },
                onOpen = { onOpenAddress?.invoke() },
                modifier = Modifier.fillMaxWidth(),
                placeholder = addressPlaceholder,
                openedUrl = address.ifBlank { null },
                loading = loading,
                statusText = statusText,
                readOnly = readOnly,
                // 编辑需要宿主同时提供输入与打开回调；固定入口页三样都不传即只读
                addressEditable = addressEditable &&
                    onAddressChange != null && onOpenAddress != null,
                desktopMode = desktopMode,
                onDesktopModeChange = onDesktopModeChange,
                backdrop = buttonBackdrop,
            )
        }
        // 顶栏最后绘制：圆钮投影盖在内容与底栏之上，玻璃圆钮采样整页底色 / 内容
        SettingsTopBar(
            title = title,
            onBack = onBack,
            trailingAction = topAction,
            buttonBackdrop = buttonBackdrop,
            modifier = Modifier
                .align(Alignment.TopStart)
                .onGloballyPositioned { coords ->
                    topBarHeight = with(density) { coords.size.height.toDp() }
                },
        )
    }
}

/** 设置页顶栏内容高度（不含状态栏）：CenterAlignedTopAppBar 默认 64dp */
private val TopBarContentHeight = 64.dp

/** 底部地址栏上方 / 下方的呼吸量（与识别页底部浮层同值） */
private val BottomBarVerticalPadding = 10.dp

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun WebViewPageScaffoldPreview() = ClassppTheme {
    WebViewPageScaffold(
        title = "教务课表",
        onBack = {},
        address = "https://jw.xmu.edu.cn/student/course",
        desktopMode = true,
        onDesktopModeChange = {},
        action = WebViewPageAction(
            icon = R.drawable.ic_checkmark,
            contentDescription = "导入当前学期课表",
            onClick = {},
        ),
        loading = true,
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
    }
}
