package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.NoteCardShape
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.tabularNumbers
import com.kyant.shapes.RoundedRectangle

/** 卡片左右内距：头部与待办行同一条左基线，与 NoteCard 的 16dp 一致。 */
private val InboxCardHorizontalPadding = 16.dp

/**
 * 卡片上下留白：卡顶一份画在头部上缘；卡底同一份收进末行的点区内侧——末行点区随之下探
 * 到卡片底缘，按压高亮像 [SettingsCard] 一样铺满整卡，行内容位置不变。
 */
private val InboxCardVerticalPadding = 14.dp

/** 头部与首行待办之间的间距。 */
private val InboxHeaderBottomSpacing = 6.dp

/** 头部图标边长：与 ic_pin 源尺寸一致。 */
private val InboxHeaderIconSize = 24.dp

/** 紧凑形态（收集箱两列网格）头部图标边长：20dp，窄卡不让图标压过标题。 */
private val InboxHeaderCompactIconSize = 20.dp

/** 头部图标与标题的间距。 */
private val InboxHeaderIconGap = 8.dp

/** 单行待办的最小高度：无属性行时标题垂直居中，有属性行时按内容自然高（50dp）。 */
private val InboxRowMinHeight = 48.dp

/**
 * 紧凑形态单行最小高度：28dp。两列网格的卡片按设计稿密排——标题行高 24dp、上下各留
 * 2dp，无属性行时不再撑到 48dp；勾选框热区同步收成 [InboxCheckboxCompactHitSize]。
 */
private val InboxRowCompactMinHeight = 28.dp

/** 紧凑形态左右内距：12dp，给 184dp 左右的窄卡留出标题宽度。 */
private val InboxCardCompactHorizontalPadding = 12.dp

/** 勾选框热区：36dp（与 NoteCard 同规格，视觉框 20dp 居中）。 */
private val InboxCheckboxHitSize = 36.dp

/** 紧凑形态勾选框热区：28dp（视觉框仍 20dp）；两列窄卡放不下 36dp 热区。 */
private val InboxCheckboxCompactHitSize = 28.dp

/** 行标题与属性行的纵向间距（NoteCard 同款）。 */
private val InboxTitlePropertySpacing = 2.dp

/** 属性项横向间距：时间/截止/重复之间 12dp（NoteCard 时间项同款）。 */
private val InboxPropertySpacing = 12.dp

/** 属性行换行时的行距（NoteCard 属性行同款）。 */
private val InboxPropertyLineSpacing = 3.dp

/** 勾选框按压反馈形状：与 NoteCard 同款小圆角。 */
private val InboxCheckboxShape = RoundedRectangle(6.dp)

/**
 * 待办收集箱卡的一条待办行数据：只承载标题与三个属性（时间 / 截止 / 重复）。
 *
 * 刻意不含标签字段——收集箱行按设计只显示这三项；也不含紧急档位、倒计时开关与删除态，
 * 这些形态由待办页的 [NoteCard] 承担。完成态只影响行标题与勾选框，不影响属性行。
 *
 * @param id 行身份键：卡片内部按它 [key] 复用行状态（完成动画、按压源），列表重排时
 *   不串位；调用方需保证同卡内唯一
 * @param title 待办标题（原样展示，可换行）
 * @param time 待办时间（已格式化文本，如 "12:30"、"明天 14:00-16:00"）：ic_clock 图标 +
 *   文本，图标与文字均 secondaryText
 * @param deadline 截止时间（已格式化文本，如 "今天 8:00"、"3/12 6:00"）：ic_clock_alarm
 *   图标 + 文本，图标与文字同取 [deadlineColor]
 * @param deadlineColor 截止时间的强调色：null 回落主题 primary；调用方按剩余天数传入
 *   `Todo.deadlineColor()` 的结果（>2 天 primary、剩 2/1 天橙黄、当天与逾期 error）
 * @param repeat 重复规则（已格式化文本，如 "每天"、"自定义"）：ic_arrow_repeat 图标 +
 *   文本，图标与文字均 primary
 * @param completed 完成态：标题渐变到 secondaryText 并按进度自绘删除线（与 [NoteCard]
 *   同款动画），右侧勾选框选中；勾选切换只回调 [TodoInboxCard.onItemCheckedChange]，
 *   由调用方更新数据（组件受控）
 * @param overdue 逾期态：标题取 error（与 NoteCard 的 overdue 同口径）；完成态优先于
 *   逾期态（都真时取完成灰）
 */
