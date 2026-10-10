package com.fangyi.classpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.classppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 轨道高度与滑块尺寸同 [ClassppSwitch]：32 胶囊、24 圆球、4 内边距（行程 = 轨道宽 − 32） */
private val TrackHeight = 32.dp
private val ThumbSize = 24.dp
private val ThumbInset = 4.dp

/** 默认值标记小球直径：明显小于滑块、又能在 32dp 轨道上读得清 */
private val MarkerSize = 8.dp

/** 当前值越过默认值后标记小球的颜色：#FFFFFF 40%（仅本组件私有，不进 theme） */
private val MarkerPassedColor = Color(0x66FFFFFF)

/**
 * 滑动选择条：胶囊轨道 + 与 [ClassppSwitch] 同尺寸的白色圆球，整条轨道任意位置按住
 * 拖动即跟手连续取值（[valueRange] 内任意 Float，无档位），宽度由调用方 modifier 决定
 * （需给定宽度，推荐 `Modifier.fillMaxWidth()`）。
 *
 * 颜色严格取自 theme：已填充轨道 = [MaterialTheme.colorScheme.primary]，未填充轨道 =
 * [MaterialTheme.colorScheme.onPrimaryContainer]，默认值标记小球未越过默认值时 =
 * [MaterialTheme.classppColors.negative]，当前值越过默认值后 = 白色 40%（组件私有
 * [MarkerPassedColor]）；圆球与开关一致，任何模式恒白。
 *
 * 形状与全局胶囊同源：轨道走 [PillShape] 连续曲率圆角，圆球与标记小球为纯圆（CircleShape）。
 *
 * 吸附与震动：[defaultValue] 处有灰色小球标记，圆球中心拖入 [snapThreshold] 半径即吸附到
 * 默认值并触发一次震动（HapticFeedback，无需权限）；已吸附需拖出 1.5 倍半径才脱离
 * （迟滞防抖，边界不来回跳），松手时若停在吸附区内会以 Motion.FastMillis 节奏补动画归位。
 *
 * 受控组件：[onValueChange] 只回吐新值，[value] 状态由调用方持有。
 */
