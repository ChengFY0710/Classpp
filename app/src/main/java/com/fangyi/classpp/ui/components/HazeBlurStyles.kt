package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.EaseIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.classppColors
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect

/**
 * Haze 2 的采样输入：本项目每个 [HazeState] 只承载「本页 / 本浮层滚动内容」一组源。
 * selection 固定 [HazeSourceSelection.Behind]（Haze 2 默认值，显式写出以锁定约定）：
 * 只采样 z 序位于最近祖先 hazeSource 之后的源，无匹配祖先时回退为全部源。
 * 项目**禁用 [HazeSourceSelection.All]**（见 docs/AGENTS.md）：它会无视 z 序采集
 * state 的全部源，效果与源交叠时会把前景 / 自身内容一并采进模糊。
 * [HazeSourceRetention.KeepLastFrame] 与 Haze 2 默认一致：源短暂不可用时保留上一帧，
 * 避免切换 / 重组瞬间闪空。
 */
internal fun hazeSources(state: HazeState): HazeInput.Sources =
    HazeInput.Sources(
        state = state,
        selection = HazeSourceSelection.Behind,
        retention = HazeSourceRetention.KeepLastFrame,
    )

/**
 * 顶栏磨砂的 tint 浓度：Haze 顶栏 Style 与无 Haze 的静态底材（[topBarStaticScrim]）
 * 共用这一个值，两条路径的静止态颜色才不会随改动漂移。
 */
private const val HazeTopBarTintAlpha = 0.30f

/** 静态底材的 tint 采样点数：与 Haze 的 HazeProgressive.asBrush 默认一致（20） */
private const val TopBarScrimStops = 20

/**
 * 顶栏渐变磨砂：顶部最强、向下渐弱（vertical 1→0），磨砂 tint 30%。
 *
 * 1.x 的 `hazeEffect { blurRadius = ...; progressive = ...; tints = ... }` 在 2.0 改为
 * 不可变、可共享的 [HazeBlurStyle]：属性写入变成 Style 函数，tint 由 [HazeColorEffect] 工厂创建。
 * [opacity] 供课表头 / 编辑栏随滚动进度渐入（旧 `alpha = progress`），传 null 表示恒 1
 * （设置 / 待办 / 日程等静态顶栏与 Preview）。
 *
 * 2.1 起 `HazeBlurStyle { }` 按 identity 比较（2.0.1 按值比较）：每次重组新建实例会被判为
 * 「样式变了」，触发效果重解析 + 重绘；同时 Style 块可以读快照状态，变化时由效果节点
 * 重新解析并重绘，不重组调用点。故 remember 的键是进度 [State] 的**身份**而不是它当前的值：
 * 宿主 remember 一次 State（滚动派生值）后，进度逐帧变化既不新建 Style、也不重组。
 * 调用方不得传入每次重组都新建的 State——那等于退回逐帧重建。
 */
@Composable
internal fun hazeTopBarStyle(
    radius: Dp = 25.dp,
    opacity: State<Float>? = null,
): HazeBlurStyle {
    val tint = MaterialTheme.classppColors.hazeTint
    return remember(radius, tint, opacity) {
        HazeBlurStyle {
            blurRadius(radius)
            progressive(HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f))
            colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = HazeTopBarTintAlpha))))
            noiseFactor(0f)
            alpha((opacity?.value ?: 1f).coerceIn(0f, 1f))
        }
    }
}

/**
 * 无 Haze 顶栏的静态底材（`OverlaySheet(hazeEnabled = false)` 用）。
 *
 * Haze 顶栏磨砂在静止态（内容还没滚到顶栏下）呈现为「不透明底色 + tint 自顶向下渐隐」：
 * tint 曲线是 progressive 的 `lerp(1, 0, EaseIn(x))`，坐标取顶栏自身高度的 0~1（Haze 的
 * progressive 以效果节点尺寸为坐标系，不含 blur 半径的外扩，见 BlurVisualEffectUtils
 * 的 `contentSize = context.size`）。本函数按同一条曲线生成静态渐变，并在这里完成
 * [tint] 与 [background] 的合成——顶段保持不透明，按钮与标题可读；整条链路不挂任何
 * RenderNode 采样，因此不可能进入 Haze 的自引用崩溃路径。
 *
 * [fadeStartFraction] 之后渐隐到完全透明：内容从顶栏下滚过时柔和淡出，替代模糊的
 * 视觉收边。渐隐终点的 RGB 仍取 [background]，避免渐透过程中发灰。
 */
internal fun topBarStaticScrim(
    background: Color,
    tint: Color,
    fadeStartFraction: Float,
): Brush {
    val fadeStart = fadeStartFraction.coerceIn(0f, 1f)
    val stops = buildList {
        repeat(TopBarScrimStops) { index ->
            val x = index.toFloat() / (TopBarScrimStops - 1)
            val y = x * fadeStart
            val intensity = 1f - EaseIn.transform(y)
            add(y to tint.copy(alpha = HazeTopBarTintAlpha * intensity).compositeOver(background))
        }
        add(1f to background.copy(alpha = 0f))
    }
    return Brush.verticalGradient(colorStops = stops.toTypedArray())
}

/**
 * 白卡 / 浮层卡磨砂：磨砂 tint 60%（弹出菜单卡、周数选择卡、底部选择卡同款配方）。
 * [radius] 由调用方给出：白卡 12dp，底部选择卡用自己的 CardBlurRadius。
 * [tintAlpha] 染色浓度（0~1）：越大玻璃越「实」、背景越淡；调用点可单独覆盖，
 * 默认 60% 与既有观感一致。
 *
 * 同 [hazeTopBarStyle]：2.1 的 Style 按 identity 比较，必须 remember，否则每次重组
 * （含 [PopupGlassCard] 默认参数求值）都会新建实例并让效果失效一次。
 */
@Composable
internal fun hazeCardStyle(
    radius: Dp = 12.dp,
    tintAlpha: Float = 0.6f,
): HazeBlurStyle {
    val tint = MaterialTheme.classppColors.hazeTint
    return remember(radius, tintAlpha, tint) {
        HazeBlurStyle {
            blurRadius(radius)
            colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = tintAlpha))))
            noiseFactor(0f)
        }
    }
}
