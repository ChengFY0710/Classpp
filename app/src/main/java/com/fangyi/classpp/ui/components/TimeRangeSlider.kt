package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.RowShape
import com.fangyi.classpp.ui.theme.classppTextStyles
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 一天最后一分钟（23:59），滑块右端对应的时间；组件、几何解算与单测共用同一常量 */
internal const val TimeRangeLastMinute = 1439

/** 滑块区域总高与时间胶囊高（胶囊在区域内垂直居中） */
private val SliderHeight = 46.dp
private val HandleHeight = 38.dp

/** 时间胶囊水平内距：宽度随「8:00」/「18:00」字数自适应 */
private val HandleHorizontalPadding = 12.dp

/** 点状轨道：点直径与点间距 */
private val DotDiameter = 3.dp
private val DotGap = 6.dp

/**
 * 局部斜率低于标称值这个比例时视为「位置被按死」：两胶囊贴在同一端、谁也动不了
 * （见 [timeRangeHandleXs] 的两次让位修正），拖动换算退回标称斜率（见
 * [timeRangeDragPxPerMinute]）。贴靠段斜率恰好是标称值的一半，必须留在这条线之上。
 */
private const val PinnedSlopeFraction = 0.4f

/** 拖动换算的斜率下限（px/分钟）：只在轨道窄到没有行程（几何退化）时兜底，避免除零 */
private const val MinDragPxPerMinute = 0.01f

/** 轨道行程（px）：0:00 贴左端、23:59 贴右端，两端各让出胶囊自身宽度 */
internal fun timeRangeTravelPx(trackWidthPx: Float, handleWidthPx: Float): Float =
    (trackWidthPx - handleWidthPx).coerceAtLeast(0f)

/** 单个胶囊「不算让位」时的左缘（px）：时间在轨道行程上按比例落位 */
private fun timeRangeIdealLeftPx(
    minutes: Int,
    trackWidthPx: Float,
    handleWidthPx: Float,
): Float = minutes / TimeRangeLastMinute.toFloat() * timeRangeTravelPx(trackWidthPx, handleWidthPx)

/** 两胶囊解算后的左缘位置（px）：[Layout] 摆放与手势命中/换算共用同一份解算 */
internal data class TimeRangeHandleXs(val startX: Float, val endX: Float)

/**
 * 时间对 → 两胶囊左缘（px），纯函数、可单测：
 *
 * - 0:00 贴轨道左端、23:59 右缘贴轨道右端，中间线性落位（每分钟走的行程相同）；
 * - 时间位置相撞时（时间差小到胶囊会重叠）两胶囊各让开重叠量的一半，让位量随时间对连续
 *   增减，只让位置、不改时间；
 * - 全程只有 min/max 夹紧，位置是 (startMinutes, endMinutes) 的连续函数，任何拖动都不会
 *   产生位置突变；
 * - 轨道两端挤不下时（如 0:01/0:02、23:58/23:59）先收回越界的左钮，仍不够再把右钮推回；
 *   极端窄轨道（两胶囊宽度之和超过轨道）放不下属几何退化，此时两胶囊重叠，命中测试以左钮为准。
 */
internal fun timeRangeHandleXs(
    trackWidthPx: Float,
    startWidthPx: Float,
    endWidthPx: Float,
    startMinutes: Int,
    endMinutes: Int,
): TimeRangeHandleXs {
    val rawStartX = timeRangeIdealLeftPx(startMinutes, trackWidthPx, startWidthPx)
    val rawEndX = timeRangeIdealLeftPx(endMinutes, trackWidthPx, endWidthPx)
    val maxEndX = timeRangeTravelPx(trackWidthPx, endWidthPx)
    val overlap = max(0f, rawStartX + startWidthPx - rawEndX)
    var startX = max(0f, rawStartX - overlap / 2f)
    var endX = min(maxEndX, rawEndX + overlap / 2f)
    if (endX - startX < startWidthPx) startX = max(0f, endX - startWidthPx)
    if (endX - startX < startWidthPx) endX = min(maxEndX, startX + startWidthPx)
    return TimeRangeHandleXs(startX, endX)
}

/**
 * 拖动换算用的局部斜率（px/分钟）：被拖胶囊的左缘对自身时间的导数，中心差分求得。
 *
 * 手指位移换算成分钟必须按这个斜率走，胶囊才会**跟手**：
 *
 * - 自由段（两胶囊拉开）斜率 = 标称行程斜率，手指走 1px 时间涨 1px 的分钟数，胶囊 1:1 跟手；
 * - 贴靠段（重叠让位）斜率恰好是标称的一半——位置只走手指位移的一半，按斜率补偿后
 *   每分钟走两倍轨道距离，换来的仍是胶囊 1:1 贴着指尖（两胶囊整体平移）；
 * - 被按死的状态（两端挤不下，斜率为 0）退回标称斜率：既不除零，时间也照常推进，
 *   位置虽然动不了，松手/反手后不会欠下位移。
 */
