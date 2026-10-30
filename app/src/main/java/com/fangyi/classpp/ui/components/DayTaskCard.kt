package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.schedule.CourseColor
import com.fangyi.classpp.ui.schedule.barColor
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.DayCourseAlertTagShape
import com.fangyi.classpp.ui.theme.classppColors
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
 *   装不下竖排内容时以内容最小高度撑底（顶边仍锚定开始时刻）；内容最小高度由渲染侧
 *   实测（[DayTaskCardContent] 的真实测量结果经 [solveDayTaskLayout] 的 [contentHeights]
 *   传入），备注折行/字体行高等任何撑高都会参与下方的 2dp 间距与时间轴局部拉伸；
 * - 同一横向范围内上下相邻卡至少留 2dp，时间贴背恰好 2dp；空间不足时由
 *   [solveDayTaskLayout] 在冲突时刻插入额外轴空间（左侧整点跨度局部变大）；
 * - 紧凑卡（比例高度 < [DayTaskCompactThreshold]，≈84 分钟）有 [CompactMinHeight]
 *   呼吸高下限：时长太短导致比例高度不足该下限时，在卡的**结束时刻**插入差额——卡顶
 *   仍锚定开始时刻、卡底正好落到结束刻度，其后刻度/卡片/当前时刻线整体下移；窄卡与
 *   竖排卡的内容撑高不参与该规则（仍只在压住下卡时推轴）；
 * - 比例高度 < [DayTaskCompactThreshold] 切紧凑单行（时间 + 标签 + 时间 + 标题横排
 *   一行）；
 * - 分列后卡宽不足 [DayTask.narrowThreshold]（课程 100dp / 待办 180dp）时切窄卡样式：
 *   起止时间置顶单行 + 标题 + 内容（课程备注最多两行、待办不画勾选框），优先于紧凑单行；
 * - 相邻任务贴近（渲染间距 < 6dp，含贴背）时接触角收敛为 6dp，时间重叠的任务均分
 *   卡片区域宽度（列间 2dp），贴邻方向接触角同样收敛——圆角一律走
 *   [com.fangyi.classpp.ui.theme.dayTaskCardShape]。
 *
 * **维护约定（实测高度缓存）**：[DayTimeline] 会把 [DayTaskCardContent] 的实测高度按
 * [DayTaskMeasureKey]（内容 + 形态 + 卡宽；字体度量由缓存生命周期保证）缓存，重测命中
 * 时不再组合测量副本。以后给卡片内容新增**任何影响高度的字段或排版**（新的可见性开关、
 * 字号/行高、备注样式开关、行数限制、内容内边距等）时，必须同步更新 [DayTaskMeasureKey]
 * 与 [DayTask.measureKey]；否则高度变化不会触发重新测量，时间轴仍按过期高度排布，
 * 会破坏上下卡 ≥ 2dp 的间距。只影响颜色/点击等尺寸无关的字段不需要加。缓存的失效时机
 * 见 DayTimeline 的 cache remember 键（密度/字号缩放、字体解析器、布局方向、区域配置、
 * 环境文字样式）。
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

    /** 待办：标题下展示截止时间与重复规则（样式复用 NoteCard 的属性行）；左侧时间与标签
     *  矩形取截止时间色（无截止回落 Primary）。[onToggle] 接完成勾选；[onClick] 接待办
     *  详情浮层的唤起（整卡点击，null = 不可点，勾选框不受影响） */
    data class Todo(
        val id: String,
        val name: String,
        val completed: Boolean,
        val deadlineText: String?,
        val deadlineColor: Color,
        val repeatText: String? = null,
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

/**
 * 卡片内容高度的缓存键：只含真正影响排版的字段。`onClick`/`onToggle` 每次重建任务
 * 列表都是新 lambda（且不影响尺寸），颜色也不影响行高，入键会让缓存永远命中不了或
 * 无谓失效。新增任何会影响内容高度的字段（文字、可见性开关等）时必须同步加进来，
 * 否则缓存会返回过期高度。
 */
internal data class DayTaskMeasureKey(
    val course: Boolean,
    val name: String,
    val teacher: String?,
    val location: String?,
    val note: String?,
    val deadlineText: String?,
    val repeatText: String?,
    val completed: Boolean,
)

/** 见 [DayTaskMeasureKey]：任务 → 内容高度缓存键（不含回调/颜色/时间范围） */
internal val DayTask.measureKey: DayTaskMeasureKey
    get() = when (this) {
        is DayTask.Course -> DayTaskMeasureKey(
            course = true,
            name = name,
            teacher = teacher,
            location = location,
            note = note,
            deadlineText = null,
            repeatText = null,
            completed = false,
        )
        is DayTask.Todo -> DayTaskMeasureKey(
            course = false,
            name = name,
            teacher = null,
            location = null,
            note = null,
            deadlineText = deadlineText,
            repeatText = repeatText,
            completed = completed,
        )
    }

/** 重叠分列时的列序：课程在左、待办在右（同型内按开始时间） */
private val DayTask.columnPriority: Int
    get() = when (this) {
        is DayTask.Course -> 0
        is DayTask.Todo -> 1
    }

/** 窄卡分界（课程）：分列后卡宽小于该值时切窄卡样式（时间置顶单行、无左侧时间列） */
internal val DayTaskNarrowThresholdCourse = 100.dp

/** 窄卡分界（待办）：待办竖排还占一列 36dp 勾选框，阈值高于课程 */
internal val DayTaskNarrowThresholdTodo = 180.dp

/** 窄卡判定阈值：由任务类型取对应分界 */
internal val DayTask.narrowThreshold: Dp
    get() = when (this) {
        is DayTask.Course -> DayTaskNarrowThresholdCourse
        is DayTask.Todo -> DayTaskNarrowThresholdTodo
    }

// ---------- 布局求解：位置尺寸 + 接触角 + 形态（纯函数，可单测） ----------

/** 单张任务卡的求解结果；[offsetX] 相对卡片区域左缘、[offsetY] 相对时间轴顶部。
 *  四个接触标记 = 对应角与邻近任务贴近（渲染间距 < 6dp），渲染时收敛为 6dp 圆角；
 *  [narrow] = 分列后卡宽不足 [DayTask.narrowThreshold]，渲染切窄卡样式（优先于 [compact]） */
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
    val narrow: Boolean,
)

