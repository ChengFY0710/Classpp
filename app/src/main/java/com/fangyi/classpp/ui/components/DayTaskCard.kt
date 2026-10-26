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
import com.fangyi.classpp.ui.motion.pressClickable
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
 * - 高度按时间占比（映射与比例尺见 DayTimeline KDoc：基础 1 小时 = 50dp），比例高度
 *   装不下竖排内容时以内容最小高度撑底（顶边仍锚定开始时刻）；
 * - 同一横向范围内上下相邻卡至少留 2dp，时间贴背恰好 2dp；空间不足时由
 *   [solveDayTaskLayout] 在冲突时刻插入额外轴空间（左侧整点跨度局部变大）；
 * - 比例高度 < [DayTaskCompactThreshold]（≈84 分钟）切紧凑单行（时间 + 标签 + 时间 +
 *   标题横排一行）；
 * - 相邻任务贴近（渲染间距 < 6dp，含贴背）时接触角收敛为 6dp，时间重叠的任务均分
 *   卡片区域宽度（列间 2dp），贴邻方向接触角同样收敛——圆角一律走
 *   [com.fangyi.classpp.ui.theme.dayTaskCardShape]。
 */
internal sealed interface DayTask {
    val startMinute: Int
    val endMinute: Int

    /** 上课：信息字段与 DayCourseAlert 一致（空白字段不出行）；[onClick] 接课程详情
     *  浮层的唤起（整卡点击，null = 不可点） */
    data class Course(
        val name: String,
        val teacher: String?,
        val location: String?,
        val note: String?,
        val color: CourseColor,
        override val startMinute: Int,
        override val endMinute: Int,
        val onClick: (() -> Unit)? = null,
    ) : DayTask

