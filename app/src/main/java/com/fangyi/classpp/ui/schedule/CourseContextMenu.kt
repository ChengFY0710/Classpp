package com.fangyi.classpp.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuPopup
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.components.menuGrowthForAnchor
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
 * [expanded] / [onExitFinished] 供宿主拆「锚点 + 可见性」两态（见 [ContextMenuPopup]）。
 * [hazeState] 下发给 [PopupMenuPopup] 做毛玻璃（采样课表网格 hazeSource，与周数弹窗同材质），
 * null（如 @Preview）退化为不透明白卡。
 */
@Composable
internal fun CourseContextMenu(
    anchor: Rect,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)?,
    onNewAlternate: () -> Unit,
    hazeState: HazeState? = null,
    onExitFinished: () -> Unit = {},
) {
    val items = buildList {
        if (onCopy != null) add(PopupMenuItem(stringResource(R.string.edit_copy_course), onClick = onCopy))
        add(PopupMenuItem(stringResource(R.string.edit_new_alternate), onClick = onNewAlternate))
    }
    ContextMenuPopup(
        anchor = anchor,
        expanded = expanded,
        onDismiss = onDismiss,
        items = items,
        hazeState = hazeState,
        onExitFinished = onExitFinished,
    )
}

/**
 * 长按空位（添加卡片）弹出的上下文菜单：单项「粘贴课程」。
 * 只有剪贴板里有课时才会被挂载（见 ScheduleScreen 的 onSlotLongClick），否则长按无响应。
 */
@Composable
internal fun SlotContextMenu(
    anchor: Rect,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
    hazeState: HazeState? = null,
    onExitFinished: () -> Unit = {},
) {
    ContextMenuPopup(
        anchor = anchor,
        expanded = expanded,
        onDismiss = onDismiss,
        items = listOf(PopupMenuItem(stringResource(R.string.edit_paste_course), onClick = onPaste)),
        hazeState = hazeState,
        onExitFinished = onExitFinished,
    )
}

/**
 * 上下文菜单的共享弹层（设计稿二的白色圆角卡）：[PopupMenuPopup] 共享宿主
 * （[PopupMenuCard] 白卡 + 36dp 柔影 + scale/内容淡入进出场）。
 *
 * 定位沿用 [ScheduleHeader] 周数弹窗那套：自定义锚定由宿主的 cardPosition 回调表达
 * （DropdownMenu 的锚定策略做不到贴卡片），[anchor] 是长按那张卡/那格的窗口坐标
 * （见 CourseGrid 的 BoundsHolder），菜单贴在它的左下角，投影留白与越界钳制由宿主处理。
 * 生命周期走「锚点 + 可见性」两态：宿主收起只翻 [expanded]，锚点保留到退场播完
 * （[onExitFinished] 后再清）——退场 150ms 里菜单内容稳定、行点击动作仍可命中
 * （动作照旧与收起同帧执行，视觉移除交给退场动画，规避原「动画没播完就点动作被吞」问题）。
 *
 * 行样式与其它菜单统一（PopupMenuCard 默认行）：onSurface 深色 + 左侧 20dp 勾选槽，
 * 整份菜单为单组无缝形态（不画项间分割线）。
 *
 * 生长角按锚点所在半屏现算（[menuGrowthForAnchor]）：课表按星期列横跨整屏，锚点在左半屏
 * 时菜单向右铺得下、用左上角；在右半屏（周五之后）会被钳到屏幕右缘、锚点跑到卡片左侧，
 * 改从右上角长出来。窗口宽取 containerSize，拿不到（如 API < 30 恒为 0）时退回宿主 View 宽度。
 */
@Composable
private fun ContextMenuPopup(
    anchor: Rect,
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<PopupMenuItem>,
    hazeState: HazeState?,
    onExitFinished: () -> Unit,
) {
    val density = LocalDensity.current
    val overlapPx = with(density) { MenuOverlap.roundToPx() }
    val insetPx = with(density) { MenuLeftInset.roundToPx() }
    val windowWidthPx = LocalWindowInfo.current.containerSize.width.takeIf { it > 0 }
        ?: LocalView.current.width
    PopupMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        sections = listOf(PopupMenuSection(items = items)),
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
        menuGrowth = menuGrowthForAnchor(anchor.center.x, windowWidthPx),
        onExitFinished = onExitFinished,
    )
}
