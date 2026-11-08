package com.fangyi.classpp.ui.ai

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.ClassppColors
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.classppColors
import kotlin.math.cos
import kotlin.math.sin

/** 2π：光斑路径与明暗起伏都以「秒」为周期单位，乘以它换算成弧度。 */
private const val TwoPi = 2f * 3.1415927f

/**
 * 思考游动的整体倍速：下方光斑的周期/明暗/呼吸都按 1× 书写，运行时统一乘以本值。
 * 8× 对应设计确认的动效节奏（约 1.75–3s 走完一条 Lissajous 路径）；想更慢就调小，
 * 只改这一处，所有光斑同步变化。
 */
private const val AuroraSpeed = 8f

/** 相位累计上限（1× 秒）：按 8× 速度约 30 小时归零一次，避免 Float 精度导致游动发涩。 */
private const val PhaseWrapSeconds = 864_000f

/** 思考时每颗光斑叠加的明暗起伏幅度：alpha = 基值 × (1 ± 0.20 × reveal)。 */
private const val PulseDepth = 0.20f

/** 思考时每颗光斑的半径呼吸幅度：半径 = 基值 × (1 ± 0.08 × reveal)。 */
private const val BreatheDepth = 0.08f

/**
 * 出现 / 退出时整片光场沿竖直方向的位移比例（以可见高度计）：0 = 设计稿原位，
 * 1 = 完全沉到输入栏后方。取 0.55——配合同步收放的 alpha 与随层移动的顶部遮罩，
 * 光斑看起来是从输入栏后方升上来 / 沉回去，而不是凭空显隐。
 */
private const val RevealSlideFraction = 0.55f

/**
 * [AiAuroraAnchor.Center] 的出现 / 退出位移比例：光场本就围绕内容居中，只需很轻的
 * 浮入（0.55 那种从输入栏后方升起的效果只在底部光带成立）。
 */
private const val CenterRevealSlideFraction = 0.15f

/**
 * 光场构图锚点：
 * - [Bottom]：贴可见区底边、顶部渐隐成一条底部光带——AI 对话浮层输入栏后方（默认，现状）；
 * - [Center]：以组件中线为基准向上下扩散、上下双向渐隐——AI 识别导入浮层图片背后的环绕光场。
 *
 * 两者共用同一套光斑游动 / 帧循环 / 离屏遮罩，只切换光斑基准位、遮罩曲线与出现位移，
 * 色值仍只取自 [ClassppColors.aiAuroraBlue] / [ClassppColors.aiAuroraCyan] /
 * [ClassppColors.aiAuroraMint] / [ClassppColors.aiAuroraLime]。
 */
enum class AiAuroraAnchor { Bottom, Center }

/**
 * AI「思考光场」：四颗软边径向渐变光斑（蓝 → 青 → 薄荷 → 黄绿）叠成设计稿的极光场。
 * 默认（[AiAuroraAnchor.Bottom]）为 AI 对话浮层底部光带、顶部用垂直 alpha 遮罩淡出到浮层底色；
 * [AiAuroraAnchor.Center] 为 AI 识别导入浮层图片背后的中部光场、上下双向渐隐。
 *
 * 只在 AI 思考 / 回答期间（[active] = true）出现：
 * - 进场（[Motion.AiAuroraEnterMillis]，[Motion.Decelerate]）：整片光场从输入栏后方
 *   向上升起并淡入，四颗光斑同时向外散开一段（thinkX/thinkY，观感上像光场「醒过来」）；
 * - 在场期间：光斑各自沿 Lissajous 路径游动（x/y 周期互质，避免机械的短循环；
 *   实际节奏 = 1× 周期 ÷ [AuroraSpeed]），alpha 错相起伏形成「蓝 → 青 → 绿 → 黄」的
 *   视觉重心交替；
 * - 退场（[Motion.AiAuroraExitMillis]，[Motion.Accelerate]）：光斑回落到基准位，
 *   整片光场下沉并淡出，相位冻结、不留跳变；
 * - 空闲（[active] = false 且退场播完）：完全不绘制，不再保留静态彩色底，底部只剩
 *   浮层自身的渐变遮罩。
 *
 * 实现约束：
 * - 不挂 `Modifier.blur`：径向渐变自带软边，避免 API 31+ 与整层离屏的开销；
 * - 只在 active 时跑 `withFrameNanos` 帧循环，退场与空闲不推进相位；系统关闭动画
 *   （`ValueAnimator.areAnimatorsEnabled()` / `ANIMATOR_DURATION_SCALE`）时直接
 *   呈现 / 收起，不做位移过渡；
 * - 绘制走 `graphicsLayer(compositingStrategy = Offscreen)` + `BlendMode.DstIn` 遮罩，
 *   只影响本组件这一条底部区域。
 *
 * [imeReservePx] 是键盘高出导航栏的预留高度：光斑的纵向基准、遮罩终点与出场位移都锚在
 * 可见区域底边（`height - imeReservePx`），键盘弹起时渐隐带不会被 IME 预留撑长。
 *
 * 玻璃采样：本组件不创建 Backdrop。宿主若要让输入栏玻璃透出光场颜色，需把本组件包在
 * 一个挂了 `layerBackdrop` 的节点里（采样层须在输入栏之外，避免读写同一层的反馈环），
 * 再把该 Backdrop 传给玻璃组件；玻璃关闭/API<31 时传 null 即可回退实心底。光场隐藏
 * （reveal = 0）时采样层只剩宿主底色，玻璃自然回退为素色。
 *
 * 色值只允许经 [ClassppColors.aiAuroraBlue] / [ClassppColors.aiAuroraCyan] /
 * [ClassppColors.aiAuroraMint] / [ClassppColors.aiAuroraLime] 取用；这是全 app 唯一的
 * 多色装饰性渐变（见《颜色使用规范》第 0.7 / 6.4 条），不得复制到其它背景。
 */
