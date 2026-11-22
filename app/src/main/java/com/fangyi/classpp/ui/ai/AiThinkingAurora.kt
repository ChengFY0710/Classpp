package com.fangyi.classpp.ui.ai

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.rememberSystemAnimationsEnabled
import com.fangyi.classpp.ui.theme.AiAuroraPalette
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.classppColors
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** 2π：光斑路径与明暗起伏都以「秒」为周期单位，乘以它换算成弧度。 */
private const val TwoPi = 2f * 3.1415927f

/**
 * 游动的整体倍速：光斑周期/明暗/呼吸都按 1× 书写，运行时统一乘以本值。
 * 8× 对应现有动效节奏（Center 导入光场约 1.75–3s 走完一条 Lissajous 路径）；
 * Full 整卡光场的周期按 30–40 书写，实际约 3.75–5s，配合更大的振幅持续交替位置。
 */
private const val AuroraSpeed = 8f

/** 相位累计上限（1× 秒）：按 8× 速度约 30 小时归零一次，避免 Float 精度导致游动发涩。 */
private const val PhaseWrapSeconds = 864_000f

/** Center 模式每颗光斑叠加的明暗起伏幅度：alpha = 基值 × (1 ± 0.20 × reveal)。 */
private const val PulseDepth = 0.20f

/** Center 模式每颗光斑的半径呼吸幅度：半径 = 基值 × (1 ± 0.08 × reveal)。 */
private const val BreatheDepth = 0.08f

/**
 * Full 整卡光场的明暗 / 呼吸幅度：面积大、幅度减半，整片只做不易察觉的呼吸，
 * 避免整屏明暗起伏抢走阅读注意力。
 */
private const val FullPulseDepth = 0.10f
private const val FullBreatheDepth = 0.03f

/**
 * Full 整卡光场的公共漂移：四颗光斑共享同一个缓慢位移，整片光球作为一个整体
 * 左右/上下游走；再叠加各自幅度更大的 Lissajous，形成「位置不断交替」的灵动感。
 * 比例以卡宽/卡高计，周期单位为 1× 秒（运行时除以 [AuroraSpeed]）。
 */
private const val SwayXAmplitude = 0.05f
private const val SwayYAmplitude = 0.035f
private const val SwayXPeriod = 44f
private const val SwayYPeriod = 52f
private const val SwayXPhase = 0.6f
private const val SwayYPhase = 1.2f

/**
 * Full 顶部渐隐的起止位置（0 = 卡片顶，1 = 卡片底）：在 [FullMaskStart] 之前完全
 * 透明，之后按 smoothstep 平滑升到实色。
 *
 * 这里不用少量断点的分段线性渐变：大面积浅色光场上，断点处斜率突变会被眼睛读成
 * 横向亮带（Mach band）。改成多段采样的 smoothstep，曲线两端斜率也为零，
 * 整片上下过渡看不出硬边。
 */
private const val FullMaskStart = 0.08f
private const val FullMaskEnd = 0.60f
private const val FullMaskStepCount = 24

/** 采样 smoothstep 得到 Full 的竖向 alpha 遮罩：多段线性逼近平滑曲线，不产生横向亮带。 */
private fun fullMaskStops(maskColor: Color): Array<Pair<Float, Color>> =
    Array(FullMaskStepCount + 1) { index ->
        val y = index / FullMaskStepCount.toFloat()
        val t = ((y - FullMaskStart) / (FullMaskEnd - FullMaskStart)).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        y to maskColor.copy(alpha = eased)
    }

/**
 * Center 的径向渐变中间 / 外圈 alpha 比例：保持导入浮层原有的光晕边缘。
 * Full 不在这里定义——它在 draw 里改用多段采样的平滑曲线，见 [FalloffFactors]。
 */
private const val CenterFalloffMid = 0.86f
private const val CenterFalloffOuter = 0.34f

/**
 * Full 径向衰减的采样段数：把平滑曲线采成多段线性，避开 0.5 / 0.8 两个断点的
 * 斜率突变在大半径光斑上形成的环状 Mach 带（观感像横向亮线）。
 */
private const val FalloffStepCount = 16

/**
 * Full 的径向 alpha 曲线：`1 + 2q⁶ − 3q⁴`（q = 距圆心 / 半径）。
 * q=0 → 1，q=0.5 ≈ 0.844，q=0.8 ≈ 0.295，q=1 → 0；两端斜率为零、全程无断点。
 */
