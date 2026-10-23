package com.fangyi.classpp.ui.day

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.EaseIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.components.hazeSources
import com.fangyi.classpp.ui.components.hazeTopBarStyle
import com.fangyi.classpp.ui.motion.DragDirection
import com.fangyi.classpp.ui.motion.DragDirectionLock
import com.fangyi.classpp.ui.motion.OffsetOverscrollEffect
import com.fangyi.classpp.ui.motion.ProvideOverscroll
import com.fangyi.classpp.ui.motion.RubberBandLazyColumn
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.rememberDeviceCornerRadius
import com.fangyi.classpp.ui.navigation.NavReserve
import com.fangyi.classpp.ui.theme.DayCardShape
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.SerifDisplay
import com.fangyi.classpp.ui.theme.isDarkTheme
import com.kyant.shapes.RoundedRectangle
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val MillisPerDay = 86_400_000L

/**
 * 日程页（AppTab.Agenda）：手势驱动的台历三态页面。
 *
 * 真实状态只有 01 台历（[DayStage.Calendar]）、02 台历缩小态（[DayStage.Compact]）、
 * 04 台历完全收起态（[DayStage.Schedule]）；设计稿 03 只是 02↔04 的中间构图，由
 * open/collapse 两个进度派生，不存在停留点。
 *
 * 01→02：图片从全屏连续收成顶部圆角卡、日期组移入卡片、底部模糊层变为卡片下段
 * 模糊带、白卡从屏底升入。02→04：白卡上移、图片卡收窄下移、顶栏渐入。02 禁止下滑
 * 回 01；点 02 图片卡用同一曲线重播 02→01（纯视觉，不写持久化）。所有几何集中在
 * [DayGeometry]，每帧只在 layout/draw 阶段读进度，拖拽不触发重组。
 *
 * 背景图仍是唯一图片源（[DayImageStore]）；ic_image 仍呼起 [DayImageSheet]，浮层
 * 在场时继续藏底部导航栏。[active] 由调用方按当前 tab 透传，仅用于状态栏图标在
 * 非 Agenda 页恢复主题默认，不参与转场与导航栏布局。
 */