@Composable
fun AiThinkingAurora(
    active: Boolean,
    modifier: Modifier = Modifier,
    imeReservePx: Int = 0,
    // 光场构图锚点：默认底部光带（AI 对话浮层），Center = 围绕内容中线的环绕光场
    anchor: AiAuroraAnchor = AiAuroraAnchor.Bottom,
) {
    val palette = MaterialTheme.classppColors
    val blobs = remember(palette, anchor) { auroraBlobs(palette, anchor) }
    // 遮罩只需要 alpha：用 surface 的深浅色值承载透明度，避免在业务代码里写恒白色
    val maskColor = MaterialTheme.colorScheme.surface
    val context = LocalContext.current
    val animationsEnabled = remember(context) { areSystemAnimationsEnabled(context) }

    // 出现进度：0 = 完全沉在输入栏后方（不绘制），1 = 完整光场。纵向位移、alpha 与光斑
    // 散开幅度都乘它；首帧即按目标值起算——预览、以及「浮层重开时正在回答」直接呈现终态
    val reveal by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (animationsEnabled) {
            tween(
                durationMillis = if (active) {
                    Motion.AiAuroraEnterMillis
                } else {
                    Motion.AiAuroraExitMillis
                },
                easing = if (active) Motion.Decelerate else Motion.Accelerate,
            )
        } else {
            // 系统关闭动画：不做位移过渡，直接呈现 / 收起（思考构图仍保留散开 + 更亮）
            snap()
        },
        label = "aiAuroraReveal",
    )
    // 相位（秒）：只在 active 时推进；退场/空闲冻结，下次思考从冻结点继续
    val phase = remember { mutableFloatStateOf(0f) }
    // 出场位移比例：底部光带从输入栏后方升起，中部光带只做轻微浮入
    val slideFraction = if (anchor == AiAuroraAnchor.Center) {
        CenterRevealSlideFraction
    } else {
        RevealSlideFraction
    }
    // 遮罩曲线：Bottom = 顶部渐隐（底部保留实色光带）；Center = 上下双向渐隐（四周淡出）
    val maskStops = remember(anchor, maskColor) {
        when (anchor) {
            AiAuroraAnchor.Bottom -> arrayOf(
                0f to maskColor.copy(alpha = 0f),
                0.28f to maskColor.copy(alpha = 0.10f),
                0.62f to maskColor.copy(alpha = 0.62f),
                0.86f to maskColor.copy(alpha = 1f),
                1f to maskColor.copy(alpha = 1f),
            )
            AiAuroraAnchor.Center -> arrayOf(
                0f to maskColor.copy(alpha = 0f),
                0.16f to maskColor.copy(alpha = 0.18f),
                0.36f to maskColor.copy(alpha = 0.88f),
                0.52f to maskColor.copy(alpha = 1f),
                0.66f to maskColor.copy(alpha = 0.90f),
                0.84f to maskColor.copy(alpha = 0.22f),
                1f to maskColor.copy(alpha = 0f),
            )
        }
    }

    LaunchedEffect(active, animationsEnabled) {
        if (!active || !animationsEnabled) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                // 掉帧时最多推进 50ms：从后台切回来不会让光斑瞬移一截
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                val next = phase.floatValue + dt * AuroraSpeed
                phase.floatValue = if (next > PhaseWrapSeconds) next - PhaseWrapSeconds else next
            }
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                // 出现自下而上浮入、退出向下沉出：位移与 alpha 同走 reveal。位移按可见高度
                // （扣除 IME 预留）计，键盘弹起时滑动距离不变
                val visibleHeight = (size.height - imeReservePx).coerceAtLeast(0f)
                translationY = (1f - reveal) * visibleHeight * slideFraction
                alpha = reveal
                // DstIn 遮罩需要独立图层；只覆盖底部光场这一块，不触发整屏离屏
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawBehind {
                // 完全沉下去后不画任何像素：空闲时这一层零绘制
                if (reveal <= 0f) return@drawBehind
                // 可见底边：键盘弹起时 height 包含 IME 预留，基准与遮罩都要扣掉它
                val visibleHeight = (size.height - imeReservePx).coerceAtLeast(1f)
                val t = phase.floatValue

                blobs.forEach { blob ->
                    val center = Offset(
                        x = (blob.baseX + reveal * (blob.thinkX + blob.moveX *
                            sin(TwoPi * t / blob.periodX + blob.phase))) * size.width,
                        y = (blob.baseY + reveal * (blob.thinkY + blob.moveY *
                            cos(TwoPi * t / blob.periodY + blob.phase))) * visibleHeight,
                    )
                    val pulse = 1f + reveal * PulseDepth *
                        sin(TwoPi * t / blob.pulsePeriod + blob.pulsePhase)
                    val alpha = (blob.baseAlpha * pulse).coerceIn(0f, 1f)
                    val radius = (blob.radius * size.width *
                        (1f + reveal * BreatheDepth *
                            sin(TwoPi * t / blob.periodX + blob.phase + 1.1f)))
                        .coerceAtLeast(1f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to blob.color.copy(alpha = alpha),
                                0.5f to blob.color.copy(alpha = alpha * 0.86f),
                                0.8f to blob.color.copy(alpha = alpha * 0.34f),
                                1f to blob.color.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }

                // 竖向遮罩：Bottom 顶部淡出到浮层底色；Center 上下双向淡出成一条光带
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = maskStops,
                        startY = 0f,
                        endY = visibleHeight,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/** 四颗光斑的静态基准位、思考位与游动参数：位置/振幅用宽度或可见高度的比例，周期单位为秒（1×，运行时乘 [AuroraSpeed]）。 */
private data class AuroraBlob(
    val color: Color,
    val baseX: Float,
    val baseY: Float,
    val radius: Float,
    /** 思考时的整体偏移（乘 reveal）：让光场在进场 600ms 内明显「散开」，而不是只靠慢速摆动 */
    val thinkX: Float,
    val thinkY: Float,
    val moveX: Float,
    val moveY: Float,
    val periodX: Float,
    val periodY: Float,
    val phase: Float,
    val baseAlpha: Float,
    val pulsePeriod: Float,
    val pulsePhase: Float,
)

private fun auroraBlobs(colors: ClassppColors, anchor: AiAuroraAnchor): List<AuroraBlob> =
    when (anchor) {
        AiAuroraAnchor.Bottom -> bottomAuroraBlobs(colors)
        AiAuroraAnchor.Center -> centerAuroraBlobs(colors)
    }

/** 底部光带：光斑基准位压在可见区底边（baseY > 1），配合顶部渐隐成为输入栏后方的光场。 */
private fun bottomAuroraBlobs(colors: ClassppColors): List<AuroraBlob> = listOf(
    // 左：蓝，最饱和、略压出左缘，撑起设计图左下角的蓝
    AuroraBlob(
        color = colors.aiAuroraBlue,
        baseX = 0.00f,
        baseY = 1.05f,
        radius = 0.58f,
        thinkX = 0.18f,
        thinkY = -0.12f,
        moveX = 0.12f,
        moveY = 0.05f,
        periodX = 14f,
        periodY = 20f,
        phase = 0f,
        baseAlpha = 0.95f,
        pulsePeriod = 13f,
        pulsePhase = 0f,
    ),
    // 左中：青，衔接蓝与薄荷
    AuroraBlob(
        color = colors.aiAuroraCyan,
        baseX = 0.50f,
        baseY = 1.04f,
        radius = 0.30f,
        thinkX = -0.12f,
        thinkY = -0.10f,
        moveX = 0.14f,
        moveY = 0.06f,
        periodX = 18f,
        periodY = 15f,
        phase = 1.9f,
        baseAlpha = 0.90f,
        pulsePeriod = 16f,
        pulsePhase = 1.6f,
    ),
    // 右中：薄荷绿，与青/黄绿交叠出设计图的中段过渡
    AuroraBlob(
        color = colors.aiAuroraMint,
        baseX = 0.74f,
        baseY = 1.04f,
        radius = 0.40f,
        thinkX = 0.12f,
        thinkY = -0.10f,
        moveX = 0.14f,
        moveY = 0.05f,
        periodX = 22f,
        periodY = 17f,
        phase = 3.4f,
        baseAlpha = 0.95f,
        pulsePeriod = 11f,
        pulsePhase = 3.0f,
    ),
    // 右：黄绿，右侧最亮的一档
    AuroraBlob(
        color = colors.aiAuroraLime,
        baseX = 1.10f,
        baseY = 1.03f,
        radius = 0.38f,
        thinkX = -0.18f,
        thinkY = -0.12f,
        moveX = 0.12f,
        moveY = 0.05f,
        periodX = 24f,
        periodY = 19f,
        phase = 4.8f,
        baseAlpha = 0.85f,
        pulsePeriod = 17f,
        pulsePhase = 4.4f,
    ),
)

/**
 * 中部光带：光斑围绕组件中线（baseY ≈ 0.5）分布，上下遮罩对称渐隐，用于图片背后的
 * 环绕光场——图片本身盖住中心，四周留下蓝 → 青 → 薄荷 → 黄绿的呼吸光晕。
 */
private fun centerAuroraBlobs(colors: ClassppColors): List<AuroraBlob> = listOf(
    // 左：蓝，最饱和、略压出左缘
    AuroraBlob(
        color = colors.aiAuroraBlue,
        baseX = 0.02f,
        baseY = 0.52f,
        radius = 0.62f,
        thinkX = 0.10f,
        thinkY = -0.05f,
        moveX = 0.10f,
        moveY = 0.04f,
        periodX = 14f,
        periodY = 20f,
        phase = 0f,
        baseAlpha = 0.92f,
        pulsePeriod = 13f,
        pulsePhase = 0f,
    ),
    // 左中：青，衔接蓝与薄荷
    AuroraBlob(
        color = colors.aiAuroraCyan,
        baseX = 0.44f,
        baseY = 0.43f,
        radius = 0.34f,
        thinkX = -0.08f,
        thinkY = -0.04f,
        moveX = 0.12f,
        moveY = 0.05f,
        periodX = 18f,
        periodY = 15f,
        phase = 1.9f,
        baseAlpha = 0.88f,
        pulsePeriod = 16f,
        pulsePhase = 1.6f,
    ),
    // 右中：薄荷绿
    AuroraBlob(
        color = colors.aiAuroraMint,
        baseX = 0.70f,
        baseY = 0.56f,
        radius = 0.42f,
        thinkX = 0.08f,
        thinkY = 0.04f,
        moveX = 0.12f,
        moveY = 0.05f,
        periodX = 22f,
        periodY = 17f,
        phase = 3.4f,
        baseAlpha = 0.92f,
        pulsePeriod = 11f,
        pulsePhase = 3.0f,
    ),
    // 右：黄绿，右侧最亮的一档
    AuroraBlob(
        color = colors.aiAuroraLime,
        baseX = 1.02f,
        baseY = 0.43f,
        radius = 0.38f,
        thinkX = -0.10f,
        thinkY = -0.05f,
        moveX = 0.10f,
        moveY = 0.04f,
        periodX = 24f,
        periodY = 19f,
        phase = 4.8f,
        baseAlpha = 0.82f,
        pulsePeriod = 17f,
        pulsePhase = 4.4f,
    ),
)

/**
 * 系统是否允许动画：API 26+ 用 [ValueAnimator.areAnimatorsEnabled]（覆盖开发者选项里的
 * 「动画程序时长缩放」关闭）；API 24–25 回退读 `ANIMATOR_DURATION_SCALE`。
 * 关闭时只做瞬时的出现 / 收起，不启动帧循环。
 */
private fun areSystemAnimationsEnabled(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }

// ---------- 预览 ----------

/** 思考态（进场终态）：预览没有帧时钟，animateFloatAsState 直接以目标值呈现完整光场 */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 240)
@Composable
private fun AiThinkingAuroraThinkingPreview() = ClassppTheme {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        AiThinkingAurora(active = true, modifier = Modifier.fillMaxSize())
    }
}

/** 深色思考态：同色相压暗的深极光 */
@Preview(showBackground = true, backgroundColor = 0xFF0F1114, widthDp = 390, heightDp = 240)
@Composable
private fun AiThinkingAuroraDarkThinkingPreview() = ClassppTheme(darkTheme = true) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        AiThinkingAurora(active = true, modifier = Modifier.fillMaxSize())
    }
}