private val FalloffFractions: FloatArray =
    FloatArray(FalloffStepCount + 1) { it / FalloffStepCount.toFloat() }

private val FalloffFactors: FloatArray =
    FloatArray(FalloffStepCount + 1) { index ->
        val q = FalloffFractions[index]
        val q2 = q * q
        val q4 = q2 * q2
        val q6 = q4 * q2
        1f + 2f * q6 - 3f * q4
    }

/**
 * [AiAuroraAnchor.Full] 的出现 / 退出纵向位移比例（以卡片高度计）：整片光场只轻微
 * 上浮 12% 后落位，不再像底部光带那样从输入栏后方整段升起。
 */
private const val FullRevealSlideFraction = 0.12f

/**
 * [AiAuroraAnchor.Center] 的出现 / 退出位移比例：光场本就围绕内容居中，只需很轻的
 * 浮入（0.15 那种从输入栏后方升起的效果只在底部光带成立）。
 */
private const val CenterRevealSlideFraction = 0.15f

/**
 * 整片配色轮换节奏（真实秒）：每组配色停留 [PaletteHoldSeconds]，再用
 * [PaletteBlendSeconds] 与下一组交叉淡化。phase 是 1× 秒的 [AuroraSpeed] 倍，
 * 绘制时除以 [AuroraSpeed] 换回真实秒；当前每组 2.5s 驻留 + 4s 长过渡，
 * 让「正在变色」的过程成为主要观感。
 */
private const val PaletteHoldSeconds = 2.5f
private const val PaletteBlendSeconds = 4f

/**
 * 光场构图锚点：
 * - [Full]：铺满整张浮层卡片、主体落在下方约 2/3，顶部渐隐——AI 对话浮层的思考背景
 *   （默认）；四组配色整片交叉轮换；
 * - [Center]：以组件中线为基准向上下扩散、上下双向渐隐——AI 识别导入浮层图片背后的
 *   环绕光场；固定取第一组蓝绿配色，沿用原构图与游动参数。
 */
enum class AiAuroraAnchor { Full, Center }

/**
 * AI「思考光场」：四颗软边径向渐变光斑叠成设计稿的弥散光场。
 *
 * 两种构图：
 * - [AiAuroraAnchor.Full]（默认，AI 对话浮层）：铺满整张浮层卡片、主体落在下方约 2/3，
 *   顶部用垂直 alpha 遮罩淡出到浮层底色；四颗光斑半径约 0.88–1.18 倍卡宽，相位错开、
 *   叠加整片公共漂移，位置持续交替，观感是一整片缓慢呼吸又不断换位的弥散光球；
 * - [AiAuroraAnchor.Center]（AI 识别导入浮层）：以组件中线为基准上下双向渐隐，固定
 *   蓝绿配色，颜色与构图与旧版逐位一致。
 *
 * 只在 AI 思考 / 回答期间（[active] = true）出现：
 * - 进场（[Motion.AiAuroraEnterMillis]，[Motion.Decelerate]）：整片光场轻微上浮并淡入，
 *   四颗光斑同时向外散开一段（thinkX/thinkY，观感上像光场「醒过来」）；
 * - 在场期间：光斑各自沿 Lissajous 路径游动（x/y 周期互质，避免机械的短循环；
 *   实际节奏 = 1× 周期 ÷ [AuroraSpeed]）；Full 模式另外按「蓝绿 → 蓝紫 → 红粉 →
 *   橙黄」整片交叉轮换配色（每组停留 2.5s、4s 长过渡），alpha 错相起伏形成视觉
 *   重心交替；
 * - 退场（[Motion.AiAuroraExitMillis]，[Motion.Accelerate]）：光斑回落到基准位，
 *   整片光场下沉并淡出，相位冻结、不留跳变；退场播完卸载本层，空闲不保留全屏
 *   离屏图层；
 * - 空闲（[active] = false 且退场播完）：完全不绘制，不保留静态彩色底。
 *
 * 实现约束：
 * - 不挂 `Modifier.blur`：径向渐变自带软边，避免 API 31+ 与整层离屏的开销；
 * - 只在 active 时跑 `withFrameNanos` 帧循环，退场与空闲不推进相位；系统关闭动画
 *   （`rememberSystemAnimationsEnabled`，与对话气泡落位共用同一处判定）时直接
 *   呈现 / 收起，不做位移过渡；
 * - 绘制走 `graphicsLayer(compositingStrategy = Offscreen)` + `BlendMode.DstIn` 遮罩，
 *   只影响本组件这一层；
 * - 色值只允许经 `MaterialTheme.classppColors.aiAuroraPalettes` 取用（四组浅/深成对
 *   配色）；这是全 app 唯一的多色装饰性渐变（见《颜色使用规范》第 0.7 / 6.4 条），
 *   不得复制到其它背景。
 *
 * 玻璃采样：本组件只画光斑、四周全透明。宿主把本组件包在挂了 `layerBackdrop` 的
 * 节点里（采样层须在玻璃组件之外，避免读写同一层的反馈环），再与内容列采样层经
 * `rememberCombinedBackdrop` 叠起来交给玻璃组件——玻璃后同时透出内容与光球颜色。
 * 玻璃关闭/API<31 时传 null 即可回退实心底；光场隐藏（reveal = 0）时该层零绘制。
 */
