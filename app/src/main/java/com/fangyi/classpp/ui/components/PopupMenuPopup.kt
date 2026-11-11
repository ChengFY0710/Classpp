package com.fangyi.classpp.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fangyi.classpp.ui.motion.popupMotionScale
import com.fangyi.classpp.ui.motion.rememberPopupMotion
import com.fangyi.classpp.ui.schedule.CardShadowBottomPadding
import com.fangyi.classpp.ui.schedule.CardShadowPadding
import dev.chrisbanes.haze.HazeState

/**
 * 行尾 20dp 上下箭头锚定的菜单，卡顶边贴行顶边的上移量：
 * 图标 20dp + 行上 padding 14dp（原 DropdownMenu offset -34dp，选择行 / 重复行共用）。
 */
val MenuTopAlignLift = 34.dp

/**
 * 弹层生长锚点：进场缩放自卡片边缘的哪个点长出。[TopLeft]（默认）= 卡片左上角，
 * 菜单挂在行尾 / 锚点靠屏幕右侧时传 [TopRight]；[TopCenter] = 顶边中点（周数弹窗等
 * 居中弹层用）。
 *
 * 点即**卡片边缘**上的点（不含四周透明投影留白），换算见 [menuGrowthOrigin]；
 * 不做 RTL 镜像——整个菜单族都按物理左右定位（各调用点的 cardPosition 亦取 anchorBounds.left）。
 */
enum class MenuGrowth { TopLeft, TopCenter, TopRight, BottomLeft, BottomRight }

/**
 * 生长锚点 → 图层内的比例锚点。图层 = 卡片 + 四周透明投影留白，直接写 0f/1f 会锚在
 * 留白外缘（动画中卡片因此横竖各偏约 3dp），故按卡片实际边缘反推比例。
 * [layerSize] 尚未量出（首帧为 0）时返回 null，调用方跳过赋值、沿用上一帧的锚点。
 */
internal fun menuGrowthOrigin(
    growth: MenuGrowth,
    layerSize: Size,
    padStart: Float,
    padEnd: Float,
    padTop: Float,
    padBottom: Float,
): TransformOrigin? {
    if (layerSize.width <= 0f || layerSize.height <= 0f) return null
    val left = padStart / layerSize.width
    val right = (layerSize.width - padEnd) / layerSize.width
    val top = padTop / layerSize.height
    val bottom = (layerSize.height - padBottom) / layerSize.height
    return when (growth) {
        MenuGrowth.TopLeft -> TransformOrigin(left, top)
        MenuGrowth.TopCenter -> TransformOrigin(0.5f, top)
        MenuGrowth.TopRight -> TransformOrigin(right, top)
        MenuGrowth.BottomLeft -> TransformOrigin(left, bottom)
        MenuGrowth.BottomRight -> TransformOrigin(right, bottom)
    }
}

/**
 * 锚点落在窗口右半屏时的生长角：菜单自锚点向右铺开，贴右缘的锚点放不下会被钳到屏幕右缘，
 * 锚点随即跑到卡片左侧，此时该用 [MenuGrowth.TopRight]。
 * [windowWidthPx] 未知（≤ 0）时退回默认左角。
 */
internal fun menuGrowthForAnchor(anchorCenterX: Float, windowWidthPx: Int): MenuGrowth =
    if (windowWidthPx > 0 && anchorCenterX > windowWidthPx / 2f) {
        MenuGrowth.TopRight
    } else {
        MenuGrowth.TopLeft
    }

