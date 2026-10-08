package com.fangyi.classpp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.settingsRowMetrics

private val SectionButtonSize = 32.dp

/**
 * 可收起的卡片分组：仿 [CardSection]（灰色小标题 + 下方一组卡片），标题行右侧加
 * 展开/收起按钮，点按切换下方内容的显示。
 *
 * - 收起状态由组件内部持有（rememberSaveable）：旋转 / 切 tab / 进程重建都不丢；
 * - chevron 用单个 ic_chevron_down 随状态旋转 180°（展开朝上、收起朝下）——
 *   ic_chevron_up/down 两个图标本就互为 180° 旋转，旋转切换比换图无跳变；
 * - 内容进出场 expand/shrink + 淡入淡出：Motion.FastMillis 微交互节奏，
 *   进场 Decelerate、退场 Accelerate（与全局曲线族约定一致）；
 * - 标题样式、左缩进与 [spacing]（标题↔首卡、卡↔卡）与 CardSection 完全一致。
 */
@Composable
fun NoteCardSection(
    title: String,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val chevronRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(Motion.FastMillis, easing = Motion.Standard),
        label = "noteCardSectionChevron",
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.classppTextStyles.sectionTitle.settingsRowMetrics(),
            )
            // 手写触控区而非 IconButton：M3 IconButton 强制 48dp 最小触控区，
            // 对 24dp 的 chevron 视觉留白过大；32dp 圆形区 + 圆形 ripple 紧凑且够点
            Box(
                modifier = Modifier
                    .size(SectionButtonSize)
                    .clip(CircleShape)
                    .clickable(onClick = { expanded = !expanded }),
                contentAlignment = Alignment.Center,
            ) {
                ChevronIcon(expanded = expanded, rotation = chevronRotation)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(Motion.FastMillis, easing = Motion.Decelerate),
                expandFrom = Alignment.Top,
            ) + fadeIn(tween(Motion.FastMillis, easing = Motion.Decelerate)),
            exit = shrinkVertically(
                animationSpec = tween(Motion.FastMillis, easing = Motion.Accelerate),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(Motion.FastMillis, easing = Motion.Accelerate)),
        ) {
            // 卡间节奏与外层一致：content 里的每张卡片同 [spacing] 排布
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                content()
            }
        }
    }
}

/** 分组按钮的 chevron：ic_chevron_down 旋转 180° 即 ic_chevron_up，转角读在 layer 内不触发重组 */
@Composable
private fun ChevronIcon(expanded: Boolean, rotation: State<Float>) {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_down),
        contentDescription = stringResource(
            if (expanded) R.string.cd_section_collapse else R.string.cd_section_expand,
        ),
        tint = MaterialTheme.classppColors.secondaryText,
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer { rotationZ = rotation.value },
    )
}

@Preview(showBackground = true, widthDp = 412, name = "可收起分组")
@Composable
private fun NoteCardSectionPreview() {
    ClassppTheme {
        NoteCardSectionShowcase()
    }
}

@Preview(showBackground = true, widthDp = 412, name = "可收起分组（深色）")
@Composable
private fun NoteCardSectionDarkPreview() {
    ClassppTheme(darkTheme = true) {
        NoteCardSectionShowcase()
    }
}

/** 预览内容：展开组、默认收起组、空内容组 */
@Composable
private fun NoteCardSectionShowcase() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = PageHorizontalSpacing, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NoteCardSection(title = "今天") {
            NoteCard(title = "户口乔迁材料报送", time = "12:30", onCheckedChange = {})
            NoteCard(
                title = "户口乔迁材料报送",
                time = "12:30",
                tags = listOf("待办标签"),
                urgency = TodoUrgency.Critical,
                onCheckedChange = {},
            )
            NoteCard(title = "户口乔迁材料报送", time = "6月18日 14:30", onCheckedChange = {})
        }
        NoteCardSection(title = "已完成", initiallyExpanded = false) {
            NoteCard(
                title = "户口乔迁材料报送",
                time = "12:30",
                tags = listOf("待办标签"),
                completed = true,
                onCheckedChange = {},
            )
        }
        NoteCardSection(title = "本周") {
            NoteCard(title = "户口乔迁材料报送", tags = listOf("待办标签"), onCheckedChange = {})
        }
    }
}
