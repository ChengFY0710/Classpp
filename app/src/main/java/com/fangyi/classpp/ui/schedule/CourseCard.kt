package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.ui.theme.ClassppTheme

private val CardShape = RoundedCornerShape(6.dp)
private val BarWidth = 3.dp

/** [CourseColor] 到卡片竖条/时间文字颜色的映射 */
private val CourseColor.barColor: Color
    get() = when (this) {
        CourseColor.Blue -> Color(0xFFB1C4EE)
        CourseColor.Green -> Color(0xFF81D689)
        CourseColor.Greentwo -> Color(0xFF98D651)
        CourseColor.Yellow -> Color(0xFFE3C160)
        CourseColor.Orange -> Color(0xFFEBB8A7)
        CourseColor.Purple -> Color(0xFFCBBCF0)
        CourseColor.Teal -> Color(0xFF7CD3D0)
        CourseColor.Pink -> Color(0xFFEDB5C9)
    }

private val SecondaryTextColor = Color(0xFFABAFB4)

/**
 * 课程卡片：白底圆角卡 + 左侧彩色竖条。
 *
 * 自上而下：开始时间（竖条同色）→ 课程名（两行截断）→ 教师 → @楼名 房间，
 * 底部对齐结束时间。
 */
@Composable
fun CourseCard(
    course: Course,
    slot: TimeSlot,
    modifier: Modifier = Modifier,
) {
    val barColor = course.color.barColor

    Row(
        modifier = modifier
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 左侧彩色竖条
        Box(
            modifier = Modifier
                .width(BarWidth)
                .fillMaxHeight()
                .background(barColor),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 4.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    text = slot.startTime,
                    color = barColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    textAlign = TextAlign.Start,
                    lineHeight = 12.sp,
                )
                Column() {
                    Text(
                        text = course.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        letterSpacing = (-0.1).sp,
                    )
                    Spacer(modifier = Modifier.size(1.dp))
                    Text(
                        text = course.teacher,
                        color = SecondaryTextColor,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    // 上课地点，统一一行
                    Text(
                        text = course.location,
                        color = SecondaryTextColor,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Start,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Text(
                text = slot.endTime,
                color = barColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                textAlign = TextAlign.Start,
                lineHeight = 12.sp,
            )
        }
    }
}

@Preview(showBackground = true, name = "常规卡片")
@Composable
private fun CourseCardPreview() {
    ClassppTheme {
        CourseCard(
            course = MockCourses[4],
            slot = DefaultTimeSlots[1],
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
    }
}

@Preview(showBackground = true, name = "两行长名")
@Composable
private fun CourseCardLongNamePreview() {
    ClassppTheme {
        CourseCard(
            course = MockCourses[0],
            slot = DefaultTimeSlots[0],
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
    }
}
