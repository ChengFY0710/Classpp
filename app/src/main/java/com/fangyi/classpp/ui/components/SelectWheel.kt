package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimation
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 滚轮单格高度 = 相邻两个数值的中心间距 */
private val WheelItemHeight: Dp = 60.dp

/** 滚轮可见格数：中间选中 + 上下各一格 */
private const val WheelVisibleItems = 3

/** 单个滚轮的宽度 */
private val WheelWidth: Dp = 120.dp

/** 邻格数字相对选中数字的缩放（对齐设计稿：上下灰格约为中间的 0.6 倍） */
internal const val AdjacentScale = 0.62f

/** 渐隐结束的距离（格）：1 = 邻格，到它时 alpha 归零（早于视口裁剪边 1.5 格） */
private const val FadeOutItems = 1.4f

/** 渐隐段里数字额外缩到多少（邻格基础上再缩） */
internal const val FadeOutScale = 0.8f

/** 拖出首尾边界的阻尼：越界位移只按此比例跟手（橡皮筋，不会越拉越远） */
private const val OvershootResistance = 0.25f

/** 滑到头惯性过冲的视觉上限（格）：甩得再狠也最多甩出这么多，随即带残速弹回边界格 */
private const val BounceOvershootItems = 0.5f

/** 最小甩动速度（Android ViewConfiguration 同款 50dp/s）：低于它的松手不算甩，直接吸附 */
private val MinimumFlingVelocity: Dp = 50.dp

/** 数字右缘到单位的间距 */
private val UnitGap: Dp = 10.dp

/**
 * 数字滚轮积木（BottomTimeSelectCard / BottomDateSelectCard 共用）：垂直 draggable 的
 * 自绘选择轮。选中数字 onSurface 全尺寸、字重 SemiBold，未选中字重 Medium（字重离散，
 * 跨半格直接切换，不随距离插值）；离中心越远逐帧混向 classppColors.secondaryText、
 * 缩小（邻格 [AdjacentScale] 倍）并渐隐（[FadeOutItems] 格处完全消失，早于视口裁剪边），
 * 数字跟手移动时上下格连续插值——上面的渐隐缩小消失、下面的渐隐放大出现。
 * 滚动期零重组：位移、颜色、透明度都是 draw 期从滚动量算出的纯函数（常驻组合零重组哲学）。
 * 视觉与手感规格收在文件顶部常量，改一处全滚轮生效。
 */

/**
 * 单个滚轮的状态：[scroll] 是内容滚动量（px，数值 i 画在 `i × 格距 − scroll` 的偏移处，
 * 正 = 内容下移 = 选中更小的值）。拖拽、惯性、吸附、滑到头回弹、点选全部收在这里，确认
 * 按钮随时可读 [selectedIndex]（跟手/吸附过渡中取最近的整格）。
 *
 * 格数可变（日期选择器的「日」轮随年月天数增减，见 [updateCount]）。
 * 滚动数学与轴向无关：竖向轮用默认格距 [WheelItemHeight]，横向轮（SelectWheelHorizontal）
 * 传格宽 [itemSize] 即可复用同一套拖拽/惯性/吸附/橡皮筋。
 */
