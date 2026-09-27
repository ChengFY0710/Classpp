package com.fangyi.classpp.ui.schedule

import java.util.Calendar
import java.util.Date

/** 卡片渲染所需的最小课程字段；持久化与周次规律后续阶段再加 */
data class Course(
    val name: String,
    val teacher: String,
    /** 上课地点，如 "@学武楼 C201" */
    val location: String,
    /** 星期：1 = 周一 … 5 = 周五 */
    val dayOfWeek: Int,
    /** 所属节次 id（本阶段不支持跨节次） */
    val slotId: Int,
    val color: CourseColor,
    /** 本周是否上课；false 时卡片按规格置灰（底 #cbcbcb、课名 #737a83） */
    val active: Boolean = true,
)

/** 一个固定节次；起止时间直接以卡片上显示的字符串形式保存 */
data class TimeSlot(
    val id: Int,
    val startTime: String,
    val endTime: String,
)

/** 课程卡片配色；映射到 Color 收在 CourseCard.kt，模型保持零 Compose 依赖 */
enum class CourseColor { Blue, Green, Greentwo , Yellow, Orange, Purple, Teal, Pink }

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

/** 第 [week] 周周一至周五的日期（周数从 1 开始） */
fun datesForWeek(week: Int): List<Date> {
    val base = Calendar.getInstance().apply {
        time = TermStart
        add(Calendar.DAY_OF_YEAR, (week - 1) * 7)
    }
    return (0..4).map { day ->
        (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, day) }.time
    }
}

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
    // 第 1 节 8:00-9:40
    Course("体育舞蹈-拉丁舞", "XX老师", "@爱秋体育馆", 2, 1, CourseColor.Pink),
    Course("微积分 I-2", "XX老师", "@学武楼 C201", 3, 1, CourseColor.Green),
    Course("微积分 I-2", "XX老师", "@学武楼 C201", 5, 1, CourseColor.Green),
    // 第 2 节 10:10-11:50
    Course("微积分 I-2", "XX老师", "@学武楼 C201", 1, 2, CourseColor.Green),
    Course("电路原理", "XX老师", "@学武楼 C404", 2, 2, CourseColor.Yellow),
    Course("概率统计", "XX老师", "@学武楼 C404", 3, 2, CourseColor.Blue),
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

/** 查找某格的课程（同格冲突时取第一门，mock 数据保证唯一） */
fun List<Course>.findAt(dayOfWeek: Int, slotId: Int): Course? =
    firstOrNull { it.dayOfWeek == dayOfWeek && it.slotId == slotId }
