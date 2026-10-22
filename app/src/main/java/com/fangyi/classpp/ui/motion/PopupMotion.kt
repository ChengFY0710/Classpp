package com.fangyi.classpp.ui.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import com.fangyi.classpp.ui.components.hazeCardStyle
import com.fangyi.classpp.ui.components.hazeSources
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * 退场尾段整卡淡出兜底覆盖的 scale 区间长度：scale 走完最后这段时整卡淡到全透明。
 * 正常退场的可见淡出由 [Motion.PopupCardFadeMillis] 尾窗承担，这里是与其取 min 的
 * 兜底下限——保证收起的任何一帧（含被竞态/残留拖住的末帧，无论动画值是否卡住）
 * 都不会显形成白卡。
 */
private const val PopupExitTailFadeScaleSpan = 0.05f

/**
 * 果冻回弹的峰值缩放：生长冲过 1 的最高点。[popupEnterJelly] 与毛玻璃快照的扩采边距
 * （[BlurSnapshotOverscan]）共用——峰值一变，快照需要补盖的差值随之自动推算。
 */
private const val PopupJellyPeakScale = 1.05f

/**
 * 毛玻璃快照四周的扩采边距（占卡面尺寸的比例）：快照经反向缩放恒等映射回卡面**静止
 * 位置**，果冻回弹（峰值 [PopupJellyPeakScale]）越过 1 时可见卡面会大于静止位置，超出
 * 部分没有模糊、露出卡壳自己的不透明底色。每边按 (峰值 − 1) × [BlurSnapshotOverscanFactor]
 * 扩采（默认 10%），系数 2 是为锚点在卡面外（如周数弹窗锚在胶囊中心）的最远边留的余量
 * （锚点外移不超过一个卡面尺寸时足够）；扩出卡面的部分平时由卡壳 clip 裁掉，回弹放大
 * 时恰好补上——「按峰值静态扩采、动画中动态显形」，无需逐帧改布局。
 */
private const val BlurSnapshotOverscanFactor = 2f

/** 见 [BlurSnapshotOverscanFactor]：每边扩采边距 = (峰值 − 1) × 该系数。 */
private val BlurSnapshotOverscan = (PopupJellyPeakScale - 1f) * BlurSnapshotOverscanFactor

/**
 * 进场 + 果冻回弹一体曲线：[startScale] → [PopupJellyPeakScale] → 1 一条 keyframes 走完
 * （600ms）。生长冲过 1 后顺势回缩定住，没有「先减速停住、再重新起拍」的 velocity 死点，
 * 两段端点（峰值 / 1.0）都落在振幅极值（速度为零），读起来是一条连续的阻尼振荡：
 * - 0 → 200ms 生长段（[Motion.Standard]）：起步逐渐加速上冲到峰值；
 * - 200 → 600ms 收定段（[Motion.Standard]）：回到 1 定住。
 * 锚点为生长角：整条曲线绕同一锚点，果冻回弹朝锚点反方向撑开，与「贴着锚点长出来」同构。
 */
private fun popupEnterJelly(startScale: Float): FiniteAnimationSpec<Float> = keyframes {
    durationMillis = 520
    startScale at 0
    PopupJellyPeakScale at 180 using Motion.Standard
    1f at 520 using Motion.Accelerate
}

/**
 * 弹层进出场动画的状态句柄（[rememberPopupMotion] 返回）：生长缩放 + 整卡首尾淡入淡出 +
 * 分层淡入 + 投影/毛玻璃联动进度，供宿主 graphicsLayer 与玻璃卡壳在 **draw 阶段** 消费
 * （零重组）。[shown] 是组合条件——宿主 `if (motion.shown) { Popup { … } }`。
 */
