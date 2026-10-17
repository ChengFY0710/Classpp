package com.fangyi.classpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.PressShade
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme

/**
 * 选择卡：白卡（[SettingsCardShape] 连续曲率圆角 + surface 底）内纵向无缝堆叠的选项行，
 * 单选/多选由调用方经 [multiSelect] 控制，选中状态受控（[selectedIndices] 进、
 * [onSelectionChange] 出，组件不持有选中状态）。
 *
 * 交互：
 * - 单选（[multiSelect] = false，默认）：点击未选项回写 `setOf(index)`（替换旧选），
 *   点击已选项不变（单选语义不允许取消）；
 * - 多选（[multiSelect] = true）：点击任意项切换其选中/取消，回写切换后的新集合。
 *
 * 规格（对齐 [SettingsCard]）：行内距 16/14 画在点击区内侧（按压反馈铺满整行，
 * pressClickable 取代涟漪；浅色压暗、深色提亮），文字走 fieldLabel（16sp SemiBold），
 * 行与行无缝堆叠、溢出由卡壳圆角裁剪。行高下限 [SheetFieldHeight] 判在**整卡**而非单行
 * （同 [SettingsCard] 的量法）：行自然高 = 上下 14 内距 + 文字高，多行卡各行维持自然高
 * （行距与 SettingsCard 多行卡一致）；整卡内容不足下限时（如只有一个选项）差额均摊给
 * 各行撑高，按压反馈仍铺满整卡、内容仍居中。
 *
 * 选中态：文字 primary 色 + 行尾 ic_checkmark（20dp，primary 着色，与设计稿一致），
 * 未选中行 = onSurface 黑字、尾部不画勾。选中过渡走 Motion.FastMillis 先快后慢
 * （与 [RowChoiceCard] 选中动效同一节奏）：文字颜色渐变，勾随选中缩放出现/消失。
 */
@Composable
fun SelectCard(
    options: List<String>,
    selectedIndices: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
    multiSelect: Boolean = false,
) {
    // 行高下限判在整卡而非单行（量法同 [SettingsCard]）：先按内容自然高度测各行，
    // 整卡不足 [SheetFieldHeight] 时把差额均摊给各行撑高；内容超出下限后各行保持自然高
    Layout(
        content = {
            options.forEachIndexed { index, label ->
                SelectCardRow(
                    label = label,
                    selected = index in selectedIndices,
                    onClick = {
                        val next = when {
                            // 多选：点击项在集合中切换；单选：已选项不变，未选项替换旧选
                            multiSelect ->
                                if (index in selectedIndices) selectedIndices - index
                                else selectedIndices + index
                            index in selectedIndices -> selectedIndices
                            else -> setOf(index)
                        }
                        if (next != selectedIndices) onSelectionChange(next)
                    },
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface),
    ) { measurables, constraints ->
        val minHeightPx = SheetFieldHeight.roundToPx()
        val width = constraints.maxWidth
        // 同一个 Measurable 一次布局只允许 measure() 一次：各行自然高度先用 intrinsic 查询
        // （不产生正式测量），再带着算好的下限一次性正式测量
        val naturals = measurables.map { it.minIntrinsicHeight(width) }
        val contentHeight = naturals.sum()
        // 空卡不兜底；已达下限或超出的卡片各行维持自然高度
        val cardHeight = if (naturals.isEmpty()) 0
            else contentHeight.coerceAtLeast(minHeightPx).coerceAtMost(constraints.maxHeight)
        // 整卡不足下限时把差额均摊给各行（余数按行序 +1px 补齐）；被撑高后内容仍垂直居中
        val extra = cardHeight - contentHeight
        val placeables = measurables.mapIndexed { index, measurable ->
            val share = if (extra > 0) {
                extra / measurables.size + if (index < extra % measurables.size) 1 else 0
            } else 0
            // 自然高可能超过父级给的最大高（如预览页面底部剩余空间不足），min 不得越过 max
            measurable.measure(
                constraints.copy(
                    minHeight = (naturals[index] + share).coerceAtMost(constraints.maxHeight),
                ),
            )
        }
        layout(width, cardHeight) {
            var y = 0
            placeables.forEach { placeable ->
                placeable.place(0, y)
                y += placeable.height
            }
        }
    }
}

/** 选项行：文字 weight(1f) 占满、勾贴行尾；行无自有 clip，矩形按压反馈原位接在行上，卡壳圆角兜住缩放溢出 */
@Composable
private fun SelectCardRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // 浅色模式下白底行加色白提亮无感（白色无法更亮），改用微微压暗表达按压；深色模式仍提亮
    val rowShade = if (MaterialTheme.isDarkTheme) PressShade.Brighten else PressShade.Darken
    val textColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurface,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "selectCardText",
    )
    // 勾随选中缩放出现/消失；恒在组合内（勾贴行尾，占位不推动文字），退场动画才有得播
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "selectCardCheck",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressClickable(RectangleShape, shade = rowShade, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.fieldLabel,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_checkmark),
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = checkScale
                    scaleY = checkScale
                    alpha = checkScale
                },
        )
    }
}

/** 单选/多选两态快速预览（Android Studio 中直接查看） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun SelectCardPreview() = ClassppTheme {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        var single by remember { mutableStateOf(setOf(5)) }
        SelectCard(
            options = listOf("无重复", "每天", "每周", "每月", "每年", "自定义"),
            selectedIndices = single,
            onSelectionChange = { single = it },
        )
        var multi by remember { mutableStateOf(setOf(0, 2)) }
        SelectCard(
            options = listOf("周一", "周三", "周五"),
            selectedIndices = multi,
            onSelectionChange = { multi = it },
            multiSelect = true,
        )
    }
}
