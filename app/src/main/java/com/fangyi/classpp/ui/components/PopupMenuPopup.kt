package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.schedule.CardShadowBottomPadding
import com.fangyi.classpp.ui.schedule.CardShadowPadding
import dev.chrisbanes.haze.HazeState

/**
 * 行尾 20dp 上下箭头锚定的菜单，卡顶边贴行顶边的上移量：
 * 图标 20dp + 行上 padding 14dp（原 DropdownMenu offset -34dp，选择行 / 重复行共用）。
 */
val MenuTopAlignLift = 34.dp

/**
 * 退场尾段整卡淡出兜底覆盖的 scale 区间长度：scale 走完最后这段（0.85→0.8，约 30ms）
 * 时整卡淡到全透明。进场方向恒满强度，模糊「由弱变强」伪影不可能出现在可见段——
 * 这段淡出只为保证收起的任何一帧（含被竞态/残留拖住的末帧）不会显形成白卡。
 */
private const val PopupExitTailFadeScaleSpan = 0.05f

/**
 * 进场 + 果冻回弹一体曲线：0.8 → 1.05 → 1 一条 keyframes 走完（约 800ms）。
 * 生长冲过 1 后顺势回缩定住，没有「先减速停住、再重新起拍」的 velocity 死点，
 * 两段端点（1.05 / 1.0）都落在振幅极值（速度为零），读起来是一条连续的阻尼振荡：
 * - 0 → 200ms 生长段（[Motion.Standard]）：起步逐渐加速上冲到峰值；
 * - 200 → 800ms 收定段（[Motion.Standard]）：回到 1 定住。
 * 锚点为生长角：整条曲线绕同一锚点，果冻回弹朝锚点反方向撑开，与「贴着锚点长出来」同构。
 */
private val PopupEnterJelly: FiniteAnimationSpec<Float> = keyframes {
    durationMillis = 800
    Motion.PopupEnterStartScale at 0
    1.05f at 200 using Motion.Standard
    1f at 800 using Motion.Standard
}

/**
 * 菜单生长角：进场缩放自卡片四角中的哪一角长出。[TopLeft]（默认）= 卡片左上角，
 * 菜单挂在行尾 / 锚点靠屏幕右侧时传 [TopRight]。
 *
 * 角即**卡片边缘**上的角（不含四周透明投影留白），换算见 [menuGrowthOrigin]；
 * 不做 RTL 镜像——整个菜单族都按物理左右定位（各调用点的 cardPosition 亦取 anchorBounds.left）。
 */
enum class MenuGrowth { TopLeft, TopRight, BottomLeft, BottomRight }