internal class WheelState(
    count: Int,
    initialIndex: Int,
    density: Density,
    private val scope: CoroutineScope,
    // 格距（相邻两个数值的中心间距）：竖向轮 = 格高 WheelItemHeight，横向轮 = 格宽
    itemSize: Dp = WheelItemHeight,
) {
    private val itemSizePx = with(density) { itemSize.toPx() }
    private val minFlingVelocityPx = with(density) { MinimumFlingVelocity.toPx() }

    /** 格数（1 起） */
    var count: Int = count.coerceAtLeast(1)
        private set

    private var maxScroll = (count - 1) * itemSizePx

    // 未阻尼的滚动量：越界段的橡皮筋映射以它为输入，避免逐帧折叠阻尼导致回拉
    private var raw = initialIndex.coerceIn(0, count - 1) * itemSizePx

    // 松手的惯性+吸附动画：再次拖拽/增减格数要先取消它并放开边界，否则 snapTo 会被界夹住
    private var settleJob: Job? = null

    val scroll = Animatable(raw)

    /** 当前选中索引（0..count-1） */
    val selectedIndex: Int
        get() = (scroll.value / itemSizePx).roundToInt().coerceIn(0, count - 1)

    /**
     * 增减格数（日轮 1..31 → 1..30）：取消收尾动画、收紧边界，当前值越界时**就地折回**
     * （31 日 → 30 日），不播动画。
     */
    fun updateCount(newCount: Int) {
        val target = newCount.coerceAtLeast(1)
        if (target == count) return
        count = target
        maxScroll = (count - 1) * itemSizePx
        interruptSettle()
        scope.launch {
            val coerced = scroll.value.coerceIn(0f, maxScroll)
            raw = coerced
            if (coerced != scroll.value) scroll.snapTo(coerced)
        }
    }

    /**
     * 橡皮筋映射：未阻尼坐标 → 视觉滚动量。界内恒等，越界段按 [OvershootResistance] 折减。
     * 拖拽与滑到头的惯性滑行共用这一条曲线，两段之间位置才连续。
     */
    private fun resist(x: Float): Float = when {
        x < 0f -> x * OvershootResistance
        x > maxScroll -> maxScroll + (x - maxScroll) * OvershootResistance
        else -> x
    }

    /** [resist] 的反解：视觉滚动量 → 未阻尼坐标（打断动画后把 raw 对齐回实际位置用） */
    private fun unresist(x: Float): Float = when {
        x < 0f -> x / OvershootResistance
        x > maxScroll -> maxScroll + (x - maxScroll) / OvershootResistance
        else -> x
    }

    /**
     * 打断进行中的惯性/吸附/回弹动画。惯性期间 raw 不跟随动画（只在 settle 收尾回写），
     * 打断时必须先把 raw 对齐到动画实际位置 scroll.value（界内恒等、越界段反解橡皮筋），
     * 否则后续拖拽以松手时的旧基准叠加增量，snapTo 会把滚轮瞬移回上一次拖拽的起点
     * （惯性中反向滑动失效的根因；回弹中抓轮同理）。
     */
    private fun interruptSettle() {
        val job = settleJob ?: return
        job.cancel()
        settleJob = null
        raw = unresist(scroll.value)
    }

    /** 跟手滚动：正 deltaPx = 手指向下 = 内容下移 = 选中变小；越界只按阻尼比例跟手 */
    fun dragBy(deltaPx: Float) {
        interruptSettle()
        scope.launch {
            scroll.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
            raw -= deltaPx
            scroll.snapTo(resist(raw))
        }
    }

    /**
     * 松手收尾（内部起协程）。低于 [MinimumFlingVelocity] 的松手（拖到头按住不动再松手、
     * 轻放）不进惯性：没有任何可滑行的距离，直接吸附最近整格——越界时这一步就是橡皮筋
     * 回弹本身。惯性不会滑到头时照旧：把界设为合法区间衰减滑行到自然停住，再把最近整格
     * 吸到中心（无过冲弹簧，ui.motion 的 Motion.Settle）。惯性会滑到头（或松手时已在
     * 橡皮筋越界段）时不再撞边截停——原实现衰减动画一越界就被界截停、速度瞬间归零，
     * 越界段松手更是首帧被夹回边界，视觉上都是跳切。改为界全放开，未阻尼坐标 raw 继续
     * 按衰减曲线滑行，视觉位置每帧经 [resist] 回写（与拖拽段同一条阻尼曲线，连续越过
     * 边界后自然减速）；滑到衰减耗尽或视觉过冲达 [BounceOvershootItems] 格，把当时残余
     * 的视觉速度原样交给回位弹簧弹回最近整格（越界时即边界格）——位置、速度全程连续。
     */
    fun settle(initialVelocityPx: Float, decay: DecayAnimationSpec<Float>) {
        interruptSettle()
        settleJob = scope.launch {
            if (abs(initialVelocityPx) < minFlingVelocityPx) {
                // 近零速度松手（拖到头按住不动再松手即属此类）不进入惯性：没有可滑行的
                // 距离，直接吸附最近格——越界时这一步就是橡皮筋回弹本身，不经过衰减动画
                scroll.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
                snapToNearestItem()
                return@launch
            }
            val projected = decay.calculateTargetValue(raw, initialVelocityPx)
            if (scroll.value in 0f..maxScroll && projected in 0f..maxScroll) {
                scroll.updateBounds(0f, maxScroll)
                scroll.animateDecay(initialVelocityPx, decay)
                scroll.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
                snapToNearestItem()
                return@launch
            }
            scroll.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
            val fling = DecayAnimation(decay, Float.VectorConverter, raw, initialVelocityPx)
            val overshootCap = itemSizePx * BounceOvershootItems
            var startNanos = 0L
            var playTime = 0L
            while (true) {
                withFrameNanos { now ->
                    if (startNanos == 0L) startNanos = now
                    playTime = now - startNanos
                }
                playTime = playTime.coerceAtMost(fling.durationNanos)
                raw = fling.getValueFromNanos(playTime)
                // 衰减曲线退化出非法值（不应发生）时立刻终止滑行，保留上一帧位置转入回弹，
                // 绝不把 NaN/Inf 写进滚动量（画布会对 NaN 全部失配，数字整体消失）
                if (!raw.isFinite()) {
                    raw = unresist(scroll.value)
                    break
                }
                scroll.snapTo(resist(raw))
                val overshoot = when {
                    raw < 0f -> -raw
                    raw > maxScroll -> raw - maxScroll
                    else -> 0f
                }
                if (
                    playTime >= fling.durationNanos ||
                    overshoot * OvershootResistance >= overshootCap
                ) break
            }
            // 交接给回位弹簧的视觉速度 = raw 速度 × resist 的斜率（越界段阻尼、界内恒等）
            val slope = if (raw < 0f || raw > maxScroll) OvershootResistance else 1f
            val carried = fling.getVelocityVectorFromNanos(playTime).value * slope
            snapToNearestItem(if (carried.isFinite()) carried else 0f)
        }
    }

    /** 把离中心最近的整格吸到中心：界内惯性滑行与滑到头回弹共用的收尾（Motion.Settle） */
    private suspend fun snapToNearestItem(initialVelocity: Float = scroll.velocity) {
        val target = selectedIndex * itemSizePx
        raw = target
        scroll.animateTo(target, Motion.Settle, initialVelocity = initialVelocity)
    }

    /**
     * 触摸按下：立刻停住惯性/点选动画并同步基准（抓住滚轮）。点选、后续拖拽都从按下
     * 时的实际位置出发——否则惯性中途点按，从按下到抬起滚轮仍在飞，松手时指下的
     * 数字已经变了，点到的不是看见的那格。
     */
    fun press() {
        interruptSettle()
    }

    /** 内容坐标（px，同 [scroll] 坐标系）落在哪一格：四舍五入取最近格，夹回 0..count-1 */
    fun itemIndexAt(contentPx: Float): Int =
        (contentPx / itemSizePx).roundToInt().coerceIn(0, count - 1)

    /** 点选第 [index] 格：从当前位置以 Motion.Settle 吸附到中心（无过冲，与松手吸附同手感） */
    fun select(index: Int) {
        val target = index.coerceIn(0, count - 1) * itemSizePx
        interruptSettle()
        settleJob = scope.launch {
            scroll.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
            raw = target
            scroll.animateTo(target, Motion.Settle)
        }
    }
}