@Composable
fun DayScreen(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onOverlayOverNavBarChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val today = LocalToday
    val density = LocalDensity.current
    val pxPerDp = density.density
    val scope = rememberCoroutineScope()
    // 02 端点的整组过滚动：直接复用 ui.motion 的 OffsetOverscrollEffect，
    // 阻尼比例、回弹弹簧与内容区 OverScroll 完全同款；效果节点挂在整页上，
    // 容器尺寸就是屏幕，拉出曲线和 04 的内容列表一致。
    val compactOverscroll = remember(scope) {
        OffsetOverscrollEffect(
            animationScope = scope,
            animationSpec = OffsetOverscrollEffect.DefaultAnimationSpec,
            maxFraction = OffsetOverscrollEffect.DefaultMaxFraction,
        )
    }
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    val darkTheme = MaterialTheme.isDarkTheme
    // 01 台历的全屏图圆角取机身真实 R 角；01→02 再连续过渡到 DayCardShape 的 24dp。
    // 图片卡外层用 shapes 库的 RoundedRectangle 裁剪，圆滑圆角与白卡同源。
    val deviceCornerRadius = rememberDeviceCornerRadius()

    // 图片采样与白卡采样分开：图片 scrim/模糊带只采图片，顶栏只采内容卡，
    // 避免两种材质互相串色（Haze 2 一个 State 可以承载多种源，这里刻意隔离）。
    val imageHazeState = rememberHazeState()
    val contentHazeState = rememberHazeState()

    // 用户图片库与当前背景：首次组合从磁盘读，之后改动即时落盘（DayImageStore）
    var images by remember { mutableStateOf(DayImageStore.loadImages(context)) }
    var selectedImage by remember { mutableStateOf(DayImageStore.loadSelected(context)) }
    // 浮层两段式：mounted 决定是否在组合里，visible 管进出场——出场播完才移出组合
    var sheetMounted by remember { mutableStateOf(false) }
    var sheetVisible by remember { mutableStateOf(false) }
    LaunchedEffect(sheetMounted) {
        onOverlayOverNavBarChange(sheetMounted)
    }

    // 持久化记录：进入时按「记录日期 == 今天」恢复 02/04，否则 01。
    val initialRecord = remember { DayStateStore.load(context) }
    val initialStage = remember(initialRecord, today) { initialRecord.restoredStage(today) }
    var openedDay by remember { mutableStateOf(initialRecord.openedDay) }
    var persistedStage by remember {
        mutableStateOf(
            if (initialRecord.openedDay == today) initialRecord.stage else DayStage.Calendar,
        )
    }
    // 提交回调要始终读最新 today/openedDay：用 State 持有 lambda，转场状态只捕获 State。
    val commitState = rememberUpdatedState<(DayStage) -> Unit>(
        newValue = { target ->
            if (target != DayStage.Calendar &&
                (openedDay != today || persistedStage != target)
            ) {
                DayStateStore.save(context, today, target)
                openedDay = today
                persistedStage = target
            }
        },
    )
    val transition = remember {
        DayTransitionState(initialStage, scope) { target -> commitState.value(target) }
    }

    // 跨天/回前台对表：LocalToday 已含 onResume 与前台每分钟对表；日期变化立即回 01。
    LaunchedEffect(today) {
        if (openedDay != today) {
            compactOverscroll.reset()
            transition.resetForNewDay()
            persistedStage = DayStage.Calendar
        }
    }
    // 离开 02 或切到别的停留态时，整组过滚动必须归零，避免残余位移带进 01/04。
    LaunchedEffect(transition.stage) {
        if (transition.stage != DayStage.Compact) compactOverscroll.reset()
    }
    // 切到其它 tab 时手势会中断，过滚动也要立即归零，回页时不带残余位移。
    LaunchedEffect(active) {
        if (!active) compactOverscroll.reset()
    }

    // 状态栏图标：01 图片上强制浅色；02/04 随主题（浅色主题深图标），浮层/非当前页恢复主题。
    // onCalendarVisual 必须作为 DisposableEffect 的 key 被组合读取：只写在 SideEffect 里
    // 不会订阅该 derivedStateOf，状态从 01 切到 02 时图标会停在浅色。
    val onCalendarVisual by remember {
        derivedStateOf {
            transition.stage == DayStage.Calendar ||
                transition.open < DayCalendarIconSwitch
        }
    }
    DisposableEffect(active, sheetMounted, onCalendarVisual, darkTheme) {
        val window = activity?.window
        if (window != null) {
            val lightIcons = if (active && !sheetMounted && onCalendarVisual) {
                false
            } else {
                !darkTheme
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightIcons
        }
        onDispose {
            // key 变化时先回到主题默认，再由新 effect 覆写；页面真正销毁时也不会把
            // 01 的浅色图标留给其他页面。
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                    !darkTheme
            }
        }
    }

    // 日期组淡出后图片 icon 仍会参与命中测试：用 derivedStateOf 在跨过淡出终点时
    // 只重组一次，把不可见的按钮一并禁用，避免 04 过滚动露出区域还能点开换图浮层。
    val dateIconVisible by remember {
        derivedStateOf { transition.collapse < DayDateFadeEnd }
    }

    val listState = rememberLazyListState()
    val dragDistancePx = with(density) { DayDragDistance.toPx() }
    val flingThresholdPx = with(density) { DayFlingVelocity.toPx() }
    // 02↔04 的跟手统一由根级 pointerInput 驱动（见下方）：内容为空占位、nested scroll
    // 不消费时，图片卡/内容卡上任意起手都用同一份原始 dy 推进 collapse，没有起手阈值。
    val bandTintBrush = remember {
        // 接近 HazeProgressive 的 EaseIn 渐隐：01 底部 30% 黑 tint，只是拆到 Haze 外，
        // 让 02 起能把 tint 干净地降到 0（Haze style 在组合期固定，不能逐帧改 tint）。
        Brush.verticalGradient(
            colors = List(11) { index ->
                Color.Black.copy(alpha = EaseIn.transform(index / 10f))
            },
        )
    }

    val nestedScrollConnection = remember(
        transition,
        listState,
        dragDistancePx,
        flingThresholdPx,
        active,
        sheetMounted,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!active || sheetMounted || transition.isAnimating) return Offset.Zero
                // 只跟手；fling 阶段由 onPreFling 统一收束，避免同一手势被写两次
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val deltaY = available.y
                val consumed = when {
                    deltaY < 0f && transition.open < 1f ->
                        transition.dragOpenBy(deltaY, dragDistancePx)
                    // 02→04 的上滑推进 collapse；04 的上滑只属于内容 Overscroll，
                    // 即使 collapse 还没回到 1 也不在 pre 阶段拉回去（会打断橡皮筋）。
                    deltaY < 0f &&
                        transition.collapse < 1f &&
                        transition.stage != DayStage.Schedule ->
                        transition.dragCollapseBy(deltaY, dragDistancePx)
                    deltaY > 0f &&
                        transition.open >= 1f &&
                        transition.collapse > 0f &&
                        !listState.canScrollBackward ->
                        transition.dragCollapseBy(deltaY, dragDistancePx)
                    else -> 0f
                }
                return Offset(0f, consumed)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!active || sheetMounted) return Velocity.Zero
                // 吸附/重播中合入的速度一律吃掉，别让内容再起一场无意义 fling
                if (transition.isAnimating) return Velocity(0f, available.y)
                val canMove = when {
                    transition.open < 1f -> true
                    transition.collapse > 0f && transition.collapse < 1f -> true
                    // 端点上的甩动：02 上甩收台历；04 任何向下速度都交给 04→02，
                    // 不能让它落到内容 Overscroll 上造成反向橡皮筋。
                    transition.collapse <= 0f && available.y < -flingThresholdPx -> true
                    // 04 向下甩：仅在列表顶部时交给 04→02；列表已下滚时让列表自己 fling。
                    transition.collapse >= 1f &&
                        available.y > 0f &&
                        !listState.canScrollBackward -> true
                    else -> false
                }
                if (!canMove) return Velocity.Zero
                transition.settle(available.y, flingThresholdPx)
                return Velocity(0f, available.y)
            }
        }
    }

    // 日期文案（日期组与顶栏共用同一份今天）
    val (year, month, dayOfMonth) = remember(today) { today.toYearMonthDay() }
    val yearMonth = stringResource(R.string.date_year_month_format, year, month)
    val weekday = stringArrayResource(R.array.weekdays)[today.isoDayOfWeek() - 1]
    val monthDay = stringResource(R.string.month_day_format, month, dayOfMonth)
    val topBarTitle = stringResource(R.string.date_title_format, monthDay, weekday)
    val cardBackLabel = stringResource(R.string.cd_day_card_back)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(nestedScrollConnection)
            // 根级转场手势：Initial pass 先于内容卡/图片卡拿到事件，按方向决定是否消费。
            // 02 上滑、04 下滑时直接消费并推进 collapse（没有起手阈值；04 方向锁定后
            // 上下都跟手）；04 起手按主方向仲裁，横向占优让位给内容手势（卡片侧滑等），
            // 上滑占优不驱动 collapse、留给内容区 OverScroll 橡皮筋。02↔04 吸附动画期间
            // 竖向起手可直接接管（停住动画、从当前进度继续跟手）。抬手速度仍在这里
            // 统一交给 settle（04 仍在 1 时跳过，避免打断橡皮筋）。
            .pointerInput(
                active,
                sheetMounted,
                flingThresholdPx,
                dragDistancePx,
                compactOverscroll,
            ) {
                if (!active || sheetMounted) return@pointerInput
                val tracker = VelocityTracker()

                // 02 端点的整组过滚动：向下全收；向上只收到当前拉出量归零，
                // 剩余位移由调用方交给 collapse。返回本次被过滚动消费的 dy。
                fun applyCompactOverscroll(
                    deltaY: Float,
                    change: PointerInputChange,
                ): Float {
                    val current = compactOverscroll.currentOffset.y
                    val overscrollDelta = when {
                        deltaY > 0f -> deltaY
                        current > 0f -> maxOf(deltaY, -current)
                        else -> 0f
                    }
                    if (overscrollDelta == 0f) return 0f
                    val consumed = compactOverscroll.applyToScroll(
                        delta = Offset(0f, overscrollDelta),
                        source = NestedScrollSource.UserInput,
                    ) { Offset.Zero }.y
                    if (consumed != 0f) change.consume()
                    return consumed
                }

                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    // 吸附/重播期间不接新手势，避免整组过滚动在转场动画上再叠位移。
                    // 例外：02↔04 吸附允许新手势接管（canTakeOverSnap），其余动画整段屏蔽。
                    if (transition.isAnimating && !transition.canTakeOverSnap) {
                        return@awaitEachGesture
                    }
                    // 本次起手是否用于接管在途的 02↔04 吸附（01↔02 吸附/重播不会走到这里）。
                    val snapTakeOver = transition.canTakeOverSnap
                    // 02→04 与 04→02 都由根级统一驱动跟手；04 起手先做横/竖轴仲裁：
                    // 横向占优整段让位，竖向（上滑）占优不写 collapse，留给内容区 Overscroll。
                    // 04 只要列表在顶部就由根级接管，避免未到达列表滚动阈值前
                    // 出现“只吸不跟手”；列表已下滚时让给列表。接管吸附时无视列表状态——
                    // 动画期间内容滚动本就是关闭的。
                    val drivesCollapse = snapTakeOver || shouldDriveCollapse(
                        stage = transition.stage,
                        canScrollForward = listState.canScrollForward,
                        canScrollBackward = listState.canScrollBackward,
                    )
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val pointerId = down.id
                    var lastX = down.position.x
                    var lastY = down.position.y
                    // 接管吸附也走 04 同一套轴仲裁与双向跟手，只是竖向定轴后先停住动画。
                    val scheduleGesture =
                        transition.stage == DayStage.Schedule || snapTakeOver
                    // 04 起手轴仲裁：复用横滑手势同一把方向锁（占比阈值 + 单次定轴），
                    // 竖向占优才由根级驱动 04→02；横向占优立即让位给卡片的横滑/其它
                    // 横向手势，不消费事件、也不写 collapse。未定轴前一律不消费，点击不受影响。
                    val scheduleAxisLock = DragDirectionLock(viewConfiguration.touchSlop)
                    // 0 = 未定，1 = 下滑（驱动 collapse），-1 = 上滑（只做 Overscroll）
                    var scheduleDirection = 0
                    var directionAccum = 0f
                    // 横向定轴后整段让位：结束时不再走 settle，吸附动画自行跑完。
                    var gaveWayToHorizontal = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val dx = change.position.x - lastX
                        lastX = change.position.x
                        val dy = change.position.y - lastY
                        lastY = change.position.y
                        if (drivesCollapse) {
                            if (scheduleGesture) {
                                when (scheduleDirection) {
                                    0 -> {
                                        directionAccum += dy
                                        when (scheduleAxisLock.onMove(dx, dy)) {
                                            // 横向手势：让位，整段不再驱动 collapse。
                                            DragDirection.Horizontal -> {
                                                gaveWayToHorizontal = true
                                                break
                                            }
                                            DragDirection.Vertical -> when {
                                                snapTakeOver && transition.canTakeOverSnap -> {
                                                    // 接管在途吸附：动画停在当前进度，
                                                    // 上下两个方向都直接跟手。
                                                    transition.takeOverSnap()
                                                    scheduleDirection = 1
                                                    val consumed = transition.dragCollapseBy(
                                                        directionAccum,
                                                        dragDistancePx,
                                                    )
                                                    if (consumed != 0f) change.consume()
                                                }
                                                transition.stage == DayStage.Schedule &&
                                                    directionAccum < 0f -> {
                                                    // 上滑锁定：完全不碰 collapse，放给 Overscroll。
                                                    scheduleDirection = -1
                                                }
                                                else -> {
                                                    scheduleDirection = 1
                                                    // 补上定向前累计的下滑量，跟手不丢位移。
                                                    val consumed = transition.dragCollapseBy(
                                                        directionAccum,
                                                        dragDistancePx,
                                                    )
                                                    if (consumed != 0f) change.consume()
                                                }
                                            }
                                            DragDirection.Undecided -> Unit
                                        }
                                    }
                                    1 -> {
                                        // 下滑锁定（或接管吸附）后仍双向跟手：上滑把 collapse
                                        // 拉回 04，松手时才按最终进度判是否回 02——拖出去再
                                        // 拉回来（未越过阈值）不会提前触发 02。
                                        if (transition.collapse <= 0f && dy > 0f) {
                                            // 04→02 已经拖到 02：继续下滑交给整组过滚动，
                                            // 不再让内容列表自己的 overscroll 接走——
                                            // 切回 Compact 时那条效果会被移除，回位会硬切。
                                            applyCompactOverscroll(dy, change)
                                        } else if (dy != 0f) {
                                            // 上滑先收回 02 的整组过滚动，剩余位移再拉回 collapse。
                                            val overscrollConsumed =
                                                if (dy < 0f && transition.collapse <= 0f) {
                                                    applyCompactOverscroll(dy, change)
                                                } else {
                                                    0f
                                                }
                                            val remaining = dy - overscrollConsumed
                                            if (remaining != 0f) {
                                                val consumed = transition.dragCollapseBy(
                                                    remaining,
                                                    dragDistancePx,
                                                )
                                                if (consumed != 0f) change.consume()
                                            }
                                        }
                                    }
                                    // -1：上滑锁定，整个手势都不再写 collapse
                                }
                            } else if (dy != 0f) {
                                if (transition.collapse <= 0f) {
                                    // 02 端点：向下喂给 OffsetOverscrollEffect 做整组
                                    // 过滚动；向上先收到拉出量归零，剩余才推进 02→04。
                                    val consumedByOverscroll = applyCompactOverscroll(dy, change)
                                    val remaining = dy - consumedByOverscroll
                                    if (remaining != 0f) {
                                        val consumed = transition.dragCollapseBy(
                                            remaining,
                                            dragDistancePx,
                                        )
                                        if (consumed != 0f) change.consume()
                                    }
                                } else {
                                    val consumed = transition.dragCollapseBy(dy, dragDistancePx)
                                    if (consumed != 0f) change.consume()
                                }
                            }
                        }
                        if (!change.pressed) break
                    }
                    // 04 上滑起手只做过滚动：collapse 仍在 1 时不 settle，避免任何吸附
                    // 动画打断橡皮筋回弹；下滑过（collapse < 1）则松手按最终进度/速度
                    // 判回 02 还是回弹 04。04 列表已下滚时手势归列表收束，根级同样不 settle。
                    // 横向让位的手势不参与转场收束，避免拿横滑的竖向抖动去触发 settle。
                    val shouldSettle = !gaveWayToHorizontal && when (transition.stage) {
                        DayStage.Schedule ->
                            drivesCollapse && transition.collapse < 1f
                        else -> true
                    }
                    // 还拉着整组过滚动时只做回位动画，不启动 02→04 吸附。
                    // 04→02 已经拖过 02 的情况也在这里：先无动画地提交 02，
                    // 再由 OffsetOverscrollEffect 的默认临界阻尼弹簧把内容送回原位；
                    // 效果挂在页面根上，不会因切回 Compact 被移除而硬切。
                    val compactPulled = !gaveWayToHorizontal &&
                        transition.collapse <= 0f &&
                        compactOverscroll.currentOffset != Offset.Zero
                    if (compactPulled) {
                        if (transition.stage == DayStage.Schedule) {
                            transition.settle(0f, flingThresholdPx)
                        }
                        val velocityY = tracker.calculateVelocity().y
                        scope.launch {
                            compactOverscroll.applyToFling(Velocity(0f, velocityY)) {
                                Velocity.Zero
                            }
                        }
                    } else if (shouldSettle) {
                        transition.settle(tracker.calculateVelocity().y, flingThresholdPx)
                    }
                }
            }
            // 02 的整组过滚动直接复用 OffsetOverscrollEffect：节点挂在页面最外层，
            // 渲染时把整个页面内容（图片卡、日期组、内容列表等）作为一个图层平移。
            .overscroll(compactOverscroll),
    ) {
        val statusBarDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
        val navBarDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value
        val metrics = remember(
            maxWidth.value,
            maxHeight.value,
            statusBarDp,
            navBarDp,
            deviceCornerRadius.value,
        ) {
            DayMetrics(
                screenWidth = maxWidth.value,
                screenHeight = maxHeight.value,
                statusBar = statusBarDp,
                navBar = navBarDp,
                deviceCornerRadius = deviceCornerRadius.value,
            )
        }

        val showContent by remember { derivedStateOf { transition.open > 0f } }
        val showTopScrim by remember { derivedStateOf { transition.open < 1f } }
        val showTopBar by remember { derivedStateOf { transition.collapse > 0f } }
        val showBand by remember(metrics) {
            derivedStateOf {
                DayGeometry.frame(metrics, transition.open, transition.collapse).bandHeight > 0f
            }
        }

        val gestureAllowed = active && !sheetMounted && !transition.isAnimating
        val contentUnscrollable =
            !listState.canScrollForward && !listState.canScrollBackward
        // 01 的上滑仍由整屏 draggable 负责；02/04 的空占位由根级 pointerInput 统一驱动，
        // 不再额外挂 Schedule 的整屏层，避免同一次拖动被写两遍。
        val fullScreenGestureEnabled =
            gestureAllowed && transition.stage == DayStage.Calendar
        val cardGestureEnabled = gestureAllowed && transition.stage == DayStage.Compact
        // 内容不可滚时根级 pointerInput 已接管图片卡/内容卡的跟手；只有未来内容真正
        // 可滚、根级驱动让位时，才由 02 卡面自己的 draggable 接管图片区域。
        val cardDragEnabled = cardGestureEnabled && !contentUnscrollable
        // 04 完全收起后才启用内容区滚动/OverScroll；02/03 的内容区由根级转场手势接管。
        val contentUserScrollEnabled =
            gestureAllowed && transition.stage == DayStage.Schedule

        val fullScreenDragState = rememberDraggableState { delta ->
            transition.dragOpenBy(delta, dragDistancePx)
        }
        val cardDragState = rememberDraggableState { delta ->
            transition.dragCollapseBy(delta, dragDistancePx)
        }
        val compactCard = remember(metrics) {
            DayGeometry.frame(metrics, open = 1f, collapse = 0f).image
        }

        // 0) 图片卡与页面底色作为同一个 Haze 源：图片卡下方的模糊带同时采到
        // 图片和卡片外底色，图片卡的边界（底边/两侧）会一起被晕开。01 全屏图
        // 完全盖住底色，不改变现有画面。
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(imageHazeState)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 1) 图片卡：全屏图固定解码尺寸，父层随进度改变尺寸/位置/圆角，
            // 图片在卡内居中裁切。
            Layout(
                content = {
                    DayBackgroundImage(
                        context = context,
                        selectedImage = selectedImage,
                        modifier = Modifier
                            .requiredSize(metrics.screenWidth.dp, metrics.screenHeight.dp)
                            .graphicsLayer {
                                val frame = DayGeometry.frame(
                                    metrics,
                                    transition.open,
                                    transition.collapse,
                                )
                                val scale = frame.imageScale(metrics)
                                scaleX = scale
                                scaleY = scale
                                translationX = frame.imageTranslationX(metrics) * pxPerDp
                                translationY = frame.imageTranslationY(metrics) * pxPerDp
                            },
                    )
                },
                modifier = Modifier
                    .offset {
                        val frame = DayGeometry.frame(
                            metrics,
                            transition.open,
                            transition.collapse,
                        )
                        IntOffset(
                            (frame.image.left * pxPerDp).roundToInt(),
                            (frame.image.top * pxPerDp).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        val frame = DayGeometry.frame(
                            metrics,
                            transition.open,
                            transition.collapse,
                        )
                        // 02→04 后段渐隐：白卡被 Overscroll 拖上去时不再露出台历残影。
                        alpha = frame.imageAlpha
                        clip = true
                        shape = RoundedRectangle(frame.imageRadius.dp)
                    },
            ) { measurables, _ ->
                val frame = DayGeometry.frame(metrics, transition.open, transition.collapse)
                val layoutWidth = (frame.image.width * pxPerDp).roundToInt().coerceAtLeast(1)
                val layoutHeight = (frame.image.height * pxPerDp).roundToInt().coerceAtLeast(1)
                val child = measurables[0].measure(
                    Constraints.fixed(
                        (metrics.screenWidth * pxPerDp).roundToInt().coerceAtLeast(1),
                        (metrics.screenHeight * pxPerDp).roundToInt().coerceAtLeast(1),
                    ),
                )
                layout(layoutWidth, layoutHeight) {
                    child.place(0, 0)
                }
            }
        }

        // 2) 图片卡下段模糊带：底边永远是白卡顶边，白卡顶过卡片中点后高度自然归零。
        if (showBand) {
            Layout(
                content = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                drawContent()
                                val frame = DayGeometry.frame(
                                    metrics,
                                    transition.open,
                                    transition.collapse,
                                )
                                val tint = frame.bandTintAlpha
                                if (tint > 0f) {
                                    // 黑色压暗只作用在图片卡内部，并复用图片卡同一 RoundedRectangle
                                    // 轮廓裁剪：底部两角随 imageRadius 一起从机身圆角过渡到
                                    // DayCardShape，页面背景与卡间间隙不再被压暗。
                                    val cardWidthPx =
                                        (frame.image.width * pxPerDp).coerceAtLeast(1f)
                                    val cardHeightPx =
                                        (frame.image.height * pxPerDp).coerceAtLeast(1f)
                                    val outline = RoundedRectangle(frame.imageRadius.dp).createOutline(
                                        size = Size(cardWidthPx, cardHeightPx),
                                        layoutDirection = layoutDirection,
                                        density = this,
                                    )
                                    val cardClip = Path().apply { addOutline(outline) }
                                    // 图片卡在模糊带局部坐标系里的落位：band child 顶部 = bandTop
                                    cardClip.translate(
                                        Offset(
                                            x = frame.image.left * pxPerDp,
                                            y = (frame.image.top - frame.bandTop) * pxPerDp,
                                        ),
                                    )
                                    clipPath(cardClip) {
                                        drawRect(bandTintBrush, alpha = tint)
                                    }
                                }
                            }
                            .hazeBlur(
                                input = hazeSources(imageHazeState),
                                style = dayBandStyle(),
                                performanceMode = HazePerformanceMode.Balanced,
                            ),
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    // 模糊带与图片卡同一条淡出曲线，避免图片卡消失后只剩一条模糊。
                    .graphicsLayer {
                        alpha = DayGeometry.frame(
                            metrics,
                            transition.open,
                            transition.collapse,
                        ).imageAlpha
                    },
            ) { measurables, constraints ->
                val frame = DayGeometry.frame(metrics, transition.open, transition.collapse)
                val bandTopPx = (frame.bandTop * pxPerDp).roundToInt()
                val bandHeightPx = (frame.bandHeight * pxPerDp).roundToInt()
                val child = measurables[0].measure(
                    Constraints.fixed(constraints.maxWidth, bandHeightPx.coerceAtLeast(1)),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (bandHeightPx > 0) child.place(0, bandTopPx)
                }
            }
        }

        // 3) 01 顶部渐变磨砂：上移 + 淡出，02 起移出组合。
        if (showTopScrim) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(DayTopScrimHeight)
                    .graphicsLayer {
                        val alpha = 1f - transition.open
                        this.alpha = alpha
                        translationY = -(1f - alpha) * DayTopScrimExitOffset.value * pxPerDp
                    }
                    .hazeBlur(
                        input = hazeSources(imageHazeState),
                        style = dayTopScrimStyle(),
                        performanceMode = HazePerformanceMode.Balanced,
                    ),
            )
        }

        // 4) 「上滑开启新一天」提示：跟随顶部磨砂一起上移淡出。
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = DayHintTopPadding)
                .graphicsLayer {
                    val alpha = 1f - transition.open
                    this.alpha = alpha
                    translationY = -(1f - alpha) * DayHintExitOffset.value * pxPerDp
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lock_open),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.day_swipe_hint),
                fontSize = 15.sp,
                color = Color.White,
            )
        }

        // 5) 整屏手势层：01 上滑开台历 / 04 下滑收顶栏；透明、不画内容。
        if (fullScreenGestureEnabled) {
            Box(
                Modifier
                    .fillMaxSize()
                    .draggable(
                        state = fullScreenDragState,
                        orientation = Orientation.Vertical,
                    ),
            )
        }

        // 6) 02 图片卡手势层：上滑收台历 + 点击原路重播回 01；固定在 02 卡片矩形。
        if (cardGestureEnabled) {
            Box(
                Modifier
                    .offset(x = compactCard.left.dp, y = compactCard.top.dp)
                    .width(compactCard.width.dp)
                    .height(compactCard.height.dp)
                    .clickable(
                        enabled = cardGestureEnabled,
                        role = Role.Button,
                        onClickLabel = cardBackLabel,
                        onClick = {
                            compactOverscroll.reset()
                            transition.replayToCalendar()
                        },
                    )
                    .draggable(
                        state = cardDragState,
                        orientation = Orientation.Vertical,
                        enabled = cardDragEnabled,
                    )
                    .semantics { contentDescription = cardBackLabel },
            )
        }

        // 7) 日期组：01 左下 → 02 卡内左下 → 02→04 随卡下移；日期行尺寸不变。
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = DayDateEdgePadding)
                .padding(bottom = navBarDp.dp + NavReserve + DayDateBottomGap)
                .offset {
                    val frame = DayGeometry.frame(metrics, transition.open, transition.collapse)
                    IntOffset(
                        (frame.dateTranslationX * pxPerDp).roundToInt(),
                        (frame.dateTranslationY * pxPerDp).roundToInt(),
                    )
                }
                .graphicsLayer {
                    // 日期组（大数字、日期行、图片 icon）整组淡出：04 被 Overscroll
                    // 拖出白卡时不再露出白色日期文字/图标。
                    alpha = DayGeometry.frame(
                        metrics,
                        transition.open,
                        transition.collapse,
                    ).dateAlpha
                },
        ) {
            // 日期 / 日期文字 / 图片 icon 作为一组整体缩放：锚在组内左下角，
            // 02→04 跟随图片卡收窄，三个元素同步缩小并随卡片下移。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val frame = DayGeometry.frame(
                            metrics,
                            transition.open,
                            transition.collapse,
                        )
                        transformOrigin = TransformOrigin(0f, 1f)
                        scaleX = frame.dateScale
                        scaleY = frame.dateScale
                    },
            ) {
                Text(
                    text = dayOfMonth.toString(),
                    fontSize = 64.sp,
                    fontFamily = SerifDisplay,
                    color = Color.White,
                    modifier = Modifier.graphicsLayer {
                        // 设计稿实测 01/02 的“21”等大；组级缩放之外仍保留这个 token。
                        transformOrigin = TransformOrigin(0f, 1f)
                        scaleX = DayDateNumberScale
                        scaleY = DayDateNumberScale
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.date_title_format, yearMonth, weekday),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        letterSpacing = (-0.3).sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        painter = painterResource(R.drawable.ic_image),
                        contentDescription = stringResource(R.string.cd_day_background),
                        tint = Color.White,
                        modifier = Modifier
                            .size(30.dp)
                            .pressClickable(
                                shape = CircleShape,
                                scale = 1.10f,
                                enabled = active && !transition.isAnimating && dateIconVisible,
                            ) {
                                compactOverscroll.reset()
                                sheetMounted = true
                                sheetVisible = true
                            },
                    )
                }
            }
        }

        // 8) 白卡就是日程页的占位内容，也是滚动列表的第一条；高度固定 235dp。
        // 04 滑到底时 Overscroll 会拖动这张白卡本身；后续真实内容直接排在它后面。
        // 内容视口铺满全屏：02 时视口顶贴住白卡顶（不盖图片卡），04 时抬到屏幕顶，
        // 白卡与后续内容才能滚进顶栏下面。白卡位置由「视口 offset + 首条 spacer」
        // 两段之和决定，恒等于 frame.content.top。
        if (showContent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(contentHazeState),
            ) {
                ProvideOverscroll {
                    RubberBandLazyColumn(
                        state = listState,
                        userScrollEnabled = contentUserScrollEnabled,
                        modifier = Modifier
                            .fillMaxSize()
                            .offset {
                                val frame = DayGeometry.frame(
                                    metrics,
                                    transition.open,
                                    transition.collapse,
                                )
                                // 02 贴白卡顶 → 04 抬到屏幕顶；collapse 越大视口越早
                                // 覆盖顶栏区域，内容才有机会进入顶栏下面。
                                val viewportTop =
                                    frame.content.top * (1f - transition.collapse)
                                IntOffset(0, (viewportTop * pxPerDp).roundToInt())
                            },
                    ) {
                        // 首条 spacer 把白卡按当前进度放到 frame.content.top；高度在
                        // measure 阶段读转场进度，拖拽每帧只触发布局、不触发重组。
                        item(key = "contentTopSpacer") {
                            Spacer(
                                Modifier.layout { measurable, constraints ->
                                    val frame = DayGeometry.frame(
                                        metrics,
                                        transition.open,
                                        transition.collapse,
                                    )
                                    val heightPx =
                                        (frame.content.top * transition.collapse * pxPerDp)
                                            .roundToInt()
                                            .coerceAtLeast(0)
                                    val placeable = measurable.measure(
                                        Constraints.fixed(constraints.maxWidth, heightPx),
                                    )
                                    layout(constraints.maxWidth, heightPx) {
                                        placeable.place(0, 0)
                                    }
                                },
                            )
                        }
                        // 第二条 = 当前白卡占位内容；后续日程条目继续 item { } 追加。
                        item(key = "dayCard") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PageHorizontalSpacing)
                                    .height(DayContentHeight)
                                    .clip(DayCardShape)
                                    .background(MaterialTheme.colorScheme.surface),
                            )
                        }
                    }
                }
            }
        }

        // 9) 日程顶栏：内容卡顶部贴住它；Haze 采样白卡，标题样式复刻 ScheduleHeader。
        if (showTopBar) {
            DayTopBar(
                title = topBarTitle,
                height = statusBarDp.dp + DayTopBarRowHeight + DayTopBarGap,
                contentAlpha = {
                    DayGeometry.frame(
                        metrics,
                        transition.open,
                        transition.collapse,
                    ).topBarContentAlpha
                },
                backgroundAlpha = {
                    DayGeometry.frame(
                        metrics,
                        transition.open,
                        transition.collapse,
                    ).topBarBackgroundAlpha
                },
                translationYDp = {
                    DayGeometry.frame(
                        metrics,
                        transition.open,
                        transition.collapse,
                    ).topBarTranslationY
                },
                hazeState = contentHazeState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // 10) 图片选择浮层保持最后挂载，盖住整个页面（含顶栏）。
        if (sheetMounted) {
            DayImageSheet(
                visible = sheetVisible,
                images = images,
                onImported = { imported ->
                    images = images + imported
                    DayImageStore.saveImages(context, images)
                },
                onSelect = { name ->
                    selectedImage = name
                    DayImageStore.saveSelected(context, name)
                    sheetVisible = false
                },
                onDelete = { name ->
                    DayImageStore.deleteImage(context, name)
                    images = images - name
                    DayImageStore.saveImages(context, images)
                    if (selectedImage == name) {
                        selectedImage = null
                        DayImageStore.saveSelected(context, null)
                    }
                },
                onDismiss = { sheetVisible = false },
                onDismissed = { sheetMounted = false },
            )
        }
    }
}

