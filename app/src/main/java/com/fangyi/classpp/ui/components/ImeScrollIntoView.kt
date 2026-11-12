package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.Motion

/**
 * 输入框停住的位置：**下缘距键盘上缘的间距**（滚动对齐的主参数）。
 *
 * 「感觉停太高/太低」就改这一个值：调大 = 输入框离键盘更远、更靠上；调小 = 更贴着键盘。
 * 只影响键盘弹起后把输入框带到的位置，不影响键盘收起的原位复位。
 */
private val ImeFieldGapAboveKeyboard: Dp = 24.dp

/**
 * 输入框上缘与**可见视口顶端**（顶栏下缘，见 `targetScrollForIme` 的 `topInsetPx`）之间
 * 保留的最小余量：只在输入框高到「下缘对齐会把上缘顶出可见区」时才起作用（多行备注框），
 * 此时退化成上缘对齐、至少保证顶部不被顶栏切掉。
 */
private val ImeFieldTopMargin: Dp = 12.dp

/**
 * 一个被追踪输入框在**滚动视口坐标系**里的位置（px）：[topPx] 即「此刻它离视口顶端的距离」。
 * 由 [Modifier.imeFieldTracking] 在聚焦期间逐帧回报（滚动/布局/键盘任一变化都会改写）。
 */
private data class FocusedField(val topPx: Int, val bottomPx: Int)

/**
 * 输入框回报给宿主 [ImeScrollTracker] 的两条通道：位置与焦点。位置在布局阶段逐帧回报，
 * 焦点只在翻转时报一次——两者分开，宿主才能「位置变了只更新位置、焦点变了才重新滚动」
 * （合在一起的话，滚动本身逐帧改位置，会导致每帧都重算滚动目标）。
 */
private class ImeFieldReporting(
    /** 位置回报：挂 [Modifier.onGloballyPositioned]，只在输入框挂载且已布局时有值 */
    val reportBounds: (LayoutCoordinates) -> Unit,
    /** 焦点翻转回报：true = 获得焦点（带一次位置换算以就位），false = 失焦 */
    val reportFocus: (Boolean) -> Unit,
)

/**
 * 输入框把自己的位置与焦点回报给宿主的通道：宿主提供，[Modifier.imeFieldTracking] 取用。
 * 宿主未提供时追踪即空转——设置页等直排表单里的 [SheetTextField] / [SheetTextArea] 行为不变。
 */
private val LocalImeFieldReporting = compositionLocalOf<ImeFieldReporting?> { null }

/**
 * 「这一次键盘收起不要滚回原位」的请求闸：输入中打开菜单类弹层时，调用方在收键盘前登记一次。
 *
 * 菜单在输入中打开时，内容回滚会让被点的那一行离开原位——菜单锚点随之漂移，用户也会先
 * 看到一整段回滚才看到菜单。保持当前滚动位置既稳住锚点，也让交互更安静。
 *
 * 登记在下一次键盘收起时消费；调用方应只在**键盘在场**时登记，否则未消费的登记会留到
 * 下一次键盘会话，误吞一次正常的回滚。
 */
@Stable
internal class ImeRestoreGate {
    private var suppressNext = false

    /** 登记一次：下一次键盘收起跳过「滚回原位」 */
    fun suppressNextRestore() {
        suppressNext = true
    }

    internal fun consumeSuppressed(): Boolean {
        val suppressed = suppressNext
        suppressNext = false
        return suppressed
    }
}

/**
 * 由 [ImeScrollTracker] 下发给内容列的「收起不回滚」请求闸；不在 tracker 作用域内
 * （设置页等直排表单）为 null。菜单类弹层（如 `SettingsCardItem.Select`）在输入中打开时
 * 读取它登记，内容保持不动。
 */
internal val LocalImeRestoreGate = compositionLocalOf<ImeRestoreGate?> { null }

/**
 * 让输入框参与宿主的「键盘弹起自动滚动」（[ImeScrollTracker]）：把位置与焦点回报给宿主。
 *
 * 挂在输入控件本身（[SheetTextField] / [SheetTextArea] / [TagChoosingCard] 已内置），而不是
 * 整张卡片：焦点可能落在别处（宿主用 `focusManager.moveFocus` 前进/后退），只有真正正在
 * 编辑的那个控件才该带着内容滚动。
 *
 * 位置只在**已聚焦**时回报：`onGloballyPositioned` 在失焦状态下也会随滚动逐帧触发，
 * 谁来都收会让宿主把「没在编辑的输入框」当成正在编辑的那个。
 *
 * 宿主未提供追踪（不在 [ImeScrollTracker] 作用域内）时不挂任何东西，零开销。
 */
