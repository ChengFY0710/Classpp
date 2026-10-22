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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.ProvideOverscroll
import com.fangyi.classpp.ui.motion.RubberBandLazyColumn
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.rememberDeviceCornerRadius
import com.fangyi.classpp.ui.navigation.NavReserve
import com.fangyi.classpp.ui.theme.DayCardShape
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
import kotlin.math.abs
import kotlin.math.roundToInt

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
            transition.resetForNewDay()
            persistedStage = DayStage.Calendar
        }
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

    val listState = rememberLazyListState()
    val dragDistancePx = with(density) { DayDragDistance.toPx() }
    val flingThresholdPx = with(density) { DayFlingVelocity.toPx() }
    // 内容卡拖拽的兜底桥：nested scroll 正常时由它消费；若某些设备/版本下 LazyColumn
    // 的空占位没有把 nested scroll 派发上来，根级 pointerInput 直接接手，避免 02→04 死区。
    val gestureBridge = remember { DayGestureBridge() }
    val contentBounds = remember { DayContentBounds() }
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
                    deltaY < 0f && transition.collapse < 1f ->
                        transition.dragCollapseBy(deltaY, dragDistancePx)
                    deltaY > 0f &&
                        transition.open >= 1f &&
                        transition.collapse > 0f &&
                        !listState.canScrollBackward ->
                        transition.dragCollapseBy(deltaY, dragDistancePx)
                    else -> 0f
                }
                if (consumed != 0f) {
                    // 本次手势已由 nested scroll 真正消费；根级 pointerInput 的兜底驱动应退让，
                    // 避免同一次拖动被写两遍进度。若 nested scroll 只透传（返回 0），不置位，
                    // 兜底路径仍可补救。
                    gestureBridge.nestedScrollSeen = true
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
                    // 端点上的快速甩动：02 上甩收台历、04 下甩回 02
                    transition.collapse <= 0f && available.y < -flingThresholdPx -> true
                    transition.collapse >= 1f && available.y > flingThresholdPx -> true
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
            // 根级抬手观察器：不消费事件，只取松手速度交给状态机吸附。
            // 为什么不用 draggable 的 onDragStopped：内容卡走 nested scroll，拖拽层走
            // draggable，两条路径只有这里能统一拿到「手已抬起」。
            .pointerInput(active, sheetMounted, flingThresholdPx, dragDistancePx) {
                if (!active || sheetMounted) return@pointerInput
                val tracker = VelocityTracker()
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 空占位内容不可滚时，若本次触摸落在白卡内，nested scroll 万一没派发，
                    // 由本观察器直接驱动 02/04 段的 collapse；它不消费事件，内容滚动/橡皮筋
                    // 仍照常运行。nested scroll 真正消费后会把 nestedScrollSeen 置真，这里让位。
                    val contentIdle =
                        !listState.canScrollForward && !listState.canScrollBackward
                    gestureBridge.nestedScrollSeen = false
                    gestureBridge.fromContent =
                        contentIdle && contentBounds.value.contains(down.position)
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val pointerId = down.id
                    var lastY = down.position.y
                    var totalDy = 0f
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val dy = change.position.y - lastY
                        lastY = change.position.y
                        totalDy += dy
                        if (!dragged && abs(totalDy) > touchSlop) dragged = true
                        if (dragged &&
                            gestureBridge.fromContent &&
                            !gestureBridge.nestedScrollSeen
                        ) {
                            transition.dragCollapseBy(dy, dragDistancePx)
                        }
                        if (!change.pressed) break
                    }
                    transition.settle(tracker.calculateVelocity().y, flingThresholdPx)
                    gestureBridge.fromContent = false
                }
            },
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
        val fullScreenGestureEnabled = gestureAllowed &&
            (transition.stage == DayStage.Calendar || transition.stage == DayStage.Schedule)
        val cardGestureEnabled = gestureAllowed && transition.stage == DayStage.Compact
        val contentUserScrollEnabled = gestureAllowed

        val fullScreenDragState = rememberDraggableState { delta ->
            when (transition.stage) {
                DayStage.Calendar -> transition.dragOpenBy(delta, dragDistancePx)
                DayStage.Schedule -> transition.dragCollapseBy(delta, dragDistancePx)
                DayStage.Compact -> Unit
            }
        }
        val cardDragState = rememberDraggableState { delta ->
            transition.dragCollapseBy(delta, dragDistancePx)
        }
        val compactCard = remember(metrics) {
            DayGeometry.frame(metrics, open = 1f, collapse = 0f).image
        }

        // 1) 图片卡：全屏图固定解码尺寸，父层随进度改变尺寸/位置/圆角，图片在卡内居中裁切。
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
                    val frame = DayGeometry.frame(metrics, transition.open, transition.collapse)
                    IntOffset(
                        (frame.image.left * pxPerDp).roundToInt(),
                        (frame.image.top * pxPerDp).roundToInt(),
                    )
                }
                .hazeSource(imageHazeState)
                .graphicsLayer {
                    val frame = DayGeometry.frame(metrics, transition.open, transition.collapse)
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
                modifier = Modifier.fillMaxSize(),
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
                        onClick = { transition.replayToCalendar() },
                    )
                    .draggable(
                        state = cardDragState,
                        orientation = Orientation.Vertical,
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
                    IntOffset(0, (frame.dateTranslationY * pxPerDp).roundToInt())
                },
        ) {
            Text(
                text = dayOfMonth.toString(),
                fontSize = 64.sp,
                fontFamily = SerifDisplay,
                color = Color.White,
                modifier = Modifier.graphicsLayer {
                    // 设计稿实测 01/02 的“21”等大；保留左下锚点缩放，后续只改 token。
                    transformOrigin = TransformOrigin(0f, 1f)
                    scaleX = DayDateNumberScale
                    scaleY = DayDateNumberScale
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.date_title_format, yearMonth, weekday),
                    fontSize = 23.sp,
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
                        .size(28.dp)
                        .pressClickable(
                            shape = CircleShape,
                            scale = 1.10f,
                            enabled = active && !transition.isAnimating,
                        ) {
                            sheetMounted = true
                            sheetVisible = true
                        },
                )
            }
        }

        // 8) 白底内容卡：内部固定 285dp 测量、外层按当前 235→285dp 裁切，
        // 高度变化不重测列表；橡皮筋滚动由 ProvideOverscroll + RubberBandLazyColumn 接入。
        if (showContent) {
            ProvideOverscroll {
                Layout(
                    content = {
                        RubberBandLazyColumn(
                            state = listState,
                            userScrollEnabled = contentUserScrollEnabled,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item { Spacer(Modifier.height(1.dp)) }
                        }
                    },
                    modifier = Modifier
                        .offset {
                            val frame = DayGeometry.frame(
                                metrics,
                                transition.open,
                                transition.collapse,
                            )
                            IntOffset(
                                (frame.content.left * pxPerDp).roundToInt(),
                                (frame.content.top * pxPerDp).roundToInt(),
                            )
                        }
                        .onGloballyPositioned { contentBounds.value = it.boundsInWindow() }
                        .hazeSource(contentHazeState)
                        .graphicsLayer {
                            clip = true
                            shape = DayCardShape
                        }
                        .background(MaterialTheme.colorScheme.surface),
                ) { measurables, _ ->
                    val frame = DayGeometry.frame(metrics, transition.open, transition.collapse)
                    val cardWidth = (frame.content.width * pxPerDp).roundToInt().coerceAtLeast(1)
                    val cardHeight = (frame.content.height * pxPerDp).roundToInt().coerceAtLeast(1)
                    val child = measurables[0].measure(
                        Constraints.fixed(
                            cardWidth,
                            (DayContentScheduleHeight.value * pxPerDp)
                                .roundToInt()
                                .coerceAtLeast(cardHeight),
                        ),
                    )
                    layout(cardWidth, cardHeight) {
                        child.place(0, 0)
                    }
                }
            }
        }

        // 9) 日程顶栏：内容卡顶部贴住它；Haze 采样白卡，标题样式复刻 ScheduleHeader。
        if (showTopBar) {
            DayTopBar(
                title = topBarTitle,
                height = statusBarDp.dp + DayTopBarRowHeight + DayTopBarGap,
                alpha = {
                    DayGeometry.frame(metrics, transition.open, transition.collapse).topBarAlpha
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
 * [alpha]/[translationYDp] 在 graphicsLayer 的绘制阶段读取转场进度，跟手进出场不触发
 * 重组；Haze 采样内容卡（[hazeState]），与待办顶栏同一配方。标题左侧 33dp、23sp
 * SemiBold / onSurface / -0.3sp，与 ScheduleHeader 的日期行一致。
 */
@Composable
private fun DayTopBar(
    title: String,
    height: Dp,
    alpha: () -> Float,
    translationYDp: () -> Float,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                this.alpha = alpha()
                translationY = translationYDp() * density
            }
            .background(MaterialTheme.colorScheme.background)
            .hazeBlur(
                input = hazeSources(hazeState),
                style = hazeTopBarStyle(),
                performanceMode = HazePerformanceMode.Balanced,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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

/**
 * 单次手势的跨路径标记：nested scroll 与根级 pointerInput 都读同一实例。
 * [nestedScrollSeen] 只在 nested scroll 真正消费了位移时置真；[fromContent] 标记
 * 本次按下是否落在白卡内（空占位且内容不可滚时，根级观察器才做兜底驱动）。
 * 普通字段而非 Compose state：只在手势回调里读写，不参与组合。
 */
private class DayGestureBridge {
    var nestedScrollSeen: Boolean = false
    var fromContent: Boolean = false
}

/** 白卡当前窗口矩形（onGloballyPositioned 写入，手势按下时读，不触发重组）。 */
private class DayContentBounds {
    var value: Rect = Rect.Zero
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
