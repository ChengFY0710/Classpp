package com.fangyi.classpp.ui.note

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.ui.components.hazeSources
import com.fangyi.classpp.ui.components.hazeTopBarStyle
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.EditActionBarHeight
import com.fangyi.classpp.ui.theme.EditActionIconSize
import com.fangyi.classpp.ui.theme.EditActionInnerPadding
import com.fangyi.classpp.ui.theme.EditActionLabelGap
import com.fangyi.classpp.ui.theme.EditActionShape
import com.fangyi.classpp.ui.theme.EditBarTopPadding
import com.fangyi.classpp.ui.theme.classppColors
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * 编辑顶栏在内容坐标系里的占位高（状态栏之上另算）：顶部留白 [EditBarTopPadding] +
 * 动作行高 [EditActionBarHeight]。比常规顶栏（TodoTopBar 的 TopBarRowSpace）高出的
 * 部分由宿主随进/退编辑动画补差让位——公开 internal 同 TopBarRowSpace 约定，宿主
 * 直接引用、不做手工镜像常量。
 */
internal val EditBarRowSpace = EditBarTopPadding + EditActionBarHeight

/**
 * 待办编辑顶栏：删除 / 移动 / 同步 / 置顶或取消 / 确认，取代常规的 [TodoTopBar]。
 *
 * 五个动作块等距一行（各占一等份），每块为"图标在上、文字在下"的纵向组合，
 * 几何规格（图标、标签间距、内距、行高）与课表编辑顶栏（ScheduleEditBar）共用
 * ui/theme 的 Spacing.kt；14sp Medium 标签、1.10 按压放大、10dp 圆角按压形状也同款。
 * 背景与 [TodoTopBar] 同一套兜底色 + Haze 渐变模糊——
 * 列表滚动到顶栏之下时透出毛玻璃。
 *
 * 动作即时落库（见 TodoScreen 的 onDeleteSelected / onPinSelected / onUnpinSelected /
 * onMovePick），[onConfirm] 只负责退出编辑——不设「取消」，系统返回同语义。「移动」经
 * [onMove] 唤起选择分组浮层（复用 GroupPickSheet，点选即整批移动）；「同步」本次仅占位
 *（显示但点击无操作）。
 * [moveEnabled] 为 false（未选中任何待办）时「移动」置灰（icon 与文字转
 * [classppColors.negative]、点击无效）。置顶块随 [pinAction] 三态切换：只选中已置顶
 * 待办时显示「取消」+ ic_pin_off、点击 [onUnpin] 取消置顶；只选中未置顶或混合选择
 *（既有已置顶又有未置顶）时显示「置顶」+ ic_pin、点击 [onPin] 整批置顶；未选中任何
 * 待办时置灰不可点。
 */