/** 重叠任务卡分列时的列间距（均分空间、间距 2dp） */
internal val DayTaskColumnGap = 2.dp

/** 上下相邻任务卡的最小纵向间距；时间贴背的任务恰好保持该间距 */
internal val DayTaskVerticalGap = 2.dp

/** 某一时刻起插入的额外轴空间：整条时间轴从 [minute] 起再向下平移 [extra]。
 *  [DayTaskLayout.pushes] 按分钟升序，同一分钟可有多条（[DayTaskLayout.extraAt] 取
 *  最后一条累计值生效） */
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
 *  比例高度不足即切紧凑单行——1 小时 = 50dp 比例尺下 60 分钟待办为紧凑形态；
 *  分列后卡宽不足窄卡阈值时优先窄卡样式（见 [DayTask.narrowThreshold]） */
internal val DayTaskCompactThreshold = 70.dp

/** 紧凑单行的最小高度兜底（比例高度更小时不再压缩，保证一行文字的呼吸感） */
private val CompactMinHeight = 40.dp

/** 贴近判定阈值：上下卡实际渲染间距小于该值 → 接触角收敛 6dp（贴背 2dp 也在此列） */
private val DayTaskContactGap = 6.dp

/** 课程备注最大显示行数：所有卡片形态统一上限，超出省略（防止长备注把时间轴拉长） */
private const val DayTaskNoteMaxLines = 2

