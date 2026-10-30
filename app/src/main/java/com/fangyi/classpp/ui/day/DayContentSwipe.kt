package com.fangyi.classpp.ui.day

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.motion.CrossPage
import com.fangyi.classpp.ui.motion.CrossPageSpec
import com.fangyi.classpp.ui.motion.DragDirection
import com.fangyi.classpp.ui.motion.DragDirectionLock
import com.fangyi.classpp.ui.motion.Motion
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 横滑切日的甩动判向阈值（dp/s，换算成 px/s 后使用）：与课表页 Pager 默认吸附速度同量级 */
private val DaySwipeFlingVelocity = 400.dp

/** 松手提交所需的位移占比：与课表页 Pager「过半翻页」同口径 */
private const val DaySwipeCommitFraction = 0.5f

/**
 * 日程页 04 态日历下方内容（上课提醒卡 + 待办提醒卡 + 时间轴/DayTaskCard）的按日横滑容器：
 * 三页（昨天 / 选中日 / 明天）各占一屏宽排成一行，手指位移 1:1 直接写图层 translationX，
 * 松手按「位移过半或同向甩动」判定是否切换到相邻日；判定提交时日期在松手当帧提交
 *（日历球/背景图/顶栏即时联动），吸附动画只负责把旧页送出视口，手感与课表页课表内容的
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
 * 高度本身是动画值：换日提交时从旧页高度平滑过渡到新页高度，裁剪边界与 item 占位
 * 同步动画，旧内容不会被新页高度立即裁掉（消除换日的几何硬切）。
 *
 * 手势与课表页共用同一把方向锁（[DragDirectionLock]）：横向定轴当帧在 Initial pass
 * 消费，早于 LazyColumn 竖向滚动的 Main pass 处理，嵌在滚动容器内也不会被抢走；
 * 竖向定轴立即让位，滚动/日历折叠/02↔04 转场完全不受影响。
 *
 * 跟手位移用普通 float state 直写（指针回调是受限挂起作用域，且拖动要当帧生效），
 * 只有松手收束走 [animate]——位移每帧只在 graphicsLayer 里读，全程不重组、不重排。
 *
 * **日历点选跳日**（[jumpTargetDate]/[jumpSession]，宿主在内容区可见的 02/04 态下发指令）
 * 按日距分流：目标在指令到达时已确定，两条路径都先提交换日（顶栏/背景图/日历球与动画
 * 同帧联动，不再等落位才换日）。相邻日回放手势式滑动——提交后三页整体平移一格，位移
 * 按新参照系回补一整页后，再以与松手收束相同的 [Motion.Settle] 滑回 0，旧页视觉位置
 * 不变、观感与手势同轨；非相邻日走 CrossPage 层级交叉（复用 ui.motion 的 CrossPage）——
 * 被离开的日期进退场层原位沉退，内容本体落位到目标日、再延迟浮入。
 * 三页结构只有相邻日，非相邻日的内容本体只能瞬移落位、靠进出场动画补足层次。指令路径
 * 不受 [enabled] 手势闸影响：02 态横滑关闭，点选跳日仍按同一套动画执行。
 *
 * @param selectedDate 当前选中日（宿主状态）
 * @param onDateChange 换日提交回调（手势松手判定提交 / 日历相邻日跳日起步）；点日历球等
 *   其它外部换日不会经过这里
 * @param enabled 仅 04 态内容可滚时开启（与内容竖向滚动同一把闸）
 * @param jumpTargetDate 日历点选跳日指令：本轮要跳到的日期，null = 无指令（其余换日
 *   路径不经此）。会话内消费一次；宿主回到 01 台历页时置 null 作废残留
 * @param jumpSession 跳日指令会话号：宿主每次点选自增，作本组件驱动 key
 * @param jumpCrossSpec 非相邻日跳转的 CrossPage 时序（与待办页顶栏分组切换共用
 *   Motion.PageJumpCross）
 * @param page 单页内容；第二个参数 revision 在每次换日提交时自增，宿主可用它作为有状态
 *   内容（如提醒卡 DayTodoAlert / DayTaskAlldayAlert 的行镜）的重置键——提交瞬间内容须
 *   从新日期的完整行集起画，而不是复用旧日期的实例重播「跨日行增减」动画
 */
