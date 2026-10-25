package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.ui.schedule.CourseColor
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.DayTimelineNowChipShape
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors

/** 一天的整点数：刻度从 0:00 到次日 0:00 共 [HoursPerDay] + 1 个 */
private const val HoursPerDay = 24

/** 相邻整点刻度的纵向间距，也是时间轴的时间比例尺：1 小时 = [HourSpacing] */
private val HourSpacing = 50.dp

/** 左侧时间区域宽度，刻度在其中水平居中；右缘即网格竖线 */
private val TimeAreaWidth = 44.dp

/** 卡片区域左缘与网格竖线的距离：12dp（与页面卡片水平边距一致） */
private val TaskAreaStartGap = 12.dp

/** 网格线笔宽：与课表页 CourseGrid 同款；当前时刻横线同款 */
private val GridLineWidth = 1.dp

/** 当前时刻矩形规格：距屏幕左缘 [NowChipStartPadding]、定宽 [NowChipWidth]（横线自其
 *  右缘起画，左侧不外露）、高 [NowChipHeight]、时刻 [NowChipFontSize] Medium 居中 */
private val NowChipStartPadding = 2.dp
private val NowChipWidth = 39.dp
private val NowChipHeight = 22.dp
private val NowChipFontSize = 10.sp

/** 刻度字号（与 DayCourseAlert 次级信息一致） */
private val TimeLabelFontSize = 12.sp

/**
 * 日程页时间轴：左侧 [TimeAreaWidth] 时间列自 0:00 到次日 0:00 共 [HoursPerDay] + 1 个
 * 整点刻度（相邻间隔 [HourSpacing]），右侧为日程卡片区域——[DayTaskCard] 按任务时长
 * 比例排布（1 小时 = [HourSpacing]），由 [tasks] 传入当天任务，无任务时整块留白。
 *
 * 当前时间指示器（[nowMinute] 非 null 且不早于锚点时）：Error 色横线自时刻矩形右缘
 * 贯穿屏幕右缘（压过任务卡），时刻矩形（白字 Error 底）距屏幕左缘 [NowChipStartPadding]、
 * 文字居中，垂直对齐当前时刻的刻度中心。
 *
 * 网格线与课表页同款（1dp 实线、colorScheme.outline）：顶部/底部两条全宽横线 + 时间列
 * 右缘一条贯通竖线，无逐小时横线。首尾横线不压刻度——每个刻度落在 50dp 格的垂直中心，
 * 中心即该整点的时间位置，映射公式 y(t) = ((t − 锚点) / 60 + 0.5) × [HourSpacing]；
 * 卡片沿用同一映射：卡片顶边对齐其开始时刻的刻度中心、高度 = 时长/60 × [HourSpacing]，
 * 卡片区域起点 x = [TimeAreaWidth] + [TaskAreaStartGap]、右缘收进 [PageHorizontalSpacing]
 * （与其他日程卡同边距），区域内布局见 [solveDayTaskPlacements]。
 *
 * 网格锚点（首刻度）= 当天最早任务（[tasks]）开始时刻所在小时的整点（floor，如最早
 * 任务 7:30 → 7:00 起），不显示任务前的空档；无任务时恒为 0:00 起显示一整天；末刻度
 * 固定为次日 0:00。将来出现跨零点任务（> 24:00）再扩展末刻度与总高。
 */
