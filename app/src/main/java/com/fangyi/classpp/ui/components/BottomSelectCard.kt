package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.SettingsCardShape

/** 白卡距 [BottomSelectSheet] 内容槽左右缘的内距：卡不贴边，与选择卡的留白节奏一致 */
private val CardInnerEdgePadding: Dp = 12.dp

/**
 * 底部选择卡的内容白卡（[BottomSelectSheet] 系内容槽的卡壳）：settings 白卡语言
 * （[SettingsCardShape] 连续曲率圆角 + surface 底）铺进选择卡的内容槽，距内容槽
 * 左右缘 [CardInnerEdgePadding]——卡不贴边；上下面贴槽（高度节奏由内容自理）。
 *
 * 日历账户选择卡（CalendarAccountPickerSheet）等需要白卡承装行选择的变体用它当外壳：
 * 内容作为 [ColumnScope] 槽位纵向堆叠（同 [SelectCard] 的行语言，行行相接、溢出由
 * 卡壳圆角裁剪）；需要行间距的调用方在槽内自行用 Arrangement 拉开。
 */
@Composable
fun BottomSelectCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CardInnerEdgePadding),
        shape = SettingsCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}