/**
 * 时间轴任务卡布局求解：重叠链分组 → 组内贪心分列 → 等宽均分 → 纵向排布 → 接触角标记。
 *
 * - 分组/分列/均分/横向接触：与旧版一致（贴背不算重叠，课程列优先，列间 2dp）；
 * - 纵向排布：保持 1 小时 = [hourSpacing] 的基础比例，按「开始组 + 结束时刻」双流
 *   合并推进；同一横向范围（x 区间严格相交）内、时间不重叠的上下卡之间至少留
 *   [DayTaskVerticalGap]，不足时在下方卡开始时刻插入额外轴空间（[DayTaskPush]）；
 *   紧凑卡的比例高度低于内容最小高度（≥ [CompactMinHeight]）时，还在其结束时刻插入
 *   差额（同刻结束的并行卡取最大、不累加），卡底恰好落到结束刻度——整点刻度跨度随
 *   插入量局部变大，贴背任务恰好 2dp，未受影响的小时仍为 [hourSpacing]；
 * - 高度：先取 max(比例高度, 内容最小高度)，再把卡片时间区间内插入的额外空间计入，
 *   保证卡片仍覆盖被拉伸的时间段（结束时刻的差额插入正是把结束刻度抬到卡底，下卡
 *   只再补 2dp 间距）；内容最小高度优先取 [contentHeights]（渲染侧对
 *   [DayTaskCardContent] 的实测高度，按输入顺序对应），缺省时用镜像排版常量的估算值
 *   （单测/预览等无排版上下文场景）；
 * - 接触角：上下卡实际渲染间距 < [DayTaskContactGap] 时收敛 6dp（含贴背 2dp）；
 * - 纵向原点：[anchorMinute]（时间轴首刻度），卡片顶边 = y(start) =
 *   [hourSpacing] × ((start − anchor) / 60 + 0.5) + 已插入的累计额外空间。
 */
