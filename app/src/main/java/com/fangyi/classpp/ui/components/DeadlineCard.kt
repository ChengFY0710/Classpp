package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppTextStyles
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** 未设截止时刻时的默认时刻：8:00（设计稿示例值） */
private const val DEFAULT_DEADLINE_MINUTE = 480

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 截止日期卡：单行「截止日期 + 日期时刻值 + >」白卡，整行可点，点按先弹 M3
 * [DatePickerDialog] 选日期、确认后接着弹 M3 [TimePickerDialog]（钟表）选时刻，
 * 两步都确认才经 [onChange] 回写（任一步取消 = 不变更，暂存日期由内部持有）。
 * 已设截止时日期弹窗多一枚「清除」，点按回写 null 对——模型约束截止日期与时刻
 * 成对出现或同时为空（TodoValidator）。
 *
 * 行规格与 [DateSelectionCard] 的「自定义日期」子行同款：[SheetCardShape] 连续圆角、
 * 行高下限 [SheetFieldHeight]、行内距 16/14 画在点击区内侧（按压反馈铺满整行，取代涟漪），
 * label 走 fieldLabel、已设值走 fieldValue，未设显示灰「无」（fieldPlaceholder），
 * 尾部 20dp 右箭头 primary。展示格式 `2026-9-7 8:00`（横杠不补零，时刻归一化走
 * [TimeText.format]）。
 *
 * 无状态受控组件：截止值由调用方持有。UTC 毫秒换算与 [DateSelectionCard] 的
 * DatePicker 同一套算法（epochDay 纯整数天）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlineCard(
    deadlineDate: IsoDate?,
    deadlineMinute: Int?,
    onChange: (date: IsoDate?, minute: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var datePicking by remember { mutableStateOf(false) }
    var timePicking by remember { mutableStateOf(false) }
    // 日期弹窗确认后暂存：等时刻也确认才一并回写（任一步取消 = 不变更）
    var pendingDate by remember { mutableStateOf<IsoDate?>(null) }
    val isSet = deadlineDate != null && deadlineMinute != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 按压反馈放 clip 之前：缩放作用于整行、提亮与 SheetCardShape 圆角对齐
            // （pressClickable 自带按压源，涟漪由按压反馈取代；clip 不挡点击输入）
            .pressClickable(SheetCardShape) {
                focusManager.clearFocus()
                keyboard?.hide()
                datePicking = true
            }
            .clip(SheetCardShape)
            .background(colors.surface)
            .heightIn(min = SheetFieldHeight)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.deadline_card_label),
            style = MaterialTheme.classppTextStyles.fieldLabel,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (deadlineDate != null && deadlineMinute != null) {
                "${deadlineDate.toDeadlineText()} ${TimeText.format(deadlineMinute)}"
            } else {
                stringResource(R.string.deadline_card_none)
            },
            style = if (deadlineDate != null && deadlineMinute != null) {
                MaterialTheme.classppTextStyles.fieldValue
            } else {
                MaterialTheme.classppTextStyles.fieldPlaceholder
            },
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(20.dp),
        )
    }

    if (datePicking) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = (deadlineDate ?: IsoDate.today()).toPickerMillis(),
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
                            pendingDate = millis.toPickerIsoDate()
                            timePicking = true
                        }
                    },
                    interactionSource = press,
                    modifier = Modifier.pressFeedback(press, ButtonDefaults.textShape),
                ) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                Row {
                    if (isSet) {
                        // 叠按压反馈：M3 涟漪在按钮内部、调用侧去不掉（按压源与按钮共用）
                        val pressClear = remember { MutableInteractionSource() }
                        TextButton(
                            onClick = {
                                datePicking = false
                                pendingDate = null
                                onChange(null, null)
                            },
                            interactionSource = pressClear,
                            modifier = Modifier.pressFeedback(pressClear, ButtonDefaults.textShape),
                        ) {
                            Text(stringResource(R.string.deadline_card_clear))
                        }
                    }
                    val pressCancel = remember { MutableInteractionSource() }
                    TextButton(
                        onClick = { datePicking = false },
                        interactionSource = pressCancel,
                        modifier = Modifier.pressFeedback(pressCancel, ButtonDefaults.textShape),
                    ) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
    if (timePicking) {
        val timeState = rememberTimePickerState(
            initialHour = (deadlineMinute ?: DEFAULT_DEADLINE_MINUTE) / 60,
            initialMinute = (deadlineMinute ?: DEFAULT_DEADLINE_MINUTE) % 60,
        )
        AlertDialog(
            onDismissRequest = { timePicking = false },
            title = {
                Text(stringResource(R.string.deadline_card_time_title))
            },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                // 叠按压反馈：M3 涟漪在按钮内部、调用侧去不掉（按压源与按钮共用）
                val press = remember { MutableInteractionSource() }
                TextButton(
                    onClick = {
                        timePicking = false
                        val minute = timeState.hour * 60 + timeState.minute
                        pendingDate?.let { date -> onChange(date, minute) }
                        pendingDate = null
                    },
                    interactionSource = press,
                    modifier = Modifier.pressFeedback(press, ButtonDefaults.textShape),
                ) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                val press = remember { MutableInteractionSource() }
                TextButton(
                    onClick = {
                        timePicking = false
                        pendingDate = null
                    },
                    interactionSource = press,
                    modifier = Modifier.pressFeedback(press, ButtonDefaults.textShape),
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/** Material3 DatePicker 的 selectedDateMillis 语义为 UTC 零点毫秒，与 epochDay 纯整数天等价（同 DateSelectionCard 换算） */
private fun IsoDate.toPickerMillis(): Long = epochDay * MILLIS_PER_DAY

private fun Long.toPickerIsoDate(): IsoDate = IsoDate(Math.floorDiv(this, MILLIS_PER_DAY))

/** 截止值展示的日期段 `2026-9-7`（横杠不补零，同设计稿）；换算同 [IsoDate.toString] 的 UTC 整数天。待办详情浮层复用 */
internal fun IsoDate?.toDeadlineText(): String {
    if (this == null) return ""
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochDay * MILLIS_PER_DAY
    }
    return String.format(
        Locale.ROOT,
        "%d-%d-%d",
        calendar.get(GregorianCalendar.YEAR),
        calendar.get(GregorianCalendar.MONTH) + 1,
        calendar.get(GregorianCalendar.DAY_OF_MONTH),
    )
}
