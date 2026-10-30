package com.fangyi.classpp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.REPEAT_END_COUNT_DEFAULT
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.markRange

/**
 * 重复规则的界面文案：日期卡「重复」行（DateSelectionCard）、重复浮层设置页
 * （RepeatSettingsSheet）与待办详情页「重复」分组（TodoDetailSheet）共用，避免多处各写一份。
 */

/** 重复类型的档位名：无重复 / 每天 / 每周 / 每月 / 每年 / 自定义 */
@Composable
internal fun RepeatType.labelText(): String = when (this) {
    RepeatType.None -> stringResource(R.string.repeat_none)
    RepeatType.Daily -> stringResource(R.string.repeat_daily)
    RepeatType.Weekly -> stringResource(R.string.repeat_weekly)
    RepeatType.Monthly -> stringResource(R.string.repeat_monthly)
    RepeatType.Yearly -> stringResource(R.string.repeat_yearly)
    RepeatType.Custom -> stringResource(R.string.date_card_custom)
}

/** 频率单位名：天 / 周 / 月 / 年 */
@Composable
internal fun RepeatUnit.unitText(): String = when (this) {
    RepeatUnit.Day -> stringResource(R.string.repeat_unit_day)
    RepeatUnit.Week -> stringResource(R.string.repeat_unit_week)
    RepeatUnit.Month -> stringResource(R.string.repeat_unit_month)
    RepeatUnit.Year -> stringResource(R.string.repeat_unit_year)
}

/**
 * 各单位的方格标记文案：周 = 一~日（同课表列头）、月 = 数字、年 = 「N月」；
 * 天无方格语义，返回空列表。重复浮层的方格与详情页「重复规则」行共用这一份。
 */
@Composable
internal fun RepeatUnit.markLabels(): List<String> = when (this) {
    RepeatUnit.Day -> emptyList()
    RepeatUnit.Week -> stringArrayResource(R.array.weekdays_short).toList()
    RepeatUnit.Month -> markRange.map { it.toString() }
    RepeatUnit.Year -> markRange.map { stringResource(R.string.repeat_month_mark, it) }
}

/** 结束条件的档位名：永不 / 日期 / 次数 */
@Composable
internal fun RepeatEndType.labelText(): String = when (this) {
    RepeatEndType.Never -> stringResource(R.string.repeat_end_never)
    RepeatEndType.Date -> stringResource(R.string.repeat_end_type_date)
    RepeatEndType.Count -> stringResource(R.string.repeat_end_type_count)
}

/**
 * 「重复」行的值文案：预设档位即档位名；自定义 = `每N单位`（方格标记不入摘要，保持单行
 * 短值）。结束条件追加摘要——日期档「 · 至日期」、次数档「 · 共N次」，永不不带后缀；
 * 档位字段不完整（正常只在导入的脏数据里出现）时退回不带结束信息的摘要。
 */
@Composable
internal fun RepeatRule.summaryText(): String {
    val base = when (type) {
        RepeatType.Custom -> stringResource(R.string.repeat_custom_summary, interval, unit.unitText())
        else -> type.labelText()
    }
    return when (endType) {
        RepeatEndType.Never -> base
        RepeatEndType.Date -> endDate?.let { date ->
            stringResource(R.string.repeat_summary_until, base, date.toCardText())
        } ?: base
        RepeatEndType.Count -> stringResource(
            R.string.repeat_summary_count,
            base,
            endCount ?: REPEAT_END_COUNT_DEFAULT,
        )
    }
}

/**
 * 详情页「重复规则」行的值：自定义档位展开为 `每N单位 标记`（如「每2周 一、三、五」）；
 * 天单位无标记语义，只显 `每N天`；非自定义档位返回 null（该行不渲染）。
 * 与 [summaryText] 的分工：摘要保持单行短值、不含标记（日期卡用），这里给详情页完整策略。
 */
@Composable
internal fun RepeatRule.customRuleText(): String? {
    if (type != RepeatType.Custom) return null
    val base = stringResource(R.string.repeat_custom_summary, interval, unit.unitText())
    if (unit == RepeatUnit.Day) return base
    val labels = unit.markLabels()
    val markText = marks.sorted()
        .mapNotNull { mark -> labels.getOrNull(mark - 1) }
        .joinToString("、")
    return if (markText.isEmpty()) base else "$base $markText"
}

/**
 * 详情页「结束」行的值：永不 / 至日期（`至2026/12/31`）/ 共N次。
 * 字段悬挂（正常只在导入的脏数据里出现）时退回「永不」，与仓库归一化口径一致。
 */
@Composable
internal fun RepeatRule.endConditionText(): String = when (endType) {
    RepeatEndType.Never -> stringResource(R.string.repeat_end_never)
    RepeatEndType.Date -> endDate
        ?.let { stringResource(R.string.repeat_end_date_value, it.toCardText()) }
        ?: stringResource(R.string.repeat_end_never)
    RepeatEndType.Count -> stringResource(
        R.string.repeat_end_count_summary,
        endCount ?: REPEAT_END_COUNT_DEFAULT,
    )
}
