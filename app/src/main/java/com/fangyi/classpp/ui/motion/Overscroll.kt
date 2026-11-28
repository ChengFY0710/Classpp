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
// 效果实例，首次手势按 delta 主方向占比阈值锁定单轴）；内容不足一屏（无滚动范围）的容器拉不出
// 橡皮筋（foundation 派发门槛），改用 rubberBandVerticalScroll / rubberBandHorizontalScroll /
// RubberBandLazyColumn / RubberBandLazyVerticalStaggeredGrid 这四个增强版。注意内容平移会把
// 边缘外区域露出来——滚动容器背后需要是可接受的底色（同色背景即可，边缘处看不出破绽）。
package com.fangyi.classpp.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.RulerScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import kotlinx.coroutines.CancellationException
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
    // 未定轴期间的双轴累积量：供按占比阈值定轴（见 applyToScroll），定轴后作废
    private var pendingAxisX = 0f
    private var pendingAxisY = 0f
    private var springJob: Job? = null
    private var flingToScrollOffset = Offset.Zero
    // 保持态（下拉刷新的「同步中」展示位）：true 时吞掉全部滚动与甩动，见 holdAt
    private var held = false

    override val isInProgress: Boolean = false

    /**
     * 下拉刷新的松手回调：竖向、向下过滚动（offset.y > 0）且用户拖拽结束（applyToFling
     * 入口）时触发，[pullPx] 为原始未阻尼拉距 px（与 [currentOffset] 同口径）。宿主在
     * 回调内判定是否达到触发阈值并同步调用 [holdAt]（它会抢在本回调返回后的既有回弹
     * 逻辑之前接管动画）；不要在回调内挂起等待网络等长操作。null = 无监听（默认）。
     */
    var onPullRelease: ((effect: OffsetOverscrollEffect, pullPx: Float) -> Unit)? = null

    /**
     * 当前原始过滚动偏移（px，未阻尼）；节点渲染时再按容器尺寸换算成视觉位移。
     * 供页级过滚动判断“是否还拉着”以及反向拖动先收回多少；也可在绘制期读取，
     * 让页面视觉与拖动量同口径（如设置页标题渐显）。
     */
    val currentOffset: Offset get() = offset

    /** 立即取消回弹并归零（切态、打开浮层、跨天重置等）。 */
    fun reset() {
        springJob?.cancel()
        springJob = null
        held = false
        offset = Offset.Zero
        flingToScrollOffset = Offset.Zero
        axis = Axis.None
        pendingAxisX = 0f
        pendingAxisY = 0f
    }

    /**
     * 定格展示（下拉刷新语义，配 [onPullRelease] 使用）：从当前偏移按弹簧收敛到
     * [restOffsetPx]（同步中展示位，原始未阻尼拉距 px，与 [onPullRelease] 的 pullPx
     * 同口径），到位后停在展示位——后续滚动与甩动全部被吞（内容停在展示位，
     * 不随手势移动），直到 [releaseHold] 回弹归零。
     */
    fun holdAt(restOffsetPx: Float) {
        held = true
        springJob?.cancel()
        if (axis == Axis.None) axis = Axis.Vertical
        val from = offset.toFloat()
        if (from == restOffsetPx) return
        springJob = animationScope.launch {
            AnimationState(from, 0f)
                .animateTo(restOffsetPx, animationSpec) {
                    offset = offset.copyWith(value)
                }
        }
    }

    /**
     * 退出保持态：从展示位按弹簧回弹归零收起，返回回弹动画（调用方可 [Job.join]
     * 等待收尾，如「松手后同步」提示要在回弹全程保持隐藏）。未在保持态时返回 null。
     */
    fun releaseHold(): Job? {
        if (!held) return null
        held = false
        springJob?.cancel()
        val from = offset.toFloat()
        if (from == 0f) return null
        return animationScope.launch {
            AnimationState(from, 0f)
                .animateTo(0f, animationSpec) {
                    offset = offset.copyWith(value)
                }
        }.also { springJob = it }
    }

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset
    ): Offset {
        // 保持态（下拉同步展示位）：吞掉全部滚动量，手势与惯性都不动内容
        if (held) return delta
        if (axis == Axis.None) {
            // 未定轴期间累积双轴分量，主方向达到占比阈值（[DirectionRatio]，同手势方向
            // 仲裁的阈值）才定轴——混合 delta 的首帧不再一锤定音，也消除原「平局偏竖向」
            // 的隐患。触摸滚动的 delta 恒为纯轴分量（竖向容器只派发 y），首帧即满足占比，
            // 定轴时机与原逐帧主分量判定一致；累计路径超过兜底长度仍纠结（持续近 45° 的
            // 混合输入）时按主分量定轴，避免永远不定轴导致橡皮筋失效
            pendingAxisX += abs(delta.x)
            pendingAxisY += abs(delta.y)
            axis = when {
                pendingAxisY > 0f && pendingAxisY >= DirectionRatio * pendingAxisX -> Axis.Vertical
                pendingAxisX > 0f && pendingAxisX >= DirectionRatio * pendingAxisY -> Axis.Horizontal
                pendingAxisX + pendingAxisY >= AxisFallbackPathPx ->
                    if (pendingAxisY >= pendingAxisX) Axis.Vertical else Axis.Horizontal
                else -> Axis.None
            }
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
        // 下拉刷新的松手判定：拖拽结束且处于向下过滚动（含甩速场景）时通知宿主，
        // 宿主在回调内判定阈值并 holdAt 定格（holdAt 先于下方既有回弹逻辑接管动画）
        if (axis == Axis.Vertical && offset.y > 0f) {
            onPullRelease?.invoke(this, offset.y)
        }

        // 保持态（下拉同步展示位）：吞掉全部甩动，等 releaseHold 收尾
        if (held) return

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

        /**
         * 默认最大拉出比例：阻尼曲线 f(p) = 1 − e^(−p·maxFraction)，初始跟手比例
         * 等于该值，位移上限 = maxFraction × 容器尺寸。页级过滚动想和内容区
         * 完全同款手感时直接使用该 token。
         */
        const val DefaultMaxFraction = 0.65f

        // 轴向锁的兜底判定路径（px，约两倍触控 slop 的等效值）：未定轴的累计路径达到它
        // 仍纠结时按主分量定轴。触摸输入的 delta 恒为纯轴分量，不会走到该兜底
        private const val AxisFallbackPathPx = 48f
    }
}

