package com.fangyi.classpp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit

/**
 * 重复规则的界面文案：日期卡「重复」行（DateSelectionCard）与重复浮层设置页
 * （RepeatSettingsSheet）共用，避免两处各写一份。
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

/** 「重复」行的值文案：预设档位即档位名；自定义 = `每N单位`（方格标记不入摘要，保持单行短值） */
@Composable
internal fun RepeatRule.summaryText(): String = when (type) {
    RepeatType.Custom -> stringResource(R.string.repeat_custom_summary, interval, unit.unitText())
    else -> type.labelText()
}
