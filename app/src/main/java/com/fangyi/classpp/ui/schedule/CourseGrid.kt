package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.Outline
import java.util.Date
import kotlin.math.roundToInt

/** 日期带高度（滚动渐隐的参考高度，模糊进度也以此为刻度） */
internal val DateBandHeight = 44.dp

/** 每个节次行的固定高度（容纳两行课程名 + 教师/地点/房间 + 起止时间） */
private val GridRowHeight = 150.dp

/** 单元格内边距，卡片间形成网格沟槽 */
private val CellPadding = 3.dp

/** 网格线笔宽 */
private val GridLineWidth = 1.dp

/**
 * 末行之后的尾部留白：扩大课表的可滚动范围（含原 8dp 间距）。
 * 节次少时也能有足够的滚动行程展示折叠/模糊过渡，按需调整此常量即可。
 */
private val TrailingScrollSpace = 120.dp

/** 日期带渐隐速率：值越大消失越快；1.0f 为原始速度（滚完自身高度才消失） */
private const val DateBandFadeSpeed = 3.0f


/**
 * 课表网格：顶部日期数字带（第 [weekDates] 对应周，今天高亮，上滑渐隐）
 * + 按节次分行的 5 列课程格。
 *
 * 与 [ScheduleHeader] 星期行同为零水平边距五等分，天然列对齐。
 * 折叠通过外部 modifier.nestedScroll 接入，本组件不感知折叠状态。
 * 列表状态由调用方持有（供模糊进度计算），顶部偏移由 contentPadding.top 跟随顶栏高度。
 */
@Composable
fun CourseGrid(
    courses: List<Course>,
    timeSlots: List<TimeSlot>,
    weekDates: List<Date>,
    today: Date,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        state = state,
        contentPadding = contentPadding,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        item(key = "dateBand") {
            DateBand(
                weekDates = weekDates,
                today = today,
                listState = state,
            )
        }
        items(timeSlots, key = { it.id }) { slot ->
            GridRow(
                slot = slot,
                courses = courses,
            )
        }
        item { Box(modifier = Modifier.height(TrailingScrollSpace)) }
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
                // 给滚动偏移乘上速率倍数，让渐隐进度更快达到 1
                val fadeProgress = (listState.firstVisibleItemScrollOffset * DateBandFadeSpeed / bandHeightPx)
                    .coerceIn(0f, 1f)
                alpha = 1f - fadeProgress
            }

            .background(MaterialTheme.colorScheme.surfaceContainer)
            // 网格线画在 graphicsLayer 之后（更内层），随日期带渐隐一起淡出；
            // 画在 background 之后，线条压在带底色之上
            .drawBehind {
                val stroke = GridLineWidth.toPx()
                for (i in 1..4) {
                    val x = (size.width * i / 5f).roundToInt().toFloat()
                    drawLine(Outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                }
                // 顶部横线：星期行与网格的分隔线；底部不画（与首行之间无线）
                drawLine(
                    Outline,
                    Offset(0f, stroke / 2),
                    Offset(size.width, stroke / 2),
                    strokeWidth = stroke,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        weekDates.forEach { date ->
            val isToday = date.isSameDay(today)
            Text(
                text = date.dayOfMonth().toString(),
                modifier = Modifier.weight(1f),
                fontSize = 18.sp,
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
            .height(GridRowHeight)
            .drawBehind {
                val stroke = GridLineWidth.toPx()
                for (i in 1..4) {
                    val x = (size.width * i / 5f).roundToInt().toFloat()
                    drawLine(Outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                }
                // 底部横线：行间分隔；顶部不画（与日期带之间无线）
                val y = size.height - stroke / 2
                drawLine(Outline, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            },
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