    /** 待办：本期只展示时间区间与截止时间（样式参考 NoteCard），[onToggle] 接完成勾选；
     *  [onClick] 接待办详情浮层的唤起（整卡点击，null = 不可点，勾选框不受影响） */
    data class Todo(
        val id: String,
        val name: String,
        val completed: Boolean,
        val deadlineText: String?,
        val deadlineColor: Color,
        val onToggle: ((Boolean) -> Unit)?,
        override val startMinute: Int,
        override val endMinute: Int,
        val onClick: (() -> Unit)? = null,
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

/** 上下相邻任务卡的最小纵向间距；时间贴背的任务恰好保持该间距 */
internal val DayTaskVerticalGap = 2.dp

/** 某一时刻起插入的额外轴空间：整条时间轴从 [minute] 起再向下平移 [extra] */
internal data class DayTaskPush(val minute: Int, val extra: Dp)

/**
 * 时间轴布局结果：[yAt] 是时间→纵坐标的完整映射（基础比例 + 局部拉伸），[pushes]
 * 记录每次局部拉伸，[placements] 与输入任务同序。
 */
internal data class DayTaskLayout(
    val hourSpacing: Dp,
    val anchorMinute: Int,
    val placements: List<DayTaskPlacement>,
    val pushes: List<DayTaskPush>,
) {
    /** ≤ [minute] 的累计额外空间：卡片顶边、整点刻度、当前时刻线使用 */
    fun extraAt(minute: Int): Dp = pushes.extraAt(minute)

    fun yAt(minute: Int): Dp =
        hourSpacing * ((minute - anchorMinute) / 60f + 0.5f) + extraAt(minute)
}

/** 紧凑/竖排分界：竖排布局（上下时间 + 拉伸标签）最小内容高约 70dp（≈84 分钟），
 *  比例高度不足即切紧凑单行——1 小时 = 50dp 比例尺下 60 分钟待办为紧凑形态 */
internal val DayTaskCompactThreshold = 70.dp

/** 紧凑单行的最小高度兜底（比例高度更小时不再压缩，保证一行文字的呼吸感） */
private val CompactMinHeight = 40.dp

/** 贴近判定阈值：上下卡实际渲染间距小于该值 → 接触角收敛 6dp（贴背 2dp 也在此列） */
private val DayTaskContactGap = 6.dp

/**
 * 时间轴任务卡布局求解：重叠链分组 → 组内贪心分列 → 等宽均分 → 纵向排布 → 接触角标记。
 *
 * - 分组/分列/均分/横向接触：与旧版一致（贴背不算重叠，课程列优先，列间 2dp）；
 * - 纵向排布：保持 1 小时 = [hourSpacing] 的基础比例，按开始时间顺序放置；同一横向
 *   范围（x 区间严格相交）内、时间不重叠的上下卡之间至少留 [DayTaskVerticalGap]，
 *   不足时在下方卡开始时刻插入额外轴空间（[DayTaskPush]），因此整点刻度跨度会按需
 *   局部变大——贴背任务恰好 2dp，未受影响的小时仍为 [hourSpacing]；
 * - 高度：先取 max(比例高度, 内容最小高度)，再把卡片时间区间内插入的额外空间计入，
 *   保证卡片仍覆盖被拉伸的时间段（结束边界处的插入留给下卡间距，不计入本卡）；
 * - 接触角：上下卡实际渲染间距 < [DayTaskContactGap] 时收敛 6dp（含贴背 2dp）；
 * - 纵向原点：[anchorMinute]（时间轴首刻度），卡片顶边 = y(start) =
 *   [hourSpacing] × ((start − anchor) / 60 + 0.5) + 已插入的累计额外空间。
 */
internal fun List<DayTask>.solveDayTaskLayout(
    regionWidth: Dp,
    hourSpacing: Dp,
    anchorMinute: Int = 0,
    fontScale: Float = 1f,
): DayTaskLayout {
    if (isEmpty() || regionWidth <= 0.dp) {
        return DayTaskLayout(hourSpacing, anchorMinute, emptyList(), emptyList())
    }
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

    // 分列：列分配顺序课程优先于待办（重叠分列时上课固定在左、待办在右），同型按开始
    // 时间；同列内时间不重叠（列内按 start 有序，末尾卡 end 即列占用下限）
    val columnInfo = arrayOfNulls<DayTaskColumnInfo>(size)
    val startContact = BooleanArray(size)
    val endContact = BooleanArray(size)
    for (group in groups) {
        group.sortWith(compareBy({ this[it].columnPriority }, { this[it].startMinute }))
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
                // 横向贴邻：相邻列存在时间重叠的卡 → 起/末两侧整边收敛
                startContact[index] = columnIndex > 0 &&
                    columns[columnIndex - 1].any { overlapsInTime(this[it], task) }
                endContact[index] = columnIndex < columnCount - 1 &&
                    columns[columnIndex + 1].any { overlapsInTime(task, this[it]) }
                columnInfo[index] = DayTaskColumnInfo(
                    offsetX = (columnWidth + DayTaskColumnGap) * columnOf[index],
                    width = columnWidth,
                )
            }
        }
    }

    // 形态与高度基准：紧凑判定沿用基础比例尺（1 小时 = hourSpacing）；内容最小高度
    // 把渲染时 heightIn 的撑高量提前纳入布局，避免内容压住下卡。[baseHeights] 会随
    // 卡内插入的空间增长（见下方局部拉伸）
    val compact = BooleanArray(size)
    val baseHeights = arrayOfNulls<Dp>(size)
    val contentMins = arrayOfNulls<Dp>(size)
    for (index in indices) {
        val task = this[index]
        val proportionalHeight = hourSpacing * ((task.endMinute - task.startMinute) / 60f)
        compact[index] = proportionalHeight < DayTaskCompactThreshold
        baseHeights[index] = proportionalHeight
        contentMins[index] = dayTaskContentMinHeight(task, compact[index], fontScale)
    }

