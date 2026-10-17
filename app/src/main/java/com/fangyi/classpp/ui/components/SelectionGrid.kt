package com.fangyi.classpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.WeekCellShape
import kotlin.math.ceil

/** 每行最少格数；均分后单格宽于上限就加列（末行补空位，保证每格同宽同高） */
private const val CellsPerRowMin = 6
private val GridCellGap = 6.dp
private val GridCellMaxWidth = 60.dp

/**
 * 选择方格：白底方格阵列的通用多选控件（课程编辑的周数方格同款，重复设置复用）。
 * 格子以 [values] 的值为身份键——[selected] 中的值蓝底白字（onPrimary），点一下选中 /
 * 再点取消；[blocked] 里的值灰显不可点（如已被同格其它交替课占用的周）。
 *
 * [labels] 与 [values] 一一对应，为各格展示文字（周数方格即数字本身，重复设置的星期
 * 是「一/二/…」、月份是「N月」）。布局：每行最少 [CellsPerRowMin] 格，按容器宽均分、
 * 单格宽超 [GridCellMaxWidth] 就加列，末行补空位保证每格同宽同高。选中/取消走 150ms
 * 颜色渐变，背景与字色同步过渡（[Motion.FastMillis] 先快后慢）；按压反馈铺满格子
 * （pressClickable 取代涟漪）。
 */
@Composable
fun SelectionGrid(
    values: List<Int>,
    labels: List<String>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    blocked: Set<Int> = emptySet(),
) {
    BoxWithConstraints(modifier = modifier) {
        // 列数取"均分后单格不超上限"的最小值，但不低于下限：
        // (宽 − (n−1)×格距)/n ≤ 上限 ⟺ n ≥ (宽 + 格距)/(上限 + 格距)
        val columns = maxOf(
            CellsPerRowMin,
            ceil((maxWidth + GridCellGap) / (GridCellMaxWidth + GridCellGap)).toInt(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(GridCellGap)) {
            values.indices.chunked(columns).forEach { rowCells ->
                Row(horizontalArrangement = Arrangement.spacedBy(GridCellGap)) {
                    rowCells.forEach { index ->
                        val value = values[index]
                        val isSelected = value in selected
                        val isBlocked = value in blocked
                        // 背景/字色各走一条渐变：点选渐变变蓝、再点渐变回白，字色同步渐变
                        //（Motion.FastMillis 先快后慢，与 SheetTextField 描边动画同一节奏）
                        val cellColor by animateColorAsState(
                            targetValue = when {
                                isSelected -> MaterialTheme.colorScheme.primary
                                isBlocked -> MaterialTheme.colorScheme.onPrimaryContainer

                                else -> MaterialTheme.colorScheme.surface
                            },
                            animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
                            label = "selectionCellColor",
                        )
                        val labelColor by animateColorAsState(
                            targetValue = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                isBlocked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
                            label = "selectionCellLabel",
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                // 按压反馈（自带按压源 + 点击，涟漪由其取代）整体放在 clip 之前：
                                // blocked 走 enabled=false，点击与反馈一起失效
                                .pressClickable(WeekCellShape, enabled = !isBlocked, onClick = { onToggle(value) })
                                .clip(WeekCellShape)
                                .background(cellColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = labels[index],
                                fontSize = 18.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = labelColor,
                            )
                        }
                    }
                    repeat(columns - rowCells.size) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 多选方格快速预览（Android Studio 中直接查看） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun SelectionGridPreview() = ClassppTheme {
    SelectionGrid(
        values = (1..18).toList(),
        labels = (1..18).map { it.toString() },
        selected = setOf(1, 3, 9),
        onToggle = {},
    )
}
