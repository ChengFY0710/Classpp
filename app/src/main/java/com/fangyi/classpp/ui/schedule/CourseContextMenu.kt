package com.fangyi.classpp.ui.schedule

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlin.math.roundToInt

private val MenuShape = RoundedCornerShape(16.dp)

/** 菜单上缘压进卡片底部的量：菜单贴着卡片长出来，而不是悬空在下方 */
private val MenuOverlap = 12.dp

/** 菜单左缘相对卡片左缘的内缩（设计稿里菜单比卡片略靠右） */
private val MenuLeftInset = 8.dp

/**
 * 长按课程卡弹出的上下文菜单（设计稿二的白色圆角卡，当前只有「新建交替课程」一项）。
 *
 * 定位沿用 [ScheduleHeader] 周数弹窗那套：自定义 [PopupPositionProvider]（DropdownMenu
 * 的锚定策略做不到贴卡片）+ `focusable` 弹窗（返回键/点外部收起）。
 * [anchor] 是长按那张卡的窗口坐标（见 CourseGrid 的 BoundsHolder），菜单贴在它的左下角，
 * 越界时钳进窗口。
 *
 * 进场与周数弹窗同规格：scale 0.8→1（200ms）+ alpha 0→1（120ms）。
 * 收起不做退场动画——动作（开新建交替课程面板）与移除在同一帧发生，
 * 面板随即从底部升起来，视觉上接得住；也避免"动画没播完就点"时动作被吞掉。
 */
@Composable
internal fun CourseContextMenu(
    anchor: Rect,
    onDismiss: () -> Unit,
    onNewAlternate: () -> Unit,
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

    val transition = rememberTransition(visibleState, label = "CourseContextMenu")
    val scale by transition.animateFloat(
        transitionSpec = { tween(200, easing = FastOutSlowInEasing) },
        label = "scale",
    ) { expanded -> if (expanded) 1f else 0.8f }
    val alpha by transition.animateFloat(
        transitionSpec = { tween(120, easing = FastOutSlowInEasing) },
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
            Box(
                modifier = Modifier
                    .clickable { onNewAlternate() }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.edit_new_alternate),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}