/**
 * 下拉进度登记表：一个页面里同时存在多个橡皮筋效果实例（分组页每页两实例、多页并存），
 * 下拉只发生在其中活跃的一个上。宿主把登记表传给各滚动容器（[RubberBandLazyColumn] 等
 * 的 `pullProgress` 参数），容器把效果实例登记进来、卸载时注销；宿主在**绘制期**读
 * [maxPullY] 取活跃实例的原始拉距（未拉动的实例恒为 0，取最大值即当前手势的拉距），
 * 驱动「松手后同步」一类的下拉进度提示（同 SettingsUi 的 graphicsLayer 零重组读法）。
 * 绘制期读 [maxPullY] 的读者只订阅「读取那一刻登记在册的实例」，所以**注册/注销本身必须
 * 可被观测**（见下方登记表字段），否则切页换实例后读者仍盯着旧实例，拉距变化收不到。
 * 主线程专用（登记走 DisposableEffect、读取走绘制阶段），不加锁。
 */
class OverscrollPullProgress {

    // 登记表必须是快照列表：绘制期读者（graphicsLayer 的延迟读取）只观测「它上一次执行
    // 那一刻登记在册的实例」，普通集合的增删不产生任何失效——切分组/切视图后新页的实例
    // 注册进来、旧页注销，提示的图层块仍挂着上一页那批实例（拉距恒为 0），新实例的拉距
    // 变化一概收不到，表现为「切页后的第一次下拉，提示恒透明、文案不出现」，要等别的
    // 重组把图层块重跑一遍才恢复。快照列表的读（含读空表）会被观测，注册/注销本身
    // 即让绘制期读者失效、重新读取当册实例
    private val effects = mutableStateListOf<OffsetOverscrollEffect>()

    fun register(effect: OffsetOverscrollEffect) {
        if (effects.none { it === effect }) effects += effect
    }

    fun unregister(effect: OffsetOverscrollEffect) {
        effects.removeAll { it === effect }
    }

