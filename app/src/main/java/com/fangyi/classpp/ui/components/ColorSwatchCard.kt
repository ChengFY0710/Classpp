package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
/** 行内相邻球的最小间距：按它反推每行球数，整行实际间距均分后只多不少 */
private val BallGapMin = 6.dp
private val RowGap = 6.dp

/**
 * 颜色选择卡片：白卡片内色球逐行均布——每行球数随卡片宽度动态调整
 * （按「相邻球间距不小于 [BallGapMin]」反推行容量，屏窄每行少、屏宽每行多）。
 * 整行铺满：剩余宽度只均分给球与球之间（SpaceBetween），首末球贴齐卡片左右
 * 16dp 内距；末行不满不拉满，靠左按整行的实际球距排（行距上下一致）。
 * 色球固定尺寸、不参与权重，保证不被挤压、完整展示。
 * 选中态 = 外圈蓝环 + 白缝 + 色球。
 *
 * 选中动效：蓝环随选中渐隐渐现（Motion.FastMillis 先快后慢，与全局动效同一节奏）；
 * 色球在选中/未选中两态下是同一布局（恒 [BallSize] 居中），环常驻叠加、只动透明度，
 * 切换时色球位置零位移。
 */
@Composable
fun ColorSwatchCard(
    colors: List<Color>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetCard(
        modifier = modifier.fillMaxWidth(),
        // 内距走 SheetCard 默认值（左右 16、上下 14），与其它浮层卡片一致
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // 每行球数：n 球占宽 n×格 + (n−1)×最小距 ≤ 可用宽
            // ⟺ n ≤ (宽 + 最小距)/(格 + 最小距)，取其下取整
            val perRow = ((maxWidth + BallGapMin) / (CellSize + BallGapMin))
                .toInt()
                .coerceAtLeast(1)
            // 整行的实际球距：行容量定下后，剩余宽度均分给球与球之间；
            // 末行靠左排也用它，末行与整行的球距一致
            val rowGap = if (perRow > 1) (maxWidth - CellSize * perRow) / (perRow - 1).toFloat() else 0.dp
            Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                colors.indices.chunked(perRow).forEach { rowIndices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (rowIndices.size == perRow) {
                            Arrangement.SpaceBetween
                        } else {
                            Arrangement.spacedBy(rowGap)
                        },
                    ) {
                        rowIndices.forEach { index ->
                            val color = colors[index]
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
        }
    }
}
