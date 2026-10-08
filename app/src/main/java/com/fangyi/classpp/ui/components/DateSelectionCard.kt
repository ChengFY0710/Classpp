package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.theme.MenuShape
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
 * 日期选择卡片：白卡主行整行可点，在两种模式间切换（暂不做展开动画）——
 *
 * - 值模式（[expanded] = false）：`日期 + 当前值 + <>`；值为 [DateSelection.Custom]
 *   时主行下方追加「自定义日期 / 重复」两行（设计稿卡一、卡三、卡四）。
 * - 选项模式（[expanded] = true）：主行变为 `日期 + 无/今天/明天/自定义 快捷选项 + 收起
 *   图标`（[R.drawable.ic_chevron_down_up] 旋转 90°，尖角向内），自定义子行随之隐藏
 *   （设计稿卡二）。
 *
 * 快捷选项点按后回写 [onValueChange] 并退回值模式；选「自定义」沿用上一次的自定义
 * 日期与重复（没有则今天 / 每周），子行随之展开。「重复」行点按弹系统菜单（选中项
 * 前置勾），「自定义日期」行点按弹 M3 [DatePickerDialog]。
 *
 * 规格：白卡 [SheetCardShape] 连续圆角，行高下限 [SheetFieldHeight]，行内距 16/14 画在
 * clickable 内侧（涟漪铺满整行，对齐设计稿「点按涟漪」）；label 走 fieldLabel，值与
 * 快捷选项走 fieldValue，尾部图标 20dp primary。
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SheetCardShape)
            .background(colors.surface),
    ) {
        // 主行：整行可点切换 值模式 ↔ 选项模式（涟漪铺满整行）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SheetFieldHeight)
                .clickable {
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
        if (custom != null && !expanded) {
            HorizontalDivider(
                thickness = 1.dp,   // 线粗细
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp).offset(y = (-3).dp),
            )
            // 自定义日期行：点按弹 M3 DatePickerDialog
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    //.heightIn(min = SheetFieldHeight)
                    .clickable {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        datePicking = true
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
                    text = custom.date.toCardText(),
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
            // 重复行：点按弹系统菜单（同 SettingsCard 选择行，菜单顶边贴行顶边）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    //.heightIn(min = SheetFieldHeight)
                    .clickable {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        repeatMenuExpanded = true
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
                    text = custom.repeat.label(),
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
                        selected = custom.repeat,
                        onDismiss = { repeatMenuExpanded = false },
                        onPick = {
                            repeatMenuExpanded = false
                            onValueChange(custom.copy(repeat = it))
                        },
                    )
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
                TextButton(onClick = {
                    datePicking = false
                    datePickerState.selectedDateMillis?.let { millis ->
                        onValueChange(custom.copy(date = millis.toPickerIsoDate()))
                    }
                }) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { datePicking = false }) {
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

/** 快捷选项文字按钮：蓝字居中，行形圆角涟漪（同 RowChoiceCard 的选项行） */
@Composable
private fun QuickOptionText(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RowShape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
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
 * 「重复」的系统弹出菜单：M3 [DropdownMenu] 锚在行尾上下箭头上（offset 上移 34dp 使
 * 菜单顶边贴行顶边，自 SettingsCard 选择行沿袭）；每项 20dp 前置勾槽保证文字对齐，
 * 选中项显示勾并蓝字。
 */
@Composable
private fun RepeatMenu(
    expanded: Boolean,
    selected: RepeatFrequency,
    onDismiss: () -> Unit,
    onPick: (RepeatFrequency) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(x = 0.dp, y = (-34).dp),
        shape = MenuShape,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        RepeatFrequency.entries.forEach { frequency ->
            DropdownMenuItem(
                leadingIcon = {
                    // 勾槽恒占 20dp：未选中留空，各菜单项文字对齐（同 PopupMenuCard）
                    Box(modifier = Modifier.width(20.dp)) {
                        if (frequency == selected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_checkmark),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                text = {
                    Text(
                        text = frequency.label(),
                        style = MaterialTheme.classppTextStyles.menuItem,
                        color = if (frequency == selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                },
                onClick = {
                    onDismiss()
                    if (frequency != selected) onPick(frequency)
                },
            )
        }
    }
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