    /** 当前最大原始拉距（px，未阻尼；向下拉为正）。无活跃实例时为 0 */
    fun maxPullY(): Float {
        // 空表也要读一次登记表（快照读）：读者据此在实例注册/注销时收到失效通知
        if (effects.isEmpty()) return 0f
        return effects.maxOfOrNull { it.currentOffset.y } ?: 0f
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
    maxFraction: Float = OffsetOverscrollEffect.DefaultMaxFraction,
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

/**
 * 创建并记住一个独立的 [OffsetOverscrollEffect]（不经环境工厂）：宿主需要读取过滚动
 * 偏移（[OffsetOverscrollEffect.currentOffset]）时使用，再把实例交给
 * [rubberBandVerticalScroll] 的 `overscrollEffect` 参数。
 */
@Composable
fun rememberOffsetOverscrollEffect(
    animationSpec: AnimationSpec<Float> = OffsetOverscrollEffect.DefaultAnimationSpec,
    maxFraction: Float = OffsetOverscrollEffect.DefaultMaxFraction,
): OffsetOverscrollEffect {
    val animationScope = rememberCoroutineScope()
    return remember(animationScope, animationSpec, maxFraction) {
        OffsetOverscrollEffect(animationScope, animationSpec, maxFraction)
    }
}

class OffsetOverscrollFactory(
    private val animationScope: CoroutineScope,
    private val animationSpec: AnimationSpec<Float> = OffsetOverscrollEffect.DefaultAnimationSpec,
    private val maxFraction: Float = OffsetOverscrollEffect.DefaultMaxFraction,
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
 *
 * [overscrollEffect] 非空时整条滚动路径改用该实例：内容可滚时直接交给
 * `verticalScroll(overscrollEffect = ...)`（由它同时负责渲染与派发），内容不足一屏时
 * 作为兜底 scrollable 的效果；null = 环境工厂实例，原行为。宿主需要读取过滚动偏移
 * [OffsetOverscrollEffect.currentOffset]（如设置页标题把过滚动算进内容位移）时传入
 * 自己 remember 的实例。
 */
@Composable
fun Modifier.rubberBandVerticalScroll(
    state: ScrollState,
    overscrollEffect: OffsetOverscrollEffect? = null,
): Modifier {
    // 环境实例始终取：保持组合结构稳定；宿主传入实例时两段路径都换用该实例
    val environmentEffect = rememberOverscrollEffect()
    val effect = overscrollEffect ?: environmentEffect
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
    } else if (overscrollEffect != null) {
        // 可滚页面：页面自有实例同时负责渲染与派发，宿主才能读到边缘过滚动
        this.verticalScroll(state, overscrollEffect = overscrollEffect)
    } else {
        this.verticalScroll(state)
    }
}

/**
 * 「自包含」橡皮筋纵向滚动：内容超过 [maxHeight] 后在自身视口内滚动；滚到边继续拖动、
 * 或惯性甩到边时，剩余量留在本容器里由独立的过滚动效果拉出，**不再交给外层滚动容器**
 * （如聊天消息列）——外层列表在这块区域上不跟手、也不接力甩动速度。
 *
 * 与 [rubberBandVerticalScroll] 的分工：那个是页面级容器，边缘剩余量本来就该由外层或
 * 页面过滚动承接；本容器嵌在另一个滚动列里，过滚动必须自收自支，否则会表现为「小卡片
 * 拉着拉着把整页列表带走了」。因此不走 scrollable 自带的 overscroll 派发（它是在父级
 * post-scroll **之后**才拿到剩余量），而是用 [NestedScrollConnection] 在 post 阶段截住
 * 剩余量、手动喂给效果实例：
 * - 用户拖动：剩余量经 [OffsetOverscrollEffect.applyToScroll] 拉出；反向拖动时在 pre
 *   阶段先收回拉出量，剩余量照常滚内容（顺序与效果的内部实现一致）；
 * - 惯性到边：自定义 [FlingBehavior] 用「本容器实际滚了多少」认边（嵌套滚动里剩余量
 *   会被外层连接吃掉，返回的 consumed 恒为全量，默认惯性据此判断不出到边，会把整段
 *   惯性在边缘空跑完，观感是一段很慢的慢动作），一到边立即停下，把剩余速度经
 *   post-fling 交给效果，做与速度相称的拉出。
 *
 * 只在内容确实超过 [maxHeight]（存在滚动范围）时接管：内容不足一屏时仍是普通滚动容器，
 * 外层列表照常跟手，避免小卡片在列表里变成滑不动的「死区」。
 * 拉出位移被 [clipToBounds] 裁在视口内，露出的底色来自容器背后（调用方应保证与卡片
 * 底色一致），不会盖到相邻内容。
 */
@Composable
fun Modifier.containedRubberBandVerticalScroll(
    state: ScrollState,
    maxHeight: Dp,
): Modifier {
    val effect = rememberOffsetOverscrollEffect()
    val density = LocalDensity.current
    // 自收自支的惯性：到边立刻停（判据是本容器实滚量，见 [ContainedFlingBehavior]）
    val flingBehavior = remember(state, density) {
        ContainedFlingBehavior(state, splineBasedDecay(density))
    }
    val connection = remember(state, effect) {
        object : NestedScrollConnection {
            // 内容超出上限才有滚动范围；不足一屏时不接管，保持普通嵌套滚动
            val scrollable: Boolean get() = state.maxValue > 0

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!scrollable || source != NestedScrollSource.UserInput) return Offset.Zero
                val offset = effect.currentOffset.y
                val delta = available.y
                if (offset == 0f || delta == 0f || delta.sign == offset.sign) return Offset.Zero
                // 反向拖动先把拉出量收回到零（夹住拖动量，避免效果把它当成反向拉出），
                // 剩余量照常进入内容滚动
                val contract = if (abs(delta) <= abs(offset)) delta else -offset
                return effect.applyToScroll(Offset(0f, contract), source) { Offset.Zero }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!scrollable || available.y == 0f) return Offset.Zero
                if (source == NestedScrollSource.UserInput) {
                    // 用户拖动到边：剩余量在卡内拉出橡皮筋，不交给外层列表
                    return effect.applyToScroll(available, source) { Offset.Zero }
                }
                // 惯性帧：只吃掉、不在这里拉出。拉出交给 fling 到边后 post-fling 的
                // 剩余速度（效果按速度做首帧位移）；在这里逐帧喂会让拉出跟着惯性衰减
                // 慢慢爬，快速滑动时就是一段很慢的动画
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!scrollable) {
                    effect.reset()
                    return Velocity.Zero
                }
                // 速度留在卡内：拉出量回弹（没有拉出量时是空操作），外层列表不接力
                effect.applyToFling(available) { Velocity.Zero }
                return available
            }
        }
    }
    return this
        // 视口上限必须在效果节点之外：效果用测量约束的高算阻尼上限，高度无界时
        // 会退化成「一帧上百像素」的台阶位移（同 [rubberBandVerticalScroll] 的注释）
        .heightIn(max = maxHeight)
        .clipToBounds()
        .overscroll(effect)
        .nestedScroll(connection)
        .verticalScroll(state, flingBehavior = flingBehavior)
}