/**
 * 单列数字滚轮：Canvas 自绘（数字按离中心的距离定位、缩放、混色、渐隐），垂直 draggable +
 * 点选（点哪格吸哪格）手势接 [WheelState]。[format] 把索引换成展示文本（年轮加基准年、月/日轮 +1 补零）；
 * [fontSize] 为数字基准字号（邻格按 [AdjacentScale] 比例随它缩放），默认共享规格；
 * [unit] 为数字右缘的单位（时/分、年/月/日）——画在中心数字右侧 [UnitGap] 处、钉在
 * 轮子中线，静态不随滚动位移/缩放（年轮恒 4 位、其余恒 2 位补零，数字宽度恒定，
 * 单位横向位置稳定不抖），null = 无单位。
 */
@Composable
internal fun NumberWheel(
    state: WheelState,
    format: (Int) -> String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = ClassppTextTokens.timeWheelNumber.fontSize,
    unit: String? = null,
) {
    val density = LocalDensity.current
    val decay = remember(density) { splineBasedDecay<Float>(density) }
    val dragState = rememberDraggableState { delta ->
        state.dragBy(delta)
    }
    val textMeasurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sideColor = MaterialTheme.classppColors.secondaryText
    // 数字样式不设色：颜色随离中心的距离在 draw 期逐帧混出（onSurface→secondaryText）。
    // fontFeatureSettings "tnum" = 等宽数字（tabular figures）：1/0 等数字同宽，滚动换值、
    // 中心数字宽度与单位位置都不抖动（字体不支持该特性时自动忽略，无副作用）
    val numberStyle = ClassppTextTokens.timeWheelNumber.copy(fontSize = fontSize)
    // 选中格字重 SemiBold、未选中 Medium：字重离散（两档字形轮廓不同，不像颜色能插值），
    // 跨半格直接切换；单位锚点量宽也用它，与循环里 centerIndex 判据保持一致
    val selectedStyle = numberStyle.copy(fontWeight = FontWeight.SemiBold)
    // 单位样式：静态灰字（secondaryText），不随滚动缩放/渐隐
    val unitStyle = ClassppTextTokens.wheelUnit

    Canvas(
        modifier = modifier
            .size(width = WheelWidth, height = WheelItemHeight * WheelVisibleItems)
            .draggable(
                orientation = Orientation.Vertical,
                state = dragState,
                onDragStopped = { velocity ->
                    // 负号：手指下滑（velocity > 0）→ 内容向下移 → scroll 减小
                    state.settle(-velocity, decay)
                },
            )
            // 点选：按下先抓住滚轮（停住惯性），松手在哪格就把哪格吸到中心。
            // 与 draggable 共存：拖过触摸侦测距离时位移被拖拽消费、点选自动取消，
            // 纯点按才触发 onTap
            .pointerInput(state) {
                detectTapGestures(
                    onPress = { state.press() },
                    onTap = { offset ->
                        val contentPx = state.scroll.value + (offset.y - size.height / 2f)
                        state.select(state.itemIndexAt(contentPx))
                    },
                )
            },
    ) {
        val item = WheelItemHeight.toPx()
        val half = size.height / 2f
        val scrollValue = state.scroll.value
        val lo = floor((scrollValue - half - item) / item).toInt().coerceAtLeast(0)
        val hi = floor((scrollValue + half + item) / item).toInt().coerceAtMost(state.count - 1)
        val centerIndex = (scrollValue / item).roundToInt().coerceIn(0, state.count - 1)
        for (i in lo..hi) {
            // 该数值格中心相对滚轮中心的偏移；d = 离中心的距离（格）
            val y = i * item - scrollValue
            val d = abs(y) / item
            if (d >= FadeOutItems) continue
            val fade = ((d - 1f) / (FadeOutItems - 1f)).coerceIn(0f, 1f)
            val scale = lerp(1f, AdjacentScale, d.coerceAtMost(1f)) * lerp(1f, FadeOutScale, fade)
            val color = lerp(onSurface, sideColor, d.coerceAtMost(1f))
            val layout =
                textMeasurer.measure(format(i), if (i == centerIndex) selectedStyle else numberStyle)
            withTransform({
                translate(size.width / 2f, half + y)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawText(
                    layout,
                    color = color,
                    alpha = 1f - fade,
                    topLeft = Offset(-layout.size.width / 2f, -layout.size.height / 2f),
                )
            }
        }
        // 单位：贴中心数字右缘 [UnitGap]、钉在轮子中线——静态标签，不随滚动位移或缩放；
        // 中心数字恒为选中格（SemiBold），量宽用 selectedStyle 才和画出来的对齐
        unit?.let { unitText ->
            val numberLayout = textMeasurer.measure(format(centerIndex), selectedStyle)
            val unitLayout = textMeasurer.measure(unitText, unitStyle)
            drawText(
                unitLayout,
                color = sideColor,
                topLeft = Offset(
                    size.width / 2f + numberLayout.size.width / 2f + UnitGap.toPx(),
                    half - unitLayout.size.height / 2f,
                ),
            )
        }
    }
}