data class TodoInboxItem(
    val id: String,
    val title: String,
    val time: String? = null,
    val deadline: String? = null,
    val deadlineColor: Color? = null,
    val repeat: String? = null,
    val completed: Boolean = false,
    val overdue: Boolean = false,
)

/**
 * 待办收集箱卡：白底 [NoteCardShape] 卡片，顶部「图标 + 大标题」，下方多条紧凑待办行。
 *
 * 视觉口径：
 * - 卡片底 [MaterialTheme.colorScheme.surface]、20dp 连续圆角、无投影；头部与行共用左基线
 *   （默认 16dp；[compact] 收 12dp）；卡顶留白画在头部上缘，卡底留白收进末行点区内侧
 *   （末行按压高亮一直铺到卡底，内容位置不变，同 [SettingsCard] 的整卡按压）；
 * - 头部图标（缺省 ic_pin）与标题统一 [headerColor]（默认 primary：已置顶/未完成；
 *   已逾期传 error、已完成传 urgentGreen），标题取 [ClassppTextTokens.cardTitle]
 *   （16sp SemiBold / 行高 20，与行标题同档，靠颜色与图标拉开层级），单行超长省略；
 * - 每条待办行 = 标题（[MaterialTheme.classppTextStyles.fieldLabel]，16sp SemiBold）+
 *   属性行（[TimeProperty]：时间 secondaryText、截止按 [TodoInboxItem.deadlineColor]、
 *   重复 primary），属性行 `FlowRow` 放不下自动换行；行内**不渲染标签、紧急旗、倒计时**；
 * - 行最小高 [InboxRowMinHeight]（[compact] 收 28dp）：没有时间/截止/重复的行，标题
 *   垂直居中；行与行之间无分割线、无缝堆叠；
 * - 右侧勾选框与 [NoteCard] 同规格：36dp 热区 + M3 Checkbox（[compact] 热区收 28dp，
 *   视觉框仍 20dp），完成态即选中态；
 * - 完成动画与 [NoteCard] 完全同款：标题颜色 150ms 渐变 + 删除线按进度自绘（不用
 *   TextDecoration，支持取消完成反向擦除）；逾期行标题取 error，完成优先于逾期。
 *
 * 交互：
 * - [onTodoClick]（必传）：点行（标题/属性行/行内空白处，不含右侧勾选框）回调；宿主据此
 *   用 [TodoInboxItem.id] 反查完整 Todo 并打开待办详情浮层。浮层必须挂在**页面根节点**
 *   ——[OverlaySheet] 是 fillMaxSize 的整屏浮层，挂在卡片或列表项内部只会被卡片/列表项
 *   裁切；组件本身不托管浮层；
 * - [onItemCheckedChange]：勾选框切换（可选，传 null = 勾选框纯展示）；组件不自行改
 *   数据，调用方更新 items 后生效。
 *
 * 宿主接入示例（页面根节点）：
 * ```
 * var detailTargetId by rememberSaveable { mutableStateOf("") }
 * TodoInboxCard(
 *     title = "已置顶",
 *     items = items,
 *     onTodoClick = { detailTargetId = it.id },
 * )
 * todos.firstOrNull { it.id == detailTargetId }?.let { todo ->
 *     TodoDetailSheet(visible = true, todo = todo, onDismiss = { detailTargetId = "" }, ...)
 * }
 * ```
 *
 * items 为空时只渲染头部（卡片不塌成空白），调用方通常应整卡隐藏；行的顺序、分组与
 * 滚动由调用方负责——本组件是一张卡，不是列表容器。
 *
 * @param title 头部标题（如「已置顶」「收集箱」），由调用方给出，组件不内置文案
 * @param items 待办行数据，按给定顺序渲染
 * @param onTodoClick 行点击回调（必传）：宿主用 item.id 反查 Todo 并打开详情浮层
 * @param modifier 作用于整卡节点（卡间距、列表项动画等由宿主挂这里）
 * @param iconRes 头部图标；默认 ic_pin，传 null 隐藏图标
 * @param headerColor 头部图标与标题的强调色：默认 primary；已逾期传 error、已完成传
 *   urgentGreen（与待办特殊分组页的强调色口径一致）
 * @param compact 紧凑形态（收集箱两列网格）：左右内距 12dp、勾选框热区 28dp、行最小高
 *   28dp、头部图标 20dp；默认 false = 整卡形态（16dp / 36dp / 48dp / 24dp）
 * @param onItemCheckedChange 勾选切换回调（item, checked）；null = 勾选框不可点
 */
