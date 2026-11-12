package com.fangyi.classpp.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.popupMotionScale
import com.fangyi.classpp.ui.motion.rememberPopupMotion
import com.fangyi.classpp.ui.schedule.CardShadowBottomPadding
import com.fangyi.classpp.ui.schedule.CardShadowPadding
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.first

/**
 * 行尾 20dp 上下箭头锚定的菜单，卡顶边贴行顶边的上移量：
 * 图标 20dp + 行上 padding 14dp（原 DropdownMenu offset -34dp，选择行 / 重复行共用）。
 */
val MenuTopAlignLift = 34.dp

/** 菜单与锚点的默认间距（首选一侧放不下、也无需翻转时的呼吸量） */
private val MenuDefaultGap = 8.dp

/** 菜单贴安全区时的默认留白：钳位先按系统栏 / IME 内缩，再留这圈边距 */
private val MenuSafeMargin = 8.dp

/**
 * 锚点参考点的默认位置：锚点矩形的几何中心（等价于「按矩形中心反推生长锚点」）。
 * 宽锚点配窄菜单的宿主（整行卡 + 自适应宽菜单）用 [PopupMenuPopup] 的 `anchorFraction`
 * 挪到靠边一端，否则中心会落在菜单外被钳到卡缘（见 [anchorPointOf]）。
 */
internal val MenuAnchorCenter = Offset(0.5f, 0.5f)

/** 菜单高度上限占窗口高度的比例：超过后转内部滚动（Material 菜单可滚动形态的量级） */
internal const val MenuMaxHeightFraction = 0.7f

/** 菜单宽度上限（Material 规范 112–280dp 的上限）；长文案单行省略，不再把卡撑宽 */
internal val MenuMaxWidth = 280.dp

/** 菜单相对锚点的首选方位：Bottom/Top = 排在锚点下方/上方，Start/End = 左/右缘对齐。 */
enum class MenuGravity(val isBottom: Boolean, val isStart: Boolean) {
    BottomStart(isBottom = true, isStart = true),
    BottomEnd(isBottom = true, isStart = false),
    TopStart(isBottom = false, isStart = true),
    TopEnd(isBottom = false, isStart = false);

    /** 垂直翻到另一侧（下方 ↔ 上方），左右对齐不变 */
    internal fun flipVertical(): MenuGravity = when (this) {
        BottomStart -> TopStart
        BottomEnd -> TopEnd
        TopStart -> BottomStart
        TopEnd -> BottomEnd
    }

    /** 水平翻到另一侧（左缘对齐 ↔ 右缘对齐），上下不变 */
    internal fun flipHorizontal(): MenuGravity = when (this) {
        BottomStart -> BottomEnd
        BottomEnd -> BottomStart
        TopStart -> TopEnd
        TopEnd -> TopStart
    }
}

/**
 * 菜单的声明式落位（A1）：给出相对锚点的首选方位，越界时由共享层自动翻转、钳进
 * `窗口 − 安全区 − margin`。生长锚点不再由调用点猜角，而是由**最终卡片矩形**与锚点中心
 * 反推（见 [menuAnchorFraction]）——翻转、钳位、贴边后菜单始终自最靠近锚点的那条边长出。
 *
 * @param gap 菜单与锚点的间距（gravity 方向）
 * @param overlap 菜单压进锚点的量，与 [gap] 相减（贴卡长出的菜单用它表达「压住 12dp」）
 * @param inset Start 端左缘 / End 端右缘相对锚点同侧边缘的内缩
 * @param flip 越界时是否翻到另一侧；两侧都放不下才退回钳位
 * @param margin 安全区内的额外留白
 */
data class MenuPlacement(
    val gravity: MenuGravity = MenuGravity.BottomStart,
    val gap: Dp = MenuDefaultGap,
    val overlap: Dp = 0.dp,
    val inset: Dp = 0.dp,
    val flip: Boolean = true,
    val margin: Dp = MenuSafeMargin,
)

