package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.RowShape
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** 日期选择卡片的取值：快捷档位（无 / 今天 / 明天）或自定义日期 + 重复频率 */
sealed interface DateSelection {
    /** 无日期 */
    data object None : DateSelection

    /** 今天（相对档位：展示文案固定，具体日期由调用方在落库前解析） */
    data object Today : DateSelection

    /** 明天 */
    data object Tomorrow : DateSelection

    /** 自定义日期 + 重复频率 */
    data class Custom(val date: IsoDate, val repeat: RepeatFrequency) : DateSelection
}

/** [DateSelection.Custom] 的重复频率 */
enum class RepeatFrequency { Daily, Weekly, Monthly, Yearly }

/**
 * 日期选择卡片：白卡主行整行可点，在两种模式间切换——
 *
 * - 值模式（[expanded] = false）：`日期 + 当前值 + <>`；值为 [DateSelection.Custom]
 *   时主行下方追加「自定义日期 / 重复」两行（设计稿卡一、卡三、卡四）。
 * - 选项模式（[expanded] = true）：主行变为 `日期 + 无/今天/明天/自定义 快捷选项 + 收起
 *   图标`（[R.drawable.ic_chevron_down_up] 旋转 90°，尖角向内），自定义子行随之隐藏
 *   （设计稿卡二）。
 *
 * 「自定义日期 / 重复」子行的出现/隐藏复用 motion 层 [Expandable]：高度动画期间卡片
 * 下方的兄弟内容逐帧实时让位。收起当帧 value 可能已被切成非自定义，展示用上一份
 * 自定义值的快照撑到退场缩完（结束即移出组合、快照失效），交互回调仍以当前值守卫
 * ——动画期间点到残留行直接忽略，避免弹窗状态卡死。
 *
 * 快捷选项点按后回写 [onValueChange] 并退回值模式；选「自定义」沿用上一次的自定义
 * 日期与重复（没有则今天 / 每周），子行随之展开。「重复」行点按弹 [PopupMenuPopup]
 * 菜单（选中项前置勾），「自定义日期」行点按弹 M3 [DatePickerDialog]。
 *
 * 规格：白卡 [SheetCardShape] 连续圆角，行高下限 [SheetFieldHeight]，行内距 16/14 画在
 * 点击区内侧（按压反馈铺满整行，ui.motion 的 pressClickable 取代涟漪）；label 走 fieldLabel，
 * 值与快捷选项走 fieldValue，尾部图标 20dp primary。
 */
