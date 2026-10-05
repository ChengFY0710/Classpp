package com.fangyi.classpp.ui.schedule

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.MenuShape
import kotlin.math.roundToInt

/** 菜单上缘压进卡片底部的量：菜单贴着卡片长出来，而不是悬空在下方 */
private val MenuOverlap = 12.dp

/** 菜单左缘相对卡片左缘的内缩（设计稿里菜单比卡片略靠右） */
private val MenuLeftInset = 8.dp

/** 菜单的一项：文案 + 点击动作（收起菜单由调用方与动作一起处理，这里只管渲染） */
private data class ContextMenuItem(val label: String, val onClick: () -> Unit)

/**
 * 长按课程卡弹出的上下文菜单：「复制课程」（可选）+「新建交替课程」。
 *
 * [onCopy] 传 null 时不显示复制项——跨节课程暂不支持复制（见 ScheduleScreen 的菜单挂载点）。
 */
@Composable
internal fun CourseContextMenu(
    anchor: Rect,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)?,
    onNewAlternate: () -> Unit,
) {
    val items = buildList {
        if (onCopy != null) add(ContextMenuItem(stringResource(R.string.edit_copy_course), onCopy))
        add(ContextMenuItem(stringResource(R.string.edit_new_alternate), onNewAlternate))
    }
    ContextMenuPopup(anchor = anchor, onDismiss = onDismiss, items = items)
}

/**
 * 长按空位（添加卡片）弹出的上下文菜单：单项「粘贴课程」。
 * 只有剪贴板里有课时才会被挂载（见 ScheduleScreen 的 onSlotLongClick），否则长按无响应。
 */
@Composable
internal fun SlotContextMenu(
    anchor: Rect,
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
) {
    ContextMenuPopup(
        anchor = anchor,
        onDismiss = onDismiss,
        items = listOf(ContextMenuItem(stringResource(R.string.edit_paste_course), onPaste)),
    )
}

/**
 * 上下文菜单的共享弹层（设计稿二的白色圆角卡）。
 *
 * 定位沿用 [ScheduleHeader] 周数弹窗那套：自定义 [PopupPositionProvider]（DropdownMenu
 * 的锚定策略做不到贴卡片）+ `focusable` 弹窗（返回键/点外部收起）。
 * [anchor] 是长按那张卡/那格的窗口坐标（见 CourseGrid 的 BoundsHolder），菜单贴在它的左下角，
 * 越界时钳进窗口。
 *
 * 进场与周数弹窗同规格（Motion.PopupScale/PopupFadeMillis）：scale 0.8→1 + alpha 0→1。
 * 收起不做退场动画——动作（复制/开面板）与移除在同一帧发生，面板随即从底部升起来，
 * 视觉上接得住；也避免"动画没播完就点"时动作被吞掉。
 */
@Composable
private fun ContextMenuPopup(
    anchor: Rect,
    onDismiss: () -> Unit,
    items: List<ContextMenuItem>,
) {
    val visibleState = remember { MutableTransitionState(false) }
    // 首帧后置真：从 0.8/0 长到 1/1
    LaunchedEffect(Unit) { visibleState.targetState = true }

    val density = LocalDensity.current
    val positionProvider = remember(anchor, density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val overlap = with(density) { MenuOverlap.roundToPx() }
                val inset = with(density) { MenuLeftInset.roundToPx() }
                return IntOffset(
                    x = (anchor.left.roundToInt() + inset)
                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y = (anchor.bottom.roundToInt() - overlap)
                        .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                )
            }
        }
    }

    val transition = rememberTransition(visibleState, label = "ContextMenu")
    val scale by transition.animateFloat(
        transitionSpec = { tween(Motion.PopupScaleMillis, easing = Motion.Standard) },
        label = "scale",
    ) { expanded -> if (expanded) 1f else 0.8f }
    val alpha by transition.animateFloat(
        transitionSpec = { tween(Motion.PopupFadeMillis, easing = Motion.Standard) },
        label = "alpha",
    ) { expanded -> if (expanded) 1f else 0f }

    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        // focusable：返回键收起；dismissOnClickOutside 默认开启
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MenuShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                // 自卡片那一侧（左上角）长出来
                transformOrigin = TransformOrigin(0f, 0f)
            },
        ) {
            // IntrinsicSize.Max + fillMaxWidth：多项时整个菜单取最宽项的宽度，各项等宽
            Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { item.onClick() }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = item.label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
