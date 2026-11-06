package com.fangyi.classpp.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 *
 * 2.1 起 `HazeBlurStyle { }` 按 identity 比较（2.0.1 按值比较）：每次重组新建实例会被判为
 * 「样式变了」，触发效果重解析 + 重绘。故按影响外观的键 remember 一份；[opacity] 为滚动
 * 进度（逐帧变化），仍留在键里——进度不变时复用同一实例，只有真变化时才换 Style。
 */
@Composable
internal fun hazeTopBarStyle(radius: Dp = 25.dp, opacity: Float = 1f): HazeBlurStyle {
    val tint = MaterialTheme.classppColors.hazeTint
    return remember(radius, opacity, tint) {
        HazeBlurStyle {
            blurRadius(radius)
            progressive(HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f))
            colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = 0.30f))))
            noiseFactor(0f)
            alpha(opacity)
        }
    }
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
