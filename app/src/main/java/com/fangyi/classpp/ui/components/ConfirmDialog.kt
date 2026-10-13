package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.classppColors

/** 确认框白卡内容：左对齐大标题 + 浅灰说明 + 底部两颗等宽胶囊，删除/放弃确认框共用 */
@Composable
fun ConfirmDialogCard(
    title: String,
    message: String,
    buttons: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 12.dp),
    ) {
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.classppColors.secondaryText,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp),
        )
        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = buttons,
        )
    }
}

/**
 * 确认框胶囊按钮：文字居中的全圆角胶囊（[PillShape]），高 52dp；
 * 大柔影配方同 [SheetPillButton]（高 elevation 撑模糊半径、低透明度压存在感），
 * 阴影色取主题 scrim；配色由调用方传 theme 槽位，本组件不含任何硬编码色值。
 * 按压反馈：整体放大 + 主体提亮（ui.motion 的 pressFeedback）。
 */
@Composable
fun DialogPillButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shadowColor = MaterialTheme.colorScheme.scrim
    // 按压态的唯一来源：pressFeedback 与 clickable 共用（涟漪由按压反馈取代，故 clickable 不再要 Indication）
    val pressInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(52.dp)
            // 按压反馈接在柔影图层之前：缩放连投影一起放大
            .pressFeedback(
                interactionSource = pressInteraction,
                shape = PillShape,
            )
            .graphicsLayer {
                shape = PillShape
                clip = true
                shadowElevation = 36.dp.toPx()
                spotShadowColor = shadowColor.copy(alpha = 0.3f)
            }
            .background(containerColor, PillShape)
            .clickable(
                interactionSource = pressInteraction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}