/**
 * 背景图：有用户选中的图片就换上去（文件缺失/解码失败回退默认测试图）。
 * 尺寸由调用方通过 requiredSize 固定为屏幕大小，图片卡内部只做图层缩放/平移，
 * 换卡尺寸时不会触发 Coil 重新按新尺寸请求。
 */
@Composable
private fun DayBackgroundImage(
    context: Context,
    selectedImage: String?,
    modifier: Modifier = Modifier,
) {
    val backgroundImage = remember(selectedImage) {
        selectedImage?.let { DayImageStore.imageFile(context, it) }
    }
    if (backgroundImage != null) {
        AsyncImage(
            model = backgroundImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            error = painterResource(R.drawable.bg_day_test),
            modifier = modifier,
        )
    } else {
        Image(
            painter = painterResource(R.drawable.bg_day_test),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

/**
 * 日程页顶栏：整条渐变模糊 + 单行日期标题。
 *
 * 背景与内容拆成两层：背景（页面底色 + Haze）固定在顶栏位置，只随
 * [backgroundAlpha] 淡入淡出；内容层独立使用 [contentAlpha] 与 [translationYDp]，
 * 02→04 先淡入下移，04→02 后淡出上移。所有进度在 graphicsLayer 的绘制阶段读取，
 * 跟手进出场不触发重组；Haze 采样内容卡（[hazeState]），与待办顶栏同一配方。
 * 标题左侧 33dp、23sp SemiBold / onSurface / -0.3sp，与 ScheduleHeader 的日期行一致。
 */
@Composable
private fun DayTopBar(
    title: String,
    height: Dp,
    contentAlpha: () -> Float,
    backgroundAlpha: () -> Float,
    translationYDp: () -> Float,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        // 背景层固定在顶栏位置，只做透明度：晚于内容出现、早于内容消失，不参与位移。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backgroundAlpha() }
                .background(MaterialTheme.colorScheme.background)
                .hazeBlur(
                    input = hazeSources(hazeState),
                    style = hazeTopBarStyle(),
                    performanceMode = HazePerformanceMode.Balanced,
                ),
        )
        // 内容层：淡入/淡出 + 上下位移；04 停在原位，02 方向缩回上方。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = contentAlpha()
                    translationY = translationYDp() * density
                }
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DayTopBarRowHeight)
                    .padding(horizontal = DayTopBarEdgePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = (-0.3).sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(DayTopBarGap))
        }
    }
}