/**
 * [containedRubberBandVerticalScroll] 的自定义惯性：与默认 spline 惯性同构，唯一区别是
 * **到边判据**。
 *
 * 默认惯性看 `scrollBy` 返回的消费量：嵌套滚动里剩余量会被外层滚动容器在 post 阶段吃掉，
 * 返回值始终是「全量已消费」，因此它在边缘不会停下，会把剩余惯性按完整衰减时长跑完——
 * 快速甩动时表现为过滚动拉出跟着那段衰减慢慢爬，像慢动作。这里改为在每帧滚动前看本容器
 * 的位置：已经贴边（正向在 [ScrollState.maxValue]、反向在 0）还继续同向甩，才算到边，
 * 立即取消并把当前速度交回 post-fling，由过滚动做速度相称的拉出。
 *
 * 不能改用 `ScrollState.value` 的前后差值判边：value 是整数，内部带小数累加器，每帧
 * 舍入差就可能超过 0.5px，正常惯性会被误判成到边并凭空拉出过滚动。
 *
 * 内容不足一屏（没有滚动范围）时退回默认判据：此时本就不该接管，惯性应照常传给外层列表。
 */
private class ContainedFlingBehavior(
    private val state: ScrollState,
    private val decay: DecayAnimationSpec<Float>,
) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) <= 1f) return initialVelocity
        val selfContained = state.maxValue > 0
        var velocityLeft = initialVelocity
        var lastValue = 0f
        val animationState = AnimationState(initialValue = 0f, initialVelocity = initialVelocity)
        try {
            animationState.animateDecay(decay) {
                val delta = value - lastValue
                lastValue = value
                val before = state.value
                val consumed = scrollBy(delta)
                velocityLeft = this.velocity
                val hitEdge = if (selfContained) {
                    // 本帧开始前就贴边、且继续朝同方向甩：本容器这一帧吃不下任何量
                    when {
                        delta > 0f -> before >= state.maxValue
                        delta < 0f -> before <= 0
                        else -> false
                    }
                } else {
                    abs(delta - consumed) > 0.5f
                }
                if (hitEdge) cancelAnimation()
            }
        } catch (exception: CancellationException) {
            // 新手势打断惯性：把打断瞬间的速度交回，保证 post-fling 的交接与默认惯性一致
            velocityLeft = animationState.velocity
        }
        return velocityLeft
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