@Composable
internal fun Modifier.imeFieldTracking(): Modifier {
    val reporting = LocalImeFieldReporting.current ?: return this
    var focused by remember { mutableStateOf(false) }
    // 卸载时按失焦收尾：卸载未必会走一次 onFocusChanged，宿主的「正在编辑」状态要清掉。
    // key 用 Unit（不能拿 reporting 当 key：宿主每次重组都可能给出新实例，effect 会反复销毁重建）
    val latestReporting = rememberUpdatedState(reporting)
    DisposableEffect(Unit) {
        onDispose { latestReporting.value.reportFocus(false) }
    }
    return this
        .onFocusChanged {
            focused = it.isFocused
            reporting.reportFocus(it.isFocused)
        }
        // 位置在布局阶段回调，随滚动/布局/键盘逐帧变化；只回报不写状态（是否收下由宿主定）
        .then(
            if (focused) {
                Modifier.onGloballyPositioned(reporting.reportBounds)
            } else {
                Modifier
            },
        )
}

/**
 * 键盘是否弹起：取 `isImeVisible`（系统给出的「输入法是否在显示」状态，弹起动画一开始即为真），
 * 不用「IME 高度大于导航栏」那类高度比较——导航栏的输入法切换条、状态栏的光标控制条在部分
 * 机型上也会计入 `ime`，按高度判断会误判。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberImeVisible(): Boolean = WindowInsets.isImeVisible

/**
 * 键盘的**终值**高度（px）：`imeAnimationTarget` 在弹起动画开始那一帧就已经是终态，
 * 动画期间保持不变；`ime` 则是逐帧变化的动画中间值。
 *
 * 自动滚动只能拿它当 `LaunchedEffect` 的 key：用逐帧变化的 `ime` 当 key 会让 effect
 * 每帧重启，`animateScrollTo` 被反复取消，内容滚动因此滞后于键盘。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberImeTargetInsetPx(): Int {
    val density = LocalDensity.current
    return WindowInsets.imeAnimationTarget.getBottom(density)
}

/**
 * 键盘收起后内容尾部余量回落的时长：与 [ImeScrollTracker] 里「滚回原位」的动画**用同一个值、
 * 同一条曲线**。
 *
 * 必须同拍：余量缩小等于把滚动上限往下压，`ScrollState` 会把当前滚动值同步钳到上限。
 * 两者同拍时，滚动动画每一帧的目标都正好落在上限之内，画面是连续的；一旦余量缩得比滚动
 * 快，滚动值就会被钳着走——表现就是「卡」。改时长只改这一个值，两边自动一致。
 *
 * 取 300ms（与系统 IME 收起动画同量级）：曾用 600ms，键盘窗口约 200~300ms 就消失了，
 * 而余量/内容还要再走一截——观感是「键盘退出很慢、有拖尾」，与弹起时不一致。
 */
private const val TailReserveMillis = 300

/** 键盘弹起时把输入框带到让位位置的时长：与系统 IME 动画同量级，曲线与回落同族 */
private const val ImeScrollMillis = TailReserveMillis

/**
 * 内容尾部余量（px）：键盘弹起当帧就位成 [target]（滚动范围不够时靠下的输入框根本滚不到
 * 键盘上方，光靠滚动定位拉不动）；键盘收起后按 [TailReserveMillis] 缩回 0。
 *
 * 弹起与收起拆成两个 effect：
 * - 弹起支的 key 带 [target]（键盘高度会晚一两拍才到终值，visible 期间逐帧跟到）；
 * - 收起支的 key **只有** [imeVisible]（+ [holdCollapse]）。键盘收起动画期间 [target]
 *   逐帧变小，若把它也当 key，回落动画会被每帧重启（每次都从当前值重新起一条回落曲线），
 *   键盘早已消失、卡片/余量却还在半空慢慢落——这就是「降落拖尾」的根因。
 *
 * [holdCollapse] = true 时收起支挂起（余量保持当前值）：宿主有弹层菜单在场（含退场）
 * 时传。回落会收缩滚动上限、让内容在动画尾段从「可滚」翻成「不可滚」，
 * `rubberBandVerticalScroll` 随即切换修饰符分支——菜单毛玻璃效果节点正活采样内容列，
 * 同帧改写采样源结构即构建自引用 RenderNode，RenderThread 在 prepareTreeImpl 递归到
 * 栈溢出（2026-11-12 AI 配置方案「切到服务商预设 → 唤起键盘 → 点服务商弹卡」真机崩溃：
 * 崩溃帧正好是 300ms 回落动画把短内容翻回不可滚的那一帧）。菜单卸载后回落从头播完，
 * 效果节点已不在场，结构改写安全。
 */
