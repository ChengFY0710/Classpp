package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.NoteCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.kyant.shapes.RoundedRectangle

private val PropertySpacing = 12.dp
private val TagIconSize = 17.dp
private val TimeIconSize = 18.dp
private val FlagIconSize = 25.dp

/**
 * 时间/截止属性项：18dp 图标 + 文本（同色），图标与文字相距 4dp（同 TagChip）。
 * 待办时间固定 secondaryText（ic_clock）；截止时间按剩余天数取紧急色（ic_clock_alarm），
 * 图标与文字颜色始终一致。
 */
@Composable
internal fun TimeProperty(
    @DrawableRes icon: Int,
    text: String,
    tint: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TimeIconSize),
        )
        Text(
            text = text,
            style = MaterialTheme.classppTextStyles.noteProperty.copy(color = tint),
        )
    }
}

/**
 * 单枚标签胶囊：ic_tag 图标 + 标签名（均取 primary），图标与文字相距 4dp。
 * 卡片属性行（[NoteCard]）与待办详情浮层共用同一形态；外层间距由调用方排布。
 */
@Composable
internal fun TagChip(tag: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_tag),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(TagIconSize),
        )
        Text(
            text = tag,
            style = MaterialTheme.classppTextStyles.noteProperty
                .copy(color = MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * 待办卡片：标题 + 属性行（待办时间、截止时间、标签，自动换行）+ 右侧紧急旗帜与勾选框。
 *
 * - 颜色全部取自主题：卡片底 surface，标题 fieldLabel（onSurface），标签着 primary，
 *   旗帜按 [urgency] 档位取色（[TodoUrgency.flagColor]，同 UrgentFlagCard），勾选框 primary；
 * - [time] 待办时间（已格式化文本，如 "12:30"、"明天 14:00-16:00"）：ic_clock 图标
 *   18dp + 文本，图标与文字均 secondaryText；
 * - [deadline] 截止时间（已格式化文本，如 "今天 8:00"、"3/12 6:00"）：ic_clock_alarm
 *   图标 18dp + 文本，图标与文字同取 [deadlineColor]（调用方按剩余天数映射紧急色）；
 * - 两个时间项缺席时均不占位，属性行整体走 FlowRow，放不下自动换行；
 * - [urgency] 紧急档位：非 None 才画旗，None 不画（列表默认形态无旗）；
 * - [completed] 完成态：标题划线置灰（时间/标签/旗帜不变），勾选框选中；
 * - [overdue] 逾期态：标题标红（theme 的 error，时间/标签/旗帜不变）；完成态优先于逾期态；
 * - 时间、截止与标签全缺席时卡片仍有 60dp 最小高度，不塌成纯文字高度。
 */
@Composable
fun NoteCard(
    title: String,
    modifier: Modifier = Modifier,
    time: String? = null,
    deadline: String? = null,
    deadlineColor: Color = MaterialTheme.colorScheme.primary,
    tags: List<String> = emptyList(),
    urgency: TodoUrgency = TodoUrgency.None,
    completed: Boolean = false,
    overdue: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val titleStyle = when {
        completed -> MaterialTheme.classppTextStyles.fieldLabel.copy(
            color = MaterialTheme.classppColors.secondaryText,
            textDecoration = TextDecoration.LineThrough,
        )
        overdue -> MaterialTheme.classppTextStyles.fieldLabel.copy(
            color = MaterialTheme.colorScheme.error,
        )
        else -> MaterialTheme.classppTextStyles.fieldLabel
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SheetFieldHeight)
            // 按压反馈同 SheetCard「可点才挂」：位置在 clip 之前（缩放作用于整卡、不被圆角
            // 裁掉），提亮范围与 NoteCardShape 对齐；null = 静态卡，涟漪由按压反馈取代
            .then(
                if (onClick != null) Modifier.pressClickable(NoteCardShape, onClick = onClick) else Modifier,
            )
            .clip(NoteCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = titleStyle,
            )
            if (time != null || deadline != null || tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PropertySpacing),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (time != null) {
                        TimeProperty(
                            icon = R.drawable.ic_clock,
                            text = time,
                            tint = MaterialTheme.classppColors.secondaryText,
                        )
                    }
                    if (deadline != null) {
                        TimeProperty(
                            icon = R.drawable.ic_clock_alarm,
                            text = deadline,
                            tint = deadlineColor,
                        )
                    }
                    tags.forEach { tag ->
                        TagChip(tag)
                    }
                }
            }
        }
        if (urgency != TodoUrgency.None) {
            Icon(
                painter = painterResource(R.drawable.ic_flag),
                contentDescription = null,
                tint = urgency.flagColor(),
                modifier = Modifier.size(FlagIconSize),
            )
            Spacer(Modifier.width(16.dp))
        }
        // M3 Checkbox 占位（后续按设计稿自绘）：颜色先取主题 primary——未选 = 蓝描边框，已选，蓝底白勾。
        // 按压源由 pressFeedback 与 toggleable 共用（indication 置 null，涟漪由按压反馈取代）
        val checkboxPress = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                // 按压反馈在 clip 之前：缩放作用于整个触控区，提亮范围与勾选框圆角对齐
                .pressFeedback(checkboxPress, RoundedRectangle(6.dp))
                .clip(RoundedRectangle(6.dp))
                .toggleable(
                    value = completed,
                    interactionSource = checkboxPress,
                    indication = null,
                    onValueChange = { onCheckedChange?.invoke(it) }, // 安全调用
                    role = Role.Checkbox,
                )
                .height(36.dp)  //定义checkBox热区高度
                .width(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Checkbox(
                checked = completed,
                onCheckedChange = null, // 事件交给外层，这里固定null
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.primary,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }


    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 900, name = "待办卡片全部形态")
