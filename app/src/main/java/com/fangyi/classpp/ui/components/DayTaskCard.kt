package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.schedule.CourseColor
import com.fangyi.classpp.ui.schedule.barColor
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.DayCourseAlertTagShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.dayTaskCardShape
import com.fangyi.classpp.ui.theme.isDarkTheme
import com.kyant.shapes.RoundedRectangle

/**
 * 日程页时间轴右侧的任务卡：课程（上课）或待办（待办），按任务时长比例排布在时间轴
 * 卡片区域（映射与区域起点见 [DayTimeline] KDoc）。两类卡片共用同一外壳与左侧时间列
 * （DayCourseAlert 右侧时间组的左置版），右内容列分道——课程沿用 DayCourseAlert 的
 * 信息行配色，待办沿用 NoteCard 的标题/属性行/勾选框样式。
 *
 * 布局规则：
 * - 高度按时间占比（映射与比例尺见 DayTimeline KDoc：1 小时 = 50dp），比例高度装不下
 *   竖排内容时以内容撑底（高度只增不减，顶边仍锚定开始时刻）；
 * - 比例高度 < [DayTaskCompactThreshold]（≈84 分钟）切紧凑单行（时间 + 标签 + 时间 +
 *   标题横排一行）；
 * - 相邻任务贴近（渲染间距 < 6dp，含贴背）时接触角收敛为 6dp，时间重叠的任务均分
 *   卡片区域宽度（列间 2dp），贴邻方向接触角同样收敛——圆角一律走
 *   [com.fangyi.classpp.ui.theme.dayTaskCardShape]。
 */
internal sealed interface DayTask {
    val startMinute: Int
    val endMinute: Int

    /** 上课：信息字段与 DayCourseAlert 一致（空白字段不出行） */
    data class Course(
        val name: String,
        val teacher: String?,
        val location: String?,
        val note: String?,
        val color: CourseColor,
        override val startMinute: Int,
        override val endMinute: Int,
    ) : DayTask

    /** 待办：本期只展示时间区间与截止时间（样式参考 NoteCard），[onToggle] 接完成勾选 */
    data class Todo(
        val id: String,
        val name: String,
        val completed: Boolean,
        val deadlineText: String?,
        val deadlineColor: Color,
        val onToggle: ((Boolean) -> Unit)?,
        override val startMinute: Int,
        override val endMinute: Int,
    ) : DayTask
}

/** 左侧标签文案：竖排时按字拆行（上/课、待/办） */
private val DayTask.tagLabel: String
    get() = when (this) {
        is DayTask.Course -> "上课"
        is DayTask.Todo -> "待办"
    }

/** 重叠分列时的列序：课程在左、待办在右（同型内按开始时间） */
private val DayTask.columnPriority: Int
    get() = when (this) {
        is DayTask.Course -> 0
        is DayTask.Todo -> 1
    }

// ---------- 布局求解：位置尺寸 + 接触角 + 形态（纯函数，可单测） ----------

/** 单张任务卡的求解结果；[offsetX] 相对卡片区域左缘、[offsetY] 相对时间轴顶部。
 *  四个接触标记 = 对应角与邻近任务贴近（渲染间距 < 6dp），渲染时收敛为 6dp 圆角 */
internal data class DayTaskPlacement(
    val task: DayTask,
    val offsetX: Dp,
    val offsetY: Dp,
    val width: Dp,
    val height: Dp,
    val topStartContact: Boolean,
    val topEndContact: Boolean,
    val bottomStartContact: Boolean,
    val bottomEndContact: Boolean,
    val compact: Boolean,
)

/** 重叠任务卡分列时的列间距（均分空间、间距 2dp） */
internal val DayTaskColumnGap = 2.dp

/** 紧凑/竖排分界：竖排布局（上下时间 + 拉伸标签）最小内容高约 70dp（≈84 分钟），
 *  比例高度不足即切紧凑单行——1 小时 = 50dp 比例尺下 60 分钟待办为紧凑形态 */
internal val DayTaskCompactThreshold = 70.dp

/** 紧凑单行的最小高度兜底（比例高度更小时不再压缩，保证一行文字的呼吸感） */
private val CompactMinHeight = 40.dp

