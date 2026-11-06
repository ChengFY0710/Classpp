package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.data.model.CourseColor as DataCourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.ui.schedule.CourseColor
import com.fangyi.classpp.ui.schedule.barColor
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.isDarkTheme

/** 课程行色条宽：与上课提醒卡（DayCourseAlert）同档（4dp） */
private val CourseBarWidth = 4.dp

/** 色条与文字列的间距：与上课提醒卡同档（12dp） */
private val CourseBarTextGap = 12.dp

/** 色条相对文字块的上下缩进：与上课提醒卡同档（上下各 3dp），色条随文字块高度伸缩 */
private val CourseBarVerticalInset = 3.dp

/**
 * 课程信息卡：左侧课程色条 + 课程名 + 补充信息。
 *
 * 调休浮层的当天课程行与调课浮层顶部共用这张卡：色条几何对齐上课提醒卡
 * （[DayCourseAlert]）——4dp 宽、随文字块高度撑满并上下各缩进 3dp，颜色取课程卡同一套
 * [CourseColor.barColor] 映射；标题/描述的间距与文字样式复用设置卡的行文本块
 * （[SettingsRowText]：fieldLabel + 3dp + settingsDescription/secondaryText），描述可换行。
 * 补充行文案由调用方按场景给——调休列表传「节次 · 地点」，调课浮层传「教师 · 地点」；
 * 空串时只显示课名。
 */
@Composable
internal fun CourseInfoCard(
    course: CourseEntry,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val barColor = CourseColor.valueOf(course.color.name).barColor(MaterialTheme.isDarkTheme)
    // IntrinsicSize.Min：让色条的 fillMaxHeight 拿到文字块的自然高度（同 DayCourseAlert）
    SheetCard(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(CourseBarWidth)
                .padding(top = CourseBarVerticalInset, bottom = CourseBarVerticalInset)
                .background(barColor, PillShape),
        )
        Spacer(Modifier.width(CourseBarTextGap))
        SettingsRowText(
            label = course.name,
            description = detail.takeIf { it.isNotBlank() },
            modifier = Modifier.weight(1f),
            fillWidth = true,
        )
    }
}

@Preview(showBackground = true, name = "课程信息卡", widthDp = 360)
@Composable
private fun CourseInfoCardPreview() {
    ClassppTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PageHorizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(CardSectionSpacing),
        ) {
            CourseInfoCard(
                course = CourseEntry(
                    id = "1",
                    name = "微积分 I-2",
                    teacher = "XX老师",
                    location = "@学武楼 C201",
                    dayOfWeek = 3,
                    startSlot = 1,
                    span = 2,
                    weeks = WeekPattern.everyWeek(16),
                    color = DataCourseColor.Green,
                ),
                detail = "第 1-2 节 · @学武楼 C201",
            )
            // 无补充信息：只显示课名，色条仍与单行文字垂直居中
            CourseInfoCard(
                course = CourseEntry(
                    id = "2",
                    name = "体育舞蹈-拉丁舞",
                    teacher = "",
                    location = "",
                    dayOfWeek = 2,
                    startSlot = 1,
                    weeks = WeekPattern.everyWeek(16),
                    color = DataCourseColor.Pink,
                ),
                detail = "",
            )
        }
    }
}