/**
 * 首选方位 → 卡片目标矩形（未钳位）。翻转判断都在这里，纯函数便于单测：
 * 下方放不下且上方放得下 → 翻到上方（反之亦然）；Start 端向右溢出且 End 端放得下 →
 * 翻到右缘对齐（反之亦然）。两侧都放不下时保留首选方位，交给调用方钳位。
 */
internal fun placeMenu(
    placement: MenuPlacement,
    anchor: IntRect,
    cardSize: IntSize,
    available: IntRect,
    density: Density,
): IntRect {
    val gapPx = with(density) { (placement.gap - placement.overlap).roundToPx() }
    val insetPx = with(density) { placement.inset.roundToPx() }
    var gravity = placement.gravity
    var x = menuX(gravity, anchor, cardSize, insetPx)
    var y = menuY(gravity, anchor, cardSize, gapPx)
    if (placement.flip) {
        if (gravity.isBottom) {
            if (y + cardSize.height > available.bottom) {
                val flipped = gravity.flipVertical()
                val flippedY = menuY(flipped, anchor, cardSize, gapPx)
                if (flippedY >= available.top) {
                    gravity = flipped
                    y = flippedY
                }
            }
        } else if (y < available.top) {
            val flipped = gravity.flipVertical()
            val flippedY = menuY(flipped, anchor, cardSize, gapPx)
            if (flippedY + cardSize.height <= available.bottom) {
                gravity = flipped
                y = flippedY
            }
        }
        if (gravity.isStart) {
            if (x + cardSize.width > available.right) {
                val flipped = gravity.flipHorizontal()
                val flippedX = menuX(flipped, anchor, cardSize, insetPx)
                if (flippedX >= available.left) {
                    gravity = flipped
                    x = flippedX
                }
            }
        } else if (x < available.left) {
            val flipped = gravity.flipHorizontal()
            val flippedX = menuX(flipped, anchor, cardSize, insetPx)
            if (flippedX + cardSize.width <= available.right) {
                gravity = flipped
                x = flippedX
            }
        }
    }
    return IntRect(x, y, x + cardSize.width, y + cardSize.height)
}

private fun menuX(
    gravity: MenuGravity,
    anchor: IntRect,
    cardSize: IntSize,
    insetPx: Int,
): Int = if (gravity.isStart) {
    anchor.left + insetPx
} else {
    anchor.right - insetPx - cardSize.width
}

private fun menuY(
    gravity: MenuGravity,
    anchor: IntRect,
    cardSize: IntSize,
    gapPx: Int,
): Int = if (gravity.isBottom) {
    anchor.bottom + gapPx
} else {
    anchor.top - gapPx - cardSize.height
}

/** 卡片矩形钳进可用区（可用区已扣安全区与 margin）；菜单比可用区还大时以左上缘为准 */
internal fun clampCard(rect: IntRect, cardSize: IntSize, available: IntRect): IntRect {
    val maxLeft = (available.right - cardSize.width).coerceAtLeast(available.left)
    val maxTop = (available.bottom - cardSize.height).coerceAtLeast(available.top)
    val left = rect.left.coerceIn(available.left, maxLeft)
    val top = rect.top.coerceIn(available.top, maxTop)
    return IntRect(left, top, left + cardSize.width, top + cardSize.height)
}

/**
 * 锚点矩形 + 参考点比例 → 参考点的窗口坐标（[PopupMenuPopup] 的 `anchorFraction` 换算）。
 * 默认 (0.5, 0.5) 即几何中心，与原「按矩形中心反推」一致。
 *
 * 宽锚点（如整张入口行卡）+ 窄菜单（按内容收窄）的组合必须改用靠边一端：菜单右对齐行
 * 右缘时，行中心常落在菜单左缘的左侧，[menuAnchorFraction] 的 `coerceIn` 会把锚点钳到
 * 菜单左缘，读感变成「从卡左侧往右撑开」，与「从行尾长出」相反。传 (1f, 0.5f) 即取行
 * 右缘的垂直中心，反推回卡片就是右上角。
 */
