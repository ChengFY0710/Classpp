package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.NoteCardShape
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.tabularNumbers
import com.kyant.shapes.RoundedRectangle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 属性行横向间距：时间项之间、时间项与首个标签之间取 [PropertySpacing]（12dp）；
 * 标签之间取 [TagSpacing]（8dp，标签带底更耐紧）。FlowRow 的行距只能统一一个值，
 * 所以行距取 TagSpacing、时间项各带 [TimeItemEndPadding] 末位内距把间距补回 PropertySpacing。
 */
private val PropertySpacing = 12.dp
private val TagSpacing = 4.dp
private val TimeItemEndPadding = PropertySpacing - TagSpacing

private val TagIconSize = 15.dp
private val TimeIconSize = 16.dp
private val FlagIconSize = 25.dp

/** 已删除样式的撤回按钮：图标 24dp，热区与勾选框同规格（36dp，见卡片右侧） */
private val RestoreIconSize = 24.dp

/** 标签胶囊底 **/
private val TagChipShape = PillShape
private val TagChipPadding = 8.dp
private val TagChipVerticalPadding = 3.dp

/** 标题与属性行的纵向间距 */
private val TitlePropertySpacing = 2.dp

/**
 * 倒计时（[CountdownLabel]）：数字 [ClassppTextTokens.countdownNumber] 半粗突出，单位与「逾期」
 * 前缀 [ClassppTextTokens.countdownUnit] 收小；倒计时与右侧内容列相距 [CountdownEndSpacing]（沿用紧急旗后的节奏）。
 */
private val CountdownEndSpacing = 16.dp

/**
 * 倒计时完成退场的左移幅度（CountdownExitSlot）：标签淡出时向卡边漂移的距离——
 * 收在 16dp 卡横向内距内，不会被卡片 clip 裁掉。
 */
private val CountdownExitSlide = 12.dp

/**
 * 时间/截止/重复属性项：图标 16dp + 文本（同色），图标与文字相距 4dp（同 TagChip）。
 * 待办时间固定 secondaryText（ic_clock）；截止时间按剩余天数取紧急色（ic_clock_alarm）；
 * 重复规则取 primary（ic_arrow_repeat）——图标与文字颜色始终一致。
 * 图标可空（收集箱两列紧凑卡的时间项不画 ic_clock，给 12sp 的窄卡省出文字宽度）。
 * 文本样式默认 [MaterialTheme.classppTextStyles.noteProperty]（14sp Normal · tnum）；紧凑卡传
 * [ClassppTextTokens.metaTextSmall]（12sp Medium / 行高 16 / tnum）。文本默认不限行数
 * （NoteCard 全宽卡不会触界）；窄容器（时间轴任务卡重叠分列）传 maxLines = 1 + Ellipsis，
 * 避免时刻被逐字折行。
 */
@Composable
internal fun TimeProperty(
    @DrawableRes icon: Int?,
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textStyle: TextStyle = MaterialTheme.classppTextStyles.noteProperty.tabularNumbers(),
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(TimeIconSize),
            )
        }
        Text(
            text = text,
            style = textStyle.copy(color = tint),
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/**
 * 单枚标签胶囊：标签色 10% 圆角底 + ic_tag 图标 + 标签名（图标与文字取标签色）——
 * TagChoosingCard 未选中胶囊同款配色语义。标签色由调用方按 [color] 注入
 * （TagChoosingCard 挑选的颜色；缺省主题 Primary）。
 * 底加水平内距（8dp）与上下内距（3dp，计入布局高度、撑起所在行）。
 * 卡片属性行（[NoteCard]）与待办详情浮层共用同一形态；外层间距由调用方排布。
 */
@Composable
internal fun TagChip(tag: String, color: Color = MaterialTheme.colorScheme.primary) {
    Row(
        modifier = Modifier
            .clip(TagChipShape)
            .background(color.copy(alpha = 0.1f))
            .padding(
                start = TagChipPadding,
                end = TagChipPadding,
                top = TagChipVerticalPadding,
                bottom = TagChipVerticalPadding,
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_tag),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(TagIconSize),
        )
        Text(
            text = tag,
            style = ClassppTextTokens.tagChip.copy(color = color),
        )
    }
}