internal fun timeRangeDragPxPerMinute(
    trackWidthPx: Float,
    startWidthPx: Float,
    endWidthPx: Float,
    startMinutes: Int,
    endMinutes: Int,
    isStart: Boolean,
): Float {
    fun leftPxAt(minute: Int): Float {
        val xs = timeRangeHandleXs(
            trackWidthPx = trackWidthPx,
            startWidthPx = startWidthPx,
            endWidthPx = endWidthPx,
            startMinutes = if (isStart) minute else startMinutes,
            endMinutes = if (isStart) endMinutes else minute,
        )
        return if (isStart) xs.startX else xs.endX
    }

    val minute = if (isStart) startMinutes else endMinutes
    val low = (minute - 1).coerceAtLeast(0)
    val high = (minute + 1).coerceAtMost(TimeRangeLastMinute)
    val local = if (high > low) (leftPxAt(high) - leftPxAt(low)) / (high - low).toFloat() else 0f
    val nominal = timeRangeTravelPx(trackWidthPx, if (isStart) startWidthPx else endWidthPx) /
        TimeRangeLastMinute
    val fallback = nominal.coerceAtLeast(MinDragPxPerMinute)
    return if (local >= fallback * PinnedSlopeFraction) local else fallback
}

/**
 * 时间范围滑块：点状轨道上两个可拖动的时间胶囊（开始/结束），范围 0:00–23:59。
 *
 * - 拖动：胶囊跟手——手指横向走多少像素、胶囊就走多少像素（换算斜率见
 *   [timeRangeDragPxPerMinute]，贴靠段同样 1:1，不会半速落后）；
 * - 点按：单独点某个胶囊弹钟表 TimePicker 精确设置（与设置页「第 N 节」同一交互）；
 * - 顺序约束：开始时间恒早于结束时间（最小间隔 1 分钟），拖到边界即顶住（不推走另一端）；
 * - 位置约束：两胶囊不重叠、最多贴在一起——时间差最小 1 分钟（约 0.2px）远小于胶囊宽度，
 *   位置上必然相撞，故重叠时两胶囊各从自己的时间位置向内让开重叠量的一半（只让位置、
 *   不改时间），时间拉开后各自回到时间位置；轨道两端挤不下时（如 0:01、23:59 附近）
 *   由越界一侧让位、另一侧贴合补足。
 *
 * 让位量只由当前时间对解出、不记「最近动过哪个胶囊」，位置是 (startMinutes, endMinutes)
 * 的连续函数：换手去拖另一个胶囊时布局结果不变，拖动从指尖处直接继续，不会整体跳开。
 *
 * 位置解算放在自定义 [Layout] 的同一次测量里：先实测两个胶囊宽度再算位置、直接摆放，
 * 不经状态回写，首帧即正确（预览单帧渲染也不会叠在原点）。同一份解算结果同步进手势用的
 * 几何快照 [TimeRangeGestureGeometry]，命中测试与位移换算读到的就是屏幕上的位置。
 *
 * 手势统一挂在整条轨道这一层，胶囊自身不持手势：按下时命中哪个胶囊（决定点按弹哪个钟表、
 * 拖动改哪个时间）由实测位置判定，路径只有一条。[TimeHandle] 只管自己的样式与文字。
 *
 * 拖动中的分钟数只记在本次拖动会话 [TimeRangeDrag] 里、绝不回读参数：参数要等调用方重组
 * 才更新，同一帧内的多次触摸事件若都从参数出发，后一次会把前一次的位移整个覆盖掉——
 * 胶囊于是落后于手指，手指来回时甚至朝反方向跳。
 *
 * 坐标一律用绝对坐标：点状轨道是横轴、时间从左到右单向递增，胶囊用 place（而非
 * placeRelative）摆放，不随 RTL 镜像——镜像会让「胶囊画在哪」与「手指点到哪」错位
 * （app 开了 supportsRtl，须显式规避）。
 *
 * 无状态受控组件：分钟数由调用方持有，每次变化经 [onRangeChange] 整体提交
 * （调用方需维持 0 ≤ startMinutes < endMinutes ≤ [TimeRangeLastMinute]）。
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
    // 正在通过弹窗自定义时间的胶囊（null = 无）
    var editingIsStart by remember { mutableStateOf<Boolean?>(null) }
    // 手势与布局共用的几何快照：测量里写入、手势里读。普通对象而非快照状态——
    // 写回不该触发重组，手势必然发生在布局之后，读到的就是屏幕上的最新位置
    val geometry = remember { TimeRangeGestureGeometry() }
    // pointerInput(Unit) 的协程只在首次组合启动，闭包内一律经 rememberUpdatedState 读最新值，
    // 避免把过期参数捕获进去、也避免拖动中途因 key 变化重启手势
    val currentStart by rememberUpdatedState(startMinutes)
    val currentEnd by rememberUpdatedState(endMinutes)
    val currentOnRangeChange by rememberUpdatedState(onRangeChange)

    Layout(
        content = {
            // 顺序即上方 measurables 顺序：开始在前、结束在后
            TimeHandle(minutes = startMinutes)
            TimeHandle(minutes = endMinutes)
        },
        modifier = modifier
            .fillMaxWidth()
            .height(SliderHeight)
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
            }
            // 点按：只认胶囊上的那一下（空轨道不消费，点击照旧穿透给上层「点空白收起键盘」）
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 落点这一下同时定下「拖动改哪个胶囊」：见 pressedHandle 的说明
                    val isStart = geometry.press(down.position.x) ?: return@awaitEachGesture
                    down.consume()
                    // 拖动中的移动会被拖动检测器消费，这里随之取消（抬手不再弹钟表）
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    up.consume()
                    editingIsStart = isStart
                }
            }
            .pointerInput(Unit) {
                var drag: TimeRangeDrag? = null
                detectHorizontalDragGestures(
                    // 空轨道上起手：不认领这次拖动（后续 move 不消费，交回上层）
                    onDragStart = {
                        drag = geometry.pressedHandle?.let { isStart ->
                            TimeRangeDrag(isStart, if (isStart) currentStart else currentEnd)
                        }
                    },
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                    onHorizontalDrag = { change, dragAmount ->
                        val active = drag ?: return@detectHorizontalDragGestures
                        change.consume()
                        val isStart = active.isStart
                        val startAt = if (isStart) active.minutes else currentStart
                        val endAt = if (isStart) currentEnd else active.minutes
                        // 上下限取自对侧时间（拖动中它不变）：开始恒早于结束、最小间隔 1 分钟
                        val lower: Int
                        val upper: Int
                        if (isStart) {
                            lower = 0
                            upper = (currentEnd - 1).coerceAtLeast(0)
                        } else {
                            upper = TimeRangeLastMinute
                            lower = (currentStart + 1).coerceAtMost(upper)
                        }
                        val previous = active.minutes
                        val target = active.advance(
                            dragAmountPx = dragAmount,
                            pxPerMinute = geometry.dragPxPerMinute(isStart, startAt, endAt),
                            lower = lower,
                            upper = upper,
                        )
                        if (target == previous) return@detectHorizontalDragGestures
                        if (isStart) currentOnRangeChange(target, currentEnd)
                        else currentOnRangeChange(currentStart, target)
                    },
                )
            },
    ) { measurables, constraints ->
        val trackW = constraints.maxWidth.toFloat()
        // 胶囊宽度自适应文字：松约束实测宽度后，同一次布局里解算位置
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val startPlaceable = measurables[0].measure(loose)
        val endPlaceable = measurables[1].measure(loose)
        val startW = startPlaceable.width.toFloat()
        val endW = endPlaceable.width.toFloat()
        // 摆放与手势同源：命中测试/位移换算读到的就是这一帧屏幕上的位置
        val xs = timeRangeHandleXs(trackW, startW, endW, startMinutes, endMinutes)
        geometry.update(trackW, startW, endW, xs.startX, xs.endX)

        layout(constraints.maxWidth, constraints.maxHeight) {
            // 绝对摆放（place，不随 RTL 镜像）：点状轨道是横轴、时间从左到右单向递增，
            // 手势又按绝对坐标做命中与换算，镜像会让「胶囊画在哪」与「手指点到哪」错位
            startPlaceable.place(
                xs.startX.roundToInt(),
                (constraints.maxHeight - startPlaceable.height) / 2,
            )
            endPlaceable.place(
                xs.endX.roundToInt(),
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
                // M3 按钮的涟漪在组件内部硬编码、调用侧置不了 null：本阶段只叠加按压反馈——
                // 按压源交给按钮形参，pressFeedback 接在 modifier 链末尾（贴按钮本体，对齐 textShape）
                val press = remember { MutableInteractionSource() }
                TextButton(
                    onClick = {
                        val picked = timeState.hour * 60 + timeState.minute
                        if (isStart) {
                            onRangeChange(picked.coerceAtMost(endMinutes - 1).coerceAtLeast(0), endMinutes)
                        } else {
                            onRangeChange(
                                startMinutes,
                                picked.coerceAtLeast(startMinutes + 1).coerceAtMost(TimeRangeLastMinute),
                            )
                        }
                        editingIsStart = null
                    },
                    interactionSource = press,
                    modifier = Modifier.pressFeedback(press, ButtonDefaults.textShape),
                ) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                // 叠按压反馈：M3 涟漪在按钮内部、调用侧去不掉（按压源与按钮共用）
                val press = remember { MutableInteractionSource() }
                TextButton(
                    onClick = { editingIsStart = null },
                    interactionSource = press,
                    modifier = Modifier.pressFeedback(press, ButtonDefaults.textShape),
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/**
 * 一次拖动会话：抓住的是哪个胶囊（true = 开始）＋本次拖动累计到的分钟＋不足 1 分钟的余量。
 *
 * 分钟数在这里自增自减、不回读参数：同帧内的连续事件因此逐次累加，而不是各自从「上一帧的
 * 旧值」出发互相覆盖——那正是胶囊落后于手指、甚至随手指来回朝反方向跳的原因。
 */
