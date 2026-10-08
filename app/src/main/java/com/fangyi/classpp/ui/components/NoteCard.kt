package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.NoteCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.kyant.shapes.RoundedRectangle

private val PropertySpacing = 12.dp
private val TagIconSize = 17.dp
private val FlagIconSize = 25.dp

/**
 * 待办卡片：标题 + 属性行（时间、标签，自动换行）+ 右侧紧急旗帜与勾选框。
 *
 * - 颜色全部取自主题：卡片底 surface，标题 fieldLabel（onSurface），时间与标签文字
 *   noteProperty（secondaryText），标签着 primary，旗帜按 [urgency] 档位取色
 *   （[TodoUrgency.flagColor]，同 UrgentFlagCard），勾选框 primary；
 * - [time] 为已格式化的文本（"12:30"、"14:00-16:00"、"6月18日 14:30"），作为属性行
 *   第一项，缺席时由标签补位；属性行整体走 FlowRow，放不下自动换行；
 * - [urgency] 紧急档位：非 None 才画旗，None 不画（列表默认形态无旗）；
 * - [completed] 完成态：标题划线置灰（时间/标签/旗帜不变），勾选框选中；
 * - 时间与标签全缺席时卡片仍有 60dp 最小高度，不塌成纯文字高度。
 */
@Composable
fun NoteCard(
    title: String,
    modifier: Modifier = Modifier,
    time: String? = null,
    tags: List<String> = emptyList(),
    urgency: TodoUrgency = TodoUrgency.None,
    completed: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val titleStyle = if (completed) {
        MaterialTheme.classppTextStyles.fieldLabel.copy(
            color = MaterialTheme.classppColors.secondaryText,
            textDecoration = TextDecoration.LineThrough,
        )
    } else {
        MaterialTheme.classppTextStyles.fieldLabel
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SheetFieldHeight)
            .clip(NoteCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
            if (time != null || tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PropertySpacing),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (time != null) {
                        Text(
                            text = time,
                            style = MaterialTheme.classppTextStyles.noteProperty,
                        )
                    }
                    tags.forEach { tag ->
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
        // M3 Checkbox 占位（后续按设计稿自绘）：颜色先取主题 primary——未选 = 蓝描边框，
        // 已选 = 蓝底白勾。组件自带 48dp 最小触控区，布局高度按 48dp 计，会撑起卡片高度
        Row(
            modifier = Modifier
                .clip(RoundedRectangle(6.dp))
                .toggleable(
                    value = completed,
                    onValueChange = { onCheckedChange?.invoke(it) }, // 安全调用
                    role = Role.Checkbox
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

/** 预览内容：设计稿的全部 9 个形态（基础、换行、标签、多标签、红旗、完成、补位、最小高、全日期） */
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
        NoteCard(title = "户口乔迁材料报送", time = "6月18日 14:30", onCheckedChange = {})
    }
}
