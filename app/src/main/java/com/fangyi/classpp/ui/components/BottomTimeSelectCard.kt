package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.fangyi.classpp.R
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
private const val AdjacentScale = 0.62f

/** 渐隐结束的距离（格）：1 = 邻格，到它时 alpha 归零（早于视口裁剪边 1.5 格） */
private const val FadeOutItems = 1.4f

/** 渐隐段里数字额外缩到多少（邻格基础上再缩） */
private const val FadeOutScale = 0.8f

/** 拖出首尾边界的阻尼：越界位移只按此比例跟手（橡皮筋，不会越拉越远） */
private const val OvershootResistance = 0.25f

/** 滚轮数字字号（对齐设计稿量得的中格大小） */
private val WheelNumberFontSize = 48.sp

/** 单位（时/分）字号 */
private val WheelUnitFontSize = 18.sp

/**
 * 单个滚轮的状态：[scroll] 是内容滚动量（px，数值 i 画在 `i × 格高 − scroll` 的偏移处，
 * 正 = 内容下移 = 选中更小的值）。拖拽、惯性、吸附全部收在这里，确认按钮随时可读
 * [selectedIndex]。
 */
private class WheelState(
    val count: Int,
    initialIndex: Int,
    private val itemHeightPx: Float,
    private val scope: CoroutineScope,
) {
    private val maxScroll = (count - 1) * itemHeightPx

    // 未阻尼的滚动量：越界段的橡皮筋映射以它为输入，避免逐帧折叠阻尼导致回拉
    private var raw = initialIndex.coerceIn(0, count - 1) * itemHeightPx

    // 松手的惯性+吸附动画：再次拖拽要先取消它并放开边界，否则 snapTo 会被界夹住
    private var settleJob: Job? = null

    val scroll = Animatable(raw)

    /** 当前选中值（0..count-1）：跟手/吸附过渡中取最近的整格 */
    val selectedIndex: Int
        get() = (scroll.value / itemHeightPx).roundToInt().coerceIn(0, count - 1)

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
            val target = (scroll.value / itemHeightPx).roundToInt()
                .coerceIn(0, count - 1) * itemHeightPx
            raw = target
            scroll.animateTo(target, Motion.Settle)
        }
    }
}

/**
 * 时间选择弹窗（BottomSelectSheet 的第一个变体）：时/分两列滚轮，24 小时制、不循环，
 * 上下滑动选择，补零显示（08 时 40 分）。
 *
 * 滚动手感：跟手 → 松手惯性滑行（splineBasedDecay）→ 吸附最近整格（ui.motion 的
 * [Motion.Settle]，无过冲）；拖出首尾边界带橡皮筋阻尼（见 [OvershootResistance]），
 * 不会出现 23→0 的回绕。
 *
 * 滚轮视觉：选中数字 onSurface 全尺寸；离中心越远逐帧混向 classppColors.secondaryText、
 * 缩小（邻格 [AdjacentScale] 倍）并渐隐（[FadeOutItems] 格处完全消失），数字跟手移动时
 * 上下格连续插值——上面的渐隐缩小消失、下面的渐隐放大出现。滚动期零重组：位移、颜色、
 * 透明度都是 draw 期从滚动量算出的纯函数（同 ui/motion 的常驻组合零重组哲学）。
 *
 * [onConfirm]（确认胶囊）回调当前吸附最近的两格值；滚轮在弹窗保持组合期间记住上次
 * 选择，移出组合（[onDismissed] 之后）重建时回到 [initialHour] / [initialMinute]。
 * 其余行为（两段式关闭、遮罩、阴影参数 [shadowAlpha]）同 [BottomSelectSheet]。
 */
@Composable
fun BottomTimeSelectCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.time_select_title),
    initialHour: Int = 8,
    initialMinute: Int = 40,
    shadowAlpha: Float = 0.3f,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismissed: () -> Unit = {},
) {
    val density = LocalDensity.current
    val itemHeightPx = with(density) { WheelItemHeight.toPx() }
    val scope = rememberCoroutineScope()
    val hourState = remember(itemHeightPx) {
        WheelState(
            count = 24,
            initialIndex = initialHour,
            itemHeightPx = itemHeightPx,
            scope = scope,
        )
    }
    val minuteState = remember(itemHeightPx) {
        WheelState(
            count = 60,
            initialIndex = initialMinute,
            itemHeightPx = itemHeightPx,
            scope = scope,
        )
    }

    BottomSelectSheet(
        title = title,
        visible = visible,
        onDismiss = onDismiss,
        onDismissed = onDismissed,
        shadowAlpha = shadowAlpha,
        modifier = modifier,
        onConfirm = { onConfirm(hourState.selectedIndex, minuteState.selectedIndex) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimeWheelGroup(
                state = hourState,
                unit = stringResource(R.string.time_unit_hour),
                modifier = Modifier.weight(1f),
            )
            TimeWheelGroup(
                state = minuteState,
                unit = stringResource(R.string.time_unit_minute),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 滚轮组：滚轮 + 右侧单位（时/分），单位与中间选中数字垂直居中对齐 */
@Composable
private fun TimeWheelGroup(
    state: WheelState,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        NumberWheel(state = state)
        Text(
            text = unit,
            style = TextStyle(fontSize = WheelUnitFontSize, fontWeight = FontWeight.Medium),
            color = MaterialTheme.classppColors.secondaryText,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * 单列数字滚轮：Canvas 自绘（数字按离中心的距离定位、缩放、混色、渐隐），垂直 draggable
 * 手势接 [WheelState]。滚动量只在 draw 期读取，滚动过程零重组。
 */
@Composable
private fun NumberWheel(
    state: WheelState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val decay = remember(density) { splineBasedDecay<Float>(density) }
    val dragState = rememberDraggableState { delta ->
        state.dragBy(delta)
    }
    val textMeasurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sideColor = MaterialTheme.classppColors.secondaryText
    // 数字样式不设色：颜色随离中心的距离在 draw 期逐帧混出（onSurface→secondaryText）
    val numberStyle = TextStyle(fontSize = WheelNumberFontSize, fontWeight = FontWeight.Medium)

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
            val layout = textMeasurer.measure(i.toString().padStart(2, '0'), numberStyle)
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
    }
}
