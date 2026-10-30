package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

/**
 * 内容（横向滚轮）相对默认位置的上抬量：滚轮画布 128dp 高、数字画在画布正中，
 * 而单位标签钉在数字下方——内容的视觉重心整体偏下（上留白明显大于下留白）。
 * 在本卡内容处整体上抬以平衡上下留白，只影响这张卡，不动 BottomSelectSheet
 * 的共享间距（其他选择卡不受牵连）。想再高/再低就调这个值。
 */
private val ContentLift: Dp = 8.dp

/**
 * 数值选择弹窗（BottomSelectSheet 变体之三）：单列**横向**滚轮，左右滑动选择，
 * 一行可见 5 个数字（中间选中放大变黑、左右渐灰渐小渐隐，视觉与手感同竖向滚轮，
 * 见 [HorizontalNumberWheel]）。「学期总周数」等单一整数的选择场景。
 *
 * [valueRange] 为可选的闭区间数值范围（滚轮格数 = 区间长度，范围越界初值收进界内）；
 * [onConfirm]（确认胶囊）回调当前吸附的数值。滚轮在弹窗保持组合期间记住上次选择，
 * 移出组合（[onDismissed] 之后）重建时回到 [initialValue]。其余行为（两段式关闭、
 * 遮罩、卡片磨砂 [hazeState]、阴影参数 [shadowAlpha]）同 [BottomSelectSheet]。
 */
@Composable
fun BottomNumberSelectCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String,
    valueRange: IntRange,
    initialValue: Int = valueRange.first,
    shadowAlpha: Float = 0.3f,
    hazeState: HazeState? = null,
    // 可选单位标签（如「周」）：钉在中心数字下方；设计稿的选择周数不带单位，默认 null
    unit: String? = null,
    onConfirm: (value: Int) -> Unit,
    onDismissed: () -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val state = remember(density, valueRange) {
        WheelState(
            count = valueRange.count(),
            initialIndex = (initialValue - valueRange.first).coerceIn(0, valueRange.count() - 1),
            density = density,
            scope = scope,
            itemSize = WheelItemWidth,
        )
    }

    BottomSelectSheet(
        title = title,
        visible = visible,
        onDismiss = onDismiss,
        onDismissed = onDismissed,
        shadowAlpha = shadowAlpha,
        hazeState = hazeState,
        modifier = modifier,
        onConfirm = { onConfirm(valueRange.first + state.selectedIndex) },
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            HorizontalNumberWheel(
                state = state,
                format = { (valueRange.first + it).toString() },
                unit = unit,
                // 上抬见 [ContentLift]：offset 只挪绘制与触控区，不改布局占位（卡高不变）
                modifier = Modifier.offset(y = -ContentLift),
            )
        }
    }
}