/** 贴近判定阈值：相邻任务渲染间距（时间差 × 比例尺）小于该值 → 接触角收敛 6dp */
private val DayTaskContactGap = 6.dp

/**
 * 时间轴任务卡布局求解：重叠链分组 → 组内贪心分列 → 等宽均分 → 接触角标记。
 *
 * - 分组：按开始时间排序后，start < 组内最大 end 即入同组（贴背 start == end 不算
 *   重叠，另起一组）；
 * - 分列：新卡放进「最后一节结束 ≤ 自己开始」的第一个既有列，否则新列——同列内
 *   时间永远不重叠；列分配顺序课程优先于待办（重叠时上课在左、待办在右）；
 * - 均分：组内 n 列等宽 = (区域宽 − (n−1) × [DayTaskColumnGap]) / n；
 * - 横向接触：相邻列且时间重叠的两卡，起/末两侧整边四角收敛；
 * - 纵向接触：不重叠且渲染间距（时间差 × 比例尺）< [DayTaskContactGap]（含贴背）
 *   的上下卡全局配对（贴背任务分属不同重叠链，不能只在列内找），按横向半宽分角；
 * - 高度：竖排卡 = 比例高度（渲染时作 heightIn 下限，内容更高自然撑底）；紧凑卡 =
 *   max(比例高度, [CompactMinHeight])；
 * - 纵向原点：[anchorMinute]（时间轴首刻度，见 DayTimeline），卡片顶边对齐其开始
 *   时刻的刻度中心 y = ((start − anchor) / 60 + 0.5) × hourSpacing。
 */
internal fun List<DayTask>.solveDayTaskPlacements(
    regionWidth: Dp,
    hourSpacing: Dp,
    anchorMinute: Int = 0,
): List<DayTaskPlacement> {
    if (isEmpty() || regionWidth <= 0.dp) return emptyList()
    val order = withIndex().sortedWith(compareBy({ it.value.startMinute }, { it.value.endMinute }))

    // 重叠链分组（存下标，避免同字段待办在 map 里被值相等覆盖）
    val groups = mutableListOf<MutableList<Int>>()
    for ((index, task) in order) {
        val last = groups.lastOrNull()
        if (last != null && task.startMinute < last.maxOf { this[it].endMinute }) {
            last.add(index)
        } else {
            groups.add(mutableListOf(index))
        }
    }

    val results = arrayOfNulls<DayTaskPlacement>(size)
    for (group in groups) {
        // 列分配顺序：课程优先于待办（重叠分列时上课固定在左、待办在右），同型按开始时间
        group.sortWith(compareBy({ this[it].columnPriority }, { this[it].startMinute }))
        // 组内贪心分列：同列内时间不重叠（列内按 start 有序，末尾卡 end 即列占用下限）
        val columns = mutableListOf<MutableList<Int>>()
        val columnOf = IntArray(size) { -1 }
        for (index in group) {
            val task = this[index]
            val column = columns.indexOfFirst { this[it.last()].endMinute <= task.startMinute }
            if (column >= 0) columns[column].add(index) else columns.add(mutableListOf(index))
            columnOf[index] = if (column >= 0) column else columns.lastIndex
        }

        val columnCount = columns.size
        val columnWidth = ((regionWidth - DayTaskColumnGap * (columnCount - 1)) / columnCount)
            .coerceAtLeast(0.dp)
        for (columnIndex in columns.indices) {
            val column = columns[columnIndex]
            for (index in column) {
                val task = this[index]
                val proportionalHeight = hourSpacing * ((task.endMinute - task.startMinute) / 60f)
                val compact = proportionalHeight < DayTaskCompactThreshold
                // 横向贴邻：相邻列存在时间重叠的卡 → 起/末两侧整边收敛
                val touchesPrevious = columnIndex > 0 &&
                    columns[columnIndex - 1].any { overlapsInTime(this[it], task) }
                val touchesNext = columnIndex < columnCount - 1 &&
                    columns[columnIndex + 1].any { overlapsInTime(task, this[it]) }
                results[index] = DayTaskPlacement(
                    task = task,
                    offsetX = (columnWidth + DayTaskColumnGap) * columnOf[index],
                    offsetY = hourSpacing * ((task.startMinute - anchorMinute) / 60f + 0.5f),
                    width = columnWidth,
                    height = if (compact) maxOf(proportionalHeight, CompactMinHeight) else proportionalHeight,
                    topStartContact = touchesPrevious,
                    topEndContact = touchesNext,
                    bottomStartContact = touchesPrevious,
                    bottomEndContact = touchesNext,
                    compact = compact,
                )
            }
        }
    }

    // 纵向接触全局配对：上卡 end ≤ 下卡 start 且渲染间距 < [DayTaskContactGap]，
    // 按横向半宽分角——下卡压上卡左半 → 上卡 bottomStart / 下卡 topStart，右半同理
    val solved = List(size) { requireNotNull(results[it]) }.toMutableList()
    for (i in solved.indices) {
        for (j in solved.indices) {
            if (i == j) continue
            val upper = solved[i]
            val lower = solved[j]
            if (upper.task.endMinute > lower.task.startMinute) continue
            if (hourSpacing * ((lower.task.startMinute - upper.task.endMinute) / 60f) >=
                DayTaskContactGap
            ) {
                continue
            }
            val upperLeft = upper.offsetX
            val upperMid = upper.offsetX + upper.width / 2f
            val upperRight = upper.offsetX + upper.width
            val lowerLeft = lower.offsetX
            val lowerMid = lower.offsetX + lower.width / 2f
            val lowerRight = lower.offsetX + lower.width
            if (xRangesIntersect(lowerLeft, lowerRight, upperLeft, upperMid)) {
                solved[i] = solved[i].copy(bottomStartContact = true)
            }
            if (xRangesIntersect(lowerLeft, lowerRight, upperMid, upperRight)) {
                solved[i] = solved[i].copy(bottomEndContact = true)
            }
            if (xRangesIntersect(upperLeft, upperRight, lowerLeft, lowerMid)) {
                solved[j] = solved[j].copy(topStartContact = true)
            }
            if (xRangesIntersect(upperLeft, upperRight, lowerMid, lowerRight)) {
                solved[j] = solved[j].copy(topEndContact = true)
            }
        }
    }
    return solved
}