/**
 * 来源徽标：外部同步的待办在属性行显示来源（课程中心场景 = 课程名）——形态与 [TagChip]
 * 同构（10% 圆角底 + 同款内距），配色取中性 secondaryText、无图标：与用户标签
 * （ic_tag + primary）一眼区分。
 */
@Composable
internal fun SourceBadge(label: String) {
    val color = MaterialTheme.classppColors.secondaryText
    Row(
        modifier = Modifier
            .clip(TagChipShape)
            .background(color.copy(alpha = 0.12f))
            .padding(
                start = TagChipPadding,
                end = TagChipPadding,
                top = TagChipVerticalPadding,
                bottom = TagChipVerticalPadding,
            ),
    ) {
        Text(
            text = label,
            style = ClassppTextTokens.tagChip.copy(color = color),
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
            withStyle(SpanStyle(fontSize = ClassppTextTokens.countdownUnit.fontSize)) { append(overduePrefix) }
        }
        withStyle(
            SpanStyle(
                fontSize = ClassppTextTokens.countdownNumber.fontSize,
                fontWeight = ClassppTextTokens.countdownNumber.fontWeight,
            ),
        ) {
            append(abs(days).toString())
        }
        withStyle(SpanStyle(fontSize = ClassppTextTokens.countdownUnit.fontSize)) { append(dayUnit) }
    }
    Text(
        text = text,
        style = MaterialTheme.classppTextStyles.noteProperty.copy(color = tint).tabularNumbers(),
        maxLines = 1,
    )
}

/**
 * 倒计时进出场槽位（编排见 [NoteCard] 内的注释块）：[slotProgress] 1→0 时槽位宽度从
 * 内容自然宽（标签 + [CountdownEndSpacing]）收缩到 0——右区内容列 weight(1f) 由布局
 * 自动跟随左移；内容本体按 [labelProgress] 向左漂移 [CountdownExitSlide] 并淡出。
 * 两个进度分开驱动，「标签先走、内容跟随」的错拍由调用方编排保证。测孩子放开 minWidth，
 * 混排 Text 才能在 Row 的约束下取到固有宽。
 */
@Composable
private fun CountdownExitSlot(
    days: Long,
    tint: Color,
    labelProgress: Float,
    slotProgress: Float,
) {
    Row(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0))
            layout(
                width = (placeable.width * slotProgress).roundToInt(),
                height = placeable.height,
            ) {
                placeable.placeRelative(0, 0)
            }
        },
    ) {
        Row(
            modifier = Modifier.graphicsLayer {
                alpha = labelProgress
                translationX = -CountdownExitSlide.toPx() * (1f - labelProgress)
            },
        ) {
            CountdownLabel(days = days, tint = tint)
            Spacer(Modifier.width(CountdownEndSpacing))
        }
    }
}

