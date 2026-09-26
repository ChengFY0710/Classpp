package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.ui.theme.ClassppTheme
import java.util.Date

/** 日期带高度（滚动渐隐的参考高度） */
private val DateBandHeight = 44.dp

/** 每个节次行的固定高度（容纳两行课程名 + 教师/地点/房间 + 起止时间） */
private val GridRowHeight = 165.dp

/** 单元格内边距，卡片间形成网格沟槽 */
private val CellPadding = 4.dp

/**
 * 课表网格：顶部日期数字带（第 [weekDates] 对应周，今天高亮，上滑渐隐）
 * + 按节次分行的 5 列课程格。
 *
 * 与 [ScheduleHeader] 星期行同为零水平边距五等分，天然列对齐。
 * 折叠通过外部 modifier.nestedScroll 接入，本组件不感知折叠状态。
 */
@Composable
fun CourseGrid(
    courses: List<Course>,
    timeSlots: List<TimeSlot>,
    weekDates: List<Date>,
    today: Date,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        item(key = "dateBand") {
            DateBand(
                weekDates = weekDates,
                today = today,
                listState = listState,
            )
        }
        items(timeSlots, key = { it.id }) { slot ->
            GridRow(
                slot = slot,
                courses = courses,
            )
        }
        item { Box(modifier = Modifier.height(8.dp)) }
    }
}

/** 日期数字带：5 天日号，今天 primary 高亮；随自身滚出量渐隐 */
@Composable
private fun DateBand(
    weekDates: List<Date>,
    today: Date,
    listState: LazyListState,
) {
    val density = LocalDensity.current
    val bandHeightPx = with(density) { DateBandHeight.toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DateBandHeight)
            // 在 draw 阶段读滚动偏移，滚动时不触发整列重组：
            // offset = 0 全显，≥ bandHeight 全隐
            .graphicsLayer {
                alpha = (1f - listState.firstVisibleItemScrollOffset / bandHeightPx)
                    .coerceIn(0f, 1f)
            }
            .background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        weekDates.forEach { date ->
            val isToday = date.isSameDay(today)
            Text(
                text = date.dayOfMonth().toString(),
                modifier = Modifier.weight(1f),
                fontSize = 17.sp,
                fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = if (isToday) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** 一个节次行：5 个等宽单元格，有课渲染卡片，无课露网格背景 */
@Composable
private fun GridRow(
    slot: TimeSlot,
    courses: List<Course>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GridRowHeight),
    ) {
        for (day in 1..5) {
            val course = courses.findAt(day, slot.id)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(CellPadding),
            ) {
                if (course != null) {
                    CourseCard(
                        course = course,
                        slot = slot,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "课表网格")
@Composable
private fun CourseGridPreview() {
    ClassppTheme {
        CourseGrid(
            courses = MockCourses,
            timeSlots = DefaultTimeSlots,
            weekDates = datesForWeek(2),
            today = java.util.Calendar.getInstance().apply {
                set(2026, java.util.Calendar.MARCH, 10)
            }.time,
        )
    }
}