/**
 * 弹出菜单的共享弹层宿主：[PopupMenuCard] 白卡 + 自定义锚定 Popup（WeekPicker 周数弹窗 /
 * TodoTopBar 排序菜单同款范式），供右键菜单、选择行菜单、重复菜单等统一接入。
 *
 * [cardPosition] 返回**卡片**左上角的期望窗口坐标（未扣投影留白、未钳窗口），
 * [cardSize] 是去掉留白后的卡片尺寸（需要按卡片自身尺寸计算位置时用）；
 * 宿主负责扣掉四周透明投影留白（左右上 [CardShadowPadding]、底部 [CardShadowBottomPadding]，
 * 36dp 柔影向下坠得最远）并钳进窗口——留白只影响 Popup 窗口大小，不改变卡片的视觉位置。
 * [anchorBounds] 是 Popup 所在锚点（弹层应挂在锚点内的 Box 上）的窗口坐标。
 *
 * 动画（分层，见 [PopupMenuCard] 与 [rememberPopupMotion]）：进场 scale 走果冻一体
 * 曲线——生长冲过 1 顺势回缩定住（enterStartScale → 1.05 → 1）+ 整卡开头淡入
 * （[com.fangyi.classpp.ui.motion.Motion.PopupCardFadeMillis]）+ 内容淡入
 * （[com.fangyi.classpp.ui.motion.Motion.PopupFadeMillis]），模糊子层反向缩放保持背景
 * 对位，投影随缩放进度渐入，自 [menuGrowth] 指定的卡片锚点长出——默认 [MenuGrowth.TopLeft]；
 * 菜单挂在行尾 / 锚点靠屏幕右侧时传 [MenuGrowth.TopRight]（该点只影响缩放锚点，不参与布局），
 * 锚点位置随内容变化的宿主（如课表按星期列）用 [menuGrowthForAnchor] 按半屏现算。
 * 收起 scale 与内容淡出共用 [com.fangyi.classpp.ui.motion.Motion.PopupExitMillis] +
 * Accelerate，同步收尾，播完才移除弹层；退场全程保持活采样毛玻璃（见下），沿原尾窗
 * 曲线淡出（[com.fangyi.classpp.ui.motion.Motion.PopupCardFadeMillis]），另有一段 scale
 * 尾区间兜底淡出，杜绝任何残留帧显形。整卡淡入淡出都压在首尾短窗内、中段恒满强度——
 * 长时间整卡半透明会把锐利背景混进毛玻璃，模糊看起来「由弱变强」（6f7cae7 存档的
 * 已知问题）。
 * [onExitFinished] 在退场播完后回调一次（宿主借机清理「仅退场期保留」的锚点状态；
 * 仅退场动画存在时才会触发，[animateExit] = false 时收起即卸载、不回调）。
 * [animateExit] = false 时收起不播退场动画、立即卸载。
 *
 * [hazeState] 下发给 [PopupMenuCard] 做毛玻璃（跨 Popup 窗口采样宿主页面 hazeSource），
 * null（如 @Preview、浮层内嵌宿主）退化为不透明白卡。
 *
 * [focusable] 默认 true：弹层窗口接管焦点，返回键收起、点外部收起（focusable 窗口会消费
 * 外部触摸）。**它只影响弹层窗口焦点**：窗口失焦可能让系统收起 IME，但不会清除宿主的
 * Compose 焦点（输入框描边仍在），关闭弹层后键盘也不会自动回来。原位输入类菜单（AI 输入栏
 * 附件、标签长按删除）传 false：不抢窗口焦点，正在输入的键盘与 Compose 焦点都保留；
 * 点外部仍由 `FLAG_WATCH_OUTSIDE_TOUCH` 的 ACTION_OUTSIDE 收起（触摸同时也会落到下层
 * 窗口，需要下层自行防重入），返回键由 [MenuBackHandler] 在宿主组合里兜底。
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
    cardPosition: (anchorBounds: IntRect, windowSize: IntSize, cardSize: IntSize) -> IntOffset,
    hazeState: HazeState? = null,
    menuGrowth: MenuGrowth = MenuGrowth.TopLeft,
    animateExit: Boolean = true,
    onExitFinished: () -> Unit = {},
    // false = 弹层窗口不抢焦点：输入中的键盘与 Compose 焦点都保留（原位输入类的菜单用）。
    // 代价是返回键不再由 Popup 窗口接收，由下方 MenuBackHandler 兜底；点外部照常收起
    // （FLAG_WATCH_OUTSIDE_TOUCH 的 ACTION_OUTSIDE）。
    focusable: Boolean = true,
) {
    val motion = rememberPopupMotion(expanded, animateExit, onExitFinished)

    val density = LocalDensity.current
    val shadowPaddingPx = with(density) { CardShadowPadding.roundToPx() }
    val shadowBottomPx = with(density) { CardShadowBottomPadding.roundToPx() }
    val positionProvider = remember(cardPosition, shadowPaddingPx, shadowBottomPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // Popup 内容 = 卡片 + 四周投影留白；回调只关心卡片，这里换算并扣除
                val cardSize = IntSize(
                    width = popupContentSize.width - shadowPaddingPx * 2,
                    height = popupContentSize.height - shadowPaddingPx - shadowBottomPx,
                )
                val card = cardPosition(anchorBounds, windowSize, cardSize)
                return IntOffset(
                    // 卡片目标位先扣投影留白，让「卡片」而非「含留白的内容」落位；越界钳进窗口
                    x = (card.x - shadowPaddingPx)
                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y = (card.y - shadowPaddingPx)
                        .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                )
            }
        }
    }

    // 非 focusable 弹层不收返回键（窗口没有输入焦点）：在宿主组合里补一层等价的分发
    if (!focusable) {
        MenuBackHandler(enabled = expanded, onBack = onDismiss)
    }

    if (motion.shown) {
        Popup(
            onDismissRequest = onDismiss,
            popupPositionProvider = positionProvider,
            // focusable：返回键收起；dismissOnClickOutside 默认开启（非 focusable 时
            // 靠 FLAG_WATCH_OUTSIDE_TOUCH 的 ACTION_OUTSIDE 收起）
            properties = PopupProperties(focusable = focusable),
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
                menuGrowth = menuGrowth,
                // 投影留白垫在卡外（裁切线 = Popup 窗口边缘，留白多大柔影就有多少活动空间）：
                // 左右上 16dp、底部 48dp——光源在上投影向下坠得最远（WeekPicker 同款配方）；
                // 缩放排在其外，整卡（含柔影）一起变换
                modifier = Modifier
                    .popupMotionScale(
                        motion,
                        origin = { size ->
                            menuGrowthOrigin(
                                growth = menuGrowth,
                                layerSize = size,
                                padStart = shadowPaddingPx.toFloat(),
                                padEnd = shadowPaddingPx.toFloat(),
                                padTop = shadowPaddingPx.toFloat(),
                                padBottom = shadowBottomPx.toFloat(),
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