/** 两段横向范围是否有共存（开区间——仅边线相贴不算，接触角需要共享一段宽度） */
private fun xRangesIntersect(aStart: Dp, aEnd: Dp, bStart: Dp, bEnd: Dp): Boolean =
    aStart < bEnd && bStart < aEnd

/** 两任务时间区间是否重叠（贴背不算） */
private fun overlapsInTime(a: DayTask, b: DayTask): Boolean =
    a.startMinute < b.endMinute && b.startMinute < a.endMinute

// ---------- 渲染 ----------

/**
 * 单张任务卡：位置尺寸由 [placement] 给出（由 DayTimeline 在卡片区域放置），高度作
 * heightIn 下限——内容更高的竖排卡自然撑底（顶边仍锚定开始时刻）。圆角按接触标记
 * 逐角取 20dp/6dp（theme 的平滑圆角），贴背/重叠时邻角收敛。
 */
@Composable
internal fun DayTaskCard(placement: DayTaskPlacement, modifier: Modifier = Modifier) {
    val task = placement.task
    val accent = when (task) {
        is DayTask.Course -> task.color.barColor(MaterialTheme.isDarkTheme)
        is DayTask.Todo -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .offset(x = placement.offsetX, y = placement.offsetY)
            .width(placement.width)
            .heightIn(min = placement.height)
            .clip(
                dayTaskCardShape(
                    topStartContact = placement.topStartContact,
                    topEndContact = placement.topEndContact,
                    bottomStartContact = placement.bottomStartContact,
                    bottomEndContact = placement.bottomEndContact,
                ),
            )
            .background(MaterialTheme.colorScheme.surface),
        propagateMinConstraints = true,
    ) {
        if (placement.compact) {
            CompactTaskContent(task, accent)
        } else {
            VerticalTaskContent(task, accent)
        }
    }
}