@Composable
fun DateSelectionCard(
    value: DateSelection,
    expanded: Boolean,
    onValueChange: (DateSelection) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var repeatMenuExpanded by remember { mutableStateOf(false) }
    var datePicking by remember { mutableStateOf(false) }
    val custom = value as? DateSelection.Custom
    // 收起动画当帧 value 可能已被切成非自定义（子行正在退场）：记住上一份自定义值把
    // 子行撑到行块平滑缩完；展示用快照，交互回调仍以当前 custom 守卫
    var lastCustom by remember { mutableStateOf(custom) }
    if (custom != null) lastCustom = custom

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SheetCardShape)
            .background(colors.surface),
    ) {
        // 主行：整行可点切换 值模式 ↔ 选项模式。行上无 clip，原位把 clickable 换成
        // pressClickable（自带按压源 = pressFeedback + indication 置 null 的 clickable，
        // 按压反馈取代涟漪），通栏行用 RectangleShape
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SheetFieldHeight)
                .pressClickable(RectangleShape) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onExpandedChange(!expanded)
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.date_card_label),
                style = MaterialTheme.classppTextStyles.fieldLabel,
            )
            if (expanded) {
                // 快捷选项均布在 label 与收起图标之间；各选项自吃点击，不再触发整行收起
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    quickOptionLabels().forEach { (option, label) ->
                        QuickOptionText(
                            label = label,
                            onClick = {
                                onValueChange(option)
                                onExpandedChange(false)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    QuickOptionText(
                        label = stringResource(R.string.date_card_custom),
                        onClick = {
                            // 选「自定义」：沿用上一次的自定义日期与重复（没有则今天 / 每周），
                            // 退回值模式让自定义子行展开
                            onValueChange(
                                DateSelection.Custom(
                                    date = custom?.date ?: IsoDate.today(),
                                    repeat = custom?.repeat ?: RepeatFrequency.Weekly,
                                ),
                            )
                            onExpandedChange(false)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.width(6.dp))
                // 收起图标：上下尖角相对的 ic_chevron_down_up 旋转 90°，变成左右尖角向内
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down_up),
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(90f),
                )
            } else {
                Spacer(Modifier.weight(1f))
                Text(
                    text = selectionLabel(value),
                    style = MaterialTheme.classppTextStyles.fieldValue,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left_right),
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Expandable(expanded = custom != null && !expanded) {
            // 退场动画期间 value 可能已被切成非自定义：展示字段用上一份快照撑到缩完
            val shownCustom = lastCustom
            if (shownCustom != null) {
                HorizontalDivider(
                    thickness = 1.dp,   // 线粗细
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp).offset(y = (-3).dp),
                )
                // 自定义日期行：点按弹 M3 DatePickerDialog（退场动画中 custom 已为空时忽略点击）。
                // 行上无 clip，原位换 pressClickable：独立按压源，与主行/重复行互不串亮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        //.heightIn(min = SheetFieldHeight)
                        .pressClickable(RectangleShape) {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            if (custom != null) datePicking = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.date_card_custom_date),
                        style = MaterialTheme.classppTextStyles.fieldLabel,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = shownCustom.date.toCardText(),
                        style = MaterialTheme.classppTextStyles.fieldValue,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                // 重复行：点按弹菜单（同 SettingsCard 选择行，菜单顶边贴行顶边）；
                // 行上无 clip，原位换 pressClickable（独立按压源，不与相邻行双亮）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        //.heightIn(min = SheetFieldHeight)
                        .pressClickable(RectangleShape) {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            if (custom != null) repeatMenuExpanded = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.date_card_repeat),
                        style = MaterialTheme.classppTextStyles.fieldLabel,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = shownCustom.repeat.label(),
                        style = MaterialTheme.classppTextStyles.fieldValue,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_up_down),
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        RepeatMenu(
                            expanded = repeatMenuExpanded,
                            selected = shownCustom.repeat,
                            onDismiss = { repeatMenuExpanded = false },
                            onPick = {
                                repeatMenuExpanded = false
                                if (custom != null) onValueChange(custom.copy(repeat = it))
                            },
                        )
                    }
                }
            }
        }
    }
    if (datePicking && custom != null) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = custom.date.toPickerMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { datePicking = false },
            confirmButton = {
                // M3 按钮的涟漪在组件内部硬编码、调用侧置不了 null：本阶段只叠加按压反馈——
                // 按压源交给按钮形参，pressFeedback 接在 modifier 链末尾（贴按钮本体，对齐 textShape）
                val press = remember { MutableInteractionSource() }
                TextButton(
                    onClick = {
                        datePicking = false
                        datePickerState.selectedDateMillis?.let { millis ->
                            onValueChange(custom.copy(date = millis.toPickerIsoDate()))
                        }
                    },
                    interactionSource = press,
                    modifier = Modifier.pressFeedback(press, ButtonDefaults.textShape),
                ) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                // 叠按压反馈：M3 涟漪在按钮内部、调用侧去不掉（按压源与按钮共用）
                val press = remember { MutableInteractionSource() }
                TextButton(
                    onClick = { datePicking = false },
                    interactionSource = press,
                    modifier = Modifier.pressFeedback(press, ButtonDefaults.textShape),
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/** 值模式主行的展示文字：无 / 今天 / 明天 / 自定义 */
@Composable
private fun selectionLabel(value: DateSelection): String = when (value) {
    DateSelection.None -> stringResource(R.string.date_card_none)
    DateSelection.Today -> stringResource(R.string.date_card_today)
    DateSelection.Tomorrow -> stringResource(R.string.date_card_tomorrow)
    is DateSelection.Custom -> stringResource(R.string.date_card_custom)
}

/** 快捷选项（自定义外）：档位 + 展示文字 */
@Composable
private fun quickOptionLabels(): List<Pair<DateSelection, String>> = listOf(
    DateSelection.None to stringResource(R.string.date_card_none),
    DateSelection.Today to stringResource(R.string.date_card_today),
    DateSelection.Tomorrow to stringResource(R.string.date_card_tomorrow),
)

/** 快捷选项文字按钮：蓝字居中，按压反馈取行形圆角 RowShape（同 RowChoiceCard 的选项行） */
@Composable
private fun QuickOptionText(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // 按压反馈放 clip 之前：缩放作用于整块、提亮与 RowShape 圆角对齐（clip 不挡点击输入）
            .pressClickable(RowShape, onClick = onClick)
            .clip(RowShape)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.fieldValue,
            maxLines = 1,
        )
    }
}