@Stable
class PopupMotionState internal constructor(
    private val animateExit: Boolean,
    /** 进场起始缩放（退场缩回同值），尾淡区间与投影渐入都按它推算 */
    val enterStartScale: Float,
    private val visibleState: MutableTransitionState<Boolean>,
    val scale: State<Float>,
    val contentAlpha: State<Float>,
    /** 整卡（含柔影留白）透明度：进场开头淡入、退场尾窗淡出，中段恒满强度（draw 阶段读取） */
    val cardAlpha: State<Float>,
) {
    /**
     * 是否应组合弹层：expanded（开）或退场动画未完（仅 [rememberPopupMotion] 的
     * [animateExit] = true 时）。收起即卸载（animateExit = false）时不为退场续命。
     */
    val shown: Boolean
        get() = visibleState.targetState || (animateExit && visibleState.currentState)

    /** 当前是否处于收起方向（退场尾段淡出只在该方向生效），draw 阶段读取 */
    internal val exiting: Boolean
        get() = !visibleState.targetState

    /** 毛玻璃子层反向缩放用的缩放值（与 [scale] 同源，draw 阶段调用） */
    fun blurScale(): Float = scale.value

    /** 投影渐入进度：scale 未到 1 前按进度渐入，越过 1（果冻回弹段）恒满强度，投影不随回缩呼吸 */
    fun blurProgress(): Float {
        val s = scale.value
        return if (s >= 1f) {
            1f
        } else {
            ((s - enterStartScale) / (1f - enterStartScale)).coerceIn(0f, 1f)
        }
    }
}

/**
 * 弹层进出场动画的共享实现（PopupMenuPopup / WeekPicker 等玻璃弹层共用）：
 * 进场 scale 走 [popupEnterJelly] 一体曲线（生长冲过 1 顺势回缩定住）+ 整卡开头淡入
 * （[Motion.PopupCardFadeMillis]）+ 内容淡入（[Motion.PopupFadeMillis]），退场 scale 与
 * 内容淡出共用 [Motion.PopupExitMillis] + Accelerate 同步收尾、尾窗整卡淡出；
 * [animateExit] = false 时收起不播退场、立即卸载。
 *
 * [enterStartScale] 是进场起始缩放（默认 0.8）：锚点离卡片很近的弹层用默认值即可；
 * 锚点要表达「从某个宿主控件里长出来」（如周数卡从胶囊中心涌出）时调小它（如 0.5f），
 * 起始卡面越小、从锚点涌现的读感越强。
 *
 * 退场播完经 [onExitFinished] 回调一次（宿主借机清理「仅退场期保留」的锚点状态；
 * 仅退场动画存在时才会触发）。transition 恒常驻组合——若随「是否显示」条件一起
 * 组装卸载，动画完成回调与自身卸载存在竞态，极端时序下 currentState 停在 true、
 * 弹层（毛玻璃壳）会卡在屏幕上。
 *
 * 卡壳侧的分层契约（白底 + 毛玻璃不淡入、只淡内容、模糊反向缩放对位）见
 * [PopupGlassCard]；宿主侧的缩放/尾淡/锚点用 [popupMotionScale] 挂到弹层内容上。
 */
@Composable
fun rememberPopupMotion(
    expanded: Boolean,
    animateExit: Boolean = true,
    onExitFinished: () -> Unit = {},
    enterStartScale: Float = Motion.PopupEnterStartScale,
): PopupMotionState {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded

    // 退场播完回调：曾进场且当前收起、动画静止时回调一次。独立于 shown 存活——
    // 退场结束后 shown 翻假、弹层已不组合，回调逻辑必须在它之外
    var hasOpened by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) hasOpened = true
    }
    LaunchedEffect(visibleState.currentState) {
        if (hasOpened && !expanded && visibleState.isIdle && !visibleState.currentState) {
            hasOpened = false
            onExitFinished()
        }
    }

    val transition = rememberTransition(visibleState, label = "PopupMotion")
    val scaleState = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                popupEnterJelly(enterStartScale)
            } else {
                tween(Motion.PopupExitMillis, easing = Motion.Accelerate)
            }
        },
        label = "PopupMotionScale",
    ) { visible -> if (visible) 1f else enterStartScale }
    val contentAlphaState = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(Motion.PopupFadeMillis, easing = Motion.Decelerate)
            } else {
                tween(Motion.PopupExitMillis, easing = Motion.Accelerate)
            }
        },
        label = "PopupMotionContentAlpha",
    ) { visible -> if (visible) 1f else 0f }
    // 整卡首尾淡入淡出（进场开头淡入 / 退场尾窗淡出，中段恒满强度，不给模糊
    // 「由弱变强」伪影留可见窗口；时长见 Motion.PopupCardFadeMillis）
    val cardAlphaState = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                // 进场开头整卡淡入，之后果冻回弹段恒满强度
                tween(Motion.PopupCardFadeMillis, easing = Motion.Decelerate)
            } else {
                // 退场先满强度走完前段收缩，尾窗整卡淡出收尾；不写 at-0 关键帧——
                // 进场中被反向收起时从当前 alpha 平滑出发，不闪跳
                keyframes {
                    durationMillis = Motion.PopupExitMillis
                    1f at (Motion.PopupExitMillis - Motion.PopupCardFadeMillis).coerceAtLeast(0) using Motion.Decelerate
                    0f at Motion.PopupExitMillis using Motion.Accelerate
                }
            }
        },
        label = "PopupMotionCardAlpha",
    ) { visible -> if (visible) 1f else 0f }

    return remember(visibleState, scaleState, contentAlphaState, cardAlphaState, animateExit, enterStartScale) {
        PopupMotionState(
            animateExit,
            enterStartScale,
            visibleState,
            scaleState,
            contentAlphaState,
            cardAlphaState,
        )
    }
}