@Composable
fun TodoInboxCard(
    title: String,
    items: List<TodoInboxItem>,
    onTodoClick: (TodoInboxItem) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = R.drawable.ic_pin,
    headerColor: Color = MaterialTheme.colorScheme.primary,
    compact: Boolean = false,
    onItemCheckedChange: ((TodoInboxItem, Boolean) -> Unit)? = null,
) {
    val horizontalPadding = if (compact) InboxCardCompactHorizontalPadding else InboxCardHorizontalPadding
    val headerIconSize = if (compact) InboxHeaderCompactIconSize else InboxHeaderIconSize
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NoteCardShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 卡顶留白画在头部这一侧：头部不可点，无需按压区覆盖
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = InboxCardVerticalPadding,
                    bottom = InboxHeaderBottomSpacing,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InboxHeaderIconGap),
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = headerColor,
                    modifier = Modifier.size(headerIconSize),
                )
            }
            Text(
                text = title,
                style = ClassppTextTokens.cardTitle,
                color = headerColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        items.forEachIndexed { index, item ->
            key(item.id) {
                TodoInboxRow(
                    item = item,
                    compact = compact,
                    // 卡底留白只由末行承载：按压区随行高下探到卡底，涟漪铺满整卡
                    bottomInset = if (index == items.lastIndex) InboxCardVerticalPadding else 0.dp,
                    onCheckedChange = onItemCheckedChange,
                    onTodoClick = onTodoClick,
                )
            }
        }
        // 没有待办时只剩头部：卡底留白没有行可承载，单留一个不可点的空档
        if (items.isEmpty()) {
            Spacer(Modifier.height(InboxCardVerticalPadding))
        }
    }
}

/**
 * 单条收集箱待办行：左侧标题 + 属性行（最多三项），右侧勾选框。
 *
 * 行高按内容自然增长：标题 [MaterialTheme.classppTextStyles.fieldLabel]（行高继承 24dp）
 * + 2dp 间距 + 属性行（[TimeProperty]：默认 14sp/24；[compact] 用 12sp/16 的
 * [ClassppTextTokens.metaTextSmall]）；三个属性项一律带图标（时间 ic_clock、截止
 * ic_clock_alarm、重复 ic_arrow_repeat），窄卡放不下由 FlowRow 换行；
 * 没有属性行时靠 [InboxRowMinHeight] 撑高并垂直居中（[compact] 形态收 28dp，按设计稿
 * 密排）。整行恒可点（[TodoInboxCard] 的 onTodoClick 必传）：按压高亮取方角
 * [RectangleShape]，外缘圆角由卡片的 clip 裁出；勾选框回调为 null 时不挂 toggleable，
 * 只作展示。
 *
 * [bottomInset] 只由末行传非 0（卡底留白）：行高下限同步抬高该值，留白全部画在
 * 内容下方——内容位置与其余行完全一致，按压高亮连同留白一起铺到卡片底缘
 * （对齐 [SettingsCard] 末行的整卡按压）。
 */
