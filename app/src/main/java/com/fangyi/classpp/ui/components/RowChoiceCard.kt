package com.fangyi.classpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.RowShape
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 行选择卡片：白卡片内一行均分的选项（如 全选 / 单周 / 双周）。
 *
 * 选中态 = 浅蓝胶囊 + 蓝色勾 + 蓝字；未选中 = 黑字。[selectedIndex] 为 null 表示
 * 「无匹配项不高亮」（如周数为自定义组合时）。
 *
 * [expandContent] 可选：画在选项行下方的展开内容（如「时段」选中后的
 * [TimeRangeSlider]），内距由内容自理；显示/收起由调用方按选中态决定（传 null 即
 * 收起），高度动画复用 motion 层 [Expandable]——展开/收起时行块实时改变上报高度，
 * 卡片下方的兄弟内容逐帧让位。收起当帧插槽已被调用方置空，组件记住上一份内容把
 * 画面撑到退场动画缩完（AnimatedVisibility 结束即把内容移出组合，快照随之失效）。
 *
 * 选中动效（所有过渡同走 Motion.FastMillis 先快后慢，与 SheetTextField 描边、周数方格渐变同一节奏）：
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
    expandContent: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    // 取消选中（null）时胶囊停在最后位置原地淡出；选中则滑向新索引
    var lastSelected by remember { mutableStateOf(selectedIndex ?: 0) }
    if (selectedIndex != null) lastSelected = selectedIndex
    val pillOffset by animateFloatAsState(
        targetValue = lastSelected.toFloat(),
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "rowChoicePillOffset",
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (selectedIndex != null) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "rowChoicePillAlpha",
    )

    // 收起动画当帧 expandContent 已被调用方置空（传 null）：记住上一份内容把滑块
    // 撑到行块平滑缩完，避免「内容瞬间消失、空白条再慢慢收」的断裂感
    var lastExpandContent by remember { mutableStateOf(expandContent) }
    if (expandContent != null) lastExpandContent = expandContent

    SheetCard(modifier = modifier, contentPadding = PaddingValues(6.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth()) {
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
                            animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
                            label = "rowChoiceIconScale",
                        )
                        val iconSlot by animateDpAsState(
                            targetValue = if (selected) 24.dp else 0.dp,
                            animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
                            label = "rowChoiceIconSlot",
                        )
                        val textColor by animateColorAsState(
                            targetValue = if (selected) colors.primary else colors.onSurface,
                            animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
                            label = "rowChoiceText",
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                // 按压反馈在 clip 之前：缩放作用于整个选项格，提亮范围与 RowShape 对齐
                                .pressClickable(RowShape) { onSelect(index) }
                                .clip(RowShape)
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
                                    style = MaterialTheme.classppTextStyles.fieldLabel,
                                    color = textColor,
                                )
                            }
                        }
                    }
                }
            }
            Expandable(expanded = expandContent != null) {
                lastExpandContent?.invoke()
            }
        }
    }
}
