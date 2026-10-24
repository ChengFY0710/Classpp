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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.hazeSources
import com.fangyi.classpp.ui.components.hazeTopBarStyle
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.EditActionShape
import com.fangyi.classpp.ui.theme.classppColors
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur

private val EditActionIconSize = 30.dp
private val EditActionLabelTextSize = 13.sp
private val EditActionLabelGap = 2.dp
private val EditActionInnerPadding = 8.dp

/** 动作行高度：纵向"图标 + 标签"块在其中垂直居中，上下留出呼吸空间（ScheduleEditBar 同款） */
private val EditActionBarHeight = 64.dp

/** 图标行与屏幕顶端的距离（ScheduleEditBar 同款 6dp；列表让位常量见 TodoScreen 的 EditBarRowSpace） */
private val EditBarTopPadding = 6.dp

/**
 * 待办编辑顶栏：删除 / 移动 / 同步 / 置顶 / 确认，取代常规的 [TodoTopBar]。
 *
 * 五个动作块等距一行（各占一等份），每块为"图标在上、文字在下"的纵向组合，
 * 规格（30dp 图标、13sp Medium 标签、1.10 按压放大、10dp 圆角按压形状）逐项对齐
 * 课表编辑顶栏（ScheduleEditBar）。背景与 [TodoTopBar] 同一套兜底色 + Haze 渐变模糊——
 * 列表滚动到顶栏之下时透出毛玻璃。
 *
 * 动作即时落库（见 TodoScreen 的 onDeleteSelected / onPinSelected / onMovePick），[onConfirm]
 * 只负责退出编辑——不设「取消」，系统返回同语义。「移动」经 [onMove] 唤起选择分组浮层
 *（复用 GroupPickSheet，点选即整批移动）；「同步」本次仅占位（显示但点击无操作）。
 * [moveEnabled] 为 false（未选中任何待办）时「移动」、[pinEnabled] 为 false（未选中任何
 * 待办，或所选待办已全部置顶）时「置顶」的 icon 与文字转 [classppColors.negative] 置灰、
 * 点击无效。
 */
@Composable
internal fun TodoEditBar(
    moveEnabled: Boolean,
    pinEnabled: Boolean,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onPin: () -> Unit,
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
                text = stringResource(R.string.todo_edit_pin),
                iconRes = R.drawable.ic_pin,
                color = if (pinEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.classppColors.negative,
                onClick = { if (pinEnabled) onPin() },
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

/** 编辑栏的纵向动作块：图标在上、13sp Medium 标签在下，两者水平居中对齐，整块可点（ScheduleEditBar 同款） */
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
            fontSize = EditActionLabelTextSize,
            fontWeight = FontWeight.Medium,
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

@Preview(showBackground = true, name = "待办编辑顶栏")
@Composable
private fun TodoEditBarPreview() {
    ClassppTheme {
        TodoEditBar(moveEnabled = true, pinEnabled = true, onDelete = {}, onMove = {}, onPin = {}, onConfirm = {})
    }
}

@Preview(showBackground = true, name = "待办编辑顶栏（置顶置灰）")
@Composable
private fun TodoEditBarPinDisabledPreview() {
    ClassppTheme {
        TodoEditBar(moveEnabled = true, pinEnabled = false, onDelete = {}, onMove = {}, onPin = {}, onConfirm = {})
    }
}

@Preview(showBackground = true, name = "待办编辑顶栏（移动置灰）")
@Composable
private fun TodoEditBarMoveDisabledPreview() {
    ClassppTheme {
        TodoEditBar(moveEnabled = false, pinEnabled = false, onDelete = {}, onMove = {}, onPin = {}, onConfirm = {})
    }
}