@Composable
fun AiThinkingAurora(
    active: Boolean,
    modifier: Modifier = Modifier,
    // 光场构图锚点：默认整卡弥散光场（AI 对话浮层），Center = 围绕内容中线的环绕光场
    anchor: AiAuroraAnchor = AiAuroraAnchor.Full,
) {
    val palettes = MaterialTheme.classppColors.aiAuroraPalettes
    val blobs = remember(anchor) { auroraBlobs(anchor) }
    // 遮罩只需要 alpha：用 surface 的深浅色值承载透明度，避免在业务代码里写恒白色
    val maskColor = MaterialTheme.colorScheme.surface
    val animationsEnabled = rememberSystemAnimationsEnabled()

    // 挂载门控：Full 是整卡离屏图层，空闲时若一直挂在组合里会保留一整屏 buffer。
    // active 时立即挂载；退场动画播完（系统关闭动画时立即）再卸载，空闲零图层。
    var mounted by remember { mutableStateOf(active) }
    LaunchedEffect(active, animationsEnabled) {
        if (active) {
            mounted = true
        } else if (mounted) {
            if (animationsEnabled) delay(Motion.AiAuroraExitMillis.toLong())
            mounted = false
        }
    }

    // 出现进度：0 = 完全隐藏（不绘制），1 = 完整光场。纵向位移与光斑散开幅度都乘它；
    // 首帧即按目标值起算——预览、以及「浮层重开时正在回答」直接呈现终态
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
    // 出场位移比例：整卡光场只轻微上浮，中部光带保持原浮入幅度
    val slideFraction = when (anchor) {
        AiAuroraAnchor.Full -> FullRevealSlideFraction
        AiAuroraAnchor.Center -> CenterRevealSlideFraction
    }
    // 明暗/呼吸幅度：整卡光场减半，避免整屏起伏
    val pulseDepth = when (anchor) {
        AiAuroraAnchor.Full -> FullPulseDepth
        AiAuroraAnchor.Center -> PulseDepth
    }
    val breatheDepth = when (anchor) {
        AiAuroraAnchor.Full -> FullBreatheDepth
        AiAuroraAnchor.Center -> BreatheDepth
    }
    // Center 固定取第一组蓝绿配色；Full 在 draw 里按相位取相邻两组插值
    val fixedPalette = palettes[0]
    // 遮罩曲线：Full = 顶部渐隐、主体压在下方约 2/3；Center = 上下双向渐隐
    val maskStops = remember(anchor, maskColor) {
        when (anchor) {
            AiAuroraAnchor.Full -> fullMaskStops(maskColor)
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

    if (!mounted) return

    Box(
        modifier = modifier
            .graphicsLayer {
                // 出现自下而上浮入、退出下沉淡出：位移与 alpha 同走 reveal
                translationY = (1f - reveal) * size.height * slideFraction
                alpha = reveal
                // DstIn 遮罩需要独立图层；Full 为整卡背景，只在思考期间挂载
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawBehind {
                // 完全隐藏后不画任何像素：退场末帧与空闲时该层零绘制
                if (reveal <= 0f) return@drawBehind
                val visibleHeight = size.height.coerceAtLeast(1f)
                val t = phase.floatValue
                // 整片配色轮换：phase / AuroraSpeed = 真实秒；每组停留 + 交叉淡化循环。
                // palettes 顺序固定为蓝绿 → 蓝紫 → 红粉 → 橙黄（见 Theme.kt 装配）
                val paletteCount = palettes.size
                val cycleSeconds = PaletteHoldSeconds + PaletteBlendSeconds
                val palettePosition = (t / AuroraSpeed) % (cycleSeconds * paletteCount)
                val paletteIndex = (palettePosition / cycleSeconds).toInt()
                    .coerceIn(0, paletteCount - 1)
                val withinPalette = palettePosition - paletteIndex * cycleSeconds
                val blendRaw = ((withinPalette - PaletteHoldSeconds) / PaletteBlendSeconds)
                    .coerceIn(0f, 1f)
                // smoothstep：两端速度为零，整片换色不会出现起止顿挫
                val blend = blendRaw * blendRaw * (3f - 2f * blendRaw)
                val fromPalette = palettes[paletteIndex]
                val toPalette = palettes[(paletteIndex + 1) % paletteCount]
                val isFull = anchor == AiAuroraAnchor.Full
                // 整片公共漂移：让光球整体在卡内交替游走；Center 保持原构图
                val swayX = if (isFull) {
                    SwayXAmplitude * sin(TwoPi * t / SwayXPeriod + SwayXPhase)
                } else {
                    0f
                }
                val swayY = if (isFull) {
                    SwayYAmplitude * cos(TwoPi * t / SwayYPeriod + SwayYPhase)
                } else {
                    0f
                }

                blobs.forEach { blob ->
                    val center = Offset(
                        x = (blob.baseX + reveal * (swayX + blob.thinkX + blob.moveX *
                            sin(TwoPi * t / blob.periodX + blob.phase))) * size.width,
                        y = (blob.baseY + reveal * (swayY + blob.thinkY + blob.moveY *
                            cos(TwoPi * t / blob.periodY + blob.phase))) * visibleHeight,
                    )
                    val color = if (isFull) {
                        lerp(fromPalette[blob.colorIndex], toPalette[blob.colorIndex], blend)
                    } else {
                        fixedPalette[blob.colorIndex]
                    }
                    val pulse = 1f + reveal * pulseDepth *
                        sin(TwoPi * t / blob.pulsePeriod + blob.pulsePhase)
                    val alpha = (blob.baseAlpha * pulse).coerceIn(0f, 1f)
                    val radius = (blob.radius * size.width *
                        (1f + reveal * breatheDepth *
                            sin(TwoPi * t / blob.periodX + blob.phase + 1.1f)))
                        .coerceAtLeast(1f)
                    // Full 用多段平滑衰减，避免大半径光斑上出现环状/横向亮带；
                    // Center 保持原来的 4 段线性衰减
                    val colorStops = if (isFull) {
                        Array(FalloffFactors.size) { index ->
                            FalloffFractions[index] to
                                color.copy(alpha = alpha * FalloffFactors[index])
                        }
                    } else {
                        arrayOf(
                            0f to color.copy(alpha = alpha),
                            0.5f to color.copy(alpha = alpha * CenterFalloffMid),
                            0.8f to color.copy(alpha = alpha * CenterFalloffOuter),
                            1f to color.copy(alpha = 0f),
                        )
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = colorStops,
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }

                // 竖向遮罩：Full 顶部淡出到浮层底色；Center 上下双向淡出成一条光带
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

/**
 * 四颗光斑的静态基准位、思考位与游动参数：位置/振幅用宽度或可见高度的比例，
 * 周期单位为秒（1×，运行时乘 [AuroraSpeed]）。[colorIndex] 指向当前配色的
 * 第几色（0 = leading … 3 = trailing），配色本身由 [AiAuroraPalette] 提供。
 */
private data class AuroraBlob(
    val colorIndex: Int,
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

private fun auroraBlobs(anchor: AiAuroraAnchor): List<AuroraBlob> =
    when (anchor) {
        AiAuroraAnchor.Full -> fullAuroraBlobs()
        AiAuroraAnchor.Center -> centerAuroraBlobs()
    }

/**
 * 整卡弥散光场：四颗超大光斑铺满卡片，主体落在下方约 2/3——左下蓝、左中青、
 * 右中青绿、右上浅水绿。半径 0.88–1.18 倍卡宽，游走振幅较大、周期约 3.75–5s，
 * 四颗相位错开（0 / π/2 / π / 3π/2）并叠加整片公共漂移，颜色位置会不断交替；
 * 合起来读作一整片缓慢漂移的光球，而不是四颗各自晃动的光点。
 */
private fun fullAuroraBlobs(): List<AuroraBlob> = listOf(
    // 左下：主蓝光，半径越过半屏，撑起设计图底部的蓝色
    AuroraBlob(
        colorIndex = 0,
        baseX = 0.16f,
        baseY = 0.94f,
        radius = 1.18f,
        thinkX = 0.02f,
        thinkY = 0.02f,
        moveX = 0.13f,
        moveY = 0.07f,
        periodX = 30f,
        periodY = 38f,
        phase = 0f,
        baseAlpha = 0.88f,
        pulsePeriod = 84f,
        pulsePhase = 0f,
    ),
    // 左中：青，衔接左下蓝与中段薄荷
    AuroraBlob(
        colorIndex = 1,
        baseX = 0.32f,
        baseY = 0.50f,
        radius = 0.94f,
        thinkX = -0.02f,
        thinkY = 0.01f,
        moveX = 0.11f,
        moveY = 0.09f,
        periodX = 36f,
        periodY = 30f,
        phase = 1.57f,
        baseAlpha = 0.72f,
        pulsePeriod = 92f,
        pulsePhase = 1.7f,
    ),
    // 右中：青绿，与青/浅水绿交叠出中段的过渡
    AuroraBlob(
        colorIndex = 2,
        baseX = 0.72f,
        baseY = 0.72f,
        radius = 0.92f,
        thinkX = 0.02f,
        thinkY = -0.01f,
        moveX = 0.12f,
        moveY = 0.07f,
        periodX = 32f,
        periodY = 40f,
        phase = 3.14f,
        baseAlpha = 0.70f,
        pulsePeriod = 76f,
        pulsePhase = 3.2f,
    ),
    // 右上：浅水绿，右侧偏上的冷色收尾
    AuroraBlob(
        colorIndex = 3,
        baseX = 0.96f,
        baseY = 0.30f,
        radius = 0.88f,
        thinkX = -0.02f,
        thinkY = -0.02f,
        moveX = 0.10f,
        moveY = 0.08f,
        periodX = 40f,
        periodY = 34f,
        phase = 4.71f,
        baseAlpha = 0.64f,
        pulsePeriod = 88f,
        pulsePhase = 4.6f,
    ),
)

/**
 * 中部光带：光斑围绕组件中线（baseY ≈ 0.5）分布，上下遮罩对称渐隐，用于图片背后的
 * 环绕光场——图片本身盖住中心，四周留下蓝 → 青 → 薄荷 → 黄绿的呼吸光晕。
 */
private fun centerAuroraBlobs(): List<AuroraBlob> = listOf(
    // 左：蓝，最饱和、略压出左缘
    AuroraBlob(
        colorIndex = 0,
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
        colorIndex = 1,
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
        colorIndex = 2,
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
        colorIndex = 3,
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

// ---------- 预览 ----------

/** 整卡光场（进场终态）：预览没有帧时钟，animateFloatAsState 直接以目标值呈现完整光场 */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 760)
@Composable
private fun AiThinkingAuroraFullPreview() = ClassppTheme {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AiThinkingAurora(active = true, modifier = Modifier.fillMaxSize())
    }
}

/** 深色整卡光场：深色板为同色相压暗的深极光 */
@Preview(showBackground = true, backgroundColor = 0xFF0F1114, widthDp = 390, heightDp = 760)
@Composable
private fun AiThinkingAuroraDarkFullPreview() = ClassppTheme(darkTheme = true) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AiThinkingAurora(active = true, modifier = Modifier.fillMaxSize())
    }
}

/** 中部环绕光场（导入浮层）：固定蓝绿配色，构图与旧版一致 */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 240)
@Composable
private fun AiThinkingAuroraCenterPreview() = ClassppTheme {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        AiThinkingAurora(
            active = true,
            anchor = AiAuroraAnchor.Center,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
