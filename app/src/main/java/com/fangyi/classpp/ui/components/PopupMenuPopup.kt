package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
 * 弹出菜单的共享弹层宿主：[PopupMenuCard] 白卡 + 自定义锚定 Popup（WeekPicker 周数弹窗 /
 * TodoTopBar 排序菜单同款范式），供右键菜单、选择行菜单、重复菜单等统一接入。
 *
 * [cardPosition] 返回**卡片**左上角的期望窗口坐标（未扣投影留白、未钳窗口），
 * [cardSize] 是去掉留白后的卡片尺寸（需要按卡片自身尺寸计算位置时用）；
 * 宿主负责扣掉四周透明投影留白（左右上 [CardShadowPadding]、底部 [CardShadowBottomPadding]，
 * 45dp 柔影向下坠得最远）并钳进窗口——留白只影响 Popup 窗口大小，不改变卡片的视觉位置。
 * [anchorBounds] 是 Popup 所在锚点（弹层应挂在锚点内的 Box 上）的窗口坐标。
 *
 * 动画：进场 scale 0.8→1（[Motion.PopupScaleMillis]）+ alpha 0→1（[Motion.PopupFadeMillis]），
 * 自 [transformOrigin]（默认左上角）长出；收起反向播放，播完才移除弹层。
 * [animateExit] = false 时收起不播退场动画、立即卸载——供"动作与移除同帧发生、
 * 随后由面板升起接住视觉"的宿主（如右键菜单）使用。
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
    transformOrigin: TransformOrigin = TransformOrigin(0f, 0f),
    animateExit: Boolean = true,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded

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

    // 退场动画期间保持弹层在场：expanded（开）或 currentState（关动画未完，仅 animateExit 时）
    if (expanded || (animateExit && visibleState.currentState)) {
        val transition = rememberTransition(visibleState, label = "PopupMenu")
        val scale by transition.animateFloat(
            transitionSpec = { tween(Motion.PopupScaleMillis, easing = Motion.Standard) },
            label = "scale",
        ) { visible -> if (visible) 1f else 0.8f }
        val alpha by transition.animateFloat(
            transitionSpec = { tween(Motion.PopupFadeMillis, easing = Motion.Standard) },
            label = "alpha",
        ) { visible -> if (visible) 1f else 0f }

        Popup(
            onDismissRequest = onDismiss,
            popupPositionProvider = positionProvider,
            // focusable：返回键收起；dismissOnClickOutside 默认开启
            properties = PopupProperties(focusable = true),
        ) {
            PopupMenuCard(
                sections = sections,
                hazeState = hazeState,
                // 投影留白垫在卡外（裁切线 = Popup 窗口边缘，留白多大柔影就有多少活动空间）：
                // 左右上 16dp、底部 48dp——光源在上投影向下坠得最远（WeekPicker 同款配方）；
                // 缩放/淡入排在其外，整卡（含柔影）一起变换
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        this.transformOrigin = transformOrigin
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
