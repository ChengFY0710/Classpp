package com.fangyi.classpp.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
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
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 按压反馈：按下时组件整体放大、主体提亮，松手还原。
 *
 * 按压态的唯一来源是调用方与 clickable/combinedClickable/selectable 共用的
 * [interactionSource]——禁止点击时点击修饰符不发按压事件、嵌套子控件按下时只有
 * 真正处理点击的那一层亮起，都由这一点自动成立。
 *
 * 动画**逐事件**驱动（直接消费 interactions 流，对标官方 RippleNode），不走
 * collectIsPressedAsState 的布尔中转：快速点按的 Press/Release 会落在同一帧窗口
 * （可滚动容器内 clickable 对 <100ms 的点按就是背靠背发射），布尔态被合并成 false、
 * LaunchedEffect(pressed) 从未以 true 运行，按压动画会整段丢失。Release 不打断上行——
 * 先等按压动画完整播完再回弹，保证每次按下都完整可见（对标系统 ripple 的最短可见
 * 时长做法）；Cancel（拖动被滚动抢走）则立即回弹，列表起手不闪。
 *
 * 时长与曲线取 ui.motion 的公共 token：按下 [Motion.FastMillis] + [Motion.Decelerate]
 * （起步即全速，无迟滞感），松手 [Motion.Settle] 弹簧回位。放大倍率、提亮幅度等
 * 仅供本效果使用的参数留在本文件。
 *
 * 提亮刻意没有走 graphicsLayer 的 colorFilter：colorFilter 会让 Compose 把整层内容
 * 渲染进一张与图层同尺寸的离屏缓冲（RenderNode 合成层），子树里向轮廓外溢出的投影
 * （如胶囊按钮的大柔影）会被缓冲边缘裁断——表现为按下时阴影四角出现直线切口。
 * 这里改为沿组件轮廓叠一层加色白（[BlendMode.Plus]，即 PorterDuff ADD，硬件画布通用），
 * 全程不建图层，投影完整；[shade] 可切换方向——[PressShade.Darken] 用低透明度黑
 * SrcOver 压暗（白色底在浅色模式下加色白无感），同样不建图层。
 *
 * 用法：接在调用方自己的 modifier 链上即可，[shape] 传组件自身的形状
 * （提亮范围与投影形状都以下它为准）：
 * ```
 * val press = remember { MutableInteractionSource() }
 * Row(
 *     modifier = Modifier
 *         .pressFeedback(press, PillShape)
 *         .background(containerColor, PillShape)
 *         .clickable(interactionSource = press, indication = null, onClick = onClick),
 * )
 * ```
 */
@Composable
fun Modifier.pressFeedback(
    interactionSource: InteractionSource,
    shape: Shape,
    scale: Float = DefaultPressScale,
    shade: PressShade = PressShade.Brighten,
): Modifier {
    val progress = remember { Animatable(0f) }
    // 逐事件驱动（见 KDoc）：pressJob = 上行，exitJob = 回弹。每个事件只做 Job 切换、
    // 不挂起——事件流（replay 0 + 16 槽 DROP_OLDEST）不会积压，快速连点时最新事件立即生效
    LaunchedEffect(interactionSource) {
        var pressJob: Job? = null
        var exitJob: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    // 新按下立即接管：打断在播回弹与上一轮上行，从当前值接着亮
                    exitJob?.cancel()
                    pressJob?.cancel()
                    pressJob = launch {
                        progress.animateTo(1f, tween(Motion.FastMillis, easing = Motion.Decelerate))
                    }
                }
                is PressInteraction.Release -> {
                    // 抬起不打断上行：先等按下动画完整播完（join）再回弹——短按的
                    // Press/Release 常在同一帧批次到达，这样每次按下都保证完整可见。
                    // 先捕获引用再 launch，避免与后续新按下的 pressJob 串扰
                    exitJob?.cancel()
                    val pressAnim = pressJob
                    exitJob = launch {
                        pressAnim?.join()
                        progress.animateTo(0f, Motion.Settle)
                    }
                }
                is PressInteraction.Cancel -> {
                    // 取消（拖动被滚动抢走等）：立刻回弹、不等上行，列表起手不闪
                    pressJob?.cancel()
                    exitJob?.cancel()
                    exitJob = launch {
                        progress.animateTo(0f, Motion.Settle)
                    }
                }
                // Interaction 是普通接口（非 sealed，官方留的扩展点）：Hover/Focus/Drag 与按压无关
                else -> Unit
            }
        }
    }
    val layoutDirection = LocalLayoutDirection.current
    return this
        // 只做缩放变换：变换本身不触发合成层，子树投影不受影响
        .graphicsLayer {
            val s = 1f + (scale - 1f) * progress.value
            scaleX = s
            scaleY = s
        }
        // 明暗层画在内容之上、且只落在组件轮廓内，轮廓之外（投影所在区域）不沾边：
        // Brighten = 加色白把底色和图文一起抬亮（深色/彩色底）；
        // Darken = 低透明度黑 SrcOver（视觉上即乘法压暗），白底浅色模式下提亮无感的替代
        .drawWithContent {
            drawContent()
            val p = progress.value
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

/** 默认按压放大倍率：中小控件够用；大卡片可传更小值、小图标钮可传更大值。 */
private const val DefaultPressScale = 1.04f

/** 提亮幅度（加色白的峰值透明度）。白底组件物理上无法更亮，反馈由缩放承担。 */
private const val BrightnessLift = 0.15f

/** 低于该进度不再绘制提亮：弹簧收尾是渐近的，防止停在极小值上每帧空画。 */
private const val BrightnessThreshold = 0.004f

/** 压暗幅度（黑罩峰值透明度）：微微压暗，深色图文几乎不受影响、白底明显降一档。 */
private const val DarkenShade = 0.06f

/**
 * 按压明暗层的方向：[Brighten] 加色白提亮（深色/彩色底，默认）；
 * [Darken] 低透明度黑压暗——白色底在浅色模式下物理上无法更亮，
 * 由调用方按主题选它（如设置行浅色模式压暗、深色模式照常提亮）。
 */
enum class PressShade { Brighten, Darken }

/**
 * [pressFeedback] + clickable 三件套的便捷封装：内部自建按压源，同时交给
 * [pressFeedback]（观察按压）与 clickable（indication 置 null，涟漪由按压反馈取代），
 * 普通点击点位一行接入。禁用态点击不发按压事件，反馈自动消失。
 *
 * 需要自行配对的形态仍用手动写法：长按 combinedClickable、toggleable/selectable、
 * 以及 M3 按钮（interactionSource 是它们的形参，clickable 不存在于调用侧）。
 */
@Composable
fun Modifier.pressClickable(
    shape: Shape,
    scale: Float = DefaultPressScale,
    shade: PressShade = PressShade.Brighten,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val press = remember { MutableInteractionSource() }
    return pressFeedback(
        interactionSource = press,
        shape = shape,
        scale = scale,
        shade = shade,
    ).clickable(
        interactionSource = press,
        indication = null,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}

/**
 * 空 Indication：在 ClassppTheme 根部替换 LocalIndication，去掉裸 clickable 的涟漪——
 * 点击反馈统一由 pressFeedback/pressClickable 接管后，涟漪退场。
 *
 * foundation 1.10.4 起 Indication 的扩展点是 [IndicationNodeFactory]
 * （旧的 IndicationInstance / rememberUpdatedInstance 已弃用至 ERROR 级，不可实现）；
 * 接口契约要求单例值等价，故覆写 equals/hashCode。
 */
object NoOpIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() {
                drawContent()
            }
        }

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0
}