@Composable
fun ClassppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    defaultValue: Float,
    snapThreshold: Dp = 10.dp,
) {
    val trackColor = MaterialTheme.colorScheme.onPrimaryContainer
    val fillColor = MaterialTheme.colorScheme.primary
    // 标记小球：未越过默认值 = negative；越过之后 = 白色 40%，与开关轨道变色同一节奏
    val markerColor by animateColorAsState(
        targetValue = if (value > defaultValue) MarkerPassedColor
        else MaterialTheme.classppColors.negative,
        animationSpec = tween(Motion.FastMillis, easing = Motion.Decelerate),
        label = "classppSliderMarker",
    )
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 轨道实测宽度（px）：布局侧换算圆球行程用；手势侧直接读 pointerInput 的 size
    var trackWidthPx by remember { mutableStateOf(0) }

    // pointerInput(Unit) 的协程只在首次组合启动，闭包内一律经 rememberUpdatedState 读最新值
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentRange by rememberUpdatedState(valueRange)
    val currentDefault by rememberUpdatedState(defaultValue)
    val currentSnapThreshold by rememberUpdatedState(snapThreshold)
    val currentHaptics by rememberUpdatedState(haptics)

    val thumbInsetPx = with(density) { ThumbInset.toPx() }
    val thumbSizePx = with(density) { ThumbSize.toPx() }
    val markerSizePx = with(density) { MarkerSize.toPx() }
    val travelPx = (trackWidthPx - thumbInsetPx * 2 - thumbSizePx).coerceAtLeast(0f)

    val range = valueRange.endInclusive - valueRange.start
    val progress =
        if (range > 0f) ((value - valueRange.start) / range).coerceIn(0f, 1f) else 0f
    val defaultProgress =
        if (range > 0f) ((defaultValue - valueRange.start) / range).coerceIn(0f, 1f) else 0f
    val thumbOffset: Dp = with(density) { (progress * travelPx).toDp() }
    val markerX: Dp = with(density) {
        (thumbInsetPx + defaultProgress * travelPx + thumbSizePx / 2 - markerSizePx / 2).toDp()
    }

    Box(
        modifier = modifier
            .height(TrackHeight)
            .onSizeChanged { trackWidthPx = it.width }
            .clip(PillShape)
            .background(trackColor)
            .pointerInput(Unit) {
                // 拖动手势内的持久局部：吸附态与手指位置不参与组合
                var snapped = false
                var freeValue = 0f
                var settleJob: Job? = null

                // 手指位置（未吸附时的连续值）到默认值标记的中心距（px）
                fun distanceToDefaultPx(v: Float): Float {
                    val r = currentRange.endInclusive - currentRange.start
                    if (r <= 0f) return Float.MAX_VALUE
                    val travel = (size.width - thumbInsetPx * 2 - thumbSizePx).coerceAtLeast(1f)
                    val p = ((v - currentRange.start) / r).coerceIn(0f, 1f)
                    val dp = ((currentDefault - currentRange.start) / r).coerceIn(0f, 1f)
                    val center = thumbInsetPx + p * travel + thumbSizePx / 2
                    val defaultCenter = thumbInsetPx + dp * travel + thumbSizePx / 2
                    return abs(center - defaultCenter)
                }

                detectHorizontalDragGestures(
                    onDragStart = {
                        settleJob?.cancel()
                        settleJob = null
                        freeValue = currentValue
                        // 起手恰好在默认值上：视为已在吸附态，起步不再重复震动
                        snapped = currentValue == currentDefault
                    },
                    onDragEnd = {
                        // 松手仍在吸附区内（外部置值等路径的兜底）：动画归位、到位补一次震动
                        if (!snapped &&
                            distanceToDefaultPx(currentValue) <= currentSnapThreshold.toPx() &&
                            currentValue != currentDefault
                        ) {
                            settleJob = scope.launch {
                                animate(
                                    initialValue = currentValue,
                                    targetValue = currentDefault,
                                    animationSpec = tween(
                                        Motion.FastMillis,
                                        easing = Motion.Decelerate,
                                    ),
                                ) { v, _ -> currentOnValueChange(v) }
                                currentHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    },
                    onDragCancel = {
                        settleJob?.cancel()
                        settleJob = null
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val r = currentRange.endInclusive - currentRange.start
                        if (r <= 0f) return@detectHorizontalDragGestures
                        val travel = (size.width - thumbInsetPx * 2 - thumbSizePx)
                            .coerceAtLeast(1f)
                        freeValue = (freeValue + dragAmount / travel * r)
                            .coerceIn(currentRange.start, currentRange.endInclusive)
                        val snapPx = currentSnapThreshold.toPx()
                        val dist = distanceToDefaultPx(freeValue)
                        var next = freeValue
                        if (snapped) {
                            // 迟滞：拖出 1.5 倍半径才脱离，避免边界抖动反复吸附震动
                            if (dist > snapPx * 1.5f) snapped = false else next = currentDefault
                        }
                        if (!snapped && dist <= snapPx) {
                            snapped = true
                            next = currentDefault
                            currentHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        if (next != currentValue) currentOnValueChange(next)
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 填充层：胶囊右端的半圆帽圆心恰与圆球球心重合（右缘 = 球心 + TrackHeight/2，
        // 即比球右缘多出一个 ThumbInset），PillShape 圆帽均匀包住圆球一圈，与设计图一致
        Box(
            modifier = Modifier
                .width(thumbOffset + TrackHeight)
                .fillMaxHeight()
                .clip(PillShape)
                .background(fillColor),
        )
        // 默认值标记：压在填充层上、垫在圆球下，吸附时被圆球自然遮住
        Box(
            modifier = Modifier
                .offset(x = markerX)
                .size(MarkerSize)
                .background(markerColor, CircleShape),
        )
        // 白色圆球：与 ClassppSwitch 同写法，offset 只负责水平行程，垂直由父容器居中
        Box(
            modifier = Modifier
                .padding(start = ThumbInset)
                .offset(x = thumbOffset)
                .size(ThumbSize)
                .background(Color.White, CircleShape),
        )
    }
}
