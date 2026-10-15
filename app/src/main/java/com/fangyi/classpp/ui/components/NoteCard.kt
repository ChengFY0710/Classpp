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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.NoteCardShape
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.kyant.shapes.RoundedRectangle
import kotlin.math.abs

/**
 * 属性行横向间距：时间项之间、时间项与首个标签之间取 [PropertySpacing]（12dp）；
 * 标签之间取 [TagSpacing]（8dp，标签带底更耐紧）。FlowRow 的行距只能统一一个值，
 * 所以行距取 TagSpacing、时间项各带 [TimeItemEndPadding] 末位内距把间距补回 PropertySpacing。
 */
private val PropertySpacing = 12.dp
private val TagSpacing = 4.dp
private val TimeItemEndPadding = PropertySpacing - TagSpacing

private val TagIconSize = 15.dp
private val TimeIconSize = 18.dp
private val FlagIconSize = 25.dp

/** 标签胶囊底 **/
private val TagChipShape = PillShape
private val TagChipPadding = 8.dp

/** 标题与属性行的纵向间距：常规 [TitlePropertySpacing]；有标签时收紧到 [TitlePropertySpacingWithTags]（标签带底视觉体量更满，与标题贴紧） */
private val TitlePropertySpacing = 4.dp
private val TitlePropertySpacingWithTags = 1.dp

/**
 * 倒计时（[CountdownLabel]）：数字 [CountdownNumberFontSize] 半粗突出，单位与「逾期」前缀
 * [CountdownUnitFontSize] 收小；倒计时与右侧内容列相距 [CountdownEndSpacing]（沿用紧急旗后的节奏）。
 */
private val CountdownNumberFontSize = 25.sp
private val CountdownUnitFontSize = 12.sp
private val CountdownEndSpacing = 16.dp

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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
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
 * 单枚标签胶囊：primaryContainer 圆角底 + ic_tag 图标 + 标签名（图标与文字均取 primary），
 * 图标与文字相距 4dp。底只加水平内距（8dp）
 * 卡片属性行（[NoteCard]）与待办详情浮层共用同一形态；外层间距由调用方排布。
 */
@Composable
internal fun TagChip(tag: String) {
    Row(
        modifier = Modifier
            .clip(TagChipShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = TagChipPadding, end = TagChipPadding, top = 3.dp, bottom = 3.dp),
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
                .copy(color = MaterialTheme.colorScheme.primary, fontSize = 12.sp),
        )
    }
}

/**
 * 左侧倒计时：[days] ≥ 0 显「N天」（今天即 0 天）；< 0 显「逾期N天」（数字取绝对值）。
 * 数字 [CountdownNumberFontSize] 半粗、单位与「逾期」前缀 [CountdownUnitFontSize]，三段同一个
 * [tint]（与右侧截止时间同色，见 [NoteCard.countdownDays]）；混排字号拼进同一个 Text，
 * 基线天然对齐（锁 1 行，避免单位被挤到下一行）。
 */
@Composable
private fun CountdownLabel(
    days: Long,
    tint: Color,
) {
    val dayUnit = stringResource(R.string.todo_countdown_day_unit)
    val overduePrefix = if (days < 0) stringResource(R.string.todo_countdown_overdue) else ""
    val text = buildAnnotatedString {
        if (overduePrefix.isNotEmpty()) {
            withStyle(SpanStyle(fontSize = CountdownUnitFontSize)) { append(overduePrefix) }
        }
        withStyle(SpanStyle(fontSize = CountdownNumberFontSize, fontWeight = FontWeight.SemiBold)) {
            append(abs(days).toString())
        }
        withStyle(SpanStyle(fontSize = CountdownUnitFontSize)) { append(dayUnit) }
    }
    Text(
        text = text,
        style = MaterialTheme.classppTextStyles.noteProperty.copy(color = tint),
        maxLines = 1,
    )
}

