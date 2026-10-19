package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import dev.chrisbanes.haze.HazeState

/**
 * 颜色选择弹卡（BottomSelectSheet 变体）：内容为 [ColorSwatchCard] 色球阵列——逐行均布、
 * 每行球数随卡宽自适应，选中 = 外圈蓝环渐现（布局与动效全在该组件内，本壳只管骨架）。
 * 「新建标签挑颜色」等固定色板的选择场景。
 *
 * [colors] 色板可自定义，默认只放主题 Primary 一颗球；[selectedIndex] 为打开时的高亮位
 * （-1 = 无高亮，如「尚未挑过色」）。点色球只改草稿，**确认胶囊才经 [onConfirm] 交回
 * 选中的索引**——草稿在弹卡挂载期记忆，移出组合（[onDismissed] 之后）重建时回到
 * [selectedIndex]。其余行为（两段式关闭、遮罩、返回键、卡片磨砂 [hazeState]、阴影参数
 * [shadowAlpha]）同 [BottomSelectSheet]。
 */
@Composable
fun BottomColorSelectCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // 色板：默认单 Primary（跟随主题深浅取值）；宿主可传任意色集（如课程 18 色）
    colors: List<Color> = listOf(MaterialTheme.colorScheme.primary),
    selectedIndex: Int = 0,
    title: String = stringResource(R.string.color_select_title),
    shadowAlpha: Float = 0.3f,
    // 卡片磨砂的采样源（透传 [BottomSelectSheet]）：宿主把浮层内容的 HazeState 交给
    // OverlaySheet 后必须把同一状态传进来（见 BottomTextFieldCard 同名参数的说明）
    hazeState: HazeState? = null,
    onConfirm: (index: Int) -> Unit,
    onDismissed: () -> Unit = {},
) {
    // 选中草稿：挂载期记忆（同滚轮卡的 WheelState），点色球即时高亮、确认才交回
    var draftIndex by remember { mutableIntStateOf(selectedIndex) }
    BottomSelectSheet(
        title = title,
        visible = visible,
        onDismiss = onDismiss,
        onDismissed = onDismissed,
        shadowAlpha = shadowAlpha,
        hazeState = hazeState,
        modifier = modifier,
        onConfirm = { onConfirm(draftIndex) },
    ) {
        // 内容槽横向 15dp：对齐 BottomTextFieldCard 的内容内距（色卡自带 16/14 内距，
        // 白卡衬在弹卡的磨砂玻璃上）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 15.dp, end = 15.dp),
        ) {
            ColorSwatchCard(
                colors = colors,
                selectedIndex = draftIndex,
                onSelect = { draftIndex = it },
            )
        }
    }
}
