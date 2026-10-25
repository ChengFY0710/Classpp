package com.fangyi.classpp.ui.components

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.ui.theme.ClassppTheme
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

/** 网格线笔宽：与课表页 CourseGrid 同款 */
private val GridLineWidth = 1.dp

/** 刻度字号（与 DayCourseAlert 次级信息一致） */
private val TimeLabelFontSize = 13.sp

/**
 * 日程页时间轴：左侧 [TimeAreaWidth] 时间列自 0:00 到次日 0:00 共 [HoursPerDay] + 1 个
 * 整点刻度（相邻间隔 [HourSpacing]），右侧为日程卡片区域——[DayTaskCard] 按任务时长
 * 比例排布（1 小时 = [HourSpacing]），由 [tasks] 传入当天任务，无任务时整块留白。
 *
 * 网格线与课表页同款（1dp 实线、colorScheme.outline）：顶部/底部两条全宽横线 + 时间列
 * 右缘一条贯通竖线，无逐小时横线。首尾横线不压刻度——每个刻度落在 50dp 格的垂直中心，
 * 中心即该整点的时间位置，映射公式 y(t) = (t/60 + 0.5) × [HourSpacing]；卡片沿用
 * 同一映射：卡片顶边对齐其开始时刻的刻度中心、高度 = 时长/60 × [HourSpacing]，卡片
 * 区域起点 x = [TimeAreaWidth] + [TaskAreaStartGap]、右缘收进 [PageHorizontalSpacing]
 * （与其他日程卡同边距），区域内布局见 [solveDayTaskPlacements]。
 *
 * 网格锚点取当天最早待办/课程开始时刻之前的零点——当天事件都在 0:00 之后，恒为 0:00 起
 * 显示一整天；无日程安排时照常显示整条。将来出现跨零点任务（> 24:00）再扩展锚点与总高。
 */
@Composable
internal fun DayTimeline(modifier: Modifier = Modifier, tasks: List<DayTask> = emptyList()) {
    val outline = MaterialTheme.colorScheme.outline
    val secondaryText = MaterialTheme.classppColors.secondaryText
    val hourLabels = remember {
        List(HoursPerDay + 1) { hour ->
            // 末刻度为次日 0:00（TimeText.format 只收 0..1439，此处特判）
            if (hour == HoursPerDay) "0:00" else TimeText.format(hour * 60)
        }
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(HourSpacing * (HoursPerDay + 1))
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
        hourLabels.forEachIndexed { hour, label ->
            // 刻度盒 = 50dp 一格：文字水平居中于 44dp 时间列、垂直居中于本格，
            // 中心即 (hour + 0.5) × HourSpacing —— 该整点的时间位置
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = HourSpacing * hour)
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
                tasks.solveDayTaskPlacements(regionWidth, HourSpacing)
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = TimeAreaWidth + TaskAreaStartGap, end = PageHorizontalSpacing),
            ) {
                placements.forEach { DayTaskCard(it) }
            }
        }
    }
}

// ---------- 预览 ----------

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 640)
@Composable
private fun DayTimelinePreview() {
    ClassppTheme {
        DayTimeline()
    }
}