/** 竖排布局（≥ [DayTaskCompactThreshold]）：左侧时间列 + 右内容列（垂直居中） */
@Composable
private fun VerticalTaskContent(task: DayTask, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = TaskCardPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(TaskContentTimeGap),
        // 满高子项（时间列/内容列）不受对齐影响，待办勾选框借此垂直居中
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左时间列（DayCourseAlert 右侧时间组左置版）：时间距卡上下缘恒定 2dp，
        // 标签 weight(1f) 撑满两时间之间，自身高度完全由卡片高度决定
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(IntrinsicSize.Max)
                .padding(vertical = TaskTimeEdgeInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TaskTagGap),
        ) {
            TaskTimeText(TimeText.format(task.startMinute), accent)
            TaskTagBox(
                label = task.tagLabel,
                accent = accent,
                stretch = true,
                vertical = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            TaskTimeText(TimeText.format(task.endMinute), accent)
        }
        when (task) {
            is DayTask.Course -> VerticalCourseContent(task, Modifier.weight(1f))
            is DayTask.Todo -> {
                VerticalTodoContent(task, Modifier.weight(1f))
                TaskTodoCheckbox(checked = task.completed, onToggle = task.onToggle)
            }
        }
    }
}

/** 课程内容（DayCourseAlert 配色：标题 onSurface、教师/地点 secondaryText、备注 primary） */
@Composable
private fun VerticalCourseContent(task: DayTask.Course, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = TaskContentPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(TaskLineSpacing, Alignment.CenterVertically),
    ) {
        Text(
            text = task.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = TaskTitleFontSize,
            lineHeight = TaskTitleLineHeight,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        task.teacher?.let { TaskInfoText(it, MaterialTheme.classppColors.secondaryText) }
        task.location?.let { TaskInfoText(it, MaterialTheme.classppColors.secondaryText) }
        task.note?.let { TaskInfoText("备注：$it", MaterialTheme.colorScheme.primary) }
    }
}

