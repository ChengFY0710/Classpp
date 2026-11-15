package com.fangyi.classpp.ui.theme

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全 app 唯一的「顶部状态栏让位」来源（dp）：各页顶栏、编辑栏、全屏浮层与列表顶部留白
 * 都读这里，不再各自直读 `WindowInsets.statusBars`；底部导航栏让位同源，见
 * [LocalNavigationBarHeight]。
 *
 * 系统 statusBars inset 在两类场景并不可靠：
 * - 跨应用转场：把待办附件交给系统查看器等，目标窗口会隐藏状态栏，宿主窗口收到
 *   statusBars = 0；
 * - 小窗 / 自由窗口：窗口不在状态栏下方，inset 直接为 0。
 * 各处直接跟随会让顶栏突然失去让位、浮层整张窜到屏幕顶端，回来再落下（上下闪）。
 *
 * 根部 [ProvideStableWindowInsets] 把它收敛成一个稳定值：
 * - 取 `statusBarsIgnoringVisibility`，状态栏被隐藏时仍返回它「若显示会占用」的高度；
 * - 0 不覆盖已有正值：小窗 / 转场期间保持最近一次有效高度，窗口回到带状态栏的形态后
 *   高度天然一致，顶栏零位移；
 * - 真实高度变化（横竖屏、窗口形态给出另一个正值）照常更新。
 * 本窗口从来没有状态栏的形态（如平板分屏下半屏、桌面窗口）初值即 0，不会凭空留白。
 */
val LocalStatusBarHeight = compositionLocalOf { 0.dp }

/**
 * [LocalStatusBarHeight] 的 [WindowInsets] 形态（只带 top、横向与底部为 0），给
 * `Scaffold(contentWindowInsets)` / `Modifier.windowInsetsPadding` 等只收 insets 的
 * 位置使用；语义与 [LocalStatusBarHeight] 完全同源。
 */
val LocalStatusBarInsets = compositionLocalOf { WindowInsets() }

/**
 * 全 app 唯一的「底部导航栏让位」来源（dp）：手势小白条（或三键导航栏）占用的高度。
 * 底部胶囊导航栏、列表尾部留白、全屏弹卡与输入栏都读这里，不再各自直读
 * `WindowInsets.navigationBars` / 用 `navigationBarsPadding()`。
 *
 * 与状态栏同理：跨应用转场、小窗 / 自由窗口下 navigationBars inset 会突变为 0，直接跟随
 * 会让底部结构整体向下塌、小白条区域失去让位。这里同样取 ignoringVisibility 高度并用
 * 「0 不覆盖已有正值」保持稳定；横向 inset 按项目约定一律不避让，本值只含 bottom。
 */
val LocalNavigationBarHeight = compositionLocalOf { 0.dp }

/**
 * [LocalNavigationBarHeight] 的 [WindowInsets] 形态（只带 bottom），给
 * `Modifier.windowInsetsPadding` / `Scaffold` 等只收 insets 的位置使用。仍参与 Compose
 * 的 inset 消费链：`.windowInsetsPadding(LocalNavigationBarInsets.current).imePadding()`
 * 这类顺序不会双计键盘高度。
 */
val LocalNavigationBarInsets = compositionLocalOf { WindowInsets() }

/**
 * 在组合根部提供稳定的状态栏 / 导航栏让位（见 [LocalStatusBarHeight] 与
 * [LocalNavigationBarHeight] 的 KDoc）。由 [ClassppTheme] 统一挂载，页面 / 浮层 /
 * 弹出菜单（Compose Popup 子组合会继承 CompositionLocal）一律从对应的 Local 取值。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProvideStableWindowInsets(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    // 「忽略可见性」的高度：系统栏被目标窗口隐藏时仍给出其占位高度
    val liveTopHeight = with(density) {
        WindowInsets.statusBarsIgnoringVisibility.getTop(density).toDp()
    }
    val liveBottomHeight = with(density) {
        WindowInsets.navigationBarsIgnoringVisibility.getBottom(density).toDp()
    }
    // 0 不覆盖已有正值（见文件 KDoc）：正值即时生效（与直读同帧，启动 / 旋转不落后一帧），
    // 0 期间回落到最近一次正值；初值可能是首帧尚未派发 insets 的 0，首个真实高度到达后补齐
    var lastPositiveTop by remember { mutableStateOf(liveTopHeight) }
    var lastPositiveBottom by remember { mutableStateOf(liveBottomHeight) }
    SideEffect {
        if (liveTopHeight > 0.dp) lastPositiveTop = liveTopHeight
        if (liveBottomHeight > 0.dp) lastPositiveBottom = liveBottomHeight
    }
    val stableTop = if (liveTopHeight > 0.dp) liveTopHeight else lastPositiveTop
    val stableBottom = if (liveBottomHeight > 0.dp) liveBottomHeight else lastPositiveBottom
    CompositionLocalProvider(
        LocalStatusBarHeight provides stableTop,
        LocalStatusBarInsets provides WindowInsets(top = stableTop),
        LocalNavigationBarHeight provides stableBottom,
        LocalNavigationBarInsets provides WindowInsets(bottom = stableBottom),
        content = content,
    )
}