@Composable
internal fun DayTimeline(
    modifier: Modifier = Modifier,
    tasks: List<DayTask> = emptyList(),
    nowMinute: Int? = null,
) {
    val outline = MaterialTheme.colorScheme.outline
    val secondaryText = MaterialTheme.classppColors.secondaryText
    // 锚点（首刻度）= 当天最早任务开始时刻所在小时的整点（floor，如最早任务 7:30 → 7:00）；
    // 无任务时恒为 0:00 起显示整天。末刻度固定为次日 0:00。
    val anchorHour = tasks.minOfOrNull { it.startMinute }?.div(60) ?: 0
    val anchorMinute = anchorHour * 60
    val hourLabels = remember(anchorHour) {
        List(HoursPerDay - anchorHour + 1) { index ->
            val hour = anchorHour + index
            // 末刻度为次日 0:00（TimeText.format 只收 0..1439，此处特判）
            if (hour == HoursPerDay) "0:00" else TimeText.format(hour * 60)
        }
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(HourSpacing * (HoursPerDay - anchorHour + 1))
            .drawBehind {
                val stroke = GridLineWidth.toPx()
                // 首尾横线全宽，笔迹整体落在容器内（与课表页首/末线同口径）
                drawLine(
                    outline,
                    Offset(0f, stroke / 2),
                    Offset(size.width, stroke / 2),
                    strokeWidth = stroke,
                )
                drawLine(
                    outline,
                    Offset(0f, size.height - stroke / 2),
                    Offset(size.width, size.height - stroke / 2),
                    strokeWidth = stroke,
                )
                // 时间列右缘竖线：贯通上下横线
                val x = TimeAreaWidth.toPx()
                drawLine(outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            },
    ) {
        hourLabels.forEachIndexed { index, label ->
            // 刻度盒 = 50dp 一格：文字水平居中于 44dp 时间列、垂直居中于本格，中心即
            // (锚点 + index) 的时间位置 —— y(t) = ((t − 锚点) / 60 + 0.5) × HourSpacing
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = HourSpacing * index)
                    .size(width = TimeAreaWidth, height = HourSpacing),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = secondaryText,
                    fontSize = TimeLabelFontSize,
                )
            }
        }
        if (tasks.isNotEmpty()) {
            // 卡片区域：网格竖线右侧留呼吸距、右缘与页面其他日程卡对齐；区域内按
            // 时长比例求解位置/宽度/接触角（重叠均分等规则见求解器 KDoc）
            val regionWidth = maxWidth - TimeAreaWidth - TaskAreaStartGap - PageHorizontalSpacing
            val placements = remember(tasks, regionWidth) {
                tasks.solveDayTaskPlacements(regionWidth, HourSpacing, anchorMinute)
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = TimeAreaWidth + TaskAreaStartGap, end = PageHorizontalSpacing),
            ) {
                placements.forEach { DayTaskCard(it) }
            }
        }
        // 当前时刻早于锚点（还没到当天第一个任务的小时）时不在时间轴范围内，不显示
        if (nowMinute != null && nowMinute >= anchorMinute) {
            // 当前时间指示器：Error 色横线自时刻矩形右缘起贯穿屏幕右缘（不外露矩形
            // 左侧，覆盖层压过任务卡与刻度），并向左多画 1dp 压进矩形底下保证接缝无隙；
            // 时刻矩形贴时间列左缘、定宽定高、文字水平垂直居中，垂直对齐当前时刻的
            // 刻度中心（映射同卡片）
            val error = MaterialTheme.colorScheme.error
            val onError = MaterialTheme.colorScheme.onError
            val nowY = HourSpacing * ((nowMinute - anchorMinute) / 60f + 0.5f)
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawLine(
                            error,
                            Offset(
                                (NowChipStartPadding + NowChipWidth - 1.dp).toPx(),
                                nowY.toPx(),
                            ),
                            Offset(size.width, nowY.toPx()),
                            strokeWidth = GridLineWidth.toPx(),
                        )
                    },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = NowChipStartPadding, y = nowY - NowChipHeight / 2)
                    .size(width = NowChipWidth, height = NowChipHeight)
                    .clip(DayTimelineNowChipShape)
                    .background(error),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = TimeText.format(nowMinute),
                    color = onError,
                    fontSize = NowChipFontSize,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ---------- 预览 ----------

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 900)
@Composable
private fun DayTimelinePreview() {
    ClassppTheme {
        // 最早任务 8:00 → 锚点 8:00（轴顶即 8:00 刻度），指示器 9:00 压过首卡
        DayTimeline(
            tasks = listOf(
                DayTask.Course(
                    name = "数据结构",
                    teacher = "陈锦秀",
                    location = "学武楼 A304",
                    note = "这是备注内容，这是备注内容",
                    color = CourseColor.Blue,
                    startMinute = 8 * 60,
                    endMinute = 9 * 60 + 40,
                ),
                DayTask.Todo(
                    id = "todo-1",
                    name = "待办标题待办标题待办标题",
                    completed = false,
                    deadlineText = "10/27 6:00",
                    deadlineColor = Color(0xFF006FEF),
                    onToggle = null,
                    startMinute = 9 * 60 + 40,
                    endMinute = 10 * 60,
                ),
            ),
            nowMinute = 9 * 60,
        )
    }
}
