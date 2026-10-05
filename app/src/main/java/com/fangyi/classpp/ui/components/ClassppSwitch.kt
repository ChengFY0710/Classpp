package com.fangyi.classpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.classppColors

/** 轨道尺寸对齐 M3 Switch 默认：52 x 32 胶囊、滑块 24、四周内边距 4（滑动行程 20） */
private val TrackWidth = 52.dp
private val TrackHeight = 32.dp
private val ThumbSize = 24.dp
private val ThumbInset = 4.dp

/**
 * 开关按钮：胶囊轨道 + 恒定大小的白色圆点滑块。
 *
 * 颜色规格：激活 = [MaterialTheme.colorScheme.primary] 轨道，未激活 = [MaterialTheme.classppColors.negative]
 * 置灰轨道（浅 #CBCBCB / 深 #5C6269），圆点无论状态与深浅模式都保持 White。
 *
 * 形状与全局胶囊按钮（顶栏按钮、确认框按钮等）同源：轨道走 [PillShape] 连续曲率胶囊，
 * 圆角与直边平滑过渡；圆点为纯圆形（CircleShape）。
 *
 * 动效（与 RowChoiceCard 等同一节奏，Motion.FastMillis 先快后慢）：圆点平移滑动、轨道颜色渐变，
 * 按压涟漪被胶囊圆角裁剪。[onCheckedChange] 为 null 时不可交互（语义与 M3 Switch 一致）。
 */
@Composable
fun ClassppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.classppColors.negative,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "classppSwitchTrack",
    )
    val thumbOffset: Dp by animateDpAsState(
        targetValue = if (checked) TrackWidth - ThumbSize - ThumbInset * 2 else 0.dp,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "classppSwitchThumb",
    )
    Box(
        modifier = modifier
            .size(width = TrackWidth, height = TrackHeight)
            .clip(PillShape)
            .background(trackColor)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 圆点始终 White：贴左缘起滑，offset 只负责水平行程，垂直方向由父容器居中
        Box(
            modifier = Modifier
                .padding(start = ThumbInset)
                .offset(x = thumbOffset)
                .size(ThumbSize)
                .background(Color.White, CircleShape),
        )
    }
}
