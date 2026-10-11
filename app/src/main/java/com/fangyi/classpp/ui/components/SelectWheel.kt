package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.fangyi.classpp.ui.motion.Motion
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

/** 滚轮数字字号（对齐设计稿量得的中格大小） */
private val WheelNumberFontSize = 36.sp

/** 单位（时/分、年/月/日）字号（横向轮的单位标签同样取这一档） */
internal val WheelUnitFontSize = 18.sp

/** 数字右缘到单位的间距 */
private val UnitGap: Dp = 10.dp

/**
 * 数字滚轮积木（BottomTimeSelectCard / BottomDateSelectCard 共用）：垂直 draggable 的
 * 自绘选择轮。选中数字 onSurface 全尺寸；离中心越远逐帧混向 classppColors.secondaryText、
 * 缩小（邻格 [AdjacentScale] 倍）并渐隐（[FadeOutItems] 格处完全消失，早于视口裁剪边），
 * 数字跟手移动时上下格连续插值——上面的渐隐缩小消失、下面的渐隐放大出现。
 * 滚动期零重组：位移、颜色、透明度都是 draw 期从滚动量算出的纯函数（常驻组合零重组哲学）。
 * 视觉与手感规格收在文件顶部常量，改一处全滚轮生效。
 */

/**
 * 单个滚轮的状态：[scroll] 是内容滚动量（px，数值 i 画在 `i × 格距 − scroll` 的偏移处，
 * 正 = 内容下移 = 选中更小的值）。拖拽、惯性、吸附全部收在这里，确认按钮随时可读
 * [selectedIndex]（跟手/吸附过渡中取最近的整格）。
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
        settleJob?.cancel()
        settleJob = null
        scope.launch {
            val coerced = scroll.value.coerceIn(0f, maxScroll)
            raw = raw.coerceIn(0f, maxScroll)
            if (coerced != scroll.value) scroll.snapTo(coerced)
        }
    }

    /** 跟手滚动：正 deltaPx = 手指向下 = 内容下移 = 选中变小；越界只按阻尼比例跟手 */
    fun dragBy(deltaPx: Float) {
        settleJob?.cancel()
        settleJob = null
        scope.launch {
            scroll.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
            raw -= deltaPx
            val resisted = when {
                raw < 0f -> raw * OvershootResistance
                raw > maxScroll -> maxScroll + (raw - maxScroll) * OvershootResistance
                else -> raw
            }
            scroll.snapTo(resisted)
        }
    }

    /**
     * 松手收尾（内部起协程）：惯性滑行期间把界设为合法区间，滑出边界动画即自动停在
     * 边界；随后放开边界，把最近的整格吸到中心（无过冲弹簧，ui.motion 的 Motion.Settle）
     */
    fun settle(initialVelocityPx: Float, decay: DecayAnimationSpec<Float>) {
        settleJob?.cancel()
        settleJob = scope.launch {
            scroll.updateBounds(0f, maxScroll)
            scroll.animateDecay(initialVelocityPx, decay)
            scroll.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
            val target =
                (scroll.value / itemSizePx).roundToInt().coerceIn(0, count - 1) * itemSizePx
            raw = target
            scroll.animateTo(target, Motion.Settle)
        }
    }
}

/**
 * 单列数字滚轮：Canvas 自绘（数字按离中心的距离定位、缩放、混色、渐隐），垂直 draggable
 * 手势接 [WheelState]。[format] 把索引换成展示文本（年轮加基准年、月/日轮 +1 补零）；
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
    fontSize: TextUnit = WheelNumberFontSize,
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
    val numberStyle = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    // 单位样式：静态灰字（secondaryText），不随滚动缩放/渐隐
    val unitStyle = TextStyle(fontSize = WheelUnitFontSize, fontWeight = FontWeight.Medium)

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
            ),
    ) {
        val item = WheelItemHeight.toPx()
        val half = size.height / 2f
        val scrollValue = state.scroll.value
        val lo = floor((scrollValue - half - item) / item).toInt().coerceAtLeast(0)
        val hi = floor((scrollValue + half + item) / item).toInt().coerceAtMost(state.count - 1)
        for (i in lo..hi) {
            // 该数值格中心相对滚轮中心的偏移；d = 离中心的距离（格）
            val y = i * item - scrollValue
            val d = abs(y) / item
            if (d >= FadeOutItems) continue
            val fade = ((d - 1f) / (FadeOutItems - 1f)).coerceIn(0f, 1f)
            val scale = lerp(1f, AdjacentScale, d.coerceAtMost(1f)) * lerp(1f, FadeOutScale, fade)
            val color = lerp(onSurface, sideColor, d.coerceAtMost(1f))
            val layout = textMeasurer.measure(format(i), numberStyle)
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
        // 单位：贴中心数字右缘 [UnitGap]、钉在轮子中线——静态标签，不随滚动位移或缩放
        unit?.let { unitText ->
            val centerIndex = (scrollValue / item).roundToInt().coerceIn(0, state.count - 1)
            val numberLayout = textMeasurer.measure(format(centerIndex), numberStyle)
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

