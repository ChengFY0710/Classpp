package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.RowShape
import com.fangyi.classpp.ui.theme.classppColors

/**
 * 行选择卡片：白卡片内一行均分的选项（如 全选 / 单周 / 双周）。
 *
 * 选中态 = 浅蓝胶囊 + 蓝色勾 + 蓝字；未选中 = 黑字。[selectedIndex] 为 null 表示
 * 「无匹配项不高亮」（如周数为自定义组合时）。
 */
@Composable
fun RowChoiceCard(
    options: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    SheetCard(modifier = modifier, contentPadding = PaddingValues(6.dp)) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RowShape)
                    .background(
                        if (selected) MaterialTheme.classppColors.primaryContainerNontrans
                        else Color.Transparent,
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_checkmark),
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = if (selected) colors.primary else colors.onSurface,
                    )
                }
            }
        }
    }
}