internal fun List<DayTask>.solveDayTaskLayout(
    regionWidth: Dp,
    hourSpacing: Dp,
    anchorMinute: Int = 0,
    fontScale: Float = 1f,
    contentHeights: List<Dp>? = null,
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

    // 形态与高度基准：窄卡按分列后的实际卡宽判定（窄卡优先于紧凑单行）；紧凑判定沿用
    // 基础比例尺（1 小时 = hourSpacing）。内容最小高度把渲染时 heightIn 的撑高量提前
    // 纳入布局，避免内容压住下卡。[baseHeights] 会随卡内插入的空间增长（见下方局部拉伸）
    val compact = BooleanArray(size)
    val narrow = BooleanArray(size)
    val baseHeights = arrayOfNulls<Dp>(size)
    val contentMins = arrayOfNulls<Dp>(size)
    for (index in indices) {
        val task = this[index]
        val width = requireNotNull(columnInfo[index]).width
        val proportionalHeight = hourSpacing * ((task.endMinute - task.startMinute) / 60f)
        narrow[index] = width < task.narrowThreshold
        compact[index] = !narrow[index] && proportionalHeight < DayTaskCompactThreshold
        baseHeights[index] = proportionalHeight
        // 渲染侧实测内容高度优先（与画卡用同一内容 composable，备注折行等抖动不再是
        // 估算误差）；紧凑卡仍保留 40dp 呼吸高下限。无实测时退回镜像常量估算
        contentMins[index] = contentHeights?.getOrNull(index)?.let { measured ->
            maxOf(measured, if (compact[index]) CompactMinHeight else 0.dp)
        } ?: dayTaskContentMinHeight(
            task = task,
            compact = compact[index],
            narrow = narrow[index],
            width = width,
            fontScale = fontScale,
        )
    }

    // 纵向排布：开始组与结束时刻两路事件按分钟合并推进。同组卡片顶边对齐；每放置
    // 一组前，先消化该组开始时刻及之前的结束事件——紧凑卡的比例高度装不下内容最低
    // 高度时，在结束时刻插入差额（卡底落到结束刻度），跨越该时刻的卡同步加高；之后
    // 再按现有逻辑检查与已放置卡的 2dp 间距，不足时在组开始时刻补插空间
    val sortedIndices = indices.sortedWith(
        compareBy({ this[it].startMinute }, { this[it].endMinute }),
    )
    val tops = arrayOfNulls<Dp>(size)
    val pushes = mutableListOf<DayTaskPush>()
    val placed = mutableListOf<Int>()
    var extra = 0.dp
    val endOrder = indices.sortedWith(
        compareBy({ this[it].endMinute }, { this[it].startMinute }),
    )
    var endCursor = 0
    fun flushEndsUpTo(limit: Int) {
        while (endCursor < endOrder.size && this[endOrder[endCursor]].endMinute <= limit) {
            val minute = this[endOrder[endCursor]].endMinute
            // 同刻结束的卡同组处理：并行车道共用同一段轴，按最大差额只插一次；
            // 差额此时重算——已放置卡可能被卡内更早的插入抬高过比例高度
            var inserted = 0.dp
            while (endCursor < endOrder.size &&
                this[endOrder[endCursor]].endMinute == minute
            ) {
                val index = endOrder[endCursor++]
                // 未放置（零时长等非法区间）的卡不产生轴空间
                if (index !in placed) continue
                val deficit = if (compact[index]) {
                    maxOf(
                        0.dp,
                        requireNotNull(contentMins[index]) -
                            requireNotNull(baseHeights[index]),
                    )
                } else {
                    0.dp
                }
                inserted = maxOf(inserted, deficit)
            }
            if (inserted <= 0.dp) continue
            extra += inserted
            pushes.add(DayTaskPush(minute, extra))
            // 时间轴从这里整体下移：正在跨越该时刻的卡片同步加高，保持覆盖自己的
            // 时间段
            for (placedIndex in placed) {
                val placedTask = this[placedIndex]
                if (placedTask.startMinute < minute && minute < placedTask.endMinute) {
                    baseHeights[placedIndex] =
                        requireNotNull(baseHeights[placedIndex]) + inserted
                }
            }
        }
    }
    var groupStart = 0
    while (groupStart < sortedIndices.size) {
        val start = this[sortedIndices[groupStart]].startMinute
        // 同刻结束先于同刻开始：贴背下卡的顶边自动包含上卡的结束差额
        flushEndsUpTo(start)
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
    // 最后一批卡没有后续开始组来触发结束事件：收尾消化，末刻度（次日 0:00）随之下移
    flushEndsUpTo(Int.MAX_VALUE)

    // 最终高度：比例高度 + 本卡时间区间内插入的空间，并保证不低于内容最小高度。
    // 结束时刻的差额插入抬的是结束刻度（卡底对齐），不计入本卡高度；下卡只再补 2dp
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
            narrow = narrow[index],
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
    contentHeights: List<Dp>? = null,
): List<DayTaskPlacement> =
    solveDayTaskLayout(
        regionWidth,
        hourSpacing,
        anchorMinute,
        fontScale,
        contentHeights,
    ).placements

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
 * 卡片内容的最小高度：镜像 [NarrowTaskContent]/[VerticalTaskContent]/[CompactTaskContent]
 * 的排版常量，让布局在渲染前就知道内容需要的高度（渲染时的 heightIn 只作兜底）。
 * 窄卡（[narrow]）按实际卡宽 [width] 估算课程备注行数并封顶 [DayTaskNoteMaxLines]；
 * 竖排备注存在时按两行保守估算。sp 按 [fontScale] 换算成 dp。渲染路径用
 * [DayTaskCardContent] 实测内容高度（[contentHeights]），这里只是无排版上下文
 * （单测/预览）时的兜底。
 */
private fun dayTaskContentMinHeight(
    task: DayTask,
    compact: Boolean,
    narrow: Boolean,
    width: Dp,
    fontScale: Float,
): Dp {
    val titleLine = (ClassppTextTokens.taskCardTitle.lineHeight.value * fontScale).dp
    val timeLine = (ClassppTextTokens.metaTextSmall.lineHeight.value * fontScale).dp
    val tagLine = (ClassppTextTokens.compactTagText.lineHeight.value * fontScale).dp
    if (narrow) {
        // 窄卡：顶行时间 + 标题 + 内容，元素间距统一 TaskLineSpacing。课程备注按卡宽
        // 估算换行行数，避免渲染撑高后压住下卡
        val infoLine = (ClassppTextTokens.metaText.lineHeight.value * fontScale).dp
        return when (task) {
            is DayTask.Course -> {
                val hasTeacherLocation =
                    listOfNotNull(task.teacher, task.location).isNotEmpty()
                val noteLines = task.note?.let { note ->
                    estimateNarrowNoteLines("备注：$note", width, fontScale)
                } ?: 0
                var height = TaskContentPaddingVertical * 2 +
                    timeLine + TaskLineSpacing + titleLine
                if (hasTeacherLocation) height += TaskLineSpacing + infoLine
                if (noteLines > 0) height += TaskLineSpacing + infoLine * noteLines
                height
            }
            is DayTask.Todo -> {
                val rows = listOfNotNull(task.deadlineText, task.repeatText).size
                val rowHeight = maxOf(
                    TaskPropertyIconSize,
                    (ClassppTextTokens.metaText.lineHeight.value * fontScale).dp,
                )
                var height = TaskContentPaddingVertical * 2 +
                    timeLine + TaskLineSpacing + titleLine
                if (rows > 0) {
                    height += TaskLineSpacing + rowHeight * rows +
                        TaskPropertyRowGap * (rows - 1)
                }
                height
            }
        }
    }
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
            val metaLine = (ClassppTextTokens.metaText.lineHeight.value * fontScale).dp
            // 备注上限两行：无实测上下文时按两行保守估算（实际行数由渲染 maxLines 决定）
            val noteSecondLine = if (task.note != null) metaLine else 0.dp
            TaskContentPaddingVertical * 2 +
                titleLine +
                (metaLine + TaskLineSpacing) * infoCount +
                noteSecondLine
        }
        is DayTask.Todo -> {
            // 属性行（截止 + 重复）在窄列里最多两项、可能折成两行；两项都缺席时整行不渲染
            val rows = listOfNotNull(task.deadlineText, task.repeatText).size
            val rowHeight = maxOf(
                TaskPropertyIconSize,
                (ClassppTextTokens.metaText.lineHeight.value * fontScale).dp,
            )
            TaskContentPaddingVertical * 2 +
                titleLine +
                if (rows > 0) {
                    TaskTitlePropertySpacing +
                        rowHeight * rows +
                        TaskPropertyRowGap * (rows - 1)
                } else {
                    0.dp
                }
        }
    }
    return maxOf(timeColumn, right, TaskTodoCheckboxSize)
}