internal fun anchorPointOf(rect: IntRect, fraction: Offset): Offset = Offset(
    x = rect.left + rect.width * fraction.x,
    y = rect.top + rect.height * fraction.y,
)

/**
 * 卡片矩形 + 锚点中心 → 生长锚点（卡面比例）。锚点在卡片上方/下方时取整条顶边/底边，
 * 横向/纵向落在卡片内时按中心投影；超出左右范围钳到 0/1。矩形未量出返回 null，
 * 调用方跳过赋值、沿用上一帧锚点（不能把 NaN 灌进 TransformOrigin）。
 */
internal fun menuAnchorFraction(cardRect: IntRect, anchorCenter: Offset): TransformOrigin? {
    if (cardRect.width <= 0 || cardRect.height <= 0) return null
    val x = ((anchorCenter.x - cardRect.left) / cardRect.width).coerceIn(0f, 1f)
    val y = when {
        cardRect.top >= anchorCenter.y -> 0f
        cardRect.bottom <= anchorCenter.y -> 1f
        else -> ((anchorCenter.y - cardRect.top) / cardRect.height).coerceIn(0f, 1f)
    }
    return TransformOrigin(x, y)
}

/**
 * 生长锚点换算到**缩放图层**坐标（图层 = 卡片 + 四周透明投影留白）。[menuAnchorFraction]
 * 给的是卡面比例，这里先还原到卡片边缘像素、再加留白，避免直接写 0f/1f 锚到留白外缘
 * （动画中卡片会横竖各偏约 3dp）。尺寸未就绪时返回 null。
 */
internal fun menuAnchorOrigin(
    layerSize: Size,
    cardRect: IntRect,
    anchorCenter: Offset,
    padStart: Float,
    padTop: Float,
): TransformOrigin? {
    if (layerSize.width <= 0f || layerSize.height <= 0f) return null
    val fraction = menuAnchorFraction(cardRect, anchorCenter) ?: return null
    return TransformOrigin(
        pivotFractionX = (padStart + fraction.pivotFractionX * cardRect.width) / layerSize.width,
        pivotFractionY = (padTop + fraction.pivotFractionY * cardRect.height) / layerSize.height,
    )
}

/** 定位结果快照：位置计算写入、draw 阶段读取（生长锚点必须由最终几何反推） */
private class MenuGeometry {
    var cardRect: IntRect = IntRect.Zero
    var anchorCenter: Offset = Offset.Zero
}

/** 弹层可用的安全区（窗口四边内缩量）：系统栏 / IME / 刘海取并集，只在弹层在场时读取 */
private data class MenuInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val Zero = MenuInsets(0, 0, 0, 0)
    }
}

/**
 * 宿主滚动信号（B5）：非 focusable 菜单不消费外部触摸，宿主内容列滚动时锚点行会移动，
 * 而菜单停在冻结的窗口坐标上——菜单与锚点脱节。滚动容器用 [menuScrollDismiss] 接线，
 * [PopupMenuPopup] 打开期间观察到版本变化即收起菜单（用户手势滚动才上报，程序化滚动
 * 如 IME 让位不触发）。
 */
@Stable
class MenuScrollSignal {
    private var versionState by mutableLongStateOf(0L)

    /** 单调递增的滚动版本；菜单侧经 snapshotFlow 观察，不触发宿主重组 */
    val version: Long
        get() = versionState

    /** 滚动容器在用户滚动时调用 */
    fun notifyScrolled() {
        versionState++
    }
}

/** 浮层内容列的滚动信号；[PopupMenuPopup] 的 dismissOnScroll 从这里读取（无信号 = 不接） */
val LocalMenuScrollSignal = staticCompositionLocalOf<MenuScrollSignal?> { null }

