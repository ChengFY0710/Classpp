package com.fangyi.classpp.ui.day

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.motion.DragDirection
import com.fangyi.classpp.ui.motion.DragDirectionLock
import com.fangyi.classpp.ui.motion.Motion
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 横滑切日的甩动判向阈值（dp/s，换算成 px/s 后使用）：与课表页 Pager 默认吸附速度同量级 */
private val DaySwipeFlingVelocity = 400.dp

/** 松手提交所需的位移占比：与课表页 Pager「过半翻页」同口径 */
private const val DaySwipeCommitFraction = 0.5f

/**
 * 日程页 04 态日历下方内容（上课提醒卡 + 待办提醒卡 + 时间轴/DayTaskCard）的按日横滑容器：
 * 三页（昨天 / 选中日 / 明天）各占一屏宽排成一行，手指位移 1:1 直接写图层 translationX，
 * 松手按「位移过半或同向甩动」吸附到相邻日并提交日期，手感与课表页课表内容的
 * `HorizontalPager`（见 CourseGrid）对齐——相邻日实时滑入、一次手势最多切一天、
 * 快速甩动优先于位移阈值。
 *
 * **为什么不用 HorizontalPager**：这里的内容必须作为 LazyColumn 的一个 item 参与竖向
 * 滚动（日历卡上方、竖向滚动/日历折叠都保持原样），而下方内容高度按天变化（时间轴的
 * 锚点小时、待办提醒卡行数都随日期不同），Pager 在 LazyColumn 的无限高约束下没有可用的
 * 固定页高。
 *
 * 三页用自定义 [Layout] 显式按 0/W/2W 摆放，容器自身只报告一页宽、高度取**当前页**
 * （邻页只画不撑高，多出来的部分由外层 `clipToBounds` 裁掉）——不能用一个 3W 宽的
 * Row 交给 Box：Box 会把超宽子项居中，静止时中间页反而落在视口外、显示成「明天」；
 * 取三页最大高度也会让短的一天（下午才有课 → 时间轴锚点晚）下方拖出大片空白。
 *
 * 手势与课表页共用同一把方向锁（[DragDirectionLock]）：横向定轴当帧在 Initial pass
 * 消费，早于 LazyColumn 竖向滚动的 Main pass 处理，嵌在滚动容器内也不会被抢走；
 * 竖向定轴立即让位，滚动/日历折叠/02↔04 转场完全不受影响。
 *
 * 跟手位移用普通 float state 直写（指针回调是受限挂起作用域，且拖动要当帧生效），
 * 只有松手收束走 [animate]——位移每帧只在 graphicsLayer 里读，全程不重组、不重排。
 *
 * @param selectedDate 当前选中日（宿主状态）
 * @param onDateChange 横滑落位后回调相邻日；点日历球等外部换日不会经过这里
 * @param enabled 仅 04 态内容可滚时开启（与内容竖向滚动同一把闸）
 * @param page 单页内容；第二个参数 revision 在每次横滑提交时自增，宿主可用它作为有状态
 *   内容（如 DayTodoAlert 的行镜）的重置键——滑入页在落位前已按新日期渲染，落位瞬间必须
 *   从新日期的完整行集起画，而不是复用旧日期的实例重播「跨日行增减」动画
 */
