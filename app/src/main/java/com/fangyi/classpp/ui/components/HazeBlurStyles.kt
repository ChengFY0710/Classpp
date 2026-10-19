package com.fangyi.classpp.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
 * 显式用 [HazeSourceSelection.All] 绕过「最近祖先 hazeSource」的隐式关系——语义等价于
 * 1.x 的「按 state 采集全部源」，也避免以后把效果嵌进源子树时悄悄选不到源。
 * [HazeSourceRetention.KeepLastFrame] 与 Haze 2 默认一致：源短暂不可用时保留上一帧，
 * 避免切换 / 重组瞬间闪空。
 */
internal fun hazeSources(state: HazeState): HazeInput.Sources =
    HazeInput.Sources(
        state = state,
        selection = HazeSourceSelection.All,
        retention = HazeSourceRetention.KeepLastFrame,
    )

/**
 * 顶栏渐变磨砂：顶部最强、向下渐弱（vertical 1→0），磨砂 tint 30%。
 *
 * 1.x 的 `hazeEffect { blurRadius = ...; progressive = ...; tints = ... }` 在 2.0 改为
 * 不可变、可共享的 [HazeBlurStyle]：属性写入变成 Style 函数，tint 由 [HazeColorEffect] 工厂创建。
 * [opacity] 供课表头 / 编辑栏随滚动进度渐入（旧 `alpha = progress`）。
 */
@Composable
internal fun hazeTopBarStyle(radius: Dp = 25.dp, opacity: Float = 1f): HazeBlurStyle {
    val tint = MaterialTheme.classppColors.hazeTint
    return HazeBlurStyle {
        blurRadius(radius)
        progressive(HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f))
        colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = 0.30f))))
        noiseFactor(0f)
        alpha(opacity)
    }
}

/**
 * 白卡 / 浮层卡磨砂：磨砂 tint 60%（弹出菜单卡、周数选择卡、底部选择卡同款配方）。
 * [radius] 由调用方给出：白卡 12dp，底部选择卡用自己的 CardBlurRadius。
 */
@Composable
internal fun hazeCardStyle(radius: Dp = 12.dp): HazeBlurStyle {
    val tint = MaterialTheme.classppColors.hazeTint
    return HazeBlurStyle {
        blurRadius(radius)
        colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = 0.6f))))
        noiseFactor(0f)
    }
}
