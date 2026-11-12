package com.fangyi.classpp.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.MenuGravity
import com.fangyi.classpp.ui.components.MenuPlacement
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuPopup
import com.fangyi.classpp.ui.components.PopupMenuSection
import dev.chrisbanes.haze.HazeState

/** 菜单上缘压进卡片底部的量：菜单贴着卡片长出来，而不是悬空在下方 */
private val MenuOverlap = 12.dp

/** 菜单左缘相对卡片左缘的内缩（设计稿里菜单比卡片略靠右） */
private val MenuLeftInset = 8.dp

/**
 * 长按课程卡弹出的上下文菜单：「复制课程」（可选）+「调课 / 编辑调课」（可选）+
 * 「新建交替课程」。
 *
 * [onCopy] 传 null 时不显示复制项（单节 / 跨节课程都可复制，见 ScheduleScreen 的菜单挂载点）。
 * [onAdjust] 传 null 时不显示调课项（当前浏览周不是该课的上课周、或调课卡自身不长按出菜单）；
 * [adjustIsEditing] = 该课次已有调课，文案改成「编辑调课」。
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
    onNewAlternate: (() -> Unit)? = null,
    onAdjust: (() -> Unit)? = null,
    adjustIsEditing: Boolean = false,
    hazeState: HazeState? = null,
    onExitFinished: () -> Unit = {},
) {
    val items = buildList {
        if (onCopy != null) {
            add(
                PopupMenuItem(
                    label = stringResource(R.string.edit_copy_course),
                    icon = R.drawable.ic_copy,
                    onClick = onCopy,
                ),
            )
        }
        if (onAdjust != null) {
            add(
                PopupMenuItem(
                    label = stringResource(
                        if (adjustIsEditing) R.string.edit_edit_adjust else R.string.edit_adjust_course,
                    ),
                    icon = R.drawable.ic_calendar_arrow_repeat_all,
                    onClick = onAdjust,
                ),
            )
        }
        if (onNewAlternate != null) {
            add(
                PopupMenuItem(
                    label = stringResource(R.string.edit_new_alternate),
                    icon = R.drawable.ic_table_cell_center_arrow_repeat_all,
                    onClick = onNewAlternate,
                ),
            )
        }
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
        items = listOf(
            PopupMenuItem(
                label = stringResource(R.string.edit_paste_course),
                icon = R.drawable.ic_clipboard_paste,
                onClick = onPaste,
            ),
        ),
        hazeState = hazeState,
        onExitFinished = onExitFinished,
    )
}

/**
 * 上下文菜单的共享弹层（设计稿二的白色圆角卡）：[PopupMenuPopup] 共享宿主
 * （[PopupMenuCard] 白卡 + 36dp 柔影 + scale/内容淡入进出场）。
 *
 * 定位沿用 [ScheduleHeader] 周数弹窗那套：菜单贴在长按卡片的左下角（压进卡片 [MenuOverlap]、
 * 再向右内缩 [MenuLeftInset]），翻转、安全区钳位与生长锚点由 [PopupMenuPopup] 的
 * [MenuPlacement] 统一处理——下方放不下时自动翻到卡片上方，生长锚点按最终落位反推。
 * 生命周期走「锚点 + 可见性」两态：宿主收起只翻 [expanded]，锚点保留到退场播完
 * （[onExitFinished] 后再清）——退场（[com.fangyi.classpp.ui.motion.Motion.PopupExitMillis]，
 * 120ms）里菜单内容稳定、行点击动作仍可命中（复制 / 调课 / 新建与收起同帧执行，视觉移除
 * 交给退场动画；它们只开编辑器 / 写剪贴板、不同帧重写课表采样源。粘贴不同——同帧往网格
 * 加课，经 [com.fangyi.classpp.ui.components.PopupActionGate] 延后到退场播完，见 ScheduleScreen）。
 *
 * 行样式与其它菜单统一（PopupMenuCard 默认行）：onSurface 深色 + 左侧 20dp 勾选槽，
 * 整份菜单为单组无缝形态（不画项间分割线）。
 *
 * 锚点显式传入（[anchor]）：本弹层由挂载方（menuAnchor）渲染在任意位置，Popup 自身的
 * anchorBounds 不是长按卡片。
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
    PopupMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        sections = listOf(PopupMenuSection(items = items)),
        hazeState = hazeState,
        placement = MenuPlacement(
            gravity = MenuGravity.BottomStart,
            gap = 0.dp,
            overlap = MenuOverlap,
            inset = MenuLeftInset,
        ),
        anchor = anchor,
        onExitFinished = onExitFinished,
    )
}