@Composable
internal fun TodoEditBar(
    moveEnabled: Boolean,
    pinAction: PinAction,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 兜底色：与页面同色，画在模糊层之下（背后无内容时与常规顶栏逐帧一致）
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (hazeState != null) {
                    // TodoTopBar 同款渐变模糊：25dp + 下采样档（Balanced = Fixed(0.5)）
                    Modifier.hazeBlur(
                        input = hazeSources(hazeState),
                        style = hazeTopBarStyle(),
                        performanceMode = HazePerformanceMode.Balanced,
                    )
                } else {
                    Modifier
                },
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Spacer(modifier = Modifier.fillMaxWidth().height(EditBarTopPadding))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(EditActionBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditAction(
                text = stringResource(R.string.todo_edit_delete),
                iconRes = R.drawable.ic_delete,
                color = MaterialTheme.colorScheme.error,
                onClick = onDelete,
                modifier = Modifier
                    .weight(1f)
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )
            EditAction(
                // 移动：唤起选择分组浮层（GroupPickSheet），点选分组即整批移动——
                // 未选中任何待办时置灰不可点（与置顶同款置灰语义）
                text = stringResource(R.string.todo_edit_move),
                iconRes = R.drawable.ic_folder_arrow_right,
                color = if (moveEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.classppColors.negative,
                onClick = { if (moveEnabled) onMove() },
                modifier = Modifier
                    .weight(1f)
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )
            EditAction(
                // 同步：本次仅占位（点击无操作）
                text = stringResource(R.string.todo_edit_sync),
                iconRes = R.drawable.ic_calendar_regular,
                color = MaterialTheme.colorScheme.onSurface,
                onClick = {},
                modifier = Modifier
                    .weight(1f)
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )
            EditAction(
                // 置顶 / 取消置顶同一块：所选全部已置顶时切「取消」+ ic_pin_off，
                // 其余可点情形统一显示「置顶」（混合选择把未置顶的一并置顶）
                text = stringResource(
                    if (pinAction == PinAction.Unpin) R.string.todo_edit_unpin else R.string.todo_edit_pin,
                ),
                iconRes = if (pinAction == PinAction.Unpin) R.drawable.ic_pin_off else R.drawable.ic_pin,
                color = if (pinAction != PinAction.Disabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.classppColors.negative
                },
                onClick = {
                    when (pinAction) {
                        PinAction.Pin -> onPin()
                        PinAction.Unpin -> onUnpin()
                        PinAction.Disabled -> Unit
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )
            EditAction(
                text = stringResource(R.string.todo_edit_confirm),
                iconRes = R.drawable.ic_checkmark_circle_regular,
                color = MaterialTheme.colorScheme.primary,
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )
        }
    }
}

/** 编辑栏的纵向动作块：图标在上、14sp Medium 标签在下，两者水平居中对齐，整块可点（ScheduleEditBar 同款） */
@Composable
private fun EditAction(
    text: String,
    @DrawableRes iconRes: Int,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            // 按压反馈（自带按压源 + 点击，涟漪由其取代）放在 clip 之前：
            // 缩放/提亮作用于整块动作区，EditActionShape 圆角裁剪在其内侧；
            // 放大倍率与课表编辑顶栏同规格单独调大到 1.10（不随全局默认）
            .pressClickable(EditActionShape, scale = 1.10f, onClick = onClick)
            .clip(EditActionShape)
            .padding(horizontal = EditActionInnerPadding, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EditActionIcon(iconRes = iconRes, color = color)
        Spacer(Modifier.height(EditActionLabelGap))
        Text(
            text = text,
            color = color,
            style = ClassppTextTokens.actionLabel,
        )
    }
}

/** 动作图标：文字已经承载语义，故 contentDescription 交给整块的文本 */
@Composable
private fun EditActionIcon(@DrawableRes iconRes: Int, color: Color) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(EditActionIconSize),
    )
}

/**
 * 编辑顶栏置顶钮的判定（待办口径，与分组浮层的 [pinAction] 不同）：选中集为空
 * （或所选 id 已不在待办列表）→ [PinAction.Disabled]；所选待办全部已置顶 →
 * [PinAction.Unpin]（按钮显示「取消」+ ic_pin_off）；其余（全部未置顶，或已置顶与
 * 未置顶混合）→ [PinAction.Pin]——混合时点按把未置顶的一并置顶，已置顶的保持不动。
 */
internal fun todoPinAction(todos: List<Todo>, selected: Set<String>): PinAction {
    val chosen = todos.filter { it.id in selected }
    if (chosen.isEmpty()) return PinAction.Disabled
    return if (chosen.all { it.pinned }) PinAction.Unpin else PinAction.Pin
}

@Preview(showBackground = true, name = "待办编辑顶栏")
@Composable
private fun TodoEditBarPreview() {
    ClassppTheme {
        TodoEditBar(
            moveEnabled = true,
            pinAction = PinAction.Pin,
            onDelete = {},
            onMove = {},
            onPin = {},
            onUnpin = {},
            onConfirm = {},
        )
    }
}

@Preview(showBackground = true, name = "待办编辑顶栏（取消置顶）")
@Composable
private fun TodoEditBarUnpinPreview() {
    ClassppTheme {
        TodoEditBar(
            moveEnabled = true,
            pinAction = PinAction.Unpin,
            onDelete = {},
            onMove = {},
            onPin = {},
            onUnpin = {},
            onConfirm = {},
        )
    }
}

@Preview(showBackground = true, name = "待办编辑顶栏（置顶置灰）")
@Composable
private fun TodoEditBarPinDisabledPreview() {
    ClassppTheme {
        TodoEditBar(
            moveEnabled = true,
            pinAction = PinAction.Disabled,
            onDelete = {},
            onMove = {},
            onPin = {},
            onUnpin = {},
            onConfirm = {},
        )
    }
}

@Preview(showBackground = true, name = "待办编辑顶栏（移动置灰）")
@Composable
private fun TodoEditBarMoveDisabledPreview() {
    ClassppTheme {
        TodoEditBar(
            moveEnabled = false,
            pinAction = PinAction.Disabled,
            onDelete = {},
            onMove = {},
            onPin = {},
            onUnpin = {},
            onConfirm = {},
        )
    }
}