/**
 * [LazyColumn] 的橡皮筋增强版：内容超出一屏时行为与原版完全一致（边缘橡皮筋由注入的
 * [OffsetOverscrollEffect] 承担，渲染节点在列表自带的 scrollableArea 里，容器尺寸就是视口）；
 * 内容不足一屏（上下都不可滚）时原版拉不出橡皮筋——foundation 的派发门槛
 * （Scrollable.kt 的 shouldDispatchOverscroll = canScrollForward || canScrollBackward），
 * 本辅助此时把拖拽 delta 与甩动速度全部交给环境 OverscrollEffect（[ProvideOverscroll] 作用域内
 * 即 iOS 式橡皮筋），由它完成「拉出 → 软着陆回弹」以及速度正比的甩动拉出。
 *
 * 与 [rubberBandVerticalScroll] 的差异：LazyColumn 是组合函数而非修饰符，且它的手势层
 * （scrollableArea）由列表内部自带、挂在调用方 modifier 之后（更内层）——内层先收到指针事件，
 * 外挂的兜底手势层会被它先吞掉 change 而收不到拖拽。所以兜底分支要关掉列表自身手势
 * （userScrollEnabled = false：DragGestureNode 在 enabled=false 时不处理任何指针事件），
 * 内容一旦变得可滚（如勾选完成使分组变高）canScroll 翻转，关手势与兜底层一并摘除，
 * 滚动路径回到与原版完全一致的状态；canScrollForward/Backward 本身是 mutableState，
 * 翻转在进出边缘的瞬间各触发一次重组，不产生逐帧重组。
 *
 * 这一开关只改同一个 LazyColumn 调用点的 modifier 与参数，不写成 if/else 两处调用：
 * canScroll 翻转并不罕见（内容高度正好跨过一屏——如展开一个可收起分组），而列表整体重建
 * 会连它内部保存列表项状态的容器一起丢掉，列表项里 rememberSaveable 持有的界面状态会被
 * 重置回初值（原因与后果见下方实现处的注释）。
 *
 * 零消费状态与原速放行 fling 的作用同 [rubberBandVerticalScroll]（绕过派发门槛、
 * 不让「无位移的惯性动画」吃掉甩动速度）；效果渲染节点（[Modifier.overscroll]）挂在
 * 列表滚动布局之外，拿真实视口做阻尼分母（同 [rubberBandVerticalScroll] 的量化规避），
 * 平移露出的边缘外区域是容器背后底色。
 */