@Composable
internal fun DayContentSwipe(
    selectedDate: IsoDate,
    onDateChange: (IsoDate) -> Unit,
    enabled: Boolean,
    jumpTargetDate: IsoDate? = null,
    jumpSession: Int = 0,
    jumpCrossSpec: CrossPageSpec = CrossPageSpec(),
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
    // 内部提交标记：滑轨自身的换日（手势落位 / 相邻日跳日起步）自行管理位移，随后的
    // selectedDate 变化不能再按「外部换日」清零位移（会把刚摆好的滑动起点抹掉）
    var internalCommitDate by remember { mutableStateOf<IsoDate?>(null) }
    // ——— 日历点选跳日的 CrossPage 转场（非相邻日路径）———
    // 退场层日期 = 被离开的那一天（连续跨日跳转必不相同，作 key 直接重建）；进场侧的
    // 淡入/放大挂在滑轨 Layout 的图层上，静止恒为全可见，手势横滑不受影响
    var jumpOverlayDate by remember { mutableStateOf<IsoDate?>(null) }
    val jumpEnterAlpha = remember { Animatable(1f) }
    val jumpEnterScale = remember { Animatable(1f) }
    // ——— 容器高度过渡（消除换日的几何硬切）———
    // 当前页实测高度由 Layout 内的 onSizeChanged 回灌；换日提交后容器高度（裁剪边界与
    // item 占位）用短动画从旧页高度过渡到新页高度——否则旧内容底部会被新页高度立即
    // 裁掉、item 高度瞬变，混在交叉淡入淡出里非常突兀。时长与交叉段的进场完成对齐
    //（延迟 100 + 淡入 200 = 300ms），新内容完全显现的同一刻几何也刚好落定
    var measuredPageHeight by remember { mutableIntStateOf(0) }
    val containerHeight = remember { Animatable(0f) }
    LaunchedEffect(measuredPageHeight) {
        if (measuredPageHeight <= 0) return@LaunchedEffect
        if (containerHeight.value <= 0f) {
            // 首测直接就位，不播动画
            containerHeight.snapTo(measuredPageHeight.toFloat())
        } else {
            containerHeight.animateTo(
                measuredPageHeight.toFloat(),
                tween(durationMillis = 300, easing = Motion.Standard),
            )
        }
    }
    // 手势块只在页宽变化时重启，闭包会一直持有首次组合时的日期；日期切换后必须读最新值，
    // 否则第二次横滑会按旧日期再减/加一天（表现为一次滑两天）
    val latestSelectedDate by rememberUpdatedState(selectedDate)
    val latestOnDateChange by rememberUpdatedState(onDateChange)

    fun stopSettle() {
        settleJob?.cancel()
        settleJob = null
    }

    // 外部换日（非相邻跳日/回到今天/跨天重置）：内容立即落位，不走横向动画。
    // 内部提交（手势落位、相邻跳日起步）的位移由提交方接续，这里跳过
    LaunchedEffect(selectedDate) {
        if (internalCommitDate == selectedDate) {
            internalCommitDate = null
            return@LaunchedEffect
        }
        stopSettle()
        offset = 0f
    }

    // 内容区横滑不可用（离开 04 / 浮层压住 / 转场动画期间）时停掉在途吸附并归零，避免
    // 带着半页位移进下一态；跳日转场的半程状态一并收敛（清退场层、恢复全可见）。
    // 只响应 enabled 翻转：02 态常驻 false，不影响该态点选跳日的指令动画
    LaunchedEffect(enabled) {
        if (!enabled) {
            stopSettle()
            offset = 0f
            jumpOverlayDate = null
            jumpEnterAlpha.snapTo(1f)
            jumpEnterScale.snapTo(1f)
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val pageWidth = maxWidth
        val pageWidthPx = constraints.maxWidth.toFloat()
        val flingVelocityPx = with(LocalDensity.current) { DaySwipeFlingVelocity.toPx() }

        // 提交换日：行镜 revision 自增（内容已按新日期渲染，提交瞬间须按新日期的完整
        // 行集重画待办提醒卡）+ 换日 + 位移归零。手势在松手判定提交时调用、相邻日跳日在
        // 指令到达时调用，两处都由调用方随即把位移换算到新页参照系。internalCommitDate
        // 让随后的 selectedDate 变化不走「外部换日」清位移
        fun commitSwipe(newDate: IsoDate) {
            revision += 1
            internalCommitDate = newDate
            latestOnDateChange(newDate)
            offset = 0f
        }

        // 松手收束：同向甩动优先，其次位移过半。判定提交时先换日（三页整体平移一格，日期
        // 与日历/背景同帧联动），位移按新参照系回补一整页后滑回 0——旧页视觉位置不变，
        // 收尾轨迹与原「落位才换日」完全一致；未达阈值则直接弹回 0
        fun settle(velocityX: Float) {
            stopSettle()
            val current = offset
            if (current == 0f) return
            val direction = if (current > 0f) 1 else -1
            val commit = abs(current) >= pageWidthPx * DaySwipeCommitFraction ||
                velocityX * direction >= flingVelocityPx
            if (commit) {
                commitSwipe(if (direction > 0) latestSelectedDate - 1 else latestSelectedDate + 1)
                offset = current - direction * pageWidthPx
            }
            settleJob = scope.launch {
                animate(
                    initialValue = offset,
                    targetValue = 0f,
                    animationSpec = Motion.Settle,
                ) { value, _ -> offset = value }
                offset = 0f
                settleJob = null
            }
        }

        // 日历点选跳日（宿主在 02/04 态下发指令；指令路径不受 enabled 手势闸影响）：目标在
        // 指令到达时已确定，两条路径都先提交换日（顶栏/背景图/日历球即时联动）再播动画，
        // 不再等落位才换日。相邻日回放手势式滑动——提交后三页整体平移一格，位移按新参照
        // 系回补一整页（旧页停在原视觉位置），再用与松手收束相同的 [Motion.Settle] 滑回 0；
        // 非相邻日走 CrossPage 层级交叉——被离开的日期进退场层原位沉退，内容本体落位到
        // 目标日后延迟浮入，时序读 [jumpCrossSpec]。进场动画挂 scope 上而非本 effect 协程：
        // 会话重启（转场中再点选）不连带取消在途进场；相邻分支重启时先恢复全可见、非相邻
        // 分支重播，无半程残留
        LaunchedEffect(jumpSession) {
            val target = jumpTargetDate ?: return@LaunchedEffect
            val current = latestSelectedDate
            if (target == current) return@LaunchedEffect
            stopSettle()
            if (target == current - 1 || target == current + 1) {
                jumpOverlayDate = null
                jumpEnterAlpha.snapTo(1f)
                jumpEnterScale.snapTo(1f)
                // 先提交再滑：日期/背景图/日历球与动画同帧联动，动画本身仍从旧页位置
                // 滑向目标页——提交后三页整体平移一格，位移按新参照系回补一整页
                //（offset_new = offset_old − targetOffset），旧页视觉位置不变
                val targetOffset = if (target == current - 1) pageWidthPx else -pageWidthPx
                val fromOffset = offset
                commitSwipe(target)
                offset = fromOffset - targetOffset
                settleJob = scope.launch {
                    animate(
                        initialValue = offset,
                        targetValue = 0f,
                        animationSpec = Motion.Settle,
                    ) { value, _ -> offset = value }
                    offset = 0f
                    settleJob = null
                }
            } else {
                jumpOverlayDate = current
                // 非相邻日同样是一次换日提交：revision 同步自增（同横滑提交纪律），
                // 待办提醒卡（行镜以 revision 为 key）按新日期的完整行集起画——
                // 不自增的话同一实例拿到新日期数据会重播「跨日行增减」动画，
                // 行动画叠在淡入半透明层上，新内容看起来就是闪现出来的
                revision += 1
                latestOnDateChange(target)
                // snapTo 会打断上一次未完成的进场，连续跳转收敛到最后一次
                scope.launch {
                    jumpEnterAlpha.snapTo(0f)
                    jumpEnterScale.snapTo(jumpCrossSpec.hiddenScale)
                    delay(jumpCrossSpec.enterDelayMillis.toLong())
                    coroutineScope {
                        launch {
                            jumpEnterAlpha.animateTo(
                                1f,
                                tween(durationMillis = jumpCrossSpec.enterAlphaMillis),
                            )
                        }
                        jumpEnterScale.animateTo(
                            1f,
                            tween(
                                durationMillis = jumpCrossSpec.enterScaleMillis,
                                easing = jumpCrossSpec.enterEasing,
                            ),
                        )
                    }
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
                    // 当前页实测高度回灌：换日提交后容器高度平滑过渡的动画目标
                    //（见 DayContentSwipe 顶部的 containerHeight）
                    Box(
                        Modifier
                            .width(pageWidth)
                            .onSizeChanged { measuredPageHeight = it.height },
                    ) { page(selectedDate, revision) }
                    Box(Modifier.width(pageWidth)) { page(nextDate, revision) }
                },
                modifier = Modifier
                    .width(pageWidth)
                    // 位移只写图层，拖动全程不重组、不重排三页内容；跨日跳转的进场侧
                    //（非相邻日：延迟后淡入 + 从 hiddenScale 放大回 1）同层延迟读取，
                    // 静止恒为 1f，手势横滑不受影响
                    .graphicsLayer {
                        translationX = offset - pageWidthPx
                        alpha = jumpEnterAlpha.value
                        scaleX = jumpEnterScale.value
                        scaleY = jumpEnterScale.value
                    },
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val placeables = measurables.map { measurable ->
                    measurable.measure(Constraints(minWidth = width, maxWidth = width))
                }
                // 占位高度只看当前页（索引 1）：邻页只画不撑高，避免短页面下方留大片空白。
                // 换日提交后改报 containerHeight——容器高度从旧页平滑过渡到新页，
                // 裁剪边界与 item 占位同步动画，旧内容底部不会被新页高度立即裁掉
                val naturalHeight = placeables.getOrNull(1)?.height ?: 0
                val height = if (containerHeight.value > 0f) {
                    containerHeight.value.roundToInt()
                } else {
                    naturalHeight
                }
                layout(width, height) {
                    placeables.forEachIndexed { index, placeable ->
                        placeable.placeRelative(index * width, 0)
                    }
                }
            }

            // 非相邻日跳转的退场层：被离开的日期内容叠在滑轨正上方，由 CrossPage 播退场——
            // 原位淡出 + 缩到 hiddenScale 沉下去，alpha 归零内容自行卸载。首组合即全可见
            //（CrossPage 对初始可见不播进场，正合「旧页原位就位再沉退」），挂载后立即翻
            // visible 启动退场。matchParentSize 不撑大容器（容器高度必须只跟随当前页），
            // 旧页超出部分与手势中的邻页一样被外层 clipToBounds 裁掉；连续跨日跳转被离开
            // 的日期必不相同，key 用日期即可直接重建
            jumpOverlayDate?.let { overlayDate ->
                key(overlayDate) {
                    var overlayExiting by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { overlayExiting = true }
                    CrossPage(
                        visible = !overlayExiting,
                        modifier = Modifier.matchParentSize(),
                        spec = jumpCrossSpec,
                    ) {
                        page(overlayDate, revision)
                    }
                }
            }
        }
    }
}
