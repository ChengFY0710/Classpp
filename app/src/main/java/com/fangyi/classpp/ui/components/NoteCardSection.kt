package com.fangyi.classpp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.CardSectionTitleSpacing
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
 * 可收起的卡片分组：仿 [CardSection]（灰色小标题 + 下方一组卡片），标题行右侧加
 * 展开/收起按钮，点按切换下方内容的显示。
 *
 * - 收起状态由组件内部持有（rememberSaveable）：旋转 / 切 tab / 进程重建都不丢；
 * - chevron 用单个 ic_chevron_down 随状态旋转 180°（展开朝上、收起朝下）——
 *   ic_chevron_up/down 两个图标本就互为 180° 旋转，旋转切换比换图无跳变；
 * - 内容进出场 expand/shrink + 淡入淡出：Motion.FastMillis 微交互节奏，
 *   进场 Decelerate、退场 Accelerate（与全局曲线族约定一致）；
 * - 标题样式、左缩进与 CardSection 完全一致；组内两条纵向间距同样独立：卡↔卡走 [spacing]，
 *   标题↔首卡走 [titleSpacing]（默认各取 ui.theme 的 CardSectionSpacing / CardSectionTitleSpacing）；
 * - [editMode] 编辑态（待办编辑页）：分组强制展开（[initiallyExpanded] 的折叠态被暂时
 *   覆盖，退出编辑自动恢复），展开箭头替换为「全选/取消全选」文字按钮——primary 色、
 *   sectionTitle 同字号，高度收敛在原 32dp 按钮盒内不撑起标题行，宽度向左占标题与
 *   按钮之间的空白；点按回调 [onSelectAllToggle]，按钮文案由 [allSelected] 驱动
 *   （组内卡片全选中显「取消全选」，否则显「全选」）。
 */
@Composable
fun NoteCardSection(
    title: String,
    modifier: Modifier = Modifier,
    // 卡间距与 CardSection 同源：ui.theme 的 CardSectionSpacing（10dp）
    spacing: Dp = CardSectionSpacing,
    // 标题↔首卡独立于卡间距，与 CardSection 同源：CardSectionTitleSpacing
    titleSpacing: Dp = CardSectionTitleSpacing,
    initiallyExpanded: Boolean = true,
    // 编辑态（待办编辑页）：强制展开 + 箭头换「全选/取消全选」文字按钮
    editMode: Boolean = false,
    // 编辑态下组内卡片是否已全选中（驱动文字按钮文案）
    allSelected: Boolean = false,
    // 编辑态下文字按钮的回调；editMode 为 true 但回调缺席时按钮不渲染（保险分支）
    onSelectAllToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val chevronRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(Motion.FastMillis, easing = Motion.Standard),
        label = "noteCardSectionChevron",
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.classppTextStyles.sectionTitle.settingsRowMetrics(),
            )
            if (editMode && onSelectAllToggle != null) {
                // 编辑态：展开箭头换「全选/取消全选」文字按钮——高度收敛在原 32dp 按钮盒内
                //（sectionTitle 行高 24sp < 32dp，不撑起标题行），宽度向左占 SpaceBetween
                // 的空白；按压反馈形状用小圆角（CircleShape 对宽按钮提亮范围太扁）
                Box(
                    modifier = Modifier
                        .heightIn(max = SectionButtonSize)
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
                        style = MaterialTheme.classppTextStyles.sectionTitle.settingsRowMetrics(),
                    )
                }
            } else {
                // 手写触控区而非 IconButton：M3 IconButton 强制 48dp 最小触控区，
                // 对 24dp 的 chevron 视觉留白过大；32dp 圆形区 + 圆形按压反馈紧凑且够点
                Box(
                    modifier = Modifier
                        .size(SectionButtonSize)
                        // 按压反馈在 clip 之前：缩放不被圆形裁掉，提亮范围与 CircleShape 对齐
                        .pressClickable(CircleShape) { expanded = !expanded }
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ChevronIcon(expanded = expanded, rotation = chevronRotation)
                }
            }
        }
        // 标题↔内容用显式留白，与 CardSection 同构；下面 content 的 spacedBy 只管卡↔卡
        Spacer(Modifier.height(titleSpacing))
        AnimatedVisibility(
            // 编辑态强制展开：声明式覆盖（不写内部状态），退出编辑自动恢复用户的折叠态
            visible = expanded || editMode,
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
                deadline = "明天 8:00",
                deadlineColor = MaterialTheme.classppColors.urgentOrange,
                tags = listOf("待办标签"),
                urgency = TodoUrgency.Critical,
                onCheckedChange = {},
            )
            NoteCard(title = "户口乔迁材料报送", time = "6/18 14:30", onCheckedChange = {})
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
        // 编辑态对照：强制展开（第二组折叠态被覆盖）+ 箭头换「全选/取消全选」文字按钮
        NoteCardSection(
            title = "未完成",
            editMode = true,
            allSelected = false,
            onSelectAllToggle = {},
        ) {
            NoteCard(title = "户口乔迁材料报送", time = "12:30", onCheckedChange = {}, selected = true)
            NoteCard(title = "外联部开会", time = "20:30", onCheckedChange = {}, selected = false)
        }
        NoteCardSection(
            title = "已完成",
            editMode = true,
            allSelected = true,
            onSelectAllToggle = {},
        ) {
            NoteCard(title = "英语六级扫码", completed = true, onCheckedChange = {}, selected = true)
        }
    }
}