/** 窄卡课程备注的估算行数：内容宽（卡宽 − 左右内距）按 14sp 中文字宽折算，至少 1 行、
 *  最多 [DayTaskNoteMaxLines] 行（与渲染 maxLines 一致）。无排版上下文时的兜底
 *  （单测/预览）；渲染侧由 [DayTaskCardContent] 实测整体内容高度 */
private fun estimateNarrowNoteLines(text: String, width: Dp, fontScale: Float): Int {
    val contentWidth = (width - TaskCardPaddingHorizontal * 2).value
    val charWidth = ClassppTextTokens.metaText.fontSize.value * fontScale
    if (contentWidth <= 0f || charWidth <= 0f) return 1
    val charsPerLine = (contentWidth / charWidth).toInt().coerceAtLeast(1)
    return ((text.length + charsPerLine - 1) / charsPerLine)
        .coerceIn(1, DayTaskNoteMaxLines)
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
 *
 * 内容高度的实测缓存与「新增影响高度的字段必须同步 [DayTaskMeasureKey]」的维护约定
 * 见文件头 KDoc。
 */
@Composable
internal fun DayTaskCard(placement: DayTaskPlacement, modifier: Modifier = Modifier) {
    val task = placement.task
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
        DayTaskCardContent(
            task = task,
            compact = placement.compact,
            narrow = placement.narrow,
        )
    }
}

