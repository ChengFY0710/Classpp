package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R
import dev.chrisbanes.haze.HazeState

/** 时/分两位补零显示（08 时 40 分） */
private fun pad2(value: Int): String = value.toString().padStart(2, '0')

/**
 * 时间选择弹窗（BottomSelectSheet 的第一个变体）：时/分两列滚轮，24 小时制、不循环，
 * 上下滑动选择，补零显示（08 时 40 分）。
 *
 * 滚轮视觉与手感收在共享积木 SelectWheel.kt（同包）：跟手 → 松手惯性滑行 → 吸附最近
 * 整格（ui.motion 的 Motion.Settle，无过冲），越界橡皮筋阻尼；选中数字 onSurface 全尺寸，
 * 邻格混向 classppColors.secondaryText、缩小并渐隐，滚动期零重组。
 *
 * [onConfirm]（确认胶囊）回调当前吸附最近的两格值；滚轮在弹窗保持组合期间记住上次
 * 选择，移出组合（[onDismissed] 之后）重建时回到 [initialHour] / [initialMinute]。
 * 其余行为（两段式关闭、遮罩、卡片磨砂 [hazeState]、阴影参数 [shadowAlpha]）同
 * [BottomSelectSheet]。
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
    hazeState: HazeState? = null,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismissed: () -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val hourState = remember(density) {
        WheelState(count = 24, initialIndex = initialHour, density = density, scope = scope)
    }
    val minuteState = remember(density) {
        WheelState(count = 60, initialIndex = initialMinute, density = density, scope = scope)
    }

    BottomSelectSheet(
        title = title,
        visible = visible,
        onDismiss = onDismiss,
        onDismissed = onDismissed,
        shadowAlpha = shadowAlpha,
        hazeState = hazeState,
        modifier = modifier,
        onConfirm = { onConfirm(hourState.selectedIndex, minuteState.selectedIndex) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberWheel(
                state = hourState,
                format = ::pad2,
                modifier = Modifier.weight(1f),
                unit = stringResource(R.string.time_unit_hour),
            )
            NumberWheel(
                state = minuteState,
                format = ::pad2,
                modifier = Modifier.weight(1f),
                unit = stringResource(R.string.time_unit_minute),
            )
        }
    }
}