    // 纵向排布：按开始时间分组，同组卡片顶边对齐。每放置一组前，先看所有已放置且
    // 横向相交、时间已结束的卡；不足 2dp 时在组开始时刻插入额外空间（时间轴局部拉伸）
    val sortedIndices = indices.sortedWith(
        compareBy({ this[it].startMinute }, { this[it].endMinute }),
    )
    val tops = arrayOfNulls<Dp>(size)
    val pushes = mutableListOf<DayTaskPush>()
    val placed = mutableListOf<Int>()
    var extra = 0.dp
    var groupStart = 0
    while (groupStart < sortedIndices.size) {
        val start = this[sortedIndices[groupStart]].startMinute
        var groupEnd = groupStart
        while (groupEnd < sortedIndices.size &&
            this[sortedIndices[groupEnd]].startMinute == start
        ) {
            groupEnd++
        }
        val baseTop = hourSpacing * ((start - anchorMinute) / 60f + 0.5f) + extra
        var top = baseTop
        for (i in groupStart until groupEnd) {
            val info = requireNotNull(columnInfo[sortedIndices[i]])
            for (placedIndex in placed) {
                if (this[placedIndex].endMinute > start) continue
                val placedInfo = requireNotNull(columnInfo[placedIndex])
                if (!xRangesIntersect(
                        info.offsetX,
                        info.offsetX + info.width,
                        placedInfo.offsetX,
                        placedInfo.offsetX + placedInfo.width,
                    )
                ) {
                    continue
                }
                top = maxOf(
                    top,
                    requireNotNull(tops[placedIndex]) +
                        maxOf(
                            requireNotNull(baseHeights[placedIndex]),
                            requireNotNull(contentMins[placedIndex]),
                        ) +
                        DayTaskVerticalGap,
                )
            }
        }
        if (top > baseTop) {
            val inserted = top - baseTop
            extra += inserted
            pushes.add(DayTaskPush(start, extra))
            // 时间轴从这里整体下移：正在跨越该时刻的卡片同步加高，保持覆盖自己的
            // 时间段；结束边界上的插入不算卡内（留给下卡间距）
            for (placedIndex in placed) {
                val placedTask = this[placedIndex]
                if (placedTask.startMinute < start && start < placedTask.endMinute) {
                    baseHeights[placedIndex] =
                        requireNotNull(baseHeights[placedIndex]) + inserted
                }
            }
        }
        for (i in groupStart until groupEnd) {
            tops[sortedIndices[i]] = top
        }
        placed.addAll(sortedIndices.subList(groupStart, groupEnd))
        groupStart = groupEnd
    }

    // 最终高度：比例高度 + 本卡时间区间内插入的空间（不含结束边界，边界空间留给下卡
    // 间距），并保证不低于内容最小高度
    val results = arrayOfNulls<DayTaskPlacement>(size)
    for (index in indices) {
        val task = this[index]
        val info = requireNotNull(columnInfo[index])
        results[index] = DayTaskPlacement(
            task = task,
            offsetX = info.offsetX,
            offsetY = requireNotNull(tops[index]),
            width = info.width,
            height = maxOf(
                requireNotNull(baseHeights[index]),
                requireNotNull(contentMins[index]),
            ),
            topStartContact = startContact[index],
            topEndContact = endContact[index],
            bottomStartContact = startContact[index],
            bottomEndContact = endContact[index],
            compact = compact[index],
        )
    }