/**
 * 待办卡片：左侧倒计时（可选）+ 标题 + 属性行（待办时间、截止时间、标签，自动换行）+ 右侧紧急旗帜与勾选框。
 *
 * - 颜色全部取自主题：卡片底 surface，标题 fieldLabel（onSurface），旗帜按 [urgency]
 *   档位取色（[TodoUrgency.flagColor]，同 UrgentFlagCard），勾选框 primary；
 * - [tagColors] 标签配色表（标签名 → 颜色）：标签胶囊底取标签色 10% 透明、图标与文字取
 *   标签色（TagChoosingCard 未选中胶囊同款）。配色由调用方从标签库解析（用户标签
 *   TagEntry.colorArgb）；表里没有的名字（含课程标签）回落主题 Primary；
 * - [sourceLabel] 来源徽标（外部同步的待办，如课程中心的课程名）：与标签胶囊同构但取
 *   中性 secondaryText、无图标，排在重复规则之后、用户标签之前；null 不渲染；
 * - [time] 待办时间（已格式化文本，如 "12:30"、"明天 14:00-16:00"）：ic_clock 图标
 *   18dp + 文本，图标与文字均 secondaryText；
 * - [deadline] 截止时间（已格式化文本，如 "今天 8:00"、"3/12 6:00"）：ic_clock_alarm
 *   图标 18dp + 文本，图标与文字同取 [deadlineColor]（调用方按剩余天数映射紧急色）；
 * - [repeat] 重复规则（已格式化文本，如 "每天"、"自定义"）：ic_arrow_repeat 图标 18dp +
 *   文本，图标与文字均 primary，排在待办时间、截止时间之后、标签之前；
 * - [countdownDays] 左侧倒计时，开关即是否传值（见 [CountdownLabel]）：非 null 时在内容列
 *   左侧画倒计时，≥0 显「N天」、<0 显「逾期N天」，取色直接复用 [deadlineColor]——与右侧
 *   截止时间同色同逻辑；null（用户关闭 / 无截止日期）整个元素不渲染、不占位；
 *   完成态（[completed] = true）同样不渲染、不占位——但不是瞬时增删：标记完成后标签
 *   「左移淡出」退场、槽位收拢让右区内容跟随左移，取消完成反向入场（见 CountdownExitSlot）；
 *   若 [countdownDays] 本身变 null（浮层里关掉开关）则维持旧口径直接收场，不走动画；
 * - 各时间项缺席时均不占位，属性行整体走 FlowRow，放不下自动换行；
 * - 行内横向间距：时间项之间、时间项与首个标签之间 12dp，标签之间 8dp（见 [PropertySpacing] /
 *   [TagSpacing]——FlowRow 行距统一取后者，时间项靠末位内距补差）；
 * - [urgency] 紧急档位：非 None 才画旗，None 不画（列表默认形态无旗）；
 * - [completed] 完成态：标题划线置灰（时间/标签/旗帜不变），勾选框选中——切换带过渡
 *   动画（颜色渐变 + 删除线按进度自绘「划过去」，取消完成反向擦除，见 titleStyle 处）；
 *   左区倒计时随完成「左移淡出」退场、右区内容跟随左移（即使 [countdownDays] 有值），
 *   取消完成后反向入场恢复；
 * - [overdue] 逾期态：标题标红（theme 的 error，时间/标签/旗帜不变）；完成态优先于逾期态；
 * - [deleted] 已删除样式（最近删除页）：右侧勾选框换成 24dp 撤回按钮（ic_arrow_reset，
 *   theme 的 error 色），点按回调 [onRestore]；其余样式与普通卡完全一致——唯一区别是右侧；
 *   该样式下 [onCheckedChange] 不参与渲染；
 * - 时间、截止、重复与标签全缺席时卡片仍有 60dp 最小高度，不塌成纯文字高度；
 * - [swipe] 侧滑动作（见 [NoteCardSwipe]）：非 null 时卡片包进侧滑层——左右滑呼出删除/
 *   置顶动作卡，双阈值结算（不足展开阈值回弹复位、越过执行阈值松手即执行、展开态
 *   动作卡可点按、点卡片主体收起）；null（默认）不加层，卡片与原来完全一致；
 * - [selected] 编辑态选中（传非 null 即进入选中形态）：选中卡画 2dp Primary 描边——宽度与
 *   透明度共用同一条 150ms 进度动画「生长+淡入」（与 SheetTextField 聚焦蓝框同规格）；
 *   右侧勾选框同时转纯展示（保留完成态视觉、不拦截点击），整卡任意位置单击即切换选中
 *   （onClick 由调用方接「切换选中」）；null（默认）无选中形态，卡片与原来完全一致；
 * - [onLongClick] 长按回调（待办列表长按进编辑页的入口）：与 onClick 同时非 null 时手势
 *   从 pressClickable 换成 pressFeedback + combinedClickable 手动配对（长按需自建按压源）；
 *   null（默认）维持纯单击；
 * - [verticalPadding] 主体行上下内距：默认 [RowCardPaddingVertical]（整卡形态的 14dp 节奏）；
 *   多条待办合并一张外卡的形态（DayTodoAlert）传 12dp，行距按合并卡节奏收紧；
 * - [shape] 卡片形状：按压提亮轮廓、背景/内容裁剪与选中描边共用；默认 [NoteCardShape]
 *   （整卡形态），合并卡形态传 RectangleShape——行与行之间的按压高亮取直角，外缘圆角
 *   由外层 DayCardShape 统一裁出（高亮随缩放溢出行界时也被外层 clip 收进卡片轮廓）；
 * - [modifier] 作用于侧滑层之外（整卡节点）：列表项级 animateItem 与卡间距内距由宿主
 *   挂在这里、不进卡内布局链——动作卡经 matchParentSize 撑满侧滑层实测尺寸，内距若进
 *   卡内 Row 会把动作卡一起撑高（比主体卡多出一份透明内距）。
 */