/**
 * 待办卡片：左侧倒计时（可选）+ 标题 + 属性行（待办时间、截止时间、标签，自动换行）+ 右侧紧急旗帜与勾选框。
 *
 * - 颜色全部取自主题：卡片底 surface，标题 fieldLabel（onSurface），标签着 primary，
 *   旗帜按 [urgency] 档位取色（[TodoUrgency.flagColor]，同 UrgentFlagCard），勾选框 primary；
 * - [time] 待办时间（已格式化文本，如 "12:30"、"明天 14:00-16:00"）：ic_clock 图标
 *   18dp + 文本，图标与文字均 secondaryText；
 * - [deadline] 截止时间（已格式化文本，如 "今天 8:00"、"3/12 6:00"）：ic_clock_alarm
 *   图标 18dp + 文本，图标与文字同取 [deadlineColor]（调用方按剩余天数映射紧急色）；
 * - [countdownDays] 左侧倒计时，开关即是否传值（见 [CountdownLabel]）：非 null 时在内容列
 *   左侧画倒计时，≥0 显「N天」、<0 显「逾期N天」，取色直接复用 [deadlineColor]——与右侧
 *   截止时间同色同逻辑；null（用户关闭 / 无截止日期）整个元素不渲染、不占位；
 * - 两个时间项缺席时均不占位，属性行整体走 FlowRow，放不下自动换行；
 * - 行内横向间距：时间项之间、时间项与首个标签之间 12dp，标签之间 8dp（见 [PropertySpacing] /
 *   [TagSpacing]——FlowRow 行距统一取后者，时间项靠末位内距补差）；
 * - 有标签时标题与属性行贴紧一点（见 [TitlePropertySpacing] / [TitlePropertySpacingWithTags]）；
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
    countdownDays: Long? = null,
    tags: List<String> = emptyList(),
    urgency: TodoUrgency = TodoUrgency.None,
    completed: Boolean = false,
    overdue: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    // 有标签时标签带底、视觉体量更大：标题与属性行贴紧一点
    val titlePropertySpacing =
        if (tags.isNotEmpty()) TitlePropertySpacingWithTags else TitlePropertySpacing

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
        // 倒计时（传值即开启）：卡片左区独占块，垂直居中随外层 Row 的 CenterVertically；
        // 内容列 weight(1f) 自动让宽，右侧旗帜与勾选框布局不受影响
        if (countdownDays != null) {
            CountdownLabel(days = countdownDays, tint = deadlineColor)
            Spacer(Modifier.width(CountdownEndSpacing))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(titlePropertySpacing),
        ) {
            Text(
                text = title,
                style = titleStyle,
            )
            if (time != null || deadline != null || tags.isNotEmpty()) {
                FlowRow(
                    // 行距取标签间距；时间项各带末位内距，把与后续项（含首个标签）的间距补到 PropertySpacing
                    horizontalArrangement = Arrangement.spacedBy(TagSpacing),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (time != null) {
                        TimeProperty(
                            icon = R.drawable.ic_clock,
                            text = time,
                            tint = MaterialTheme.classppColors.secondaryText,
                            modifier = Modifier.padding(end = TimeItemEndPadding),
                        )
                    }
                    if (deadline != null) {
                        TimeProperty(
                            icon = R.drawable.ic_clock_alarm,
                            text = deadline,
                            tint = deadlineColor,
                            modifier = Modifier.padding(end = TimeItemEndPadding),
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

@Preview(showBackground = true, widthDp = 412, heightDp = 700, name = "待办卡片倒计时形态")
@Composable
private fun NoteCardCountdownPreview() {
    ClassppTheme {
        NoteCardCountdownShowcase()
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 700, name = "待办卡片倒计时形态（深色）")
@Composable
private fun NoteCardCountdownDarkPreview() {
    ClassppTheme(darkTheme = true) {
        NoteCardCountdownShowcase()
    }
}

/**
 * 预览内容：倒计时开关打开的形态（数字 25sp、单位与「逾期」前缀 12sp；取色与右侧截止时间
 * 同源——>2 天 primary、剩 2 天 urgentYellow、剩 1 天 urgentOrange、当天与逾期 error），
 * 末尾一张是关闭态对照（countdownDays 缺席，与既有形态完全一致）。
 */
@Composable
private fun NoteCardCountdownShowcase() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 15.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 未逾期 25 天（>2 天 primary）：时间、截止、标签齐全——设计稿第一张
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            deadline = "3/12 6:00",
            deadlineColor = MaterialTheme.colorScheme.primary,
            countdownDays = 25,
            tags = listOf("待办标签"),
            onCheckedChange = {},
        )
        // 逾期 25 天（error）：字面「逾期25天」+ 标题标红——设计稿第二张
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            deadline = "3/12 6:00",
            deadlineColor = MaterialTheme.colorScheme.error,
            countdownDays = -25,
            tags = listOf("待办标签"),
            overdue = true,
            onCheckedChange = {},
        )
        // 剩 2 天：urgentYellow
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            deadline = "后天 8:00",
            deadlineColor = MaterialTheme.classppColors.urgentYellow,
            countdownDays = 2,
            onCheckedChange = {},
        )
        // 剩 1 天：urgentOrange
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            deadline = "明天 8:00",
            deadlineColor = MaterialTheme.classppColors.urgentOrange,
            countdownDays = 1,
            onCheckedChange = {},
        )
        // 当天（未逾期，显「0天」）：error
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            deadline = "今天 8:00",
            deadlineColor = MaterialTheme.colorScheme.error,
            countdownDays = 0,
            onCheckedChange = {},
        )
        // 关闭态对照：不传天数，倒计时不渲染、不占位
        NoteCard(title = "户口乔迁材料报送", time = "12:30", tags = listOf("待办标签"), onCheckedChange = {})
    }
}