@Composable
internal fun rememberTailReservePx(
    target: Int,
    imeVisible: Boolean,
    holdCollapse: Boolean = false,
): Int {
    var reserve by remember { mutableIntStateOf(0) }
    // 弹起：余量跟住键盘高度（逐帧到终值；直接赋值，不播动画）
    LaunchedEffect(imeVisible, target) {
        if (imeVisible) reserve = target
    }
    // 收起：只由 imeVisible / holdCollapse 翻转触发一次，与 ImeScrollTracker 的
    // 「滚回原位」同拍；菜单在场期间挂起，卸载后再回落（见 [holdCollapse]）
    LaunchedEffect(imeVisible, holdCollapse) {
        if (!imeVisible && !holdCollapse) {
            animate(
                initialValue = reserve.toFloat(),
                targetValue = 0f,
                animationSpec = tween(TailReserveMillis, easing = Motion.Standard),
            ) { value, _ -> reserve = value.toInt() }
        }
    }
    return reserve
}

/** 滚动内容末尾的余量空白：把 [rememberTailReservePx] 的过渡值画出来 */
@Composable
internal fun TailReserveSpacer(reservePx: Int) {
    val density = LocalDensity.current
    Spacer(Modifier.height(with(density) { reservePx.toDp() }))
}

/**
 * 把滚动列的内容（[content]）交给键盘让位能力渲染：参与时套上 [ImeScrollTracker]，
 * 不参与时（浮层选了不理会键盘）原样调用。
 *
 * [scope] 由调用点显式传入：卡片里那个 `BoxWithConstraints` 已经出了 Column 的接收者
 * 作用域，接收者形式的函数类型在那个位置解析不到，只能把它带过来。
 */
@Composable
internal fun SheetScrollHost(
    enabled: Boolean,
    scrollState: ScrollState,
    viewportHeightPx: Int,
    viewportTopInRootPx: Int,
    imeTargetInsetPx: Int,
    imeVisible: Boolean,
    topInsetPx: Int = 0,
    scope: ColumnScope,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (enabled) {
        ImeScrollTracker(
            scrollState = scrollState,
            viewportHeightPx = viewportHeightPx,
            viewportTopInRootPx = viewportTopInRootPx,
            imeTargetInsetPx = imeTargetInsetPx,
            imeVisible = imeVisible,
            topInsetPx = topInsetPx,
            scope = scope,
            content = content,
        )
    } else {
        scope.content()
    }
}

/**
 * 键盘弹起/收起时把正在编辑的输入框滚到键盘上方、把内容滚回原位。
 *
 * 挂在与滚动视口同尺寸的位置（滚动列被 `fillMaxSize` 撑满浮层卡片，量它等价于量视口），
 * 且必须在 `BoxWithConstraints` 之内——视口高度是这整套换算的全部依据。
 *
 * 三件事：
 * 1. **输入框滚到键盘上方**：键盘弹起、或弹着键盘换到另一个输入框时，把正在编辑的输入框
 *    滚到可见区顶端（顶栏下缘）附近——它离键盘最远，贴顶即最大程度让开键盘
 *    （换算见 [targetScrollForIme]）；
 * 2. **内容滚回原位**：弹起前记下滚动位置，键盘收起时用**与尾部余量回落同一时长、同一曲线**
 *    的动画滚回去（见下方注释：两者必须同拍，否则滚动值会被逐帧收缩的滚动上限钳着走）；
 * 3. **坐标系**：字段报的是 `positionInRoot`，布局阶段已把滚动量落在子节点的放置位置上，
 *    故减去滚动列自身在根里的位置（[viewportTopInRootPx]，滚动列自己的放置位置不随其内容
 *    滚动而变）即得「输入框离视口顶端的距离」。
 *
 * @param viewportHeightPx 滚动视口高度（px）
 * @param viewportTopInRootPx 滚动列上缘在根坐标系里的 y（px）
 * @param imeTargetInsetPx 键盘的**终值**高度（px，取 `imeAnimationTarget`）：滚动定位时用
 *   它算可见区下缘。不能传逐帧变化的 `ime`——那会让自动滚动的 effect 每帧重启
 * @param topInsetPx 滚动列顶部被顶栏盖住的高度（px，OverlaySheet 传 `TopBarHeight`）：
 *   内容可见区实际上从顶栏下缘开始，上缘对齐必须在它之下留出 [ImeFieldTopMargin]
 */