/**
 * 卡片内容（不含白底卡壳）：渲染与「布局前的内容高度实测」共用同一份 composable，因此
 * 备注折行、字体行高、可见性分支等任何撑高质量都与求解器计入的一致。调用方负责给出与
 * [DayTaskCard] 相同的宽度约束；卡壳 Box 的 propagateMinConstraints 会把卡高作为最小
 * 高度传进来（竖排卡的时间列/内容列据此拉伸与垂直居中）。
 */
@Composable
internal fun DayTaskCardContent(task: DayTask, compact: Boolean, narrow: Boolean) {
    val accent = when (task) {
        is DayTask.Course -> task.color.barColor(MaterialTheme.isDarkTheme)
        // 待办左侧时间/标签矩形取截止时间色（NoteCard 属性行的同一取色：>2 天 Primary、
        // 剩 2 天黄、剩 1 天橙、当天与逾期红）；无截止日期回落 Primary
        is DayTask.Todo -> if (task.deadlineText != null) {
            task.deadlineColor
        } else {
            MaterialTheme.colorScheme.primary
        }
    }
    when {
        narrow -> NarrowTaskContent(task, accent)
        compact -> CompactTaskContent(task, accent)
        else -> VerticalTaskContent(task, accent)
    }
}

/**
 * 窄卡布局（分列后卡宽 < [DayTask.narrowThreshold]）：起止时间单行置顶，其次标题，
 * 内容列紧随（课程：教师/地点一行 + 备注最多两行；待办：截止/重复属性行，不画勾选框）。
 * 无左侧时间列与「上课/待办」标签；内容整体垂直居中，卡片高度仍按比例/内容最小高度撑开。
 */
@Composable
private fun NarrowTaskContent(task: DayTask, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = TaskCardPaddingHorizontal,
                vertical = TaskContentPaddingVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(TaskLineSpacing, Alignment.CenterVertically),
    ) {
        TaskTimeText(
            text = "${TimeText.format(task.startMinute)} - ${TimeText.format(task.endMinute)}",
            color = accent,
        )
        when (task) {
            is DayTask.Course -> NarrowCourseContent(task)
            is DayTask.Todo -> NarrowTodoContent(task)
        }
    }
}

/** 窄卡课程内容：标题（单行省略）+ 教师/地点并作一行 + 备注（最多两行，超出省略） */
@Composable
private fun ColumnScope.NarrowCourseContent(task: DayTask.Course) {
    Text(
        text = task.name,
        style = ClassppTextTokens.courseTaskCardTitle,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    listOfNotNull(task.teacher, task.location)
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }
        ?.let { TaskInfoText(it, MaterialTheme.classppColors.secondaryText) }
    task.note?.let {
        TaskInfoText(
            text = "备注：$it",
            color = MaterialTheme.colorScheme.primary,
            maxLines = DayTaskNoteMaxLines,
        )
    }
}