@Composable
fun RubberBandLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    /** 下拉刷新松手回调（见 [OffsetOverscrollEffect.onPullRelease]）；null = 不监听 */
    onPullRelease: ((effect: OffsetOverscrollEffect, pullPx: Float) -> Unit)? = null,
    /** 下拉进度登记（见 [OverscrollPullProgress]）；null = 不登记 */
    pullProgress: OverscrollPullProgress? = null,
    content: LazyListScope.() -> Unit,
) {
    val effect = rememberOverscrollEffect()
    // 兜底路径之外、列表自带手势层用的效果实例：与兜底实例分开持有（两个 remember 各
    // 建一个），两条路径切换时不会出现同一实例被两处同时挂载
    val listEffect = rememberOverscrollEffect()
    // 下拉刷新松手回调：同一时刻只有活跃实例（兜底层或列表自带层）会收到拖拽，
    // 两个实例都接线；SideEffect 每次组合刷新引用，回调方拿到的 lambda 永远最新。
    // 环境实例的静态类型是 foundation 基类，ProvideOverscroll 作用域内必为
    // OffsetOverscrollEffect（其余工厂/无工厂时安全跳过——该手势只支持自家的橡皮筋）
    SideEffect {
        (effect as? OffsetOverscrollEffect)?.onPullRelease = onPullRelease
        (listEffect as? OffsetOverscrollEffect)?.onPullRelease = onPullRelease
    }
    // 下拉进度登记：两实例都登记（同一时刻只有活跃实例有拉距），容器卸载时注销防悬空
    DisposableEffect(pullProgress) {
        val ambient = effect as? OffsetOverscrollEffect
        val list = listEffect as? OffsetOverscrollEffect
        if (pullProgress != null && ambient != null) pullProgress.register(ambient)
        if (pullProgress != null && list != null) pullProgress.register(list)
        onDispose {
            if (pullProgress != null && ambient != null) pullProgress.unregister(ambient)
            if (pullProgress != null && list != null) pullProgress.unregister(list)
        }
    }
    val unscrollable = !state.canScrollForward && !state.canScrollBackward
    // 兜底判据：有环境效果实例、调用方允许手势、且列表上下都不可滚
    val fallbackEffect = effect?.takeIf { userScrollEnabled && unscrollable }
    // 不消费任何滚动量的状态：canScrollForward/Backward 默认恒为 true，恰好绕过
    // foundation 的派发门槛，让全部 delta 进入 overscroll
    val passthroughState = remember { ScrollableState { 0f } }
    // 原速放行 fling：不产生任何惯性位移，速度原样交还效果实例
    val passthroughFling = remember {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                initialVelocity
        }
    }
    // 单一调用点：兜底开关只改 modifier 与参数，绝不写成 if/else 两个 LazyColumn。
    // 列表整体重建会连它内部保存列表项状态的容器一起丢掉（LazyLayout 的可保存状态容器
    // 建在列表里），列表项中 rememberSaveable 持有的界面状态会被重置回初值；而内容高度
    // 恰好跨过「不足一屏 ↔ 可滚」时兜底判据会翻转——若此刻正有依赖项内保存状态的操作
    // 进行中，状态会立刻被重置、再点再重置（表现为操作怎么点都不生效）
    LazyColumn(
        modifier = if (fallbackEffect != null) {
            modifier
                // 效果渲染节点：挂在列表外侧（更外层），按真实视口尺寸做阻尼分母
                // （同 rubberBandVerticalScroll 的量化规避）
                .overscroll(fallbackEffect)
                // 兜底手势层：列表自带手势层关掉后由它接管拖拽（内层先于外层派发）
                .scrollable(
                    state = passthroughState,
                    orientation = Orientation.Vertical,
                    overscrollEffect = fallbackEffect,
                    flingBehavior = passthroughFling,
                )
        } else {
            modifier
        },
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        // 兜底时关掉列表自带手势层（更内层、会先吞掉拖拽 change）；平时原样透传
        userScrollEnabled = if (fallbackEffect == null) userScrollEnabled else false,
        // 兜底时摘掉列表自带的效果实例（它永远收不到 delta），只留外侧兜底那一个；
        // 平时用列表自己的实例
        overscrollEffect = if (fallbackEffect == null) listEffect else null,
        content = content,
    )
}

/**
 * [LazyVerticalStaggeredGrid] 的橡皮筋增强版：与 [RubberBandLazyColumn] 完全同构
 * （同一套「不足一屏时关列表手势、外挂兜底手势层 + 效果渲染节点」的接管与量化规避手段），
 * 仅容器换成两列/多列瀑布流——收集箱视图的卡片网格用它。
 *
 * 内容超出一屏时行为与原版完全一致（边缘橡皮筋由注入的 [OffsetOverscrollEffect] 承担，
 * 渲染节点在网格自带的 scrollableArea 里，容器尺寸就是视口）；内容不足一屏（上下都不可滚）
 * 时原版拉不出橡皮筋——foundation 的派发门槛（Scrollable.kt 的
 * shouldDispatchOverscroll = canScrollForward || canScrollBackward），本辅助此时把拖拽
 * delta 与甩动速度全部交给环境 OverscrollEffect（[ProvideOverscroll] 作用域内即 iOS 式
 * 橡皮筋），由它完成「拉出 → 软着陆回弹」以及速度正比的甩动拉出。
 *
 * 与 [RubberBandLazyColumn] 的差异只在容器参数：网格的手势层同样由列表内部自带、挂在
 * 调用方 modifier 之后（更内层），所以兜底分支同样要关掉网格自身手势
 * （userScrollEnabled = false）并摘掉它自带的效果实例；canScroll 翻转时兜底层自动摘除，
 * 滚动路径回到与原版一致的状态。保持单一 [LazyVerticalStaggeredGrid] 调用点，内容高度
 * 跨过一屏阈值时不重建网格、不丢条目状态（原因见 [RubberBandLazyColumn] 实现处注释）。
 */
