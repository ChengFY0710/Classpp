// 来源：Cresto（Apache License 2.0）glasense-ui 模块 core/interaction/overscroll，
// 经单文件整理后移植（原实现拆为 5 个文件，此处合并）。按 Apache 2.0 保留本声明。
//
// iOS 风格橡皮筋 overscroll：滚动到边缘后继续拖动时——
// 1. 整块滚动内容作为一张图层被「拖出」边缘（平移，不是系统的 stretch 拉伸变形）；
// 2. 拖动距离越远增益越小——「越拉越硬」的阻尼手感（指数饱和曲线，位移渐近上限 = 容器尺寸），
//    永不无限拉出；
// 3. 松手后以临界阻尼弹簧（无过冲）软着陆回原位；
// 4. 在边缘直接甩动（fling）时，拉出量与甩动速度成正比——甩得越狠，拖出越多；
// 5. 回弹过程中反向拖动，手指立刻接管内容，同时把残余速度交还给列表继续滚动，衔接无跳变。
//
// 接入方式：在需要生效的作用域根部用 [ProvideOverscroll] 包一层，作用域内所有可滚动容器
// （verticalScroll / LazyColumn 等）自动生效（经 LocalOverscrollFactory 逐容器创建独立
// 效果实例，首次手势按 delta 主分量锁定单轴）。注意内容平移会把边缘外区域露出来——
// 滚动容器背后需要是可接受的底色（同色背景即可，边缘处看不出破绽）。
package com.fangyi.classpp.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.RulerScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.fastRoundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

// ---------------------------------------------------------------
// 1. 指数饱和阻尼曲线：p 越大增益越小，位移渐近收敛（f(p) = sign(p)·(1 − e^(−|p|·k))）
// ---------------------------------------------------------------

object ProgressConverter {
    fun convert(progress: Float, fraction: Float): Float {
        return (1f - exp(-abs(progress * fraction))) * progress.sign
    }
}

// ---------------------------------------------------------------
// 2. 零开销布局辅助：手写 MeasureResult，复用测量结果，
//    在 placeWithLayer 的 layer block 中应用 translation
// ---------------------------------------------------------------

private val NoOpLayerBlock: GraphicsLayerScope.() -> Unit = {}

private class SimplePlacementScope(
    override val parentWidth: Int,
    override val parentLayoutDirection: LayoutDirection,
    override val density: Float,
    override val fontScale: Float
) : Placeable.PlacementScope()

private fun MeasureScope.singleRelativeLayoutWithLayer(
    placeable: Placeable,
    layerBlock: GraphicsLayerScope.() -> Unit = NoOpLayerBlock
): MeasureResult {

    return object : MeasureResult {

        override val width = placeable.width
        override val height = placeable.height
        override val alignmentLines = emptyMap<AlignmentLine, Int>()
        override val rulers: (RulerScope.() -> Unit)? = null

        private val placementScope =
            SimplePlacementScope(width, layoutDirection, density, fontScale)

        override fun placeChildren() {
            with(placementScope) {
                placeable.placeWithLayer(IntOffset.Zero, layerBlock = layerBlock)
            }
        }
    }
}

// ---------------------------------------------------------------
// 3. 核心 OverscrollEffect
// ---------------------------------------------------------------

