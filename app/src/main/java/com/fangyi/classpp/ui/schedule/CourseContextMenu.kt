package com.fangyi.classpp.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuPopup
import com.fangyi.classpp.ui.components.PopupMenuSection
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/** 菜单上缘压进卡片底部的量：菜单贴着卡片长出来，而不是悬空在下方 */
private val MenuOverlap = 12.dp

/** 菜单左缘相对卡片左缘的内缩（设计稿里菜单比卡片略靠右） */
private val MenuLeftInset = 8.dp

/**
 * 长按课程卡弹出的上下文菜单：「复制课程」（可选）+「新建交替课程」。
 *
 * [onCopy] 传 null 时不显示复制项——跨节课程暂不支持复制（见 ScheduleScreen 的菜单挂载点）。
 * [hazeState] 下发给 [PopupMenuPopup] 做毛玻璃（采样课表网格 hazeSource，与周数弹窗同材质），
 * null（如 @Preview）退化为不透明白卡。
 */
@Composable
internal fun CourseContextMenu(
    anchor: Rect,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)?,
    onNewAlternate: () -> Unit,
    hazeState: HazeState? = null,
) {
    val items = buildList {
        if (onCopy != null) add(PopupMenuItem(stringResource(R.string.edit_copy_course), onClick = onCopy))
        add(PopupMenuItem(stringResource(R.string.edit_new_alternate), onClick = onNewAlternate))
    }
    ContextMenuPopup(anchor = anchor, onDismiss = onDismiss, items = items, hazeState = hazeState)
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
    hazeState: HazeState? = null,
) {
    ContextMenuPopup(
        anchor = anchor,
        onDismiss = onDismiss,
        items = listOf(PopupMenuItem(stringResource(R.string.edit_paste_course), onClick = onPaste)),
        hazeState = hazeState,
    )
}

/**
 * 上下文菜单的共享弹层（设计稿二的白色圆角卡）：[PopupMenuPopup] 共享宿主
 * （[PopupMenuCard] 白卡 + 36dp 柔影 + scale/alpha 进出场）。
 *
 * 定位沿用 [ScheduleHeader] 周数弹窗那套：自定义锚定由宿主的 cardPosition 回调表达
 * （DropdownMenu 的锚定策略做不到贴卡片），[anchor] 是长按那张卡/那格的窗口坐标
 * （见 CourseGrid 的 BoundsHolder），菜单贴在它的左下角，投影留白与越界钳制由宿主处理。
 * 挂载即展开（宿主经 menuAnchor?.let 控制生命周期），[PopupMenuPopup] 的
 * animateExit = false——收起不做退场动画：动作（复制/开面板）与移除在同一帧发生，
 * 面板随即从底部升起来，视觉上接得住；也避免"动画没播完就点"时动作被吞掉。
 *
 * 行样式与其它菜单统一（PopupMenuCard 默认行）：onSurface 深色 + 左侧 20dp 勾选槽，
 * 项间分割线经「每项独占一个 [PopupMenuSection]」表达（首组忽略 divider）。
 */
@Composable
private fun ContextMenuPopup(
    anchor: Rect,
    onDismiss: () -> Unit,
    items: List<PopupMenuItem>,
    hazeState: HazeState?,
) {
    val density = LocalDensity.current
    val overlapPx = with(density) { MenuOverlap.roundToPx() }
    val insetPx = with(density) { MenuLeftInset.roundToPx() }
    // 每项独占一组：PopupMenuSection 的分割线画在「与上一组之间」，逐项成组即项间分割线
    val sections = items.mapIndexed { index, item ->
        PopupMenuSection(items = listOf(item), showDivider = index > 0)
    }
    PopupMenuPopup(
        // 挂载即展开（进场动画照播）；收起由挂载方卸载，无退场
        expanded = true,
        onDismiss = onDismiss,
        sections = sections,
        hazeState = hazeState,
        // 卡片左缘 = 卡片左缘 + 内缩，顶缘 = 卡片底缘 - 压入量（贴着卡片左下角长出）。
        // 不用回调给的 anchorBounds——本弹层由挂载方（menuAnchor）渲染在任意位置，
        // 定位只认长按卡片的窗口坐标 anchor
        cardPosition = { _, _, _ ->
            IntOffset(
                x = anchor.left.roundToInt() + insetPx,
                y = anchor.bottom.roundToInt() - overlapPx,
            )
        },
        animateExit = false,
    )
}
