package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.settingsRowMetrics

/**
 * 卡片分组：灰色小标题（卡片外）+ 下方一组卡片，标题↔首卡、卡↔卡间距统一 [spacing]。
 * 自设置页的 SettingsSection 迁入更名，设置页与各浮层（课表设置、加课面板等）共用，
 * 保持同一套标题样式与间距节奏；组件只管纵向排版，横向边距与卡片本体由调用方提供
 * （典型搭配：卡内放 SettingsCard / PopupSelectCard 等）。
 *
 * 标题走 sectionTitle 角色样式再叠 [settingsRowMetrics]（24sp 行高 / 0.5sp 字距），
 * 左缩进 4dp 与卡内 label 视觉对齐。
 */
@Composable
fun CardSection(
    title: String,
    modifier: Modifier = Modifier,
    // 覆盖示例：周数选择区沿用浮层的 SheetSectionSpacingBetween（12dp）
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.classppTextStyles.sectionTitle.settingsRowMetrics(),
        )
        content()
    }
}