class OffsetOverscrollEffect(
    private val animationScope: CoroutineScope,
    private val animationSpec: AnimationSpec<Float>,
    private val maxFraction: Float,
) : OverscrollEffect {

    private var offset by mutableStateOf(Offset.Zero)
    private var axis = Axis.None
    private var springJob: Job? = null
    private var flingToScrollOffset = Offset.Zero

    override val isInProgress: Boolean = false

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset
    ): Offset {
        if (axis == Axis.None) {
            axis = if (abs(delta.y) >= abs(delta.x)) Axis.Vertical else Axis.Horizontal
        }

        val isUserInput = source == NestedScrollSource.UserInput
        if (isUserInput) {
            springJob?.cancel()
            springJob = null
        }

        var unconsumed = delta + flingToScrollOffset
        flingToScrollOffset = Offset.Zero

        if (offset != Offset.Zero) {
            unconsumed -= consumeOffset(unconsumed)
            if (unconsumed == Offset.Zero) return delta
        }

        unconsumed -= performScroll(unconsumed)
        if (unconsumed == Offset.Zero) return delta

        if (source == NestedScrollSource.UserInput) {
            unconsumed -= consumeOffset(unconsumed)
        }

        return delta - unconsumed
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity
    ) {
        var unconsumed = velocity

        // 已处于 overscroll 状态：spring 回弹，残余偏移反哺给列表滚动
        if (offset != Offset.Zero) {
            springJob = CoroutineScope(currentCoroutineContext()).launch {
                AnimationState(offset.toFloat(), unconsumed.toFloat())
                    .animateTo(0f, animationSpec) {
                        unconsumed = unconsumed.copyWith(this.velocity)
                        var unconsumedOffset = value.toOffset() - offset
                        unconsumedOffset -= consumeOffset(unconsumedOffset)
                        if (offset.toFloat() == 0f) {
                            flingToScrollOffset = unconsumedOffset
                            cancelAnimation()
                        }
                    }
            }
            springJob?.join()
            springJob = null
        }

        // 实测帧间隔（近似一帧时长），用于下方的首帧位移估算
        var frameTimeNanos = 1000L / 60L * 1_000_000L
        if (offset.toFloat() == 0f && unconsumed.toFloat() != 0f) {
            animationScope.launch {
                var start = System.currentTimeMillis()
                awaitFrame()
                frameTimeNanos = (System.currentTimeMillis() - start) * 1_000_000L
                start = System.currentTimeMillis()
                awaitFrame()
                frameTimeNanos = (System.currentTimeMillis() - start) * 1_000_000L
            }
        }

        unconsumed -= performFling(unconsumed)

        // 从边缘直接 fling 出界：用 spring 解析式估算首帧位移（∝ 速度）作为初始偏移
        if (unconsumed.toFloat() != 0f) {
            springJob = CoroutineScope(currentCoroutineContext()).launch {
                if (offset.toFloat() == 0f) {
                    offset = animationSpec.vectorize(Float.VectorConverter).getValueFromNanos(
                        frameTimeNanos,
                        AnimationVector(0f),
                        AnimationVector(0f),
                        AnimationVector(unconsumed.toFloat())
                    ).value.toOffset()
                }
                AnimationState(offset.toFloat(), unconsumed.toFloat())
                    .animateTo(0f, animationSpec) {
                        offset = offset.copyWith(value)
                    }
            }
            springJob?.join()
            springJob = null
        }
    }

    override val node: DelegatableNode = object : LayoutModifierNode, Modifier.Node() {

        override val shouldAutoInvalidate: Boolean = false

        override fun MeasureScope.measure(
            measurable: Measurable,
            constraints: Constraints
        ): MeasureResult {
            val placeable = measurable.measure(constraints)
            val maxWidth = constraints.maxWidth.toFloat()
            val maxHeight = constraints.maxHeight.toFloat()

            return singleRelativeLayoutWithLayer(placeable) {
                shape = NoOpShape

                val currentAxis = axis
                val maxDistance = when (currentAxis) {
                    Axis.Horizontal -> maxWidth
                    Axis.Vertical -> maxHeight
                    Axis.None -> 0f
                }

                val overscrollDistance = offset.toFloat()
                if (currentAxis != Axis.None && maxDistance > 0f && overscrollDistance != 0f) {
                    val offsetPx = computeOffset(overscrollDistance, maxDistance).fastRoundToInt()
                    when (currentAxis) {
                        Axis.Horizontal -> translationX = offsetPx.toFloat()
                        Axis.Vertical -> translationY = offsetPx.toFloat()
                        // 进入该分支前已排除 None，此处补全穷尽性（Kotlin 2.2 不做智能收敛）
                        Axis.None -> Unit
                    }
                }
            }
        }
    }

    private fun computeOffset(overscrollDistance: Float, maxDistance: Float): Float {
        val progress = ProgressConverter.convert(overscrollDistance / maxDistance, maxFraction)
        return progress * maxDistance
    }

    // 同向：全部消耗；反向：只消耗到归零为止（先"拉回来"再滚动列表）
    private fun consumeOffset(delta: Offset): Offset {
        val oldOffset = offset.toFloat()
        val delta = delta.toFloat()
        val consumed =
            if (oldOffset == 0f || (oldOffset + delta).sign == oldOffset.sign) {
                delta
            } else {
                -oldOffset
            }
        offset = offset.copyWith(oldOffset + consumed)
        return consumed.toOffset()
    }

    private fun Offset.toFloat(): Float {
        return when (axis) {
            Axis.Vertical -> y
            Axis.Horizontal -> x
            Axis.None -> 0f
        }
    }

    private fun Velocity.toFloat(): Float {
        return when (axis) {
            Axis.Vertical -> y
            Axis.Horizontal -> x
            Axis.None -> 0f
        }
    }

    private fun Float.toOffset(): Offset {
        return when (axis) {
            Axis.Vertical -> Offset(0f, this)
            Axis.Horizontal -> Offset(this, 0f)
            Axis.None -> Offset.Zero
        }
    }

    private fun Offset.copyWith(value: Float): Offset {
        return when (axis) {
            Axis.Vertical -> copy(y = value)
            Axis.Horizontal -> copy(x = value)
            Axis.None -> this
        }
    }

    private fun Velocity.copyWith(value: Float): Velocity {
        return when (axis) {
            Axis.Vertical -> copy(y = value)
            Axis.Horizontal -> copy(x = value)
            Axis.None -> this
        }
    }

    private enum class Axis {
        None,
        Vertical,
        Horizontal
    }

    companion object {
        // 临界阻尼、低刚度：软着陆、无过冲（androidx 原生签名 spring(阻尼, 刚度, 收敛阈值)）
        val DefaultAnimationSpec = spring(1f, 150f, 0.5f)
    }
}

