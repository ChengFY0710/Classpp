package com.fangyi.classpp.ui.schedule

import java.util.Calendar
import java.util.Date

/** 卡片渲染所需的最小课程字段；持久化与周次规律后续阶段再加 */
data class Course(
    val name: String,
    val teacher: String,
    /** 上课地点，如 "@学武楼 C201" */
    val location: String,
    /** 星期：1 = 周一 … 5 = 周五（7 天课表另有 6 = 周六、7 = 周日） */
    val dayOfWeek: Int,
    /** 所属节次 id（起始节次；跨节课程从这里开始向下占据 [span] 行） */
    val slotId: Int,
    val color: CourseColor,
    /** 本周是否上课；false 时卡片按规格置灰（theme.kt negative色） */
    val active: Boolean = true,
    /** 连续占用节数（1 = 单节）；跨节卡由网格叠加层绘制，见 CourseGrid */
    val span: Int = 1,
    /** 数据层 CourseEntry 的 id；编辑态点击卡片靠它回查草稿条目（mock/预览无 id、不可点） */
    val id: String = "",
    /**
     * 非本周交替课的配色 → 卡片左侧色条的下 1/4（见 CourseCard）；
     * null = 该格没有交替课，或本卡本周不上（整卡置灰时不上色）。
     * 由 [resolveWeekCards] 解析填入。
     */
    val alternateBar: CourseColor? = null,
)

/** 一个固定节次；起止时间直接以卡片上显示的字符串形式保存 */
data class TimeSlot(
    val id: Int,
    val startTime: String,
    val endTime: String,
)

/** 课程卡片配色；映射到 Color 收在 CourseCard.kt，模型保持零 Compose 依赖 */
enum class CourseColor { Blue, Green, Greentwo , Yellow, Orange, Purple, Teal, Pink }

/**
 * 一周的整页渲染数据：日期带 + 该周课程。
 * 横向翻周时每周一页（见 CourseGrid 的 Pager），按页周号现取，无需预先算好相邻周。
 */
data class WeekPageContent(
    val week: Int,
    val courses: List<Course>,
    /** 该周各上课日的日期（5 天视图 5 个、7 天视图 7 个；日期带显示日号） */
    val dates: List<Date>,
    /** 日期带高亮列：与顶栏日期同规则（查看本周 = 今天，其它周 = 该周周一） */
    val highlightDate: Date,
    /**
     * [highlightDate] 是否为今天（查看今周时为 true）：true 时日期带给它垫主题
     * primaryContainer 胶囊底；浏览其它周高亮该周周一，只变色不加底。
     */
    val highlightIsToday: Boolean = false,
    /**
     * 学期开始/结束日：日期带里出现时分别给绿（Correct）/红（Error）字标记；
     * 恰为「今天」时让位给蓝字胶囊底（见 DateBand）。null = 无学期上下文（@Preview）。
     */
    val termStartDate: Date? = null,
    val termEndDate: Date? = null,
)

/** 学期第一个教学周一（2026-03-02 周一 = 第 1 周周一） */
val TermStart: Date = Calendar.getInstance().apply {
    set(2026, Calendar.MARCH, 2, 0, 0, 0)
    set(Calendar.MILLISECOND, 0)
}.time

/** 默认 5 节次 */
val DefaultTimeSlots = listOf(
    TimeSlot(1, "8:00", "9:40"),
    TimeSlot(2, "10:10", "11:50"),
    TimeSlot(3, "14:30", "16:10"),
    TimeSlot(4, "16:40", "18:20"),
    TimeSlot(5, "19:10", "20:50"),
)

/**
 * 第 [week] 周周一至周 [daysPerWeek] 的日期（周数从 1 开始）。
 * 默认 5 天；7 天视图下多出周六/周日两列。
 */
fun datesForWeek(week: Int, daysPerWeek: Int = 5): List<Date> {
    val base = Calendar.getInstance().apply {
        time = TermStart
        add(Calendar.DAY_OF_YEAR, (week - 1) * 7)
    }
    return (0 until daysPerWeek).map { day ->
        (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, day) }.time
    }
}

/**
 * 课表网格的竖分隔线 x 坐标：把宽度等分成 [days] 列，取**内部**的列边界（共 days-1 条）。
 *
 * 与布局同源：网格用 `Modifier.weight(1f)` 把宽度等分成 days 列，分隔线必须按同一套划分计算，
 * 否则线会落在格子中间。日期带、课程行、页右缘接缝线都用它，三者保证接成同一条线。
 * `days = 1`（无内部边界）或宽度未测量出来时返回空列表。
 */
fun columnDividerXs(widthPx: Float, days: Int): List<Float> =
    if (days < 2) emptyList() else (1 until days).map { widthPx * it / days }

/** 是否同一天（年月日） */
fun Date.isSameDay(other: Date): Boolean {
    val a = Calendar.getInstance().apply { time = this@isSameDay }
    val b = Calendar.getInstance().apply { time = other }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.MONTH) == b.get(Calendar.MONTH) &&
        a.get(Calendar.DAY_OF_MONTH) == b.get(Calendar.DAY_OF_MONTH)
}

/** 日号（9、10…），用于日期带显示 */
fun Date.dayOfMonth(): Int =
    Calendar.getInstance().apply { time = this@dayOfMonth }.get(Calendar.DAY_OF_MONTH)

