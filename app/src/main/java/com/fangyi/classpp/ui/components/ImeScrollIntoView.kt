package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
 * 输入框上缘与视口顶端之间保留的最小余量：只在输入框高到「下缘对齐会把上缘顶出可见区」
 * 时才起作用（多行备注框），此时退化成上缘对齐、至少保证顶部不被顶栏切掉。
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
 * 键盘收起后内容尾部余量回落的时长：与 [ImeScrollTracker] 里「滚回原位」的动画**用同一个值、
 * 同一条曲线**。
 *
 * 必须同拍：余量缩小等于把滚动上限往下压，`ScrollState` 会把当前滚动值同步钳到上限。
 * 两者同拍时，滚动动画每一帧的目标都正好落在上限之内，画面是连续的；一旦余量缩得比滚动
 * 快，滚动值就会被钳着走——表现就是「卡」。改时长只改这一个值，两边自动一致。
 */
private const val TailReserveMillis = 600
/**
 * 内容尾部余量（px）：键盘弹起当帧就位成 [target]（滚动范围不够时靠下的输入框根本滚不到
 * 键盘上方，光靠滚动定位拉不动）；键盘收起后按 [TailReserveMillis] 缩回 0。
 */
@Composable
internal fun rememberTailReservePx(target: Int, imeVisible: Boolean): Int {
    var reserve by remember { mutableIntStateOf(0) }
    LaunchedEffect(imeVisible, target) {
        if (imeVisible) {
            reserve = target
        } else {
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
    imeInsetPx: Int,
    imeVisible: Boolean,
    scope: ColumnScope,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (enabled) {
        ImeScrollTracker(
            scrollState = scrollState,
            viewportHeightPx = viewportHeightPx,
            viewportTopInRootPx = viewportTopInRootPx,
            imeInsetPx = imeInsetPx,
            imeVisible = imeVisible,
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
 *    滚到视口顶端——视口顶端离键盘最远，贴顶即最大程度让开键盘（换算见 [targetScrollForIme]）；
 * 2. **内容滚回原位**：弹起前记下滚动位置，键盘收起时用**与尾部余量回落同一时长、同一曲线**
 *    的动画滚回去（见下方注释：两者必须同拍，否则滚动值会被逐帧收缩的滚动上限钳着走）；
 * 3. **坐标系**：字段报的是 `positionInRoot`，布局阶段已把滚动量落在子节点的放置位置上，
 *    故减去滚动列自身在根里的位置（[viewportTopInRootPx]，滚动列自己的放置位置不随其内容
 *    滚动而变）即得「输入框离视口顶端的距离」。
 *
 * @param viewportHeightPx 滚动视口高度（px）
 * @param viewportTopInRootPx 滚动列上缘在根坐标系里的 y（px）
 * @param imeInsetPx 键盘占去的高度（px），滚动定位时用它算可见区下缘
 */
@Composable
internal fun ImeScrollTracker(
    scrollState: ScrollState,
    viewportHeightPx: Int,
    viewportTopInRootPx: Int,
    imeInsetPx: Int,
    imeVisible: Boolean,
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
    // 滚动列上缘在根坐标系里的 y：被位置回报经由闭包读取，故包成 State（换值要能读到新值）
    val viewportTopInRootPxState = rememberUpdatedState(viewportTopInRootPx)

    val reporting = remember {
        ImeFieldReporting(
            reportBounds = { coords ->
                if (coords.isAttached) {
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
        } else {
            restoreTarget?.let {
                scrollState.animateScrollTo(
                    value = it,
                    animationSpec = tween(TailReserveMillis, easing = Motion.Standard),
                )
            }
            restoreTarget = null
        }
    }

    // 键盘弹起、或弹着键盘换输入框：把正在编辑的输入框滚到视口顶端。
    // key 带上 [imeInsetPx]：`isImeVisible` 会先于「键盘高度」一拍变真（那一拍 ime 还是 0，
    // 按 0 算会得出「输入框本来就可见、不用滚」），键盘高度的真实值到下一拍才来，没这个
    // key 就再也不会重算，表现为整件事没发生。
    LaunchedEffect(imeVisible, imeInsetPx, focusTicket) {
        val focused = field ?: return@LaunchedEffect
        if (!imeVisible) return@LaunchedEffect
        val target = targetScrollForIme(
            fieldTopPx = focused.topPx,
            fieldBottomPx = focused.bottomPx,
            scrollPx = scrollState.value,
            viewportPx = viewportHeightPx,
            imeInsetPx = imeInsetPx,
            marginPx = with(density) { ImeFieldTopMargin.roundToPx() },
            gapPx = with(density) { ImeFieldGapAboveKeyboard.roundToPx() },
        )
        scrollState.animateScrollTo(target)
    }

    CompositionLocalProvider(LocalImeFieldReporting provides reporting) {
        scope.content()
    }
}

/**
 * 目标滚动位置（px）：让正在编辑的输入框落在「键盘正上方那一带」，且**只朝让开键盘的方向滚**
 * （输入框已经满足条件就不动，免得为一点余量来回滚）。
 *
 * 换算约定（[fieldTopPx] / [fieldBottomPx] 是视口坐标系，已含当前滚动位移 [scrollPx]；
 * 视口顶端 = 顶栏下缘）：
 * - 键盘弹起后可见区下缘（= 键盘上缘）= `viewportPx − imeInsetPx`；
 * - **不动**的条件：下缘已在「可见区下缘 − [gapPx]」之上，且上缘没有夹在
 *   `[0, marginPx)` 这条带子里（上缘已经滚出可见区顶部不算问题——那是上半截被顶栏盖住，
 *   不是被键盘压住，不该反手把内容再往上推）；
 * - 需要滚动时：
 *   1. **下缘对齐**（落点）：把下缘停在「可见区下缘 − [gapPx]」，即贴键盘上方停住。
 *      **不想再改停的位置就只调 [gapPx]**（调大 = 让得更多、停得更靠上）；
 *   2. **上缘上界**：推得再高也不能让上缘越过距可见区顶部 [marginPx] 那条线——输入框高到
 *      放不下时（多行备注框）由此接管，表现是上缘贴住可见区顶部、下缘钻进键盘。
 *   取两者较小者，再夹到不早于内容起点（0）。
 *
 * @param fieldTopPx 输入框上缘的距离（视口坐标系）
 * @param fieldBottomPx 输入框下缘的距离（视口坐标系）
 * @param scrollPx 当前滚动值
 * @param viewportPx 滚动视口高度
 * @param imeInsetPx 键盘占去的高度
 * @param marginPx 输入框上缘与视口顶端之间保留的最小余量（放不下时退化为上缘对齐）
 * @param gapPx 输入框下缘与键盘上缘之间保留的间距（停靠位置的主参数）
 */
internal fun targetScrollForIme(
    fieldTopPx: Int,
    fieldBottomPx: Int,
    scrollPx: Int,
    viewportPx: Int,
    imeInsetPx: Int,
    marginPx: Int,
    gapPx: Int,
): Int {
    val visibleTop = fieldTopPx - scrollPx
    val visibleBottom = fieldBottomPx - scrollPx
    val visibleBottomLimit = viewportPx - imeInsetPx
    // 已经达成目标就不用动：整个露在键盘上方，或虽有一截滚出可见区顶部、但下缘已在
    // 键盘上方（这时再滚只会把它推得更高）。上半截被顶栏盖住不算「被键盘压住」——
    // 用户在下滑状态点一个上方的框，不该反手把内容再往上推
    val topVisibleEnough = visibleTop >= gapPx || visibleTop < 0
    if (topVisibleEnough && visibleBottom <= visibleBottomLimit - gapPx) return scrollPx
    // 落点：下缘贴键盘上方（留 gapPx）停住——这是默认想要的位置
    val bottomAligned = visibleBottom - visibleBottomLimit + gapPx + scrollPx
    // 上界：推得再高也不能让上缘越过「距可见区顶部 marginPx」那条线（高框放不下时由此接管）
    val topAligned = fieldTopPx - marginPx
    return minOf(bottomAligned, topAligned).coerceAtLeast(0)
}
