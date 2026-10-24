package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.motion.PressShade
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** 日期选择卡片的取值：快捷档位（无 / 今天 / 明天）或自定义日期 + 重复规则 */
sealed interface DateSelection {
    /** 无日期 */
    data object None : DateSelection

    /** 今天（相对档位：展示文案固定，具体日期由调用方在落库前解析） */
    data object Today : DateSelection

    /** 明天 */
    data object Tomorrow : DateSelection

    /** 自定义日期 + 重复规则（随 Todo.repeat 落库） */
    data class Custom(val date: IsoDate, val repeat: RepeatRule) : DateSelection
}

/**
 * 日期选择卡片：白卡主行整行可点（行首 ic_clock 图标 24dp onSurface + 12dp 间距，同
 * [SettingsCardwithIcon]），在两种模式间切换——
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
 * 日期与重复（没有则今天 / 无重复），子行随之展开。「重复」行点按经 [onRepeatEditRequest]
 * 请求宿主弹重复浮层设置页（RepeatSettingsSheet，页内覆盖层画不进卡片里——浮层内容会被
 * Surface 裁剪，由宿主组合在浮层级，同「自定义日期」行的滚轮弹窗）；「自定义日期」行
 * 点按经 [onCustomDatePickRequest] 请求宿主弹滚轮日期弹窗（BottomDateSelectCard）。
 *
 * 规格：白卡 [SheetCardShape] 连续圆角，行高下限 [SheetFieldHeight]，行内距 16/14 画在
 * 点击区内侧（按压反馈铺满整行，ui.motion 的 pressClickable 取代涟漪）；行首 ic_clock
 * 图标 24dp onSurface、与 label 间距 12dp（同 [SettingsCardwithIcon]），label 走 fieldLabel，
 * 值与快捷选项走 fieldValue，尾部图标 20dp primary。
 */
@Composable
fun DateSelectionCard(
    value: DateSelection,
    expanded: Boolean,
    onValueChange: (DateSelection) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    // 「自定义日期」行上报：初值为当前自定义日期，[onPicked] 收确认日期后经
    // [onValueChange] 回写；弹窗本体由宿主（NewTodoSheet）组合在浮层之后
    onCustomDatePickRequest: (initialDate: IsoDate, onPicked: (IsoDate) -> Unit) -> Unit,
    // 「重复」行上报：初值为当前重复规则、锚日期为自定义日期（浮层切频率单位时以它播种
    // 方格），[onPicked] 收确认规则后经 [onValueChange] 回写；浮层本体由宿主组合在浮层之后
    onRepeatEditRequest: (initial: RepeatRule, anchorDate: IsoDate, onPicked: (RepeatRule) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // 浅色模式下白底行加色白提亮无感（白色无法更亮），改用微微压暗表达按压；深色模式仍提亮
    val rowShade = if (MaterialTheme.isDarkTheme) PressShade.Brighten else PressShade.Darken
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
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
                .pressClickable(RectangleShape, shade = rowShade) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onExpandedChange(!expanded)
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_clock),
                contentDescription = null,
                tint = colors.onSurface,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.date_card_label),
                style = MaterialTheme.classppTextStyles.fieldLabel,
            )
            if (expanded) {
                // 快捷选项为纯文字按钮（无按压反馈 / 涟漪占位）：文字按内容占宽，Row 内
                // SpaceEvenly 均分空隙——文字宽窄不一，相邻文字间距仍相等；各选项自吃点击，
                // 不再触发整行收起
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    quickOptionLabels().forEach { (option, label) ->
                        QuickOptionText(
                            label = label,
                            onClick = {
                                onValueChange(option)
                                onExpandedChange(false)
                            },
                        )
                    }
                    QuickOptionText(
                        label = stringResource(R.string.date_card_custom),
                        onClick = {
                            // 选「自定义」：沿用上一次的自定义日期与重复（没有则今天 / 无重复），
                            // 退回值模式让自定义子行展开
                            onValueChange(
                                DateSelection.Custom(
                                    date = custom?.date ?: IsoDate.today(),
                                    repeat = custom?.repeat ?: RepeatRule(),
                                ),
                            )
                            onExpandedChange(false)
                        },
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
                        .pressClickable(RectangleShape, shade = rowShade) {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            // 自定义日期行 → 宿主的滚轮日期弹窗（退场动画中 custom 已为空时忽略点击）
                            if (custom != null) {
                                onCustomDatePickRequest(custom.date) { picked ->
                                    onValueChange(custom.copy(date = picked))
                                }
                            }
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
                // 重复行：点按请求宿主弹重复浮层设置页（退场动画中 custom 已为空时忽略点击）；
                // 行上无 clip，原位换 pressClickable（独立按压源，不与相邻行双亮）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        //.heightIn(min = SheetFieldHeight)
                        .pressClickable(RectangleShape, shade = rowShade) {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            if (custom != null) {
                                onRepeatEditRequest(custom.repeat, custom.date) { picked ->
                                    onValueChange(custom.copy(repeat = picked))
                                }
                            }
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
                        text = shownCustom.repeat.summaryText(),
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
            }
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

/** 快捷选项文字按钮：纯文字可点，无按压反馈 / 涟漪占位（主题根以 NoOpIndication 关掉涟漪） */
@Composable
private fun QuickOptionText(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.classppTextStyles.fieldValue,
        maxLines = 1,
        // 内边距画在点击区内侧：触控区比文字外扩一圈，文字间隙的误触不落回整行收起
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    )
}

private const val MILLIS_PER_DAY = 86_400_000L

/** 设计稿展示格式 `2026/10/3`（不补零）；换算同 [IsoDate.toString] 的 UTC 整数天。
 * internal：重复摘要（RepeatFormat）与重复浮层的结束日期行共用同一格式 */
internal fun IsoDate.toCardText(): String {
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