/** 把宿主滚动容器接到 [MenuScrollSignal]：只在用户手势滚动、且本帧确有滚动量时上报 */
@Composable
fun Modifier.menuScrollDismiss(signal: MenuScrollSignal): Modifier {
    val connection = remember(signal) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (
                    source == NestedScrollSource.UserInput &&
                    (available.x != 0f || available.y != 0f)
                ) {
                    signal.notifyScrolled()
                }
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/**
 * 弹出菜单的共享弹层宿主：[PopupMenuCard] 白卡 + 自定义锚定 Popup（WeekPicker 周数弹窗 /
 * TodoTopBar 排序菜单同款范式），供右键菜单、选择行菜单、重复菜单等统一接入。
 *
 * 落位（A1）二选一：
 * - [placement]：声明式方位 + 自动翻转 + 安全区钳位。锚点取 [anchor]（窗口坐标，长按快照
 *   等 Popup 挂载点不等于锚点的宿主用）或 Popup 自身的 anchorBounds；
 * - [cardPosition]：逃生口，返回**卡片**左上角的期望窗口坐标（未扣投影留白）；传了就完全
 *   接管首选落位，但仍参与安全区钳位与生长锚点反推。[anchor] 同样用于反推生长锚点。
 *
 * 生长锚点恒由**最终**卡片矩形与锚点参考点反推（[menuAnchorOrigin]）：翻转、钳位、贴边后
 * 菜单自最靠近锚点的那条边长出，不再由调用点猜角。参考点默认是锚点矩形的几何中心
 * （[MenuAnchorCenter]），可经 [anchorFraction] 挪到矩形内的任意比例位置——锚点比菜单宽
 * 得多时（整行卡 + 自适应宽菜单）必须挪到靠边一端，否则中心落在菜单外、锚点被钳到卡缘
 * （见 [anchorPointOf]）。
 * [cardSize] 是去掉了投影留白的卡片尺寸（需要按卡片自身尺寸计算位置时用）；宿主不必再扣
 * 四周透明投影留白——留白只影响 Popup 窗口大小，不改变卡片的视觉位置。
 * [anchorBounds] 是 Popup 所在锚点的窗口坐标（弹层应挂在锚点内的 Box 上）。
 *
 * 安全区（B3）：钳位矩形按 `WindowInsets.systemBars` / `ime` 内缩，再留 [MenuPlacement.margin]
 * （cardPosition 逃生口用默认 8dp）。edge-to-edge 下贴顶/贴底不再压到系统栏，键盘在场时
 * 菜单也不会被 IME 盖住。
 *
 * 尺寸（B2）：内容上限 [MenuMaxWidth] 宽、窗口高 × [MenuMaxHeightFraction]，超出转内部滚动。
 *
 * 退场锁（A2）：收起后（[expanded] = false、退场动画还在播）行立即置为不可点，
 * 同时内容列停止滚动——双击 / 误触不会把同一个动作交给 [PopupActionGate] 执行两次。
 *
 * 动画（分层，见 [PopupMenuCard] 与 [rememberPopupMotion]）：进场 scale 走果冻一体
 * 曲线——生长冲过 1 顺势回缩定住（enterStartScale → 1.05 → 1）+ 整卡开头淡入
 * （[com.fangyi.classpp.ui.motion.Motion.PopupCardFadeMillis]）+ 内容淡入
 * （[com.fangyi.classpp.ui.motion.Motion.PopupFadeMillis]），模糊子层反向缩放保持背景
 * 对位，投影随缩放进度渐入。收起 scale 与内容淡出共用
 * [com.fangyi.classpp.ui.motion.Motion.PopupExitMillis] + Accelerate，同步收尾，播完才移除
 * 弹层；退场全程保持活采样毛玻璃（见下），沿原尾窗曲线淡出，另有一段 scale 尾区间兜底
 * 淡出，杜绝任何残留帧显形。整卡淡入淡出都压在首尾短窗内、中段恒满强度——长时间整卡
 * 半透明会把锐利背景混进毛玻璃，模糊看起来「由弱变强」（6f7cae7 存档的已知问题）。
 * [onExitFinished] 在退场播完后回调一次（宿主借机清理「仅退场期保留」的锚点状态；
 * 仅退场动画存在时才会触发，[animateExit] = false 时收起即卸载、不回调）。
 * [animateExit] = false 时收起不播退场动画、立即卸载。
 *
 * [hazeState] 下发给 [PopupMenuCard] 做毛玻璃（跨 Popup 窗口采样宿主页面 hazeSource），
 * null（如 @Preview、浮层内嵌宿主）退化为不透明白卡。
 *
 * [focusable] 默认 true：弹层窗口接管焦点，返回键收起、点外部收起（focusable 窗口会消费
 * 外部触摸），并把首焦点落到第一个可用项、支持上下方向键。**它只影响弹层窗口焦点**：
 * 窗口失焦可能让系统收起 IME，但不会清除宿主的 Compose 焦点（输入框描边仍在），关闭弹层后
 * 键盘也不会自动回来。原位输入类菜单（AI 输入栏附件、标签长按删除）传 false：不抢窗口
 * 焦点，正在输入的键盘与 Compose 焦点都保留；点外部仍由 `FLAG_WATCH_OUTSIDE_TOUCH` 的
 * ACTION_OUTSIDE 收起（触摸同时也会落到下层窗口，需要下层自行防重入），返回键由
 * [MenuBackHandler] 在宿主组合里兜底。
 *
 * [dismissOnScroll] 默认 true：宿主经 [LocalMenuScrollSignal] / [menuScrollDismiss] 接线后，
 * 打开期间一旦宿主动手滚动就收起（见 [MenuScrollSignal]）。无信号时无副作用。
 *
 * 退场活采样与崩溃防护（3e4d4dc 曾以「退场首帧卸掉毛玻璃」防护，退场退化白卡，已回退；
 * 尝试过的「退场冻结采样」（输入换空源态靠 KeepLastFrame 保留帧）真机证伪——保留帧只
 * 覆盖同一 state 内源短暂不可用，换输入态不触发）：崩溃 = 效果节点对源子树**活采样引用**
 * × 源子树**结构重写**同帧。不变量为「弹层在场（含退场）期间，被采样源不发生结构重写」，
 * 由宿主侧两类接线承担：
 * - 收起类重写动作（排序 / 调课 / 删除等，点选即收菜单）：经 [PopupActionGate] 延后到
 *   退场播完执行（[rememberPopupMotion] 的 onExitFinished）——重写帧弹层已卸载，零效果
 *   节点可撞（3e4d4dc 崩溃史的原始强不变量）；
 * - 开关类重写动作（菜单点选不收起，如待办分组显隐）：包 [com.fangyi.classpp.ui.components.HazeRetreatState.forRewrite]
 *   源侧退让——重写帧内 hazeSource 卸载两帧，活采样效果节点零源可引（防护方向同
 *   8038c0a 的设备验证），效果节点以保留帧或短暂白闪度过该窗口；
 * - 浮层内部非菜单触发的重写沿用既有 HazeRetreat 接线，不在本组件职责内。
 * 周数弹窗（WeekPicker）与分类菜单的长期在线表现佐证：纯收起 / 开关类不卸载亦未崩。
 */
@Composable
fun PopupMenuPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sections: List<PopupMenuSection>,
    placement: MenuPlacement = MenuPlacement(),
    cardPosition: ((anchorBounds: IntRect, windowSize: IntSize, cardSize: IntSize) -> IntOffset)? = null,
    anchor: Rect? = null,
    // 锚点矩形内的参考点比例（默认几何中心）：生长锚点按这个点反推，而不是按矩形中心。
    // 锚点比菜单宽得多时（整行卡 + 内容自适应宽的菜单）中心会落在菜单外、被钳到卡缘，
    // 此时传 (1f, …) 之类的靠边比例，才能表达「从行尾长出」
    anchorFraction: Offset = MenuAnchorCenter,
    hazeState: HazeState? = null,
    paneTitle: String? = null,
    // null = 自动判定（任一行带勾选态即按单选菜单暴露选中语义）；动作型 / 单选型菜单
    // 也可显式指定（B4）
    selectionMode: Boolean? = null,
    animateExit: Boolean = true,
    onExitFinished: () -> Unit = {},
    // false = 弹层窗口不抢焦点：输入中的键盘与 Compose 焦点都保留（原位输入类的菜单用）。
    // 代价是返回键不再由 Popup 窗口接收，由下方 MenuBackHandler 兜底；点外部照常收起
    // （FLAG_WATCH_OUTSIDE_TOUCH 的 ACTION_OUTSIDE）。
    focusable: Boolean = true,
    dismissOnScroll: Boolean = true,
) {
    // 进场时间轴走全部玻璃弹层共用的默认值（PopupEnterDefaultTiming），菜单与模态弹层
    // 同节奏，不再单独传
    val motion = rememberPopupMotion(
        expanded = expanded,
        animateExit = animateExit,
        onExitFinished = onExitFinished,
    )

    val density = LocalDensity.current
    val shadowPaddingPx = with(density) { CardShadowPadding.roundToPx() }
    val shadowBottomPx = with(density) { CardShadowBottomPadding.roundToPx() }
    val geometry = remember { MenuGeometry() }

    // B3：安全区在组合层取值（PopupPositionProvider 拿不到 insets）。只在弹层在场时读——
    // 未展开的菜单若也订阅 ime，键盘动画的每一帧都会让全部菜单宿主重组（设置页一行一个
    // 选择菜单）。systemBars 与 ime 取并集：菜单不压系统栏、也不被键盘盖住；
    // displayCutout 补上横屏刘海（systemBars 不含它）。
    val menuInsets = if (motion.shown) {
        val safeInsets = WindowInsets.systemBars
        val imeInsets = WindowInsets.ime
        val cutoutInsets = WindowInsets.displayCutout
        MenuInsets(
            left = maxOf(
                safeInsets.getLeft(density, LayoutDirection.Ltr),
                cutoutInsets.getLeft(density, LayoutDirection.Ltr),
            ),
            top = maxOf(safeInsets.getTop(density), cutoutInsets.getTop(density)),
            right = maxOf(
                safeInsets.getRight(density, LayoutDirection.Ltr),
                cutoutInsets.getRight(density, LayoutDirection.Ltr),
            ),
            bottom = maxOf(
                safeInsets.getBottom(density),
                cutoutInsets.getBottom(density),
                imeInsets.getBottom(density),
            ),
        )
    } else {
        MenuInsets.Zero
    }

    val positionProvider = remember(
        cardPosition,
        placement,
        anchor,
        anchorFraction,
        shadowPaddingPx,
        shadowBottomPx,
        menuInsets,
        density,
    ) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // Popup 内容 = 卡片 + 四周投影留白；回调只关心卡片，这里换算并扣除
                val cardSize = IntSize(
                    width = (popupContentSize.width - shadowPaddingPx * 2).coerceAtLeast(0),
                    height = (popupContentSize.height - shadowPaddingPx - shadowBottomPx).coerceAtLeast(0),
                )
                // 可用区 = 窗口 − 系统栏/IME − margin；翻转与钳位都以它为准。
                // windowSize 必须是窗口边界而不是可视区（见 Popup 的 clippingEnabled =
                // false）：可视区已扣过 IME/系统栏，这里再按 menuInsets 扣一次会把上边界
                // 顶进键盘上方，键盘在场时菜单卡被钳到锚点上方很远（AI 附件菜单即此形状）
                val marginPx = with(density) { placement.margin.roundToPx() }
                val available = IntRect(
                    left = menuInsets.left + marginPx,
                    top = menuInsets.top + marginPx,
                    right = (windowSize.width - menuInsets.right - marginPx)
                        .coerceAtLeast(menuInsets.left + marginPx),
                    bottom = (windowSize.height - menuInsets.bottom - marginPx)
                        .coerceAtLeast(menuInsets.top + marginPx),
                )
                val anchorRect = anchor?.roundToIntRect() ?: anchorBounds
                val desired = cardPosition?.let { position ->
                    val card = position(anchorBounds, windowSize, cardSize)
                    IntRect(card.x, card.y, card.x + cardSize.width, card.y + cardSize.height)
                } ?: placeMenu(placement, anchorRect, cardSize, available, density)
                // 先按安全区钳位，再按 Popup 内容边界终钳：后者只会把卡片往窗口内推（不会
                // 更贴边），投影留白因此不会被窗口边缘裁掉
                val cardRect = clampCard(desired, cardSize, available)
                val offsetX = (cardRect.left - shadowPaddingPx)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val offsetY = (cardRect.top - shadowPaddingPx)
                    .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                // 生长锚点必须按最终几何反推：位置计算写入、draw 阶段读取
                geometry.cardRect = IntRect(
                    left = offsetX + shadowPaddingPx,
                    top = offsetY + shadowPaddingPx,
                    right = offsetX + shadowPaddingPx + cardSize.width,
                    bottom = offsetY + shadowPaddingPx + cardSize.height,
                )
                geometry.anchorCenter = anchorPointOf(anchorRect, anchorFraction)
                return IntOffset(offsetX, offsetY)
            }
        }
    }

    // 非 focusable 弹层不收返回键（窗口没有输入焦点）：在宿主组合里补一层等价的分发
    if (!focusable) {
        MenuBackHandler(enabled = expanded, onBack = onDismiss)
    }

    // B5：宿主内容列滚动即收起（仅打开期间观察；程序化滚动不上报，见 MenuScrollSignal）
    val scrollSignal = LocalMenuScrollSignal.current
    if (dismissOnScroll && scrollSignal != null) {
        LaunchedEffect(expanded, scrollSignal) {
            if (!expanded) return@LaunchedEffect
            val openedAt = scrollSignal.version
            snapshotFlow { scrollSignal.version }.first { it != openedAt }
            onDismiss()
        }
    }

    // B2：最大高度在宿主组合层算（Popup 内部拿 LocalWindowInfo 会读到弹层自身尺寸）。
    // containerSize 在 API < 30 恒为 0，退回整屏高度。
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val maxMenuHeight = with(density) {
        if (windowHeightPx > 0) {
            (windowHeightPx * MenuMaxHeightFraction).toDp()
        } else {
            LocalConfiguration.current.screenHeightDp.dp * MenuMaxHeightFraction
        }
    }

    val resolvedPaneTitle = paneTitle ?: stringResource(R.string.cd_popup_menu)

    if (motion.shown) {
        Popup(
            onDismissRequest = onDismiss,
            popupPositionProvider = positionProvider,
            // focusable：返回键收起；dismissOnClickOutside 默认开启（非 focusable 时
            // 靠 FLAG_WATCH_OUTSIDE_TOUCH 的 ACTION_OUTSIDE 收起）。
            // clippingEnabled = false：开启时 Compose 传给 PopupPositionProvider 的
            // windowSize 是 getWindowVisibleDisplayFrame（已扣掉 IME 与系统栏），定位里
            // 再按 menuInsets 扣一次 IME，可用区会被顶到键盘上方——键盘在场时菜单被
            // 钳到锚点上方一截（AI 附件菜单）。关掉后 windowSize 是窗口边界，与
            // params 的坐标空间一致；越界仍由 clampCard 按安全区兜住
            properties = PopupProperties(focusable = focusable, clippingEnabled = false),
        ) {
            PopupMenuCard(
                sections = sections,
                // 退场全程活采样毛玻璃（WeekPicker 同款）：纯收起与延后/退让过的重写动作
                // 不构成「活引用 × 源重写」同帧，防护约定见上方 KDoc 与 PopupActionGate
                hazeState = hazeState,
                // 投影随缩放渐入（果冻回弹段恒满强度）；毛玻璃子层绕同一生长锚点反向
                // 缩放，生长/回弹全程与真实背景 1:1 对位——联动进度均在 draw 阶段读值
                blurProgress = motion::blurProgress,
                contentAlpha = { motion.contentAlpha.value },
                blurScale = motion::blurScale,
                // 生长锚点 = 最终卡片矩形上的锚点投影（翻转 / 钳位后自动正确）
                blurOrigin = { menuAnchorFraction(geometry.cardRect, geometry.anchorCenter) ?: TransformOrigin(0f, 0f) },
                // A2：退场期间行不可点、内容列不可滚
                interactive = expanded,
                paneTitle = resolvedPaneTitle,
                selectionMode = selectionMode,
                focusFirstItem = focusable,
                maxHeight = maxMenuHeight,
                // 投影留白垫在卡外（裁切线 = Popup 窗口边缘，留白多大柔影就有多少活动空间）：
                // 左右上 16dp、底部 48dp——光源在上投影向下坠得最远（WeekPicker 同款配方）；
                // 缩放排在其外，整卡（含柔影）一起变换
                modifier = Modifier
                    // Esc 收起（系统返回键由 Popup / MenuBackHandler 承担）
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    }
                    .popupMotionScale(
                        motion,
                        origin = { size ->
                            menuAnchorOrigin(
                                layerSize = size,
                                cardRect = geometry.cardRect,
                                anchorCenter = geometry.anchorCenter,
                                padStart = shadowPaddingPx.toFloat(),
                                padTop = shadowPaddingPx.toFloat(),
                            )
                        },
                    )
                    .padding(
                        start = CardShadowPadding,
                        top = CardShadowPadding,
                        end = CardShadowPadding,
                        bottom = CardShadowBottomPadding,
                    ),
            )
        }
    }
}