/** 待办内容（NoteCard 样式：fieldLabel 标题 + 时间/截止属性行；本期不展示标签/重复等） */
@Composable
private fun VerticalTodoContent(task: DayTask.Todo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = TaskContentPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(TaskTitlePropertySpacing, Alignment.CenterVertically),
    ) {
        Text(
            text = task.name,
            style = MaterialTheme.classppTextStyles.fieldLabel.copy(
                color = if (task.completed) {
                    MaterialTheme.classppColors.secondaryText
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
            textDecoration = if (task.completed) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // 重叠分列后卡可能只剩一半宽：时刻单行省略，避免逐字折行（图 5 同款截断）
            TimeProperty(
                icon = R.drawable.ic_clock,
                text = "${TimeText.format(task.startMinute)} - ${TimeText.format(task.endMinute)}",
                tint = MaterialTheme.classppColors.secondaryText,
                modifier = Modifier.padding(end = TaskPropertyItemEndPadding),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            task.deadlineText?.let { deadline ->
                TimeProperty(
                    icon = R.drawable.ic_clock_alarm,
                    text = deadline,
                    tint = task.deadlineColor,
                    modifier = Modifier.padding(end = TaskPropertyItemEndPadding),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 紧凑单行布局（< [DayTaskCompactThreshold]）：时间 + 标签 + 时间 + 标题横排一行 */
@Composable
private fun CompactTaskContent(task: DayTask, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TaskCardPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskTimeText(TimeText.format(task.startMinute), accent)
        Spacer(Modifier.width(TaskTimeTagGap))
        TaskTagBox(
            label = task.tagLabel,
            accent = accent,
            stretch = false,
            vertical = false,
        )
        Spacer(Modifier.width(TaskTimeTagGap))
        TaskTimeText(TimeText.format(task.endMinute), accent)
        Spacer(Modifier.width(TaskContentTimeGap))
        when (task) {
            is DayTask.Course -> CompactTaskTitle(
                title = task.name,
                completed = false,
                modifier = Modifier.weight(1f),
            )
            is DayTask.Todo -> {
                CompactTaskTitle(
                    title = task.name,
                    completed = task.completed,
                    modifier = Modifier.weight(1f),
                )
                TaskTodoCheckbox(checked = task.completed, onToggle = task.onToggle)
            }
        }
    }
}

/** 紧凑单行的标题：fieldLabel 半粗，待办完成态置灰划线 */
@Composable
private fun CompactTaskTitle(title: String, completed: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier,
        style = MaterialTheme.classppTextStyles.fieldLabel.copy(
            color = if (completed) {
                MaterialTheme.classppColors.secondaryText
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        textDecoration = if (completed) TextDecoration.LineThrough else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 「上课/待办」标签：底色 accent 25% 透明 + 文字降明度（DayCourseAlert TagBox 同款）；
 *  [stretch] = 竖列中间档撑满给定约束（水平内边距归零），[vertical] = 文字按字竖排 */
@Composable
private fun TaskTagBox(
    label: String,
    accent: Color,
    stretch: Boolean,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(accent.copy(alpha = TaskTagBackgroundAlpha), DayCourseAlertTagShape)
            .padding(horizontal = if (stretch) 0.dp else TaskTagPaddingHorizontal),
        contentAlignment = Alignment.Center,
    ) {
        // 深浅各自降明度（同 DayCourseAlert：深色课程色本就更深一档，沿用浅色系数会闷进标签底色）
        val dimFactor =
            if (MaterialTheme.isDarkTheme) TaskTagTextDimFactorDark else TaskTagTextDimFactorLight
        val tagColor = accent.darkened(dimFactor)
        if (vertical) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TaskTagText(label.substring(0, 1), tagColor)
                TaskTagText(label.substring(1, 2), tagColor)
            }
        } else {
            TaskTagText(label, tagColor)
        }
    }
}

/** 左列上下时间：13sp Medium，accent 色（DayCourseAlert TimeText 同款） */
@Composable
private fun TaskTimeText(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = TaskTimeFontSize,
        lineHeight = TaskTimeLineHeight,
        fontWeight = FontWeight.Medium,
    )
}

/** 标签文字：11sp Medium，降明度 accent（DayCourseAlert TagText 同款） */
@Composable
private fun TaskTagText(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = TaskTagFontSize,
        lineHeight = TaskTagLineHeight,
        fontWeight = FontWeight.Medium,
    )
}

/** 课程信息行（教师/地点/备注）：13sp，颜色由调用方给；单行省略（重叠窄卡不撑高） */
@Composable
private fun TaskInfoText(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = TaskInfoFontSize,
        lineHeight = TaskInfoLineHeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 待办勾选框（NoteCard 同款）：36dp 热区 + M3 Checkbox（primary），按压反馈代涟漪 */
@Composable
private fun TaskTodoCheckbox(
    checked: Boolean,
    onToggle: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .then(
                if (onToggle != null) {
                    Modifier
                        .pressFeedback(press, RoundedRectangle(6.dp))
                        .clip(RoundedRectangle(6.dp))
                        .toggleable(
                            value = checked,
                            interactionSource = press,
                            indication = null,
                            onValueChange = onToggle,
                            role = Role.Checkbox,
                        )
                } else {
                    Modifier
                },
            )
            .height(36.dp)
            .width(36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null, // 事件交给外层热区，这里固定 null
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}

// ---------- 视觉规格（左时间列/标签与 DayCourseAlert 右侧时间组同款，属性行与 NoteCard 同款） ----------

private val TaskCardPaddingHorizontal = 12.dp

/** 竖排内容列上下呼吸距：比例高度装不下内容、卡片撑底扩展时保证文字不贴卡缘 */
private val TaskContentPaddingVertical = 10.dp

/** 左时间列与右内容列的间距 */
private val TaskContentTimeGap = 12.dp

/** 竖排：上下时间距卡片上下缘的距离（恒定，高度变化全部由标签吸收） */
private val TaskTimeEdgeInset = 2.dp

/** 竖排：时间与标签的间距 */
private val TaskTagGap = 3.dp

/** 紧凑单行：时间与标签的间距（DayCourseAlert 单行卡同款） */
private val TaskTimeTagGap = 6.dp

/** 课程信息行间距（DayCourseAlert LineSpacing 同款） */
private val TaskLineSpacing = 3.dp

/** 待办标题与属性行间距（NoteCard TitlePropertySpacing 同款） */
private val TaskTitlePropertySpacing = 2.dp

/** 待办属性行内时间项与下一项的间距（NoteCard 4dp FlowRow 间距 + 8dp 末位内距 = 12dp） */
private val TaskPropertyItemEndPadding = 8.dp

/** 标签底色透明度：accent 25%（DayCourseAlert 同款设计巧思） */
private const val TaskTagBackgroundAlpha = 0.25f

/** 标签文字相对 accent 的降明度系数（RGB 同比）·浅色主题 */
private const val TaskTagTextDimFactorLight = 0.5f

/** 同上 ·深色主题：系数放缓避免文字闷进 25% 透明的标签底色（DayCourseAlert 同款） */
private const val TaskTagTextDimFactorDark = 0.95f

private val TaskTagPaddingHorizontal = 5.dp

private val TaskTitleFontSize = 16.sp
private val TaskTitleLineHeight = 20.sp
private val TaskInfoFontSize = 13.sp
private val TaskInfoLineHeight = 18.sp
private val TaskTimeFontSize = 13.sp
private val TaskTimeLineHeight = 20.sp
private val TaskTagFontSize = 11.sp
private val TaskTagLineHeight = 20.sp

// ---------- 预览 ----------

/** 设计稿同款课程色 */
private val PreviewCourseColor = CourseColor.Blue

/** 设计稿同款截止色（primary 蓝） */
private val PreviewDeadlineColor = Color(0xFF006FEF)

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 900)
@Composable
private fun DayTaskCardPreview() {
    ClassppTheme {
        Box(Modifier.fillMaxSize()) {
            // 区域宽 = 390 − 时间列 44 − 竖线距 12 − 右缘页边距 12（与 DayTimeline 同口径）
            val placements = remember {
                listOf(
                    DayTask.Course(
                        name = "数据结构",
                        teacher = "陈锦秀",
                        location = "学武楼 A304",
                        note = "这是备注内容，这是备注内容",
                        color = PreviewCourseColor,
                        startMinute = 8 * 60,
                        endMinute = 9 * 60 + 40,
                    ),
                    // 贴背：与上卡接触角收敛 6dp
                    DayTask.Todo(
                        id = "todo-1",
                        name = "待办标题待办标题待办标题",
                        completed = false,
                        deadlineText = "10/27 6:00",
                        deadlineColor = PreviewDeadlineColor,
                        onToggle = null,
                        startMinute = 9 * 60 + 40,
                        endMinute = 10 * 60,
                    ),
                    // 10 分钟间距（8.3dp > 6dp）：圆角保持 20dp
                    DayTask.Course(
                        name = "计算机科学与技术导论",
                        teacher = "陈锦秀",
                        location = "学武楼 A304",
                        note = null,
                        color = CourseColor.Purple,
                        startMinute = 10 * 60 + 10,
                        endMinute = 11 * 60 + 50,
                    ),
                    // 60 分钟：紧凑单行
                    DayTask.Todo(
                        id = "todo-2",
                        name = "待办标题待办标题待办标题",
                        completed = false,
                        deadlineText = "10/27 6:00",
                        deadlineColor = PreviewDeadlineColor,
                        onToggle = null,
                        startMinute = 13 * 60,
                        endMinute = 14 * 60,
                    ),
                    // 时间重叠：与下卡均分宽度、2dp 间距、贴邻角收敛
                    DayTask.Course(
                        name = "形势与政策",
                        teacher = "陈锦秀",
                        location = "学武楼 A304",
                        note = null,
                        color = CourseColor.Coral,
                        startMinute = 14 * 60 + 30,
                        endMinute = 16 * 60 + 10,
                    ),
                    DayTask.Todo(
                        id = "todo-3",
                        name = "待办标题待办标题待办标题",
                        completed = true,
                        deadlineText = "10/27 6:00",
                        deadlineColor = PreviewDeadlineColor,
                        onToggle = null,
                        startMinute = 15 * 60,
                        endMinute = 15 * 60 + 30,
                    ),
                )
            }.solveDayTaskPlacements(regionWidth = 322.dp, hourSpacing = 50.dp)
            Box(Modifier.padding(start = 56.dp, end = 12.dp)) {
                placements.forEach { DayTaskCard(it) }
            }
        }
    }
}
