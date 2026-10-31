package com.fangyi.classpp.ui.day

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.OpResult
import com.fangyi.classpp.data.TagRepository
import com.fangyi.classpp.data.TodoFilter
import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.calendar.ChineseDayMarks
import com.fangyi.classpp.data.datesCoveredWithin
import com.fangyi.classpp.data.expandRepeatOccurrences
import com.fangyi.classpp.data.filterTodos
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.sortedFor
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.components.BottomFadeScrim
import com.fangyi.classpp.ui.components.DayCalendar
import com.fangyi.classpp.ui.components.DayCalendarFoldState
import com.fangyi.classpp.ui.components.DayCourseAlert
import com.fangyi.classpp.ui.components.DayCourseAlertState
import com.fangyi.classpp.ui.components.DayTask
import com.fangyi.classpp.ui.components.DayTaskAlldayAlert
import com.fangyi.classpp.ui.components.DayTimeline
import com.fangyi.classpp.ui.components.DayTodoAlert
import com.fangyi.classpp.ui.components.hazeSources
import com.fangyi.classpp.ui.components.hazeTopBarStyle
import com.fangyi.classpp.ui.motion.DragDirection
import com.fangyi.classpp.ui.motion.DragDirectionLock
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.OffsetOverscrollEffect
import com.fangyi.classpp.ui.motion.ProvideOverscroll
import com.fangyi.classpp.ui.motion.RubberBandLazyColumn
import com.fangyi.classpp.ui.motion.TiltParallaxState
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.rememberDeviceCornerRadius
import com.fangyi.classpp.ui.motion.rememberTiltParallax
import com.fangyi.classpp.ui.motion.sharePointerInputWithSiblings
import com.fangyi.classpp.ui.motion.tiltParallax
import com.fangyi.classpp.ui.navigation.NavReserve
import com.fangyi.classpp.ui.note.CalendarPickerHost
import com.fangyi.classpp.ui.note.NewTodoSheet
import com.fangyi.classpp.ui.note.TodoDetailSheet
import com.fangyi.classpp.ui.note.deadlineColor
import com.fangyi.classpp.ui.note.rememberCalendarAddState
import com.fangyi.classpp.ui.note.repeatCardText
import com.fangyi.classpp.ui.note.toAbsoluteDateText
import com.fangyi.classpp.ui.schedule.CourseColor
import com.fangyi.classpp.ui.schedule.CourseDetailSheet
import com.fangyi.classpp.ui.schedule.CourseTodoSheet
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.DayCardShape
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
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
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 接管 02↔04 吸附所需的最小竖向确认距离（× touchSlop）。
 *
 * 横滑起手常带一小段竖向弧线，刚过方向锁阈值就去停吸附，会让横滑把在途吸附
 * 打断、再按阈值重新吸附（表现为「左右滑触发阈值」）。因此要求竖向位移达到
 * 这个距离、且仍明显压过横向位移，才允许接管；未确认前一律不消费、不动画。
 */
private const val SnapTakeOverConfirmSlopFactor = 2f

/** 上课提醒卡与日历卡的间距 */
private val CourseAlertTopSpacing = 10.dp

/** 上课提醒卡的时刻对表间隔：倒计时按分钟显示，30s 步进保证一分钟内跳字 */
private const val CourseAlertResyncMillis = 30_000L

/** 时间轴与上方提醒卡（全天任务卡 / 待办提醒卡）的间距；两张卡都折叠时自然成为与上课提醒卡的间距 */
private val TimelineTopSpacing = 12.dp

/**
 * 末行之后的尾部留白：扩大内容列表的可滚动范围（与课表页 CourseGrid 同款，
 * 叠加在 contentPadding 的导航栏 inset + NavReserve 之上）。
 * 内容较少时末段时间轴/卡片也能拉离屏底阅读；按需调整此常量即可。
 */
private val TrailingScrollSpace = 120.dp

/**
 * 日程页（AppTab.Agenda）：手势驱动的台历三态页面。
 *
 * 真实状态只有 01 台历（[DayStage.Calendar]）、02 台历缩小态（[DayStage.Compact]）、
 * 04 台历完全收起态（[DayStage.Schedule]）；设计稿 03 只是 02↔04 的中间构图，由
 * open/collapse 两个进度派生，不存在停留点。
 *
 * 01→02：图片从全屏连续收成顶部圆角卡、日期组移入卡片、底部模糊层变为卡片下段
 * 模糊带、白卡从屏底升入、底部渐变遮罩（BottomFadeScrim）从屏底跟手升入。02→04：
 * 白卡上移、图片卡收窄下移、顶栏渐入。02 禁止下滑
 * 回 01；点 02 图片卡用同一曲线重播 02→01（纯视觉，不写持久化）。所有几何集中在
 * [DayGeometry]，每帧只在 layout/draw 阶段读进度，拖拽不触发重组。
 *
 * 背景图仍是唯一图片源（[DayImageStore]）；ic_image 仍呼起 [DayImageSheet]，浮层
 * 在场时继续藏底部导航栏。[active] 由调用方按当前 tab 透传，仅用于状态栏图标在
 * 非 Agenda 页恢复主题默认，不参与转场与导航栏布局。
 *
 * [todoRepository] 喂日历卡的待办小点与日历卡下方的待办提醒卡（DayTodoAlert）：前者
 * 按 DayCalendar 回报的网格窗口取覆盖日期（[datesCoveredWithin]），后者按截止窗口
 * （逾期 ~ 后天）取未完成待办；未注入（预览/首帧）都按无待办处理。待办提醒卡行与时间轴
 * 待办卡可点击唤起待办详情浮层（TodoDetailSheet，与待办页同款两段式接线，含备注/步骤/
 * 倒计时与「编辑」叠层 NewTodoSheet）。
 *
 * [tagRepository] 只喂待办提醒卡的标签配色（与待办页同源，见 tagColorMap）；
 * 未注入（预览/首帧）标签回落主题 Primary。
 *
 * [scheduleRepository] 只喂日历卡下方的上课提醒卡（DayCourseAlert）：按今天的周号/星期/
 * 周规律取该上的课，结合当前时刻给出倒计时/上课中/课已上完/今日无课；未注入（预览/
 * 首帧）按「今日无课」处理。有课的上课提醒卡与时间轴上课卡（DayTaskCard.Course）可点击
 * 唤起课程详情浮层（CourseDetailSheet，与课表页同款两段式接线，含备注编辑、课程待办与
 * 「新建课程待办」叠层浮层），浮层在场时经 [onOverlayOverNavBarChange] 藏底部导航栏。
 */
