package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.ui.theme.RowShape
import com.fangyi.classpp.ui.theme.classppTextStyles
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 一天最后一分钟（23:59），滑块右端对应的时间 */
private const val DAY_LAST_MINUTE = 1439

/** 滑块区域总高与时间胶囊高（胶囊在区域内垂直居中） */
private val SliderHeight = 46.dp
private val HandleHeight = 38.dp

/** 时间胶囊水平内距：宽度随「8:00」/「18:00」字数自适应 */
private val HandleHorizontalPadding = 12.dp

/** 点状轨道：点直径与点间距 */
private val DotDiameter = 3.dp
private val DotGap = 6.dp

/**
 * 时间范围滑块：点状轨道上两个可拖动的时间胶囊（开始/结束），范围 0:00–23:59。
 *
 * - 拖动：胶囊跟随手指，位置与分钟一一对应（全天 [DAY_LAST_MINUTE] 分钟均分轨道行程）；
 * - 点按：单独点某个胶囊弹钟表 TimePicker 精确设置（与设置页「第 N 节」同一交互）；
 * - 顺序约束：开始时间恒早于结束时间（最小间隔 1 分钟）；
 * - 位置约束：两胶囊不重叠、最多贴在一起——时间差最小 1 分钟（约 0.2px）远小于胶囊宽度，
 *   位置上必然相撞，故重叠时两胶囊各从自己的时间位置向内让开重叠量的一半（只让位置、
 *   不改时间），时间拉开后各自回到时间位置；轨道两端挤不下时（如 0:01、23:59 附近）
 *   由越界一侧让位、另一侧贴合补足。
 *
 * 让位量只由当前时间对解出、不记「最近动过哪个胶囊」，位置是 (startMinutes, endMinutes)
 * 的连续函数：换手去拖另一个胶囊时布局结果不变，拖动从指尖处直接继续，不会整体跳开。
 *
 * 位置解算放在自定义 [Layout] 的同一次测量里：先实测两个胶囊宽度再算位置、直接摆放，
 * 不经状态回写，首帧即正确（预览单帧渲染也不会叠在原点）。手势换算另用尺寸快照
 * （拖动必然发生在布局稳定后，首帧为 0 无碍）。
 *
 * 无状态受控组件：分钟数由调用方持有，每次变化经 [onRangeChange] 整体提交。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeSlider(
    startMinutes: Int,
    endMinutes: Int,
    onRangeChange: (startMinutes: Int, endMinutes: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var trackWidthPx by remember { mutableIntStateOf(0) }
    // 正在通过弹窗自定义时间的胶囊（null = 无）
    var editingIsStart by remember { mutableStateOf<Boolean?>(null) }

    Layout(
        content = {
            // 顺序即上方 measurables 顺序：开始在前、结束在后
            TimeHandle(
                minutes = startMinutes,
                minMinutes = 0,
                maxMinutes = endMinutes - 1,
                trackWidthPx = trackWidthPx,
                onChange = { newStart -> onRangeChange(newStart, endMinutes) },
                onTap = { editingIsStart = true },
            )
            TimeHandle(
                minutes = endMinutes,
                minMinutes = startMinutes + 1,
                maxMinutes = DAY_LAST_MINUTE,
                trackWidthPx = trackWidthPx,
                onChange = { newEnd -> onRangeChange(startMinutes, newEnd) },
                onTap = { editingIsStart = false },
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .height(SliderHeight)
            .onSizeChanged { trackWidthPx = it.width }
            .drawBehind {
                // 点状轨道：整排点在轨道上水平居中、垂直居中
                val step = DotDiameter.toPx() + DotGap.toPx()
                val count = (size.width / step).toInt() + 1
                val totalWidth = (count - 1) * step
                val radius = DotDiameter.toPx() / 2f
                var x = (size.width - totalWidth) / 2f + radius
                repeat(count) {
                    drawCircle(colors.outline, radius, Offset(x, size.height / 2f))
                    x += step
                }
            },
    ) { measurables, constraints ->
        val trackW = constraints.maxWidth.toFloat()
        // 胶囊宽度自适应文字：松约束实测宽度后，同一次布局里解算位置
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val startPlaceable = measurables[0].measure(loose)
        val endPlaceable = measurables[1].measure(loose)
        val startW = startPlaceable.width.toFloat()
        val endW = endPlaceable.width.toFloat()
        // 时间 → 胶囊左缘：0:00 贴轨道左端、23:59 右缘贴轨道右端
        val rawStartX = startMinutes / DAY_LAST_MINUTE.toFloat() * (trackW - startW)
        val rawEndX = endMinutes / DAY_LAST_MINUTE.toFloat() * (trackW - endW)
        // 时间位置相撞时两胶囊各让一半（只让位置、不改时间），让位量随时间对连续增减
        val overlap = max(0f, rawStartX + startW - rawEndX)
        // 全程只做 min/max 夹紧：位置是时间的连续函数，任何拖动都不会产生位置突变
        var startX = max(0f, rawStartX - overlap / 2f)
        var endX = min((trackW - endW).coerceAtLeast(0f), rawEndX + overlap / 2f)
        // 轨道两端挤不下时（如 0:01/0:02、23:58/23:59）让位：先收回越界的左钮，仍不够再把右钮推回
        if (endX - startX < startW) startX = max(0f, endX - startW)
        if (endX - startX < startW) endX = min((trackW - endW).coerceAtLeast(0f), startX + startW)

        layout(constraints.maxWidth, constraints.maxHeight) {
            startPlaceable.placeRelative(
                startX.roundToInt(),
                (constraints.maxHeight - startPlaceable.height) / 2,
            )
            endPlaceable.placeRelative(
                endX.roundToInt(),
                (constraints.maxHeight - endPlaceable.height) / 2,
            )
        }
    }

    // 点按胶囊 → 钟表弹窗精确设置（TimePicker 结构性杜绝格式错误）；确认时同样夹紧先后顺序
    editingIsStart?.let { isStart ->
        val initial = if (isStart) startMinutes else endMinutes
        val timeState = rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
        )
        AlertDialog(
            onDismissRequest = { editingIsStart = null },
            title = {
                Text(stringResource(if (isStart) R.string.time_range_start else R.string.time_range_end))
            },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val picked = timeState.hour * 60 + timeState.minute
                    if (isStart) {
                        onRangeChange(picked.coerceAtMost(endMinutes - 1).coerceAtLeast(0), endMinutes)
                    } else {
                        onRangeChange(
                            startMinutes,
                            picked.coerceAtLeast(startMinutes + 1).coerceAtMost(DAY_LAST_MINUTE),
                        )
                    }
                    editingIsStart = null
                }) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingIsStart = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/**
 * 单个时间胶囊：灰底蓝字（tnum 等宽数字，拖动中数字位宽稳定不抖），可点按、可横向拖动。
 * 摆放位置由 [TimeRangeSlider] 的 Layout 解算，这里只负责自身尺寸与手势。
 */
