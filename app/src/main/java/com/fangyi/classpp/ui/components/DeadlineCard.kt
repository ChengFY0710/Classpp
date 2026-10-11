package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.fangyi.classpp.ui.motion.PressShade
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** 未设截止时刻时的默认时刻：8:00（设计稿示例值） */
private const val DEFAULT_DEADLINE_MINUTE = 480

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 截止日期卡：单行「截止日期 + 日期时刻值 + >」白卡，整行可点，点按先经
 * [onDatePickRequest] 请求宿主弹日期轮（BottomDateSelectCard，页内覆盖层画不进卡片里
 * ——浮层内容会被 Surface 裁剪，由宿主组合在浮层级），确认后接着经 [onTimePickRequest]
 * 弹时刻轮，两步都确认才经 [onChange] 回写（任一步取消 = 不变更，日期在链式闭包间传递）。
 * 已设截止时日期轮带一枚「清除」第三动作（宿主经 neutralAction 下发），点按回写 null 对
 * ——模型约束截止日期与时刻成对出现或同时为空（TodoValidator）。
 *
 * 行规格与 [DateSelectionCard] 的「自定义日期」子行同款：[SheetCardShape] 连续圆角、
 * 行高下限 [SheetFieldHeight]、行内距 16/14 画在点击区内侧（按压反馈铺满整行，取代涟漪），
 * label 走 fieldLabel、已设值走 fieldValue，未设显示灰「无」（fieldPlaceholder），
 * 尾部 20dp 右箭头 primary。展示格式 `2026-9-7 8:00`（横杠不补零，时刻归一化走
 * [TimeText.format]）。
 *
 * 无状态受控组件：截止值由调用方持有。
 */
@Composable
fun DeadlineCard(
    deadlineDate: IsoDate?,
    deadlineMinute: Int?,
    onChange: (date: IsoDate?, minute: Int?) -> Unit,
    // 日期步上报：初值为当前截止日（未设时今天），[onPicked] 收确认的日期后接时刻步
    onDatePickRequest: (initialDate: IsoDate, onPicked: (IsoDate) -> Unit) -> Unit,
    // 时刻步上报：初值为待选时刻（分钟数，一天内），[onPicked] 收用户确认的分钟数
    // 并在此完成 onChange 回写；两个弹窗本体都由宿主（NewTodoSheet）组合在浮层之后
    onTimePickRequest: (initialMinute: Int, onPicked: (minuteOfDay: Int) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // 浅色模式下白底卡加色白提亮无感（白色无法更亮），改用微微压暗表达按压；深色模式仍提亮
    val rowShade = if (MaterialTheme.isDarkTheme) PressShade.Brighten else PressShade.Darken
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 按压反馈放 clip 之前：缩放作用于整行、明暗与 SheetCardShape 圆角对齐
            // （pressClickable 自带按压源，涟漪由按压反馈取代；clip 不挡点击输入）
            .pressClickable(SheetCardShape, shade = rowShade) {
                focusManager.clearFocus()
                keyboard?.hide()
                // 日期轮 → 确认 → 时刻轮：日期在链式闭包间传递，两步都确认才 onChange
                onDatePickRequest(deadlineDate ?: IsoDate.today()) { pickedDate ->
                    onTimePickRequest(deadlineMinute ?: DEFAULT_DEADLINE_MINUTE) { pickedMinute ->
                        onChange(pickedDate, pickedMinute)
                    }
                }
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
}

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