/** 01 顶部磨砂配方：顶部最强向下渐隐，黑色 tint 24%（照片底恒定用黑，不随主题）。 */
private fun dayTopScrimStyle(): HazeBlurStyle = HazeBlurStyle {
    blurRadius(25.dp)
    progressive(HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f))
    colorEffects(listOf(HazeColorEffect.tint(Color.Black.copy(alpha = 0.24f))))
    noiseFactor(0f)
}

/** 图片卡下段模糊带：只出模糊，tint 由 drawWithContent 的渐变叠层按进度控制。 */
private fun dayBandStyle(): HazeBlurStyle = HazeBlurStyle {
    blurRadius(25.dp)
    progressive(HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f))
    noiseFactor(0f)
}

/** 从 Context 包装链里找 Activity（状态栏图标需要 window）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** [IsoDate] → (年, 月, 日)（UTC 日历日换算，同 BottomDateSelectCard/RepeatSchedule 的既有模式）。 */
private fun IsoDate.toYearMonthDay(): Triple<Int, Int, Int> {
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochDay * MillisPerDay
    }
    return Triple(
        calendar.get(GregorianCalendar.YEAR),
        calendar.get(GregorianCalendar.MONTH) + 1,
        calendar.get(GregorianCalendar.DAY_OF_MONTH),
    )
}
