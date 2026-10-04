package com.fangyi.classpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.RowShape

/**
 * 行选择卡片：白卡片内一行均分的选项（如 全选 / 单周 / 双周）。
 *
 * 选中态 = 浅蓝胶囊 + 蓝色勾 + 蓝字；未选中 = 黑字。[selectedIndex] 为 null 表示
 * 「无匹配项不高亮」（如周数为自定义组合时）。
 *
 * 选中动效（所有过渡同走 150ms 先快后慢，与 SheetTextField 描边、周数方格渐变同一节奏）：
 * - 浅蓝胶囊是一整块滑动层：换选时从旧位**平移**到新位，取消选中时原地淡出；
 * - 对勾随选中缩放出现/消失，占位宽度同步伸缩、推动文字平移；
 * - 文字颜色随选中渐变。
 */
@Composable
fun RowChoiceCard(
    options: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // 取消选中（null）时胶囊停在最后位置原地淡出；选中则滑向新索引
    var lastSelected by remember { mutableStateOf(selectedIndex ?: 0) }
    if (selectedIndex != null) lastSelected = selectedIndex
    val pillOffset by animateFloatAsState(
        targetValue = lastSelected.toFloat(),
        animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
        label = "rowChoicePillOffset",
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (selectedIndex != null) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
        label = "rowChoicePillAlpha",
    )

    SheetCard(modifier = modifier, contentPadding = PaddingValues(6.dp)) {
        Box(modifier = Modifier.weight(1f)) {
            // 滑动蓝底：宽度恒为 1/选项数，按选中索引平移；透明度与位移是两条独立动画——
            // 换选 = 只平移不淡出，取消 = 原地淡出不平移
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = pillAlpha },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(1f / options.size)
                        .fillMaxHeight()
                        .graphicsLayer { translationX = pillOffset * size.width }
                        .background(MaterialTheme.colorScheme.primaryContainer, RowShape),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    // 对勾缩放出现/消失；占位宽同步伸缩（勾 18dp + 间距 6dp），文字位置随之平移
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
                        label = "rowChoiceIconScale",
                    )
                    val iconSlot by animateDpAsState(
                        targetValue = if (selected) 24.dp else 0.dp,
                        animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
                        label = "rowChoiceIconSlot",
                    )
                    val textColor by animateColorAsState(
                        targetValue = if (selected) colors.primary else colors.onSurface,
                        animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
                        label = "rowChoiceText",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RowShape)
                            .clickable { onSelect(index) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 勾的占位：宽 0→24dp 渐变推动文字平移；勾贴槽位左缘缩放，右侧留 6dp 间距
                            Box(
                                modifier = Modifier.width(iconSlot),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_checkmark),
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                            alpha = iconScale
                                        },
                                )
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = textColor,
                            )
                        }
                    }
                }
            }
        }
    }
}