/**
 * 非 focusable 弹层的返回键兜底：弹层窗口没有输入焦点，收不到返回键，由宿主组合里的
 * BackHandler 承担（语义同 focusable Popup 的「返回键收起」）。
 *
 * 宿主没有 dispatcher（@Preview 等）时直接跳过——BackHandler 在没有 owner 时会抛异常。
 */
@Composable
internal fun MenuBackHandler(enabled: Boolean, onBack: () -> Unit) {
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = enabled, onBack = onBack)
    }
}

/**
 * 重写类菜单动作的退场门：菜单项 onClick 立即收起弹层、把动作交给 [defer]；
 * 退场播完（[rememberPopupMotion] 的 onExitFinished）调 [flush] 执行。动作按到达顺序
 * 排队（退场约 120ms 内连点两行不丢动作）。
 *
 * 弹层带 Haze 毛玻璃时退场全程活采样宿主源子树（[PopupMenuPopup] KDoc），同帧结构
 * 重写采样源会构建自引用 RenderNode（3e4d4dc 崩溃史）：排序 / 调课 / 删除等会增删宿主
 * 内容的动作一律过此门；打开选择器、纯文本草稿更新等无结构重写的动作不必。
 * 前提 [PopupMenuPopup] 的 animateExit = true（默认）：无退场即无 onExitFinished，
 * 延后动作不会执行。
 */
@Stable
class PopupActionGate internal constructor() {
    private val pending = ArrayDeque<() -> Unit>()

    /** 菜单项 onClick 内调用：收起弹层由宿主负责，重写动作延后到退场播完 */
    fun defer(action: () -> Unit) {
        pending.add(action)
    }

    /** 退场播完回调内调用：按序执行并清空延后动作；可与锚点清理等其他回调同帧 */
    fun flush() {
        while (pending.isNotEmpty()) {
            pending.removeFirst().invoke()
        }
    }
}

/** [PopupMenuPopup] 重写类动作的退场门，每份菜单一个（机制见 [PopupActionGate]） */
@Composable
fun rememberPopupActionGate(): PopupActionGate = remember { PopupActionGate() }