/**
 * 生长角 → 图层内的比例锚点。图层 = 卡片 + 四周透明投影留白，直接写 0f/1f 会锚在留白外缘
 * （动画中卡片因此横竖各偏约 3dp），故按卡片实际边缘反推比例。
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
 * 动画（分层，见 [PopupMenuCard]：毛玻璃卡壳不淡入，模糊首帧即最终强度）：
 * 进场 scale 0.8→1 + 内容淡入（[Motion.PopupFadeMillis]）：
 * scale 走 [PopupEnterJelly] 一体曲线——生长冲过 1 顺势回缩定住（0.8 → 1.05 → 1，
 * 约 800ms），入场与果冻无缝衔接，模糊子层反向缩放保持背景对位，
 * 投影随缩放进度渐入，自 [menuGrowth] 指定的卡片角长出——默认 [MenuGrowth.TopLeft]；
 * 菜单挂在行尾 / 锚点靠屏幕右侧时传 [MenuGrowth.TopRight]（该角只影响缩放锚点，不参与布局），
 * 锚点位置随内容变化的宿主（如课表按星期列）用 [menuGrowthForAnchor] 按半屏现算。
 * 收起 scale 与内容淡出共用 [Motion.PopupExitMillis] + Accelerate，同步收尾，播完才移除弹层；
 * 收起最后约 30ms（scale 0.85→0.8）整卡淡出到全透明作兜底——进场方向恒满强度、模糊
 * 「由弱变强」伪影不可能出现，这段淡出只为杜绝任何残留帧显形成白卡。
 * [onExitFinished] 在退场播完后回调一次（宿主借机清理「仅退场期保留」的锚点状态；
 * 仅退场动画存在时才会触发，[animateExit] = false 时收起即卸载、不回调）。
 * [animateExit] = false 时收起不播退场动画、立即卸载。
 *
 * [hazeState] 下发给 [PopupMenuCard] 做毛玻璃（跨 Popup 窗口采样宿主页面 hazeSource），
 * null（如 @Preview 或浮层内嵌宿主）退化为不透明白卡。
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
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded

    // 退场播完回调：曾进场且当前收起、动画静止时回调一次。放在下方 if 之外——
    // 退场结束后该 if 条件为假、transition 已不组合，回调逻辑须独立于它存活
    var hasOpened by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) hasOpened = true
    }
    LaunchedEffect(visibleState.currentState) {
        if (hasOpened && !expanded && visibleState.isIdle && !visibleState.currentState) {
            hasOpened = false
            onExitFinished()
        }
    }

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

    // 动画常驻在下方 if 之外：过渡必须活着才能把 currentState 归位到 false，「退场播完
    // 移除弹层 / onExitFinished」都依赖它——若随 if 块一起组装卸载，动画完成回调与自身
    // 卸载存在竞态，极端时序下 currentState 会停在 true，弹层（毛玻璃壳）卡在屏幕上
    val transition = rememberTransition(visibleState, label = "PopupMenu")
    // 分层动画：卡壳只缩放不淡（整卡 alpha 会把锐利背景混进毛玻璃，模糊「由弱变强」），
    // 内容行单独淡入淡出，投影随缩放进度渐入渐出。
    // 进场走 [PopupEnterJelly] 一体曲线（生长 + 果冻回弹无缝衔接）；退场 Accelerate
    // 更短更利落，scale 与内容淡出同步收尾——沿用进场的「fade 120 / scale 200」会在
    // 退场出现「alpha 已归零、scale 还在隐形缩小」的空窗
    val scaleState = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                PopupEnterJelly
            } else {
                tween(Motion.PopupExitMillis, easing = Motion.Accelerate)
            }
        },
        label = "scale",
    ) { visible -> if (visible) 1f else Motion.PopupEnterStartScale }
    val contentAlphaState = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(Motion.PopupFadeMillis, easing = Motion.Decelerate)
            } else {
                tween(Motion.PopupExitMillis, easing = Motion.Accelerate)
            }
        },
        label = "contentAlpha",
    ) { visible -> if (visible) 1f else 0f }

    // 退场动画期间保持弹层在场：expanded（开）或 currentState（关动画未完，仅 animateExit 时）
    if (expanded || (animateExit && visibleState.currentState)) {
        Popup(
            onDismissRequest = onDismiss,
            popupPositionProvider = positionProvider,
            // focusable：返回键收起；dismissOnClickOutside 默认开启
            properties = PopupProperties(focusable = true),
        ) {
            PopupMenuCard(
                sections = sections,
                hazeState = hazeState,
                // 投影随缩放渐入：scale 未到 1 前按进度渐入，越过 1（果冻回弹段）恒满强度，
                // 投影不随回缩呼吸（draw 阶段读值，零重组）
                blurProgress = {
                    val s = scaleState.value
                    if (s >= 1f) {
                        1f
                    } else {
                        ((s - Motion.PopupEnterStartScale) / (1f - Motion.PopupEnterStartScale))
                            .coerceIn(0f, 1f)
                    }
                },
                contentAlpha = { contentAlphaState.value },
                // 毛玻璃子层绕同一生长角反向缩放：生长/果冻回弹中模糊与真实背景保持 1:1 对位
                blurScale = { scaleState.value },
                menuGrowth = menuGrowth,
                // 投影留白垫在卡外（裁切线 = Popup 窗口边缘，留白多大柔影就有多少活动空间）：
                // 左右上 16dp、底部 48dp——光源在上投影向下坠得最远（WeekPicker 同款配方）；
                // 缩放排在其外，整卡（含柔影）一起变换
                modifier = Modifier
                    .graphicsLayer {
                        val scale = scaleState.value
                        scaleX = scale
                        scaleY = scale
                        // 退场尾段整卡淡出兜底（进场恒满强度，模糊不会「由弱变强」）：方向按
                        // targetState 在 draw 阶段现读，scale 走完最后 [PopupExitTailFadeScaleSpan]
                        // 时淡到全透明——收起的任何一帧（含被竞态/残留拖住的末帧，无论卡在
                        // 组合层、Popup 窗口还是 Haze 效果层）都不再可能显形成白卡
                        alpha = if (visibleState.targetState) {
                            1f
                        } else {
                            ((scale - Motion.PopupEnterStartScale) / PopupExitTailFadeScaleSpan)
                                .coerceIn(0f, 1f)
                        }
                        // 锚点 = 卡片上离锚点近的那只角；块里拿得到图层实测尺寸（含投影留白），
                        // 故留白按比例扣掉后锚点正好落在卡片边缘
                        menuGrowthOrigin(
                            growth = menuGrowth,
                            layerSize = size,
                            padStart = CardShadowPadding.toPx(),
                            padEnd = CardShadowPadding.toPx(),
                            padTop = CardShadowPadding.toPx(),
                            padBottom = CardShadowBottomPadding.toPx(),
                        )?.let { transformOrigin = it }
                    }
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
