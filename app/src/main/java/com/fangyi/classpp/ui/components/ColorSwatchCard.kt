package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 色球直径（选中环在其外侧，格子统一 46dp 保证行列对齐）。 */
private val BallSize = 36.dp
private val CellSize = 46.dp
private val RingWidth = 2.5.dp
private val RingGap = 2.5.dp

/**
 * 颜色选择卡片：白卡片内色球横排，按屏幕宽度自动换行（FlowRow）——
 * 色球固定尺寸、不参与权重，保证不被挤压、完整展示。
 * 选中态 = 外圈蓝环 + 白缝 + 色球。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatchCard(
    colors: List<Color>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 12.dp,
        ),
    ) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            colors.forEachIndexed { index, color ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .size(CellSize)
                        .clip(CircleShape)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(CellSize)
                                .clip(CircleShape)
                                .border(RingWidth, MaterialTheme.colorScheme.primary, CircleShape)
                                .padding(RingWidth + RingGap),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(color),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(BallSize)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }
            }
        }
    }
}
