package com.fangyi.classpp.ui.ai

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/** 思考时每颗光斑叠加的明暗起伏幅度：alpha = 基值 × (1 ± 0.20 × motion)。 */
private const val PulseDepth = 0.20f

/** 思考时每颗光斑的半径呼吸幅度：半径 = 基值 × (1 ± 0.08 × motion)。 */
private const val BreatheDepth = 0.08f

/**
 * AI 对话浮层底部「思考光场」：四颗软边径向渐变光斑（蓝 → 青 → 薄荷 → 黄绿）叠成
 * 设计稿的极光场，顶部用垂直 alpha 遮罩淡出到浮层底色。
 *
 * 状态：
 * - [active] = false（空闲）：光斑停在基准位置、基准 alpha，即设计稿的静态彩色光场；
 * - [active] = true（AI 发送/思考）：[motion] 用 600ms 渐入，四颗光斑先向外散开一段
 *   （thinkX/thinkY，观感上像光场「醒过来」），再各自沿 Lissajous 路径游动
 *   （x/y 周期互质，避免机械的短循环；实际节奏 = 1× 周期 ÷ [AuroraSpeed]），
 *   alpha 错相起伏形成「蓝 → 青 → 绿 → 黄」的视觉重心交替；停止时 900ms 回落到
 *   设计稿的静态基准位，相位冻结、不留跳变。
 *
 * 实现约束：
 * - 不挂 `Modifier.blur`：径向渐变自带软边，避免 API 31+ 与整层离屏的开销；
 * - 只在 active 时跑 `withFrameNanos` 帧循环，空闲零重绘；系统关闭动画
 *   （`ValueAnimator.areAnimatorsEnabled()` / `ANIMATOR_DURATION_SCALE`）时直接静态；
 * - 绘制走 `graphicsLayer(compositingStrategy = Offscreen)` + `BlendMode.DstIn` 遮罩，
 *   只影响本组件这一条底部区域。
 *
 * [imeReservePx] 是键盘高出导航栏的预留高度：光斑的纵向基准与遮罩终点都锚在可见区域
 * 底边（`height - imeReservePx`），键盘弹起时渐隐带不会被 IME 预留撑长。
 *
 * 玻璃采样：本组件不创建 Backdrop。宿主若要让输入栏玻璃透出光场颜色，需把本组件包在
 * 一个挂了 `layerBackdrop` 的节点里（采样层须在输入栏之外，避免读写同一层的反馈环），
 * 再把该 Backdrop 传给玻璃组件；玻璃关闭/API<31 时传 null 即可回退实心底。
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
) {
    val palette = MaterialTheme.classppColors
    val blobs = remember(palette) { auroraBlobs(palette) }
    // 遮罩只需要 alpha：用 surface 的深浅色值承载透明度，避免在业务代码里写恒白色
    val maskColor = MaterialTheme.colorScheme.surface
    val context = LocalContext.current
    val animationsEnabled = remember(context) { areSystemAnimationsEnabled(context) }

    // 游动强度：0 = 静态基准位，1 = 完整游动。位移/明暗/呼吸都乘它，停下来自然归位
    val motion = remember { Animatable(0f) }
    // 相位（秒）：只在 active 时推进；空闲冻结，再次思考从冻结点继续
    val phase = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(active, animationsEnabled) {
        val target = if (active) 1f else 0f
        if (!animationsEnabled) {
            // 系统关闭动画：不做过渡，但仍直接呈现「思考态」构图（散开 + 更亮），
            // 让用户至少能看出 AI 在工作；连续游动按无障碍规范保持静止。
            motion.snapTo(target)
            return@LaunchedEffect
        }
        motion.animateTo(
            targetValue = target,
            animationSpec = tween(
                durationMillis = if (active) {
                    Motion.AiAuroraWakeMillis
                } else {
                    Motion.AiAuroraSettleMillis
                },
                easing = if (active) Motion.Decelerate else Motion.Accelerate,
            ),
        )
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
            // DstIn 遮罩需要独立图层；只覆盖底部光场这一块，不触发整屏离屏
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                // 可见底边：键盘弹起时 height 包含 IME 预留，基准与遮罩都要扣掉它
                val visibleHeight = (size.height - imeReservePx).coerceAtLeast(1f)
                val motionAmount = motion.value
                val t = phase.floatValue

                blobs.forEach { blob ->
                    val center = Offset(
                        x = (blob.baseX + motionAmount * (blob.thinkX + blob.moveX *
                            sin(TwoPi * t / blob.periodX + blob.phase))) * size.width,
                        y = (blob.baseY + motionAmount * (blob.thinkY + blob.moveY *
                            cos(TwoPi * t / blob.periodY + blob.phase))) * visibleHeight,
                    )
                    val pulse = 1f + motionAmount * PulseDepth *
                        sin(TwoPi * t / blob.pulsePeriod + blob.pulsePhase)
                    val alpha = (blob.baseAlpha * pulse).coerceIn(0f, 1f)
                    val radius = (blob.radius * size.width *
                        (1f + motionAmount * BreatheDepth *
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

                // 顶部淡出：0 → 28% 几乎透明，62% 后逐渐接近实色，86% 起完全保留光场
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to maskColor.copy(alpha = 0f),
                            0.28f to maskColor.copy(alpha = 0.10f),
                            0.62f to maskColor.copy(alpha = 0.62f),
                            0.86f to maskColor.copy(alpha = 1f),
                            1f to maskColor.copy(alpha = 1f),
                        ),
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
    /** 思考时的整体偏移（乘 motion）：让光场在唤醒的 600ms 内明显「散开」，而不是只靠慢速摆动 */
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

private fun auroraBlobs(colors: ClassppColors): List<AuroraBlob> = listOf(
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
 * 系统是否允许动画：API 26+ 用 [ValueAnimator.areAnimatorsEnabled]（覆盖开发者选项里的
 * 「动画程序时长缩放」关闭）；API 24–25 回退读 `ANIMATOR_DURATION_SCALE`。
 * 关闭时只保留静态光场，不启动帧循环。
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

/** 设计稿静态态：四颗光斑停在基准位，即空闲时的光场 */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 240)
@Composable
private fun AiThinkingAuroraIdlePreview() = ClassppTheme {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        AiThinkingAurora(active = false, modifier = Modifier.fillMaxSize())
    }
}

/** 深色静态态：同色相压暗的深极光 */
@Preview(showBackground = true, backgroundColor = 0xFF0F1114, widthDp = 390, heightDp = 240)
@Composable
private fun AiThinkingAuroraDarkPreview() = ClassppTheme(darkTheme = true) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        AiThinkingAurora(active = false, modifier = Modifier.fillMaxSize())
    }
}