@Composable
private fun TimeHandle(
    minutes: Int,
    minMinutes: Int,
    maxMinutes: Int,
    trackWidthPx: Int,
    onChange: (Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var widthPx by remember { mutableIntStateOf(0) }
    // pointerInput(Unit) 的协程只在首次组合启动，闭包内一律经 rememberUpdatedState 读最新值，
    // 避免把过期参数捕获进去、也避免拖动中途因 key 变化重启手势
    val currentMinutes by rememberUpdatedState(minutes)
    val currentMin by rememberUpdatedState(minMinutes)
    val currentMax by rememberUpdatedState(maxMinutes)
    val currentTrackWidth by rememberUpdatedState(trackWidthPx)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnTap by rememberUpdatedState(onTap)

    Box(
        modifier = modifier
            .height(HandleHeight)
            .clip(RowShape)
            .background(colors.background)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                detectTapGestures { currentOnTap() }
            }
            .pointerInput(Unit) {
                // 拖动量先累积、凑满 1 分钟才消费：全轨 1439 分钟、每分钟不足 1px，
                // 单帧增量直接取整会整帧丢失
                var pendingPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { pendingPx = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val travel = currentTrackWidth - widthPx
                        if (travel <= 0f) return@detectHorizontalDragGestures
                        val pxPerMinute = travel / DAY_LAST_MINUTE
                        pendingPx += dragAmount
                        val deltaMinutes = (pendingPx / pxPerMinute).roundToInt()
                        if (deltaMinutes != 0) {
                            pendingPx -= deltaMinutes * pxPerMinute
                            currentOnChange(
                                (currentMinutes + deltaMinutes).coerceIn(currentMin, currentMax),
                            )
                        }
                    },
                )
            }
            .padding(horizontal = HandleHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = TimeText.format(minutes),
            style = MaterialTheme.classppTextStyles.fieldLabel.copy(fontFeatureSettings = "tnum"),
            color = colors.primary,
        )
    }
}
