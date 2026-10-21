package com.fangyi.classpp.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch

/**
 * Q弹按压放大（仿官方 Kyant0/AndroidLiquidGlass 的按压手感）：
 * 按下果冻式鼓到 [pressedScale]，松手弹回原位。X/Y 两轴弹簧稍异刚度，
 * 鼓起与回弹的过冲不同步——形状会先歪一下再弹正，像果冻一样颤，
 * 这就是「Q弹」的来源；单轴等比缩放出不来这个效果。
 *
 * 弹簧规格照抄官方 DampedDragAnimation 的按压段：X 轴 spring(0.6, 250)（稍弹、
 * 过冲略大）、Y 轴 spring(0.7, 250)（稍稳）。两轴都带约 9% 的轻微过冲；
 * 官方玻璃指示器的按压幅度是 78/56 ≈ 1.39，槽位里的组件建议 1.15 上下防压邻。
 *
 * 与 [pressFeedback] 的分工：pressFeedback = 硬节奏缩放 + 主体提亮；
 * 本效果只管缩放、换成官方软弹簧手感。两者可共用同一按压源叠加——
 * 叠加时把 pressFeedback 的 scale 传 1f，只留提亮，缩放交给这里。
 *
 * 事件语义沿用 pressFeedback：逐事件驱动（快速点按不丢按压，对标官方 RippleNode），
 * Cancel（拖动被滚动抢走）立即回弹。一处刻意的不同：松手**不等**上行动画播完、
 * 从当前值直接弹回（官方同款，Animatable 内置互斥天然如此）——Q弹的短按就是
 * 一次快速的部分鼓起加弹回，等播完反而拖沓。
 *
 * 用法：接在调用方自己的 modifier 链上，惯例位置与 pressFeedback 相同——
 * 带 clip 的图层之前（放大会被 clip 裁掉）：
 * ```
 * val press = remember { MutableInteractionSource() }
 * Box(
 *     Modifier
 *         .pressScale(press, pressedScale = 1.2f)
 *         .background(…, shape)
 *         .clickable(interactionSource = press, indication = null, onClick = …),
 * )
 * ```
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = DefaultPressedScale,
): Modifier {
    // 命名避开 GraphicsLayerScope 的同名属性（lambda 内会遮蔽外层 val）
    val scaleXAnim = remember { Animatable(1f) }
    val scaleYAnim = remember { Animatable(1f) }
    // 逐事件驱动（见 KDoc）：Animatable 内置互斥，新事件的新 animateTo 会从
    // 当前值接管在播动画——快速连按/连放都不丢拍、不跳变
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    launch { scaleXAnim.animateTo(pressedScale, ScaleXSpec) }
                    launch { scaleYAnim.animateTo(pressedScale, ScaleYSpec) }
                }
                is PressInteraction.Release -> {
                    launch { scaleXAnim.animateTo(1f, ScaleXSpec) }
                    launch { scaleYAnim.animateTo(1f, ScaleYSpec) }
                }
                is PressInteraction.Cancel -> {
                    launch { scaleXAnim.animateTo(1f, ScaleXSpec) }
                    launch { scaleYAnim.animateTo(1f, ScaleYSpec) }
                }
                // Interaction 非密封（官方留的扩展点）：Hover/Focus/Drag 与按压无关
                else -> Unit
            }
        }
    }
    return graphicsLayer {
        scaleX = scaleXAnim.value
        scaleY = scaleYAnim.value
    }
}

/**
 * [pressScale] + clickable 三件套的便捷封装（对齐 pressClickable）：内部自建
 * 按压源，同时交给 pressScale（观察按压）与 clickable（indication 置 null，
 * 涟漪由按压反馈体系取代）。禁用态点击不发按压事件，反馈自动消失。
 */
@Composable
fun Modifier.pressScaleClickable(
    pressedScale: Float = DefaultPressedScale,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val press = remember { MutableInteractionSource() }
    return pressScale(press, pressedScale).clickable(
        interactionSource = press,
        indication = null,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}

/** 默认按压放大倍率：与胶囊原 pressFeedback 的 1.15 保持同一按压记忆 */
private const val DefaultPressedScale = 1.15f

/** 官方手感弹簧（DampedDragAnimation 按压段）：X 稍弹、Y 稍稳——异刚度才有果冻感 */
private val ScaleXSpec = spring(0.6f, 250f, 0.001f)
private val ScaleYSpec = spring(0.7f, 250f, 0.001f)