private object NoOpShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        return Outline.Rectangle(Rect(Offset.Zero, size))
    }
}

// ---------------------------------------------------------------
// 4. 工厂与 Composable 入口
// ---------------------------------------------------------------

@Composable
fun rememberOffsetOverscrollFactory(
    animationSpec: AnimationSpec<Float> = OffsetOverscrollEffect.DefaultAnimationSpec,
    maxFraction: Float = 0.65f
): OverscrollFactory {
    val animationScope = rememberCoroutineScope()
    return remember(animationScope, animationSpec, maxFraction) {
        OffsetOverscrollFactory(
            animationScope = animationScope,
            animationSpec = animationSpec,
            maxFraction = maxFraction
        )
    }
}

class OffsetOverscrollFactory(
    private val animationScope: CoroutineScope,
    private val animationSpec: AnimationSpec<Float> = OffsetOverscrollEffect.DefaultAnimationSpec,
    private val maxFraction: Float = 0.65f
) : OverscrollFactory {

    override fun createOverscrollEffect(): OverscrollEffect {
        return OffsetOverscrollEffect(
            animationScope = animationScope,
            animationSpec = animationSpec,
            maxFraction = maxFraction
        )
    }

    // foundation 1.10+ 的 OverscrollFactory 把 equals/hashCode 重声明为抽象成员（工厂标识
    // 决定滚动容器是否重建效果实例）。本工厂一律经 remember 持有、实例身份稳定，引用相等即可
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 在此作用域内启用 iOS 式橡皮筋 overscroll：作用域内所有可滚动容器（verticalScroll、
 * LazyColumn 等）自动把系统的 stretch overscroll 替换为 [OffsetOverscrollEffect]，
 * 逐容器独立实例、单轴锁定；作用域外的滚动容器不受影响。
 *
 * 调参：改 [rememberOffsetOverscrollFactory] 的默认值——maxFraction 调大（如 0.8）更软、
 * 能拉更多，调小（如 0.4）更紧更硬；回弹弹簧 stiffness 调大更利落，dampingRatio 降到 1 以下
 * 会带一次过冲「弹一下」。
 */
@Composable
fun ProvideOverscroll(content: @Composable () -> Unit) {
    val overscrollFactory = rememberOffsetOverscrollFactory()
    CompositionLocalProvider(LocalOverscrollFactory provides overscrollFactory, content = content)
}

/**
 * [Modifier.verticalScroll] 的橡皮筋增强版：内容超出屏幕时行为与原版完全一致（边缘橡皮筋
 * 由注入的 [OffsetOverscrollEffect] 承担）；内容不足一屏时也能拉出橡皮筋——这是原版
 * 做不到的：foundation 在滚动范围为 0（canScrollForward 与 canScrollBackward 均为 false）
 * 时不向 overscroll 派发任何 delta（Scrollable.kt 的 shouldDispatchOverscroll 门槛），
 * 短页面/短浮层因此拉不出橡皮筋。
 *
 * 放宽方式：仅在「上下都不可滚」时追加一层兜底——
 * - 手势层在 verticalScroll 之内（更内层）：[ScrollableState] 不消费任何滚动量、
 *   [FlingBehavior] 把速度原速放行，拖拽 delta 与松手速度全部进入环境 OverscrollEffect
 *   （[ProvideOverscroll] 作用域内即 iOS 式橡皮筋），由它完成「拉出 → 软着陆回弹」以及
 *   速度正比的甩动拉出。若用默认 spline 惯性衰减，速度会被一场「无位移的惯性动画」
 *   吃光，甩动拉出就不会发生，所以必须放行；
 * - 效果渲染节点（[Modifier.overscroll]）则在 verticalScroll 之外（更外层）：滚动布局
 *   以「高度无穷」测量内层内容，节点在里层会把阻尼曲线的容器尺寸取成 Int.MAX_VALUE，
 *   32 位浮点下渲染位移被量化成上百像素的台阶（一顿一顿）；外层拿真实视口尺寸，
 *   阻尼与连续性才正确。外层平移的整组内容里自带滚动裁剪，视觉与正常路径一致；
 * - 手势接管：兜底层位于 verticalScroll 手势检测器的内层（Main pass 冒泡派发、内层
 *   先于外层），slop 触发后由它接管拖拽，外层检测器因 change 已被消费而退出；
 * - 内容一旦变得可滚（如浮层内键盘弹出使内容超高），canScroll 翻转、兜底层自动摘除，
 *   滚动路径回到与原版完全一致的状态。canScrollForward/Backward 本身是 derivedStateOf
 *   推导，滚动过程中只在进出边缘的瞬间翻转一次，不产生逐帧重组。
 *
 * 效果实例经 [rememberOverscrollEffect] 从环境工厂取（注入作用域内即
 * [OffsetOverscrollEffect] 的独立实例，与 verticalScroll 自建实例互不复用）；
 * 未注入任何工厂时退回系统 stretch，本修饰符仍可用。
 */
@Composable
fun Modifier.rubberBandVerticalScroll(state: ScrollState): Modifier {
    val effect = rememberOverscrollEffect()
    val unscrollable = !state.canScrollForward && !state.canScrollBackward
    return if (effect != null && unscrollable) {
        // 不消费任何滚动量的状态：canScrollForward/Backward 默认恒为 true，恰好绕过
        // foundation 的派发门槛，让全部 delta 进入 overscroll
        val passthroughState = remember { ScrollableState { 0f } }
        // 原速放行 fling：不产生任何惯性位移，速度原样交还效果实例（见上）
        val passthroughFling = remember {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                    initialVelocity
            }
        }
        this
            // 效果渲染节点必须挂在 verticalScroll 的滚动布局之外（更外层）：滚动布局给
            // 内层内容的测量约束是高度无穷（Constraints.Infinity = Int.MAX_VALUE），节点
            // 若在里层会把「容器尺寸」取成约 21 亿像素——阻尼曲线 f(p) = 1 − e^(−p·k) 中
            // p ≈ 拖动量 / 21亿 ≈ 1e-8，32 位浮点下 e^(−1e-8) 直接舍入成 1.0，渲染位移
            // 被量化成上百像素一级的台阶（拖动一顿一顿）。挂在外层拿到真实视口尺寸，
            // 阻尼饱和与连续性都正确——层级与 scrollableArea 自挂效果节点的位置一致
            .overscroll(effect)
            .verticalScroll(state)
            .scrollable(
                state = passthroughState,
                orientation = Orientation.Vertical,
                overscrollEffect = effect,
                flingBehavior = passthroughFling,
            )
    } else {
        this.verticalScroll(state)
    }
}