@Composable
private fun NoteCardVariantsPreview() {
    ClassppTheme {
        NoteCardShowcase()
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 900, name = "待办卡片全部形态（深色）")
@Composable
private fun NoteCardVariantsDarkPreview() {
    ClassppTheme(darkTheme = true) {
        NoteCardShowcase()
    }
}

/** 预览内容：设计稿的全部形态（基础、换行、标签、多标签、红旗、完成、补位、最小高、日期形态与截止各色） */
@Composable
private fun NoteCardShowcase() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 15.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NoteCard(title = "户口乔迁材料报送", time = "12:30", onCheckedChange = {})
        NoteCard(
            title = "待办标题待办标题待办标题待办标题待办标题待办标题待办标题待办标题",
            time = "12:30",
            onCheckedChange = {},
        )
        NoteCard(title = "户口乔迁材料报送", time = "12:30", tags = listOf("待办标签"), onCheckedChange = {})
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            tags = List(5) { "待办标签" },
            onCheckedChange = {},
        )
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            tags = listOf("待办标签"),
            urgency = TodoUrgency.High,
            onCheckedChange = {},
        )
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            tags = listOf("待办标签"),
            urgency = TodoUrgency.Critical,
            completed = true,
            onCheckedChange = {},
        )
        NoteCard(title = "户口乔迁材料报送", tags = listOf("待办标签"), urgency = TodoUrgency.Medium, onCheckedChange = {})
        NoteCard(title = "户口乔迁材料报送", urgency = TodoUrgency.Low, onCheckedChange = {})
        NoteCard(title = "户口乔迁材料报送", time = "6/18 14:30", onCheckedChange = {})
        NoteCard(title = "户口乔迁材料报送", time = "6/18 14:30", overdue = true, onCheckedChange = {})
        // 待办时间 + 截止时间同排：截止按剩余天数取色（>2 天 primary）
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            deadline = "2027/3/12 6:00",
            deadlineColor = MaterialTheme.colorScheme.primary,
            onCheckedChange = {},
        )
        NoteCard(title = "户口乔迁材料报送", time = "12:30", deadline = "3/12 6:00", onCheckedChange = {})
        NoteCard(
            title = "户口乔迁材料报送",
            deadline = "后天 8:00",
            deadlineColor = MaterialTheme.classppColors.urgentYellow,
            onCheckedChange = {},
        )
        NoteCard(
            title = "户口乔迁材料报送",
            deadline = "明天 8:00",
            deadlineColor = MaterialTheme.classppColors.urgentOrange,
            onCheckedChange = {},
        )
        NoteCard(
            title = "户口乔迁材料报送",
            deadline = "今天 8:00",
            deadlineColor = MaterialTheme.colorScheme.error,
            onCheckedChange = {},
        )
        NoteCard(
            title = "户口乔迁材料报送",
            deadline = "昨天 8:00",
            deadlineColor = MaterialTheme.colorScheme.error,
            overdue = true,
            onCheckedChange = {},
        )
        NoteCard(
            title = "户口乔迁材料报送",
            deadline = "前天 8:00",
            deadlineColor = MaterialTheme.colorScheme.error,
            overdue = true,
            onCheckedChange = {},
        )
    }
}
