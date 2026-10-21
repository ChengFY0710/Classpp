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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch

/**
 * Q弹按压放大 + 按压提亮（仿官方 Kyant0/AndroidLiquidGlass 的按压手感）：
 * 按下果冻式鼓到 [pressedScale]、沿轮廓加色白提亮，松手弹回原位。X/Y 两轴
 * 弹簧稍异刚度，鼓起与回弹的过冲不同步——形状会先歪一下再弹正，像果冻一样颤，
 * 这就是「Q弹」的来源；单轴等比缩放出不来这个效果。
 *
 * 弹簧规格照抄官方 DampedDragAnimation 的按压段：X 轴 spring(0.6, 250)（稍弹、
 * 过冲略大）、Y 轴 spring(0.7, 250)（稍稳）。两轴都带约 9% 的轻微过冲；
 * 官方玻璃指示器的按压幅度是 78/56 ≈ 1.39，槽位里的组件建议 1.15 上下防压邻。
 *
 * 提亮层沿用 pressFeedback 的做法：加色白（BlendMode.Plus）沿组件轮廓叠加，
 * 全程不建合成层——子树向轮廓外溢出的投影不被裁断（rationale 见 pressFeedback
 * KDoc）；进度与 X 轴缩放共用同一颗弹簧，提亮跟着果冻节奏一起脉动。
 * [shade] 可切 [PressShade.Darken]（低透明度黑压暗）——白色底浅色模式下
 * 提亮物理上无感的替代方向。
 *
 * 与 [pressFeedback] 的关系：同一套「缩放 + 提亮」，只有缩放手感不同——
 * pressFeedback 是硬节奏（tween 上行 + Settle 回位），本效果是官方软弹簧
 * 果冻感。直接互相替换即可，不再需要叠加。
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
 *         .pressScale(press, PillShape, pressedScale = 1.2f)
 *         .background(…, PillShape)
 *         .clickable(interactionSource = press, indication = null, onClick = …),
 * )
 * ```
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    shape: Shape,
    pressedScale: Float = DefaultPressedScale,
    shade: PressShade = PressShade.Brighten,
): Modifier {
    // 命名避开 GraphicsLayerScope 的同名属性（lambda 内会遮蔽外层 val）
    val scaleXAnim = remember { Animatable(1f) }
    val scaleYAnim = remember { Animatable(1f) }
    // 提亮进度独立一档（0..1）：缩放 Animatable 的值域是 1..pressedScale，
    // 从缩放值反推进度要依赖 pressedScale ≠ 1，不划算。弹簧与 X 轴同款，
    // 提亮跟着果冻节奏脉动（过冲上限 ≈1.09，alpha = 0.15 × p 恒小于 1，无需夹紧）
    val brightenProgress = remember { Animatable(0f) }
    val layoutDirection = LocalLayoutDirection.current
    // 逐事件驱动（见 KDoc）：Animatable 内置互斥，新事件的新 animateTo 会从
    // 当前值接管在播动画——快速连按/连放都不丢拍、不跳变
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    launch { scaleXAnim.animateTo(pressedScale, ScaleXSpec) }
                    launch { scaleYAnim.animateTo(pressedScale, ScaleYSpec) }
                    launch { brightenProgress.animateTo(1f, ScaleXSpec) }
                }
                is PressInteraction.Release -> {
                    launch { scaleXAnim.animateTo(1f, ScaleXSpec) }
                    launch { scaleYAnim.animateTo(1f, ScaleYSpec) }
                    launch { brightenProgress.animateTo(0f, ScaleXSpec) }
                }
                is PressInteraction.Cancel -> {
                    launch { scaleXAnim.animateTo(1f, ScaleXSpec) }
                    launch { scaleYAnim.animateTo(1f, ScaleYSpec) }
                    launch { brightenProgress.animateTo(0f, ScaleXSpec) }
                }
                // Interaction 非密封（官方留的扩展点）：Hover/Focus/Drag 与按压无关
                else -> Unit
            }
        }
    }
    return this
        .graphicsLayer {
            scaleX = scaleXAnim.value
            scaleY = scaleYAnim.value
        }
        // 明暗层画在内容之上、只落在组件轮廓内，轮廓之外（投影所在区域）不沾边：
        // Brighten = 加色白把底色和图文一起抬亮（深色/彩色底）；
        // Darken = 低透明度黑 SrcOver（视觉上即乘法压暗），白底浅色模式下的替代。
        // 画在 graphicsLayer 内侧，随缩放一起变换，始终贴合变形后的轮廓
        .drawWithContent {
            drawContent()
            val p = brightenProgress.value
            if (p > BrightnessThreshold) {
                val outline = shape.createOutline(size, layoutDirection, this@drawWithContent)
                val path = Path().apply { addOutline(outline) }
                when (shade) {
                    PressShade.Brighten -> drawPath(
                        path = path,
                        color = Color.White,
                        alpha = BrightnessLift * p,
                        blendMode = BlendMode.Plus,
                    )
                    PressShade.Darken -> drawPath(
                        path = path,
                        color = Color.Black,
                        alpha = DarkenShade * p,
                    )
                }
            }
        }
}

/**
 * [pressScale] + clickable 三件套的便捷封装（对齐 pressClickable）：内部自建
 * 按压源，同时交给 pressScale（观察按压）与 clickable（indication 置 null，
 * 涟漪由按压反馈体系取代）。禁用态点击不发按压事件，反馈自动消失。
 */
@Composable
fun Modifier.pressScaleClickable(
    shape: Shape,
    pressedScale: Float = DefaultPressedScale,
    shade: PressShade = PressShade.Brighten,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val press = remember { MutableInteractionSource() }
    return pressScale(press, shape, pressedScale, shade).clickable(
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

/** 提亮幅度（加色白的峰值透明度）：同 pressFeedback（白底组件无法更亮，反馈由缩放承担） */
private const val BrightnessLift = 0.15f

/** 低于该进度不再绘制提亮：弹簧收尾是渐近的，防止停在极小值上每帧空画 */
private const val BrightnessThreshold = 0.004f

/** 压暗幅度（黑罩峰值透明度）：同 pressFeedback，微微压暗、白底明显降一档 */
private const val DarkenShade = 0.06f