@Composable
internal fun ImeScrollTracker(
    scrollState: ScrollState,
    viewportHeightPx: Int,
    viewportTopInRootPx: Int,
    imeTargetInsetPx: Int,
    imeVisible: Boolean,
    topInsetPx: Int = 0,
    scope: ColumnScope,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    // 正在编辑的输入框（视口坐标系）
    var field by remember { mutableStateOf<FocusedField?>(null) }
    // 每次「获得焦点」自增：弹着键盘换输入框时它变化，触发重新滚动；同一框内移动光标不自增
    var focusTicket by remember { mutableIntStateOf(0) }
    // 键盘弹起前的滚动位置（收起键盘时滚回这里）
    var restoreTarget by remember { mutableStateOf<Int?>(null) }
    // 本次键盘会话里是否追踪到内容列内的输入框：只有这种会话才存在「为排除键盘把内容
    // 顶上去、收起再滚回原位」这件事。AI 对话的输入栏在钉底槽（不在本 tracker 作用域），
    // 恒为 false——否则收起键盘时的回滚会与「滚到底」抢同一个 ScrollState（P2-4）
    var imeSessionTracked by remember { mutableStateOf(false) }
    // 菜单类弹层在输入中打开时可以登记「这次收起不回滚」（见 [ImeRestoreGate]）
    val restoreGate = remember { ImeRestoreGate() }
    // 滚动列上缘在根坐标系里的 y：被位置回报经由闭包读取，故包成 State（换值要能读到新值）
    val viewportTopInRootPxState = rememberUpdatedState(viewportTopInRootPx)
    // 焦点/位置回报回调里判断「此刻键盘是否在场」，同样要读最新值
    val imeVisibleState = rememberUpdatedState(imeVisible)

    val reporting = remember {
        ImeFieldReporting(
            reportBounds = { coords ->
                if (coords.isAttached) {
                    // 键盘在场时回报过位置 = 本次会话确实在内容列里编辑，收起才需要滚回原位
                    if (imeVisibleState.value && !imeSessionTracked) imeSessionTracked = true
                    val top = coords.positionInRoot().y - viewportTopInRootPxState.value
                    val next = FocusedField(
                        topPx = top.toInt(),
                        bottomPx = (top + coords.size.height).toInt(),
                    )
                    // 位置逐帧变化，值没变就不写状态（免得白重组一帧）
                    if (next != field) field = next
                }
            },
            // 焦点落到另一个输入框（或从无到有）：重新滚一次
            reportFocus = { focused ->
                if (focused) {
                    focusTicket++
                    if (imeVisibleState.value) imeSessionTracked = true
                } else {
                    field = null
                }
            },
        )
    }

    // 键盘收起：内容滚回弹起前的位置。
    // 时长与曲线必须与尾部余量的回落（OverlaySheet 的 rememberTailReservePx）一致：
    // 余量缩小就是滚动上限往下压，同步钳制当前滚动值；同拍时动画目标每一帧都在上限内，
    // 画面连续。不同拍就会被钳着走，看起来一顿一顿。
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            restoreTarget = scrollState.value
            // 弹起那一刻若已有内容列输入框聚焦（挂载自动聚焦），也算本次会话有追踪
            imeSessionTracked = field != null
        } else {
            // 只有内容列里真的编辑过才存在回滚；菜单打开时调用方可登记跳过（内容原地不动）
            val suppressRestore = restoreGate.consumeSuppressed()
            if (imeSessionTracked && !suppressRestore) {
                restoreTarget?.let {
                    scrollState.animateScrollTo(
                        value = it,
                        animationSpec = tween(TailReserveMillis, easing = Motion.Standard),
                    )
                }
            }
            restoreTarget = null
            imeSessionTracked = false
        }
    }

    // 键盘弹起、或弹着键盘换输入框：把正在编辑的输入框滚到视口顶端。
    // key 里的 [imeTargetInsetPx] 是键盘**终值**（imeAnimationTarget，动画期间不变）：
    // - 用逐帧变化的 ime 当 key 会让本 effect 每帧重启，animateScrollTo 被反复取消，
    //   滚动几乎不动，表现为「键盘先压住输入框，内容再慢慢追上来」；
    // - 终值在弹起动画开始那一帧就到了，不会出现「按 0 算发现不用滚」的漏执行。
    LaunchedEffect(imeVisible, imeTargetInsetPx, focusTicket) {
        val focused = field ?: return@LaunchedEffect
        if (!imeVisible) return@LaunchedEffect
        // 有内容列输入框参与本次键盘会话：收起时才需要滚回原位（覆盖「弹起后才聚焦」的路径）
        imeSessionTracked = true
        val target = targetScrollForIme(
            fieldTopPx = focused.topPx,
            fieldBottomPx = focused.bottomPx,
            scrollPx = scrollState.value,
            viewportPx = viewportHeightPx,
            imeInsetPx = imeTargetInsetPx,
            marginPx = with(density) { ImeFieldTopMargin.roundToPx() },
            gapPx = with(density) { ImeFieldGapAboveKeyboard.roundToPx() },
            topInsetPx = topInsetPx,
        )
        // 与回落同族曲线、同一时长（tween + Motion.Standard）：弹起与收起对称，
        // 不再出现「弹起快、回落拖尾」
        scrollState.animateScrollTo(
            value = target,
            animationSpec = tween(ImeScrollMillis, easing = Motion.Standard),
        )
    }

    CompositionLocalProvider(
        LocalImeFieldReporting provides reporting,
        LocalImeRestoreGate provides restoreGate,
    ) {
        scope.content()
    }
}