    // 纵向接触全局配对：上卡 end ≤ 下卡 start，按实际渲染间距判定（含贴背 2dp），
    // 按横向半宽分角——下卡压上卡左半 → 上卡 bottomStart / 下卡 topStart，右半同理
    val solved = List(size) { requireNotNull(results[it]) }.toMutableList()
    for (i in solved.indices) {
        for (j in solved.indices) {
            if (i == j) continue
            val upper = solved[i]
            val lower = solved[j]
            if (upper.task.endMinute > lower.task.startMinute) continue
            if (lower.offsetY - (upper.offsetY + upper.height) >= DayTaskContactGap) continue
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
    return DayTaskLayout(hourSpacing, anchorMinute, solved, pushes.toList())
}

/** 旧入口：只取布局结果中的卡片位置（预览与既有单测使用） */
internal fun List<DayTask>.solveDayTaskPlacements(
    regionWidth: Dp,
    hourSpacing: Dp,
    anchorMinute: Int = 0,
    fontScale: Float = 1f,
): List<DayTaskPlacement> =
    solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute, fontScale).placements

/** 两段横向范围是否有共存（开区间——仅边线相贴不算，接触角需要共享一段宽度） */
private fun xRangesIntersect(aStart: Dp, aEnd: Dp, bStart: Dp, bEnd: Dp): Boolean =
    aStart < bEnd && bStart < aEnd

/** 两任务时间区间是否重叠（贴背不算） */
private fun overlapsInTime(a: DayTask, b: DayTask): Boolean =
    a.startMinute < b.endMinute && b.startMinute < a.endMinute

/** 卡片在横向上的分列结果 */
private data class DayTaskColumnInfo(
    val offsetX: Dp,
    val width: Dp,
)

/** ≤ [minute] 的累计额外空间（[pushes] 按分钟升序） */
private fun List<DayTaskPush>.extraAt(minute: Int): Dp =
    lastOrNull { it.minute <= minute }?.extra ?: 0.dp

/**
 * 卡片内容的最小高度：镜像 [VerticalTaskContent]/[CompactTaskContent] 的排版常量，
 * 让布局在渲染前就知道内容需要的高度（渲染时的 heightIn 只作兜底）。sp 按 [fontScale]
 * 换算成 dp。
 */
private fun dayTaskContentMinHeight(task: DayTask, compact: Boolean, fontScale: Float): Dp {
    val titleLine = (TaskTitleLineHeight.value * fontScale).dp
    val timeLine = (TaskTimeLineHeight.value * fontScale).dp
    val tagLine = (TaskTagLineHeight.value * fontScale).dp
    if (compact) {
        return maxOf(
            CompactMinHeight,
            timeLine,
            tagLine + TaskTagPaddingVertical * 2,
            titleLine,
            TaskTodoCheckboxSize,
        )
    }
    // 左侧时间列：上下时间 + 两行标签 + 2dp 上下内距与 3dp 间距
    val timeColumn = TaskTimeEdgeInset * 2 +
        timeLine * 2 +
        TaskTagGap * 2 +
        tagLine * 2
    val right = when (task) {
        is DayTask.Course -> {
            val infoCount = listOfNotNull(task.teacher, task.location, task.note).size
            TaskContentPaddingVertical * 2 +
                titleLine +
                ((TaskInfoLineHeight.value * fontScale).dp + TaskLineSpacing) * infoCount
        }
        is DayTask.Todo -> {
            // 属性行（时间 + 截止）在窄列里最多两项、可能折成两行
            val rows = if (task.deadlineText != null) 2 else 1
            val rowHeight = maxOf(
                TaskPropertyIconSize,
                (TaskPropertyLineHeight.value * fontScale).dp,
            )
            TaskContentPaddingVertical * 2 +
                titleLine +
                TaskTitlePropertySpacing +
                rowHeight * rows +
                TaskPropertyRowGap * (rows - 1)
        }
    }
    return maxOf(timeColumn, right, TaskTodoCheckboxSize)
}

// ---------- 渲染 ----------

/**
 * 单张任务卡：位置尺寸由 [placement] 给出（由 DayTimeline 在卡片区域放置），高度作
 * heightIn 下限——内容更高的竖排卡自然撑底（顶边仍锚定开始时刻）。圆角按接触标记
 * 逐角取 20dp/6dp（theme 的平滑圆角），贴背/重叠时邻角收敛。
 *
 * 两型卡片带 [DayTask.Course.onClick] / [DayTask.Todo.onClick] 时整卡可点（按压反馈形状
 * 同卡壳圆角，含收敛的接触角），宿主分别用它唤起课程/待办详情浮层；待办勾选框仍是
 * 独立交互位（子级优先消费点击，按压反馈互不串扰），无回调的卡不可点。
 */
@Composable
internal fun DayTaskCard(placement: DayTaskPlacement, modifier: Modifier = Modifier) {
    val task = placement.task
    val accent = when (task) {
        is DayTask.Course -> task.color.barColor(MaterialTheme.isDarkTheme)
        is DayTask.Todo -> MaterialTheme.colorScheme.primary
    }
    val cardShape = dayTaskCardShape(
        topStartContact = placement.topStartContact,
        topEndContact = placement.topEndContact,
        bottomStartContact = placement.bottomStartContact,
        bottomEndContact = placement.bottomEndContact,
    )
    val cardClick = when (task) {
        is DayTask.Course -> task.onClick
        is DayTask.Todo -> task.onClick
    }
    Box(
        modifier = modifier
            .offset(x = placement.offsetX, y = placement.offsetY)
            .width(placement.width)
            .heightIn(min = placement.height)
            .then(
                if (cardClick != null) {
                    Modifier.pressClickable(shape = cardShape) { cardClick() }
                } else {
                    Modifier
                },
            )
            .clip(cardShape)
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
                backgroundAlpha = if (task is DayTask.Course) {
                    TaskCourseTagBackgroundAlpha
                } else {
                    TaskTodoTagBackgroundAlpha
                },
                dimText = task is DayTask.Course,
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
                lineHeight = TaskTitleLineHeight,
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
            backgroundAlpha = if (task is DayTask.Course) {
                TaskCourseTagBackgroundAlpha
            } else {
                TaskTodoTagBackgroundAlpha
            },
            dimText = task is DayTask.Course,
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
            fontSize = TaskTitleFontSize,
            color = if (completed) {
                MaterialTheme.classppColors.secondaryText
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            lineHeight = TaskTitleLineHeight,
        ),
        textDecoration = if (completed) TextDecoration.LineThrough else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 「上课/待办」标签：底色 accent [backgroundAlpha] 透明；[dimText] = 文字相对 accent
 *  降明度（课程色为马卡龙浅色需要压深，深浅各一套系数）——待办标签的 accent 本身就是
 *  Primary，传 false 直接用原色 */
@Composable
private fun TaskTagBox(
    label: String,
    accent: Color,
    backgroundAlpha: Float,
    dimText: Boolean,
    stretch: Boolean,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(accent.copy(alpha = backgroundAlpha), DayCourseAlertTagShape)
            .padding(
                horizontal = if (stretch) 0.dp else TaskTagPaddingHorizontal,
                // 上下内距即横向紧凑标签的高度旋钮（高度 = 文字行高 + 上下内距 × 2）；
                // 拉伸态（竖排卡）标签高度由时间列 weight 分配，内距归零
                vertical = if (stretch) 0.dp else TaskTagPaddingVertical,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 深浅各自降明度（同 DayCourseAlert：深色课程色本就更深一档，沿用浅色系数会闷进标签底色）
        val dimFactor =
            if (MaterialTheme.isDarkTheme) TaskTagTextDimFactorDark else TaskTagTextDimFactorLight
        val tagColor = if (dimText) accent.darkened(dimFactor) else accent
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
            .height(TaskTodoCheckboxSize)
            .width(TaskTodoCheckboxSize),
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

/** 待办勾选框热区：方径见 [TaskTodoCheckboxSize]（NoteCard 同款 36dp），M3 Checkbox 居中其中 */
private val TaskTodoCheckboxSize = 36.dp

/** 待办属性行（TimeProperty）的图标尺寸与保守行高：用于内容最小高度估算 */
private val TaskPropertyIconSize = 16.dp
private val TaskPropertyLineHeight = 18.sp

/** 待办属性行换行时的行间距（FlowRow verticalArrangement = 3dp） */
private val TaskPropertyRowGap = 3.dp

/** 标签底色透明度：课程色 25%（DayCourseAlert 同款设计巧思）；待办取 Primary 10%
 *  （NoteCard TagChip 同款） */
private const val TaskCourseTagBackgroundAlpha = 0.25f
private const val TaskTodoTagBackgroundAlpha = 0.1f

/** 标签文字相对 accent 的降明度系数（RGB 同比）·浅色主题 */
private const val TaskTagTextDimFactorLight = 0.5f

/** 同上 ·深色主题：系数放缓避免文字闷进 25% 透明的标签底色（DayCourseAlert 同款） */
private const val TaskTagTextDimFactorDark = 0.95f

private val TaskTagPaddingHorizontal = 5.dp

/** 紧凑单行标签的上下内距：横向标签矩形高度 = 文字行高 + 上下内距 × 2；
 *  拉伸态（竖排卡）不适用（高度由时间列 weight 分配，见 TaskTagBox） */
private val TaskTagPaddingVertical = 4.dp

private val TaskTitleFontSize = 16.sp
private val TaskTitleLineHeight = 20.sp
private val TaskInfoFontSize = 13.sp
private val TaskInfoLineHeight = 18.sp
private val TaskTimeFontSize = 13.sp
private val TaskTimeLineHeight = 20.sp
private val TaskTagFontSize = 11.sp
private val TaskTagLineHeight = 12.sp

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
