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
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.settingsRowMetrics

/**
 * 卡片分组：灰色小标题（卡片外）+ 下方一组卡片，标题↔首卡、卡↔卡间距统一 [spacing]。
 * 自设置页的 SettingsSection 迁入更名，设置页与各浮层（课表设置、加课面板等）共用，
 * 保持同一套标题样式与间距节奏；组件只管纵向排版，横向边距与卡片本体由调用方提供
 * （典型搭配：卡内放 SettingsCard / PopupSelectCard 等）。
 *
 * [title] 可空：null（默认）不渲染标题，组件退化为纯分组容器——组内仍走 [spacing]
 * 节奏（无题卡组与带题分组的纵向节奏因此一致）。
 */
@Composable
fun CardSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    // 卡间距统一走 ui.theme 的 CardSectionSpacing（10dp），调用点无需覆盖
    spacing: Dp = CardSectionSpacing,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (title != null) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.classppTextStyles.sectionTitle.settingsRowMetrics(),
            )
        }
        content()
    }
}
