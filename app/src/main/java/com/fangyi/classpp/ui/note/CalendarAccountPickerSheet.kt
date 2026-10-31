package com.fangyi.classpp.ui.note

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.calendar.CalendarAccount
import com.fangyi.classpp.ui.components.BottomSelectCard
import com.fangyi.classpp.ui.components.BottomSelectSheet
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.PressShade
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme
import dev.chrisbanes.haze.HazeState

/**
 * 日历账户选择卡（「添加到日历」的写入目标选择）：[BottomSelectSheet] 变体——设备上有
 * 多个日历账户时弹出，列出账户供单选，确认后由宿主把待办写入所选日历；单账户时宿主
 * 直接写入、不挂载本卡。
 *
 * 内容是一张 [com.fangyi.classpp.ui.components.BottomSelectCard] 白卡内纵向无缝堆叠的
 * 账户行（视觉与 [com.fangyi.classpp.ui.components.SelectCard] 的选项行同源）：
 * 主文本为日历显示名（fieldLabel）、副文本为账户名（noteProperty，
 * 与显示名相同时省去不重复展示）；选中行文字 primary 色 + 行尾 ic_checkmark（20dp），
 * 选中过渡走 Motion.FastMillis 先快后慢——文字颜色渐变、勾随选中缩放出现/消失，
 * 勾贴行尾不推动文字。选中状态受控（[selectedId] 进、[onSelect] 出），组件不持有。
 *
 * 确认胶囊文案为「添加」（[onConfirm] 带回所选账户 id）；所选项不在当前账户列表里时
 * 置灰不可点（正常不会发生：宿主弹出前已把 id 对回列表，防御保留）。其余行为
 * （两段式关闭、遮罩、卡片磨砂 [hazeState]、阴影参数 [shadowAlpha]）同 [BottomSelectSheet]。
 */
@Composable
internal fun CalendarAccountPickerSheet(
    visible: Boolean,
    accounts: List<CalendarAccount>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissed: () -> Unit = {},
    shadowAlpha: Float = 0.3f,
    hazeState: HazeState? = null,
) {
    val confirmLabel = stringResource(R.string.calendar_picker_confirm)
    // 宿主弹出前已把 selectedId 对回当前列表；这里只防御「无效 id」不出现可点的确认
    val selectionValid = accounts.any { it.id == selectedId }

    BottomSelectSheet(
        title = stringResource(R.string.calendar_picker_title),
        confirmLabel = confirmLabel,
        visible = visible,
        onDismiss = onDismiss,
        onDismissed = onDismissed,
        confirmEnabled = selectionValid,
        shadowAlpha = shadowAlpha,
        hazeState = hazeState,
        modifier = modifier,
        onConfirm = { if (selectionValid) onConfirm(selectedId) },
    ) {
        BottomSelectCard {
            accounts.forEach { account ->
                CalendarAccountRow(
                    account = account,
                    selected = account.id == selectedId,
                    onClick = { onSelect(account.id) },
                )
            }
        }
    }
}

/** 账户行：显示名 + 账户名副文本占满、勾贴行尾（同 SelectCard 行的选中动效与按压反馈） */
@Composable
private fun CalendarAccountRow(
    account: CalendarAccount,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // 浅色模式下白底行加色白提亮无感（白色无法更亮），改用微微压暗表达按压；深色模式仍提亮
    val rowShade = if (MaterialTheme.isDarkTheme) PressShade.Brighten else PressShade.Darken
    val titleColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurface,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "calendarAccountTitle",
    )
    // 勾随选中缩放出现/消失；恒在组合内（勾贴行尾，占位不推动文字），退场动画才有得播
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "calendarAccountCheck",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressClickable(RectangleShape, shade = rowShade, onClick = onClick)
            .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.displayName,
                style = MaterialTheme.classppTextStyles.fieldLabel,
                color = titleColor,
            )
            // 账户名副文本：与显示名相同（多为本地日历）时不重复展示
            if (account.accountName != account.displayName) {
                Text(
                    text = account.accountName,
                    style = MaterialTheme.classppTextStyles.noteProperty,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_checkmark),
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = checkScale
                    scaleY = checkScale
                    alpha = checkScale
                },
        )
    }
}