@Composable
private fun TodoInboxRow(
    item: TodoInboxItem,
    compact: Boolean,
    bottomInset: Dp,
    onCheckedChange: ((TodoInboxItem, Boolean) -> Unit)?,
    onTodoClick: (TodoInboxItem) -> Unit,
) {
    // 截止色缺省回落主题 primary（调用方按剩余天数传入紧急色）
    val deadlineColor = item.deadlineColor ?: MaterialTheme.colorScheme.primary
    // 勾选框按压源：pressFeedback 与 toggleable 共用（indication 置 null，涟漪由按压反馈取代）
    val checkboxPress = remember { MutableInteractionSource() }
    val horizontalPadding = if (compact) InboxCardCompactHorizontalPadding else InboxCardHorizontalPadding
    val rowMinHeight = if (compact) InboxRowCompactMinHeight else InboxRowMinHeight
    val checkboxHitSize = if (compact) InboxCheckboxCompactHitSize else InboxCheckboxHitSize
    // 属性行字号：整卡形态与 NoteCard 同款 14sp；两列紧凑卡按设计稿用 12sp 时间行 token，
    // 让「时间 + 截止」在 132dp 左右的内容宽里尽量同排；两种形态都带 tnum，时间换值不抖动
    val propertyStyle = if (compact) {
        ClassppTextTokens.metaTextSmall
    } else {
        MaterialTheme.classppTextStyles.noteProperty.tabularNumbers()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行高下限含卡底留白（非末行 0）；留白全画在内容下方，末行内容与其余行
            // 位置一致，只有按压区（含留白）下探到卡片底缘
            .heightIn(min = rowMinHeight + bottomInset)
            // 方角按压高亮：行与行之间不露圆角缺口，外缘圆角由卡片统一裁出
            .pressClickable(
                shape = RectangleShape,
                onClick = { onTodoClick(item) },
            )
            .padding(horizontal = horizontalPadding)
            .padding(bottom = bottomInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(InboxTitlePropertySpacing),
        ) {
            InboxRowTitle(
                text = item.title,
                completed = item.completed,
                overdue = item.overdue,
            )
            if (item.time != null || item.deadline != null || item.repeat != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(InboxPropertySpacing),
                    verticalArrangement = Arrangement.spacedBy(InboxPropertyLineSpacing),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.time != null) {
                        TimeProperty(
                            icon = R.drawable.ic_clock,
                            text = item.time,
                            tint = MaterialTheme.classppColors.secondaryText,
                            textStyle = propertyStyle,
                        )
                    }
                    if (item.deadline != null) {
                        TimeProperty(
                            icon = R.drawable.ic_clock_alarm,
                            text = item.deadline,
                            tint = deadlineColor,
                            textStyle = propertyStyle,
                        )
                    }
                    if (item.repeat != null) {
                        TimeProperty(
                            icon = R.drawable.ic_arrow_repeat,
                            text = item.repeat,
                            tint = MaterialTheme.colorScheme.primary,
                            textStyle = propertyStyle,
                        )
                    }
                }
            }
        }
        // 勾选框：热区 36dp、视觉框 20dp 居中；按压反馈在 clip 之前，提亮范围与热区圆角对齐
        Row(
            modifier = Modifier
                .then(
                    if (onCheckedChange != null) {
                        Modifier
                            .pressFeedback(checkboxPress, InboxCheckboxShape)
                            .clip(InboxCheckboxShape)
                            .toggleable(
                                value = item.completed,
                                interactionSource = checkboxPress,
                                indication = null,
                                onValueChange = { onCheckedChange(item, it) },
                                role = Role.Checkbox,
                            )
                    } else {
                        Modifier
                    },
                )
                .height(checkboxHitSize)
                .width(checkboxHitSize),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Checkbox(
                checked = item.completed,
                onCheckedChange = null, // 事件交给外层 toggleable，这里固定 null
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.primary,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

/**
 * 行标题：普通态 onSurface；逾期态 error；完成态渐变到 secondaryText 并按进度自绘删除线。
 * 完成态优先于逾期态（两者都真时取完成灰，同 [NoteCard]）。
 *
 * 与 [NoteCard] 的完成态同一套做法——不用 TextDecoration（只能整体开关、无动画），
 * 而是按 [TextLayoutResult] 的逐行基线与行宽从左向右揭示，取消完成反向擦除；多行标题
 * 按行序擦过去。线宽 1dp、位置取基线上方 0.30×字号（平台 LineThrough 的常规视觉位）。
 */
@Composable
private fun InboxRowTitle(text: String, completed: Boolean, overdue: Boolean) {
    val completedProgress by animateFloatAsState(
        targetValue = if (completed) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "todoInboxRowCompletedStrike",
    )
    val titleColor by animateColorAsState(
        targetValue = when {
            completed -> MaterialTheme.classppColors.secondaryText
            overdue -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "todoInboxRowTitleColor",
    )
    val titleStyle = MaterialTheme.classppTextStyles.fieldLabel.copy(color = titleColor)
    // 标题布局结果（onTextLayout 回传，随文本/宽度变化刷新）：删除线要按行取基线与行宽
    var titleLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = titleStyle,
        onTextLayout = { titleLayout = it },
        modifier = Modifier.drawBehind {
            val layout = titleLayout ?: return@drawBehind
            if (completedProgress <= 0f) return@drawBehind
            // 删除线按进度从行首向行尾揭示（多行标题按行序擦过去），取消完成反向擦除
            val fontPx = titleStyle.fontSize.toPx()
            var total = 0f
            for (line in 0 until layout.lineCount) {
                total += layout.getLineRight(line) - layout.getLineLeft(line)
            }
            var budget = total * completedProgress
            for (line in 0 until layout.lineCount) {
                if (budget <= 0f) break
                val left = layout.getLineLeft(line)
                val drawn = minOf(layout.getLineRight(line) - left, budget)
                budget -= drawn
                drawLine(
                    color = titleColor,
                    start = Offset(left, layout.getLineBaseline(line) - fontPx * 0.30f),
                    end = Offset(left + drawn, layout.getLineBaseline(line) - fontPx * 0.30f),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Butt,
                )
            }
        },
    )
}

@Preview(showBackground = true, widthDp = 412, heightDp = 900, name = "待办收集箱卡片")
@Composable
private fun TodoInboxCardPreview() {
    ClassppTheme {
        TodoInboxCardShowcase()
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 900, name = "待办收集箱卡片（深色）")
@Composable
private fun TodoInboxCardDarkPreview() {
    ClassppTheme(darkTheme = true) {
        TodoInboxCardShowcase()
    }
}

/**
 * 预览内容：
 * - 第一张对照整卡形态：已置顶头 + 五条行（时间+截止、时间、重复、紧急截止、完成态），
 *   勾选与行点击都接线（空实现）；
 * - 第二张：三属性齐全的长标题（验证换行自然高）与无任何属性行（验证最小高居中），
 *   只接行点击、不接勾选，演示「勾选框纯展示」形态；
 * - 第三行对照两列网格的紧凑形态与强调色：已逾期（error 红标题）与已完成（urgentGreen
 *   绿头 + 灰标题），卡片间距 10dp。
 */
@Composable
private fun TodoInboxCardShowcase() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = PageHorizontalSpacing, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(CardSectionSpacing),
    ) {
        TodoInboxCard(
            title = "已置顶",
            items = listOf(
                TodoInboxItem(
                    id = "time-deadline",
                    title = "待办标题待办标题",
                    time = "12:30",
                    deadline = "3/12 6:00",
                    deadlineColor = MaterialTheme.colorScheme.primary,
                ),
                TodoInboxItem(id = "time", title = "待办标题待办标题", time = "12:30"),
                TodoInboxItem(id = "repeat", title = "待办标题待办标题", repeat = "每天"),
                TodoInboxItem(
                    id = "deadline-urgent",
                    title = "待办标题待办标题",
                    deadline = "明天 8:00",
                    deadlineColor = MaterialTheme.classppColors.urgentOrange,
                ),
                TodoInboxItem(id = "completed", title = "待办标题待办标题", completed = true),
            ),
            onItemCheckedChange = { _, _ -> },
            onTodoClick = {},
        )
        TodoInboxCard(
            title = "收集箱",
            items = listOf(
                TodoInboxItem(
                    id = "all-properties",
                    title = "一条很长的待办标题，用来验证换行时行的自然高度",
                    time = "12:30",
                    deadline = "3/12 6:00",
                    repeat = "每天",
                ),
                TodoInboxItem(id = "no-properties", title = "没有时间、截止与重复的待办"),
            ),
            onTodoClick = {},
        )
        // 两列网格对照：紧凑形态 + 头部强调色 + 逾期红标题；卡片间距 10dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TodoInboxCard(
                title = "已逾期",
                items = listOf(
                    TodoInboxItem(id = "overdue-1", title = "待办标题待办标题", overdue = true),
                    TodoInboxItem(id = "overdue-2", title = "待办标题待办标题", overdue = true),
                    TodoInboxItem(id = "overdue-3", title = "待办标题待办标题", overdue = true),
                ),
                onTodoClick = {},
                iconRes = R.drawable.ic_checkbox_warning,
                headerColor = MaterialTheme.colorScheme.error,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            TodoInboxCard(
                title = "已完成",
                items = listOf(
                    TodoInboxItem(id = "done-1", title = "待办标题待办标题", completed = true),
                    TodoInboxItem(id = "done-2", title = "待办标题待办标题", completed = true),
                    TodoInboxItem(id = "done-3", title = "待办标题待办标题", completed = true),
                ),
                onTodoClick = {},
                iconRes = R.drawable.ic_checkbox_checked,
                headerColor = MaterialTheme.classppColors.urgentGreen,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
