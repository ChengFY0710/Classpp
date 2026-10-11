package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import java.util.GregorianCalendar
import java.util.TimeZone

private const val MILLIS_PER_DAY = 86_400_000L

/** 月/日两位补零显示（08 月 27 日） */
private fun pad2(value: Int): String = value.toString().padStart(2, '0')

/** 某年某月的天数（含闰年 2 月） */
private fun monthLength(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (GregorianCalendar().isLeapYear(year)) 29 else 28
}

/** 年份轮默认范围：今年-1 ~ 今年+10（学期起止等大跨度场景用 [BottomDateSelectCard] 的 yearRange 调宽） */
private fun defaultYearRange(): IntRange =
    IsoDate.today().toYearMonthDay().first.let { (it - 1)..(it + 10) }

/** [IsoDate] → (年, 月, 日)（UTC 日历日，与 [IsoDate.of] 同一套换算；模型本身不存 y/m/d 字段） */
private fun IsoDate.toYearMonthDay(): Triple<Int, Int, Int> {
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochDay * MILLIS_PER_DAY
    }
    return Triple(
        calendar.get(GregorianCalendar.YEAR),
        calendar.get(GregorianCalendar.MONTH) + 1,
        calendar.get(GregorianCalendar.DAY_OF_MONTH),
    )
}

/**
 * 日期选择弹窗（BottomSelectSheet 变体之二）：年/月/日三列滚轮，上下滑动选择，
 * 补零显示（2026 年 08 月 27 日）。滚轮视觉与手感同时间选择（共享积木 SelectWheel.kt）。
 *
 * 「日」轮格数随年月联动：年/月吸附跨过新值即按新月天数增减（[monthLength]），越界值
 * 就地折回（1 月 31 日 → 滚到 4 月变 30 日），确认值恒为合法日历日期（[IsoDate.of]
 * 对非法日期会抛异常，确认侧再做一次天数收敛兜底）。
 *
 * [onConfirm]（确认胶囊）回调当前吸附的 [IsoDate]；滚轮在弹窗保持组合期间记住上次
 * 选择，移出组合（[onDismissed] 之后）重建时回到 [initialDate]。[initialDate] 的年份
 * 落在 [yearRange] 之外时年轮停在最近边界。其余行为（两段式关闭、遮罩、阴影参数
 * [shadowAlpha]）同 [BottomSelectSheet]。
 */
@Composable
fun BottomDateSelectCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.date_select_title),
    initialDate: IsoDate = IsoDate.today(),
    yearRange: IntRange = defaultYearRange(),
    shadowAlpha: Float = 0.3f,
    // 可选第三动作（截止日期流程的「清除」等），透传给 [BottomSelectSheet]
    neutralAction: SheetTopAction? = null,
    onConfirm: (date: IsoDate) -> Unit,
    onDismissed: () -> Unit = {},
) {
    val (initYear, initMonth, initDay) = initialDate.toYearMonthDay()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val yearState = remember(density, yearRange) {
        WheelState(
            count = yearRange.count(),
            initialIndex = (initYear - yearRange.first).coerceIn(0, yearRange.count() - 1),
            density = density,
            scope = scope,
        )
    }
    val monthState = remember(density) {
        WheelState(count = 12, initialIndex = initMonth - 1, density = density, scope = scope)
    }
    val dayState = remember(density) {
        WheelState(
            count = monthLength(initYear, initMonth),
            initialIndex = initDay - 1,
            density = density,
            scope = scope,
        )
    }

    // 「日」轮格数随年月联动：年/月的选中值跨过新格即增减天数，越界值就地折回
    val daysInMonth by remember {
        derivedStateOf { monthLength(yearState.selectedIndex, monthState.selectedIndex + 1) }
    }
    LaunchedEffect(daysInMonth) { dayState.updateCount(daysInMonth) }

    BottomSelectSheet(
        title = title,
        visible = visible,
        onDismiss = onDismiss,
        onDismissed = onDismissed,
        shadowAlpha = shadowAlpha,
        neutralAction = neutralAction,
        modifier = modifier,
        onConfirm = {
            // 兜底收敛：格数联动的副作用晚于滚动一拍时，防 31 日 + 30 天月这类非法组合
            val year = yearRange.first + yearState.selectedIndex
            val month = monthState.selectedIndex + 1
            val day = (dayState.selectedIndex + 1).coerceAtMost(monthLength(year, month))
            onConfirm(IsoDate.of(year, month, day))
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelGroup(
                state = yearState,
                unit = stringResource(R.string.date_unit_year),
                format = { (yearRange.first + it).toString() },
                modifier = Modifier.weight(1f),
            )
            WheelGroup(
                state = monthState,
                unit = stringResource(R.string.date_unit_month),
                format = { pad2(it + 1) },
                modifier = Modifier.weight(1f),
            )
            WheelGroup(
                state = dayState,
                unit = stringResource(R.string.date_unit_day),
                format = { pad2(it + 1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
