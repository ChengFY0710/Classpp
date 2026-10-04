package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.classppTextStyles

/** 单条信息：左侧 label + 右侧值（均走统一角色样式，见 classppTextStyles）。 */
data class SheetInfoEntry(
    val label: String,
    val value: String,
)

/**
 * 浮层「信息展示框」：一张白卡内展示多条只读信息，为课程详情浮层准备（暂无调用方）。
 *
 * 与 [SheetTextField] 同族：同样的 [SheetCardShape]、同样的白底与内距
 * （[SheetCardPadding]，水平 16 / 垂直 14，即文本距白底四边的边距）、
 * 单条信息时与 [SheetFieldHeight] 等高（60 = 上下内距 14×2 + 行最小高 32）。
 *
 * 每条信息一行：左 label + 右值（16sp，走统一角色样式）；值区宽度固定为 label 之外的剩余宽度，
 * 超宽自动换行（右对齐、两行左缘对齐），卡片随行数长高、上下边距不变，
 * label 始终垂直居中。纯展示，无点击/描边/聚焦态。
 */
@Composable
fun SheetInfoCard(
    entries: List<SheetInfoEntry>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SheetFieldHeight)
            .clip(SheetCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(SheetCardPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        entries.forEach { entry ->
            Row(
                // 行最小高 32：单条信息时 60 = 内距 14×2 + 32，卡片与输入行卡等高
                modifier = Modifier.heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.classppTextStyles.fieldLabel,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = entry.value,
                    style = MaterialTheme.classppTextStyles.fieldValue,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