/**
 * 目标滚动位置（px）：让正在编辑的输入框落在「键盘正上方那一带」，且**只朝让开键盘的方向滚**
 * （输入框已经满足条件就不动，免得为一点余量来回滚）。
 *
 * 换算约定（[fieldTopPx] / [fieldBottomPx] 是视口坐标系，已含当前滚动位移 [scrollPx]；
 * 视口原点 = 滚动列（卡片）顶端，内容可见区从 [topInsetPx]（顶栏下缘）开始）：
 * - 键盘弹起后可见区下缘（= 键盘上缘）= `viewportPx − imeInsetPx`；
 * - **不动**的条件：下缘已在「可见区下缘 − [gapPx]」之上，且上缘没有夹在
 *   `[topInsetPx, topInsetPx + marginPx)` 这条带子里（上缘已经滚出可见区顶部不算问题——
 *   那是上半截被顶栏盖住，不是被键盘压住，不该反手把内容再往上推）；
 * - 需要滚动时：
 *   1. **下缘对齐**（落点）：把下缘停在「可见区下缘 − [gapPx]」，即贴键盘上方停住。
 *      **不想再改停的位置就只调 [gapPx]**（调大 = 让得更多、停得更靠上）；
 *   2. **上缘上界**：推得再高也不能让上缘越过「距可见区顶部 [marginPx]」那条线——输入框高到
 *      放不下时（多行备注框）由此接管，表现是上缘贴住可见区顶部（顶栏下缘）、下缘钻进键盘。
 *   取两者较小者，再夹到不早于内容起点（0）。
 *
 * @param fieldTopPx 输入框上缘的距离（视口坐标系）
 * @param fieldBottomPx 输入框下缘的距离（视口坐标系）
 * @param scrollPx 当前滚动值
 * @param viewportPx 滚动视口高度
 * @param imeInsetPx 键盘占去的高度
 * @param marginPx 输入框上缘与可见区顶端（顶栏下缘）之间保留的最小余量
 * @param gapPx 输入框下缘与键盘上缘之间保留的间距（停靠位置的主参数）
 * @param topInsetPx 视口顶端被顶栏盖住的高度（px）：可见区从它开始，上缘上界取
 *   `topInsetPx + marginPx`。默认 0 = 没有顶栏遮挡
 */
internal fun targetScrollForIme(
    fieldTopPx: Int,
    fieldBottomPx: Int,
    scrollPx: Int,
    viewportPx: Int,
    imeInsetPx: Int,
    marginPx: Int,
    gapPx: Int,
    topInsetPx: Int = 0,
): Int {
    val visibleTop = fieldTopPx - scrollPx
    val visibleBottom = fieldBottomPx - scrollPx
    val visibleTopLimit = topInsetPx
    val visibleBottomLimit = viewportPx - imeInsetPx
    // 已经达成目标就不用动：整个露在键盘上方，或虽有一截滚出可见区顶部、但下缘已在
    // 键盘上方（这时再滚只会把它推得更高）。上半截被顶栏盖住不算「被键盘压住」——
    // 用户在下滑状态点一个上方的框，不该反手把内容再往上推
    val topVisibleEnough =
        visibleTop >= visibleTopLimit + marginPx || visibleTop < visibleTopLimit
    if (topVisibleEnough && visibleBottom <= visibleBottomLimit - gapPx) return scrollPx
    // 落点：下缘贴键盘上方（留 gapPx）停住——这是默认想要的位置
    val bottomAligned = visibleBottom - visibleBottomLimit + gapPx + scrollPx
    // 上界：推得再高也不能让上缘越过「距可见区顶部（顶栏下缘）marginPx」那条线
    val topAligned = fieldTopPx - visibleTopLimit - marginPx
    return minOf(bottomAligned, topAligned).coerceAtLeast(0)
}
