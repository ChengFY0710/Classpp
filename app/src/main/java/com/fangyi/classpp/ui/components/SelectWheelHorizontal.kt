package com.fangyi.classpp.ui.components

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.fangyi.classpp.ui.theme.classppColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** 横向滚轮单格宽度 = 相邻两个数值的中心间距 */
internal val WheelItemWidth: Dp = 64.dp

/** 横向滚轮可见格数：中间选中 + 左右各两格（对齐设计稿一行 5 个数字） */
private const val HorizontalVisibleItems = 5

/** 横向滚轮的行高（画布高度，数字带上下留白） */
private val HorizontalWheelHeight = 128.dp

/** 横向滚轮数字字号（单轮独占一行，比竖向共享默认 36sp 大一档） */
private val HorizontalWheelFontSize = 40.sp

/** 横向渐隐结束的距离（格）：设计稿最外格（±2）仍隐约可见，尾巴拉到 2.4（早于视口裁剪边 2.5 格） */
private const val HorizontalFadeOutItems = 2.4f

/** 单位（若传）距中心数字下缘的间距 */
private val UnitGapBelow: Dp = 8.dp

/**
 * 横向数字滚轮积木（[BottomNumberSelectCard] 宿主）：水平 draggable + 点选（点哪格吸哪格）
 * 的自绘选择轮，竖向 [NumberWheel] 的横向孪生——滚动状态直接复用 [WheelState]（传格宽
 * itemSize），拖拽跟手、惯性滑行、Motion.Settle 吸附、点选吸附、越界橡皮筋与竖向完全同一份代码；
 * 字重/缩放/混色/渐隐公式逐条照搬（选中格 SemiBold、未选中 Medium，跨半格切换），
 * 仅坐标轴换成 x（可见格数与渐隐尾按设计稿放宽到 ±2 格）。
 * 符号约定同竖向：往左滑 = 内容左移 = 选中更大的值（数字随手指方向滚动）。
 * 滚动期零重组：位移、颜色、透明度都是 draw 期从滚动量算出的纯函数。
 * [unit] 为可选单位标签：钉在中线数字下方静态绘制，不随滚动位移/缩放，null = 无单位。
 */
@Composable
internal fun HorizontalNumberWheel(
    state: WheelState,
    format: (Int) -> String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = HorizontalWheelFontSize,
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
    // 数字样式不设色：颜色随离中心的距离在 draw 期逐帧混出（onSurface→secondaryText）；
    // "tnum" 等宽数字同竖向（字体不支持该特性时自动忽略，无副作用）
    val numberStyle = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    // 选中格字重 SemiBold、未选中 Medium：字重离散（两档字形轮廓不同，不像颜色能插值），
    // 跨半格直接切换；单位锚点量宽也用它，与循环里 centerIndex 判据保持一致
    val selectedStyle = numberStyle.copy(fontWeight = FontWeight.SemiBold)
    // 单位样式：静态灰字（secondaryText），不随滚动缩放/渐隐
    val unitStyle = TextStyle(fontSize = WheelUnitFontSize, fontWeight = FontWeight.Medium)

    Canvas(
        modifier = modifier
            .size(width = WheelItemWidth * HorizontalVisibleItems, height = HorizontalWheelHeight)
            .draggable(
                orientation = Orientation.Horizontal,
                state = dragState,
                onDragStopped = { velocity ->
                    // 负号同竖向：手指右滑（velocity > 0）→ 内容右移 → scroll 减小
                    state.settle(-velocity, decay)
                },
            )
            // 点选同竖向 [NumberWheel]：按下先抓住滚轮（停住惯性），松手在哪格就把哪格吸到中心；
            // 与 draggable 共存——拖过触摸侦测距离时点选自动取消，纯点按才触发 onTap
            .pointerInput(state) {
                detectTapGestures(
                    onPress = { state.press() },
                    onTap = { offset ->
                        val contentPx = state.scroll.value + (offset.x - size.width / 2f)
                        state.select(state.itemIndexAt(contentPx))
                    },
                )
            },
    ) {
        val item = WheelItemWidth.toPx()
        val half = size.height / 2f
        val halfWidth = size.width / 2f
        val scrollValue = state.scroll.value
        val lo = floor((scrollValue - halfWidth - item) / item).toInt().coerceAtLeast(0)
        val hi = floor((scrollValue + halfWidth + item) / item).toInt().coerceAtMost(state.count - 1)
        val centerIndex = (scrollValue / item).roundToInt().coerceIn(0, state.count - 1)
        for (i in lo..hi) {
            // 该数值格中心相对滚轮中心的偏移；d = 离中心的距离（格）
            val x = i * item - scrollValue
            val d = abs(x) / item
            if (d >= HorizontalFadeOutItems) continue
            val fade = ((d - 1f) / (HorizontalFadeOutItems - 1f)).coerceIn(0f, 1f)
            val scale = lerp(1f, AdjacentScale, d.coerceAtMost(1f)) * lerp(1f, FadeOutScale, fade)
            val color = lerp(onSurface, sideColor, d.coerceAtMost(1f))
            val layout =
                textMeasurer.measure(format(i), if (i == centerIndex) selectedStyle else numberStyle)
            withTransform({
                translate(halfWidth + x, half)
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
        // 单位：钉在中线数字下方 [UnitGapBelow] 处——静态标签，不随滚动位移或缩放；
        // 中心数字恒为选中格（SemiBold），量宽用 selectedStyle 才和画出来的对齐
        unit?.let { unitText ->
            val numberLayout = textMeasurer.measure(format(centerIndex), selectedStyle)
            val unitLayout = textMeasurer.measure(unitText, unitStyle)
            drawText(
                unitLayout,
                color = sideColor,
                topLeft = Offset(
                    halfWidth - unitLayout.size.width / 2f,
                    half + numberLayout.size.height / 2f + UnitGapBelow.toPx(),
                ),
            )
        }
    }
}