/** 窄卡待办内容：标题（单行省略）+ 截止/重复属性行；勾选框不参与渲染，完成态仍灰划线 */
@Composable
private fun ColumnScope.NarrowTodoContent(task: DayTask.Todo) {
    Text(
        text = task.name,
        style = ClassppTextTokens.taskCardTitle.copy(
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
    if (task.deadlineText != null || task.repeatText != null) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(TaskPropertyRowGap),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
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
            task.repeatText?.let { repeat ->
                TimeProperty(
                    icon = R.drawable.ic_arrow_repeat,
                    text = repeat,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = TaskPropertyItemEndPadding),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                modifier = Modifier.weight(1f).width(TaskTagWidth),
            )
            TaskTimeText(TimeText.format(task.endMinute), accent)
        }
        when (task) {
            is DayTask.Course -> VerticalCourseContent(task, Modifier.weight(1f))
            is DayTask.Todo -> {
                VerticalTodoContent(task, Modifier.weight(1f))
                TaskTodoCheckbox(
                    checked = task.completed,
                    onToggle = task.onToggle,
                    modifier = Modifier.padding(end = TaskTodoCheckboxEndInset),
                )
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
            style = ClassppTextTokens.courseTaskCardTitle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        task.teacher?.let { TaskInfoText(it, MaterialTheme.classppColors.secondaryText) }
        task.location?.let { TaskInfoText(it, MaterialTheme.classppColors.secondaryText) }
        task.note?.let {
            TaskInfoText(
                text = "备注：$it",
                color = MaterialTheme.colorScheme.primary,
                maxLines = DayTaskNoteMaxLines,
            )
        }
    }
}

/** 待办内容（NoteCard 样式：fieldLabel 标题 + 截止/重复属性行；标签、紧急旗暂不接入） */
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
            style = ClassppTextTokens.taskCardTitle.copy(
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
        if (task.deadlineText != null || task.repeatText != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                // 重叠分列后卡可能只剩一半宽：属性项单行省略，避免逐字折行（图 5 同款截断）
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
                task.repeatText?.let { repeat ->
                    TimeProperty(
                        icon = R.drawable.ic_arrow_repeat,
                        text = repeat,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = TaskPropertyItemEndPadding),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                TaskTodoCheckbox(
                    checked = task.completed,
                    onToggle = task.onToggle,
                    modifier = Modifier.padding(end = TaskTodoCheckboxEndInset),
                )
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
        style = ClassppTextTokens.taskCardTitle.copy(
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

/** 「上课/待办」标签：底色 accent [backgroundAlpha] 透明；[dimText] = 文字相对 accent
 *  降明度（课程色为马卡龙浅色需要压深，深浅各一套系数）——待办标签的 accent 取截止时间色
 *  （无截止回落 Primary），传 false 直接用原色 */
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

/** 左列上下时间：12sp Medium，accent 色（DayCourseAlert TimeText 同款） */
@Composable
private fun TaskTimeText(text: String, color: Color) {
    Text(
        text = text,
        style = ClassppTextTokens.metaTextSmall,
        color = color,
    )
}

/** 标签文字：12sp Medium，降明度 accent（DayCourseAlert TagText 同款） */
@Composable
private fun TaskTagText(text: String, color: Color) {
    Text(
        text = text,
        style = ClassppTextTokens.compactTagText,
        color = color,
    )
}

/** 课程信息行（教师/地点/备注）：14sp，颜色由调用方给；[maxLines] 缺省单行省略（教师/
 *  地点不撑高），备注传 [DayTaskNoteMaxLines] 上限两行、超出省略 */
@Composable
private fun TaskInfoText(text: String, color: Color, maxLines: Int = 1) {
    Text(
        text = text,
        style = ClassppTextTokens.metaText,
        color = color,
        maxLines = maxLines,
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
private val TaskTagGap = 1.dp

/** 竖排卡标签矩形定宽：不再随列宽（= 时间文字宽）撑满，与上下时间共用中轴居中 */
private val TaskTagWidth = 32.dp

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

/** 待办勾选框距卡片右缘的补差：卡壳横向内距 12dp 已是卡内内容基准，勾选框再补 4dp，
 *  右缘间距凑成 NoteCard 的 16dp（热区方径不变，补的是卡内排版位） */
private val TaskTodoCheckboxEndInset = 16.dp - TaskCardPaddingHorizontal

/** 待办属性行（TimeProperty）的图标尺寸：用于内容最小高度估算 */
private val TaskPropertyIconSize = 16.dp

/** 待办属性行换行时的行间距（FlowRow verticalArrangement = 3dp） */
private val TaskPropertyRowGap = 3.dp

/** 标签底色透明度：课程色 25%（DayCourseAlert 同款设计巧思）；待办取截止时间色 10%
 *  （NoteCard TagChip 同款，截止色随剩余天数变化、无截止为 Primary） */
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

// ---------- 预览 ----------

/** 设计稿同款课程色 */
private val PreviewCourseColor = CourseColor.Blue

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 900)
@Composable
private fun DayTaskCardPreview() {
    ClassppTheme {
        Box(Modifier.fillMaxSize()) {
            // 截止配色读当前主题（浅/深预览各得其所，口径同 Todo.deadlineColor），
            // 作 remember key：深浅切换时重摆
            val deadlinePrimary = MaterialTheme.colorScheme.primary
            val deadlineYellow = MaterialTheme.classppColors.urgentYellow
            val deadlineRed = MaterialTheme.colorScheme.error
            // 区域宽 = 390 − 时间列 44 − 竖线距 12 − 右缘页边距 12（与 DayTimeline 同口径）
            val placements = remember(deadlinePrimary, deadlineYellow, deadlineRed) {
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
                        deadlineColor = deadlinePrimary,
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
                    // 90 分钟：竖排形态，标题下为截止 + 重复属性行；当天截止 → 左侧与标签取红
                    DayTask.Todo(
                        id = "todo-2",
                        name = "待办标题待办标题待办标题",
                        completed = false,
                        deadlineText = "今天 8:00",
                        deadlineColor = deadlineRed,
                        repeatText = "每天",
                        onToggle = null,
                        startMinute = 11 * 60 + 50,
                        endMinute = 13 * 60 + 20,
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
                        deadlineText = "后天 8:00",
                        deadlineColor = deadlineYellow,
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

/**
 * 窄卡样式对照：待办半宽（160dp < [DayTaskNarrowThresholdTodo]）与课程四列
 * （≈79dp < [DayTaskNarrowThresholdCourse]）。位置直接给定，聚焦样式本身
 * （触发判定由 DayTaskPlacementTest 覆盖）。
 */
@Preview(
    showBackground = true,
    backgroundColor = 0xFFF2F4F6,
    widthDp = 390,
    heightDp = 620,
    name = "窄卡样式",
)
@Composable
private fun DayTaskCardNarrowPreview() {
    ClassppTheme {
        Box(Modifier.fillMaxSize()) {
            DayTaskCard(
                DayTaskPlacement(
                    task = DayTask.Todo(
                        id = "narrow-todo",
                        name = "待办标题待办标题待办标题",
                        completed = false,
                        deadlineText = "明天 8:00",
                        deadlineColor = MaterialTheme.classppColors.urgentOrange,
                        onToggle = null,
                        startMinute = 13 * 60,
                        endMinute = 14 * 60,
                    ),
                    offsetX = 16.dp,
                    offsetY = 16.dp,
                    width = 160.dp,
                    height = 84.dp,
                    topStartContact = false,
                    topEndContact = false,
                    bottomStartContact = false,
                    bottomEndContact = false,
                    compact = false,
                    narrow = true,
                ),
            )
            DayTaskCard(
                DayTaskPlacement(
                    task = DayTask.Course(
                        name = "课程标题课程标题课程标题",
                        teacher = "XX老师",
                        location = "上课地点",
                        note = "备注内容备注内容备注内容备注内容",
                        color = PreviewCourseColor,
                        startMinute = 13 * 60,
                        endMinute = 14 * 60,
                    ),
                    offsetX = 16.dp,
                    offsetY = 116.dp,
                    width = 90.dp,
                    height = 160.dp,
                    topStartContact = false,
                    topEndContact = false,
                    bottomStartContact = false,
                    bottomEndContact = false,
                    compact = false,
                    narrow = true,
                ),
            )
        }
    }
}