/**
 * [Modifier.horizontalScroll] 的橡皮筋增强版：与 [rubberBandVerticalScroll] 完全同构
 * （同一套「兜底手势层在内、效果渲染节点在外」的接管与量化规避手段），仅方向为水平。
 *
 * 内容超出一行时行为与原版一致（边缘橡皮筋由注入的 [OffsetOverscrollEffect] 承担）；
 * 内容不足一行（横向两个方向都不可滚）时原版拉不出橡皮筋——foundation 的派发门槛
 * （Scrollable.kt 的 shouldDispatchOverscroll）——此辅助在内层追加一层零消费
 * scrollable + 原速 fling 兜底，把拖拽 delta 与松手速度全部交给环境 OverscrollEffect
 * （[ProvideOverscroll] 作用域内即 iOS 式橡皮筋），由它完成「拉出 → 软着陆回弹」。
 */
@Composable
fun Modifier.rubberBandHorizontalScroll(state: ScrollState): Modifier {
    val effect = rememberOverscrollEffect()
    val unscrollable = !state.canScrollForward && !state.canScrollBackward
    return if (effect != null && unscrollable) {
        // 与 vertical 兜底同一套构件：零消费状态（canScroll 默认 true，绕过派发门槛）
        val passthroughState = remember { ScrollableState { 0f } }
        // 原速放行 fling：不产生任何惯性位移，速度原样交还效果实例
        val passthroughFling = remember {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                    initialVelocity
            }
        }
        this
            // 效果渲染节点在 horizontalScroll 之外（更外层）：滚动布局以「宽度无穷」测量
            // 内层内容，节点在里层会把「容器尺寸」取成 Int.MAX_VALUE，阻尼渲染位移被
            // 量化成台阶；外层拿真实视口尺寸，阻尼与连续性才正确
            .overscroll(effect)
            .horizontalScroll(state)
            .scrollable(
                state = passthroughState,
                orientation = Orientation.Horizontal,
                overscrollEffect = effect,
                flingBehavior = passthroughFling,
            )
    } else {
        this.horizontalScroll(state)
    }
}