@Composable
internal fun DayContentSwipe(
    selectedDate: IsoDate,
    onDateChange: (IsoDate) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    page: @Composable (viewDate: IsoDate, revision: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 当前页位移（px）：0 = 中间页占满视口，正 = 拖向昨天，负 = 拖向明天
    var offset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val prevDate = remember(selectedDate) { selectedDate - 1 }
    val nextDate = remember(selectedDate) { selectedDate + 1 }
    var revision by remember { mutableIntStateOf(0) }
    // 手势块只在页宽变化时重启，闭包会一直持有首次组合时的日期；日期切换后必须读最新值，
    // 否则第二次横滑会按旧日期再减/加一天（表现为一次滑两天）
    val latestSelectedDate by rememberUpdatedState(selectedDate)
    val latestOnDateChange by rememberUpdatedState(onDateChange)

    fun stopSettle() {
        settleJob?.cancel()
        settleJob = null
    }

    // 外部换日（点日历球/回到今天/跨天重置）：内容立即落位，不走横向动画
    LaunchedEffect(selectedDate) {
        stopSettle()
        offset = 0f
    }

    // 离开 04 / 浮层压住 / 转场动画期间停掉在途吸附并归零，避免带着半页位移进下一态
    LaunchedEffect(enabled) {
        if (!enabled) {
            stopSettle()
            offset = 0f
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val pageWidth = maxWidth
        val pageWidthPx = constraints.maxWidth.toFloat()
        val flingVelocityPx = with(LocalDensity.current) { DaySwipeFlingVelocity.toPx() }

        // 松手收束：同向甩动优先，其次位移过半；提交时先滑满一页，再切日期并把位移归零——
        // 归零后新选中日正好停在视口原位，落位前后画面完全一致
        fun settle(velocityX: Float) {
            stopSettle()
            val current = offset
            if (current == 0f) return
            val direction = if (current > 0f) 1 else -1
            val commit = abs(current) >= pageWidthPx * DaySwipeCommitFraction ||
                velocityX * direction >= flingVelocityPx
            val target = if (commit) direction * pageWidthPx else 0f
            settleJob = scope.launch {
                animate(
                    initialValue = current,
                    targetValue = target,
                    animationSpec = Motion.Settle,
                ) { value, _ -> offset = value }
                offset = target
                settleJob = null
                if (commit) {
                    revision += 1
                    latestOnDateChange(
                        if (direction > 0) latestSelectedDate - 1 else latestSelectedDate + 1,
                    )
                    offset = 0f
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .then(
                    if (enabled && pageWidthPx > 0f) {
                        Modifier.pointerInput(pageWidthPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                val lock = DragDirectionLock(viewConfiguration.touchSlop)
                                val tracker = VelocityTracker()
                                var dragging = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val changes = event.changes
                                    // 方向被他人抢定（父级滚动已接管并消费）：本段让位
                                    if (changes.fastAny { it.isConsumed }) {
                                        if (dragging) settle(0f)
                                        break
                                    }
                                    val change = changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        if (dragging) settle(0f)
                                        break
                                    }
                                    val delta = change.positionChange()
                                    when (lock.onMove(delta.x, delta.y)) {
                                        DragDirection.Horizontal -> {
                                            if (!dragging) {
                                                dragging = true
                                                stopSettle()
                                                tracker.resetTracking()
                                            }
                                            // Initial pass 消费：父级（Main pass）整段退出
                                            change.consume()
                                            tracker.addPosition(change.uptimeMillis, change.position)
                                            offset = (offset + delta.x)
                                                .coerceIn(-pageWidthPx, pageWidthPx)
                                        }
                                        // 认定竖滑：立即让位，滚动/折叠正常接管
                                        DragDirection.Vertical -> break
                                        // 未定轴：只观察不消费，不影响点击
                                        DragDirection.Undecided -> Unit
                                    }
                                    if (!change.pressed) {
                                        if (dragging) settle(tracker.calculateVelocity().x)
                                        break
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Layout(
                content = {
                    Box(Modifier.width(pageWidth)) { page(prevDate, revision) }
                    Box(Modifier.width(pageWidth)) { page(selectedDate, revision) }
                    Box(Modifier.width(pageWidth)) { page(nextDate, revision) }
                },
                modifier = Modifier
                    .width(pageWidth)
                    // 位移只写图层，拖动全程不重组、不重排三页内容
                    .graphicsLayer { translationX = offset - pageWidthPx },
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val placeables = measurables.map { measurable ->
                    measurable.measure(Constraints(minWidth = width, maxWidth = width))
                }
                // 占位高度只看当前页（索引 1）：邻页只画不撑高，避免短页面下方留大片空白
                val height = placeables.getOrNull(1)?.height ?: 0
                layout(width, height) {
                    placeables.forEachIndexed { index, placeable ->
                        placeable.placeRelative(index * width, 0)
                    }
                }
            }
        }
    }
}