internal class TimeRangeDrag(val isStart: Boolean, startMinutes: Int) {
    var minutes: Int = startMinutes
        private set

    /** 不足 1 分钟的位移余量（手指慢速移动时不丢分钟） */
    private var pendingMinutes = 0f

    /**
     * 消化一段手指位移（px），返回本次落到的分钟（被上下限夹紧时可能与当前值相同）。
     *
     * 位移先按 [pxPerMinute] 换算成分钟攒着、凑满 1 分钟才消费（全轨 1439 分钟、每分钟
     * 不足 1px，逐帧取整会整帧丢失）；只扣掉本次请求的整分钟，被夹掉的位移不再攒着，
     * 否则反手要先把「欠」下的位移还完胶囊才动。
     */
    fun advance(dragAmountPx: Float, pxPerMinute: Float, lower: Int, upper: Int): Int {
        pendingMinutes += dragAmountPx / pxPerMinute
        val step = pendingMinutes.roundToInt()
        if (step == 0) return minutes
        pendingMinutes -= step
        minutes = (minutes + step).coerceIn(lower, upper)
        return minutes
    }
}

/**
 * 布局 → 手势的几何快照：测量里写入实测宽度与解算位置，手势里读来做命中测试与位移换算。
 *
 * 用普通对象而不是快照状态：测量期写快照状态会触发一轮布局回环，而这份数据只是「上一帧
 * 屏幕上在哪」，没有任何 UI 依赖它——手势必然发生在布局之后，读到的就是当前帧的位置。
 */