/**
 * 弹层内容的缩放图层（[rememberPopupMotion] 的宿主侧消费端）：生长缩放 + 整卡首尾
 * 淡入淡出（退场尾段另取兜底下限）+ 生长锚点，全部在 draw 阶段读值、零重组。垫在
 * 投影留白 padding **之外**——整卡（含柔影）一起变换，锚点由 [origin] 按图层实测尺寸
 * （含留白）反推，未提供（首帧 size 未量出等）时沿用上一帧锚点。
 */
fun Modifier.popupMotionScale(
    motion: PopupMotionState,
    origin: (Size) -> TransformOrigin? = { null },
): Modifier = graphicsLayer {
    val scale = motion.scale.value
    scaleX = scale
    scaleY = scale
    // 整卡首尾淡入淡出（进场开头淡入 / 退场尾窗淡出，中段恒满强度）；退场再与尾段
    // 兜底取 min——scale 掉进尾区间时强制趋零，收起的任何一帧（含被竞态/残留拖住的
    // 末帧，无论卡在组合层、Popup 窗口还是 Haze 效果层）都不再可能显形成白卡
    alpha = if (!motion.exiting) {
        motion.cardAlpha.value
    } else {
        minOf(
            motion.cardAlpha.value,
            ((scale - motion.enterStartScale) / PopupExitTailFadeScaleSpan).coerceIn(0f, 1f),
        )
    }
    origin(size)?.let { transformOrigin = it }
}

/**
 * 玻璃弹层卡壳的共享配方（PopupMenuCard / WeekPicker 卡壳同款）：形状 clip + 投影
 * （[shadowElevation] × [blurProgress] 渐入）+ 不透明白底 + 白底之上叠 Haze 毛玻璃。
 *
 * 分层契约（弹出动画的前提）：
 * - 卡壳（白底 + 毛玻璃 + 投影）在本组件内不做任何透明度动画——整卡长时间半透明会把
 *   锐利背景混进毛玻璃，模糊看起来「由弱变强」。整卡只在进出场**首尾短窗**
 *   （[Motion.PopupCardFadeMillis]，中段恒满强度）由宿主缩放层（[popupMotionScale]）
 *   统一淡入淡出；组件内只淡 [contentAlpha]（内容）与 [blurProgress]（投影），
 *   均在 draw 阶段读值、零重组；
 * - 毛玻璃是独立子层，并按 [blurScale] 绕 [blurOrigin] **反向缩放**——外层生长缩放会把
 *   按最终位置画好的采样内容一起变换，动画中除锚点外处处与真实背景错位；反向缩放后
 *   两层相乘为恒等，毛玻璃全程 1:1 钉在真实背景上（模糊半径也不被放大），卡形状/白底/
 *   投影/内容照常随缩放生长，超出卡面的部分由壳 clip 兜住。快照的布局范围另按
 *   [BlurSnapshotOverscan] 四周扩采（[blurSnapshotOverscan]）：恒等映射使快照恒落在卡面
 *   静止位置，回弹越过 1 时卡面大于静止位置，扩采段补上差值、卡底色不从边缘漏出。
 *
 * [blurOrigin] 是反向缩放的锚点，**卡面坐标**下的比例点（卡面 = 本子层扣除四周扩采
 * 边距；本组件内部完成到扩采层坐标的换算）；锚点可在卡面外（负值/大于 1，如「从宿主
 * 胶囊中心长出」）。恒等变换要求它与宿主缩放图层（[popupMotionScale] 的 origin，
 * 图层坐标 = 卡面 + 四周留白）指向同一个物理点：两边锚点相差一圈 padding 平移。
 *
 * [propagateMinConstraints]：卡被外部 modifier 撑宽时内容至少跟着撑满（Surface 同款
 * 行为），玻璃模式菜单卡依赖它，换壳时不能丢。
 * [blurStyle] 毛玻璃样式（[hazeCardStyle] 组合期构建，tint 读主题），调用方单独覆盖
 * 半径/染色时不影响其他玻璃卡。
 */
