package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.CardSectionTitleSpacing
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.settingsRowMetrics
import com.kyant.shapes.RoundedRectangle

private val SectionButtonSize = 32.dp

/** 编辑态「全选/取消全选」文字按钮的按压反馈形状（NoteCard 撤回按钮同款小圆角） */
private val SelectAllButtonShape = RoundedRectangle(6.dp)

/** 文字按钮的水平内距：只补按压提亮范围，不计入标题行高度 */
private val SelectAllHorizontalPadding = 4.dp

/**
 * 可收起卡片分组的头：灰色小标题 + 右侧展开/收起按钮，标题样式与左缩进同 [CardSection]
 * （原 NoteCardSection 拆分后的「头」半边）。待办列表（TodoList）已重构为逐卡 LazyColumn
 * item——卡片进出场与折叠收合全部交给 Modifier.animateItem（规格见 Motion 的
 * TodoCardEnter/Exit/PlaceMillis），头不再持有内容槽与折叠状态：
 *
 * - 折叠态由宿主持有并经 [expanded] 下发，点按箭头回调 [onToggleClick]；chevron 用单个
 *   ic_chevron_down 随状态旋转 180°（展开朝上、收起朝下）——ic_chevron_up/down 两个
 *   图标本就互为 180° 旋转，旋转切换比换图无跳变（Motion.FastMillis + Standard）；
 * - 标题行下方永久保留 [CardSectionTitleSpacing] 留白：列表里它是「标题 ↔ 首卡」的间距，
 *   折叠态（宿主不再下发卡片项）即头的收尾留白，两种状态纵向节奏一致；
 * - [editMode] 编辑态（待办编辑页）：展开箭头替换为「全选/取消全选」文字按钮——primary 色、
 *   sectionTitle 同字号，按钮盒钉在与箭头盒相同的 32dp（标题行高两种形态一致，进编辑
 *   不塌行高），宽度向左占标题与按钮之间的空白；点按回调 [onSelectAllToggle]，按钮
 *   文案由 [allSelected] 驱动（组内卡片全选中显「取消全选」，否则显「全选」）。
 */
@Composable
fun NoteCardSectionHeader(
    title: String,
    expanded: Boolean,
    onToggleClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 编辑态（待办编辑页）：箭头换「全选/取消全选」文字按钮
    editMode: Boolean = false,
    // 编辑态下组内卡片是否已全选中（驱动文字按钮文案）
    allSelected: Boolean = false,
    // 编辑态下文字按钮的回调；editMode 为 true 但回调缺席时按钮不渲染（保险分支）
    onSelectAllToggle: (() -> Unit)? = null,
) {
    val chevronRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(Motion.FastMillis, easing = Motion.Standard),
        label = "noteCardSectionChevron",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = CardSectionTitleSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.classppTextStyles.sectionTitle.settingsRowMetrics(),
        )
        if (editMode && onSelectAllToggle != null) {
            // 编辑态：展开箭头换「全选/取消全选」文字按钮——高度钉在与箭头盒相同的 32dp
            //（标题行靠 32dp 盒撑高，按钮若按文字行高收缩，进编辑行高塌 8dp、其下卡片
            // 整段上跳），宽度按文字收缩、向左占 SpaceBetween 的空白；按压反馈形状用
            // 小圆角（CircleShape 对宽按钮提亮范围太扁）
            Box(
                modifier = Modifier
                    .height(SectionButtonSize)
                    .pressClickable(SelectAllButtonShape) { onSelectAllToggle() }
                    .clip(SelectAllButtonShape)
                    .padding(horizontal = SelectAllHorizontalPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (allSelected) R.string.todo_edit_deselect_all else R.string.todo_edit_select_all,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = ClassppTextTokens.sectionAction,
                )
            }
        } else {
            // 手写触控区而非 IconButton：M3 IconButton 强制 48dp 最小触控区，
            // 对 24dp 的 chevron 视觉留白过大；32dp 圆形区 + 圆形按压反馈紧凑且够点
            Box(
                modifier = Modifier
                    .size(SectionButtonSize)
                    // 按压反馈在 clip 之前：缩放不被圆形裁掉，提亮范围与 CircleShape 对齐
                    .pressClickable(CircleShape) { onToggleClick() }
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ChevronIcon(expanded = expanded, rotation = chevronRotation)
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

@Preview(showBackground = true, widthDp = 412, name = "分组头")
@Composable
private fun NoteCardSectionHeaderPreview() {
    ClassppTheme {
        NoteCardSectionHeaderShowcase()
    }
}

@Preview(showBackground = true, widthDp = 412, name = "分组头（深色）")
@Composable
private fun NoteCardSectionHeaderDarkPreview() {
    ClassppTheme(darkTheme = true) {
        NoteCardSectionHeaderShowcase()
    }
}

/**
 * 预览内容：展开组（含卡片）、默认收起组（无卡片项——列表里折叠即宿主停止下发卡片）、
 * 编辑态组（箭头换「全选/取消全选」文字按钮 + 选中形态卡片对照）。折叠态由宿主持有
 * （列表里是 TodoList 的折叠集），预览用本地状态演示。
 */
@Composable
private fun NoteCardSectionHeaderShowcase() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = PageHorizontalSpacing, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var expanded by rememberSaveable { mutableStateOf(true) }
        NoteCardSectionHeader(
            title = "今天",
            expanded = expanded,
            onToggleClick = { expanded = !expanded },
        )
        NoteCard(title = "户口乔迁材料报送", time = "12:30", onCheckedChange = {})
        NoteCard(title = "外联部开会", time = "20:30", onCheckedChange = {})
        // 默认收起：预览同样不渲染卡片项（列表里折叠 = 卡片项被宿主移除，animateItem 收合）
        var doneExpanded by rememberSaveable { mutableStateOf(false) }
        NoteCardSectionHeader(
            title = "已完成",
            expanded = doneExpanded,
            onToggleClick = { doneExpanded = !doneExpanded },
        )
        if (doneExpanded) {
            NoteCard(title = "英语六级扫码", completed = true, onCheckedChange = {})
        }
        // 编辑态对照：箭头换「全选/取消全选」文字按钮（编辑态强制展开，预览直出展开形态）
        NoteCardSectionHeader(
            title = "未完成",
            expanded = true,
            onToggleClick = {},
            editMode = true,
            allSelected = false,
            onSelectAllToggle = {},
        )
        NoteCard(title = "户口乔迁材料报送", time = "12:30", onCheckedChange = {}, selected = true)
        NoteCard(title = "外联部开会", time = "20:30", onCheckedChange = {}, selected = false)
    }
}
