package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable

/** 色球直径（选中环在其外侧，格子统一 46dp 保证行列对齐）。 */
private val BallSize = 36.dp
private val CellSize = 46.dp
private val RingWidth = 2.5.dp
private val RingGap = 2.5.dp

/**
 * 颜色选择卡片：白卡片内色球横排，按屏幕宽度自动换行（FlowRow）——
 * 色球固定尺寸、不参与权重，保证不被挤压、完整展示。
 * 选中态 = 外圈蓝环 + 白缝 + 色球。
 *
 * 选中动效：蓝环随选中渐隐渐现（Motion.FastMillis 先快后慢，与全局动效同一节奏）；
 * 色球在选中/未选中两态下是同一布局（恒 [BallSize] 居中），环常驻叠加、只动透明度，
 * 切换时色球位置零位移。
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
                // 选中环透明度：选中渐现、取消渐隐（环始终在场，宽 2.5dp 恒定、只动 alpha）
                val ringAlpha by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
                    label = "colorSwatchRing",
                )
                Box(
                    modifier = Modifier
                        .size(CellSize)
                        // 按压反馈在 clip 之前：缩放不被圆形裁掉，提亮范围与 CircleShape 对齐
                        .pressClickable(CircleShape) { onSelect(index) }
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // 色球恒 36dp 居中：选中/未选中同一布局，切换时零位移
                    Box(
                        modifier = Modifier
                            .size(BallSize)
                            .clip(CircleShape)
                            .background(color),
                    )
                    // 外圈蓝环：46dp 描边内缩绘制，环内缘与球缘之间留 RingGap 白缝
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = ringAlpha }
                            .border(RingWidth, MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
    }
}