@Composable
fun PopupGlassCard(
    shape: Shape,
    shadowElevation: Dp,
    spotShadowColor: Color,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    blurOrigin: (Size) -> TransformOrigin = { TransformOrigin(0f, 0f) },
    blurStyle: HazeBlurStyle = hazeCardStyle(),
    blurScale: () -> Float = { 1f },
    contentAlpha: () -> Float = { 1f },
    blurProgress: () -> Float = { 1f },
    propagateMinConstraints: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        propagateMinConstraints = propagateMinConstraints,
        modifier = modifier
            .graphicsLayer {
                this.shape = shape
                clip = true
                this.shadowElevation = shadowElevation.toPx() * blurProgress()
                this.spotShadowColor = spotShadowColor
            }
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (hazeState != null) {
            // 白卡毛玻璃（跨 Popup 窗口采样宿主页面 hazeSource）。独立子层 + 反向缩放
            // （理由见上方 KDoc）：锚点由 [blurOrigin] 给出，须与宿主缩放图层指向同一
            // 物理点。生长/果冻回弹都会短暂偏离 1，反向缩放对任意 s 成立，故恒生效。
            // [blurSnapshotOverscan] 把快照四周扩采一圈：回弹越过 1 时可见卡面大于快照
            // 静止位置，扩采段补上差值，卡壳自己的白底不从边缘漏出
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blurSnapshotOverscan()
                    .graphicsLayer {
                        val s = blurScale().coerceAtLeast(0.1f)
                        scaleX = 1f / s
                        scaleY = 1f / s
                        // blurOrigin 给的是卡面坐标的比例点；本层已含四周扩边
                        // （每边占卡面 [BlurSnapshotOverscan]），换算到扩边层坐标：
                        // (f + c) / (1 + 2c)——物理锚点不变，恒等变换不被破坏
                        val inv = 1f + 2f * BlurSnapshotOverscan
                        val origin = blurOrigin(Size(size.width / inv, size.height / inv))
                        transformOrigin = TransformOrigin(
                            (origin.pivotFractionX + BlurSnapshotOverscan) / inv,
                            (origin.pivotFractionY + BlurSnapshotOverscan) / inv,
                        )
                    }
                    .hazeBlur(
                        input = hazeSources(hazeState),
                        style = blurStyle,
                        performanceMode = HazePerformanceMode.Quality,
                    ),
            )
        }
        // 只淡内容：白底 + 毛玻璃壳在本组件内恒为满 alpha（整卡首尾淡入淡出由宿主缩放层统一做）
        Box(
            propagateMinConstraints = propagateMinConstraints,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha() },
            content = content,
        )
    }
}

/**
 * 毛玻璃快照的扩采布局（[PopupGlassCard] 模糊子层专用，**接在 matchParentSize 之后**）：
 * matchParentSize 拿到卡壳量出的精确卡面约束后，这里把内容外扩 [BlurSnapshotOverscan]
 * 一圈量测、自身仍报告卡面尺寸——不改变卡壳的实际尺寸（matchParentSize 子层本就不参与
 * 卡壳定尺寸）。外扩段平时由卡壳形状 clip 裁掉，回弹放大（卡面越过静止位置）时显形补盖。
 */
private fun Modifier.blurSnapshotOverscan(): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight || width <= 0 || height <= 0) {
        val placeable = measurable.measure(constraints)
        layout(width.coerceAtLeast(0), height.coerceAtLeast(0)) { placeable.placeRelative(0, 0) }
    } else {
        val padWidth = (width * BlurSnapshotOverscan).roundToInt()
        val padHeight = (height * BlurSnapshotOverscan).roundToInt()
        val placeable = measurable.measure(
            Constraints.fixed(width + padWidth * 2, height + padHeight * 2),
        )
        layout(width, height) { placeable.placeRelative(-padWidth, -padHeight) }
    }
}
