package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 整宽白卡操作行（保存方案 / 保存为新方案等）：内容居中，主色图标（可选）+ 主色文字。
 * 复刻待办详情「添加步骤」的行卡风格；[enabled] = false 时文字与图标统一用 theme 的
 * negative 置灰色、无按压反馈（原 TimeSchemeSheet 私有组件，抽到公共组件供
 * 「课程时间方案」与「AI 配置方案」两个浮层共用）。
 */
@Composable
fun ActionRowCard(
    label: String,
    icon: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.classppColors.negative
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SheetFieldHeight)
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (enabled) Modifier.pressClickable(SettingsCardShape, onClick = onClick) else Modifier)
            .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.fieldValue,
            color = contentColor,
        )
    }
}