/** 示例课表：覆盖截图中的课程分布（单周 mock，选周时暂显示同一份） */
val MockCourses: List<Course> = listOf(
    // 第 1 节 8:00-9:40（周三微积分跨到第 2 节，对应设计稿）
    Course("体育舞蹈-拉丁舞", "XX老师", "@爱秋体育馆", 2, 1, CourseColor.Pink),
    Course("微积分 I-2", "XX老师", "@学武楼 C201", 3, 1, CourseColor.Green, span = 2),
    Course("微积分 I-2", "XX老师", "@学武楼 C201", 5, 1, CourseColor.Green),
    // 第 2 节 10:10-11:50
    Course("微积分 I-2", "XX老师", "@学武楼 C201", 1, 2, CourseColor.Green),
    Course("电路原理", "XX老师", "@学武楼 C404", 2, 2, CourseColor.Yellow),
    Course("面向对象程序设计", "XX老师", "@学武楼 C304", 4, 2, CourseColor.Orange),
    Course("概率统计", "XX老师", "@学武楼 C404", 5, 2, CourseColor.Blue),
    // 第 3 节 14:30-16:10
    Course("大学物理 B（上）", "XX老师", "@学武楼 A206", 1, 3, CourseColor.Purple),
    Course("中国近代史纲要", "XX老师", "@学武楼 A204", 2, 3, CourseColor.Teal),
    Course("大学生心理健康", "XX老师", "@学武楼 C201", 3, 3, CourseColor.Greentwo),
    Course("大学物理 B（上）", "XX老师", "@学武楼 A206", 4, 3, CourseColor.Purple),
    Course("电路原理", "XX老师", "@学武楼 C404", 5, 3, CourseColor.Yellow),
    // 第 4 节 16:40-18:20
    Course("形式与政策", "XX老师", "@学武楼 B101", 1, 4, CourseColor.Purple),
    Course("学术沟通之道", "XX老师", "@学武楼", 2, 4, CourseColor.Pink),
    // 第 5 节 19:10-20:50
    Course("中国古代文学史与作品精读", "XX老师", "@西部片区#4 208", 3, 5, CourseColor.Green),
    Course("程序设计研讨", "XX老师", "@学武楼 B203", 4, 5, CourseColor.Blue),
)

/** 7 天视图预览用 mock：5 天那份再加两门落在周六/周日的课（6/7 列只有 7 天课表才会有） */
val MockCoursesWeekend: List<Course> = MockCourses + listOf(
    Course("大学英语（听说）", "XX老师", "@学武楼 B105", 6, 2, CourseColor.Teal),
    Course("工程训练", "XX老师", "@工程训练中心", 7, 4, CourseColor.Orange),
)

/**
 * 每周渲染解析：**输出的卡片两两不重叠**。
 *
 * 交替课程 = 同一天里 span 相交（占用节次有重合）的课程，周数互不重合（由校验保证），
 * 故本周至多一门在课。交替课的起止节次可以不一致（跨节课程与它续格上的单节课就是一组），
 * 不能再按"同起始节"分组，改为**按天两轮贪心**：
 *
 * 1. 当周在课（[Course.active]）的卡全部显示——校验保证它们互不重叠；
 * 2. 非在课的按添加顺序补位：卡范围（[slotId, slotId+span-1]）与已显示卡相交的跳过
 *    （它本周不上，且画出来会被跨节卡盖住，正是"跨节覆盖续格课"的来源）。
 *
 * 全组本周都不上课时，最先添加的那门补位显示（照旧置灰，且不画色条）。
 * 在课卡的 [Course.alternateBar] 记**与其 span 相交的第一门非在课课**的配色——
 * 卡片底部 1/4 色条（见 CourseCard），组内再多门也只占 1/4；整卡置灰时不掺彩色。
 * 结果保持原列表顺序。
 *
 * 喂给网格的列表已只剩互不重叠的卡，[findAt] 与 [isContinuationAt] 因此都只看到
 * 本周真正要画的那门；被盖住的交替课靠色条与编辑选择弹窗触达（见 ScheduleScreen）。
 */
fun List<Course>.resolveWeekCards(): List<Course> {
    val shown = BooleanArray(size)
    for ((_, dayIndices) in mapIndexed { index, course -> course.dayOfWeek to index }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    ) {
        // 已显示卡占用的节次范围；新卡与任一范围相交即视为重叠
        val occupied = mutableListOf<IntRange>()
        fun tryShow(index: Int) {
            val course = this[index]
            val range = course.slotId..(course.slotId + course.span - 1)
            if (occupied.none { range.first <= it.last && it.first <= range.last }) {
                occupied += range
                shown[index] = true
            }
        }
        dayIndices.filter { this[it].active }.forEach(::tryShow)
        dayIndices.filterNot { this[it].active }.forEach(::tryShow)
    }
    return mapIndexed { index, course ->
        course.takeIf { shown[index] }?.let { card ->
            if (!card.active) {
                card
            } else {
                val bar = firstOrNull { other ->
                    !other.active && other.dayOfWeek == card.dayOfWeek &&
                        other.slotId <= card.slotId + card.span - 1 &&
                        card.slotId <= other.slotId + other.span - 1
                }?.color
                card.copy(alternateBar = bar)
            }
        }
    }.filterNotNull()
}

/** 查找某格起始的课程（解析后各卡互不重叠，同一格至多一张）；跨节课程只在起始格命中 */
fun List<Course>.findAt(dayOfWeek: Int, slotId: Int): Course? =
    firstOrNull { it.dayOfWeek == dayOfWeek && it.slotId == slotId }

/**
 * 被跨节课覆盖但非其起始格：渲染层既不画课卡也不画添加卡
 * （跨节卡由 WeekPage 的叠加层绘制，见 CourseGrid）。
 */
fun List<Course>.isContinuationAt(dayOfWeek: Int, slotId: Int): Boolean =
    any {
        it.dayOfWeek == dayOfWeek && it.span > 1 &&
            slotId in (it.slotId + 1)..(it.slotId + it.span - 1)
    }
