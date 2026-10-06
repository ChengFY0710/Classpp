package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 带图标设置卡条目：导航行（左侧图标 + label + 蓝色箭头），
 * 可带右侧 primary 色当前值与灰色描述文字（自动换行、行卡随之长高）。
 */
data class SettingsCardwithIconItem(
    @DrawableRes val icon: Int,
    val label: String,
    val description: String? = null,
    val value: String? = null,
    val onClick: () -> Unit,
)

/**
 * 带图标设置卡片：[SettingsCard] 导航行加行首图标的变体，卡体与整卡行高均摊逻辑一致——
 * 白底（surface）+ [SettingsCardShape] 连续曲率圆角，内含一至多行 [SettingsCardwithIconItem]。
 * [rowSpacing] = 0（默认）时行与行无缝堆叠，整行涟漪铺满行宽后被卡片圆角裁剪（对齐设计稿
 * 「点击涟漪」）；需要行间留白时传正值（间隙不可点，四周边距不受影响）。
 * [rowMinHeight] 为行高下限（默认 [SheetFieldHeight]）：多行内容卡要收紧行距时传小值，
 * 行高回落为自然高（上下内距 14×2 + 内容高），只影响传入该参数的卡片。
 * [contentVerticalPadding] 为卡内首行之前 / 末行之后的额外留白（默认 0）。
 *
 * 规格（对齐 [SettingsCard]）：
 * - 字体：label 走 fieldLabel（16sp SemiBold onSurface），描述走 fieldPlaceholder
 *   （13sp/20sp 行高 Medium secondaryText），行值走 fieldValue；
 * - 图标：24dp onSurface（随深浅色模式），与文字间距 12dp；
 * - 间距：行高下限 [rowMinHeight]（默认 60dp = [SheetFieldHeight]）判在**整卡**而非单行，
 *   整卡内容不足时差额均摊给各行撑高（单行卡与旧「行高 min 60」规格渲染一致）；行内
 *   左右 16，内距画在交互区**内侧**；
 * - 颜色只取四处主题色：surface（卡底）、onSurface（label/图标）、primary（箭头/行值，
 *   值经 fieldValue 角色）、classppColors.secondaryText（描述），深浅模式自动适配。
 */
@Composable
fun SettingsCardwithIcon(
    items: List<SettingsCardwithIconItem>,
    modifier: Modifier = Modifier,
    // 行与行之间的额外间距：默认 0 = 无缝堆叠（涟漪区域连贯），> 0 = 行间留白
    rowSpacing: Dp = 0.dp,
    // 行高下限：默认 60dp（对齐 SheetTextField）；多行卡收紧行距时传小值，
    // 行高回落为自然高（上下内距 14×2 + 内容），不传则其余卡片渲染不变
    rowMinHeight: Dp = SheetFieldHeight,
    // 卡内内容的上下内距：首行之前与末行之后各留一份（默认 0，卡外四周不受影响）
    contentVerticalPadding: Dp = 0.dp,
) {
    // 行高下限判在整卡而非单行：先按内容自然高度测各行，整卡不足 [rowMinHeight]
    // 时把差额均摊给各行撑高——行变高后涟漪仍铺满整卡、内容仍居中，单行卡与旧的
    // 「行高 min 60dp」规格渲染一致；内容超出下限后各行保持自然高度
    Layout(
        content = {
            items.forEach { item ->
                SettingsCardwithIconRow(item)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface),
    ) { measurables, constraints ->
        val spacingPx = rowSpacing.roundToPx()
        val minHeightPx = rowMinHeight.roundToPx()
        val insetPx = contentVerticalPadding.roundToPx()
        // 同一个 Measurable 一次布局只允许 measure() 一次：各行自然高度先用 intrinsic 查询
        // （不产生正式测量），再带着算好的下限一次性正式测量
        val width = constraints.maxWidth
        val naturals = measurables.map { it.minIntrinsicHeight(width) }
        val contentHeight = naturals.sum() + spacingPx * (naturals.size - 1).coerceAtLeast(0) +
            insetPx * 2
        // 空卡片不兜底；已达下限或超出的卡片各行维持自然高度
        val cardHeight = if (naturals.isEmpty()) 0 else
            contentHeight.coerceAtLeast(minHeightPx).coerceAtMost(constraints.maxHeight)
        // 整卡不足下限时把差额均摊给各行（余数按行序 +1px 补齐），行高 = max(自然高, 均摊份额)；
        // 行被撑高后涟漪仍铺满整卡、内容仍居中，单行卡与旧「行高 min 60dp」规格渲染一致
        val extra = cardHeight - contentHeight
        val placeables = measurables.mapIndexed { index, measurable ->
            val share = if (extra > 0) {
                extra / measurables.size + if (index < extra % measurables.size) 1 else 0
            } else 0
            // 自然高可能超过父级给的最大高（如预览页面底部剩余空间不足），min 不得越过 max
            measurable.measure(
                constraints.copy(minHeight = (naturals[index] + share).coerceAtMost(constraints.maxHeight)),
            )
        }
        layout(width, cardHeight) {
            var y = insetPx
            placeables.forEach { placeable ->
                placeable.place(0, y)
                y += placeable.height + spacingPx
            }
        }
    }
}

/**
 * 单行渲染：padding 在 clickable 内侧，涟漪铺满整行宽。行高随内容，
 * 不再自行兜底 60dp——下限由 [SettingsCardwithIcon] 判在整卡上。
 */
@Composable
private fun SettingsCardwithIconRow(item: SettingsCardwithIconItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(item.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        SettingsRowText(label = item.label, description = item.description, modifier = Modifier.weight(1f))
        if (item.value != null) {
            Text(
                text = item.value,
                style = MaterialTheme.classppTextStyles.fieldValue,
            )
            Spacer(Modifier.width(6.dp))
        }
        Chevron()
    }
}

/** label + 可选灰色描述：无描述时单行垂直居中，有描述时整块随行数长高、与尾部控件垂直居中 */
@Composable
private fun SettingsRowText(
    label: String,
    description: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.fieldLabel,
        )
        if (description != null) {
            Spacer(modifier = Modifier.size(3.dp))
            Text(
                text = description,
                // 描述可能多行换行：补 20sp 行高，多行时行间节奏与 16sp 字号匹配
                style = MaterialTheme.classppTextStyles.fieldPlaceholder.copy(lineHeight = 20.sp, fontSize = 13.sp),
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
    )
}
