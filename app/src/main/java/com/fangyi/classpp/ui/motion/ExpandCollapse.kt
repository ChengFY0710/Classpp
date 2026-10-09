package com.fangyi.classpp.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 内容区展开/收起动画（可复用）：[content] 的高度在 [Motion.ExpandMillis] 内展开/收起，
 * 供「头部行可点、下方内容折叠」类组件（标签选择卡课程区等）复用。
 *
 * 两条关键机制（官方 expandVertically/AnimatedVisibility 的测量语义）：
 * - **兄弟组件实时让位**：动画期间节点向父布局上报的是「动画中的高度」，父布局每帧
 *   重新摆放兄弟项——不是播完才跳，连续快速翻转也会平滑掉头（AnimatedVisibility
 *   原生支持中途反向）。
 * - **子内容不重排**：内容每帧用动画之外的原始约束测量，按完整自然尺寸排好版后仅
 *   裁切显示（窗帘式揭示）——FlowRow 等换行布局在整个动画过程中排版稳定，
 *   动画只动外框、不逐帧挤压重排。
 *
 * 规格：进场 expand + fade 同走 [Motion.ExpandMillis] + [Motion.Decelerate]，
 * 退场 shrink + fade 同走 [Motion.ExpandMillis] + [Motion.Accelerate]（同族曲线对）；
 * 从上缘拉开（expandFrom = Top），与卡片内自上而下的展开方向一致。
 *
 * [content] 拿到的是 [AnimatedVisibilityScope]，需要错峰/联动的子项可用
 * `animateEnterExit` 跟随展开进度。多个子项直接并列即可——AnimatedVisibility 的
 * 测量策略把直接子项全部叠放在同一原点（Box 式 place(0,0)），本组件内部包了一层
 * Column 保证内容按纵向依次排列。
 *
 * @param expanded 展开态；翻转即启动动画，动画中途再翻转则从当前值反向
 * @param content 折叠内容，收起时被裁切并最终退出组合（不接收命中测试）
 */
@Composable
fun Expandable(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(
            animationSpec = tween(Motion.ExpandMillis, easing = Motion.Decelerate),
            expandFrom = Alignment.Top,
        ) + fadeIn(tween(Motion.ExpandMillis, easing = Motion.Decelerate)),
        exit = shrinkVertically(
            animationSpec = tween(Motion.ExpandMillis, easing = Motion.Accelerate),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(tween(Motion.ExpandMillis, easing = Motion.Accelerate)),
    ) {
        Column { content() }
    }
}