private class TimeRangeGestureGeometry {
    private var trackWidthPx = 0f
    private var startWidthPx = 0f
    private var endWidthPx = 0f
    private var startX = 0f
    private var endX = 0f

    /**
     * 最近一次手指落点的命中结果：true = 开始胶囊、false = 结束胶囊、null = 空轨道。
     * 点按与拖动共用这一次判定——拖动检测器给的起点可能已越过 touch slop（贴着胶囊边缘
     * 往外拖时它已落在胶囊之外），用落点才不会扑空。
     */
    var pressedHandle: Boolean? = null
        private set

    fun update(
        trackWidthPx: Float,
        startWidthPx: Float,
        endWidthPx: Float,
        startX: Float,
        endX: Float,
    ) {
        this.trackWidthPx = trackWidthPx
        this.startWidthPx = startWidthPx
        this.endWidthPx = endWidthPx
        this.startX = startX
        this.endX = endX
    }

    /** 记下落点命中结果并返回（命中测试：true = 开始胶囊、false = 结束胶囊、null = 空轨道） */
    fun press(x: Float): Boolean? = hitTest(x).also { pressedHandle = it }

    private fun hitTest(x: Float): Boolean? = when {
        x >= startX && x < startX + startWidthPx -> true
        x >= endX && x < endX + endWidthPx -> false
        else -> null
    }

    /** 拖动换算斜率（px/分钟）：两胶囊都贴在轨道内，几何同 [timeRangeHandleXs] */
    fun dragPxPerMinute(isStart: Boolean, startMinutes: Int, endMinutes: Int): Float =
        timeRangeDragPxPerMinute(
            trackWidthPx = trackWidthPx,
            startWidthPx = startWidthPx,
            endWidthPx = endWidthPx,
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            isStart = isStart,
        )
}

/**
 * 单个时间胶囊：灰底蓝字（tnum 等宽数字，拖动中数字位宽稳定不抖）。
 * 摆放位置由 [TimeRangeSlider] 的 Layout 解算，手势也由那一层统一接管，这里只负责自身尺寸与样式。
 */
@Composable
private fun TimeHandle(
    minutes: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .height(HandleHeight)
            .clip(RowShape)
            .background(colors.background)
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