@Composable
fun RubberBandLazyVerticalStaggeredGrid(
    columns: StaggeredGridCells,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalItemSpacing: Dp = 0.dp,
    horizontalArrangement: Arrangement.Horizontal =
        if (!reverseLayout) Arrangement.Start else Arrangement.End,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    /** 下拉刷新松手回调（见 [OffsetOverscrollEffect.onPullRelease]）；null = 不监听 */
    onPullRelease: ((effect: OffsetOverscrollEffect, pullPx: Float) -> Unit)? = null,
    /** 下拉进度登记（见 [OverscrollPullProgress]）；null = 不登记 */
    pullProgress: OverscrollPullProgress? = null,
    content: LazyStaggeredGridScope.() -> Unit,
) {
    val effect = rememberOverscrollEffect()
    // 兜底路径之外、网格自带手势层用的效果实例：与兜底实例分开持有（两个 remember 各
    // 建一个），两条路径切换时不会出现同一实例被两处同时挂载
    val gridEffect = rememberOverscrollEffect()
    // 下拉刷新松手回调：同一时刻只有活跃实例（兜底层或网格自带层）会收到拖拽，
    // 两个实例都接线；SideEffect 每次组合刷新引用，回调方拿到的 lambda 永远最新
    //（环境实例静态类型为 foundation 基类，同 RubberBandLazyColumn 的转型说明）
    SideEffect {
        (effect as? OffsetOverscrollEffect)?.onPullRelease = onPullRelease
        (gridEffect as? OffsetOverscrollEffect)?.onPullRelease = onPullRelease
    }
    // 下拉进度登记：同 RubberBandLazyColumn，卸载时注销防悬空
    DisposableEffect(pullProgress) {
        val ambient = effect as? OffsetOverscrollEffect
        val grid = gridEffect as? OffsetOverscrollEffect
        if (pullProgress != null && ambient != null) pullProgress.register(ambient)
        if (pullProgress != null && grid != null) pullProgress.register(grid)
        onDispose {
            if (pullProgress != null && ambient != null) pullProgress.unregister(ambient)
            if (pullProgress != null && grid != null) pullProgress.unregister(grid)
        }
    }
    val unscrollable = !state.canScrollForward && !state.canScrollBackward
    // 兜底判据：有环境效果实例、调用方允许手势、且网格上下都不可滚
    val fallbackEffect = effect?.takeIf { userScrollEnabled && unscrollable }
    // 不消费任何滚动量的状态：canScrollForward/Backward 默认恒为 true，恰好绕过
    // foundation 的派发门槛，让全部 delta 进入 overscroll
    val passthroughState = remember { ScrollableState { 0f } }
    // 原速放行 fling：不产生任何惯性位移，速度原样交还效果实例
    val passthroughFling = remember {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                initialVelocity
        }
    }
    // 单一调用点：兜底开关只改 modifier 与参数，绝不写成 if/else 两个网格（内容高度
    // 恰好跨过「不足一屏 ↔ 可滚」时兜底判据会翻转，整体重建会丢掉条目里的保存状态）
    LazyVerticalStaggeredGrid(
        columns = columns,
        modifier = if (fallbackEffect != null) {
            modifier
                // 效果渲染节点：挂在网格外侧（更外层），按真实视口尺寸做阻尼分母
                // （同 rubberBandVerticalScroll 的量化规避）
                .overscroll(fallbackEffect)
                // 兜底手势层：网格自带手势层关掉后由它接管拖拽（内层先于外层派发）
                .scrollable(
                    state = passthroughState,
                    orientation = Orientation.Vertical,
                    overscrollEffect = fallbackEffect,
                    flingBehavior = passthroughFling,
                )
        } else {
            modifier
        },
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalItemSpacing = verticalItemSpacing,
        horizontalArrangement = horizontalArrangement,
        flingBehavior = flingBehavior,
        // 兜底时关掉网格自带手势层（更内层、会先吞掉拖拽 change）；平时原样透传
        userScrollEnabled = if (fallbackEffect == null) userScrollEnabled else false,
        // 兜底时摘掉网格自带的效果实例（它永远收不到 delta），只留外侧兜底那一个；
        // 平时用网格自己的实例
        overscrollEffect = if (fallbackEffect == null) gridEffect else null,
        content = content,
    )
}