/**
 * 「重复」的弹出菜单：[PopupMenuPopup] 锚在行尾上下箭头上（[PopupMenuCard] 白卡 +
 * 柔影，同 SettingsCard 选择行菜单），卡顶边贴行顶边（[MenuTopAlignLift]）；
 * 选中项经勾选槽显示勾并蓝字（PopupMenuCard 原生行样式）。
 */
@Composable
private fun RepeatMenu(
    expanded: Boolean,
    selected: RepeatFrequency,
    onDismiss: () -> Unit,
    onPick: (RepeatFrequency) -> Unit,
) {
    val density = LocalDensity.current
    val topAlignLiftPx = with(density) { MenuTopAlignLift.roundToPx() }
    PopupMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        sections = listOf(
            PopupMenuSection(
                items = RepeatFrequency.entries.map { frequency ->
                    PopupMenuItem(
                        label = frequency.label(),
                        checked = frequency == selected,
                        onClick = {
                            onDismiss()
                            if (frequency != selected) onPick(frequency)
                        },
                    )
                },
            ),
        ),
        // 卡片左缘对齐图标左缘（DropdownMenu 原位），上移量使卡顶边贴行顶边
        cardPosition = { anchorBounds, _, _ ->
            IntOffset(anchorBounds.left, anchorBounds.bottom - topAlignLiftPx)
        },
    )
}

@Composable
private fun RepeatFrequency.label(): String = when (this) {
    RepeatFrequency.Daily -> stringResource(R.string.repeat_daily)
    RepeatFrequency.Weekly -> stringResource(R.string.repeat_weekly)
    RepeatFrequency.Monthly -> stringResource(R.string.repeat_monthly)
    RepeatFrequency.Yearly -> stringResource(R.string.repeat_yearly)
}

private const val MILLIS_PER_DAY = 86_400_000L

/** Material3 DatePicker 的 selectedDateMillis 语义为 UTC 零点毫秒，与 epochDay 纯整数天等价（同 ScheduleAdapters 换算） */
private fun IsoDate.toPickerMillis(): Long = epochDay * MILLIS_PER_DAY

private fun Long.toPickerIsoDate(): IsoDate = IsoDate(Math.floorDiv(this, MILLIS_PER_DAY))

/** 设计稿展示格式 `2026/10/3`（不补零）；换算同 [IsoDate.toString] 的 UTC 整数天 */
private fun IsoDate.toCardText(): String {
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochDay * MILLIS_PER_DAY
    }
    return String.format(
        Locale.ROOT,
        "%d/%d/%d",
        calendar.get(GregorianCalendar.YEAR),
        calendar.get(GregorianCalendar.MONTH) + 1,
        calendar.get(GregorianCalendar.DAY_OF_MONTH),
    )
}