@Composable
fun DayScreen(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    todoRepository: TodoRepository? = null,
    scheduleRepository: ScheduleRepository? = null,
    tagRepository: TagRepository? = null,
    onOverlayOverNavBarChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val today = LocalToday
    // 选中查看的日期：默认今天；跨零点 today 变化时随 remember(today) 自动重置回今天
    //（与下方跨天重置同拍）。日历点选/「回到今天」只改这里，页面日期文案、背景图、
    // 时间轴任务全部按它取
    var selectedDate by remember(today) { mutableStateOf(today) }
    // 日历点选跳日指令（02/04 态下发——内容区可见的两个形态）：目标日 + 递增会话号
    //（DayContentSwipe 的驱动 key）。相邻日回放手势式滑动、非相邻日走 CrossPage 层级交叉；
    // 两条路径都在转场起步时先提交日期（日历球/图片卡日期/背景图与动画同帧联动，不等
    // 落位）。01 台历页点击（内容不在视野内）与跨天重置不经此路径，照旧直接换日
    var jumpTargetDate by remember { mutableStateOf<IsoDate?>(null) }
    var jumpSession by remember { mutableIntStateOf(0) }
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
    // 01 静止态全屏图满幅直角；滑动一开始才取机身真实 R 角，01→02 再连续
    // 过渡到 DayCardShape 的 24dp。图片卡外层用 shapes 库的 RoundedRectangle
    // 裁剪，圆滑圆角与白卡同源。
    val deviceCornerRadius = rememberDeviceCornerRadius()

    // 图片采样与白卡采样分开：图片 scrim/模糊带只采图片，顶栏只采内容卡，
    // 避免两种材质互相串色（Haze 2 一个 State 可以承载多种源，这里刻意隔离）。
    val imageHazeState = rememberHazeState()
    val contentHazeState = rememberHazeState()

    // 「添加到日历」流程（待办详情菜单项唤起）：权限/列账户/写入/账户选择卡全在
    // CalendarAddFlow 编排（与待办页详情共用同一套），这里取状态与总入口
    val calendarAdd = rememberCalendarAddState()

    // 用户图片库与当前背景：首次组合从磁盘读，之后改动即时落盘（DayImageStore）
    var images by remember { mutableStateOf(DayImageStore.loadImages(context)) }
    var selectedImage by remember { mutableStateOf(DayImageStore.loadSelected(context)) }
    // 随机轮播：开启时背景取「当日命中」，命中即落盘锁定（DayImageStore）——当天内导入
    // 新图不重抽（背景不随图库变动切换），命中图被删才重抽，跨零点（today 变化）重抽；
    // 关闭或图库为空回退手动选中。查看其他日期时不落盘：按该日种子现抽当"那天的图"预览，
    // 不改动今天已锁定的命中
    var randomEnabled by remember { mutableStateOf(DayImageStore.loadRandomEnabled(context)) }
    // 壁纸视差开关：01 态背景图随重力倾斜微移（效果本体在 ui.motion 的 TiltParallax，
    // 状态接线见下方 tilt）；口径同随机轮播——首帧从磁盘读，改动即时落盘
    var tiltEnabled by remember { mutableStateOf(DayImageStore.loadTiltParallaxEnabled(context)) }
    var randomPick by remember { mutableStateOf(DayImageStore.loadRandomPick(context)) }
    // 命中在组合期同步算出（首帧即正确）：今天沿用已锁定命中，没有才按今天种子现抽；
    // 其他日期一律按该日种子现抽（种子保证同天同图库下重组结果稳定）。落盘只针对今天
    val dayPick = if (randomEnabled && images.isNotEmpty()) {
        if (selectedDate == today) {
            randomPick?.takeIf { (day, name) -> day == today.epochDay && name in images }
                ?: (today.epochDay to images[Random(today.epochDay).nextInt(images.size)])
        } else {
            selectedDate.epochDay to images[Random(selectedDate.epochDay).nextInt(images.size)]
        }
    } else {
        null
    }
    LaunchedEffect(dayPick) {
        if (dayPick != null && selectedDate == today && randomPick != dayPick) {
            randomPick = dayPick
            DayImageStore.saveRandomPick(context, dayPick.first, dayPick.second)
        }
    }
    val backgroundImage = if (randomEnabled && images.isNotEmpty()) {
        dayPick?.second
    } else {
        selectedImage
    }
    // 浮层两段式：mounted 决定是否在组合里，visible 管进出场——出场播完才移出组合
    var sheetMounted by remember { mutableStateOf(false) }
    var sheetVisible by remember { mutableStateOf(false) }
    // 课程详情浮层：被点课程 id（点上课提醒卡/时间轴上课卡进入）；空 = 未打开。
    // 条目从当前课表反查，课程被删/切课表后反查落空 → 请求清空 → 浮层自动关闭
    //（两段式约定同课表页，宿主块见底部挂载处）
    var detailTargetId by rememberSaveable { mutableStateOf("") }
    var detailMounted by remember { mutableStateOf<CourseEntry?>(null) }
    // 待办详情浮层：被点待办 id + 所点条目的分期日期（ISO 文本，空 = 非逐期卡）——
    // 点待办提醒卡行/时间轴待办卡进入；条目从原始待办列表反查（id 不随分期展开变化），
    // 待办被删后反查落空 → 请求清空 → 浮层自动关闭（两段式约定同待办页，宿主块同下）
    var todoDetailTargetId by rememberSaveable { mutableStateOf("") }
    var todoDetailTargetDate by rememberSaveable { mutableStateOf("") }
    var todoDetailMounted by remember { mutableStateOf<Todo?>(null) }
    // 编辑待办浮层：详情顶栏「编辑」进入（快照表单同待办页，叠在详情之上）；空 = 未打开
    var todoEditTargetId by rememberSaveable { mutableStateOf("") }
    var todoEditMounted by remember { mutableStateOf<Todo?>(null) }
    // 任一浮层（图片/课程详情/待办详情/待办编辑）在场：根级转场手势、嵌套滚动接管与内容
    // 列表滚动一并闭闸——根级 pointerInput 走 Initial pass，先于浮层子树拿到事件，不闭闸
    // 会把浮层顶栏的下拉关闭抢去驱动 02↔04 转场（浮层自身手势被吞，表现为列表跟手）
    val overlayMounted = sheetMounted || detailMounted != null ||
        todoDetailMounted != null || todoEditMounted != null
    LaunchedEffect(overlayMounted) {
        onOverlayOverNavBarChange(overlayMounted)
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
    // 回到 01 台历页即作废未消费的跳日指令：内容 item 可能随竖向滚动离场后重建（remember
    // 状态随之丢失），残留指令会在重组后重放一次旧转场。02/04 态内容常驻可见、指令有效
    LaunchedEffect(transition.stage) {
        if (transition.stage == DayStage.Calendar) jumpTargetDate = null
    }
    // 壁纸视差（01 态专属）：仅「本页活跃 + 图片浮层未开 + 停在 01」才注册重力传感器
    // （rememberTiltParallax 内部再叠加生命周期 RESUMED 门控，切后台必注销），转场离开
    // 01 即注销定格，02/04 态不空转。位移效果不靠注销收敛——靠图片层的 gate（1 - open）
    // 随转场进度收敛到 0，落位像素精确回到几何落位（见 DayBackgroundImage 接线处）
    val tiltActive by remember {
        derivedStateOf { active && !sheetMounted && transition.stage == DayStage.Calendar }
    }
    // active 管暂停（浮层/切 tab/离开 01 → 画面定格原地），enabled 管用户开关
    // （关闭 → 画面平滑滑回中心），两种失效走向不同，见 rememberTiltParallax
    val tilt = rememberTiltParallax(active = tiltActive, enabled = tiltEnabled)
    // 04 内容卡（DayCalendar）的折叠进度：与 open/collapse 同一驱动口径（跟手同步直写
    // + 松手吸附），由下方根级转场手势在 04 统一驱动——上滑先折叠日历、下滑先展开折叠
    // 再过渡 02（见手势块注释），02 阶段恒为展开态。
    val calendarFold = remember { DayCalendarFoldState(scope) }

    // 日历待办小点：活跃待办在日历网格窗口内覆盖的日期（显式日期 + 重复分期，见
    // datesCoveredWithin）。网格窗口由 DayCalendar 回报（锚点月翻页时变化，组合后首帧
    // 才到——首帧按无小点渲染，与仓库异步装载同拍）；待办回流实时刷新。
    val todosState = todoRepository?.todos?.collectAsState()
    val todos = todosState?.value ?: emptyList()
    // 待办分组列表（编辑待办浮层的选分组浮层用）：与待办同源（TodoRepository StateFlow）
    val groupsState = todoRepository?.groups?.collectAsState()
    val groups = groupsState?.value ?: emptyList()
    var calendarRange by remember { mutableStateOf<Pair<IsoDate, IsoDate>?>(null) }
    val todoMarkedDates = remember(todos, calendarRange) {
        val range = calendarRange ?: return@remember emptySet<IsoDate>()
        todos.datesCoveredWithin(range.first, range.second)
    }

    // 标签配色表（标签名 → 颜色）：待办提醒卡的标签胶囊取色，与待办页同源——用户标签
    // 取 TagEntry.colorArgb（null = 跟随主题 Primary），仅用户标签进表
    val userTagEntries by remember(tagRepository) {
        tagRepository?.tags ?: MutableStateFlow(emptyList())
    }.collectAsState()
    val primaryColor = MaterialTheme.colorScheme.primary
    val tagColorMap = remember(userTagEntries, primaryColor) {
        userTagEntries.associate { it.name to (it.colorArgb?.let(::Color) ?: primaryColor) }
    }
    // 待办提醒卡的勾选完成落库：回显与停一拍由 DayTodoAlert 负责，这里只做路由——
    // 重复待办按分期结算（setOccurrenceCompleted），普通待办整条结算；失败 Toast 提示
    val todoSaveFailedMessage = stringResource(R.string.todo_error_save_failed)
    val completeAlertTodo: (Todo) -> Unit = { todo ->
        val repository = todoRepository
        if (repository != null) {
            scope.launch {
                val date = todo.dates.firstOrNull()
                val result = if (todo.repeat.type != RepeatType.None && date != null) {
                    repository.setOccurrenceCompleted(todo.id, date, true)
                } else {
                    repository.setCompleted(todo.id, true)
                }
                if (result is TodoOpResult.Err) {
                    AppToasts.show(context, todoSaveFailedMessage)
                }
            }
        }
    }
    // 时间轴待办卡的勾选落库：与 completeAlertTodo 同一结算口径，但支持双向（勾/取消勾）；
    // 卡片自带完成态回显（划线置灰），无需停一拍编排
    val toggleTaskTodo: (Todo, Boolean) -> Unit = { todo, checked ->
        val repository = todoRepository
        if (repository != null) {
            scope.launch {
                val date = todo.dates.firstOrNull()
                val result = if (todo.repeat.type != RepeatType.None && date != null) {
                    repository.setOccurrenceCompleted(todo.id, date, checked)
                } else {
                    repository.setCompleted(todo.id, checked)
                }
                if (result is TodoOpResult.Err) {
                    AppToasts.show(context, todoSaveFailedMessage)
                }
            }
        }
    }
    // 分组写回统一入口（编辑待办浮层的选分组浮层「新建」用）：整表连 renames 交仓库
    // setGroups 落盘，失败 Toast；日程页无分组过滤 UI，不需要待办页的选中组跟随逻辑
    val commitGroups: (List<TodoGroup>, Map<String, String>) -> Unit = { newGroups, renames ->
        val repository = todoRepository
        if (repository != null) {
            scope.launch {
                if (repository.setGroups(newGroups, renames) is TodoOpResult.Err) {
                    AppToasts.show(context, todoSaveFailedMessage)
                }
            }
        }
    }
    // 待办详情唤起统一入口（待办提醒卡行 + 时间轴待办卡共用）：记 id 与分期日期——
    // 详情浮层按 id 反查原始待办，逐期上下文只由点击带入（同待办页两态口径，宿主块见底部挂载处）
    val openTodoDetail: (Todo) -> Unit = { todo ->
        todoDetailTargetId = todo.id
        todoDetailTargetDate = todo.dates.firstOrNull()?.toString().orEmpty()
    }

    // 课表数据：日历下方内容按页日期现算上课提醒卡/时间轴课程卡（横滑容器预组合前后两天，
    // 故不在页面级提前算好某一天）。时刻对表：前台每 30s 一跳 + 回前台立即对表（进程冻结
    // 期间 delay 不走，与 LocalToday 同款兜底）。
    val scheduleState = scheduleRepository?.activeSchedule?.collectAsState()
    var nowMinute by remember { mutableIntStateOf(nowMinuteOfDay()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) nowMinute = nowMinuteOfDay()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            nowMinute = nowMinuteOfDay()
            delay(CourseAlertResyncMillis)
        }
    }

    // 跨天/回前台对表：LocalToday 已含 onResume 与前台每分钟对表；日期变化立即回 01。
    LaunchedEffect(today) {
        if (openedDay != today) {
            compactOverscroll.reset()
            transition.resetForNewDay()
            calendarFold.reset()
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
    // 02↔04 的跟手统一由根级 pointerInput 驱动（见下方）：02 恒由根级接管（内容区
    // 滚动只在 04 开启）、04 列表在顶部时也由根级接管——图片卡/内容卡上任意起手都
    // 用同一份原始 dy 推进 collapse，没有起手阈值。
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
        overlayMounted,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!active || overlayMounted || transition.isAnimating) return Offset.Zero
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
                    // 04 下滑（列表回滚）：先展开日历折叠，归零后剩余才推进 04→02
                    // （与根级手势「先展开折叠，再过渡 02」同口径）。
                    deltaY > 0f &&
                        transition.open >= 1f &&
                        transition.collapse > 0f &&
                        !listState.canScrollBackward ->
                        if (calendarFold.progress > 0f) {
                            calendarFold.dragBy(deltaY)
                        } else {
                            transition.dragCollapseBy(deltaY, dragDistancePx)
                        }
                    else -> 0f
                }
                return Offset(0f, consumed)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!active || overlayMounted) return Velocity.Zero
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
                // 04 下滑甩：先展开日历折叠（同拖拽口径「先展开折叠，再过渡 02」）——
                // 折叠收束本场速度、不启动 04→02 吸附；已展开时按原逻辑交回转场。
                if (transition.collapse >= 1f && calendarFold.progress > 0f) {
                    calendarFold.settle(available.y)
                    return Velocity(0f, available.y)
                }
                transition.settle(available.y, flingThresholdPx)
                return Velocity(0f, available.y)
            }
        }
    }

    // 日期文案（日期组与顶栏共用同一份选中日期）
    val (year, month, dayOfMonth) = remember(selectedDate) { selectedDate.yearMonthDay() }
    // 节日/节气当天用同名两字文案替换大日期数字；名称用字已包含在字体子集中。
    val dayMark = remember(selectedDate) { ChineseDayMarks.markOf(selectedDate) }
    val yearMonth = stringResource(R.string.date_year_month_format, year, month)
    val weekday = stringArrayResource(R.array.weekdays)[selectedDate.isoDayOfWeek() - 1]
    val monthDay = stringResource(R.string.month_day_format, month, dayOfMonth)
    val topBarTitle = stringResource(R.string.date_title_format, monthDay, weekday)
    // 01 顶部提示随查看日期分档：今天开新一天（开锁）；已过去回顾这一天（图标不变）；
    // 还没到先看看这一天（图标换闭锁——未来还锁着，过去随时可翻）
    val swipeHintText = stringResource(
        when {
            selectedDate.epochDay < today.epochDay -> R.string.day_swipe_hint_past
            selectedDate.epochDay > today.epochDay -> R.string.day_swipe_hint_future
            else -> R.string.day_swipe_hint
        },
    )
    val swipeHintIconRes =
        if (selectedDate.epochDay > today.epochDay) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
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
                overlayMounted,
                flingThresholdPx,
                dragDistancePx,
                compactOverscroll,
            ) {
                if (!active || overlayMounted) return@pointerInput
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
                    val snapTakeOverConfirmPx =
                        viewConfiguration.touchSlop * SnapTakeOverConfirmSlopFactor
                    // 02→04 与 04→02 都由根级统一驱动跟手；04 起手先做横/竖轴仲裁：
                    // 横向占优整段让位，竖向（上滑）占优不写 collapse，留给内容区 Overscroll。
                    // 04 只要列表在顶部就由根级接管，避免未到达列表滚动阈值前
                    // 出现“只吸不跟手”；列表已下滚时让给列表。接管吸附时无视列表状态——
                    // 动画期间内容滚动本就是关闭的。
                    val drivesCollapse = snapTakeOver || shouldDriveCollapse(
                        stage = transition.stage,
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
                    var horizontalAccum = 0f
                    // 横向定轴后整段让位：结束时不再走 settle，吸附动画自行跑完。
                    var gaveWayToHorizontal = false
                    // 定轴/接管后从累计位移开始跟手：下滑方向补上定向前位移，
                    // 上滑方向不碰 collapse（0 不给调用方消费）。
                    fun startVerticalDrag(): Float =
                        if (scheduleDirection > 0) {
                            transition.dragCollapseBy(directionAccum, dragDistancePx)
                        } else {
                            0f
                        }
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
                                        horizontalAccum += dx
                                        when (scheduleAxisLock.onMove(dx, dy)) {
                                            // 横向手势：让位，整段不再驱动 collapse。
                                            DragDirection.Horizontal -> {
                                                gaveWayToHorizontal = true
                                                break
                                            }
                                            DragDirection.Vertical -> {
                                                // 接管在途吸附要求明确的竖向意图：只看
                                                // 方向锁定轴还不够（横滑起手常带竖向弧线），
                                                // 必须再走出一段竖向位移且仍压过横向。
                                                val takeOverConfirmed = snapTakeOver &&
                                                    confirmsSnapTakeOver(
                                                        directionAccum,
                                                        horizontalAccum,
                                                        snapTakeOverConfirmPx,
                                                    )
                                                when {
                                                    // 还在观察（吸附中或已结束）：不消费、
                                                    // 不碰 collapse，动画/阈值都不被打断。
                                                    snapTakeOver && !takeOverConfirmed -> Unit
                                                    snapTakeOver && transition.canTakeOverSnap -> {
                                                        // 接管在途吸附：动画停在当前进度，
                                                        // 上下两个方向都直接跟手。
                                                        transition.takeOverSnap()
                                                        scheduleDirection = 1
                                                        val consumed = startVerticalDrag()
                                                        if (consumed != 0f) change.consume()
                                                    }
                                                    // 确认竖向时吸附已自己结束：按普通竖向手势继续。
                                                    snapTakeOver -> {
                                                        scheduleDirection =
                                                            if (
                                                                transition.stage == DayStage.Schedule &&
                                                                directionAccum < 0f
                                                            ) {
                                                                -1
                                                            } else {
                                                                1
                                                            }
                                                        val consumed = startVerticalDrag()
                                                        if (consumed != 0f) change.consume()
                                                    }
                                                    transition.stage == DayStage.Schedule &&
                                                        directionAccum < 0f -> {
                                                        // 上滑锁定：完全不碰 collapse，放给 Overscroll。
                                                        scheduleDirection = -1
                                                    }
                                                    else -> {
                                                        scheduleDirection = 1
                                                        val consumed = startVerticalDrag()
                                                        if (consumed != 0f) change.consume()
                                                    }
                                                }
                                            }
                                            DragDirection.Undecided -> Unit
                                        }
                                    }
                                    1 -> {
                                        // 下滑锁定（或接管吸附）后仍双向跟手：上滑把 collapse
                                        // 拉回 04，松手时才按最终进度判是否回 02——拖出去再
                                        // 拉回来（未越过阈值）不会提前触发 02。
                                        // 04 下滑先跟手展开日历折叠，归零后剩余位移才走原有
                                        // 链路（04→02、02 端点整组过滚动）——「先展开折叠，
                                        // 再过渡 02」在单次手势内完成。
                                        val unfoldConsumed =
                                            if (dy > 0f && calendarFold.progress > 0f) {
                                                calendarFold.dragBy(dy)
                                            } else {
                                                0f
                                            }
                                        if (unfoldConsumed != 0f) change.consume()
                                        val dyAfterUnfold = dy - unfoldConsumed
                                        if (transition.collapse <= 0f && dyAfterUnfold > 0f) {
                                            // 04→02 已经拖到 02：继续下滑交给整组过滚动，
                                            // 不再让内容列表自己的 overscroll 接走——
                                            // 切回 Compact 时那条效果会被移除，回位会硬切。
                                            applyCompactOverscroll(dyAfterUnfold, change)
                                        } else if (dyAfterUnfold != 0f) {
                                            // 上滑先收回 02 的整组过滚动，剩余位移再拉回 collapse。
                                            val overscrollConsumed =
                                                if (dyAfterUnfold < 0f && transition.collapse <= 0f) {
                                                    applyCompactOverscroll(dyAfterUnfold, change)
                                                } else {
                                                    0f
                                                }
                                            val remaining = dyAfterUnfold - overscrollConsumed
                                            if (remaining != 0f) {
                                                val consumed = transition.dragCollapseBy(
                                                    remaining,
                                                    dragDistancePx,
                                                )
                                                if (consumed != 0f) change.consume()
                                            }
                                        }
                                    }
                                    -1 -> {
                                        // 上滑锁定：先跟手折叠日历（04 上滑即开始折叠）；
                                        // 折叠到头后不再消费，剩余位移放给内容 Overscroll
                                        // 橡皮筋——与此前「上滑不写 collapse」的让位行为
                                        // 在折叠到头后完全一致。
                                        if (dy < 0f && calendarFold.progress < 1f) {
                                            if (calendarFold.dragBy(dy) != 0f) change.consume()
                                        }
                                    }
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
                    // 没有驱动转场的手势（横向让位 / 吸附起手但没真正接管）整段只算观察：
                    // 不 settle，否则横滑残留的竖向抖动会再触发一次阈值吸附。
                    val passiveGesture = gaveWayToHorizontal ||
                        (snapTakeOver && scheduleDirection == 0)
                    val shouldSettle = !passiveGesture && when (transition.stage) {
                        DayStage.Schedule ->
                            drivesCollapse && transition.collapse < 1f
                        else -> true
                    }
                    // 还拉着整组过滚动时只做回位动画，不启动 02→04 吸附。
                    // 04→02 已经拖过 02 的情况也在这里：先无动画地提交 02，
                    // 再由 OffsetOverscrollEffect 的默认临界阻尼弹簧把内容送回原位；
                    // 效果挂在页面根上，不会因切回 Compact 被移除而硬切。
                    // 折叠停在半程（拖了但没到端点、或松手速度不足以判向）：只吸附折叠
                    // 本身——collapse 仍为 1，不启动 02↔04 吸附。折叠已到端点的手势走
                    // 原有收束：fold=1 时 collapse<1f 不成立、无吸附；fold=0 且拖过
                    // collapse 的按最终进度判回 02/回弹 04。
                    val foldMid = calendarFold.progress > 0f && calendarFold.progress < 1f
                    val compactPulled = !passiveGesture &&
                        transition.collapse <= 0f &&
                        compactOverscroll.currentOffset != Offset.Zero
                    if (foldMid && !passiveGesture && drivesCollapse) {
                        calendarFold.settle(tracker.calculateVelocity().y)
                    } else if (compactPulled) {
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

        val gestureAllowed = active && !overlayMounted && !transition.isAnimating
        // 01 的上滑仍由整屏 draggable 负责；02/04 的转场跟手由根级 pointerInput 统一驱动
        // （02 恒由根级接管、不依赖内容是否可滚，见 shouldDriveCollapse），不再额外挂
        // Schedule 的整屏层，避免同一次拖动被写两遍。
        val fullScreenGestureEnabled =
            gestureAllowed && transition.stage == DayStage.Calendar
        // 02 图片卡手势层只保留点击重播：竖向跟手与白卡一致归根级 pointerInput，不再挂
        // draggable 兜底，避免与根级形成双驱动（含双指各按一处的场景）。
        val cardGestureEnabled = gestureAllowed && transition.stage == DayStage.Compact
        // 04 完全收起后才启用内容区滚动/OverScroll；02/03 的内容区由根级转场手势接管。
        val contentUserScrollEnabled =
            gestureAllowed && transition.stage == DayStage.Schedule

        val fullScreenDragState = rememberDraggableState { delta ->
            transition.dragOpenBy(delta, dragDistancePx)
        }
        val compactCard = remember(metrics) {
            DayGeometry.frame(metrics, open = 1f, collapse = 0f).image
        }

        // 02 的整组过滚动：OffsetOverscrollEffect 的渲染节点从页面根内收到这层内容组
        // 包装上——平移时把图片卡、日期组、内容列表、顶栏作为整组图层拖出，而底部渐变
        // 遮罩（9）与 10.5 起的各浮层留在包装之外：遮罩与导航栏同为屏幕固定层，橡皮筋
        // 拉出时钉在屏幕底部不动；浮层本就不参与过滚动。包装容器仍是全屏，阻尼分母
        // 与手感不变。
        Box(
            Modifier
                .fillMaxSize()
                .overscroll(compactOverscroll),
        ) {
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
                        backgroundImage = backgroundImage,
                        tilt = tilt,
                        // 01 静止态视差满量；拖拽/吸附向 02 收敛（open→1 位移归零），
                        // 02→01 重播时自然恢复。open 在 graphicsLayer 内绘制阶段读
                        tiltGate = { 1f - transition.open },
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
                                transition.deviceCornerAlpha,
                            )
                            // 02→04 后段渐隐：白卡被 Overscroll 拖上去时不再露出台历残影。
                            alpha = frame.imageAlpha
                            // 01 静止态是满幅直角：不裁剪，避免 RoundedRectangle(0) 在
                            // 屏幕边缘留下抗锯齿细线；滑动一开始（open > 0）立即套机身圆角。
                            val radius = frame.imageRadius
                            if (radius > 0f) {
                                clip = true
                                shape = RoundedRectangle(radius.dp)
                            } else {
                                clip = false
                            }
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

            // 2) 图片卡下段模糊带：底边延伸到图片卡底边以下 DayBandBleed、压到白卡顶边
            // 之下；白卡（不透明）叠在带上方盖住越界段——模糊始终遮住图片卡底部，
            // 02 静止态白卡两侧留白与 02↔04 白卡扫过图片卡时都不再有切线。
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
                                        transition.deviceCornerAlpha,
                                    )
                                    val tint = frame.bandTintAlpha
                                    if (tint > 0f) {
                                        // 黑色压暗只作用在图片卡内部，并复用图片卡同一 RoundedRectangle
                                        // 轮廓裁剪：底部两角随 imageRadius 一起从机身圆角过渡到
                                        // DayCardShape，页面背景与卡间间隙不再被压暗。
                                        // 01 静止态（圆角 0）用矩形轮廓，与不裁剪的图片一致。
                                        val cardWidthPx =
                                            (frame.image.width * pxPerDp).coerceAtLeast(1f)
                                        val cardHeightPx =
                                            (frame.image.height * pxPerDp).coerceAtLeast(1f)
                                        val cardSize = Size(cardWidthPx, cardHeightPx)
                                        val outline = if (frame.imageRadius > 0f) {
                                            RoundedRectangle(frame.imageRadius.dp).createOutline(
                                                size = cardSize,
                                                layoutDirection = layoutDirection,
                                                density = this,
                                            )
                                        } else {
                                            RectangleShape.createOutline(
                                                size = cardSize,
                                                layoutDirection = layoutDirection,
                                                density = this,
                                            )
                                        }
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

            // 4) 「上滑…」提示：跟随顶部磨砂一起上移淡出；文案/图标随查看日期分档（见上）。
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
                    painter = painterResource(swipeHintIconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = swipeHintText,
                    style = ClassppTextTokens.secondaryTextLarge,
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

            // 6) 02 图片卡手势层：点击原路重播回 01（竖向跟手归根级 pointerInput 统一驱动）；
            // 固定在 02 卡片矩形。
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
                        text = dayMark ?: dayOfMonth.toString(),
                        // 中文节日/节气比日期数字略小，避免两字文案在视觉上过宽。
                        style = if (dayMark != null) {
                            ClassppTextTokens.displayDateMark
                        } else {
                            ClassppTextTokens.displayDate
                        },
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
                            style = ClassppTextTokens.calendarSubtitle,
                            color = Color.White,
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

            // 8) 日历卡（DayCalendar）是日程页的内容卡，也是滚动列表的首条实体；高度随月/周
            // 折叠形态自适应，位置仍由 frame.content.top 决定（白卡顶边几何不变）。
            // 04 滑到底时 Overscroll 会拖动这张卡本身；后续真实内容直接排在它后面。
            // 内容视口铺满全屏，白卡位置 = 视口 offset + 首条 spacer（恒为 04 顶栏高度）
            // 之和，恒等于 frame.content.top：02 时视口顶抬到白卡上方 spacer 处（不盖
            // 图片卡），04 时视口贴屏幕顶、spacer 垫在顶栏下方，卡与后续内容才能滚进
            // 顶栏下面。spacer 不可随进度归零，原因见 spacer 处注释。
            if (showContent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(contentHazeState)
                        // 02 的内容视口顶垫进图片卡下半部（首条 spacer 恒高，见列表处注释），
                        // 列表 scrollable 节点即使手势关闭也构成命中，会把下层图片卡点击层
                        // 与换背景 icon 整片挡死；声明与下层兄弟共享命中后二者恢复可点。
                        .sharePointerInputWithSiblings(),
                ) {
                    ProvideOverscroll {
                        RubberBandLazyColumn(
                            state = listState,
                            userScrollEnabled = contentUserScrollEnabled,
                            // 滚动余量与课表页同口径：滚动到底时时间轴末线停在导航栏胶囊上方，
                            // 底部渐变遮罩之下不露内容断茬。
                            contentPadding = PaddingValues(bottom = navBarDp.dp + NavReserve),
                            modifier = Modifier
                                .fillMaxSize()
                                .offset {
                                    val frame = DayGeometry.frame(
                                        metrics,
                                        transition.open,
                                        transition.collapse,
                                    )
                                    // 视口顶 = 白卡顶 − 顶栏高度，与恒高的首条 spacer 相加
                                    // 恒等于 frame.content.top；collapse 越大视口越早覆盖
                                    // 顶栏区域，内容才有机会进入顶栏下面。
                                    val viewportTop =
                                        frame.content.top - metrics.contentTopSchedule()
                                    IntOffset(0, (viewportTop * pxPerDp).roundToInt())
                                },
                        ) {
                            // 首条 spacer：恒为 04 顶栏高度（状态栏 + 标题行 + 间距），把白卡
                            // 顶边垫到 frame.content.top。高度绝不能随 collapse 归零——02 态
                            // 若 spacer 高 0，LazyList 会把这个 0 高的 firstVisibleItem 规范化
                            // 跳过（firstVisibleItemIndex 推进到日历、占位被永久丢弃），之后
                            // 02→04 落位时日历贴屏幕顶、内容整体上移一个顶栏高度。
                            item(key = "contentTopSpacer") {
                                Spacer(
                                    Modifier.height(metrics.contentTopSchedule().dp),
                                )
                            }
                            // 第二条 = 日历卡：白卡占位已替换为 DayCalendar 组件（自带 surface
                            // 卡壳与 DayCardShape，高度随月/周折叠形态自适应）；折叠进度由根级
                            // 转场手势统一驱动（calendarFold），组件内不自挂手势。后续日程条目
                            // 继续 item { } 追加。
                            item(key = "dayCard") {
                                DayCalendar(
                                    selectedDate = selectedDate,
                                    markedDates = todoMarkedDates,
                                    // 02/04 态点选/回到今天走跳日指令（相邻日回放手势滑动、
                                    // 非相邻日走 CrossPage），日期在转场起步时提交；
                                    // 01 台历页照旧直接换日（内容不在视野内，无需动画）
                                    onDateClick = { date ->
                                        if (transition.stage != DayStage.Calendar && date != selectedDate) {
                                            jumpSession += 1
                                            jumpTargetDate = date
                                        } else {
                                            selectedDate = date
                                        }
                                    },
                                    onBackToToday = {
                                        if (transition.stage != DayStage.Calendar && today != selectedDate) {
                                            jumpSession += 1
                                            jumpTargetDate = today
                                        } else {
                                            selectedDate = today
                                        }
                                    },
                                    onGridRangeChange = { firstDate, lastDate ->
                                        calendarRange = firstDate to lastDate
                                    },
                                    foldState = calendarFold,
                                    today = today,
                                    modifier = Modifier.padding(horizontal = PageHorizontalSpacing),
                                )
                            }
                            // 第三条 = 日历下方内容组（横滑切日）：上课提醒卡 + 待办提醒卡 +
                            // 时间轴合并为同一个 item，交给 DayContentSwipe 预组合昨天/选中日/
                            // 明天三页并跟手横滑（手感同课表页课表内容的 HorizontalPager）。
                            // 日历卡本身不在滑轨内，横滑只认日历下方的这块内容；竖向滚动与
                            // 日历折叠仍归根级手势/LazyColumn，互不干扰。三页各按自己的日期
                            // 现算提醒与时间轴，横滑途中相邻日实时滑入。
                            item(key = "dayContent") {
                                DayContentSwipe(
                                    selectedDate = selectedDate,
                                    onDateChange = { selectedDate = it },
                                    enabled = contentUserScrollEnabled,
                                    // 日历点选跳日指令：相邻日回放手势滑动、非相邻日走
                                    // CrossPage（时序见 Motion.PageJumpCross）
                                    jumpTargetDate = jumpTargetDate,
                                    jumpSession = jumpSession,
                                    jumpCrossSpec = Motion.PageJumpCross,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { viewDate, revision ->
                                    DayDateContent(
                                        viewDate = viewDate,
                                        revision = revision,
                                        schedule = scheduleState?.value,
                                        todos = todos,
                                        today = today,
                                        nowMinute = nowMinute,
                                        tagColors = tagColorMap,
                                        onCompleteTodo = completeAlertTodo,
                                        onToggleTodo = toggleTaskTodo,
                                        onTodoClick = openTodoDetail,
                                        onCourseClick = { detailTargetId = it },
                                    )
                                }
                            }
                            // 第六条 = 尾部留白（TrailingScrollSpace）：纯占位，扩大可滚动
                            // 范围，与课表页末行之后的留白同构。
                            item(key = "trailingScrollSpace") {
                                Spacer(Modifier.height(TrailingScrollSpace))
                            }
                        }
                    }
                }
            }
            // 10) 日程顶栏：内容卡顶部贴住它；Haze 采样白卡，标题样式复刻 ScheduleHeader。
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
        }

        // 9) 02 底部渐变遮罩：与 01↔02 转场同一进度源（open）跟手——01→02 从屏幕底外
        // 一比一升入落位，02→01 反向沉出（拖拽与吸附动画都自动跟随）；02/04 常驻盖住
        // 滚进导航栏区域的内容卡。画在内容组包装（过滚动层）之外、各浮层之下：整组
        // 橡皮筋平移时遮罩钉在屏幕底部不动，内容沉入固定的渐变里被盖住。
        if (showContent) {
            BottomFadeScrim(
                progress = { transition.open },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 10.5) 课程详情浮层：点上课提醒卡/时间轴上课卡唤起，与课表页（ScheduleScreen）
        // 同款接线。条目从当前课表反查（课程被删/切课表后反查落空 → 请求清空 → 浮层自动
        // 关闭）；挂载/可见两段式：收场动画期间 detailMounted 保持内容稳定不闪空。
        // 画在顶栏之后、图片浮层之前——图片浮层保持最顶层（盖住整个页面，含浮层）。
        val activeSchedule = scheduleState?.value
        val detailEntry = activeSchedule?.courses?.firstOrNull { it.id == detailTargetId }
        if (detailEntry != null) detailMounted = detailEntry
        detailMounted?.let { detail ->
            val schedule = activeSchedule ?: return@let
            // 该课程标签（=课程名）命中的全部待办：先做重复待办的分期展开（同待办列表页
            // 口径——分期副本的日期与"随重复"折算截止才是当期真实值），再按标签过滤（标签
            // 语义走 filterTodos 的"任一重合"），排序与待办页默认一致（创建时间降序）。
            // 分期展开按选中日期算：从查看日的时间轴卡点进来，相关待办就是那天的口径
            val courseTodos = remember(todos, selectedDate, detail.name) {
                todos
                    .expandRepeatOccurrences(selectedDate)
                    .filterTodos(TodoFilter(tags = setOf(detail.name)))
                    .sortedFor(TodoSort.Created, descending = true)
            }
            // 新建课程待办浮层：详情浮层左上「新建」请求挂载。期望态 + 挂载态两段式
            //（同其它浮层），期望态同时驱动详情浮层 covered（上层在场时缩小后退）
            var courseTodoVisible by remember { mutableStateOf(false) }
            var courseTodoMounted by remember { mutableStateOf(false) }
            val keyboard = LocalSoftwareKeyboardController.current
            val noteSaveFailedMessage = stringResource(R.string.error_persist_failed)
            key(detail.id) {
                CourseDetailSheet(
                    visible = detailEntry != null,
                    entry = detail,
                    schedule = schedule,
                    todos = courseTodos,
                    covered = courseTodoVisible,
                    // 同时间轴待办卡：勾选结算走 toggleTaskTodo（分期/整条 + 失败 Toast）
                    onTodoCheckedChange = toggleTaskTodo,
                    onNoteSave = { note ->
                        val cleaned = note.trim()
                        if (cleaned != detail.note) {
                            scope.launch {
                                val result = scheduleRepository?.upsertCourse(
                                    schedule.id,
                                    detail.copy(note = cleaned),
                                )
                                if (result is OpResult.Err) {
                                    AppToasts.show(context, noteSaveFailedMessage)
                                }
                            }
                        }
                    },
                    // 左上「新建」：收键盘后挂载新建课程待办浮层（入口进弹层的通用约定）
                    onCreateTodoClick = {
                        keyboard?.hide()
                        courseTodoMounted = true
                        courseTodoVisible = true
                    },
                    onDismiss = { detailTargetId = "" },
                    onDismissed = { detailMounted = null },
                )
            }
            // 新建课程待办浮层：画在详情浮层之后（组合顺序即绘制顺序）叠层，返回键
            // 后注册先响应（先于详情浮层的关闭拦截）。确认落库：Ok 关浮层回详情（新
            // 待办带课程名标签，回流后即出现在上方待办卡列表）；Err Toast 不关，可改后重试
            if (courseTodoMounted) {
                CourseTodoSheet(
                    visible = courseTodoVisible,
                    entry = detail,
                    schedule = schedule,
                    onConfirm = { todo ->
                        scope.launch {
                            when (val result = todoRepository?.addTodo(todo)) {
                                is TodoReadResult.Ok -> courseTodoVisible = false
                                is TodoReadResult.Err -> AppToasts.show(context, todoSaveFailedMessage)
                                null -> Unit
                            }
                        }
                    },
                    onDismiss = { courseTodoVisible = false },
                    onDismissed = { courseTodoMounted = false },
                )
            }
        }

        // 10.6) 待办详情浮层：点待办提醒卡行/时间轴待办卡唤起，与待办页（TodoScreen）
        // 同款接线。条目从原始待办列表反查（id 不随分期展开变化），待办被删后反查落空 →
        // 请求清空 → 浮层自动关闭；分期日期由点击带入，随重复的截止行据此折算。
        // 画在课程详情浮层之后、图片浮层之前——图片浮层保持最顶层（盖住整个页面）。
        val todoDetailEntry = todos.firstOrNull { it.id == todoDetailTargetId }
        if (todoDetailEntry != null) todoDetailMounted = todoDetailEntry
        todoDetailMounted?.let { detail ->
            val repository = todoRepository ?: return@let
            // 回调（协程）里不是组合上下文，字符串在组合期解析（同待办页浮层约定）
            val stepDeletedMessage = stringResource(R.string.toast_todo_step_deleted)
            key(detail.id) {
                TodoDetailSheet(
                    visible = todoDetailEntry != null,
                    // 编辑浮层盖上来时本层缩小后退，清 id（确认/取消/删除）的瞬间同步回位
                    covered = todoEditTargetId != "",
                    todo = detail,
                    tagColors = tagColorMap,
                    occurrenceDate = IsoDate.parseOrNull(todoDetailTargetDate),
                    onDismiss = {
                        todoDetailTargetId = ""
                        todoDetailTargetDate = ""
                    },
                    onDismissed = { todoDetailMounted = null },
                    // 备注随任意关闭路径落盘：未改动零开销；Err 仅剩校验不过等异常（Toast 提示）
                    onNoteSave = { note ->
                        val cleaned = note.trim()
                        if (cleaned != detail.note) {
                            scope.launch {
                                if (repository.updateTodo(detail.copy(note = cleaned)) is TodoOpResult.Err) {
                                    AppToasts.show(context, todoSaveFailedMessage)
                                }
                            }
                        }
                    },
                    // 详情保留在下层不关：最新快照（含未落盘备注）直接当表单初值挂载编辑浮层
                    //（叠在详情之上），确认/取消后回到详情；不等落盘——备注不丢、打开零延迟
                    onEdit = { latest ->
                        todoEditMounted = latest
                        todoEditTargetId = latest.id
                    },
                    // 步骤新增/勾选：各自落库（addStep 现铸步骤 id、setStepDone 按 id 定点切换），
                    // Err（待办已删/空标题等异常）Toast 提示；列表随仓库回流自动刷新
                    onStepAdd = { title ->
                        scope.launch {
                            if (repository.addStep(detail.id, title) is TodoOpResult.Err) {
                                AppToasts.show(context, todoSaveFailedMessage)
                            }
                        }
                    },
                    onStepToggle = { stepId, done ->
                        scope.launch {
                            if (repository.setStepDone(detail.id, stepId, done) is TodoOpResult.Err) {
                                AppToasts.show(context, todoSaveFailedMessage)
                            }
                        }
                    },
                    // 步骤侧滑删除：松手过阈值即删（removeStep 按 todo+step 双 id 定点滤除），
                    // 成功 Toast 反馈；Err Toast 提示、卡片由侧滑层自动滑回
                    onStepDelete = { stepId ->
                        scope.launch {
                            when (repository.removeStep(detail.id, stepId)) {
                                TodoOpResult.Ok -> AppToasts.show(context, stepDeletedMessage)
                                is TodoOpResult.Err -> AppToasts.show(context, todoSaveFailedMessage)
                            }
                        }
                    },
                    // 完成情况浮层里的绿/红球切换：按所点分期落库（补完成先归账逾期、取消完成
                    // 出完成集合）；Err Toast 提示，其余随仓库回流刷新
                    onOccurrenceCompletedChange = { date, completed ->
                        scope.launch {
                            val result = repository.setOccurrenceCompleted(detail.id, date, completed)
                            if (result is TodoOpResult.Err) {
                                AppToasts.show(context, todoSaveFailedMessage)
                            }
                        }
                    },
                    // 倒计时开关：落库后列表卡左区显示剩余天数（详情菜单文案随仓库回流自动切换）
                    onCountdownToggle = { enabled ->
                        scope.launch {
                            if (repository.setCountdown(detail.id, enabled) is TodoOpResult.Err) {
                                AppToasts.show(context, todoSaveFailedMessage)
                            }
                        }
                    },
                    // 「添加到日历」（详情菜单项）：权限/账户选择/写入由 calendarAdd 编排；
                    // 传详情快照 detail，写入目标锁定点菜单那一刻的内容
                    onAddToCalendar = { calendarAdd.begin(detail) },
                )
            }
        }
        // 日历账户选择卡：画在待办详情浮层之后（组合顺序即绘制顺序，盖在详情之上），
        // 两段式挂载收口在 CalendarPickerHost
        CalendarPickerHost(calendarAdd, hazeState = contentHazeState)
        // 编辑待办浮层：两段式挂载同详情，画在详情之后（组合顺序即绘制顺序）叠在详情之上。
        // todoEditTargetId 是期望目标（空 = 关），todoEditMounted 是挂载时的快照（表单初值
        // 只取一次）；visible 从当前列表反查：待办被删反查落空 → 自动收起
        val todoEditEntry = todos.firstOrNull { it.id == todoEditTargetId }
        todoEditMounted?.let { initial ->
            val repository = todoRepository ?: return@let
            key(initial.id) {
                NewTodoSheet(
                    visible = todoEditEntry != null,
                    onDismissed = { todoEditMounted = null },
                    onDismiss = { todoEditTargetId = "" },
                    tagRepository = tagRepository,
                    scheduleRepository = scheduleRepository,
                    groups = groups,
                    todos = todos,
                    // 选择分组浮层的「新建」走同一套整表写回（落盘失败 Toast 由 commitGroups 统一弹）
                    onGroupsChange = commitGroups,
                    existing = initial,
                    onConfirm = { todo ->
                        scope.launch {
                            // 空名等非法值已在浮层内拦截，Err 仅剩落盘失败等异常，Toast 提示、
                            // 表单保持原样
                            when (repository.updateTodo(todo)) {
                                TodoOpResult.Ok -> todoEditTargetId = ""
                                is TodoOpResult.Err -> AppToasts.show(context, todoSaveFailedMessage)
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            when (repository.removeTodo(initial.id)) {
                                TodoOpResult.Ok -> {
                                    // 反查落空本会让两个浮层自动收起，这里显式清 id 收尾
                                    todoEditTargetId = ""
                                    todoDetailTargetId = ""
                                    todoDetailTargetDate = ""
                                }
                                is TodoOpResult.Err -> AppToasts.show(context, todoSaveFailedMessage)
                            }
                        }
                    },
                )
            }
        }

        // 11) 图片选择浮层保持最后挂载，盖住整个页面（含顶栏）。
        if (sheetMounted) {
            DayImageSheet(
                visible = sheetVisible,
                images = images,
                // 当前显示中的背景（随机命中或手动选中）：浮层里它的删除项置灰不可点
                currentImage = backgroundImage,
                randomEnabled = randomEnabled,
                onRandomChange = { enabled ->
                    randomEnabled = enabled
                    DayImageStore.saveRandomEnabled(context, enabled)
                    // 关闭随机不切换背景：把当日命中固化为手动选中，画面保持原样
                    //（无有效命中——图库空或跨天残留——时不固化，显示自然不变）
                    if (!enabled) {
                        randomPick?.let { (day, name) ->
                            if (day == today.epochDay && name in images) {
                                selectedImage = name
                                DayImageStore.saveSelected(context, name)
                            }
                        }
                    }
                },
                parallaxEnabled = tiltEnabled,
                onParallaxChange = { enabled ->
                    tiltEnabled = enabled
                    DayImageStore.saveTiltParallaxEnabled(context, enabled)
                },
                onImported = { imported ->
                    images = images + imported
                    DayImageStore.saveImages(context, images)
                },
                onSelect = { name ->
                    selectedImage = name
                    DayImageStore.saveSelected(context, name)
                    // 随机开启时点选立即切换：点中的图固化为当日命中（随机保持开启，
                    // 当天锁定不再重抽，跨零点恢复每日随机）
                    if (randomEnabled) {
                        randomPick = today.epochDay to name
                        DayImageStore.saveRandomPick(context, today.epochDay, name)
                    }
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
 * 日历下方单日内容（横滑三页中的一页）：上课提醒卡 + 待办提醒卡 + 全天任务提醒卡 + 时间轴。
 *
 * 三页各自按 [viewDate] 现算，横滑途中相邻日实时滑入，故这里不做任何「当前选中日」的
 * 假设。课表取课口径与页面级一致：今天随时刻对表出倒计时/上课中/课已上完/今日无课，
 * 其他日期出整天节数概览（开学前/学期外 0 节）；时间轴只接该日有起止时刻的课程与待办。
 *
 * [revision] 在每次换日提交时自增（横滑松手判定 / 日历跳日），用作两张提醒卡
 * （[DayTodoAlert] / [DayTaskAlldayAlert]）的行镜重置键：提交瞬间内容必须从新日期的
 * 完整行集起画——若复用旧日期的行镜，会先重播一遍「跨日行增减」动画（表现为新页面
 * 刚滑进来又闪回旧待办行）。点日历球等外部换日不经过 revision，原有跨日行动画保持不变。
 */
@Composable
private fun DayDateContent(
    viewDate: IsoDate,
    revision: Int,
    schedule: Schedule?,
    todos: List<Todo>,
    today: IsoDate,
    nowMinute: Int,
    tagColors: Map<String, Color>,
    onCompleteTodo: (Todo) -> Unit,
    onToggleTodo: (Todo, Boolean) -> Unit,
    onTodoClick: (Todo) -> Unit,
    onCourseClick: (String) -> Unit,
) {
    val isToday = viewDate == today
    // 上课提醒卡：今天有课可点（唤起课程详情浮层），其余传 null 不可点
    val courseAlert = remember(schedule, viewDate, isToday, nowMinute) {
        if (isToday) dayCourseAlertData(schedule, viewDate, nowMinute) else null
    }
    // 非今天整天概览卡的节数：同一取课口径（周号 + 星期 + 周规律，开学前/学期外 0 节）
    val dayCourseCount = remember(schedule, viewDate) {
        schedule?.let { todayCourseEntries(it, viewDate).size } ?: 0
    }
    // 时间轴任务卡：该日有起止时刻的课程 + 待办（口径见 dayTasks），装配成 DayTask 列表。
    // 截止取色是 @Composable 口径（读 LocalToday），故在组合期装配而非 remember 块里；
    // 上课卡/待办卡的点击分别经 onCourseClick/onTodoClick 记下目标，唤起详情浮层
    //（宿主块见底部挂载处）
    val timelineTasks = dayTasks(
        schedule,
        todos,
        viewDate,
        onToggleTodo,
        onCourseClick = onCourseClick,
        onTodoClick = onTodoClick,
    )

    Column(Modifier.fillMaxWidth()) {
        if (courseAlert != null) {
            DayCourseAlert(
                state = courseAlert.state,
                courseName = courseAlert.course?.name.orEmpty(),
                startTime = courseAlert.startTimeText,
                endTime = courseAlert.endTimeText,
                color = courseAlert.course?.let { CourseColor.valueOf(it.color.name) }
                    ?: CourseColor.Blue,
                teacher = courseAlert.course?.teacher,
                location = courseAlert.course?.location,
                note = courseAlert.course?.note,
                onClick = courseAlert.course?.let { course -> { onCourseClick(course.id) } },
                modifier = Modifier.padding(
                    top = CourseAlertTopSpacing,
                    start = PageHorizontalSpacing,
                    end = PageHorizontalSpacing,
                ),
            )
        } else {
            // 非今天整天概览卡：未来「这天 N 节课 做好准备」，过去「已上完 N 节课 好好休息吧」
            DayCourseAlert(
                state = DayCourseAlertState.DaySummary(
                    count = dayCourseCount,
                    isPast = viewDate.epochDay < today.epochDay,
                ),
                modifier = Modifier.padding(
                    top = CourseAlertTopSpacing,
                    start = PageHorizontalSpacing,
                    end = PageHorizontalSpacing,
                ),
            )
        }
        // 待办提醒卡：截止窗口随查看日期推算——今天收「今明后天 + 已逾期」的未完成待办；
        // 查看其他日期收「那天起 2 天内」窗口 + 全部已逾期（拖欠始终在场）。无候选时整卡
        // 折叠不占位。倒计时（含取色）与标题逾期标红随查看日期同源推算；非今天截止文案
        // 取绝对日期（避免与倒计时错位）。完成勾选走落库路由，行点击经 openTodoDetail
        // 唤起待办详情浮层。key(revision) 的落位重置口径见函数 KDoc。
        key(revision) {
            DayTodoAlert(
                todos = todos,
                today = today,
                viewDate = viewDate,
                nowMinute = nowMinute,
                tagColors = tagColors,
                onComplete = onCompleteTodo,
                onTodoClick = onTodoClick,
                modifier = Modifier.padding(horizontal = PageHorizontalSpacing),
            )
            // 全天任务提醒卡：只收查看日命中的全天（AllDay）未完成待办；截止窗口内的
            // 全天任务已在上一张卡显示，这里让位不重复。行不显示截止/倒计时（只回答
            //「这一天有哪些全天任务」）。无候选时整卡折叠，10dp 间距随内容一并收起。
            DayTaskAlldayAlert(
                todos = todos,
                today = today,
                viewDate = viewDate,
                tagColors = tagColors,
                onComplete = onCompleteTodo,
                onTodoClick = onTodoClick,
                modifier = Modifier.padding(horizontal = PageHorizontalSpacing),
            )
        }
        // 时间轴（DayTimeline）：0:00 → 次日 0:00 全宽网格 + 左侧 44dp 时间列；网格线与
        // 屏幕同宽，故不加水平 padding。右侧卡片区域渲染当天任务卡，按任务时长比例
        // （1 小时 = 50dp）排布；当前时刻指示器仅在今天显示，无任务时照常显示整条留白。
        DayTimeline(
            tasks = timelineTasks,
            nowMinute = if (isToday) nowMinute else null,
            modifier = Modifier.padding(top = TimelineTopSpacing),
        )
    }
}

/**
 * 背景图：调用方解析好的背景文件名（手动选中，或随机轮播按天抽取的图）有就换上去
 * （文件缺失/解码失败回退默认测试图）。尺寸由调用方通过 requiredSize 固定为屏幕大小，
 * 图片卡内部只做图层缩放/平移，换卡尺寸时不会触发 Coil 重新按新尺寸请求。
 *
 * 换图（02 态点日历切日期按日命中新图、图片浮层换选/开关随机）走单向 cross-dissolve：
 * 底图恒满强度垫底，新图等 Coil 解码就绪（失败回退默认图也算）才以 [Motion.Overlay]
 * 淡入盖上——双向各淡一半会在中段同时半透明、透出页面底色；等就绪再起播则让
 * [Motion.AgendaImageFadeMillis] 只花在真正的淡入上、不被磁盘解码吃掉。快速连续换图
 * 恒最多两层：在途层已就绪的瞬时落定为底图再起新一场，未就绪的直接丢弃（底图从未
 * 离开，不闪页面底色）。
 */
@Composable
private fun DayBackgroundImage(
    context: Context,
    backgroundImage: String?,
    tilt: TiltParallaxState,
    tiltGate: () -> Float,
    modifier: Modifier = Modifier,
) {
    var settledImage by remember { mutableStateOf(backgroundImage) }
    var incomingImage by remember { mutableStateOf<String?>(null) }
    var incomingReady by remember { mutableStateOf(false) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(backgroundImage) {
        val target = backgroundImage
        if (target == null || target == settledImage) {
            // null（图库删空/选中被删，回退默认测试图）只由图片浮层触达、浮层盖着屏幕，
            // 直接换不淡入；等于底图是首帧同图重复，或淡入途中折返回底图——弃掉在途层即可
            settledImage = target
            incomingImage = null
            fade.snapTo(1f)
            return@LaunchedEffect
        }
        if (incomingImage != null) {
            // 上一场未播完被顶替：已就绪的半成品瞬时落定为底图（快连点时 alpha 有个
            // 小跳变，好过叠出第三层）；未就绪的直接丢弃
            if (incomingReady) settledImage = incomingImage
            incomingImage = null
            fade.snapTo(1f)
        }
        incomingImage = target
        incomingReady = false
        fade.snapTo(0f)
        snapshotFlow { incomingReady }.first { it }
        fade.animateTo(1f, tween(Motion.AgendaImageFadeMillis, easing = Motion.Overlay))
        // 播完收台：新图成为底图、在途层退场（中途被顶替时本协程已取消，走不到这）
        settledImage = target
        incomingImage = null
    }
    Box(modifier) {
        // 视差包在两层换图图层之外：cross-dissolve 的底图与在途层同动不错位；且位于
        // 转场 transform（外层 graphicsLayer 的缩放/平移）内侧——位移在局部空间里随
        // 卡片收缩被同比例缩小，[tiltGate] 再把它收敛到 0，02 落位不受视差影响
        Box(
            Modifier
                .matchParentSize()
                .tiltParallax(tilt, tiltGate),
        ) {
            DayImageLayer(context, settledImage, Modifier.fillMaxSize())
            if (incomingImage != null) {
                DayImageLayer(
                    context = context,
                    image = incomingImage,
                    // 淡入进度只在绘制阶段读，逐帧动画不触发重组；fillMaxSize 维持
                    // requiredSize 固定的解码尺寸，Coil 不按图片固有尺寸另起请求
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = fade.value },
                    // 回报先与本层图名比对：被顶替的旧层迟到的回调不误放行
                    onReady = { image -> if (image == incomingImage) incomingReady = true },
                )
            }
        }
    }
}

/** 单层背景图：文件交给 Coil 按测量尺寸解码（缺失/失败回退默认测试图），null 直接默认图。 */
@Composable
private fun DayImageLayer(
    context: Context,
    image: String?,
    modifier: Modifier = Modifier,
    onReady: ((String?) -> Unit)? = null,
) {
    val file = remember(image) { image?.let { DayImageStore.imageFile(context, it) } }
    if (file != null) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            error = painterResource(R.drawable.bg_day_test),
            onSuccess = { onReady?.invoke(image) },
            onError = { onReady?.invoke(image) },
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
                    style = ClassppTextTokens.pageTitle,
                    color = MaterialTheme.colorScheme.onSurface,
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

/** 当前时刻的当天分钟数（0..1439），与 TimeText.parseMinutes 同口径可直接比较 */
private fun nowMinuteOfDay(): Int {
    val cal = GregorianCalendar()
    return cal.get(GregorianCalendar.HOUR_OF_DAY) * 60 + cal.get(GregorianCalendar.MINUTE)
}

/** 上课提醒卡渲染数据：状态 + 最近一节的课程字段与起止时间（无课/课已上完 course = null） */
private data class DayCourseAlertData(
    val state: DayCourseAlertState,
    val course: CourseEntry? = null,
    val startTimeText: String = "",
    val endTimeText: String = "",
)

/**
 * 提醒卡取数：今天该上的课 + 当前时刻 → 卡片状态。取课口径同 NotificationPlanner.planCourses
 * （周号 + 星期 + 周规律过滤，开学前/学期外整段跳过），但**不按 daysPerWeek 跳过周末**：
 * 那只是网格显示列数，周末排了课仍要提醒：
 * - 进行中的课（开始 ≤ 现在 < 结束）→ Ongoing，多节并发取节次最早者；
 * - 否则最近一节未开始的课 → Countdown（分钟差，标题按小时分档的文案由组件派生）；
 * - 今天的课全部上完 → DayDone；今天没排课（含学期外/课表未装载）→ NoCourse。
 * 结束时间取末节 endTime：跨节（span > 1）的课 = 最后一节的下课时刻。
 */
private fun dayCourseAlertData(schedule: Schedule?, today: IsoDate, nowMinute: Int): DayCourseAlertData {
    if (schedule == null) return DayCourseAlertData(DayCourseAlertState.NoCourse)
    val todayCourses = todayCourseEntries(schedule, today)
    if (todayCourses.isEmpty()) return DayCourseAlertData(DayCourseAlertState.NoCourse)

    var next: Triple<CourseEntry, Pair<String, String>, Int>? = null
    for (course in todayCourses) {
        val startSlot = schedule.slots.getOrNull(course.startSlot - 1) ?: continue
        val endSlot = schedule.slots.getOrNull(course.endSlot - 1) ?: startSlot
        val startMinute = TimeText.parseMinutes(startSlot.startTime) ?: continue
        val endMinute = TimeText.parseMinutes(endSlot.endTime) ?: continue
        if (startMinute <= nowMinute && nowMinute < endMinute) {
            return DayCourseAlertData(
                state = DayCourseAlertState.Ongoing,
                course = course,
                startTimeText = startSlot.startTime,
                endTimeText = endSlot.endTime,
            )
        }
        if (next == null && startMinute > nowMinute) {
            next = Triple(course, startSlot.startTime to endSlot.endTime, startMinute)
        }
    }
    val (course, times, startMinute) = next ?: return DayCourseAlertData(DayCourseAlertState.DayDone)
    return DayCourseAlertData(
        state = DayCourseAlertState.Countdown(startMinute - nowMinute),
        course = course,
        startTimeText = times.first,
        endTimeText = times.second,
    )
}

/** 今天该上的课（周号 + 星期 + 周规律过滤，开学前/学期外整段跳过）。**不按 daysPerWeek
 *  跳过周末**：那只是网格的显示列数，周末真实排了课仍要提醒/上时间轴。上课提醒卡
 *  （[dayCourseAlertData]）与时间轴任务卡（[dayTasks]）共用同一取课口径 */
private fun todayCourseEntries(schedule: Schedule, today: IsoDate): List<CourseEntry> {
    val termStartMonday = schedule.termStart.mondayOfWeek().epochDay
    val week = ((today.mondayOfWeek().epochDay - termStartMonday) / 7).toInt() + 1
    if (week !in 1..schedule.totalWeeks) return emptyList()
    val dayOfWeek = today.isoDayOfWeek()
    return schedule.courses
        .filter { it.dayOfWeek == dayOfWeek && it.weeks.contains(week) }
        .sortedBy { it.startSlot }
}

/**
 * 时间轴任务卡取数（组合期调用——截止取色是 @Composable 口径，读 LocalToday）：
 * 今天该上的课 + 当天有起止时刻（Period 档）的待办。
 * 课程口径同 [todayCourseEntries]，时刻经大节定义解析（跨节 span > 1 = 末节下课时刻）；
 * 上课卡经 [onCourseClick]（课程 id）、待办卡经 [onTodoClick]（分期副本，宿主取 id + 分期
 * 日期）接详情浮层的唤起；待办先按重复规则展开分期再取落在今天的，已完成分期也上轴
 * （卡片内置灰划线呈现），AllDay/无时刻的待办在比例时间轴上无处安放、不上轴；
 * 截止文案取绝对日期（如「10/27 6:00」），重复规则取档位名（如「每天」）。
 */
@Composable
private fun dayTasks(
    schedule: Schedule?,
    todos: List<Todo>,
    today: IsoDate,
    onToggleTodo: (Todo, Boolean) -> Unit,
    onCourseClick: (String) -> Unit = {},
    onTodoClick: (Todo) -> Unit = {},
): List<DayTask> = buildList {
    if (schedule != null) {
        for (course in todayCourseEntries(schedule, today)) {
            val startSlot = schedule.slots.getOrNull(course.startSlot - 1) ?: continue
            val endSlot = schedule.slots.getOrNull(course.endSlot - 1) ?: startSlot
            val startMinute = TimeText.parseMinutes(startSlot.startTime) ?: continue
            val endMinute = TimeText.parseMinutes(endSlot.endTime) ?: continue
            add(
                DayTask.Course(
                    name = course.name,
                    teacher = course.teacher.takeIf { it.isNotBlank() },
                    location = course.location.takeIf { it.isNotBlank() },
                    note = course.note.takeIf { it.isNotBlank() },
                    // 存储枚举（data.model）→ UI 枚举（ui.schedule）同名映射，barColor 取色同源
                    color = CourseColor.valueOf(course.color.name),
                    startMinute = startMinute,
                    endMinute = endMinute,
                    onClick = { onCourseClick(course.id) },
                ),
            )
        }
    }
    for (todo in todos.expandRepeatOccurrences(today)) {
        if (today !in todo.dates) continue
        if (todo.timeKind != TodoTimeKind.Period) continue
        val startMinute = todo.startMinute ?: continue
        val endMinute = todo.endMinute ?: continue
        add(
            DayTask.Todo(
                id = todo.id,
                name = todo.name,
                completed = todo.completed,
                deadlineText = todo.deadlineDate?.let { date ->
                    listOfNotNull(
                        date.toAbsoluteDateText(today),
                        todo.deadlineMinute?.let(TimeText::format),
                    ).joinToString(" ")
                },
                deadlineColor = todo.deadlineColor(),
                repeatText = todo.repeatCardText(),
                onToggle = { checked -> onToggleTodo(todo, checked) },
                onClick = { onTodoClick(todo) },
                startMinute = startMinute,
                endMinute = endMinute,
            ),
        )
    }
}.sortedBy { it.startMinute }