@Composable
fun NoteCard(
    title: String,
    modifier: Modifier = Modifier,
    time: String? = null,
    deadline: String? = null,
    deadlineColor: Color = MaterialTheme.colorScheme.primary,
    repeat: String? = null,
    countdownDays: Long? = null,
    tags: List<String> = emptyList(),
    // 标签配色表（标签名 → 颜色，与 TagChoosingCard 同源）：缺席的名字回落主题 Primary
    tagColors: Map<String, Color> = emptyMap(),
    // 来源徽标（外部同步的待办，如课程中心课程名）：null 不渲染，与用户标签视觉区分
    sourceLabel: String? = null,
    urgency: TodoUrgency = TodoUrgency.None,
    completed: Boolean = false,
    overdue: Boolean = false,
    // 已删除样式（最近删除页）：右侧勾选框换成撤回按钮，点按回调 onRestore；
    // 其余样式不变（唯一区别是右侧），onCheckedChange 在该样式下不参与渲染
    deleted: Boolean = false,
    onRestore: (() -> Unit)? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    // 编辑态选中：非 null 进入选中形态（Primary 蓝框 + 勾选框纯展示），null 无此形态
    selected: Boolean? = null,
    // 长按回调（进编辑页入口）：与 onClick 同时非 null 才生效
    onLongClick: (() -> Unit)? = null,
    // 主体行上下内距：默认整卡节奏（RowCardPaddingVertical），合并卡形态传 12dp
    verticalPadding: Dp = RowCardPaddingVertical,
    // 卡片形状：按压提亮轮廓、背景/内容裁剪与选中描边共用；合并卡形态（DayTodoAlert）
    // 传 RectangleShape，行与行之间的按压高亮取直角
    shape: Shape = NoteCardShape,
    swipe: NoteCardSwipe? = null,
) {
    // 完成态过渡：删除线不再用 TextDecoration（只能整体开关，无动画）——颜色渐变 +
    // 按进度自绘（见标题 Text 的 drawBehind）。颜色端点同旧口径：completed 优先于
    // overdue（都真时取完成灰），普通态取 fieldLabel 自带的 onSurface
    val completedProgress by animateFloatAsState(
        targetValue = if (completed) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "noteCardCompletedStrike",
    )
    val titleColor by animateColorAsState(
        targetValue = when {
            completed -> MaterialTheme.classppColors.secondaryText
            overdue -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "noteCardTitleColor",
    )
    val titleStyle = MaterialTheme.classppTextStyles.fieldLabel.copy(color = titleColor)
    // 标题布局结果（onTextLayout 回传，随文本/宽度变化刷新）：删除线自绘要按行取
    // 基线与行宽，逐行揭示才能正确处理换行标题
    var titleLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 标签配色的回落色：配色表里没有的名字（含调用方未接线时）统一落主题 Primary
    val fallbackTagColor = MaterialTheme.colorScheme.primary
    // 选中蓝框（编辑态）：宽度与透明度共用同一条 0→1 进度动画，选中「生长+淡入」、
    // 取消「萎缩+淡出」严格同步——SheetTextField 聚焦描边同规格（150ms LinearOutSlowIn）
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected == true) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "noteCardSelectionStroke",
    )
    val selectionStrokeColor = MaterialTheme.colorScheme.primary
    val selectionStrokeWidth = 2.dp * selectionProgress
    // 倒计时进出场编排：目标态 = 有天数且未完成。完成/取消完成不再瞬时卸载/恢复，
    // 而是两条 Animatable 编排——标签「左移淡出」（alpha + 左移幅度）与槽位宽度收拢
    // （右区内容由布局跟随左移），取消完成镜像入场。shown 控制是否参与组合：退场动画
    // 播完才卸载；初值取首帧目标态，常驻展示的卡不会起播一条无谓动画。快速连点时
    // LaunchedEffect 重启，Animatable 从当前值自然反转，「播完再卸载」随取消自动作废
    val countdownTarget = countdownDays != null && !completed
    var countdownShown by remember { mutableStateOf(countdownTarget) }
    val countdownLabelProgress = remember { Animatable(if (countdownTarget) 1f else 0f) }
    val countdownSlotProgress = remember { Animatable(if (countdownTarget) 1f else 0f) }
    LaunchedEffect(countdownTarget) {
        if (countdownTarget) {
            // 入场（取消完成，与退场互为倒带）：标签先从左淡入回归，槽位延后一拍张回，
            // 内容右移让位
            countdownShown = true
            launch {
                countdownLabelProgress.animateTo(
                    1f,
                    tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
                )
            }
            delay(Motion.TodoCountdownFollowDelayMillis.toLong())
            countdownSlotProgress.animateTo(
                1f,
                tween(durationMillis = Motion.TodoCountdownFollowMillis, easing = Motion.Decelerate),
            )
        } else {
            // 退场（完成）：标签先左移淡出，槽位收拢延后一拍启动，内容跟随滑入让位
            launch {
                countdownLabelProgress.animateTo(
                    0f,
                    tween(durationMillis = Motion.TodoCountdownExitMillis, easing = Motion.Accelerate),
                )
            }
            delay(Motion.TodoCountdownFollowDelayMillis.toLong())
            countdownSlotProgress.animateTo(
                0f,
                tween(durationMillis = Motion.TodoCountdownFollowMillis, easing = Motion.Decelerate),
            )
            countdownShown = false
        }
    }
    // 侧滑层：swipe 非 null 时卡片包进侧滑层（左右滑呼出动作卡），下方 Row 原样不动；
    // null = 不加层，输出与无侧滑时完全一致。[modifier] 挂在侧滑层之外（整卡节点）——
    // 列表项级 animateItem 与卡间距内距由宿主挂这里、不进卡内 Row：动作卡经
    // matchParentSize 撑满侧滑层实测尺寸，内距若进 Row 会把它一起撑高（比主体卡
    // 多出一份透明内距，表现为滑出的动作卡比待办卡高）
    NoteCardSwipeLayer(swipe = swipe, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SheetFieldHeight)
                // 按压反馈同 SheetCard「可点才挂」：位置在 clip 之前（缩放作用于整卡、不被圆角
                // 裁掉），提亮范围与卡片形状（shape）对齐；null = 静态卡，涟漪由按压反馈取代。
                // 长按形态（编辑入口）手动配对 pressFeedback + combinedClickable——
                // pressClickable 只封了单击（长按需自建按压源，见其 KDoc）
                .then(
                    when {
                        onClick != null && onLongClick != null -> {
                            val press = remember { MutableInteractionSource() }
                            Modifier.pressFeedback(press, shape).combinedClickable(
                                interactionSource = press,
                                indication = null,
                                onClick = onClick,
                                onLongClick = onLongClick,
                            )
                        }
                        onClick != null -> Modifier.pressClickable(shape, onClick = onClick)
                        else -> Modifier
                    },
                )
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    // 进度归 0 后干脆不挂 border：0 宽描边会被 Skia 画成 1px 发丝线而非不可见
                    // （SheetTextField 上机踩过的坑，同款规避）
                    if (selectionStrokeWidth > 0.dp) {
                        Modifier.border(
                            width = selectionStrokeWidth,
                            color = selectionStrokeColor.copy(
                                alpha = selectionStrokeColor.alpha * selectionProgress,
                            ),
                            shape = shape,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = RowCardPaddingHorizontal, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 倒计时（传值即开启；完成/取消完成走上方编排的动画进出场，不再瞬时增删）：
            // 卡片左区独占块，垂直居中随外层 Row 的 CenterVertically；
            // 槽位收拢后内容列 weight(1f) 自动占满让出的宽，右侧旗帜与勾选框布局不受影响
            if (countdownShown && countdownDays != null) {
                CountdownExitSlot(
                    days = countdownDays,
                    tint = deadlineColor,
                    labelProgress = countdownLabelProgress.value,
                    slotProgress = countdownSlotProgress.value,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TitlePropertySpacing),
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    onTextLayout = { titleLayout = it },
                    modifier = Modifier.drawBehind {
                        val layout = titleLayout ?: return@drawBehind
                        if (completedProgress <= 0f) return@drawBehind
                        // 删除线按进度从行首向行尾揭示（多行标题按行序擦过去），取消完成
                        // 反向擦除。位置取基线上方 0.3×字号（平台 LineThrough 的常规视觉位），
                        // 线宽 1dp 与 16sp 字号的系统删除线粗细相当；平头端帽对齐系统样式
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
                if (time != null || deadline != null || repeat != null || sourceLabel != null || tags.isNotEmpty()) {
                    FlowRow(
                        // 行距取标签间距；时间项各带末位内距，把与后续项（含首个标签）的间距补到 PropertySpacing
                        horizontalArrangement = Arrangement.spacedBy(TagSpacing),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
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
                        if (repeat != null) {
                            TimeProperty(
                                icon = R.drawable.ic_arrow_repeat,
                                text = repeat,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = TimeItemEndPadding),
                            )
                        }
                        if (sourceLabel != null) {
                            SourceBadge(sourceLabel)
                        }
                        tags.forEach { tag ->
                            TagChip(tag, tagColors[tag] ?: fallbackTagColor)
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
            if (deleted) {
                // 已删除样式：右侧换撤回按钮——24dp 图标取 theme 的 error 色，热区与勾选框同为
                // 36×36；按压反馈同源（pressClickable 的缩放作用于整个触控区、放在 clip 之前，
                // 提亮范围与圆角对齐）。onRestore 缺席时按钮只是不响应（预览态）
                Row(
                    modifier = Modifier
                        .pressClickable(
                            shape = RoundedRectangle(6.dp),
                            role = Role.Button,
                            onClick = { onRestore?.invoke() },
                        )
                        .clip(RoundedRectangle(6.dp))
                        .height(36.dp)
                        .width(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_reset),
                        contentDescription = stringResource(R.string.todo_restore),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(RestoreIconSize),
                    )
                }
            } else {
                // M3 Checkbox 占位（后续按设计稿自绘）：颜色先取主题 primary——未选 = 蓝描边框，已选，蓝底白勾。
                // 按压源由 pressFeedback 与 toggleable 共用（indication 置 null，涟漪由按压反馈取代）；
                // 编辑选中形态（selected 非 null）转纯展示——不挂按压反馈与 toggleable，
                // 点击穿透给整卡（单击任意位置切换选中），勾选框只保留完成态视觉
                val checkboxPress = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        // 按压反馈在 clip 之前：缩放作用于整个触控区，提亮范围与勾选框圆角对齐
                        .then(
                            if (selected == null) {
                                Modifier
                                    .pressFeedback(checkboxPress, RoundedRectangle(6.dp))
                                    .clip(RoundedRectangle(6.dp))
                                    .toggleable(
                                        value = completed,
                                        interactionSource = checkboxPress,
                                        indication = null,
                                        onValueChange = { onCheckedChange?.invoke(it) }, // 安全调用
                                        role = Role.Checkbox,
                                    )
                            } else {
                                Modifier
                            },
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

/** 预览内容：设计稿的全部形态（基础、换行、标签、多标签、红旗、完成、补位、最小高、日期形态与截止各色、重复） */
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
        // 标签配色：底 = 标签色 10%、图标与文字 = 标签色（TagChoosingCard 挑选的颜色，
        // 调用方经 tagColors 注入）；「复习」不在配色表里 → 回落主题 Primary
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            tags = listOf("高等数学", "实验报告", "复习"),
            tagColors = mapOf(
                "实验报告" to Color(0xFFE5645C),
            ),
            onCheckedChange = {},
        )
        // 无配色表：标签回落主题 Primary
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
        // 重复规则：primary 蓝图标+文字，排在待办时间、截止时间之后、标签之前（自定义只显「自定义」）
        NoteCard(title = "户口乔迁材料报送", time = "12:30", repeat = "每天", onCheckedChange = {})
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            deadline = "3/12 6:00",
            deadlineColor = MaterialTheme.colorScheme.primary,
            repeat = "自定义",
            tags = listOf("待办标签"),
            onCheckedChange = {},
        )
        // 已删除样式（最近删除页）：右侧勾选框换成红色撤回按钮，其余样式与普通卡完全一致
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            tags = listOf("待办标签"),
            deleted = true,
            onRestore = {},
        )
        // 编辑选中形态对照：选中（2dp Primary 蓝框）与未选中（进入编辑形态、无蓝框）——
        // 勾选框转纯展示（保留完成态视觉），整卡单击切换选中
        NoteCard(title = "户口乔迁材料报送", time = "12:30", onCheckedChange = {}, selected = true)
        NoteCard(title = "户口乔迁材料报送", time = "12:30", onCheckedChange = {}, selected = false)
        // 选中与完成态叠加：蓝框 + 划线置灰标题 + 勾选框选中
        NoteCard(title = "户口乔迁材料报送", time = "12:30", completed = true, onCheckedChange = {}, selected = true)
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
 * 预览内容：倒计时开关打开的形态（数字 24sp、单位与「逾期」前缀 14sp；取色与右侧截止时间
 * 同源——>2 天 primary、剩 2 天 urgentYellow、剩 1 天 urgentOrange、当天与逾期 error），
 * 倒数第二张是完成态对照（countdownDays 有值也不渲染——标记完成后倒计时退场），
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
        // 完成态对照：倒计时开关开着（countdownDays 有值）也不渲染、不占位——标记完成后退场
        NoteCard(
            title = "户口乔迁材料报送",
            time = "12:30",
            deadline = "3/12 6:00",
            deadlineColor = MaterialTheme.colorScheme.primary,
            countdownDays = 25,
            tags = listOf("待办标签"),
            completed = true,
            onCheckedChange = {},
        )
        // 关闭态对照：不传天数，倒计时不渲染、不占位
        NoteCard(title = "户口乔迁材料报送", time = "12:30", tags = listOf("待办标签"), onCheckedChange = {})
    }
}
